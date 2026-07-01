"""
SyncCode - PC 接收端 (pc-receiver)
跨端验证码极速同步系统 - 桌面终端

功能：
  每 1.5 秒轮询云端 /api/pull 接口，获取 Android 端上报的验证码。
  获取到新验证码后：
    1. 将纯数字验证码写入系统剪贴板（可直接 Ctrl+V 粘贴）
    2. 弹出 Windows 桌面通知（显示应用名和验证码）

v1.2.0 新增：
  系统托盘 (System Tray) 驻留，右键菜单优雅退出。

使用方式：
  python main.py

依赖安装：
  pip install -r requirements.txt
"""

import os
import sys
import time
import subprocess
import threading
import traceback
from io import BytesIO

import requests
import pyperclip
from plyer import notification
from dotenv import load_dotenv
from PIL import Image, ImageDraw
import pystray

# 加载 .env 文件中的环境变量（兼容 PyInstaller 打包路径）
import sys as _sys
if getattr(_sys, 'frozen', False):
    # 打包后的 exe：从 exe 所在目录加载 .env
    _app_dir = os.path.dirname(_sys.executable)
else:
    # 直接运行脚本：从脚本所在目录加载 .env
    _app_dir = os.path.dirname(os.path.abspath(__file__))
load_dotenv(os.path.join(_app_dir, '.env'))

# ============================================================
# 配置常量（从 .env 环境变量读取，不再硬编码）
# ============================================================
API_BASE_URL = os.getenv("API_BASE_URL", "https://sms-worker.example.workers.dev")
API_SECRET = os.getenv("API_SECRET", "")
POLL_INTERVAL = float(os.getenv("POLL_INTERVAL", "1.5"))

# ============================================================
# 图标生成（内存绘制，不依赖外部文件）
# ============================================================

def create_tray_icon_image() -> Image.Image:
    """
    使用 Pillow 在内存中绘制一个 64x64 的托盘图标。
    蓝色底色 + 白色 "S" 字母，纯代码生成，无外部文件依赖。
    """
    size = 64
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    # 蓝色圆角方形背景
    draw.rounded_rectangle(
        [(4, 4), (size - 4, size - 4)],
        radius=14,
        fill=(79, 70, 229, 255)  # Indigo #4F46E5
    )

    # 白色 "S" 字母（用 draw.text 绘制）
    try:
        from PIL import ImageFont
        font = ImageFont.truetype("arial.ttf", 36)
    except (OSError, IOError):
        font = ImageFont.load_default()

    # 居中绘制白色 "S"
    text = "S"
    bbox = draw.textbbox((0, 0), text, font=font)
    text_w = bbox[2] - bbox[0]
    text_h = bbox[3] - bbox[1]
    x = (size - text_w) // 2
    y = (size - text_h) // 2 - 2
    draw.text((x, y), text, fill=(255, 255, 255, 255), font=font)

    return image

# ============================================================
# 核心逻辑
# ============================================================

def pull_code() -> dict | None:
    """
    调用云端 /api/pull 接口拉取验证码。

    返回：
        dict | None  - 有数据时返回 {"app": ..., "code": ..., "raw_text": ...}
                      无数据或出错时返回 None
    """
    try:
        resp = requests.get(
            f"{API_BASE_URL}/api/pull",
            headers={
                "Authorization": f"Bearer {API_SECRET}",
            },
            timeout=10,
        )
        resp.raise_for_status()

        data = resp.json()

        if data.get("has_data") and data.get("data"):
            return data["data"]
        else:
            return None

    except requests.exceptions.Timeout:
        return None
    except requests.exceptions.ConnectionError:
        return None
    except requests.exceptions.RequestException:
        return None
    except Exception:
        return None


def notify_and_copy(app: str, code: str, raw_text: str) -> None:
    """
    将验证码写入剪贴板，并弹出系统桌面通知。
    通知策略：plyer → PowerShell Toast → 控制台输出（逐级降级）
    """
    try:
        pyperclip.copy(code)
        print(f"[OK] 验证码已写入剪贴板: {code}")
    except Exception as e:
        print(f"[WARN] 剪贴板写入失败: {e}")

    # 通知方法 1：plyer（主方案）
    try:
        notification.notify(
            title=f"SyncCode - {app}",
            message=f"验证码: {code}\n{raw_text}",
            app_name="SyncCode",
            timeout=5,
        )
        print(f"[OK] 桌面通知已弹出: [{app}] {code}")
        return
    except Exception as e:
        print(f"[WARN] plyer 通知失败: {e}，尝试 PowerShell 备用方案")

    # 通知方法 2：PowerShell Toast（备选方案）
    try:
        ps_script = f'''
        [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > $null
        $template = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02)
        $textNodes = $template.GetElementsByTagName("text")
        $textNodes.Item(0).AppendChild($template.CreateTextNode("SyncCode - {app}")) > $null
        $textNodes.Item(1).AppendChild($template.CreateTextNode("验证码: {code}")) > $null
        $toast = [Windows.UI.Notifications.ToastNotification]::new($template)
        [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier("SyncCode").Show($toast)
        '''
        subprocess.run(
            ["powershell", "-NoProfile", "-Command", ps_script],
            capture_output=True, timeout=10,
        )
        print(f"[OK] PowerShell 通知已弹出: [{app}] {code}")
        return
    except Exception as e:
        print(f"[WARN] PowerShell 通知也失败: {e}")

    # 通知方法 3：仅控制台（最终兜底）
    print(f"[ALERT] 验证码到达: [{app}] {code} — 通知弹窗不可用")


def run_polling_loop(stop_event: threading.Event) -> None:
    """
    后台轮询主循环（运行在守护线程中）。
    通过 stop_event 接收退出信号。
    """
    print("=" * 50)
    print("  SyncCode PC 接收端已启动")
    print(f"  轮询地址: {API_BASE_URL}/api/pull")
    print(f"  轮询间隔: {POLL_INTERVAL} 秒")
    print("  右键托盘图标可退出")
    print("=" * 50)

    last_code: str | None = None

    while not stop_event.is_set():
        try:
            data = pull_code()

            if data is not None:
                code = data.get("code", "")
                app = data.get("app", "未知应用")
                raw_text = data.get("raw_text", "")

                if code != last_code or last_code is None:
                    notify_and_copy(app, code, raw_text)
                    last_code = code
                else:
                    print(f"[SKIP] 重复验证码，跳过: {code}")

            # 使用短间隔等待，以便能及时响应退出信号
            stop_event.wait(timeout=POLL_INTERVAL)

        except Exception:
            print(f"[ERR] 未预期的错误: {traceback.format_exc()}")
            stop_event.wait(timeout=POLL_INTERVAL)

    print("[BYE] SyncCode 接收端已退出。")


# ============================================================
# 系统托盘
# ============================================================

def create_tray_icon(stop_event: threading.Event) -> pystray.Icon:
    """
    创建系统托盘图标，菜单包含"退出"选项。
    """
    icon_image = create_tray_icon_image()

    def on_exit(icon: pystray.Icon, item):
        """退出菜单回调：发送停止信号，停止托盘图标"""
        print("[INFO] 正在退出...")
        stop_event.set()
        icon.stop()

    menu = pystray.Menu(
        pystray.MenuItem("退出 (Exit)", on_exit),
    )

    return pystray.Icon(
        name="SyncCode",
        icon=icon_image,
        title="SyncCode - 跨端验证码同步",
        menu=menu,
    )


# ============================================================
# 入口
# ============================================================

def main() -> None:
    # 停止事件：用于协调轮询线程和托盘图标
    stop_event = threading.Event()

    # 启动后台轮询线程（守护线程，主线程退出时自动终止）
    polling_thread = threading.Thread(
        target=run_polling_loop,
        args=(stop_event,),
        daemon=True,
    )
    polling_thread.start()

    # 创建托盘图标
    tray_icon = create_tray_icon(stop_event)

    # 阻塞主线程运行托盘图标（icon.run() 在 stop() 调用后返回）
    tray_icon.run()


if __name__ == "__main__":
    main()