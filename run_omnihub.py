import subprocess
import time
import os
import sys
import shutil

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

def main():
    print(f"HUB_DIR is {HUB_DIR}")
    hub_log_path = os.path.join(os.environ.get("GEMINI_TEMP_DIR", ""), "hub_output.log")
    cli_log_path = os.path.join(os.environ.get("GEMINI_TEMP_DIR", ""), "cli_output.log")


    # Clear previous logs
    for log_file in [hub_log_path, cli_log_path]:
        if os.path.exists(log_file):
            try:
                os.remove(log_file)
            except OSError as e:
                print(f"Error deleting {log_file}: {e}. Please ensure no other process is using it.")
                # Attempt to proceed, but user might need to intervene
                time.sleep(1)

    # Clean previous build artifacts
    for folder in ["bin", "obj"]:
        path_to_delete = os.path.join(HUB_DIR, folder)
        if os.path.exists(path_to_delete):
            print(f"Deleting {path_to_delete}")
            shutil.rmtree(path_to_delete)
    
    # Delete .vs folder if it exists
    vs_folder = os.path.join(SCRIPT_DIR, ".vs")
    if os.path.exists(vs_folder):
        print(f"Deleting {vs_folder}")
        shutil.rmtree(vs_folder)

    kill_hub_process()

    hub_log_file = None # Initialize to None outside try block
    hub_process = None # Initialize to None to avoid UnboundLocalError
    try:
        print("\n--- Cleaning OmniSync.Hub ---")
        clean_hub_result = run_command("dotnet clean", cwd=HUB_DIR, log_file=hub_log_path)
        if clean_hub_result is None or clean_hub_result.returncode != 0:
            print("OmniSync.Hub clean failed. Aborting. Check hub_output.log for details.")
            return # Exit if clean failed

        print("\n--- Clearing NuGet cache (optional) ---")
        clear_nuget_cache_result = run_command("dotnet nuget locals all --clear", cwd=HUB_DIR, log_file=hub_log_path)
        if clear_nuget_cache_result is None or clear_nuget_cache_result.returncode != 0:
            print("Warning: NuGet cache clear failed. Continuing anyway. Check hub_output.log for details.")
            # Do not return, continue with the rest of the script

        print("\n--- Restoring OmniSync.Hub dependencies ---")
        restore_hub_result = run_command(f"dotnet restore \"{HUB_DIR}\"", cwd=HUB_DIR, log_file=hub_log_path)
        if restore_hub_result is None or restore_hub_result.returncode != 0:
            print("OmniSync.Hub restore failed. Aborting. Check hub_output.log for details.")
            return # Exit if restore failed

        print("\n--- Building OmniSync.Hub ---")
        build_hub_result = run_command("dotnet build", cwd=HUB_DIR, log_file=hub_log_path)
        if build_hub_result is None or build_hub_result.returncode != 0:
            print("OmniSync.Hub build failed. Aborting. Check hub_output.log for details.")
            return # Exit if build failed
        time.sleep(1) # Give it a moment

        print("\n--- Starting OmniSync.Hub in background ---")
        
        hub_log_file = open(hub_log_path, "a", encoding="utf-8", errors="replace") # Open the file once and keep it open
        hub_log_file.write(f"\n--- Starting OmniSync.Hub (PID will be known after Popen) ---\\n")

        # Ensure the executable exists before trying to run it
        if not os.path.exists(HUB_EXE_PATH):
            print(f"Error: Hub executable not found at {HUB_EXE_PATH}. Did the build fail?")
            return

        # Use Popen to start the hub process in a detached way
        hub_process = subprocess.Popen(
            [HUB_EXE_PATH], # Run the compiled executable directly
            cwd=HUB_DIR,
            stdout=hub_log_file, # Redirect stdout to the open file
            stderr=hub_log_file, # Redirect stderr to the open file
            creationflags=subprocess.DETACHED_PROCESS if sys.platform == "win32" else 0, # For Windows, run truly detached
            shell=False # Don't use shell if running exe directly
        )
        print(f"OmniSync.Hub started with PID: {hub_process.pid}")
        
        time.sleep(5)
        
    finally:
        if hub_process:
            print(f"OmniSync.Hub started with PID: {hub_process.pid}")

if __name__ == "__main__":
    main()
