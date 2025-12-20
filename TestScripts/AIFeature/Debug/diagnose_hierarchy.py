import psutil
import os
import pygetwindow as gw
import win32process

def diagnose():
    my_pid = os.getpid()
    print(f"Agent PID: {my_pid}")
    
    # Trace hierarchy
    curr = psutil.Process(my_pid)
    hierarchy = []
    while curr:
        try:
            hierarchy.append({
                'pid': curr.pid,
                'name': curr.name(),
                'cmdline': curr.cmdline()
            })
            curr = curr.parent()
        except:
            break
            
    print("\nProcess Hierarchy:")
    for i, p in enumerate(hierarchy):
        print(f"{i}: PID={p['pid']}, Name={p['name']}, Cmdline={' '.join(p['cmdline']) if p['cmdline'] else 'N/A'}")

    print("\nGemini-related Windows:")
    all_windows = gw.getAllWindows()
    for win in all_windows:
        title = win.title
        if "gemini" in title.lower():
            try:
                thread_id, pid = win32process.GetWindowThreadProcessId(win._hWnd)
                print(f"Window: '{title}' (PID: {pid})")
                if any(h['pid'] == pid for h in hierarchy):
                    print(f"  ^^^ THIS IS IN OUR HIERARCHY")
            except:
                pass

if __name__ == "__main__":
    diagnose()
