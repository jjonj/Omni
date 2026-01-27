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
API_KEY = "test_api_key"

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("HookinPathTest")

class HookinPathTester:
    def __init__(self):
        self.connection_started = False
        self.new_session_pid = None
        self.response_received = False
        self.hub = None
        self.session_created_event = asyncio.Event()
        self.response_text = ""

    def on_open(self):
        logger.info("SignalR Connection opened.")
        self.connection_started = True

    def on_ai_message(self, args):
        pass # Ignore user messages

    def on_ai_response(self, args):
        if len(args) >= 2:
            response, pid = args[0], args[1]
            logger.info(f"HUB BROADCAST: AI Response from PID {pid}: {response[:100]}...")
            if pid == self.new_session_pid:
                self.response_text = response
                if "DummyProject" in response:
                    self.response_received = True

    def on_new_session_pid(self, args):
        if args:
            pid = args[0]
            logger.info(f"Received NEW SESSION PID: {pid}")
            self.new_session_pid = pid
            self.session_created_event.set()

    async def run_test(self):
        # Setup paths
        current_dir = os.path.dirname(os.path.abspath(__file__))
        target_workspace = os.path.join(current_dir, "DummyProject")
        target_workspace = os.path.abspath(target_workspace)
        
        logger.info(f"Target Workspace: {target_workspace}")

        self.hub = HubConnectionBuilder()\
            .with_url(HUB_URL)\
            .configure_logging(logging.WARNING)\
            .build()

        self.hub.on("ReceiveAiMessage", self.on_ai_message)
        self.hub.on("ReceiveAiResponse", self.on_ai_response)
        self.hub.on("ReceiveNewAiSessionPid", self.on_new_session_pid)
        self.hub.on_open(self.on_open)
        
        self.hub.start()

        # 1. Connect
        for _ in range(10):
            if self.connection_started: break
            await asyncio.sleep(1)
        
        if not self.connection_started: return 1
        self.hub.send("Authenticate", [API_KEY])
        await asyncio.sleep(1)

        # 2. Call StartCliAtWorkspace
        logger.info("Step 1: Requesting StartCliAtWorkspace...")
        self.hub.send("StartCliAtWorkspace", [target_workspace, "JarvisFast"])

        # 3. Wait for PID
        logger.info("Waiting for PID...")
        try:
            await asyncio.wait_for(self.session_created_event.wait(), timeout=30)
        except asyncio.TimeoutError:
            logger.error("Timed out waiting for session PID")
            return 1

        logger.info(f"Step 2: Session created (PID {self.new_session_pid}). Verifying CWD...")
        
        # 4. Ask AI for current directory
        # We rely on the AI knowing its CWD or being able to run 'pwd' if it has tool access.
        # But even asking "Where are you?" might work if context is loaded.
        # Since this is a CLI wrapper, we can ask it to print the CWD.
        prompt = "Print the current working directory."
        self.hub.send("SendAiMessage", [prompt, self.new_session_pid])

        # 5. Wait for response
        logger.info("Step 3: Waiting for response containing 'DummyProject'...")
        start_time = time.time()
        while time.time() - start_time < 45:
            if self.response_received:
                logger.info("SUCCESS: AI response confirmed correct directory.")
                self.hub.stop()
                return 0
            await asyncio.sleep(1)

        self.hub.stop()
        
        logger.warning(f"WARNING: 'DummyProject' not found in any response. Last response: '{self.response_text}'.")
        return 1

if __name__ == "__main__":
    # Run cleanup first
    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    cleanup_script = os.path.join(root_dir, "TestScripts", "AIFeature", "cleanup_gemini_windows.py")
    if os.path.exists(cleanup_script):
        subprocess.run([sys.executable, cleanup_script], cwd=root_dir)
        
    tester = HookinPathTester()
    sys.exit(asyncio.run(tester.run_test()))
