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
        // 配置（从 local.properties 注入，不再硬编码）
        // ============================================================
        private const val API_URL = BuildConfig.API_URL
        private const val API_SECRET = BuildConfig.API_SECRET

        // ============================================================
        // 正则规则
        // ============================================================

        // 提取【中文方括号】或 [英文方括号] 中的内容 → 作为 app（应用名）
        private val APP_REGEX = Regex("""【(.*?)】|\[(.*?)]""")

        // 提取 4-6 位连续数字 → 作为 code（验证码）
        // (?<!\d) 和 (?!\d) 确保不匹配更长数字串的子串
        private val CODE_REGEX = Regex("""(?<!\d)\d{4,6}(?!\d)""")

        // 判断是否为验证码短信的关键字（过滤无关短信，减少无效请求）
        private val SMS_KEYWORDS = listOf("验证码", "code", "登录", "注册", "verify")
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
     * 判断是否为验证码短信（关键字匹配）
     */
    private fun isVerificationSms(text: String): Boolean {
        return SMS_KEYWORDS.any { text.contains(it, ignoreCase = true) }
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
     * 使用 OkHttp 异步发送 POST 请求到云端 Worker
     * 成功后自动写入 Room 本地数据库
     */
    private fun pushToCloud(context: Context, app: String, code: String, rawText: String) {
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

                    // 写入本地 Room 数据库（异步线程，不阻塞 OkHttp 回调）
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
                            dao.insert(record)
                        }.start()
                        Log.d(TAG, "已写入本地数据库")
                    } catch (e: Exception) {
                        Log.e(TAG, "写入数据库失败: ${e.message}")
                    }
                } else {
                    Log.w(TAG, "上报被拒 → HTTP ${response.code}: ${response.body?.string()}")
                }
                response.close()
            }
        })
    }
}