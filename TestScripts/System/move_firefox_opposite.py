import win32gui
import win32con
import win32api
import win32process
import psutil
import sys

def get_firefox_pids():
    pids = []
    for proc in psutil.process_iter(['pid', 'name']):
        try:
            if proc.info['name'].lower() == 'firefox.exe':
                pids.append(proc.info['pid'])
        except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
            pass
    return pids

def get_monitors():
    return win32api.EnumDisplayMonitors()

def move_window_to_opposite_monitor(hwnd, monitors):
    if not win32gui.IsWindowVisible(hwnd):
        return

    # Get current window rect
    rect = win32gui.GetWindowRect(hwnd)
    x, y, w, h = rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1]

    # Find which monitor the window is currently on
    try:
        hmonitor = win32api.MonitorFromWindow(hwnd, win32con.MONITOR_DEFAULTTONEAREST)
    except:
        return

    # Get monitor index
    current_monitor_index = -1
    for i, m in enumerate(monitors):
        if m[0].handle == hmonitor.handle:
            current_monitor_index = i
            break
    
    if current_monitor_index == -1 or len(monitors) < 2:
        print(f"Window {hwnd} is on monitor {current_monitor_index}, but not enough monitors to move.")
        return

    # Opposite monitor index
    target_monitor_index = (current_monitor_index + 1) % len(monitors)
    target_monitor = monitors[target_monitor_index]
    
    # Target monitor info
    # monitor_info = win32api.GetMonitorInfo(target_monitor[0])
    # work_area = monitor_info['Work']
    
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
    win32gui.SetWindowPos(hwnd, win32con.HWND_TOP, int(new_x), int(new_y), int(w), int(h), win32con.SWP_SHOWWINDOW)

def main():
    firefox_pids = get_firefox_pids()
    if not firefox_pids:
        print("Firefox is not running.")
        return

    monitors = get_monitors()
    if len(monitors) < 2:
        print("Only one monitor detected. Cannot move to opposite.")
        return

    def enum_callback(hwnd, pids):
        if win32gui.IsWindowVisible(hwnd):
            _, found_pid = win32process.GetWindowThreadProcessId(hwnd)
            if found_pid in pids:
                # Check if it has a title (to avoid moving hidden utility windows)
                title = win32gui.GetWindowText(hwnd)
                if title:
                    move_window_to_opposite_monitor(hwnd, monitors)
        return True

    win32gui.EnumWindows(enum_callback, firefox_pids)

if __name__ == "__main__":
    main()
