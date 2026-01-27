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
HUB_PORT = 5000

# Setup Root Directory
ROOT_DIR = os.path.dirname(os.path.abspath(__file__))
if "TestScripts" in ROOT_DIR:
    ROOT_DIR = os.path.abspath(os.path.join(ROOT_DIR, "..", ".."))

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("RoundtripTest")

class RoundtripTester:
    def __init__(self):
        self.connection_started = False
        self.message_received = False
        self.response_received = False
        self.failure_detected = False
        self.new_session_pid = None
        self.target_pid = None
        self.hub = None

    def on_open(self):
        logger.info("SignalR Connection opened.")
        self.connection_started = True

    def on_ai_message(self, args):
        # Hub sends [senderId, message, pid]
        if len(args) >= 3:
            sender_id, message, pid = args[0], args[1], args[2]
            if str(pid) != str(self.target_pid):
                return
            logger.info(f"HUB BROADCAST: Message from {sender_id} to PID {pid}: {message}")
            self.message_received = True
        else:
            logger.info(f"HUB BROADCAST: AI Message received (partial args: {args})")

    def on_ai_response(self, args):
        # Hub sends [response, pid]
        if args and len(args) >= 2:
            response, pid = args[0], args[1]
            if str(pid) != str(self.target_pid):
                return
            logger.info(f"AI Response (PID {pid}): {response}")
            if str(response).startswith("Error:") or "Error:" in str(response)[:20]:
                logger.error(f"FAIL: AI Response indicated an error: {response}")
                self.failure_detected = True

    def on_ai_status(self, args):
        # Hub sends [status, pid]
        if args and len(args) >= 2:
            status, pid = args[0], args[1]
            if str(pid) != str(self.target_pid):
                return
            logger.info(f"AI Status (PID {pid}): {status}")
            if status == "FINISHED":
                self.response_received = True

    def on_ai_thought(self, args):
        # Hub sends [thought, pid]
        if args and len(args) >= 2:
            thought, pid = args[0], args[1]
            if str(pid) != str(self.target_pid):
                return
            logger.info(f"AI Thought (PID {pid}): {thought}")

    def on_new_session_pid(self, args):
        pid = args[0]
        logger.info(f"Received new session PID from Hub: {pid}")
        self.new_session_pid = pid
        if self.target_pid is None:
            self.target_pid = pid
            logger.info(f"Captured Target PID: {self.target_pid}")

    async def run_test(self):
        cleanup_all_gemini_windows()

        self.hub = HubConnectionBuilder()\
            .with_url(HUB_URL)\
            .configure_logging(logging.WARNING)\
            .build()

        self.hub.on("ReceiveAiMessage", self.on_ai_message)
        self.hub.on("ReceiveAiResponse", self.on_ai_response)
        self.hub.on("ReceiveAiStatus", self.on_ai_status)
        self.hub.on("ReceiveAiThought", self.on_ai_thought)
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

        # 4. Send test message with PID
        # Reset response flag to ignore startup 'FINISHED' event
        self.response_received = False 
        test_msg = "Hello AI, this is an automated roundtrip test. Please respond."
        logger.info(f"Sending message via Hub to PID {target_pid}: {test_msg}")
        self.hub.send("SendAiMessage", [test_msg, target_pid])


        # 5. Wait for events
        start_time = time.time()
        timeout = 45
        while time.time() - start_time < timeout:
            if self.message_received and self.response_received:
                break
            await asyncio.sleep(1)

        exit_code = 0
        if not self.message_received:
            logger.error("FAIL: Did not receive ReceiveAiMessage broadcast.")
            exit_code = 1
        if not self.response_received:
            logger.error("FAIL: Did not receive ReceiveAiResponse broadcast.")
            exit_code = 1
        if self.failure_detected:
            logger.error("FAIL: Test failed due to error response from AI.")
            exit_code = 1

        self.hub.stop()
        return exit_code

def is_port_in_use(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        return s.connect_ex(('localhost', port)) == 0

def cleanup_all_gemini_windows():
    print("[CLEANUP] Cleaning up all Gemini windows...")
    cleanup_script = os.path.join(ROOT_DIR, "TestScripts", "AIFeature", "cleanup_gemini_windows.py")
    subprocess.run([sys.executable, cleanup_script], cwd=ROOT_DIR)

async def main():
    print("\n" + "="*60)
    print("      OMNISYNC: FULL STACK AI ROUNDTRIP TEST")
    print("="*60 + "\n")

    if not is_port_in_use(HUB_PORT):
        print(f"!! Hub is not running on port {HUB_PORT}. Please run run_omnihub.py first.")
        sys.exit(1)

    cleanup_all_gemini_windows()
    
    tester = RoundtripTester()
    exit_code = await tester.run_test()
    
    if exit_code == 0:
        print("\nOVERALL STATUS: SUCCESS")
    else:
        print("\nOVERALL STATUS: FAILED")
    
    print("\n" + "="*60)
    sys.exit(exit_code)

if __name__ == "__main__":
    asyncio.run(main())
