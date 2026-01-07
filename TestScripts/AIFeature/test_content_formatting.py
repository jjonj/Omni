#!/usr/bin/env python3
import asyncio
import time
import os
import sys
import logging
import re
import json
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("DisplayCapabilitiesTest")

class DisplayCapabilitiesTester:
    def __init__(self):
        self.connection_started = False
        self.message_received = False
        self.response_received = False
        self.full_response_text = ""
        self.hub = None
        self.new_session_pid = None
        self.test_failed = False
        self.last_activity_time = 0

    def on_open(self):
        logger.info("SignalR Connection opened.")
        self.connection_started = True

    def on_ai_message(self, args):
        sender_id, message = args
        self.message_received = True

    def on_ai_response(self, args):
        response = args[0]
        # logger.info(f"HUB BROADCAST: AI Response chunk: {len(str(response))} chars")
        self.full_response_text += str(response)

    def on_ai_status(self, args):
        status = args[0]
        logger.info(f"AI Status: {status}")
        if status == "FINISHED":
            self.response_received = True

    def on_new_session_pid(self, args):
        pid = args[0]
        logger.info(f"Received new session PID from Hub: {pid}")
        self.new_session_pid = pid

    async def setup_connection(self):
        self.hub = (HubConnectionBuilder()
            .with_url(HUB_URL)
            .configure_logging(logging.WARNING)
            .build())

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
        workspace = "D:/SSDProjects" 
        logger.info(f"Requesting Hub to launch Gemini CLI at {workspace}...")
        self.hub.send("StartCliAtWorkspace", [workspace])
        
        start_wait = time.time()
        while self.new_session_pid is None and time.time() - start_wait < 30:
            await asyncio.sleep(1)
            
        if self.new_session_pid is None:
            logger.error("Timed out waiting for new session PID.")
            return None
            
        logger.info(f"Targeting session PID: {self.new_session_pid}")
        return self.new_session_pid

    async def wait_for_startup_completion(self, timeout=60, silence_duration=5):
        logger.info("Waiting for AI startup (expecting at least one FINISHED status)...")
        start = time.time()
        has_finished = False
        
        # Wait for first FINISHED
        while time.time() - start < timeout:
            if self.response_received: # response_received is set on FINISHED
                has_finished = True
                logger.info("Startup turn finished (FINISHED received).")
                break
            await asyncio.sleep(1)
        
        if not has_finished:
            logger.warning("No startup output received within timeout. Assuming silent startup.")
        
        # Now wait for silence to ensure no follow-up chains
        logger.info(f"Waiting for {silence_duration} seconds of silence...")
        end_time = time.time() + silence_duration
        while time.time() < end_time:
            if time.time() - self.last_activity_time < silence_duration:
                end_time = self.last_activity_time + silence_duration
            await asyncio.sleep(0.5)
        logger.info("Silence achieved. AI is ready.")

    def update_activity(self):
        self.last_activity_time = time.time()

    def on_ai_message(self, args):
        # args is [sender_id, message, targetPid]
        if len(args) >= 2:
            sender_id, message = args[0], args[1]
            self.message_received = True
            self.update_activity()

    def on_ai_response(self, args):
        # args is [text, targetPid]
        if args:
            response = args[0]
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
        self.hub = (HubConnectionBuilder()
            .with_url(HUB_URL)
            .configure_logging(logging.WARNING)
            .build())

        self.hub.on("ReceiveAiMessage", self.on_ai_message)
        self.hub.on("ReceiveAiResponse", self.on_ai_response)
        self.hub.on("ReceiveAiStatus", self.on_ai_status)
        self.hub.on("ReceiveNewAiSessionPid", self.on_new_session_pid)
        self.hub.on_open(self.on_open)
        
        self.hub.start()
        # ... rest is same ...
        for _ in range(10):
            if self.connection_started: break
            await asyncio.sleep(1)
        
        if not self.connection_started:
            logger.error("Failed to connect to Hub.")
            return False

        self.hub.send("Authenticate", [API_KEY])
        await asyncio.sleep(1)
        return True

    # ... start_session is same ...

    async def run_single_test(self, test_name, prompt, verification_func):
        logger.info(f"\n--- RUNNING TEST: {test_name} ---")
        self.response_received = False
        self.full_response_text = ""
        
        logger.info(f"Sending prompt: {prompt}")
        self.hub.send("SendAiMessage", [prompt, self.new_session_pid])

        # Wait for FINISHED status
        start_time = time.time()
        timeout = 90
        while time.time() - start_time < timeout:
            if self.response_received: break
            await asyncio.sleep(1)
        
        if not self.response_received:
            logger.error(f"FAIL: Test '{test_name}' timed out waiting for response.")
            self.test_failed = True
            return False

        logger.info(f"Response received. Verifying...")
        if verification_func(self.full_response_text):
            logger.info(f"PASS: Test '{test_name}' passed.")
            return True
        else:
            logger.error(f"FAIL: Test '{test_name}' verification failed.")
            logger.error(f"Full content was:\n{self.full_response_text}")
            self.test_failed = True
            return False

    async def run_all_tests(self):
        if not await self.setup_connection():
            return 1

        pid = await self.start_session()
        if not pid:
            self.hub.stop()
            return 1
        
        # Reset flag before waiting
        self.response_received = False
        await self.wait_for_startup_completion()
        
        # Test 1: Bullet Points
        def verify_bullets(text):
            count = len(re.findall(r'^\s*([-*]|\d+\.)\s+', text, re.MULTILINE))
            has_word = "apple" in text.lower()
            logger.info(f"  - Bullet points found: {count}")
            logger.info(f"  - Word 'apple' found: {has_word}")
            return count >= 5 and has_word

        await self.run_single_test(
            "Bullet Points", 
            "Make a bullet point list of 5 items, each containing the word 'apple'.",
            verify_bullets
        )

        # Test 2: Multiple Choice
        def verify_multiple_choice(text):
            # Look for A. or 1. followed by text, at least 3 times close together
            options = len(re.findall(r'^\s*([A-C]|\d)\.\s+', text, re.MULTILINE))
            has_question = "?" in text
            logger.info(f"  - Options found: {options}")
            return options >= 3 and has_question

        await self.run_single_test(
            "Multiple Choice",
            "Ask me a multiple choice question about the capital of France with 3 options (A, B, C).",
            verify_multiple_choice
        )

        # Test 3: Code Block
        def verify_code_block(text):
            has_block = "```python" in text or "```" in text
            has_print = "print" in text
            logger.info(f"  - Code block found: {has_block}")
            return has_block and has_print

        await self.run_single_test(
            "Code Block",
            "Write a python script that prints 'Hello World' in a code block.",
            verify_code_block
        )

        # Test 4: System Call (List Files)
        # Note: Depending on permission/mode, AI might ask for confirmation or just do it.
        # We check if it mentions files or tries to run it.
        def verify_system_call(text):
            # If it runs, it might show output.
            # If it asks, it might show "run_shell_command".
            # We look for file names or the intent.
            has_file = "test_content_formatting.py" in text or "test_content_formatting" in text
            # Or if it lists other files in TestScripts/AIFeature
            has_intent = "listing" in text.lower() or "directory" in text.lower()
            logger.info(f"  - File mentioned: {has_file}")
            return has_file or has_intent

        # Use a path we know exists and has this file
        target_dir = os.path.join("TestScripts", "AIFeature").replace("\\", "/")
        await self.run_single_test(
            "System Call",
            f"List the files in the directory '{target_dir}'.",
            verify_system_call
        )

        self.hub.stop()
        return 1 if self.test_failed else 0

if __name__ == "__main__":
    try:
        import signalrcore
    except ImportError:
        import subprocess
        subprocess.check_call([sys.executable, "-m", "pip", "install", "signalrcore"])

    tester = DisplayCapabilitiesTester()
    exit_code = asyncio.run(tester.run_all_tests())
    sys.exit(exit_code)