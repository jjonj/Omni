#!/usr/bin/env python3
import asyncio
import time
import os
import sys
import logging
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("AndroidAiTest")

class AndroidAiTester:
    def __init__(self):
        self.connection_started = False
        self.message_received = False
        self.response_received = False
        self.hub = None

    def on_open(self):
        logger.info("SignalR Connection opened.")
        self.connection_started = True

    def on_ai_message(self, args):
        sender_id, message = args
        logger.info(f"HUB BROADCAST: Message from {sender_id}: {message}")
        self.message_received = True

    def on_ai_response(self, args):
        response = args[0]
        logger.info(f"HUB BROADCAST: AI Response received: {response}")
        self.response_received = True

    def on_ai_status(self, args):
        status = args[0]
        logger.info(f"AI Status: {status}")
        if status == "FINISHED":
            self.response_received = True

    async def run_test(self):
        self.hub = HubConnectionBuilder()\
            .with_url(HUB_URL)\
            .configure_logging(logging.WARNING)\
            .build()

        self.hub.on("ReceiveAiMessage", self.on_ai_message)
        self.hub.on("ReceiveAiResponse", self.on_ai_response)
        self.hub.on("ReceiveAiStatus", self.on_ai_status)
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

        # 2. Ensure a session exists (Auto-launch if needed)
        # Android behavior: It just sends a message. The Hub should auto-launch if needed.
        test_msg = "Hello from Python Android Simulator. Fixed routing test."
        logger.info(f"Sending message (2 args, one is None): {test_msg}")
        self.hub.send("SendAiMessage", [test_msg, None])

        # 3. Wait for response
        start_time = time.time()
        timeout = 30
        while time.time() - start_time < timeout:
            if self.response_received: break
            await asyncio.sleep(1)

        exit_code = 0
        if not self.response_received:
            logger.error("FAIL: Did not receive AI response.")
            exit_code = 1

        self.hub.stop()
        return exit_code

def cleanup_all_gemini_windows():
    print("[CLEANUP] Cleaning up all Gemini windows...")
    # Find ROOT_DIR
    root_dir = os.path.dirname(os.path.abspath(__file__))
    if "TestScripts" in root_dir:
        root_dir = os.path.abspath(os.path.join(root_dir, "..", ".."))
    cleanup_script = os.path.join(root_dir, "TestScripts", "AIFeature", "cleanup_gemini_windows.py")
    subprocess.run([sys.executable, cleanup_script], cwd=root_dir)

async def main():
    cleanup_all_gemini_windows()
    tester = AndroidAiTester()
    sys.exit(await tester.run_test())

if __name__ == "__main__":
    import subprocess
    asyncio.run(main())
