import subprocess
import os
import sys

def launch_gemini(workspace=None):
    gemini_dir = r"D:\SSDProjects\Tools\omni-omni-gemini-cli"
    
    if not workspace or workspace.strip() == "":
        workspace = r"D:\SSDProjects"
    workspace = os.path.abspath(workspace)
    
    log_file = os.path.join(os.getcwd(), "gemini_cli_debug.log")
    if os.path.exists(log_file):
        try: os.remove(log_file)
        except: pass

    # DUPLICATING THE WORKING COMMAND STRUCTURE EXACTLY:
    # No quotes around gemini_dir, as in the old script.
    # We only append the workspace argument.
    # CRITICAL FIX: Do NOT put quotes around the workspace path here. 
    # It seems they are being passed literally to the node process, causing it to fail path resolution.
    cmd = f'title OMNI_GEMINI_INTERACTIVE && cd /d {gemini_dir} && node bundle/gemini.js --workspace {workspace}'
    
    print(f"Launching Gemini CLI in new console...")
    print(f"  Workspace: {workspace}")
    
    new_env = os.environ.copy()
    new_env["GEMINI_DEBUG_LOG_FILE"] = log_file
    
    try:
        subprocess.Popen(
            ['cmd.exe', '/K', cmd],
            creationflags=subprocess.CREATE_NEW_CONSOLE,
            close_fds=True,
            env=new_env
        )
        print("Success.")
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    target = None
    if len(sys.argv) > 1:
        target = sys.argv[-1].strip().strip('"').strip("'")
    launch_gemini(target)
