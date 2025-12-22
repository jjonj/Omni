import subprocess
import os
import sys

def launch_gemini(workspace=None):
    gemini_bundle = r"D:\\SSDProjects\\Tools\\gemini-cli\\bundle\\gemini.js"
    
    if not workspace or workspace.strip() == "":
        workspace = r"D:\\SSDProjects"
    
    workspace = os.path.abspath(workspace)
    
    # Create a temporary batch file to handle everything cleanly
    bat_path = os.path.join(os.getcwd(), "temp_launch.bat")
    try:
        with open(bat_path, "w") as f:
            f.write(f'@echo off\n')
            f.write(f'title OMNI_GEMINI_INTERACTIVE\n')
            f.write(f'cd /d "{workspace}"\n')
            # Use absolute path to node if possible, or just 'node'
            f.write(f'node "{gemini_bundle}" --workspace "{workspace}"\n')
            f.write(f'if %errorlevel% neq 0 pause\n')
            # Clean up itself (optional, might be tricky while running)
            # f.write(f'del "%~f0"\n') 
    except Exception as e:
        print(f"Error creating batch file: {e}")
        return

    print(f"Launching Gemini CLI via batch file...")
    print(f"  Workspace: {workspace}")
    
    new_env = os.environ.copy()
    new_env["GEMINI_DEBUG_LOG_FILE"] = os.path.join(os.getcwd(), "gemini_cli_debug.log")
    
    try:
        # Launch the batch file in a new console
        subprocess.Popen(
            ['cmd.exe', '/C', 'start', 'cmd.exe', '/K', bat_path],
            creationflags=subprocess.CREATE_NEW_CONSOLE,
            close_fds=True,
            env=new_env
        )
        print("Success: Launch command issued.")
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    if len(sys.argv) > 1:
        target = sys.argv[-1].strip().strip('"').strip("'")
    else:
        target = None
    launch_gemini(target)