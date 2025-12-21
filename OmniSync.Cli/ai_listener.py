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
import argparse
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
    logger.info("Searching for Gemini processes...")
    for proc in psutil.process_iter(['pid', 'name', 'cmdline']):
        try:
            cmdline_list = proc.info['cmdline'] or []
            cmdline = " ".join(cmdline_list)
            name = proc.info['name'] or ""
            
            # Target the node process running the gemini bundle
            if 'node' in name.lower() and 'gemini' in cmdline.lower():
                logger.info(f"Checking potential node/gemini process: PID {proc.info['pid']} - Cmd: {cmdline}")
                # Exclude the listener itself
                if "ai_listener" not in cmdline.lower() and "modify_gemini" not in cmdline.lower():
                    # STRONGLY Prioritize the local bundle version
                    if 'bundle/gemini.js' in cmdline.replace('\\', '/'):
                        logger.info(f"MATCH: Found local Gemini bundle process: PID {proc.info['pid']}")
                        return proc.info['pid']
                    
                    # Also prioritize SSDProjects dist version
                    if 'ssdprojects' in cmdline.lower() and 'dist/index.js' in cmdline.replace('\\', '/'):
                        logger.info(f"MATCH: Found local Gemini dist process: PID {proc.info['pid']}")
                        return proc.info['pid']
                    
                    # Fallback to other bundle/dist versions
                    if 'bundle/gemini.js' in cmdline.replace('\\', '/') or 'dist/index.js' in cmdline.replace('\\', '/'):
                        if not best_pid: # Keep the first one found as fallback
                            best_pid = proc.info['pid']
                            logger.info(f"POTENTIAL FALLBACK: Found generic bundle process: PID {proc.info['pid']}")
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            pass
    
    if best_pid:
        logger.info(f"FALLBACK: Using best found PID {best_pid}")
    else:
        logger.warning("No Gemini process found.")
    return best_pid

def sync_pipe_comm(pid, command_text):
    """Synchronous part of pipe communication to be run in a thread."""
    pipe_path = f"\\\\.\\pipe\\gemini-cli-{pid}"
    logger.info(f"Connecting to pipe: {pipe_path}")
    
    handle = None
    # Retry connecting for a few seconds if pipe is not yet available
    for i in range(10):
        try:
            handle = win32file.CreateFile(
                pipe_path,
                win32file.GENERIC_READ | win32file.GENERIC_WRITE,
                0,
                None,
                win32file.OPEN_EXISTING,
                0,
                None
            )
            break
        except Exception as e:
            if i == 9:
                logger.error(f"Pipe communication failed after 10 retries: {e}")
                return f"Error: {str(e)}"
            time.sleep(1)
            
    try:
        payload = json.dumps({"command": "prompt", "text": command_text}) + "\n"
        win32file.WriteFile(handle, payload.encode())
        
        logger.info("Command sent. Collecting response parts...")
        
        full_text = ""
        # We'll use a timeout to avoid hanging forever if something goes wrong
        start_time = time.time()
        timeout = 120 # 2 minutes
        
        while time.time() - start_time < timeout:
            # Peek to see if data is available
            _, bytes_avail, _ = win32pipe.PeekNamedPipe(handle, 0)
            if bytes_avail > 0:
                hr, data = win32file.ReadFile(handle, bytes_avail)
                chunk = data.decode().strip()
                if not chunk:
                    continue
                
                # Each line is a JSON message
                lines = chunk.split('\n')
                for line in lines:
                    if not line.strip(): continue
                    try:
                        msg = json.loads(line)
                        if msg.get('type') == 'response':
                            text = msg.get('text', '')
                            if text == '[TURN_FINISHED]':
                                win32file.CloseHandle(handle)
                                return full_text.strip() or "[No Output]"
                            elif text == '[Command Handled]':
                                pass
                            else:
                                full_text += text + "\n"
                        
                        # If we received exactly one line and it was a valid 'response' 
                        # but NOT a finished marker, and there's no more data available,
                        # it might be an older CLI version that sends everything in one go.
                        if len(lines) == 1 and not text == '[TURN_FINISHED]':
                            # Check if more data is coming
                            time.sleep(0.5)
                            _, still_avail, _ = win32pipe.PeekNamedPipe(handle, 0)
                            if still_avail == 0:
                                win32file.CloseHandle(handle)
                                return full_text.strip() or "[No Output]"
                                
                    except json.JSONDecodeError:
                        continue
            else:
                # Sleep briefly to avoid busy loop
                time.sleep(0.1)
                
        win32file.CloseHandle(handle)
        if full_text:
            return full_text.strip()
        return "Error: Timeout waiting for [TURN_FINISHED] response from pipe."
            
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

    # Use named pipe for all commands (including slash commands)
    response = await send_remote_command(message)
    
    if response:
        logger.info("Sending AI Response back to Hub")
        
        # Check if the response contains a Hub Command
        # Format: HUB_COMMAND: {"Command": "...", "Payload": {...}}
        if "HUB_COMMAND:" in response:
            try:
                parts = response.split("HUB_COMMAND:", 1)
                cmd_json_str = parts[1].strip()
                # Extract only the first valid JSON object if there's trailing text
                # Simple heuristic: find the last '}'
                last_brace = cmd_json_str.rfind('}')
                if last_brace != -1:
                    cmd_json_str = cmd_json_str[:last_brace+1]
                
                cmd_data = json.loads(cmd_json_str)
                cmd_name = cmd_data.get("Command")
                cmd_payload = cmd_data.get("Payload", {})
                
                if cmd_name:
                    logger.info(f"Forwarding AI Hub Command: {cmd_name}")
                    hub.send("SendAiHubCommand", [cmd_name, cmd_payload])
            except Exception as e:
                logger.error(f"Failed to parse AI Hub Command: {e}")

        print("\n" + "="*60)
        print(f"CAPTURED AI RESPONSE:\n{response}")
        print("="*60 + "\n")
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

import argparse

# ... existing imports ...

async def main():
    global hub, GLOBAL_LOOP
    GLOBAL_LOOP = asyncio.get_running_loop()

    parser = argparse.ArgumentParser(description="AI Listener for OmniSync")
    parser.add_argument("--pid", type=int, help="Specific Gemini PID to target")
    args = parser.parse_args()
    
    target_pid = args.pid
    if target_pid:
        logger.info(f"Targeting specific Gemini PID: {target_pid}")
        # Override get_gemini_pid to return this PID
        global get_gemini_pid
        original_get_pid = get_gemini_pid
        get_gemini_pid = lambda: target_pid

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