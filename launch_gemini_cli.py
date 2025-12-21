import subprocess
import os
import sys

def launch_gemini():
    gemini_dir = r"D:\SSDProjects\Tools\gemini-cli"
    log_file = os.path.join(os.getcwd(), "gemini_cli_debug.log")
    if os.path.exists(log_file):
        try: os.remove(log_file)
        except: pass

    # /K to keep open, 'title' to set title
    cmd = f'title OMNI_GEMINI_INTERACTIVE && cd /d {gemini_dir} && node bundle/gemini.js'
    
    print(f"Launching Gemini CLI in new console with title: OMNI_GEMINI_INTERACTIVE")
    print(f"Debug log: {log_file}")
    
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
    launch_gemini()