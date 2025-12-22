import wmi
import psutil

def diagnose():
    print("--- WMI Node Processes ---")
    try:
        c = wmi.WMI()
        for process in c.Win32_Process(Name="node.exe"):
            print(f"PID: {process.ProcessId}")
            print(f"  Name: {process.Name}")
            print(f"  CommandLine: {process.CommandLine}")
            print("-" * 20)
    except Exception as e:
        print(f"WMI Error: {e}")

    print("\n--- PSUtil Node Processes ---")
    for proc in psutil.process_iter(['pid', 'name', 'cmdline']):
        try:
            if 'node' in proc.info['name'].lower():
                print(f"PID: {proc.info['pid']}")
                print(f"  Name: {proc.info['name']}")
                print(f"  Cmdline: {proc.info['cmdline']}")
                print("-" * 20)
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            pass

if __name__ == "__main__":
    diagnose()
