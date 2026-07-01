package com.example.synccode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.example.synccode.data.VerificationCode
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SyncCode"

        // ============================================================
        // 配置（从 local.properties → BuildConfig 注入，非 const 避免编译期常量解析失败）
        // ============================================================
        private val API_URL = BuildConfig.API_URL
        private val API_SECRET = BuildConfig.API_SECRET

        // ============================================================
        // 正则规则
        // ============================================================

        // 提取【中文方括号】或 [英文方括号] 中的内容 → 作为 app（应用名）
        private val APP_REGEX = Regex("""【(.*?)】|\[(.*?)]""")

        // 提取 4-6 位连续数字 → 作为 code（验证码）
        // (?<!\d) 和 (?!\d) 确保不匹配更长数字串的子串
        private val CODE_REGEX = Regex("""(?<!\d)\d{4,6}(?!\d)""")

        // 验证码短信关键词
        private val SMS_KEYWORDS = listOf("验证码", "code", "登录", "注册", "verify", "OTP")

        // 误判排除：订单号/运单号/金额/电话号码等
        private val EXCLUDE_PATTERNS = listOf(
            Regex("""订单号|运单号|快递单号|订单编号|物流单号|挂号单|提单号"""),
            Regex("""￥\s*\d+|¥\s*\d+|\d+\s*元|\d+\.\d{2}\s*元"""),
            Regex("""\d{3,4}[-\s]\d{3,4}[-\s]\d{3,4}"""),
            Regex("""\d{7,}""")  // 7 位以上连续数字必不是验证码
        )

        // 数字与关键字必须在 40 字符以内才算验证码（避免"标题含关键字 + 正文含订单号"的误判）
        private const val PROXIMITY_RANGE = 40
    }

    // OkHttp 客户端（单例，复用连接池）
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    override fun onReceive(context: Context, intent: Intent) {
        // 校验广播动作
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        // 从 Intent 中解析短信内容
        val rawText = extractSmsBody(intent) ?: return
        if (rawText.isBlank()) return

        Log.d(TAG, "收到短信: $rawText")

        // 关键字过滤：不是验证码短信就跳过
        if (!isVerificationSms(rawText)) {
            Log.d(TAG, "非验证码短信，已忽略")
            return
        }

        // 正则提取 app 和 code
        val app = extractApp(rawText)
        val code = extractCode(rawText)

        Log.d(TAG, "提取结果 → app: $app, code: $code")

        // 即使 code 为空也上报（方便排查问题）
        pushToCloud(context, app, code, rawText)
    }

    /**
     * 从 Intent 中提取短信正文（支持多段 SMS 拼接）
     */
    private fun extractSmsBody(intent: Intent): String? {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return null
        return messages.joinToString("") { it.displayMessageBody ?: "" }
    }

    /**
     * 判断是否为验证码短信（关键字 + 数字邻近度 + 排除误判）
     */
    private fun isVerificationSms(text: String): Boolean {
        // 1. 排除误判模式
        if (EXCLUDE_PATTERNS.any { it.containsMatchIn(text) }) {
            Log.d(TAG, "命中排除模式，已忽略")
            return false
        }

        // 2. 查找所有 4-6 位数字
        val codeMatches = CODE_REGEX.findAll(text).toList()
        if (codeMatches.isEmpty()) return false

        // 3. 找到离关键字最近的数字，必须在 PROXIMITY_RANGE 范围内
        val keywordMatch = SMS_KEYWORDS.firstOrNull { text.contains(it, ignoreCase = true) }
            ?: return false

        val keywordPos = text.indexOf(keywordMatch, ignoreCase = true)
        val anyCodeNearKeyword = codeMatches.any { match ->
            kotlin.math.abs(match.range.first - keywordPos) <= PROXIMITY_RANGE
        }

        if (!anyCodeNearKeyword) {
            Log.d(TAG, "数字距离关键字过远（>${PROXIMITY_RANGE}字符），已忽略")
        }
        return anyCodeNearKeyword
    }

    /**
     * 从短信文本中提取 app（应用名）
     * 匹配 【xxx】 或 [xxx]，取第一个匹配结果
     */
    private fun extractApp(text: String): String {
        val match = APP_REGEX.find(text) ?: return "未知应用"
        // groupValues[1] 是【xxx】的捕获，groupValues[2] 是 [xxx] 的捕获
        return match.groupValues[1].ifEmpty {
            match.groupValues[2].ifEmpty { "未知应用" }
        }
    }

    /**
     * 从短信文本中提取 code（验证码）
     * 匹配 4-6 位连续数字，取第一个匹配结果
     */
    private fun extractCode(text: String): String {
        return CODE_REGEX.find(text)?.value ?: ""
    }

    /**
     * 使用 OkHttp 异步发送 POST 请求到云端 Worker。
     * 无论云端上报成功与否，本地 Room 数据库必须保存记录。
     */
    private fun pushToCloud(context: Context, app: String, code: String, rawText: String) {
        // 1. 本地数据库优先保存（不依赖网络请求结果）
        saveToLocalDatabase(context, app, code, rawText)

        // 2. 异步上报云端
        try {
            val json = JSONObject().apply {
                put("app", app)
                put("code", code)
                put("raw_text", rawText)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = json.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer $API_SECRET")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "上报失败: ${e.message}")
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    if (response.isSuccessful) {
                        Log.i(TAG, "上报成功 → [$app] $code")
                    } else {
                        Log.w(TAG, "上报被拒 → HTTP ${response.code}: ${response.body?.string()}")
                    }
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "构建网络请求失败: ${e.message}")
        }
    }

    /**
     * 将验证码记录写入本地 Room 数据库（独立方法，确保一定执行）
     */
    private fun saveToLocalDatabase(context: Context, app: String, code: String, rawText: String) {
        try {
            val appContext = context.applicationContext as App
            val dao = appContext.database.verificationCodeDao()
            val record = VerificationCode(
                app = app,
                code = code,
                rawText = rawText,
                timestamp = System.currentTimeMillis()
            )
            Thread {
                try {
                    dao.insert(record)
                    Log.d(TAG, "已写入本地数据库")
                } catch (e: Exception) {
                    Log.e(TAG, "数据库写入失败: ${e.message}")
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "获取数据库实例失败: ${e.message}")
        }
    }
}