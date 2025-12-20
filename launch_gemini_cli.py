import subprocess
import os
import sys

def launch_gemini():
    gemini_dir = r"D:\SSDProjects\Tools\gemini-cli"
    # /K to keep open, 'title' to set title
    cmd = f'title OMNI_GEMINI_INTERACTIVE && cd /d {gemini_dir} && node bundle/gemini.js'
    
    print(f"Launching Gemini CLI in new console with title: OMNI_GEMINI_INTERACTIVE")
    try:
        subprocess.Popen(
            ['cmd.exe', '/K', cmd],
            creationflags=subprocess.CREATE_NEW_CONSOLE,
            close_fds=True
        )
        print("Success.")
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    launch_gemini()
