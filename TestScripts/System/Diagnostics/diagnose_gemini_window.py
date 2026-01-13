import pygetwindow as gw
import win32gui
import win32con
import win32process
import os

def diagnose():
    print(f"{'Title':<50} | {'Class':<30} | {'HWND':<10} | {'PID':<10}")
    print("-" * 110)
    for win in gw.getAllWindows():
        if win.title.strip():
            try:
                classname = win32gui.GetClassName(win._hWnd)
                _, pid = win32process.GetWindowThreadProcessId(win._hWnd)
                print(f"{win.title[:50]:<50} | {classname:<30} | {win._hWnd:<10} | {pid:<10}")
            except:
                pass

if __name__ == "__main__":
    diagnose()
