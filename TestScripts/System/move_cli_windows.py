import pygetwindow as gw
from screeninfo import get_monitors
import sys
import time

def move_cli_windows():
    targets = ['cmd.exe', 'powershell.exe', 'OMNI_GEMINI_INTERACTIVE', 'Windows PowerShell']
    
    monitors = get_monitors()
    secondary = next((m for m in monitors if not m.is_primary), None)
    
    if not secondary:
        print("No secondary monitor found.")
        return

    target_x = secondary.x + 50
    target_y = secondary.y + 50

    print(f"Moving windows to X={target_x}, Y={target_y}")
    
    for window in gw.getAllWindows():
        title = window.title
        if any(t.lower() in title.lower() for t in targets):
            try:
                if window.isMinimized:
                    window.restore()
                window.moveTo(target_x, target_y)
                window.resize(1200, 800)
                print(f"Moved: {title}")
            except:
                pass

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--delay":
        time.sleep(2)
    move_cli_windows()
