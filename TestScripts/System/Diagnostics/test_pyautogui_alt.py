import pygetwindow as gw
import pyautogui
import pyperclip
import time

def test_pyautogui_alt():
    title = "Gemini - gemini-cli"
    try:
        window = gw.getWindowsWithTitle(title)[0]
    except:
        print(f"Window {title} not found")
        return

    window.activate()
    time.sleep(1)
    
    # Click to ensure focus
    pyautogui.click(window.left + 100, window.top + 100)
    time.sleep(0.5)
    
    # Try typing something
    pyautogui.write("test_observation", interval=0.01)
    pyautogui.press('enter')
    time.sleep(2)
    
    # Try to copy via System Menu (Alt+Space, E, S for Select All, then Enter)
    print("Attempting Alt+Space copy...")
    pyautogui.hotkey('alt', 'space')
    time.sleep(0.2)
    pyautogui.press('e')
    time.sleep(0.2)
    pyautogui.press('s') # Select All
    time.sleep(0.2)
    pyautogui.press('enter') # Enter copies selection in CMD
    
    content = pyperclip.paste()
    print("--- CLIPBOARD ---")
    print(content[-500:])
    print("--- END ---")

if __name__ == "__main__":
    test_pyautogui_alt()
