import win32gui
import win32process
import sys
import psutil

sys.stdout.reconfigure(encoding='utf-8')

def cb(hwnd, _):
    title = win32gui.GetWindowText(hwnd)
    class_name = win32gui.GetClassName(hwnd)
    _, pid = win32process.GetWindowThreadProcessId(hwnd)
    try:
        name = psutil.Process(pid).name()
    except:
        name = "unknown"
    
    # Search for system32, node, or any console-related terms
    if any(term in title.lower() for term in ["system32", "node", "cmd", "terminal", "omni"]) or \
       any(term in name.lower() for term in ["node", "cmd", "terminal"]):
        is_visible = win32gui.IsWindowVisible(hwnd)
        rect = win32gui.GetWindowRect(hwnd)
        print(f"PID: {pid} | Process: {name} | Class: {class_name} | Visible: {is_visible} | Rect: {rect} | Title: {title}")

win32gui.EnumWindows(cb, None)
