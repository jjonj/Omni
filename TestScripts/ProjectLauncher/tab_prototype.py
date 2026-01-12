import os
import time
import subprocess
import win32gui
import win32con
import win32com.client

def open_onecommander_tab(path):
    print(f"Opening OneCommander tab: {path}")
    oc_path = r"E:\Program Files\OneCommander\OneCommander.exe"
    try:
        subprocess.run([oc_path, '-o', path, '-newtab'], check=True)
    except Exception as e:
        print(f"Error opening OneCommander: {e}")

def open_explorer_tab(path):
    print(f"Opening Explorer tab: {path}")
    
    # Try to find an existing Explorer window
    hwnd = win32gui.FindWindow("CabinetWClass", None)
    
    if not hwnd:
        print("No Explorer window found, opening new one.")
        subprocess.run(['explorer.exe', path])
        return

    print(f"Found Explorer window (HWND: {hwnd}), bringing to foreground.")
    
    # Bring to foreground
    # Sometimes SetForegroundWindow fails if the caller isn't the foreground process
    # We use a trick by sending an ALT key first or using ShowWindow
    win32gui.ShowWindow(hwnd, win32con.SW_RESTORE)
    shell = win32com.client.Dispatch("WScript.Shell")
    shell.SendKeys('%') # Send ALT to bypass focus restrictions
    win32gui.SetForegroundWindow(hwnd)
    
    time.sleep(0.5) 
    
    # Send Ctrl+T for new tab
    shell.SendKeys("^t")
    time.sleep(0.5)
    
    # Send Alt+D for address bar
    shell.SendKeys("%d")
    time.sleep(0.3)
    
    # Type path and Enter
    # We need to escape some characters if they are special in SendKeys
    # But for paths, we mostly care about brackets {{}} and parenthesis ()
    # Actually SendKeys uses {{ }} for special keys.
    safe_path = path.replace("{", "{{{{").replace("}}", "}}}}")
    shell.SendKeys(safe_path)
    shell.SendKeys("{ENTER}")

if __name__ == "__main__":
    test_path_1 = os.getcwd()
    test_path_2 = r"C:\Windows"
    
    # print("Testing Explorer Tab Opening...")
    # open_explorer_tab(test_path_1)
    
    print("\nTesting OneCommander Tab Opening...")
    open_onecommander_tab(test_path_1)
    time.sleep(1)
    open_onecommander_tab(test_path_2)
