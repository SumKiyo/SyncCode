"""
SyncCode - PC 接收端 (pc-receiver)
跨端验证码极速同步系统 - 桌面终端

功能：
  每 3 秒轮询云端 /api/pull 接口，获取 Android 端上报的验证码。
  获取到新验证码后：
    1. 将纯数字验证码写入系统剪贴板（可直接 Ctrl+V 粘贴）
    2. 弹出 Windows 桌面通知（显示应用名和验证码）

使用方式：
  python main.py

依赖安装：
  pip install -r requirements.txt
"""

import time
import sys
import os
import traceback

import requests
import pyperclip
from plyer import notification
from dotenv import load_dotenv

# 加载 .env 文件中的环境变量
load_dotenv()

# ============================================================
# 配置常量（从 .env 环境变量读取，不再硬编码）
# ============================================================
API_BASE_URL = os.getenv("API_BASE_URL", "https://sms-worker.example.workers.dev")
API_SECRET = os.getenv("API_SECRET", "")
POLL_INTERVAL = float(os.getenv("POLL_INTERVAL", "1.5"))

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
            timeout=10,  # 10 秒超时，避免长时间卡住
        )
        resp.raise_for_status()

        data = resp.json()

        if data.get("has_data") and data.get("data"):
            return data["data"]
        else:
            return None

    except requests.exceptions.Timeout:
        # 超时：静默忽略，下次继续轮询
        return None
    except requests.exceptions.ConnectionError:
        # 断网 / DNS 解析失败：静默忽略
        return None
    except requests.exceptions.RequestException:
        # 其他网络异常：静默忽略
        return None
    except Exception:
        # 兜底：JSON 解析失败等意外错误
        return None


def notify_and_copy(app: str, code: str, raw_text: str) -> None:
    """
    将验证码写入剪贴板，并弹出系统桌面通知。
    """
    # 1. 写入剪贴板
    try:
        pyperclip.copy(code)
        print(f"[OK] 验证码已写入剪贴板: {code}")
    except Exception as e:
        print(f"[WARN] 剪贴板写入失败: {e}")

    # 2. 弹出桌面通知
    try:
        notification.notify(
            title=f"📩 验证码 - {app}",
            message=f"{code}\n{raw_text}",
            app_name="SyncCode",
            timeout=5,  # 通知显示 5 秒
        )
        print(f"[OK] 桌面通知已弹出: [{app}] {code}")
    except Exception as e:
        print(f"[WARN] 桌面通知失败: {e}")


def main() -> None:
    print("=" * 50)
    print("  SyncCode PC 接收端已启动")
    print(f"  轮询地址: {API_BASE_URL}/api/pull")
    print(f"  轮询间隔: {POLL_INTERVAL} 秒")
    print("  按 Ctrl+C 退出")
    print("=" * 50)

    # 记录上一次成功获取的验证码，避免重复通知
    last_code: str | None = None

    while True:
        try:
            data = pull_code()

            if data is not None:
                code = data.get("code", "")
                app = data.get("app", "未知应用")
                raw_text = data.get("raw_text", "")

                # 避免因网络抖动导致的重复通知
                if code != last_code or last_code is None:
                    notify_and_copy(app, code, raw_text)
                    last_code = code
                else:
                    print(f"[SKIP] 重复验证码，跳过: {code}")

            time.sleep(POLL_INTERVAL)

        except KeyboardInterrupt:
            print("\n[BYE] SyncCode 接收端已退出。")
            sys.exit(0)
        except Exception:
            # 兜底：任何未预期的异常都不应导致程序崩溃
            print(f"[ERR] 未预期的错误: {traceback.format_exc()}")
            time.sleep(POLL_INTERVAL)


if __name__ == "__main__":
    main()