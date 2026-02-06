import win32gui
import win32process
import sys

# Ensure stdout uses UTF-8
sys.stdout.reconfigure(encoding='utf-8')

def cb(hwnd, _):
    if win32gui.IsWindowVisible(hwnd):
        title = win32gui.GetWindowText(hwnd)
        if title:
            _, pid = win32process.GetWindowThreadProcessId(hwnd)
            print(f"PID: {pid} | Title: {title}")

win32gui.EnumWindows(cb, None)
