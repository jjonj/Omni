import pygetwindow as gw
import win32gui
import win32process
import os
import psutil
import time

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
    
    # 1. Cleanup Windows
    print("Scanning for Gemini windows...")
    all_windows = gw.getAllWindows()
    count_win = 0
    for window in all_windows:
        title = window.title
        if not title: continue
        hwnd = window._hWnd
        
        # Heuristic: Title contains "Gemini CLI" or "gemini-cli" or "OMNI_GEMINI"
        if ("gemini" in title.lower() and "cli" in title.lower()) or "OMNI_GEMINI" in title:
            classname = win32gui.GetClassName(hwnd)
            if classname == "CabinetWClass": continue # Skip Explorer
            
            win_pid = get_window_pid(hwnd)
            if win_pid in my_ancestors:
                print(f"Skipping CURRENT window: {title} (PID: {win_pid})")
                continue
            
            print(f"KILLING TARGET WINDOW: {title} (PID: {win_pid})")
            try:
                window.close()
                count_win += 1
            except Exception as e:
                print(f"Failed to close window: {e}")

    # 2. Cleanup Headless Processes
    print("Scanning for headless Gemini 'node' processes...")
    count_proc = 0
    for proc in psutil.process_iter(['pid', 'name', 'cmdline']):
        try:
            # Skip ourselves and ancestors
            if proc.info['pid'] == os.getpid() or proc.info['pid'] in my_ancestors:
                continue

            cmdline = " ".join(proc.info['cmdline'] or [])
            # Target node running gemini.js or index.js
            if 'node' in proc.info['name'].lower() and ('gemini' in cmdline or 'index.js' in cmdline):
                 # Verify it's not the listener
                 if "ai_listener" in cmdline: continue

                 print(f"KILLING HEADLESS PROCESS: PID {proc.info['pid']} ({cmdline[:50]}...)")
                 try:
                     proc.kill()
                     count_proc += 1
                 except Exception as e:
                     print(f"Failed to kill process {proc.info['pid']}: {e}")
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            pass

    print(f"Total cleanup: {count_win} windows, {count_proc} processes.")

if __name__ == "__main__":
    cleanup_gemini_windows()
