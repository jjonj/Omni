import win32file
import glob

def list_pipes():
    import psutil
    import os
    
    my_pid = os.getpid()
    ancestor_pids = set()
    try:
        curr = psutil.Process(my_pid)
        while curr:
            ancestor_pids.add(curr.pid)
            curr = curr.parent()
    except:
        pass

    print(f"{'PID':<10} | {'Type':<10} | {'Cmdline'}")
    print("-" * 70)
    for proc in psutil.process_iter(['pid', 'name', 'cmdline']):
        try:
            cmdline = " ".join(proc.info['cmdline'] or [])
            is_gemini = ('node' in proc.info['name'].lower() and 
                         ('gemini' in cmdline.lower() or 'google-gemini' in cmdline.lower()))
            
            if is_gemini:
                ptype = "ANCESTOR" if proc.info['pid'] in ancestor_pids else "OTHER"
                print(f"{proc.info['pid']:<10} | {ptype:<10} | {cmdline}")
        except:
            pass

if __name__ == "__main__":
    list_pipes()
