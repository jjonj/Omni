import subprocess
import time
import os
import sys

# Define directories and executable path
HUB_DIR = "B:/GDrive/SharedWithPhone/Omni/OmniSync.Hub/src/OmniSync.Hub"
HUB_EXE_PATH = os.path.join(HUB_DIR, "bin", "Debug", "net9.0", "OmniSync.Hub.exe") 

def run_command(command, cwd=None, shell=False, capture_output=False, log_file=None):
    """
    Runs a shell command and optionally logs its output.
    """
    print(f"Executing: {command}")
    
    process = None
    stdout_redir = subprocess.PIPE if capture_output or log_file else None
    stderr_redir = subprocess.PIPE if capture_output or log_file else None

    if "taskkill" in command.lower() or "wmic" in command.lower():
        shell = True

    try:
        process = subprocess.run(
            command,
            cwd=cwd,
            shell=shell,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace"
        )

        if log_file:
            with open(log_file, "a", encoding="utf-8") as f:
                f.write(f"--- Command: {command} (CWD: {cwd}) ---\n")
                f.write("--- STDOUT ---\n")
                f.write(process.stdout)
                f.write("--- STDERR ---\n")
                f.write(f"--- Exit Code: {process.returncode} ---\n\n")
        else:
            print(process.stdout, end='')
            print(process.stderr, end='')

        if process.returncode != 0:
            print(f"Command failed with exit code {process.returncode}. See {log_file if log_file else 'output above'} for details.")
        
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
    hub_log_path = "hub_output.log"

    if os.path.exists(hub_log_path):
        try:
            os.remove(hub_log_path)
        except OSError as e:
            print(f"Error deleting {hub_log_path}: {e}. Please ensure no other process is using it.")
            time.sleep(1)

    kill_hub_process()

    hub_log_file = None
    try:
        print("\n--- Building OmniSync.Hub ---")
        build_hub_result = run_command("dotnet build", cwd=HUB_DIR, log_file=hub_log_path)
        if build_hub_result is None or build_hub_result.returncode != 0:
            print("OmniSync.Hub build failed. Aborting. Check hub_output.log for details.")
            return

        print("\n--- Starting OmniSync.Hub in background ---")
        
        hub_log_file = open(hub_log_path, "a", encoding="utf-8", errors="replace")
        hub_log_file.write(f"\n--- Starting OmniSync.Hub (PID will be known after Popen) ---\\n")

        if not os.path.exists(HUB_EXE_PATH):
            print(f"Error: Hub executable not found at {HUB_EXE_PATH}. Did the build fail?")
            return

        hub_process = subprocess.Popen(
            [HUB_EXE_PATH],
            cwd=HUB_DIR,
            stdout=hub_log_file,
            stderr=hub_log_file,
            creationflags=subprocess.DETACHED_PROCESS if sys.platform == "win32" else 0,
            shell=False
        )
        print(f"OmniSync.Hub started with PID: {hub_process.pid}")
        
        time.sleep(5) 
        print("\nOmniSync.Hub is running in the background. You can now run your test scripts.")
        print(f"Please remember to manually kill the hub process (PID: {hub_process.pid}) after your tests are complete.")
        print("You can do this by running: taskkill /PID {pid} /F in a new terminal.")
        
    finally:
        if hub_log_file and not hub_log_file.closed:
            hub_log_file.close()

if __name__ == "__main__":
    main()
