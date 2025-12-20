#!/usr/bin/env python3
import time
import logging
import asyncio 
import traceback
import subprocess
import os
import json
import psutil
import win32file
import win32pipe
import pywintypes
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"
GEMINI_CLI_DIR = r"D:\\SSDProjects\\Tools\\gemini-cli"
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
GLOBAL_LOOP = None

def get_gemini_pid():
    """Finds the PID of the Gemini CLI process."""
    best_pid = None
    for proc in psutil.process_iter(['pid', 'name', 'cmdline']):
        try:
            cmdline = " ".join(proc.info['cmdline'] or [])
            # Target the node process running the gemini bundle
            if 'node' in proc.info['name'].lower() and 'gemini' in cmdline.lower():
                # Exclude the listener itself
                if "ai_listener" not in cmdline.lower() and "modify_gemini" not in cmdline.lower():
                    # Prioritize the bundle version
                    if 'bundle/gemini.js' in cmdline.replace('\\', '/'):
                        logger.info(f"Found Gemini bundle process: PID {proc.info['pid']}")
                        return proc.info['pid']
                    best_pid = proc.info['pid']
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            pass
    return best_pid

def sync_pipe_comm(pid, command_text):
    """Synchronous part of pipe communication to be run in a thread."""
    pipe_path = f"\\\\.\\pipe\\gemini-cli-{pid}"
    logger.info(f"Connecting to pipe: {pipe_path}")
    
    try:
        # Connect to the named pipe
        handle = win32file.CreateFile(
            pipe_path,
            win32file.GENERIC_READ | win32file.GENERIC_WRITE,
            0,
            None,
            win32file.OPEN_EXISTING,
            0,
            None
        )
        
        payload = json.dumps({"command": "prompt", "text": command_text}) + "\n"
        win32file.WriteFile(handle, payload.encode())
        
        logger.info("Command sent. Waiting for response...")
        
        # Read response from pipe (blocking call)
        # Using a simple read as the response is expected in one JSON line
        hr, data = win32file.ReadFile(handle, 1024 * 1024) # 1MB buffer
        full_resp = data.decode().strip()
        
        win32file.CloseHandle(handle)
        
        if not full_resp:
            return "Error: Empty response from pipe."
            
        response_msg = json.loads(full_resp)
        if response_msg.get('type') == 'response':
            return response_msg.get('text', 'No output detected.')
        else:
            return f"Unexpected response type: {response_msg.get('type')}"
            
    except Exception as e:
        logger.error(f"Pipe communication failed: {e}")
        return f"Error: {str(e)}"

async def send_remote_command(command_text):
    pid = get_gemini_pid()
    if not pid:
        return "Error: Could not find Gemini CLI process. Is it running?"
    
    # Run synchronous pipe I/O in a separate thread to avoid blocking the event loop
    return await asyncio.to_thread(sync_pipe_comm, pid, command_text)

async def handle_and_reply(message):
    try:
        logger.info("Sending AI Status: Thinking...")
        hub.send("SendAiStatus", ["Thinking..."])
    except Exception as e:
        logger.error(f"Error sending status to hub: {e}")

    response = await send_remote_command(message)
    
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

def on_ai_message(args):
    try:
        sender_id, message = args
        our_id = getattr(hub.transport, 'connection_id', None)
        if our_id and sender_id == our_id:
            return 
            
        logger.info(f"Received AI Message: {message[:50]}...")
        asyncio.run_coroutine_threadsafe(handle_and_reply(message), GLOBAL_LOOP)
    except Exception as e:
        logger.error(f"Error in on_ai_message callback: {e}")

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
    logger.info("Authenticated. Listening for AI messages via Named Pipe Hook.")

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