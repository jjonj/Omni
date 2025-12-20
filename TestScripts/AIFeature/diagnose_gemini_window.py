import pygetwindow as gw
import win32gui
import win32con

def diagnose():
    print(f"{'Title':<50} | {'Class':<30} | {'HWND':<10}")
    print("-" * 95)
    for win in gw.getAllWindows():
        if win.title.strip():
            try:
                classname = win32gui.GetClassName(win._hWnd)
                print(f"{win.title[:50]:<50} | {classname:<30} | {win._hWnd:<10}")
            except:
                pass

if __name__ == "__main__":
    diagnose()
