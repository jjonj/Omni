import pygetwindow as gw
import pyautogui
import time
import sys

def poc_gemini_control():
    # Target command
    command = "Hello Myself"
    
    print("Looking for Gemini CLI window...")
    
    # Try to find window
    target_window = None
    titles = gw.getAllTitles()
    
    # Heuristic: Look for 'gemini' or 'PowerShell' or 'cmd'
    candidates = [t for t in titles if 'gemini' in t.lower()]
    if not candidates:
        candidates = [t for t in titles if 'powershell' in t.lower()]
    if not candidates:
        candidates = [t for t in titles if 'cmd' in t.lower()]
        
    if not candidates:
        print("Error: Could not find any likely Gemini CLI window (Gemini, PowerShell, cmd).")
        return

    # Pick the first one (or maybe ask user? For POC, just pick first)
    # Prefer one that is NOT this script's console if possible?
    # Usually this script runs in a console too.
    
    print(f"Found candidates: {candidates}")
    target_title = candidates[0]
    target_window = gw.getWindowsWithTitle(target_title)[0]
    
    print(f"Targeting window: '{target_title}'")
    
    try:
        if target_window.isMinimized:
            target_window.restore()
        target_window.activate()
        time.sleep(1.0) # Wait for focus
    except Exception as e:
        print(f"Error activating window: {e}")
        return

    print(f"Typing command: {command}")
    pyautogui.write(command, interval=0.05)
    pyautogui.press('enter')
    
    print("Command sent.")

if __name__ == "__main__":
    poc_gemini_control()
