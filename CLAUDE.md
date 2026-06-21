# SyncCode 项目全局开发规范

## 1. 核心目录结构（严禁混淆）
- `/android-app`: Android 端原生工程 (Kotlin/XML)
- `/pc-receiver`: PC 端 Python 轮询与剪贴板脚本
- `/sms-worker`: 云端 Cloudflare Worker 中转服务 (TypeScript)

## 2. 自动化文档同步指令
任何时候，只要你修改了上述三个核心目录中的业务代码、架构配置或修复了 Bug，**必须**在操作完成的最后一步，自动去更新根目录下的 `CHANGELOG.md` 和 `README.md`（如有必要）。
- 更新要求：在 `CHANGELOG.md` 顶部追加最新的时间戳和变动说明，保持专业、简明的开发者口吻。
- 执行原则：不需要询问用户是否更新文档，这是强制的自动收尾流程。

## 3. 自动化构建规范 (Build Workflow)
接收到"打包"、"生成可执行程序"等构建指令时，必须将其作为独立任务，按以下流程执行：
1. **版本提取**：读取 `android-app/app/build.gradle.kts` 中的 `versionName` 值。
2. **动态打包**：进入 `pc-receiver` 目录，严格按提取的版本号执行打包，严禁生成默认命名。
   - 必须使用的执行命令：`pyinstaller -F -w -n "SyncCode-v[versionName]" main.py`
3. **交付输出**：打包结束后，输出 `dist` 目录下的 `.exe` 路径。