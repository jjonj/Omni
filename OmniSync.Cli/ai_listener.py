#!/usr/bin/env python3
import time
import logging
import asyncio 
import traceback
import subprocess
import os
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://10.0.0.37:5000/signalrhub"
API_KEY = "test_api_key"
GEMINI_CLI_DIR = r"D:\SSDProjects\Tools\gemini-cli"
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
        process = await asyncio.create_subprocess_exec(
            'node', 'bundle/gemini.js', '--prompt', message, '--output-format', 'text',
            cwd=GEMINI_CLI_DIR,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE
        )
        
        stdout, stderr = await process.communicate()
        
        if process.returncode == 0:
            response = stdout.decode().strip()
            # Remove any greeting or CLI junk if possible
            return response
        else:
            error_msg = stderr.decode().strip()
            logger.error(f"Gemini CLI error: {error_msg}")
            return f"Error: {error_msg}"
            
    except Exception as e:
        logger.error(f"Exception running Gemini CLI: {e}")
        return f"Exception: {str(e)}"

def on_ai_message(args):
    try:
        sender_id, message = args
        # Check if we have a valid transport and connection_id
        our_id = getattr(hub.transport, 'connection_id', None)
        if our_id and sender_id == our_id:
            return # Ignore our own messages if broadcasted back
            
        logger.info(f"Received AI Message from {sender_id}")
        
        # Run in event loop
        asyncio.run_coroutine_threadsafe(handle_and_reply(message), GLOBAL_LOOP)
    except Exception as e:
        logger.error(f"Error in on_ai_message callback: {e}")

async def handle_and_reply(message):
    response = await run_gemini_cli(message)
    if response:
        logger.info("Sending AI Response back to Hub")
        hub.send("SendAiResponse", [response])

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

    # Authentication
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
