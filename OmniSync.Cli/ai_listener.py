#!/usr/bin/env python3
import time
import logging
import asyncio 
import traceback
import subprocess
import os
import pygetwindow as gw
import pyautogui
import pyperclip
import win32gui
import win32con
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"
GEMINI_CLI_DIR = r"D:\\SSDProjects\\Tools\\gemini-cli"
# Specific titles to avoid accidental matches
TARGET_WINDOW_TITLES = ['OMNI_GEMINI_INTERACTIVE', 'Gemini - gemini-cli'] 
# ---------------------

# Configure logging
log_file = os.path.join(os.getcwd(), "ai_listener.log")
logging.basicConfig(
    level=logging.INFO, 
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler(log_file),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger("AIListener")

connection_started = False
hub = None

def get_target_window():
    titles = gw.getAllTitles()
    # Terminal class names
    VALID_CLASSES = ['ConsoleWindowClass', 'CASCADIA_HOSTING_WINDOW_CLASS']
    
    for preferred in TARGET_WINDOW_TITLES:
        candidates = [t for t in titles if preferred.lower() in t.lower()]
        if candidates:
            for cand in candidates:
                if "AIListener" not in cand and "TortoiseGit" not in cand:
                    wins = gw.getWindowsWithTitle(cand)
                    for win in wins:
                        classname = win32gui.GetClassName(win._hWnd)
                        if classname in VALID_CLASSES:
                            logger.info(f"get_target_window matched: '{cand}' (Class: {classname})")
                            return win
    return None

def is_window_focused(win):
    if not win: return False
    return win32gui.GetForegroundWindow() == win._hWnd

def force_focus(win):
    try:
        if win.isMinimized:
            win.restore()
        
        # Windows often blocks SetForegroundWindow from background processes.
        # A common trick is to simulate an Alt key press to "unlock" the foreground.
        pyautogui.press('alt')
        
        # Multiple attempts at focusing
        for _ in range(3):
            win32gui.ShowWindow(win._hWnd, win32con.SW_RESTORE)
            win32gui.SetForegroundWindow(win._hWnd)
            time.sleep(0.3)
            if is_window_focused(win):
                return True
                
        active_hwnd = win32gui.GetForegroundWindow()
        active_title = win32gui.GetWindowText(active_hwnd)
        logger.error(f"Failed to focus target window {win.title}. Currently focused: {active_title}")
        return False
    except Exception as e:
        logger.error(f"Error forcing focus: {e}")
        return False

async def type_command_to_window(command):
    try:
        target_window = get_target_window()
        if not target_window:
            logger.error("Could not find Gemini CLI window.")
            return "Error: Could not find Gemini CLI window."

        logger.info(f"Targeting window: {target_window.title} (HWND: {target_window._hWnd})")
        
        if not force_focus(target_window):
            return "Error: Could not focus Gemini CLI window."

        time.sleep(1.0) # Wait for focus to stabilize

        # INITIAL CAPTURE
        pyautogui.hotkey('ctrl', 'a')
        time.sleep(0.3)
        pyautogui.hotkey('ctrl', 'insert') # Safe Copy (doesn't trigger SIGINT)
        time.sleep(0.3)
        history_before = pyperclip.paste()
        
        # Deselect
        pyautogui.click(target_window.left + 50, target_window.top + 50)
        time.sleep(0.1)

        # Type the command
        logger.info(f"Typing: {command}")
        pyautogui.write(command, interval=0.01)
        time.sleep(0.2)
        pyautogui.press('enter')
        
        logger.info("Command sent. Waiting 5s for AI to start...")
        await asyncio.sleep(5.0) 
        
        def clean_text(s):
            if not s: return ""
            for char in ['|', '/', '-', '\\', '⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏']:
                s = s.replace(char, '')
            return s.strip()

        history_after = history_before
        current_history = history_before

        for i in range(30):
            await asyncio.sleep(3.0) 
            
            if not is_window_focused(target_window):
                if not force_focus(target_window):
                    continue

            pyautogui.hotkey('ctrl', 'a')
            time.sleep(0.2)
            pyautogui.hotkey('ctrl', 'insert') # Safe Copy
            time.sleep(0.2)
            
            # Deselect
            pyautogui.click(target_window.left + 50, target_window.top + 50)
            time.sleep(0.1)
            
            current_history = pyperclip.paste()
            curr_clean = clean_text(current_history)
            after_clean = clean_text(history_after)
            before_clean = clean_text(history_before)
            
            logger.info(f"Iter {i+1}: Lengths - Before: {len(before_clean)}, After: {len(after_clean)}, Current: {len(curr_clean)}")
            logger.info(f"Capture Sample: {curr_clean[:100]}...")
            
            if curr_clean == after_clean and len(curr_clean) > len(before_clean) + 5:
                logger.info("Output stabilized.")
                history_after = current_history
                break
            
            history_after = current_history

        if history_after.startswith(history_before):
            new_text = history_after[len(history_before):].strip()
        else:
            lines = history_after.splitlines()
            new_text = "\n".join(lines[-20:]).strip()
            
        return clean_text(new_text) if new_text else "No output detected."

    except Exception as e:
        logger.error(f"Error: {e}")
        return f"Error: {str(e)}"

async def run_gemini_cli(message):
    target_window = get_target_window()
    if target_window:
        logger.info(f"Using interactive window: {target_window.title}")
        return await type_command_to_window(message)

    try:
        logger.info("Falling back to direct CLI execution...")
        process = await asyncio.create_subprocess_exec(
            'node', 'bundle/gemini.js', '--prompt', message, '--output-format', 'text',
            cwd=GEMINI_CLI_DIR,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE
        )
        stdout, stderr = await asyncio.wait_for(process.communicate(), timeout=90.0)
        return stdout.decode().strip() if process.returncode == 0 else stderr.decode().strip()
    except Exception as e:
        return f"CLI Error: {str(e)}"

def on_ai_message(args):
    try:
        sender_id, message = args
        our_id = getattr(hub.transport, 'connection_id', None)
        if our_id and sender_id == our_id:
            return 
            
        logger.info(f"Received AI Message from {sender_id}")
        asyncio.run_coroutine_threadsafe(handle_and_reply(message), GLOBAL_LOOP)
    except Exception as e:
        logger.error(f"Error in on_ai_message callback: {e}")

async def handle_and_reply(message):
    try:
        logger.info("Sending AI Status: AI Responding...")
        hub.send("SendAiStatus", ["AI Responding..."])
    except Exception as e:
        logger.error(f"Error sending status to hub: {e}")

    response = await run_gemini_cli(message)
    
    if response:
        logger.info("Sending AI Response back to Hub")
        try:
            hub.send("SendAiResponse", [response])
            hub.send("SendAiStatus", [None]) 
        except Exception as e:
            logger.error(f"Error sending response to hub: {e}")
    else:
        try:
            hub.send("SendAiStatus", [None]) 
        except: pass

def on_close():
    global connection_started
    logger.info("Connection closed.")
    connection_started = False

def on_open():
    global connection_started
    logger.info("Connection opened.")
    connection_started = True

def on_error(error):
    logger.error(f"Connection error: {error}")

GLOBAL_LOOP = None

async def main():
    global hub, GLOBAL_LOOP
    GLOBAL_LOOP = asyncio.get_running_loop()

    hub = HubConnectionBuilder()\
        .with_url(HUB_URL)\
        .configure_logging(logging.INFO)\
        .with_automatic_reconnect({
            "type": "raw",
            "keep_alive_interval": 10,
            "reconnect_interval": 5,
            "max_attempts": 999
        }).build()

    hub.on("ReceiveAiMessage", on_ai_message)
    hub.on_close(on_close) 
    hub.on_open(on_open)   
    hub.on_error(on_error) 

    logger.info(f"Connecting to {HUB_URL}...")
    hub.start()

    while not connection_started:
        await asyncio.sleep(0.5)
    
    hub.send("Authenticate", [API_KEY])
    logger.info("Authenticated. Listening for AI messages...")

    while True:
        await asyncio.sleep(1)

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logger.info("Stopping AI Listener...")
    except Exception as e:
        logger.error(f"Fatal error: {e}")
        traceback.print_exc()
