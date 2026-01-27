#!/usr/bin/env python3
import asyncio
import os
import sys
import time
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
WORKSPACE_BASE = r"D:/SSDProjects/Omni/TestScripts/AIFeature/TempWorkspace"
HUB_PORT = 5000

# Setup unique workspace for this run
WORKSPACE = f"{WORKSPACE_BASE}_{int(time.time())}"
if not os.path.exists(WORKSPACE):
    os.makedirs(WORKSPACE)

# Setup Root Directory
ROOT_DIR = os.path.dirname(os.path.abspath(__file__))
if "TestScripts" in ROOT_DIR:
    ROOT_DIR = os.path.abspath(os.path.join(ROOT_DIR, "..", ".."))

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("CliApiE2ETest")

class CliApiTester:
    def __init__(self, loop):
        self.loop = loop
        self.connection_started = False
        self.new_session_pid = None
        self.hub = None
        self.history_received = asyncio.Event()
        self.last_history = None

    def on_open(self):
        logger.info("SignalR Connection opened.")
        self.connection_started = True

    def on_new_session_pid(self, args):
        pid = args[0]
        logger.info(f"Received new session PID from Hub: {pid}")
        self.new_session_pid = pid

    def on_ai_history(self, args):
        # Hub sends [historyJson, pid]
        if args and len(args) >= 2:
            history_json, pid = args[0], args[1]
            if str(pid) == str(self.new_session_pid):
                logger.info(f"Received history JSON for PID {pid}")
                self.last_history = history_json
                # Use call_soon_threadsafe because SignalR runs in a separate thread
                self.loop.call_soon_threadsafe(self.history_received.set)

    async def run_test(self):
        self.hub = HubConnectionBuilder() \
            .with_url(SIGNALR_URL) \
            .configure_logging(logging.WARNING) \
            .build()

        self.hub.on("ReceiveNewAiSessionPid", self.on_new_session_pid)
        self.hub.on("ReceiveAiHistory", self.on_ai_history)
        self.hub.on_open(self.on_open)
        
        self.hub.start()

        # 1. Connect and Authenticate
        for _ in range(10):
            if self.connection_started: break
            await asyncio.sleep(1)
        
        if not self.connection_started:
            logger.error("Failed to connect to Hub via SignalR.")
            return 1

        self.hub.send("Authenticate", [API_KEY])
        await asyncio.sleep(1)

        # 2. Launch CLI via Hub
        logger.info(f"Launching Gemini CLI at {WORKSPACE}...")
        self.hub.send("StartCliAtWorkspace", [WORKSPACE, "JarvisFast"])
        
        # 3. Wait for PID
        start_wait = time.time()
        while self.new_session_pid is None and time.time() - start_wait < 40:
            await asyncio.sleep(1)
            
        if self.new_session_pid is None:
            logger.error("Timed out waiting for CLI launch.")
            return 1
            
        pid = self.new_session_pid
        logger.info(f"CLI Session Active: PID {pid}")

        # 4. Test REST API: List Sessions
        logger.info("Testing REST API: List Sessions...")
        resp = requests.get(f"{REST_API_URL}/cli/sessions", params={"key": API_KEY})
        if resp.status_code != 200:
            logger.error(f"List Sessions API failed: {resp.status_code} {resp.text}")
            return 1
        
        sessions = resp.json()
        logger.info(f"Active Sessions: {len(sessions)}")
        if not any(s['pid'] == pid for s in sessions):
            logger.error(f"Launched PID {pid} not found in active sessions list.")
            return 1
        logger.info("List Sessions verification SUCCESS.")

        # 5. Test REST API: Send Message
        logger.info(f"Testing REST API: Send Message to PID {pid}...")
        test_msg = "Hello, please confirm you received this message by saying 'ACK'."
        payload = {"Pid": pid, "Message": test_msg}
        resp = requests.post(f"{REST_API_URL}/command", params={"key": API_KEY, "cmd": "SEND_CLI_MESSAGE"}, json=payload)
        if resp.status_code != 200:
            logger.error(f"Send Message API failed: {resp.status_code} {resp.text}")
            return 1
        logger.info("Send Message command accepted. Waiting for AI to process...")
        await asyncio.sleep(10) # Give AI time to respond and update history

        # 6. Test REST API: Get History
        logger.info(f"Testing REST API: Get History for PID {pid}...")
        self.history_received.clear()
        resp = requests.get(f"{REST_API_URL}/cli/history", params={"key": API_KEY, "pid": pid, "maxChars": 1000})
        if resp.status_code != 200:
            logger.error(f"Get History API failed: {resp.status_code} {resp.text}")
            return 1
        
        logger.info("History request triggered via REST, waiting for Hub broadcast...")
        try:
            await asyncio.wait_for(self.history_received.wait(), timeout=30)
            logger.info(f"History received successfully. Length: {len(self.last_history)}")
            preview = self.last_history[:150] + "..." if len(self.last_history) > 150 else self.last_history
            print(f"\nHISTORY PREVIEW:\n{preview}\n")
            
            if "ACK" not in self.last_history and "confirm" not in self.last_history:
                logger.warning("Warning: Could not find expected message content in history. AI might still be thinking.")
        except asyncio.TimeoutError:
            logger.error("Timed out waiting for history broadcast.")
            return 1

        # 7. Test REST API: Open Resource
        target_path = os.path.join(ROOT_DIR, "design.txt")
        logger.info(f"Testing REST API: Open Resource (File: {target_path})...")
        payload = {"Path": target_path}
        resp = requests.post(f"{REST_API_URL}/command", params={"key": API_KEY, "cmd": "OPEN_RESOURCE"}, json=payload)
        if resp.status_code != 200:
            logger.error(f"Open Resource API failed: {resp.status_code} {resp.text}")
            return 1
        logger.info("Open Resource command accepted. Please check if Notepad++ opened design.txt.")
        await asyncio.sleep(3)

        self.hub.stop()
        return 0

def is_port_in_use(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        return s.connect_ex(('localhost', port)) == 0

async def main():
    if not is_port_in_use(HUB_PORT):
        print(f"!! Hub is not running on port {HUB_PORT}.")
        sys.exit(1)

    loop = asyncio.get_running_loop()
    tester = CliApiTester(loop)
    exit_code = await tester.run_test()
    
    if exit_code == 0:
        print("\nCLI API E2E VERIFICATION: SUCCESS")
    else:
        print("\nCLI API E2E VERIFICATION: FAILED")
    
    sys.exit(exit_code)

if __name__ == "__main__":
    asyncio.run(main())
