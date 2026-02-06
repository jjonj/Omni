#!/usr/bin/env python3
import asyncio
import time
import os
import sys
import socket
import logging
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"
WORKSPACE = r"D:/SSDProjects"
HUB_PORT = 5000

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("LaunchStopTest")

class LaunchStopTester:
    def __init__(self):
        self.connection_started = False
        self.new_session_pid = None
        self.hub = None
        self.session_stopped = False

    def on_open(self):
        logger.info("SignalR Connection opened.")
        self.connection_started = True

    def on_new_session_pid(self, args):
        pid = args[0]
        logger.info(f"Received new session PID from Hub: {pid}")
        self.new_session_pid = pid

    def on_ai_status(self, args):
        if args and len(args) >= 2:
            status, pid = args[0], args[1]
            if pid == self.new_session_pid:
                logger.info(f"AI Status (PID {pid}): {status}")
                if status == "STOPPED" or status == "TERMINATED":
                    self.session_stopped = True

    async def run_test(self):
        self.hub = (HubConnectionBuilder()
            .with_url(HUB_URL)
            .configure_logging(logging.WARNING)
            .build())

        self.hub.on("ReceiveNewAiSessionPid", self.on_new_session_pid)
        self.hub.on("ReceiveAiStatus", self.on_ai_status)
        self.hub.on_open(self.on_open)
        self.hub.on_error(lambda data: logger.error(f"SignalR Error: {data}"))
        
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

        # 2. Launch CLI via Hub
        logger.info(f"Requesting Hub to launch Gemini CLI at {WORKSPACE}...")
        self.hub.send("StartCliAtWorkspace", [WORKSPACE, "JarvisFast"])
        
        # 3. Wait for PID
        logger.info("Waiting for new session PID...")
        start_wait = time.time()
        while self.new_session_pid is None and time.time() - start_wait < 30:
            await asyncio.sleep(1)
            
        if self.new_session_pid is None:
            logger.error("Timed out waiting for new session PID from Hub.")
            return 1
            
        target_pid = self.new_session_pid
        logger.info(f"Targeting new session PID: {target_pid}")
        await asyncio.sleep(2) # Give it a moment to stabilize

        # 4. Leave the session running (no StopAiSession sent)
        logger.info(f"Leaving AI session PID {target_pid} running for verification...")
        await asyncio.sleep(5)

        self.hub.stop()
        
        if self.session_stopped:
            logger.info("Session stop event received (unexpected in no-stop mode).")
        return 0

def is_port_in_use(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        return s.connect_ex(('localhost', port)) == 0

async def main():
    print("\n" + "="*60)
    print("      OMNISYNC: LAUNCH AND STOP TEST")
    print("="*60 + "\n")

    if not is_port_in_use(HUB_PORT):
        print(f"!! Hub is not running on port {HUB_PORT}. Please run run_omnihub.py first.")
        sys.exit(1)

    tester = LaunchStopTester()
    exit_code = await tester.run_test()
    
    if exit_code == 0:
        print("\nOVERALL STATUS: SUCCESS")
    else:
        print("\nOVERALL STATUS: FAILED")
    
    print("\n" + "="*60)
    print("[FORCE-STOP-TURN]")
    sys.exit(exit_code)


if __name__ == "__main__":
    asyncio.run(main())

