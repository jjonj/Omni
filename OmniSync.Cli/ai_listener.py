#!/usr/bin/env python3
import time
import logging
import asyncio 
import traceback
import subprocess
import os
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"
GEMINI_CLI_DIR = r"D:\\SSDProjects\\Tools\\gemini-cli"
# ---------------------

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("AIListener")

connection_started = False
hub = None

async def run_gemini_cli(message):
    try:
        logger.info(f"Processing message with Gemini CLI: {message[:50]}...")
        # Use node to run the bundled gemini-cli non-interactively
        # Using a direct call to node with the bundled script.
        # We add a timeout to avoid hanging indefinitely (e.g. on 429 retries)
        
        process = await asyncio.create_subprocess_exec(
            'node', 'bundle/gemini.js', '--prompt', message, '--output-format', 'text',
            cwd=GEMINI_CLI_DIR,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE
        )
        
        try:
            stdout, stderr = await asyncio.wait_for(process.communicate(), timeout=60.0)
        except asyncio.TimeoutError:
            process.kill()
            logger.error("Gemini CLI timed out.")
            return "Error: Gemini CLI timed out after 60 seconds."

        if process.returncode == 0:
            response = stdout.decode().strip()
            if not response:
                return "Error: Gemini CLI returned empty response."
            return response
        else:
            error_msg = stderr.decode().strip()
            # If stdout has content even on failure, it might contain the 429 message
            if not error_msg:
                error_msg = stdout.decode().strip()
            logger.error(f"Gemini CLI error (Code {process.returncode}): {error_msg}")
            return f"CLI Error: {error_msg}"
            
    except Exception as e:
        logger.error(f"Exception running Gemini CLI: {e}")
        return f"Exception: {str(e)}"

def on_ai_message(args):
    try:
        sender_id, message = args
        # Check our own connection id to avoid loops
        our_id = getattr(hub.transport, 'connection_id', None)
        if our_id and sender_id == our_id:
            return 
            
        logger.info(f"Received AI Message from {sender_id}")
        
        # Schedule the handler in the global loop
        asyncio.run_coroutine_threadsafe(handle_and_reply(message), GLOBAL_LOOP)
    except Exception as e:
        logger.error(f"Error in on_ai_message callback: {e}")

async def handle_and_reply(message):
    response = await run_gemini_cli(message)
    if response:
        logger.info("Sending AI Response back to Hub")
        try:
            hub.send("SendAiResponse", [response])
        except Exception as e:
            logger.error(f"Error sending response to hub: {e}")

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

    # Wait for connection
    while not connection_started:
        await asyncio.sleep(0.5)
    
    hub.send("Authenticate", [API_KEY])
    logger.info("Authenticated. Listening for AI messages...")

    # Keep running
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