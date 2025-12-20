import subprocess
import os
import time

def test_startup():
    exe_path = r"D:\SSDProjects\Omni\OmniSync.Hub\src\OmniSync.Hub\bin\Debug\net9.0-windows\OmniSync.Hub.exe"
    
    # Set CWD to System32 to simulate startup behavior
    cwd = r"C:\Windows\System32"
    
    print(f"Launching {exe_path} from {cwd}...")
    
    try:
        # Launch process
        process = subprocess.Popen([exe_path], cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        
        # Wait a bit
        time.sleep(5)
        
        # Check if running
        if process.poll() is None:
            print("Process is still running. Success!")
            # Kill it
            process.terminate()
        else:
            print(f"Process exited early with code {process.returncode}")
            out, err = process.communicate()
            print(f"Stdout: {out}")
            print(f"Stderr: {err}")

    except Exception as e:
        print(f"Failed to launch: {e}")

if __name__ == "__main__":
    test_startup()

