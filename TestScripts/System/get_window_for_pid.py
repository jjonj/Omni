import win32gui
import win32process
import psutil
import sys

def get_real_window(target_pid):
    """
    Attempts to find the actual window handle (HWND) for a given PID.
    Handles nested processes, shell hosts (cmd/powershell), and Windows Terminal.
    """
    try:
        root_proc = psutil.Process(target_pid)
    except psutil.NoSuchProcess:
        return None

    # 1. Build the family tree (parents and children)
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
        if not win32gui.IsWindowVisible(hwnd):
            return True
        
        _, pid = win32process.GetWindowThreadProcessId(hwnd)
        
        # If the window is owned by someone in our tree
        if pid in tree_pids:
            title = win32gui.GetWindowText(hwnd)
            class_name = win32gui.GetClassName(hwnd)
            rect = win32gui.GetWindowRect(hwnd)
            w, h = rect[2]-rect[0], rect[3]-rect[1]
            
            # Filter out utility/hidden windows
            if w > 10 and h > 10:
                found_windows.append({
                    "hwnd": hwnd,
                    "pid": pid,
                    "title": title,
                    "class": class_name,
                    "size": (w, h),
                    "priority": 0
                })
        return True

    win32gui.EnumWindows(enum_cb, None)

    # 2. Fallback: If no window found, look for Windows Terminal hosting our shell
    # Windows Terminal (WindowsTerminal.exe) doesn't always appear in the parent tree.
    # But it often has titles like "[system32]" or the name of the process.
    if not found_windows:
        def terminal_fallback_cb(hwnd, _):
            if not win32gui.IsWindowVisible(hwnd): return True
            class_name = win32gui.GetClassName(hwnd)
            if class_name == "CASCADIA_HOSTING_WINDOW_CLASS":
                title = win32gui.GetWindowText(hwnd)
                # If terminal title mentions system32 or if we could somehow verify connection
                # For now, we'll check if any 'PseudoConsoleWindow' in our tree belongs to this terminal session
                # But a simpler heuristic: if the user just spawned it, it might be the active terminal.
                if "[system32]" in title or root_proc.name().replace(".exe","") in title.lower():
                    rect = win32gui.GetWindowRect(hwnd)
                    w, h = rect[2]-rect[0], rect[3]-rect[1]
                    found_windows.append({
                        "hwnd": hwnd,
                        "pid": win32process.GetWindowThreadProcessId(hwnd)[1],
                        "title": title,
                        "class": class_name,
                        "size": (w, h),
                        "priority": -1 # Lower priority than direct PID match
                    })
            return True
        win32gui.EnumWindows(terminal_fallback_cb, None)

    if not found_windows:
        return None

    # Sort by priority (higher is better) and then size
    # Priority: 
    # 0: Direct PID match in tree
    # -1: Title match (fallback)
    found_windows.sort(key=lambda x: (x["priority"], x["size"][0] * x["size"][1]), reverse=True)
    
    best_window = found_windows[0]
    # Log all found for debugging but only return the best one
    for w in found_windows:
        print(f"[DEBUG_SCAN] Candidate: HWND:{w['hwnd']} Title:{w['title']} Priority:{w['priority']} Size:{w['size']}", file=sys.stderr)
    
    return best_window

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python get_window_for_pid.py <PID>")
        sys.exit(1)
        
    pid = int(sys.argv[1])
    win = get_real_window(pid)
    if win:
        print(f"HWND:{win['hwnd']}|PID:{win['pid']}|Title:{win['title']}|Class:{win['class']}")
    else:
        print("NOT_FOUND")
