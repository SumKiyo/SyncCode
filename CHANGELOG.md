# Changelog

本文件记录 SyncCode 跨端验证码极速同步系统从零到一的全部技术迭代。

---

## v1.2.1 (2026-06-20) — 热修复补丁

### Bug 修复

- **修复**：修复了 Android 端环境变量（`BuildConfig`）常量解析导致的崩溃问题。`const val` 引用 `BuildConfig` 字段在编译期解析失败，导致 `API_URL` 和 `API_SECRET` 变为空串，所有网络请求静默失败。
- **架构优化**：彻底重构了数据持久化逻辑，将本地数据库（Room）写入与异步网络上报解耦。拆出独立 `saveToLocalDatabase()` 方法在网络请求前优先执行，确保网络请求异常时本地验证码记录仍能 100% 可靠留存。

### 工程配置

- APK 产物自动命名：`SyncCode-v{versionName}.apk`（通过 `applicationVariants` 动态重命名）

### 版本

- Android: `versionCode` 6, `versionName` "1.2.1"

---

## v1.2.0 (2026-06-20) — PC 端桌面级升级

### 变更概览

功能新增：PC 端新增系统托盘 (System Tray) 驻留功能，支持通过右键菜单优雅退出；支持打包为独立 `.exe` 客户端。

### 详细变更

| 端 | 变更 |
|------|------|
| PC 端 | 引入 `pystray` 系统托盘，后台轮询移至守护线程，主线程托管托盘图标 |
| PC 端 | 使用 `Pillow` 内存绘制托盘图标（蓝色 Indigo 圆角方块 + 白色 "S"），无外部文件依赖 |
| PC 端 | 托盘右键菜单"退出 (Exit)"，通过 `threading.Event` 安全通知轮询线程退出 |
| PC 端 | 新增 `pyinstaller` 支持，`-F -w` 打包为无控制台窗口的单文件 `.exe` |
| 工程 | `requirements.txt` 追加 `pystray` / `Pillow` / `pyinstaller` |

### 版本

- Android: `versionCode` 5, `versionName` "1.2.0"

---

## v1.1.2 (2026-06-20) — 安全脱敏重构

### 变更概览

安全更新：将全端硬编码的密钥和 Token 抽离为本地环境变量，提升系统开源部署的安全性。

### 详细变更

| 端 | 变更 |
|------|------|
| PC 端 | 引入 `python-dotenv`，创建 `.env.example` / `.env`，`API_BASE_URL` / `API_SECRET` / `POLL_INTERVAL` 均从环境变量读取 |
| Android 端 | `API_URL` / `API_SECRET` 从 `local.properties` 注入 `BuildConfig`，`SmsReceiver.kt` 不再硬编码 |
| Worker 端 | `API_SECRET` / `UPSTASH_URL` / `UPSTASH_TOKEN` 改为从 `env` 绑定读取，`wrangler.jsonc` 声明 `vars`，新增 `.dev.vars.example` |
| 仓库 | 根目录 `.gitignore` 拦截 `.env`、`.dev.vars`、`local.properties`；三端各提供 `.example` 模板文件 |

### 版本

- `versionCode` 4, `versionName` "1.1.2"

---

## v1.1.1 (2026-06-20) — 热修复补丁

### Bug 修复

| 问题 | 修复 |
|------|------|
| 历史记录相对时间不刷新（"刚刚"冻结） | Adapter 新增 `Handler` 定时器，每 30s 刷新可见 item 的时间文本 |
| Payload 刷新逻辑导致重复 `companion object` 编译失败 | 合并 `PAYLOAD_TIME` 常量到 DiffCallback 的 companion object，删除多余结构 |
| 合并后类体闭合 `}` 缺失 | 补回 `VerificationCodeAdapter` 的类体结束括号 |

### 版本

- `versionCode` 3, `versionName` "1.1.1"

---

## v1.1 (2026-06-20) — Android 端体验升级

### 变更概览

| 组件 | 版本 | 变更说明 |
|------|------|----------|
| Android App | 1.1 | 引入 Room 本地持久化、UI 全面重构、自定义矢量图标 |

### 详细变更

#### 1. 本地持久化 (Room/SQLite)

- 新增 `data/VerificationCode` Entity — 存储应用名、验证码、短信全文、时间戳
- 新增 `data/VerificationCodeDao` — 插入、全量查询（按时间倒序）、计数、清空
- 新增 `data/AppDatabase` — Room 数据库（单例，Application 作用域）
- `SmsReceiver` 在云端推送成功后自动将记录写入 Room，不设数量上限，完全依赖硬盘存储
- 依赖：Room 2.6.1 + KSP 2.1.0

#### 2. UI 全面重构

- **头部状态卡**：深色渐变背景，展示 SyncCode 品牌标识、运行状态指示灯（绿色圆点 + "监听中"）、实时同步计数
- **历史记录列表**：`RecyclerView` + `MaterialCardView` 卡片式布局
  - 应用名（粗体） + 相对时间（刚刚 / N 分钟前 / N 小时前）
  - 验证码大字高亮（28sp 等宽字体，靛蓝色）
  - 短信全文预览（灰色小字，最多 2 行）
  - 点击卡片复制验证码，长按复制全文
- **空状态**：无记录时显示 logo 水印 + 引导文案
- 配色方案：深邃靛蓝主色调（#4F46E5），搭配翠绿强调色（#34D399），Material 3 设计语言

#### 3. 矢量图标

- 新增 `drawable/ic_sync_logo.xml` — 跨端同步科技感图标（双设备 + 双向箭头 + 锁/验证码盾牌）
- 更新 `ic_launcher_foreground.xml` — 自适应图标使用 SyncCode 品牌设计
- 新增 `drawable/status_dot_green.xml` — 运行状态指示灯

#### 4. 工程配置

- `versionCode` 2, `versionName` "1.1"
- 开启 ViewBinding（`buildFeatures { viewBinding = true }`）
- 引入 KSP 注解处理器（Room 编译器）
- DAO 使用非 `suspend` 方法 + 后台线程调用（规避 KSP 泛型通配符冲突）
- 引入 `lifecycle-runtime-ktx` / `lifecycle-viewmodel-ktx`（协程支持）
- 注册 `android:name=".App"` Application 类
- `gradle.properties` 追加 `android.disallowKotlinSourceSets=false`（修复 AGP 9.x + KSP 兼容性）

---

## v1.0 (2026-06-20) — 首个正式版本

三端架构全部就绪，端到端延迟 < 3 秒。存储引擎历经三次迭代，最终稳定于 Upstash Redis。

### 三端组件

| 组件 | 版本 | 说明 |
|------|------|------|
| Android App | 1.0 | 短信监听 → 正则提取 → OkHttp 上报 |
| Cloudflare Worker | 1.0 | 鉴权 → Upstash Redis 存取 → 阅后即焚 |
| PC 接收端 | 1.0 | 1.5s 轮询 → 剪贴板 + 桌面通知 |

---

### 存储引擎迭代历程

v1.0 开发过程中，云端存储方案经历了三次演进：

#### 第一阶段：内存变量（已废弃）

Worker 全局变量 `let storedCode` 暂存验证码。

- **优点**：零配置，代码最简单
- **问题**：Worker 边缘节点间不共享内存，POST 和 GET 可能命中不同节点，出现"数据孤岛"导致 PC 端拉不到数据

#### 第二阶段：Cloudflare KV（已废弃）

通过 `wrangler kv namespace create` 创建 KV 数据库，`env.SMS_KV.put/get/delete` 替代内存变量。

- **优点**：消除数据孤岛，所有边缘节点共享同一份数据
- **问题**：需额外 Wrangler 绑定配置；KV 无内建 TTL，需手动删除过期数据；部署复杂度增加

#### 第三阶段：Upstash Redis（当前方案）

移除 KV 绑定，Worker 通过 `fetch` 直接调用 Upstash REST API：

```
POST /api/push → SETEX latest_code 120 <json>   # 写入 + 120 秒自动过期
GET  /api/pull → GET latest_code → DEL latest_code  # 读取 + 立即删除
```

- **优点**：零绑定依赖（纯 HTTP 调用）；SETEX 内建 TTL 自动过期；单节点 Redis 全局一致
- **配置**：`UPSTASH_URL` + `UPSTASH_TOKEN` 写入代码顶部常量

---

### Android 端

`SmsReceiver.kt` 核心设计：

| 步骤 | 实现 |
|------|------|
| 拦截广播 | `BroadcastReceiver` 监听 `SMS_RECEIVED`，`priority="999"` |
| 关键字过滤 | 仅处理含"验证码/code/登录/注册/verify"的短信 |
| 提取 app | 正则 `【(.*?)】` 或 `[(.*?)]`，取不到则 `"未知应用"` |
| 提取 code | 正则 `(?<!\d)\d{4,6}(?!\d)`，取不到则为空 |
| 上报 | OkHttp `enqueue` 异步 POST，JSON body `{"app","code","raw_text"}` |

代码草稿：`android-draft.txt`

---

### PC 端

`pc-receiver/main.py` 核心设计：

| 步骤 | 实现 |
|------|------|
| 轮询 | 每 1.5 秒 GET `/api/pull` |
| 异常处理 | 超时/断网/DNS 失败均静默重试，不崩溃 |
| 去重 | `last_code` 缓存，避免重复通知 |
| 剪贴板 | `pyperclip.copy(code)`，直接 Ctrl+V 粘贴 |
| 桌面通知 | `plyer` 弹出 Windows 通知，显示应用名和验证码 |

---

### 技术决策记录

| 决策 | 选择 | 理由 |
|------|------|------|
| 云端平台 | Cloudflare Workers | 零运维、全球边缘部署、免费额度充足 |
| 最终存储 | Upstash Redis | 全局一致、内建 TTL、零绑定 |
| PC 端语言 | Python 3 | 快速开发、pyperclip/plyer 生态成熟 |
| Android 端语言 | Kotlin | Android 官方推荐，协程友好 |
| 鉴权方式 | 静态 Bearer Token | 极简实现，适合个人工具场景 |
| API 设计 | 阅后即焚 | 验证码一次性消费，保障隐私 |
| 轮询频率 | 1.5 秒 | 日均 ~57,600 次，Cloudflare 免费额度内 |

---

### 已知限制

- Bearer Token 为静态密钥，非生产级安全方案
- Android 端部分国产 ROM 需手动开启短信权限 + 关闭电池优化
- PC 端仅支持 Windows（`plyer` 跨平台但 `pyperclip` 在 macOS/Linux 需额外依赖）