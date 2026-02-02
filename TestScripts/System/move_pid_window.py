import psutil
import sys
import win32gui
import win32process
import win32con
import win32api

def get_visible_hwnds_for_pid(pid):
    def callback(hwnd, hwnds):
        if win32gui.IsWindowVisible(hwnd):
            _, found_pid = win32process.GetWindowThreadProcessId(hwnd)
            if found_pid == pid:
                hwnds.append(hwnd)
        return True
    hwnds = []
    win32gui.EnumWindows(callback, hwnds)
    return hwnds

def move_pid_window(pid, monitor_index):
    monitors = win32api.EnumDisplayMonitors()
    if monitor_index >= len(monitors):
        monitor_index = 0
        
    target_monitor = monitors[monitor_index]
    target_monitor_info = win32api.GetMonitorInfo(target_monitor[0])
    target_rect = target_monitor_info['Monitor']
    
    # Defaults
    target_x = target_rect[0] + 50
    target_y = target_rect[1] + 50
    width = 1200
    height = 800

    print(f"Targeting monitor {monitor_index} (X={target_x}) for PID {pid}")
    
    # 1. Search for windows in the PID's entire tree
    target_hwnds = []
    try:
        proc = psutil.Process(pid)
        tree_pids = [pid] + [c.pid for c in proc.children(recursive=True)]
        
        curr = proc
        while curr.parent():
            curr = curr.parent()
            tree_pids.append(curr.pid)
            if curr.name().lower() == "explorer.exe": break
            
        print(f"Scanning process tree ({len(tree_pids)} PIDs)...")
        for p in tree_pids:
            target_hwnds.extend(get_visible_hwnds_for_pid(p))
    except Exception as e:
        print(f"Process scan error: {e}")

    # 2. Fallback: Search by known titles using EnumWindows (efficient)
    if not target_hwnds:
        print("No window found by PID tree, searching by title...")
        def title_callback(hwnd, results):
            if win32gui.IsWindowVisible(hwnd):
                title = win32gui.GetWindowText(hwnd)
                if any(t in title for t in ["OMNI_GEMINI_INTERACTIVE", "cmd.exe", "powershell.exe"]):
                    results.append(hwnd)
            return True
        win32gui.EnumWindows(title_callback, target_hwnds)

    if not target_hwnds:
        print("Could not find any window to move.")
        return

    target_hwnds = list(set(target_hwnds))
    print(f"Found {len(target_hwnds)} window(s) to move: {target_hwnds}")
    
    for hwnd_val in target_hwnds:
        title = win32gui.GetWindowText(hwnd_val)
        print(f"Moving window '{title}' (Handle: {hwnd_val})")
        
        try:
            if win32gui.IsIconic(hwnd_val):
                win32gui.ShowWindow(hwnd_val, win32con.SW_RESTORE)
            
            # Use SetWindowPos directly - it's fast and doesn't lag
            win32gui.SetWindowPos(hwnd_val, win32con.HWND_TOP, int(target_x), int(target_y), width, height, win32con.SWP_SHOWWINDOW)
            print(f"  Successfully moved '{title}' to monitor {monitor_index}")
        except Exception as e:
            print(f"  Move failed for '{title}': {e}")

if __name__ == "__main__":

    if len(sys.argv) < 3:

        sys.exit(1)

    move_pid_window(int(sys.argv[1]), int(sys.argv[2]))
