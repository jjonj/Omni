#!/usr/bin/env python3
import asyncio
import time
import os
import sys
import logging
import tempfile
import shutil
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"
TEST_FILE_NAME = "temp_diff_test.txt"
TEST_CONTENT = "The quick unique_target_word fox jumps over the lazy dog."
TARGET_WORD = "unique_target_word"
REPLACEMENT_WORD = "unique_replacement_word"

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("DiffDisplayTest")

class DiffDisplayTester:
    def __init__(self):
        self.connection_started = False
        self.response_received = False
        self.full_response_text = ""
        self.hub = None
        self.new_session_pid = None
        self.last_activity_time = 0
        self.test_dir = tempfile.mkdtemp(prefix="gemini_test_ws_")

    def on_open(self):
        logger.info("SignalR Connection opened.")
        self.connection_started = True

    def update_activity(self):
        self.last_activity_time = time.time()

    def on_ai_message(self, args):
        # args is [sender_id, message, targetPid]
        if len(args) >= 2:
            self.update_activity()

    def on_ai_response(self, args):
        # args is [text, targetPid]
        if args:
            response = args[0]
            # logger.info(f"Received chunk: {response}")
            self.full_response_text += str(response)
            self.update_activity()

    def on_ai_status(self, args):
        # args is [status, targetPid]
        if args:
            status = args[0]
            logger.info(f"AI Status: {status}")
            self.update_activity()
            if status == "FINISHED":
                self.response_received = True

    def on_new_session_pid(self, args):
        pid = args[0]
        logger.info(f"Received new session PID from Hub: {pid}")
        self.new_session_pid = pid
        self.update_activity()

    async def setup_connection(self):
        self.last_activity_time = time.time()
        self.hub = (
            HubConnectionBuilder()
            .with_url(HUB_URL)
            .configure_logging(logging.WARNING)
            .build()
        )

        self.hub.on("ReceiveAiMessage", self.on_ai_message)
        self.hub.on("ReceiveAiResponse", self.on_ai_response)
        self.hub.on("ReceiveAiStatus", self.on_ai_status)
        self.hub.on("ReceiveNewAiSessionPid", self.on_new_session_pid)
        self.hub.on_open(self.on_open)
        
        self.hub.start()

        for _ in range(10):
            if self.connection_started: break
            await asyncio.sleep(1)
        
        if not self.connection_started:
            logger.error("Failed to connect to Hub.")
            return False

        self.hub.send("Authenticate", [API_KEY])
        await asyncio.sleep(1)
        return True

    async def start_session(self):
        logger.info(f"Requesting Hub to launch Gemini CLI at {self.test_dir}...")
        self.hub.send("StartCliAtWorkspace", [self.test_dir])
        
        start_wait = time.time()
        while self.new_session_pid is None and time.time() - start_wait < 30:
            await asyncio.sleep(1)
            
        if self.new_session_pid is None:
            logger.error("Timed out waiting for new session PID.")
            return None
            
        return self.new_session_pid

    async def wait_for_startup_completion(self, timeout=60, silence_duration=5):
        logger.info("Waiting for AI startup...")
        start = time.time()
        has_finished = False
        
        while time.time() - start < timeout:
            if self.response_received: 
                has_finished = True
                break
            await asyncio.sleep(1)
        
        # Wait for silence
        logger.info(f"Waiting for {silence_duration}s silence...")
        end_time = time.time() + silence_duration
        while time.time() < end_time:
            if time.time() - self.last_activity_time < silence_duration:
                end_time = self.last_activity_time + silence_duration
            await asyncio.sleep(0.5)
        logger.info("AI Ready.")

    async def run_test(self):
        # 1. Setup file
        target_path = os.path.join(self.test_dir, TEST_FILE_NAME).replace("\\", "/")
        with open(target_path, "w") as f:
            f.write(TEST_CONTENT)
        logger.info(f"Created test file '{target_path}' with content: '{TEST_CONTENT}'")

        if not await self.setup_connection():
            return 1

        pid = await self.start_session()
        if not pid:
            logger.error("SANITY FAIL: Session PID not received.")
            self.hub.stop()
            return 1
        
        logger.info(f"SANITY PASS: Session exists (PID: {pid})")

        await self.wait_for_startup_completion()

        # Clear context
        logger.info("Clearing AI context...")
        self.hub.send("SendAiMessage", ["/clear", self.new_session_pid])
        await self.wait_for_startup_completion(timeout=30, silence_duration=3)

        # 2. Send Prompt
        prompt = f"In file '{target_path}', replace '{TARGET_WORD}' with '{REPLACEMENT_WORD}'. Use the replace tool."
        logger.info(f"Sending prompt: {prompt}")
        
        self.response_received = False
        # Do NOT clear full_response_text here if you want to keep startup logs, 
        # but for the test verification we usually want just the prompt result.
        # Let's clear it to isolate the verification.
        self.full_response_text = ""
        self.update_activity()
        
        self.hub.send("SendAiMessage", [prompt, self.new_session_pid])

        # 3. Wait for the AI to complete ALL turns (using silence detection)
        logger.info("Waiting for AI to complete all turns (silence detection)...")
        # silence_duration=10 to be very safe that it's done with follow-ups
        await self.wait_for_startup_completion(timeout=120, silence_duration=10)

        self.hub.stop()
        
        # 4. Verify Content
        logger.info("Verifying response content...")
        logger.info(f"Full Response Length: {len(self.full_response_text)}")
        logger.info(f"Full Response Text:\n{self.full_response_text}")

        # Check for old word and new word in the output (indicating a diff or explanation)
        has_target = TARGET_WORD in self.full_response_text
        has_replacement = REPLACEMENT_WORD in self.full_response_text
        
        logger.info(f"Contains '{TARGET_WORD}': {has_target}")
        logger.info(f"Contains '{REPLACEMENT_WORD}': {has_replacement}")

        # Cleanup
        try:
            shutil.rmtree(self.test_dir)
        except:
            pass

        if has_target and has_replacement:
            logger.info("SUCCESS: Output contains both original and replacement words.")
            return 0
        else:
            logger.error("FAIL: Output missing diff details.")
            return 1

if __name__ == "__main__":
    try:
        import signalrcore
    except ImportError:
        import subprocess
        subprocess.check_call([sys.executable, "-m", "pip", "install", "signalrcore"])

    tester = DiffDisplayTester()
    exit_code = asyncio.run(tester.run_test())
    sys.exit(exit_code)
