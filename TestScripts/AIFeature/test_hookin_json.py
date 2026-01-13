#!/usr/bin/env python3
import asyncio
import time
import os
import sys
import logging
import json
import subprocess
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("HookinJsonTest")

class HookinJsonTester:
    def __init__(self):
        self.connection_started = False
        self.new_session_pid = None
        self.response_received = False
        self.hub = None
        self.response_text = ""

    def on_open(self):
        logger.info("SignalR Connection opened.")
        self.connection_started = True

    def on_ai_message(self, args):
        pass

    def on_ai_response(self, args):
        if len(args) >= 2:
            response, pid = args[0], args[1]
            logger.info(f"HUB BROADCAST: AI Response from PID {pid}: {response[:100]}...")
            
            # We accept response from the new PID or from -1 (if Hub hasn't resolved it yet in event)
            if pid == self.new_session_pid or self.new_session_pid is None:
                self.response_text += response
                # Check if it looks like JSON
                try:
                    # Clean markdown code blocks if present
                    clean_response = self.response_text.strip()
                    if clean_response.startswith("```json"):
                        clean_response = clean_response[7:]
                    if clean_response.endswith("```"):
                        clean_response = clean_response[:-3]
                    
                    data = json.loads(clean_response.strip())
                    logger.info(f"JSON PARSED SUCCESSFULLY: {data}")
                    
                    if "action" in data and "newContent" in data:
                        logger.info("JSON Schema Validated.")
                        self.response_received = True
                    else:
                        logger.warning("JSON parsed but missing required keys (action, newContent).")
                except json.JSONDecodeError:
                    pass # Keep waiting for a JSON response

    def on_new_session_pid(self, args):
        if args:
            pid = args[0]
            logger.info(f"Received NEW SESSION PID: {pid}")
            self.new_session_pid = pid

    def on_ai_status(self, args):
        if args:
             status = args[0]
             logger.info(f"AI STATUS: {status}")

    def on_ai_dialog(self, args):
        if len(args) >= 3:
            pid, dtype, prompt = args[0], args[1], args[2]
            logger.info(f"AI DIALOG from PID {pid}: Type={dtype}, Prompt={prompt}")
            # If we see auth in progress, we might want to wait or log it
            if dtype == "auth_in_progress":
                logger.warning("Auth in progress dialog received. Prompt might have been ignored.")

    async def run_test(self):
        # Setup paths
        current_dir = os.path.dirname(os.path.abspath(__file__))
        unique_id = int(time.time())
        target_workspace = os.path.join(current_dir, f"DummyProjectJson_{unique_id}")
        if not os.path.exists(target_workspace):
            os.makedirs(target_workspace)
        target_workspace = os.path.abspath(target_workspace)
        
        logger.info(f"Target Workspace: {target_workspace}")

        self.hub = HubConnectionBuilder()\
            .with_url(HUB_URL)\
            .configure_logging(logging.WARNING)\
            .build()

        self.hub.on("ReceiveAiMessage", self.on_ai_message)
        self.hub.on("ReceiveAiResponse", self.on_ai_response)
        self.hub.on("ReceiveNewAiSessionPid", self.on_new_session_pid)
        self.hub.on("ReceiveAiStatus", self.on_ai_status)
        self.hub.on("ReceiveAiDialog", self.on_ai_dialog)
        self.hub.on_open(self.on_open)
        
        self.hub.start()

        # 1. Connect
        for _ in range(10):
            if self.connection_started: break
            await asyncio.sleep(1)
        
        if not self.connection_started: return 1
        self.hub.send("Authenticate", [API_KEY])
        await asyncio.sleep(1)

        # 2. Prepare JSON Prompt
        prompt = """
        I am currently editing the file: `test_file.txt`
        
        CURRENT CONTENT:
        ```
        This is a test file.
        ```
        
        Please suggest improvements. 
        IMPORTANT: You must return your suggested changes in the following JSON format:
        ```json
        {
          "action": "edit",
          "explanation": "Brief explanation",
          "newContent": "The entire new content"
        }
        ```
        Only return the JSON block.
        """

        # 3. Call StartCliAtWorkspace AND SendAiMessage INSTANTLY
        logger.info("Step 1: Requesting StartCliAtWorkspace...")
        self.hub.send("StartCliAtWorkspace", [target_workspace])
        
        logger.info("Step 2: INSTANTLY Sending AI Message to PID -1 (Current Target)...")
        # Sending to -1 hopes that StartCliAtWorkspace sets the new session as target immediately
        self.hub.send("SendAiMessage", [prompt, -1])

        # 4. Wait for response
        logger.info("Step 3: Waiting for JSON response...")
        start_time = time.time()
        while time.time() - start_time < 60:
            if self.response_received:
                logger.info("SUCCESS: Received and validated JSON response.")
                self.hub.stop()
                return 0
            await asyncio.sleep(1)

        self.hub.stop()
        logger.error(f"FAILURE: Did not receive valid JSON response. Last text: {self.response_text[:100]}...")
        return 1

if __name__ == "__main__":
    # Run cleanup first
    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    cleanup_script = os.path.join(root_dir, "TestScripts", "AIFeature", "cleanup_gemini_windows.py")
    if os.path.exists(cleanup_script):
        subprocess.run([sys.executable, cleanup_script], cwd=root_dir)
        
    tester = HookinJsonTester()
    sys.exit(asyncio.run(tester.run_test()))
