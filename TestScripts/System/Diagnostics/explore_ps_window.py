import win32gui

def callback(hwnd, extra):
    if win32gui.IsWindowVisible(hwnd):
        title = win32gui.GetWindowText(hwnd)
        classname = win32gui.GetClassName(hwnd)
        if "PowerShell" in title or "Console" in classname:
            print(f"HWND: {hwnd}, Title: {title}, Class: {classname}")
            def child_callback(child_hwnd, _):
                print(f"  Child HWND: {child_hwnd}, Title: {win32gui.GetWindowText(child_hwnd)}, Class: {win32gui.GetClassName(child_hwnd)}")
            win32gui.EnumChildWindows(hwnd, child_callback, None)

win32gui.EnumWindows(callback, None)
