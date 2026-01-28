import subprocess
import time
import os
import sys
import shutil
import ctypes

# Define directories and executable path
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
HUB_DIR = os.path.join(SCRIPT_DIR, "OmniSync.Hub", "src", "OmniSync.Hub")
CLI_DIR = os.path.join(SCRIPT_DIR, "OmniSync.Cli", "deprecated")
HUB_EXE_PATH = os.path.join(HUB_DIR, "bin", "Debug", "net9.0-windows", "OmniSync.Hub.exe") 

def run_command(command, cwd=None, shell=False, log_file=None):
    """
    Runs a shell command and optionally logs its output.
    """
    print(f"Executing: {command} in {cwd}")
    
    stdout_redirect = subprocess.PIPE
    stderr_redirect = subprocess.PIPE

    try:
        process = subprocess.run(
            command,
            cwd=cwd,
            shell=shell,
            stdout=stdout_redirect,
            stderr=stderr_redirect,
            text=True,
            encoding="utf-8",
            errors="replace" # Handle decoding errors gracefully
        )

        if log_file:
            with open(log_file, "a", encoding="utf-8") as f:
                f.write(f"--- Command: {command} (CWD: {cwd}) ---\n")
                f.write("--- STDOUT ---\n")
                f.write(process.stdout)
                f.write("--- STDERR ---\n")
                f.write(process.stderr)
                f.write(f"--- Exit Code: {process.returncode} ---\n\n")
        else: # Print to console if no log file
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
        result = run_command(kill_cmd, shell=True)
        if result:
            if "No tasks are running" in result.stdout or "process not found" in result.stderr:
                print("OmniSync.Hub.exe was not running or could not be found.")
            elif result.returncode == 0:
                print("OmniSync.Hub.exe processes killed successfully.")
            else:
                print(f"Failed to kill OmniSync.Hub.exe: {result.stderr}")
    else:
        # For non-Windows, assume 'pkill' or similar if available, or just warn.
        print("Warning: Process killing not implemented for non-Windows platforms.")

def on_rmtree_error(func, path, exc_info):
    """
    Error handler for shutil.rmtree.
    If the error is due to a read-only file, it tries to change the permissions and retry.
    """
    import stat
    if not os.access(path, os.W_OK):
        os.chmod(path, stat.S_IWUSR)
        func(path)
    else:
        raise

def delete_with_retry(path, max_retries=5, delay=1):
    """
    Deletes a directory with retries.
    """
    if not os.path.exists(path):
        return

    for i in range(max_retries):
        try:
            shutil.rmtree(path, onerror=on_rmtree_error)
            print(f"Successfully deleted {path}")
            return
        except Exception as e:
            if i < max_retries - 1:
                print(f"Error deleting {path}: {e}. Retrying in {delay}s... ({i+1}/{max_retries})")
                time.sleep(delay)
            else:
                print(f"Failed to delete {path} after {max_retries} attempts.")

def is_admin():
    try:
        return ctypes.windll.shell32.IsUserAnAdmin()
    except:
        return False

def main():
    if sys.platform == 'win32' and not is_admin():
        print("Script is not running as admin. Requesting elevation via PowerShell...")
        script_path = os.path.abspath(__file__)
        params = " ".join([f'"{arg}"' for arg in sys.argv[1:]])
        
        # Use PowerShell's Start-Process with -Verb RunAs to request elevation
        ps_command = f"Start-Process '{sys.executable}' -ArgumentList '"{script_path}" {params}' -Verb RunAs"
        
        try:
            subprocess.run(["powershell.exe", "-Command", ps_command], check=True)
            sys.exit(0)
        except Exception as e:
            print(f"Elevation request failed: {e}")
            sys.exit(1)

    print(f"HUB_DIR is {HUB_DIR}")
    hub_log_path = os.path.join(SCRIPT_DIR, "hub_build.log")
    cli_log_path = os.path.join(SCRIPT_DIR, "cli_output.log")


    # Clear previous logs
    for log_file in [hub_log_path, cli_log_path]:
        if os.path.exists(log_file):
            try:
                os.remove(log_file)
            except OSError as e:
                print(f"Warning: Error deleting {log_file}: {e}.")
                # Attempt to proceed

    kill_hub_process()
    time.sleep(3) # Give the OS more time to release file handles

    # Clean previous build artifacts
    for folder in ["bin", "obj"]:
        path_to_delete = os.path.join(HUB_DIR, folder)
        delete_with_retry(path_to_delete)
    
    # Delete .vs folder if it exists
    vs_folder = os.path.join(SCRIPT_DIR, ".vs")
    delete_with_retry(vs_folder)


    hub_log_file = None # Initialize to None outside try block
    hub_process = None # Initialize to None to avoid UnboundLocalError
    try:
        print("\n--- Cleaning OmniSync.Hub ---")
        clean_hub_result = run_command("dotnet clean", cwd=HUB_DIR, log_file=hub_log_path)
        if clean_hub_result is None or clean_hub_result.returncode != 0:
            print(f"OmniSync.Hub clean failed. Aborting. Check {hub_log_path} for details.")
            return # Exit if clean failed

        print("\n--- Clearing NuGet cache (optional) ---")
        clear_nuget_cache_result = run_command("dotnet nuget locals all --clear", cwd=HUB_DIR, log_file=hub_log_path)
        if clear_nuget_cache_result is None or clear_nuget_cache_result.returncode != 0:
            print("Warning: NuGet cache clear failed. Continuing anyway.")

        print("\n--- Restoring OmniSync.Hub dependencies ---")
        restore_hub_result = run_command(f"dotnet restore \"{HUB_DIR}\"", cwd=HUB_DIR, log_file=hub_log_path)
        if restore_hub_result is None or restore_hub_result.returncode != 0:
            print(f"OmniSync.Hub restore failed. Aborting. Check {hub_log_path} for details.")
            return # Exit if restore failed

        print("\n--- Building OmniSync.Hub ---")
        build_hub_result = run_command("dotnet build", cwd=HUB_DIR, log_file=hub_log_path)
        if build_hub_result is None or build_hub_result.returncode != 0:
            print(f"OmniSync.Hub build failed. Aborting. Check {hub_log_path} for details.")
            return # Exit if build failed
        
        # Build successful, delete the build log
        try:
            if os.path.exists(hub_log_path):
                os.remove(hub_log_path)
                print("Build successful. hub_build.log deleted.")
        except Exception as e:
            print(f"Warning: Could not delete {hub_log_path}: {e}")

        time.sleep(1) # Give it a moment

        print("\n--- Starting OmniSync.Hub in background ---")
        
        # Ensure the executable exists before trying to run it
        if not os.path.exists(HUB_EXE_PATH):
            print(f"Error: Hub executable not found at {HUB_EXE_PATH}. Did the build fail?")
            return

        # Use Popen to start the hub process in a detached way
        # We don't redirect to build log anymore as it's for runtime now
        hub_runtime_log = os.path.join(SCRIPT_DIR, "hub_runtime.log")
        with open(hub_runtime_log, "a", encoding="utf-8", errors="replace") as hrl:
            hub_process = subprocess.Popen(
                [HUB_EXE_PATH], # Run the compiled executable directly
                cwd=HUB_DIR,
                stdout=hrl, 
                stderr=hrl, 
                creationflags=subprocess.DETACHED_PROCESS if sys.platform == "win32" else 0, # For Windows, run truly detached
                shell=False # Don't use shell if running exe directly
            )
        print(f"OmniSync.Hub started with PID: {hub_process.pid}")
        
        # Wait a few seconds and check if it's still running
        time.sleep(3)
        
        # Check if it exited
        if hub_process.poll() is not None:
            print(f"ERROR: OmniSync.Hub crashed immediately after starting with exit code {hub_process.returncode}.")
            sys.exit(1)
            
        # Check if crash log was updated recently (in case it's stuck on a popup)
        crash_log = os.path.join(SCRIPT_DIR, "hub_crash_log.log")
        if os.path.exists(crash_log):
            mtime = os.path.getmtime(crash_log)
            if time.time() - mtime < 10: # Updated in last 10 seconds
                print(f"ERROR: OmniSync.Hub seems to have crashed (crash log updated).")
                # Kill it if it's stuck on a popup
                hub_process.kill()
                sys.exit(1)

        print("OmniSync.Hub is still running after 3 seconds.")
        
finally:
    if hub_process:
        print(f"OmniSync.Hub started with PID: {hub_process.pid}")

if __name__ == "__main__":
    main()
