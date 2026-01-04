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
TEST_CONTENT = """Hello world line 1
Hello world line 2
Hello world line 3"""

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("SpecialMessageTest")

class SpecialMessageTester:
    def __init__(self):
        self.connection_started = False
        self.response_received = False
        self.message_received = False
        self.full_response_text = ""
        self.hub = None
        self.new_session_pid = None
        self.last_activity_time = 0

    def on_open(self):
        logger.info("SignalR Connection opened.")
        self.connection_started = True

    def update_activity(self):
        self.last_activity_time = time.time()

    def on_ai_message(self, args):
        if len(args) >= 3:
            sender_id, message, pid = args[0], args[1], args[2]
            logger.info(f"HUB BROADCAST: Message from {sender_id} to PID {pid}: {message}")
        self.message_received = True
        self.update_activity()

    def on_ai_response(self, args):
        # Hub sends [response, pid]
        if args:
            response = str(args[0])
            self.full_response_text += response
            logger.info(f"AI Response Chunk: {response[:50]}...")
            self.update_activity()

    def on_ai_status(self, args):
        if not args: return
        status = args[0]
        logger.info(f"AI Status: {status}")
        if status == "FINISHED":
            self.response_received = True
        self.update_activity()

    def on_new_session_pid(self, args):
        pid = args[0]
        logger.info(f"Received new session PID: {pid}")
        self.new_session_pid = pid
        self.update_activity()

    async def wait_for_silence(self, silence_duration=15, timeout=180):
        logger.info(f"Waiting for {silence_duration}s of silence (timeout {timeout}s)...")
        start_wait = time.time()
        while time.time() - start_wait < timeout:
            await asyncio.sleep(1)
            elapsed_since_activity = time.time() - self.last_activity_time
            if elapsed_since_activity >= silence_duration:
                logger.info(f"Silence detected ({int(elapsed_since_activity)}s).")
                return True
            if int(time.time() - start_wait) % 10 == 0:
                logger.info(f"Still waiting... last activity {int(elapsed_since_activity)}s ago. Text len: {len(self.full_response_text)}")
        return False

    async def setup_connection(self):
        self.last_activity_time = time.time()
        self.hub = HubConnectionBuilder().with_url(HUB_URL).configure_logging(logging.WARNING).build()
        self.hub.on("ReceiveAiMessage", self.on_ai_message)
        self.hub.on("ReceiveAiResponse", self.on_ai_response)
        self.hub.on("ReceiveAiStatus", self.on_ai_status)
        self.hub.on("ReceiveNewAiSessionPid", self.on_new_session_pid)
        self.hub.on_open(self.on_open)
        self.hub.start()
        for _ in range(10):
            if self.connection_started: break
            await asyncio.sleep(1)
        if not self.connection_started: return False
        self.hub.send("Authenticate", [API_KEY])
        await asyncio.sleep(1)
        return True

    async def run_test(self):
        target_path = os.path.join(os.getcwd(), "test_verify_special.txt").replace("\\\\", "/")
        with open(target_path, "w") as f: f.write(TEST_CONTENT)
        
        if not await self.setup_connection(): return 1
        
        logger.info(f"Launching AI session at {os.getcwd()}...")
        self.hub.send("StartCliAtWorkspace", [os.getcwd()])
        while self.new_session_pid is None: await asyncio.sleep(1)
        
        await asyncio.sleep(5)
        logger.info("Waiting for AI to finish startup...")
        await self.wait_for_silence(silence_duration=10)

        # 1. Test READ
        logger.info(f"STEP 1: Testing READ tool call for {target_path}...")
        self.full_response_text = ""
        self.response_received = False
        self.hub.send("SendAiMessage", [f"please read {target_path} and tell me the content", self.new_session_pid])
        await self.wait_for_silence(silence_duration=15)
        
        has_read = "Read" in self.full_response_text or "read_file" in self.full_response_text
        logger.info(f"READ result - has_read_msg: {has_read}")

        # 2. Test REPLACE
        logger.info("STEP 2: Testing REPLACE diff...")
        self.full_response_text = ""
        self.response_received = False
        self.hub.send("SendAiMessage", [f"In file '{target_path}', replace 'line 2' with 'SUCCESS' using the replace tool.", self.new_session_pid])
        await self.wait_for_silence(silence_duration=15)
        
        has_replace = "Replacement" in self.full_response_text or "Replace" in self.full_response_text or "replace" in self.full_response_text.lower()
        logger.info(f"REPLACE result - has_replace_msg: {has_replace}")

        # Cleanup: Stop the session
        if self.new_session_pid:
            logger.info(f"Stopping AI session {self.new_session_pid}...")
            self.hub.send("StopAiSession", [self.new_session_pid])
            await asyncio.sleep(2)

        self.hub.stop()
        try: os.remove(target_path)
        except: pass

        if has_read and has_replace:
            logger.info("FINAL RESULT: PASS")
            return 0
        else:
            logger.error("FINAL RESULT: FAIL (Missing message markers)")
            return 1

if __name__ == "__main__":
    exit_code = asyncio.run(SpecialMessageTester().run_test())
    sys.exit(exit_code)