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
logger = logging.getLogger("AndroidUxTest")

class AndroidUxTester:
    def __init__(self):
        self.connection_started = False
        self.hub = None
        self.target_pid = -1

    def on_open(self):
        logger.info("SignalR Connection opened.")
        self.connection_started = True

    def on_sessions(self, args):
        sessions = args[0]
        if sessions:
            self.target_pid = sessions[0]['pid']
            logger.info(f"Target PID set to: {self.target_pid}")

    async def run_test(self):
        self.hub = HubConnectionBuilder()\
            .with_url(HUB_URL)\
            .configure_logging(logging.WARNING)\
            .build()

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
        
        # Get sessions to find a PID
        self.hub.send("GetAiSessions", [])
        await asyncio.sleep(2)
        
        pid = self.target_pid if self.target_pid != -1 else 1234
        
        logger.info("Starting automated UX test sequence...")
        
        # 1. Send many messages (Test Auto-Scroll)
        for i in range(3):
            logger.info(f"Sending message {i+1}...")
            self.hub.send("ReceiveAiResponse", [f"Scroll test message {i+1} " * 5, pid])
            await asyncio.sleep(0.5)
            
        # 2. Trigger Dialog Alert (Test Sound & Highlight)
        logger.info("Triggering Dialog Alert...")
        self.hub.send("ReceiveAiDialog", [pid, "choice", "Automated UX Test Dialog", ["Yes", "No"]])
        await asyncio.sleep(1)
        
        # 3. Trigger 'Thinking' Status
        logger.info("Triggering 'Thinking' status...")
        self.hub.send("ReceiveAiStatus", ["Thinking...", pid])
        await asyncio.sleep(1)
        
        # 4. Trigger 'Finished' Status
        logger.info("Triggering 'FINISHED' status...")
        self.hub.send("ReceiveAiStatus", ["FINISHED", pid])
        await asyncio.sleep(1)

        self.hub.stop()
        return 0

async def main():
    tester = AndroidUxTester()
    await tester.run_test()

if __name__ == "__main__":
    asyncio.run(main())
