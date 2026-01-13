import pygetwindow as gw
import pyautogui
import pyperclip
import time
import os
import subprocess

def test_read_gemini_cli():
    gemini_dir = r"D:\SSDProjects\Tools\gemini-cli"
    print("Launching new Gemini CLI...")
    cmd = f'start cmd.exe /K "title GEMINI_TARGET && cd /d {gemini_dir} && node bundle/gemini.js"'
    os.system(cmd)
    
    print("Waiting for window to appear...")
    # Wait loop
    target_window = None
    for i in range(20):
        titles = gw.getAllTitles()
        candidates = [t for t in titles if 'GEMINI_TARGET' in t]
        if candidates:
            target_window = gw.getWindowsWithTitle(candidates[0])[0]
            print(f"Found window: {candidates[0]}")
            break
        time.sleep(1)
        
    if not target_window:
        print("Error: Could not find GEMINI_TARGET window.")
        return

    # Bring to front
    try:
        if target_window.isMinimized:
            target_window.restore()
        target_window.activate()
        time.sleep(2.0)
    except Exception as e:
        print(f"Error activating window: {e}")
        return

    # 3. Send a message
    test_message = "What is the capital of France?"
    print(f"Sending message: {test_message}")
    pyautogui.write(test_message, interval=0.01)
    pyautogui.press('enter')
    
    print("Waiting for response (15s)...")
    time.sleep(15.0) # Wait for Gemini to respond

    target_window.activate()
    time.sleep(1.0)
    
    print("Attempting to copy history...")
    # Try multiple ways to copy
    pyautogui.hotkey('ctrl', 'a')
    time.sleep(0.5)
    pyautogui.hotkey('ctrl', 'c')
    time.sleep(0.5)
    
    # 5. Read from clipboard
    history = pyperclip.paste()
    
    if history:
        print("--- RETRIEVED HISTORY (FIRST 500 CHARS) ---")
        print(history[:500])
        print("--- END PREVIEW ---")
        
        with open("TestScripts/captured_history.txt", "w", encoding="utf-8") as f:
            f.write(history)
    else:
        print("Failed to retrieve history from clipboard.")

if __name__ == "__main__":
    test_read_gemini_cli()