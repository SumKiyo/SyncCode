# SyncCode 跨端验证码极速同步系统

[![Architecture](https://img.shields.io/badge/Architecture-Three--Tier-blue)](#系统架构)
[![Worker](https://img.shields.io/badge/Cloudflare-Workers-orange)](https://workers.cloudflare.com/)
[![Redis](https://img.shields.io/badge/Storage-Upstash_Redis-green)](https://upstash.com/)
[![Python](https://img.shields.io/badge/PC-Python_3-yellow)](https://www.python.org/)
[![Android](https://img.shields.io/badge/Android-Kotlin-brightgreen)](https://kotlinlang.org/)

> 解决电脑端登录时手机不在身边或不便查看的痛点，实现 Android 端接收到的验证码短信在 **1.5 秒内**自动同步至电脑端系统剪贴板，并提供视觉通知。

---

## 目录

- [系统架构](#系统架构)
- [数据流转](#数据流转)
- [项目结构](#项目结构)
- [快速开始](#快速开始)
  - [1. 部署 Cloudflare Worker](#1-部署-cloudflare-worker)
  - [2. 运行 PC 接收端](#2-运行-pc-接收端)
  - [3. 部署 Android 采集端](#3-部署-android-采集端)
- [API 文档](#api-文档)
- [安全设计](#安全设计)
- [性能指标](#性能指标)
- [技术栈](#技术栈)
- [License](#license)

---

## 系统架构

```
┌──────────────────┐     ┌──────────────────────┐     ┌──────────────────┐
│   Android 采集端  │────▶│    Cloudflare Worker  │────▶│   PC 桌面接收端   │
│   (SmsReceiver)  │     │  (sms-worker / 中转)  │     │  (Python 轮询)   │
│                  │     │                      │     │                  │
│  • 监听短信广播   │     │  Upstash Redis       │     │  • 1.5s 轮询     │
│  • 正则提取验证码 │     │  ┌────────────────┐  │     │  • 写入剪贴板     │
│  • OkHttp 上报   │     │  │ SETEX 120s TTL │  │     │  • 桌面通知弹窗   │
└──────────────────┘     │  │ GET + DEL      │  │     └──────────────────┘
                         │  └────────────────┘  │
                         └──────────────────────┘
```

**三端职责：**

| 端点 | 角色 | 核心能力 |
|------|------|----------|
| Android | 采集层 | 监听系统短信广播 → 正则提取 app/code → HTTP POST 上报 |
| Cloudflare Worker | 中转层 | 鉴权校验 → Upstash Redis 读写 → 阅后即焚 |
| PC (Python) | 消费层 | 轮询拉取 → 写入剪贴板 → 桌面通知 |

---

## 数据流转

```
  Android 手机收到验证码短信
           │
           ▼
  SmsReceiver 正则提取
    app: "阿里云"
    code: "123456"
           │
           ▼
  POST /api/push ──────────────────────────────────────┐
  Authorization: Bearer <SECRET>                       │
  {"app":"阿里云","code":"123456","raw_text":"..."}     │
           │                                           │
           ▼                                           ▼
  ┌──────────────────────────────────────────────────────┐
  │              Upstash Redis (latest_code)              │
  │  SETEX latest_code 120 {"app":"阿里云","code":...}   │
  │  2 分钟无人拉取 → 自动过期清除                         │
  └──────────────────────┬───────────────────────────────┘
                         │
                         ▼
  PC 端每 1.5 秒轮询 ─────────────────────────────────┐
  GET /api/pull                                        │
  Authorization: Bearer <SECRET>                       │
           │                                           │
           ▼                                           │
  ┌──────────────────────────────────────────────────────┐
  │  ① GET latest_code → 读取到数据                      │
  │  ② DEL latest_code → 阅后即焚                        │
  │  ③ pyperclip.copy(code) → 写入剪贴板                 │
  │  ④ plyer 通知 → 桌面右下角弹窗                        │
  └──────────────────────────────────────────────────────┘
```

---

## 项目结构

```
SyncCode/
├── sms-worker/                  # Cloudflare Worker（云端中转站）
│   ├── src/
│   │   └── index.ts             # Worker 核心逻辑
│   ├── wrangler.jsonc           # Wrangler 配置
│   ├── package.json
│   └── tsconfig.json
│
├── pc-receiver/                 # PC 桌面接收端
│   ├── main.py                  # 轮询脚本
│   └── requirements.txt         # Python 依赖
│
├── android-app/                 # Android 采集端（完整工程）
│   ├── app/
│   │   ├── build.gradle.kts     # 应用级构建配置（Room + KSP）
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── java/com/example/synccode/
│   │       │   ├── App.kt                      # Application（Room 初始化）
│   │       │   ├── MainActivity.kt             # 主界面（状态卡 + 历史列表）
│   │       │   ├── SmsReceiver.kt              # 短信广播接收 + 云端上报 + Room 写入
│   │       │   ├── data/
│   │       │   │   ├── VerificationCode.kt     # Room Entity
│   │       │   │   ├── VerificationCodeDao.kt  # Room DAO
│   │       │   │   └── AppDatabase.kt          # Room Database
│   │       │   └── ui/
│   │       │       └── VerificationCodeAdapter.kt  # RecyclerView 适配器
│   │       └── res/
│   │           ├── drawable/
│   │           │   ├── ic_sync_logo.xml         # 品牌矢量图标
│   │           │   └── status_dot_green.xml     # 状态指示灯
│   │           ├── layout/
│   │           │   ├── activity_main.xml        # 主界面布局
│   │           │   └── item_verification_code.xml # 卡片列表项
│   │           └── values/
│   │               ├── colors.xml
│   │               ├── strings.xml
│   │               └── themes.xml
│   ├── build.gradle.kts          # 根构建配置
│   ├── settings.gradle.kts
│   └── gradle/
│       └── libs.versions.toml    # 版本目录（Room 2.6.1 / KSP 2.1.0）
│
├── CHANGELOG.md                 # 版本更新日志
├── System_Design.md             # 系统设计文档
├── CLAUDE.md                    # 项目开发规范
└── README.md                    # 本文件
```

---

## 快速开始

### 1. 部署 Cloudflare Worker

**前置条件：** Node.js 18+、Cloudflare 账号

```bash
cd sms-worker

# 安装依赖
npm install

# 登录 Cloudflare（首次需要）
npx wrangler login

# 创建 Upstash Redis 数据库
# 访问 https://console.upstash.com/ → 创建 Redis → 获取 REST API URL 和 Token
# 将 URL 和 Token 填入 src/index.ts 顶部的 UPSTASH_URL 和 UPSTASH_TOKEN

# 修改 API_SECRET 为你自己的强密码
# 编辑 src/index.ts 第 17 行

# 部署
npx wrangler deploy
```

部署成功后会输出 Worker 地址，例如 `https://sms-worker.xxx.workers.dev`。

> **安全提示：** 请务必修改 `src/index.ts` 中的 `API_SECRET` 为强密码，并妥善保管你的 Upstash Token。

### 2. 运行 PC 接收端

**前置条件：** Python 3.9+、Windows 10/11

```bash
cd pc-receiver

# 安装依赖
pip install -r requirements.txt

# 修改配置（如 Worker 地址或 API_SECRET 有变化）
# 编辑 main.py 第 29-30 行

# 启动
python main.py
```

终端输出：
```
==================================================
  SyncCode PC 接收端已启动
  轮询地址: https://sms-worker.xxx.workers.dev/api/pull
  轮询间隔: 1.5 秒
  按 Ctrl+C 退出
==================================================
```

**验证方法：** 脚本运行期间，向 Worker 推送一条测试数据：

```bash
curl -X POST https://<your-worker>.workers.dev/api/push \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <your-secret>" \
  -d '{"app":"测试","code":"998877","raw_text":"【测试】验证码998877"}'
```

此时电脑右下角弹出通知，按 `Ctrl+V` 即可粘贴出 `998877`。

**开机自启（可选）：**
1. `Win+R` → 输入 `shell:startup`
2. 将 `main.py` 的快捷方式拖入该文件夹

### 3. 部署 Android 采集端

1. 用 Android Studio 打开 `android-app/` 目录
2. 等待 Gradle 同步完成（首次需下载 Room/KSP 等依赖）
3. 修改 `SmsReceiver.kt` 中的 `API_URL` 和 `API_SECRET` 为你的 Worker 地址和密钥
4. Build → Build APK，安装到手机

**关键后续操作（手机端）：**
- 在系统设置中为该 App 开启"短信权限"
- 关闭电池优化 / 允许后台运行
- 锁定最近任务（防止被系统清理）

**v1.1 新增功能：**
- 本地历史记录：所有验证码自动存入 SQLite，支持无限量回溯
- 现代化 UI：深色状态卡 + 卡片式历史列表，点击即可复制验证码

---

## API 文档

### Base URL

```
https://<your-worker-name>.<your-subdomain>.workers.dev
```

### 鉴权

所有 API 请求必须在 Header 中携带：

```
Authorization: Bearer <API_SECRET>
```

### POST /api/push — 上报验证码

Android 端调用，将验证码写入云端。

**Request**

```http
POST /api/push
Content-Type: application/json
Authorization: Bearer <API_SECRET>

{
  "app": "阿里云",
  "code": "123456",
  "raw_text": "【阿里云】您的验证码是 123456，5分钟内有效。"
}
```

**Response**

| 状态码 | 含义 | Body |
|--------|------|------|
| 200 | 成功 | `{"status":"success","msg":"Code stored"}` |
| 400 | 参数缺失 | `{"status":"error","msg":"Missing required fields: ..."}` |
| 401 | 鉴权失败 | `{"status":"error","msg":"Unauthorized"}` |

### GET /api/pull — 拉取验证码

PC 端调用，获取并消费验证码（阅后即焚）。

**Request**

```http
GET /api/pull
Authorization: Bearer <API_SECRET>
```

**Response**

有数据（200）：

```json
{
  "has_data": true,
  "data": {
    "app": "阿里云",
    "code": "123456",
    "raw_text": "【阿里云】您的验证码是 123456，5分钟内有效。"
  }
}
```

无数据（200）：

```json
{
  "has_data": false
}
```

---

## 安全设计

| 层级 | 措施 | 说明 |
|------|------|------|
| 传输层 | HTTPS | 所有通信加密，防止中间人窃听 |
| 应用层 | Bearer Token 鉴权 | 静态 Secret 校验，阻止未授权访问 |
| 存储层 | 阅后即焚 + 2 分钟 TTL | 验证码拉取后立即删除，超时自动清除 |
| 客户端 | 关键字过滤 | Android 端仅上报验证码短信，减少无效请求 |

> ⚠️ 当前鉴权为静态 Token 方案，适合个人/小团队使用。如需更高安全性，建议升级为 JWT 或 Cloudflare Access。

---

## 性能指标

| 指标 | 数值 |
|------|------|
| 端到端延迟（理论最差） | ~1.5s（轮询间隔）+ 网络 RTT |
| 端到端延迟（实测） | < 3s |
| PC 端日请求量 | ~57,600 次（1.5s 间隔） |
| Cloudflare 免费额度 | 10 万次/天 ✅ |
| Upstash 免费额度 | 10,000 条/天 ✅ |
| Worker 冷启动 | < 10ms |
| Redis 读取延迟 | < 1ms（Upstash 全球边缘） |

---

## 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 云端 | Cloudflare Workers (TypeScript) | Wrangler 4.x |
| 存储 | Upstash Redis (REST API) | — |
| 桌面端 | Python 3 + requests + pyperclip + plyer | 3.9+ |
| 移动端 | Kotlin + OkHttp + Android SDK | API 24+ |

---

## License

MIT License

---

**SyncCode** — 让验证码飞一会儿 ✈️