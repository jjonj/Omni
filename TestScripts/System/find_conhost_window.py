import win32gui
import win32process
import sys

def main():
    target_pid = 42760
    if len(sys.argv) > 1:
        target_pid = int(sys.argv[1])
        
    print(f"Searching for windows owned by PID {target_pid}...")
    
    def callback(hwnd, extra):
        if win32gui.IsWindowVisible(hwnd):
            _, pid = win32process.GetWindowThreadProcessId(hwnd)
            if pid == target_pid:
                title = win32gui.GetWindowText(hwnd)
                print(f"Found Window: HWND {hwnd} Title: '{title}'")
        return True
        
    win32gui.EnumWindows(callback, None)

if __name__ == "__main__":
    main()
