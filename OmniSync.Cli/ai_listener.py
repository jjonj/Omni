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
TARGET_PID = None

def get_all_gemini_pids():
    """Finds all PIDs of Gemini CLI processes."""
    pids = []
    for proc in psutil.process_iter(['pid', 'name', 'cmdline']):
        try:
            cmdline = " ".join(proc.info['cmdline'] or [])
            name = proc.info['name'] or ""
            if 'node' in name.lower() and 'gemini' in cmdline.lower():
                # Check for bundle/gemini.js or dist/index.js
                if 'bundle/gemini.js' in cmdline.replace('\\', '/') or 'dist/index.js' in cmdline.replace('\\', '/'):
                    pids.append(proc.info['pid'])
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            pass
    return pids

async def handle_get_sessions(args):
    pids = get_all_gemini_pids()
    logger.info(f"Discovery found PIDs: {pids}")
    hub.send("ReceiveAiSessions", [pids])

async def handle_switch_session(args):
    global TARGET_PID
    pid = args[0]
    TARGET_PID = pid
    logger.info(f"Switched to Gemini PID: {pid}")
    
    # Fetch history for the new session
    history_resp = await asyncio.to_thread(sync_pipe_comm, pid, "", "getHistory")
    if history_resp.startswith("[HISTORY_DATA]"):
        history_json = history_resp[len("[HISTORY_DATA]"):]
        hub.send("ReceiveAiHistory", [history_json])

def on_get_sessions(args):
    asyncio.run_coroutine_threadsafe(handle_get_sessions(args), GLOBAL_LOOP)

def on_switch_session(args):
    asyncio.run_coroutine_threadsafe(handle_switch_session(args), GLOBAL_LOOP)

def get_gemini_pid():
    """Finds the PID of the Gemini CLI process, prioritizing local development versions."""
    best_pid = None
    fallback_pid = None
    logger.info("Searching for Gemini processes...")
    for proc in psutil.process_iter(['pid', 'name', 'cmdline']):
        try:
            cmdline_list = proc.info['cmdline'] or []
            cmdline = " ".join(cmdline_list)
            name = proc.info['name'] or ""
            
            if 'node' in name.lower() and 'gemini' in cmdline.lower():
                # Exclude the listener itself and other helper scripts
                if "ai_listener" not in cmdline.lower() and "modify_gemini" not in cmdline.lower():
                    cmdline_norm = cmdline.replace('\\', '/')
                    
                    # Highest priority: Local bundle version
                    if 'bundle/gemini.js' in cmdline_norm and 'SSDProjects' in cmdline:
                        logger.info(f"MATCH (High Priority): Found local Gemini bundle: PID {proc.info['pid']}")
                        return proc.info['pid']
                    
                    # High priority: Any bundle version
                    if 'bundle/gemini.js' in cmdline_norm:
                        if not best_pid:
                            logger.info(f"MATCH (Medium Priority): Found bundle process: PID {proc.info['pid']}")
                            best_pid = proc.info['pid']
                    
                    # Fallback: Any dist version
                    if 'dist/index.js' in cmdline_norm:
                        if not fallback_pid:
                            logger.info(f"MATCH (Low Priority): Found dist process: PID {proc.info['pid']}")
                            fallback_pid = proc.info['pid']
                            
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            pass
            
    if best_pid:
        return best_pid
    if fallback_pid:
        logger.warning(f"Using fallback dist process: PID {fallback_pid}")
    return fallback_pid

def sync_pipe_comm(pid, command_text, command_type="prompt"):
    """Synchronous part of pipe communication to be run in a thread."""
    pipe_path = f"\\\\.\\pipe\\gemini-cli-{pid}"
    logger.info(f"Connecting to pipe: {pipe_path} for {command_type}")
    
    handle = None
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
        if command_type == "getHistory":
            payload = json.dumps({"command": "getHistory"}) + "\n"
        else:
            payload = json.dumps({"command": "prompt", "text": command_text}) + "\n"
            
        win32file.WriteFile(handle, payload.encode())
        
        full_text = ""
        start_time = time.time()
        timeout = 120 
        
        while time.time() - start_time < timeout:
            _, bytes_avail, _ = win32pipe.PeekNamedPipe(handle, 0)
            if bytes_avail > 0:
                hr, data = win32file.ReadFile(handle, bytes_avail)
                chunk = data.decode().strip()
                if not chunk: continue
                
                lines = chunk.split('\n')
                for line in lines:
                    if not line.strip(): continue
                    try:
                        msg = json.loads(line)
                        if msg.get('type') == 'response':
                            text = msg.get('text', '')
                            
                            if '[HISTORY_START]' in text:
                                start_idx = text.find('[HISTORY_START]') + len('[HISTORY_START]')
                                end_idx = text.find('[HISTORY_END]', start_idx)
                                if end_idx != -1:
                                    history_json = text[start_idx:end_idx]
                                    win32file.CloseHandle(handle)
                                    return f"[HISTORY_DATA]{history_json}"

                            if text == '[TURN_FINISHED]':
                                win32file.CloseHandle(handle)
                                return full_text.strip() or "[No Output]"
                            elif text == '[Command Handled]':
                                pass
                            else:
                                full_text += text + "\n"
                    except json.JSONDecodeError:
                        continue
            else:
                time.sleep(0.1)
                
        win32file.CloseHandle(handle)
        return full_text.strip() or "Error: Timeout waiting for response."
            
    except Exception as e:
        if handle: win32file.CloseHandle(handle)
        logger.error(f"Pipe communication failed: {e}")
        return f"Error: {str(e)}"

async def send_remote_command(command_text):
    global TARGET_PID
    pid = TARGET_PID if TARGET_PID else get_gemini_pid()
    
    if not pid:
        logger.info("No Gemini CLI found. Auto-starting new session...")
        hub.send("SendAiStatus", ["Starting Gemini..."])
        
        # Launch Gemini CLI
        launch_script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "launch_gemini_cli.py")
        if os.path.exists(launch_script):
            subprocess.Popen(['python', launch_script], shell=True)
        else:
            # Fallback if running from a different working dir
            subprocess.Popen(['python', r'D:\SSDProjects\Omni\launch_gemini_cli.py'], shell=True)
            
        # Wait for it to appear
        for i in range(20): # Wait up to 10 seconds
            await asyncio.sleep(0.5)
            pid = get_gemini_pid()
            if pid:
                logger.info(f"Gemini started with PID: {pid}")
                TARGET_PID = pid
                hub.send("SendAiStatus", ["Thinking..."])
                break
        
        if not pid:
            return "Error: Failed to auto-start Gemini CLI."

    # Run synchronous pipe I/O in a separate thread to avoid blocking the event loop
    return await asyncio.to_thread(sync_pipe_comm, pid, command_text)

async def handle_and_reply(message):
    try:
        hub.send("SendAiStatus", ["Thinking..."])
    except Exception as e:
        logger.error(f"Error sending status to hub: {e}")

    response = await send_remote_command(message)
    
    if response:
        if "HUB_COMMAND:" in response:
            try:
                parts = response.split("HUB_COMMAND:", 1)
                cmd_json_str = parts[1].strip()
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

    parser = argparse.ArgumentParser(description="AI Listener for OmniSync")
    parser.add_argument("--pid", type=int, help="Specific Gemini PID to target")
    args = parser.parse_args()
    
    global TARGET_PID
    if args.pid:
        TARGET_PID = args.pid
        logger.info(f"Initial target Gemini PID: {TARGET_PID}")

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
    hub.on("RequestAiSessions", on_get_sessions)
    hub.on("SwitchAiSession", on_switch_session)
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