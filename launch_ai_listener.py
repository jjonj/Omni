import subprocess
import os
import sys
import psutil
import time

def kill_existing_listeners():
    print("Checking for existing AIListener processes...")
    current_pid = os.getpid()
    for proc in psutil.process_iter(['pid', 'name', 'cmdline']):
        try:
            # Check if it's a python process and has ai_listener.py in its cmdline
            if proc.info['cmdline'] and any('ai_listener.py' in part for part in proc.info['cmdline']):
                if proc.info['pid'] != current_pid:
                    print(f"Killing existing listener (PID: {proc.info['pid']})...")
                    proc.kill()
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            pass

def launch(pid=None):
    if not pid:
        kill_existing_listeners()
    time.sleep(1) # Wait for cleanup
    
    script_path = os.path.join("OmniSync.Cli", "ai_listener.py")
    args = [sys.executable, script_path]
    if pid:
        args.extend(["--pid", str(pid)])
        print(f"Launching {script_path} for PID {pid} in a new console...")
    else:
        print(f"Launching {script_path} in a new console...")
    
    try:
        # Use CREATE_NEW_CONSOLE to make it visible
        subprocess.Popen(
            args,
            creationflags=subprocess.CREATE_NEW_CONSOLE,
            close_fds=True 
        )
        print("Process started.")
    except Exception as e:
        print(f"Failed to launch: {e}")

if __name__ == "__main__":
    target_pid = int(sys.argv[1]) if len(sys.argv) > 1 else None
    launch(target_pid)