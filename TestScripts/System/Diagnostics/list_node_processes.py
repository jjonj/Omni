import psutil
import os

print(f"My PID: {os.getpid()}")
print("Listing all 'node' processes:")

for proc in psutil.process_iter(['pid', 'name', 'cmdline', 'ppid']):
    try:
        if 'node' in proc.info['name'].lower():
            print(f"PID: {proc.info['pid']}, PPID: {proc.info['ppid']}, Cmd: {proc.info['cmdline']}")
    except (psutil.NoSuchProcess, psutil.AccessDenied):
        pass
