import win32gui
import win32process
import sys
import psutil

sys.stdout.reconfigure(encoding='utf-8')

def cb(hwnd, _):
    if win32gui.IsWindowVisible(hwnd):
        title = win32gui.GetWindowText(hwnd)
        _, pid = win32process.GetWindowThreadProcessId(hwnd)
        try:
            name = psutil.Process(pid).name()
        except:
            name = "unknown"
        print(f"PID: {pid} | Process: {name} | Title: {title}")

win32gui.EnumWindows(cb, None)
