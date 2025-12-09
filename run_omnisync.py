import subprocess
import time
import os
import sys
import ctypes # Import ctypes

# Define directories and executable path
HUB_DIR = "B:/GDrive/SharedWithPhone/Omni/OmniSync.Hub/src/OmniSync.Hub"
HUB_EXE_PATH = os.path.join(HUB_DIR, "bin", "Debug", "net9.0", "OmniSync.Hub.exe") 

def is_admin():
    """
    Checks if the current process has administrator privileges on Windows.
    """
    try:
        return ctypes.windll.shell32.IsUserAnAdmin()
    except:
        return False

def run_command(command, cwd=None, shell=False, capture_output=False):
    """
    Runs a shell command and prints its output.
    """
    print(f"Executing: {command}")
    
    process = None
    # Handle shell for Windows specific commands like taskkill
    if "taskkill" in command.lower() or "wmic" in command.lower():
        shell = True

    try:
        process = subprocess.run(
            command,
            cwd=cwd,
            shell=shell,
            capture_output=capture_output,
            text=True,
            encoding="utf-8",
            errors="replace" # Handle decoding errors gracefully
        )
        if capture_output:
            print(process.stdout, end='')
            print(process.stderr, end='')

        if process.returncode != 0:
            print(f"Command failed with exit code {process.returncode}.")
        
        return process

    except FileNotFoundError:
        print(f"Error: Command '{command.split()[0]}' not found.")
        return None
    except Exception as e:
        print(f"An error occurred while running command '{command}': {e}")
        return None

def kill_hub_process():
    """
    Kills any running OmniSync.Hub.exe processes.
    """
    print("Attempting to kill OmniSync.Hub.exe processes...")
    if sys.platform == "win32":
        kill_cmd = "taskkill /IM OmniSync.Hub.exe /F"
        result = run_command(kill_cmd, shell=True, capture_output=True)
        if result:
            if "No tasks are running" in result.stdout or "process not found" in result.stderr:
                print("OmniSync.Hub.exe was not running or could not be found.")
            elif result.returncode == 0:
                print("OmniSync.Hub.exe processes killed successfully.")
            else:
                print(f"Failed to kill OmniSync.Hub.exe: {result.stderr}")
    else:
        print("Warning: Process killing not implemented for non-Windows platforms.")

def main():
    if sys.platform == "win32" and not is_admin():
        print("This script needs to be run with Administrator privileges.")
        print("Please restart your terminal/IDE as Administrator and try again.")
        sys.exit(1)

    kill_hub_process()

    print("\n--- Building OmniSync.Hub ---")
    build_hub_result = run_command("dotnet build", cwd=HUB_DIR)
    if build_hub_result is None or build_hub_result.returncode != 0:
        print("OmniSync.Hub build failed. Aborting.")
        return # Exit if build failed
    time.sleep(1) # Give it a moment

    print("\n--- Starting OmniSync.Hub in background ---")
    
    # Ensure the executable exists before trying to run it
    if not os.path.exists(HUB_EXE_PATH):
        print(f"Error: Hub executable not found at {HUB_EXE_PATH}. Did the build fail?")
        return

    # Use Popen to start the hub process in a detached way
    # If the script itself is run as admin, subprocess.Popen will inherit those rights.
    hub_process = subprocess.Popen(
        [HUB_EXE_PATH], # Run the compiled executable directly
        cwd=HUB_DIR,
        creationflags=subprocess.DETACHED_PROCESS if sys.platform == "win32" else 0, # For Windows, run truly detached
        shell=False # Don't use shell if running exe directly
    )
    print(f"OmniSync.Hub started with PID: {hub_process.pid}")
    
    print("\nOmniSync.Hub is running in the background.")
    print("Use 'python run_omnisync.py --kill' to stop it, or manually kill the process if needed.")

if __name__ == "__main__":
    if "--kill" in sys.argv:
        kill_hub_process()
    else:
        main()