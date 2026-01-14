import asyncio
import time
import os
import sys
import logging
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("TellPcTest")

class TellPcTester:
    def __init__(self):
        self.connection_started = False
        self.pids_received = []
        self.status_updates = []
        self.hub = None

    def on_new_pid(self, args):
        pid = args[0]
        logger.info(f"New AI Session PID: {pid}")
        self.pids_received.append(pid)

    def on_status(self, args):
        status = args[0]
        logger.info(f"AI Status: {status}")
        self.status_updates.append(status)

    async def run_test(self):
        self.hub = HubConnectionBuilder() \
            .with_url(HUB_URL) \
            .configure_logging(logging.WARNING) \
            .build()

        self.hub.on("ReceiveNewAiSessionPid", self.on_new_pid)
        self.hub.on("ReceiveAiStatus", self.on_status)
        self.hub.on_open(lambda: setattr(self, 'connection_started', True))
        
        self.hub.start()

        # 1. Wait for connection
        for _ in range(10):
            if self.connection_started: break
            await asyncio.sleep(1)
        
        if not self.connection_started:
            logger.error("Failed to connect to Hub.")
            return False

        self.hub.send("Authenticate", [API_KEY])
        await asyncio.sleep(1)

        # 2. Trigger Tell PC
        logger.info("Triggering Tell PC...")
        self.hub.send("TriggerTellPc", [])

        # 3. Wait for PID
        start_time = time.time()
        while len(self.pids_received) == 0 and time.time() - start_time < 30:
            await asyncio.sleep(1)

        # 4. Check results
        if len(self.pids_received) == 0:
            logger.error("FAIL: Did not receive new session PID.")
            return False
        
        target_pid = self.pids_received[0]
        logger.info(f"Targeting PID {target_pid} for message test...")

        # 5. Send Message (Simulate Voice Command)
        self.hub.send("SendAiMessage", ["/dir", target_pid])
        
        # Wait for response (simplified check, real test would hook ReceiveAiResponse)
        await asyncio.sleep(5)

        if len(self.pids_received) > 1:
            logger.error(f"FAIL: Received multiple PIDs: {self.pids_received}")
            return False

        logger.info("SUCCESS: Exactly one session launched and targeted.")
        self.hub.stop()
        return True

if __name__ == "__main__":
    tester = TellPcTester()
    success = asyncio.run(tester.run_test())
    sys.exit(0 if success else 1)