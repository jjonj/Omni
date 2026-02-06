#!/usr/bin/env python3
import asyncio
import os
import sys
import time
import socket
import logging
import requests
import json
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_BASE_URL = "http://127.0.0.1:5000"
SIGNALR_URL = f"{HUB_BASE_URL}/signalrhub"
REST_API_URL = f"{HUB_BASE_URL}/api/external"
API_KEY = "test_api_key"
WORKSPACE = r"D:/SSDProjects/Omni"
HUB_PORT = 5000

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("ScreenshotAiVerifier")

class ScreenshotAiTester:
    def __init__(self, loop):
        self.loop = loop
        self.connection_started = False
        self.new_session_pid = None
        self.hub = None
        self.ai_finished = asyncio.Event()
        self.responses = []

    def on_open(self):
        logger.info("SignalR Connection opened.")
        self.connection_started = True

    def on_new_session_pid(self, args):
        pid = args[0]
        logger.info(f"Received new session PID: {pid}")
        self.new_session_pid = pid

    def on_ai_status(self, args):
        if args and len(args) >= 2:
            status, pid = args[0], args[1]
            if str(pid) == str(self.new_session_pid):
                logger.info(f"AI Status (PID {pid}): {status}")
                if status == "FINISHED":
                    self.loop.call_soon_threadsafe(self.ai_finished.set)

    def on_ai_response(self, args):
        if args and len(args) >= 2:
            text, pid = args[0], args[1]
            if str(pid) == str(self.new_session_pid):
                self.responses.append(text)

    async def run_test(self):
        self.hub = HubConnectionBuilder() \
            .with_url(SIGNALR_URL) \
            .configure_logging(logging.WARNING) \
            .build()

        self.hub.on("ReceiveNewAiSessionPid", self.on_new_session_pid)
        self.hub.on("ReceiveAiStatus", self.on_ai_status)
        self.hub.on("ReceiveAiResponse", self.on_ai_response)
        self.hub.on_open(self.on_open)
        
        self.hub.start()

        # 1. Connect and Authenticate
        for _ in range(10):
            if self.connection_started: break
            await asyncio.sleep(1)
        
        if not self.connection_started:
            logger.error("Failed to connect to Hub via SignalR.")
            return 1

        self.hub.send("Authenticate", [API_KEY])
        await asyncio.sleep(1)

        # 2. Launch CLI
        logger.info(f"Launching Gemini CLI at {WORKSPACE}...")
        self.hub.send("StartCliAtWorkspace", [WORKSPACE, "JarvisFast"])
        
        start_wait = time.time()
        while self.new_session_pid is None and time.time() - start_wait < 40:
            await asyncio.sleep(1)
            
        if self.new_session_pid is None:
            logger.error("Timed out waiting for CLI launch.")
            return 1
            
        pid = self.new_session_pid
        logger.info(f"CLI Session Active: PID {pid}")

        # 3. Prompt AI to use tool and verify
        ai_prompt = (
            "Please use your 'take_screenshot' tool to capture the screen. "
            "Then, analyze the screenshot and tell me what you see. "
            "IMPORTANT: If you can see the screen contents, start your message with '[SUCCESS]'. "
            "If you cannot use the tool or see the image, start with '[FAILURE]'."
        )
        
        logger.info("Sending verification prompt to AI...")
        payload = {"Pid": pid, "Message": ai_prompt}
        self.ai_finished.clear()
        
        resp = requests.post(f"{REST_API_URL}/command", params={"key": API_KEY, "cmd": "SEND_CLI_MESSAGE"}, json=payload)
        if resp.status_code != 200:
            logger.error(f"Send AI Prompt failed: {resp.status_code} {resp.text}")
            return 1
            
        # 4. Wait for AI response
        logger.info("Waiting for AI to finish processing...")
        try:
            await asyncio.wait_for(self.ai_finished.wait(), timeout=120) # Long timeout for vision analysis
        except asyncio.TimeoutError:
            logger.error("Timed out waiting for AI response.")
            return 1

        # 5. Analyze Output
        full_response = "".join(self.responses)
        logger.info(f"Full AI Response received (Length: {len(full_response)})")
        
        if "[SUCCESS]" in full_response:
            logger.info("AI VERIFICATION: SUCCESS (AI found [SUCCESS] tag)")
            result = 0
        elif "[FAILURE]" in full_response:
            logger.error("AI VERIFICATION: FAILURE (AI found [FAILURE] tag)")
            result = 1
        else:
            logger.warning("AI VERIFICATION: UNCERTAIN (Tags not found in output)")
            print(f"\n--- AI RESPONSE ---\n{full_response}\n-------------------\n")
            result = 1

        self.hub.stop()
        return result

def is_port_in_use(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        return s.connect_ex(('localhost', port)) == 0

async def main():
    if not is_port_in_use(HUB_PORT):
        print(f"!! Hub is not running on port {HUB_PORT}.")
        sys.exit(1)

    loop = asyncio.get_running_loop()
    tester = ScreenshotAiTester(loop)
    exit_code = await tester.run_test()
    sys.exit(exit_code)

if __name__ == "__main__":
    asyncio.run(main())
