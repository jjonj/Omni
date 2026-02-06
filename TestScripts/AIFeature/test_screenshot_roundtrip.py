#!/usr/bin/env python3
import asyncio
import time
import os
import sys
import socket
import logging
import requests
import subprocess
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_BASE_URL = "http://127.0.0.1:5000"
SIGNALR_URL = f"{HUB_BASE_URL}/signalrhub"
REST_API_URL = f"{HUB_BASE_URL}/api/external"
API_KEY = "test_api_key"
WORKSPACE = r"D:/SSDProjects/Omni"
HUB_PORT = 5000

# Setup Root Directory
ROOT_DIR = os.path.dirname(os.path.abspath(__file__))
if "TestScripts" in ROOT_DIR:
    ROOT_DIR = os.path.abspath(os.path.join(ROOT_DIR, "..", ".."))

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("ScreenshotRoundtrip")

class ScreenshotRoundtripTester:
    def __init__(self, loop):
        self.loop = loop
        self.connection_started = False
        self.message_received = False
        self.response_received = False
        self.new_session_pid = None
        self.target_pid = None
        self.hub = None
        self.ai_finished = asyncio.Event()
        self.history_received = asyncio.Event()
        self.last_history = None

    def on_open(self):
        logger.info("SignalR Connection opened.")
        self.connection_started = True

    def on_ai_message(self, args):
        if len(args) >= 3:
            sender_id, message, pid = args[0], args[1], args[2]
            if str(pid) == str(self.target_pid):
                logger.info(f"HUB BROADCAST: Message to PID {pid}: {message}")
                self.message_received = True

    def on_ai_status(self, args):
        if args and len(args) >= 2:
            status, pid = args[0], args[1]
            if str(pid) == str(self.target_pid):
                logger.info(f"AI Status (PID {pid}): {status}")
                if status == "FINISHED":
                    self.loop.call_soon_threadsafe(self.ai_finished.set)

    def on_ai_history(self, args):
        if args and len(args) >= 2:
            history_json, pid = args[0], args[1]
            if str(pid) == str(self.target_pid):
                logger.info(f"Received history JSON for PID {pid}")
                self.last_history = history_json
                self.loop.call_soon_threadsafe(self.history_received.set)

    def on_new_session_pid(self, args):
        pid = args[0]
        logger.info(f"Received new session PID: {pid}")
        self.new_session_pid = pid
        if self.target_pid is None:
            self.target_pid = pid

    async def run_test(self):
        self.hub = HubConnectionBuilder() \
            .with_url(SIGNALR_URL) \
            .configure_logging(logging.WARNING) \
            .build()

        self.hub.on("ReceiveAiMessage", self.on_ai_message)
        self.hub.on("ReceiveAiStatus", self.on_ai_status)
        self.hub.on("ReceiveAiHistory", self.on_ai_history)
        self.hub.on("ReceiveNewAiSessionPid", self.on_new_session_pid)
        self.hub.on_open(self.on_open)
        
        self.hub.start()

        # 1. Connect and Authenticate
        for _ in range(10):
            if self.connection_started: break
            await asyncio.sleep(1)
        
        if not self.connection_started:
            logger.error("Failed to connect to Hub.")
            return 1

        self.hub.send("Authenticate", [API_KEY])
        await asyncio.sleep(1)

        # 2. Launch CLI
        logger.info(f"Launching Gemini CLI at {WORKSPACE}...")
        self.hub.send("StartCliAtWorkspace", [WORKSPACE, "JarvisFast"])
        
        start_wait = time.time()
        while self.new_session_pid is None and time.time() - start_wait < 40:
            await asyncio.sleep(1)
            
        if self.new_session_pid is None:
            logger.error("Timed out waiting for CLI launch.")
            return 1
            
        self.target_pid = self.new_session_pid
        logger.info(f"Targeting session PID: {self.target_pid}")

        # 3. Ask AI to take screenshot
        # Reset event to ignore startup FINISHED
        self.ai_finished.clear()
        
        ai_prompt = "Please use your 'take_screenshot' tool to capture the screen and tell me the file path."
        logger.info(f"Sending prompt to AI: {ai_prompt}")
        
        # Use Hub's SendAiMessage instead of REST for full SignalR flow test
        self.hub.send("SendAiMessage", [ai_prompt, self.target_pid])

        # 4. Wait for AI to finish
        logger.info("Waiting for AI response...")
        try:
            await asyncio.wait_for(self.ai_finished.wait(), timeout=90)
            logger.info("AI finished its turn.")
        except asyncio.TimeoutError:
            logger.error("Timed out waiting for AI response.")
            return 1

        # 5. Verify Tool Call in History
        logger.info("Requesting history to verify tool call...")
        self.history_received.clear()
        self.hub.send("RequestAiHistory", [self.target_pid, 5000])
        
        try:
            await asyncio.wait_for(self.history_received.wait(), timeout=30)
            if "take_screenshot" in self.last_history:
                logger.info("SUCCESS: 'take_screenshot' tool call found in history.")
                return 0
            else:
                logger.error("FAIL: 'take_screenshot' tool call NOT found in history.")
                print(f"\n--- HISTORY DEBUG ---\n{self.last_history}\n")
                return 1
        except asyncio.TimeoutError:
            logger.error("Timed out waiting for history broadcast.")
            return 1
        finally:
            self.hub.stop()

def is_port_in_use(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        return s.connect_ex(('localhost', port)) == 0

async def main():
    if not is_port_in_use(HUB_PORT):
        print(f"!! Hub is not running on port {HUB_PORT}.")
        sys.exit(1)

    loop = asyncio.get_running_loop()
    tester = ScreenshotRoundtripTester(loop)
    exit_code = await tester.run_test()
    
    if exit_code == 0:
        print("\nOVERALL RESULT: SUCCESS")
    else:
        print("\nOVERALL RESULT: FAILED")
    sys.exit(exit_code)

if __name__ == "__main__":
    asyncio.run(main())
