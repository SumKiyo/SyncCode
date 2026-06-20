一、 需求分析 (Requirement Analysis)
1. 核心目标
解决电脑端登录时手机不在身边或不便查看的痛点，实现 Android 端接收到的验证码短信在 3 秒内自动同步至电脑端系统剪贴板，并提供视觉通知。

2. 功能性需求

Android 端 (采集层)： 能够静默监听系统新短信，自动识别是否包含验证码；提取“应用名”、“验证码”和“原始文本”；通过网络上报至云端。

云端服务 (中转层)： 提供数据接收与下发接口；具备“阅后即焚”机制，确保验证码不被持久化存储。

PC 端 (展示层)： 后台静默运行；定时轮询云端接口；获取到新数据后，触发系统级桌面通知，并将纯数字验证码写入剪贴板。

3. 非功能性需求 (关键约束)

安全性： 公网接口必须增加简易鉴权（Token/Secret），防止接口被恶意扫描和恶意投毒。

实时性： 验证码从手机发出到电脑剪贴板的端到端延迟应控制在 5 秒以内。

极简性： 手机端无需复杂 UI，申请最小化权限；云端零运维成本（Serverless）。

二、 系统架构与技术栈设计
系统采用 C/S 架构的变种（双客户端 + 弱服务端）：

Android 采集端： Kotlin/Java + Android 原生 SDK (基于 BroadcastReceiver 监听 android.provider.Telephony.SMS_RECEIVED 广播) + OkHttp (网络请求)。

云端中转站： Cloudflare Workers (JavaScript/TypeScript) + 全局内存变量（由于只需要暂存几秒钟的单条数据，甚至可以不引入 KV 数据库，直接用 Worker 实例的内存空间或极其轻量的缓存机制）。

PC 接收端： Python 3 + requests (网络轮询) + plyer (系统通知) + pyperclip (剪贴板操作)。

三、 接口文档 (API Specification)
安全性前置说明： 由于 Worker 部署后对外暴露，我们将采用一个静态的 API_SECRET（自定义的一串复杂字符串）放在 HTTP Header 中进行极简鉴权。

Base URL: https://<your-worker-name>.<your-subdomain>.workers.dev

1. 上报验证码接口 (Android端调用)
路径: /api/push

方法: POST

请求头 (Headers):

Content-Type: application/json

Authorization: Bearer <API_SECRET>

请求体 (Request Body):

JSON
{
  "app": "阿里云",
  "code": "123456",
  "raw_text": "【阿里云】您的验证码是 123456，5分钟内有效。"
}
响应 (Response):

成功 (200): {"status": "success", "msg": "Code stored"}

失败 (401): {"status": "error", "msg": "Unauthorized"}

2. 获取并消费验证码接口 (PC端调用)
路径: /api/pull

方法: GET

请求头 (Headers):

Authorization: Bearer <API_SECRET>

逻辑约束 (阅后即焚): 服务端收到此请求并返回数据的同时，必须将服务端暂存的该条数据清空。

响应 (Response):

成功且有新数据 (200):

JSON
{
  "has_data": true,
  "data": {
    "app": "阿里云",
    "code": "123456",
    "raw_text": "【阿里云】您的验证码是 123456，5分钟内有效。"
  }
}
成功但无新数据 (200): {"has_data": false}

失败 (401): {"status": "error", "msg": "Unauthorized"}

四、 核心提取逻辑 (正则表达式规约)
为了让 Android 端能够准确打包数据，需采用以下正则策略（此策略也可直接写在需求里让 AI 生成代码）：

判定是否为验证码短信： 检查 raw_text 是否包含关键字：["验证码", "code", "登录", "注册"]，若都不包含则直接丢弃，不发网络请求。

提取 app (应用名)： 匹配中文短信常用的签名符号。正则：【(.*?)】 或 \[(.*?)\]。若提取失败，默认赋值为 "未知应用"。

提取 code (验证码)： 匹配连续的 4 到 6 位数字。正则：(?<!\d)\d{4,6}(?!\d)。若提取失败，默认赋值为 ""（空字符串）。

五、 开发与实施大纲 (Roadmap)
我们接下来将分为四个阶段推进，这对应了你将要执行的具体操作：

阶段一：中转基建 (Cloudflare Worker 部署)

创建 Worker 项目。

实现 /api/push 和 /api/pull 路由。

实现 Header 鉴权与内存级别的“阅后即焚”逻辑。

验收标准： 可用 Postman/curl 成功模拟 POST 发送和 GET 提取，第二次 GET 返回无数据。

阶段二：桌面终端 (Python 脚本开发)

编写死循环轮询脚本（休眠 3 秒）。

接入 plyer 和 pyperclip 库。

验收标准： 脚本运行中，用 Postman 向 Worker 推送一条假数据，电脑右下角立即弹窗，且直接 Ctrl+V 能粘贴出代码。

阶段三：移动端攻坚 (Android App 开发)

IDEA 初始化工程，配置基础 SDK。

编写 AndroidManifest.xml（网络权限、短信读取/接收权限注册）。

实现 SmsReceiver 并编写正则解析与 OkHttp 请求代码。

阶段四：联调与保活

在手机上安装 App，允许短信权限。

发送真实短信进行端到端测试。

关键后续操作： 在手机系统设置中，为该 App 开启“允许后台运行”、“关闭电池优化”、“锁定最近任务”。