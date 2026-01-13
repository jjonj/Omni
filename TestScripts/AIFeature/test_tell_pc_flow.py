#!/usr/bin/env python3
import asyncio
import time
import os
import sys
import logging
import subprocess
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key" # Adjust if your local key is different

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("TellPcTest")

class TellPcTester:
    def __init__(self):
        self.connection_started = False
        self.response_received = False
        self.status_updates = []
        self.new_session_pids = []
        self.hub = None

    def on_open(self):
        logger.info("SignalR Connection opened.")
        self.connection_started = True

    def on_ai_status(self, args):
        if args:
            status = args[0]
            pid = args[1] if len(args) > 1 else -1
            logger.info(f"HUB BROADCAST: AI Status: {status} (PID: {pid})")
            self.status_updates.append(status)
            if status == "FINISHED":
                self.response_received = True

    def on_new_pid(self, args):
        if args:
            pid = args[0]
            logger.info(f"HUB BROADCAST: New Session PID: {pid}")
            self.new_session_pids.append(pid)

    def on_sessions(self, args):
        if args:
            logger.info(f"HUB BROADCAST: Sessions list updated: {args}")

    async def run_test(self):
        self.hub = HubConnectionBuilder()\
            .with_url(HUB_URL)\
            .configure_logging(logging.WARNING)\
            .build()

        self.hub.on("ReceiveAiStatus", self.on_ai_status)
        self.hub.on("ReceiveNewAiSessionPid", self.on_new_pid)
        self.hub.on("ReceiveAiSessions", self.on_sessions)
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

        # 2. Trigger Tell PC (Starts Session 1)
        logger.info("TRIGGERING Tell PC...")
        self.hub.send("TriggerTellPc", [])
        
        # 3. Wait a moment then send message (Simulating fast user response)
        await asyncio.sleep(5)
        test_msg = "Test Tell PC Message - Routing check"
        logger.info(f"SENDING Tell PC Message: {test_msg}")
        self.hub.send("SendAiMessage", [test_msg, -1])

        # 4. Wait for results
        start_time = time.time()
        timeout = 60
        while time.time() - start_time < timeout:
            if self.response_received: break
            await asyncio.sleep(1)

        exit_code = 0
        
        # ANALYSIS
        logger.info("--- TEST RESULTS ---")
        logger.info(f"Total sessions launched: {len(self.new_session_pids)}")
        logger.info(f"PIDs: {self.new_session_pids}")
        
        if len(self.new_session_pids) > 1:
            logger.error(f"FAIL: Too many sessions launched ({len(self.new_session_pids)})")
            exit_code = 1
        elif len(self.new_session_pids) == 0:
            logger.error("FAIL: No session launched")
            exit_code = 1
            
        if not self.response_received:
            logger.error("FAIL: Did not receive AI response (FINISHED status)")
            exit_code = 1
        else:
            logger.info("SUCCESS: AI response received.")

        self.hub.stop()
        return exit_code

def cleanup_all_gemini_windows():
    print("[CLEANUP] Cleaning up all Gemini windows...")
    root_dir = os.path.dirname(os.path.abspath(__file__))
    if "TestScripts" in root_dir:
        root_dir = os.path.abspath(os.path.join(root_dir, "..", ".."))
    cleanup_script = os.path.join(root_dir, "TestScripts", "AIFeature", "cleanup_gemini_windows.py")
    subprocess.run([sys.executable, cleanup_script], cwd=root_dir)

async def main():
    cleanup_all_gemini_windows()
    tester = TellPcTester()
    sys.exit(await tester.run_test())

if __name__ == "__main__":
    asyncio.run(main())
