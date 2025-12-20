import os
import sys
import time

def check_gemini_help_visible():
    gemini_dir = r"D:\SSDProjects\Tools\gemini-cli"
    print(f"Checking Gemini CLI help in visible window...")
    
    if sys.platform == "win32":
        try:
            # We run --help and keep it open with /K
            cmd = f'start cmd.exe /K "title GEMINI_HELP && cd /d {gemini_dir} && node bundle/gemini.js --help"'
            os.system(cmd)
            print("Command sent.")
        except Exception as e:
            print(f"Error: {e}")
    else:
        print("This script is designed for Windows.")

if __name__ == "__main__":
    check_gemini_help_visible()