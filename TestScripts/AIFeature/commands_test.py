#!/usr/bin/env python3
import asyncio
import time
import os
import sys
import socket
import logging
import subprocess
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"
WORKSPACE = r"D:/SSDProjects"
OMNI_DIR = r"D:/SSDProjects/Omni"
HUB_PORT = 5000

# Setup Root Directory
ROOT_DIR = os.path.dirname(os.path.abspath(__file__))
if "TestScripts" in ROOT_DIR:
    ROOT_DIR = os.path.abspath(os.path.join(ROOT_DIR, "..", ".."))

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("CommandsTest")

class CommandsTester:
    def __init__(self):
        self.connection_started = False
        self.hub = None
        self.responses = asyncio.Queue()
        self.loop = None
        self.session_pid = None

    def on_open(self):
        logger.info("SignalR Connection opened.")
        self.connection_started = True

    def on_new_session_pid(self, args):
        pid = args[0]
        logger.info(f"Received new session PID from Hub: {pid}")
        self.session_pid = pid

    def on_ai_response(self, args):
        response = args[0]
        logger.info(f"--- AI RESPONSE RECEIVED: {response} ---")
        if self.loop:
            self.loop.call_soon_threadsafe(self.responses.put_nowait, response)

    def on_ai_status(self, args):
        status = args[0]
        logger.info(f"--- AI STATUS RECEIVED: {status} ---")
        if status == "FINISHED":
            if self.loop:
                self.loop.call_soon_threadsafe(self.responses.put_nowait, "[FINISHED]")

    async def wait_for_response(self, timeout=60):
        received_text = False
        try:
            while True:
                res = await asyncio.wait_for(self.responses.get(), timeout=timeout)
                if res == "[FINISHED]":
                    if received_text:
                        return True
                    else:
                        logger.warning("Received [FINISHED] but no text response was received!")
                        return False # Should be True if we accept empty responses, but for this test we expect text.
                
                received_text = True
                
        except asyncio.TimeoutError:
            return False

    async def run_test(self):
        cleanup_all_gemini_windows()
        self.loop = asyncio.get_running_loop()
        self.hub = HubConnectionBuilder()\
            .with_url(HUB_URL)\
            .configure_logging(logging.WARNING)\
            .build()

        self.hub.on("ReceiveAiResponse", self.on_ai_response)
        self.hub.on("ReceiveAiStatus", self.on_ai_status)
        self.hub.on("ReceiveNewAiSessionPid", self.on_new_session_pid)
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
        
        # Wait for PID
        logger.info("Waiting for new session PID...")
        start_wait = time.time()
        while self.session_pid is None and time.time() - start_wait < 30:
            await asyncio.sleep(1)

        if self.session_pid is None:
            logger.error("Timed out waiting for new session PID from Hub.")
            self.hub.stop()
            return 1

        logger.info(f"Targeting new session PID: {self.session_pid}")

        # 3. Step 1: Set directory context via /dir command
        command1 = f"/dir add {OMNI_DIR}"
        logger.info(f"Step 1: Sending AI command: {command1}")
        self.hub.send("SendAiMessage", [command1, self.session_pid])

        res1 = await self.wait_for_response(timeout=45)
        if not res1:
            logger.error("Timed out waiting for first AI response.")
            self.hub.stop()
            return 1
        logger.info("Response 1 received.")

        # 4. Step 2: Ask about Tasks.txt
        command2 = "Read Tasks.txt and tell me what is the first task in it."
        logger.info(f"Step 2: Sending message: {command2}")
        self.hub.send("SendAiMessage", [command2, self.session_pid])

        res2 = await self.wait_for_response(timeout=90)
        if res2:
            logger.info("--- AI FINAL RESPONSE ---")
            print(res2)
            logger.info("--- END RESPONSE ---")
        else:
            logger.error("Timed out waiting for second AI response.")
            self.hub.stop()
            return 1

        self.hub.stop()
        return 0

def is_port_in_use(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        return s.connect_ex(('localhost', port)) == 0

def cleanup_all_gemini_windows():
    print("[CLEANUP] Cleaning up all Gemini windows...")
    cleanup_script = os.path.join(ROOT_DIR, "TestScripts", "AIFeature", "cleanup_gemini_windows.py")
    subprocess.run([sys.executable, cleanup_script], cwd=ROOT_DIR)

async def main():
    print("\n" + "="*60)
    print("      OMNISYNC: HUB-MEDIATED AI COMMANDS TEST")
    print("="*60 + "\n")

    if not is_port_in_use(HUB_PORT):
        print(f"!! Hub is not running on port {HUB_PORT}. Please run run_omnihub.py first.")
        sys.exit(1)

    cleanup_all_gemini_windows()
    
    tester = CommandsTester()
    exit_code = await tester.run_test()
    
    if exit_code == 0:
        print("\nOVERALL STATUS: SUCCESS")
    else:
        print("\nOVERALL STATUS: FAILED")
    
    print("\n" + "="*60)
    sys.exit(exit_code)

if __name__ == "__main__":
    asyncio.run(main())
