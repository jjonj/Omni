import win32gui
import win32con
import win32api
import win32process
import psutil
import sys

def get_pids_by_name(name):
    pids = []
    for proc in psutil.process_iter(['pid', 'name']):
        try:
            if name.lower() in proc.info['name'].lower():
                pids.append(proc.info['pid'])
        except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
            pass
    return pids

def get_monitors():
    return win32api.EnumDisplayMonitors()

def move_window_to_opposite_monitor(hwnd, monitors):
    if not win32gui.IsWindowVisible(hwnd):
        return False

    # Get current window rect
    try:
        rect = win32gui.GetWindowRect(hwnd)
    except:
        return False
        
    x, y, w, h = rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1]

    # Find which monitor the window is currently on
    try:
        hmonitor = win32api.MonitorFromWindow(hwnd, win32con.MONITOR_DEFAULTTONEAREST)
    except:
        return False

    # Get monitor index
    current_monitor_index = -1
    for i, m in enumerate(monitors):
        if m[0].handle == hmonitor.handle:
            current_monitor_index = i
            break
    
    if current_monitor_index == -1 or len(monitors) < 2:
        return False

    # Opposite monitor index
    target_monitor_index = (current_monitor_index + 1) % len(monitors)
    
    # Current monitor info
    current_monitor_info = win32api.GetMonitorInfo(monitors[current_monitor_index][0])
    current_monitor_rect = current_monitor_info['Monitor']
    
    # Target monitor info
    target_monitor_info = win32api.GetMonitorInfo(monitors[target_monitor_index][0])
    target_monitor_rect = target_monitor_info['Monitor']

    # Calculate relative position
    rel_x = x - current_monitor_rect[0]
    rel_y = y - current_monitor_rect[1]
    
    # New position on target monitor
    new_x = target_monitor_rect[0] + rel_x
    new_y = target_monitor_rect[1] + rel_y

    print(f"Moving window {hwnd} from Monitor {current_monitor_index} to {target_monitor_index}")
    
    # Restore if minimized
    if win32gui.IsIconic(hwnd):
        win32gui.ShowWindow(hwnd, win32con.SW_RESTORE)
        
    # Move window
    try:
        win32gui.SetWindowPos(hwnd, win32con.HWND_TOP, int(new_x), int(new_y), int(w), int(h), win32con.SWP_SHOWWINDOW)
        return True
    except:
        return False

def main():
    if len(sys.argv) < 2:
        print("Usage: move_window_opposite.py <PID|ProcessName|WindowTitle>")
        return

    target = sys.argv[1]
    monitors = get_monitors()
    if len(monitors) < 2:
        print("Only one monitor detected.")
        return

    target_hwnds = []
    target_pids = set()

    # Try as PID first
    try:
        pid = int(target)
        target_pids.add(pid)
        try:
            proc = psutil.Process(pid)
            # Add entire tree (children + parents up to explorer)
            for child in proc.children(recursive=True):
                target_pids.add(child.pid)
            
            curr = proc
            while curr.parent():
                curr = curr.parent()
                if curr.name().lower() == "explorer.exe": break
                target_pids.add(curr.pid)
        except:
            pass
    except ValueError:
        pass

    # Cache process names ONCE to avoid lag in the callback
    pid_to_name = {}
    if not target_pids:
        for proc in psutil.process_iter(['pid', 'name']):
            try:
                pid_to_name[proc.info['pid']] = proc.info['name'].lower()
            except:
                pass

    def enum_callback(hwnd, results):
        if win32gui.IsWindowVisible(hwnd):
            _, found_pid = win32process.GetWindowThreadProcessId(hwnd)
            
            # Match by PID tree
            if found_pid in target_pids:
                results.append(hwnd)
                return True
                
            # Match by name or title
            title = win32gui.GetWindowText(hwnd).lower()
            pname = pid_to_name.get(found_pid, "")
            
            target_lower = target.lower()
            if target_lower in title or (pname and target_lower in pname):
                if title or pname:
                    results.append(hwnd)
        return True

    win32gui.EnumWindows(enum_callback, target_hwnds)

    if not target_hwnds:
        print(f"No windows found for target: {target}")
        return

    target_hwnds = list(set(target_hwnds))
    moved_count = 0
    for hwnd in target_hwnds:
        if move_window_to_opposite_monitor(hwnd, monitors):
            moved_count += 1
    
    print(f"Moved {moved_count} windows.")

if __name__ == "__main__":
    main()
