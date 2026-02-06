import win32gui
import win32con
import win32api
import win32process
import win32gui
import win32con
import win32api
import win32process
import psutil
import time
import sys
import traceback

def get_real_window(target_pid):
    """
    Attempts to find the actual window handle (HWND) for a given PID.
    Handles nested processes, shell hosts (cmd/powershell), and Windows Terminal.
    """
    try:
        root_proc = psutil.Process(target_pid)
    except psutil.NoSuchProcess:
        return None

    tree_pids = {target_pid}
    
    # Add children
    try:
        for child in root_proc.children(recursive=True):
            tree_pids.add(child.pid)
    except: pass
    
    # Add parents
    curr = root_proc
    try:
        while curr.parent():
            curr = curr.parent()
            tree_pids.add(curr.pid)
            if curr.name().lower() == "explorer.exe": break
    except: pass

    found_windows = []

    def enum_cb(hwnd, _):
        if not win32gui.IsWindowVisible(hwnd): return True
        _, pid = win32process.GetWindowThreadProcessId(hwnd)
        if pid in tree_pids:
            title = win32gui.GetWindowText(hwnd)
            class_name = win32gui.GetClassName(hwnd)
            rect = win32gui.GetWindowRect(hwnd)
            w, h = rect[2]-rect[0], rect[3]-rect[1]
            if w > 10 and h > 10:
                found_windows.append({"hwnd": hwnd, "size": (w, h), "priority": 0})
        return True

    win32gui.EnumWindows(enum_cb, None)

    if not found_windows:
        def terminal_fallback_cb(hwnd, _):
            if not win32gui.IsWindowVisible(hwnd): return True
            if win32gui.GetClassName(hwnd) == "CASCADIA_HOSTING_WINDOW_CLASS":
                title = win32gui.GetWindowText(hwnd)
                if "[system32]" in title or root_proc.name().replace(".exe","") in title.lower():
                    rect = win32gui.GetWindowRect(hwnd)
                    w, h = rect[2]-rect[0], rect[3]-rect[1]
                    found_windows.append({"hwnd": hwnd, "size": (w, h), "priority": -1})
            return True
        win32gui.EnumWindows(terminal_fallback_cb, None)

    if not found_windows: return None
    found_windows.sort(key=lambda x: (x["priority"], x["size"][0] * x["size"][1]), reverse=True)
    return found_windows[0]["hwnd"]

def main():
    target_pid = 38756
    if len(sys.argv) > 1: target_pid = int(sys.argv[1])
    
    if not psutil.pid_exists(target_pid):
        print(f"Error: Process with PID {target_pid} does not exist.")
        sys.exit(1)
        
    print(f"Starting 5s toggle for PID {target_pid} (10 iterations)")
    
    monitors = win32api.EnumDisplayMonitors()
    
    for i in range(10):
        if not psutil.pid_exists(target_pid):
            print(f"Process {target_pid} terminated. Exiting.")
            break
            
        try:
            hwnd = get_real_window(target_pid)
            if hwnd:
                title = win32gui.GetWindowText(hwnd)
                rect = win32gui.GetWindowRect(hwnd)
                w, h = rect[2]-rect[0], rect[3]-rect[1]
                
                hmon = win32api.MonitorFromWindow(hwnd, 2)
                idx = -1
                for m_idx, m in enumerate(monitors):
                    if m[0].handle == hmon.handle:
                        idx = m_idx; break
                
                if idx != -1:
                    t_idx = (idx + 1) % len(monitors)
                    curr_m = win32api.GetMonitorInfo(monitors[idx][0])['Monitor']
                    next_m = win32api.GetMonitorInfo(monitors[t_idx][0])['Monitor']
                    
                    new_x = next_m[0] + (rect[0] - curr_m[0])
                    new_y = next_m[1] + (rect[1] - curr_m[1])
                    
                    print(f"Iteration {i+1}: Toggling '{title}' to Monitor {t_idx}")
                    
                    if win32gui.GetWindowPlacement(hwnd)[1] == win32con.SW_SHOWMAXIMIZED:
                        win32gui.ShowWindow(hwnd, win32con.SW_RESTORE)
                        
                    win32gui.SetWindowPos(hwnd, win32con.HWND_TOP, int(new_x), int(new_y), int(w), int(h), win32con.SWP_SHOWWINDOW)
                    win32gui.SetForegroundWindow(hwnd)
            else:
                print(f"Iteration {i+1}: No window found for PID {target_pid}")
        except Exception as e:
            print(f"Error in iteration {i+1}: {e}")
            
        time.sleep(5)

if __name__ == "__main__":
    main()
if __name__ == "__main__":
    main()
