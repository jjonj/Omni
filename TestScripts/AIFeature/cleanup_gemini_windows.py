import pygetwindow as gw
import win32gui
import win32process
import os
import psutil

def get_window_pid(hwnd):
    try:
        _, pid = win32process.GetWindowThreadProcessId(hwnd)
        return pid
    except:
        return None

def get_my_ancestors():
    ancestors = set()
    try:
        p = psutil.Process(os.getpid())
        while p:
            ancestors.add(p.pid)
            p = p.parent()
    except:
        pass
    return ancestors

def cleanup_gemini_windows():
    print(f"Current PID: {os.getpid()}")
    my_ancestors = get_my_ancestors()
    
    all_windows = gw.getAllWindows()
    
    # We want to identify windows with "Gemini CLI" or "gemini-cli" in title
    # EXCEPT the one we are in.
    # AND we want to exclude Explorer windows (Class CabinetWClass)
    
    count = 0
    for window in all_windows:
        title = window.title
        if not title:
            continue
            
        hwnd = window._hWnd
        
        # Check if it's a Gemini CLI window (case insensitive)
        if "gemini" in title.lower() and "cli" in title.lower():
            classname = win32gui.GetClassName(hwnd)
            
            # Exclude Explorer
            if classname == "CabinetWClass":
                # print(f"Skipping Explorer window: {title}")
                continue
            
            win_pid = get_window_pid(hwnd)
            
            # Check if this window's process is in our ancestry
            # Or if our process is a child of this window's process
            if win_pid in my_ancestors:
                print(f"Skipping CURRENT window: {title} (PID: {win_pid})")
                continue
            
            # If we are in Windows Terminal, multiple windows might share a PID
            # If any ancestor of ours matches the window PID, it's probably our host
            
            print(f"KILLING TARGET WINDOW: {title} (PID: {win_pid}, Class: {classname})")
            window.close() # KILL CODE ENABLED
            count += 1

    print(f"Total target windows found: {count}")

if __name__ == "__main__":
    cleanup_gemini_windows()