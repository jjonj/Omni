import asyncio
import logging
import sys
import time
import subprocess
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"
# ---------------------

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("TestHubExit")

class HubExitTester:
    def __init__(self):
        self.connection_started = False
        self.hub = None

    def on_open(self):
        logger.info("Connection opened.")
        self.connection_started = True

    def on_close(self):
        logger.info("Connection closed.")

    def on_error(self, error):
        logger.error(f"Error: {error}")

    async def run_test(self):
        self.hub = HubConnectionBuilder() \
            .with_url(HUB_URL) \
            .configure_logging(logging.INFO) \
            .build()

        self.hub.on_open(self.on_open)
        self.hub.on_close(self.on_close)
        self.hub.on_error(self.on_error)

        self.hub.start()

        # Wait for connection
        for _ in range(10):
            if self.connection_started: break
            await asyncio.sleep(1)
        
        if not self.connection_started:
            logger.error("Failed to connect.")
            return

        # Authenticate
        self.hub.send("Authenticate", [API_KEY])
        await asyncio.sleep(1)

        # Trigger Hub Exit
        logger.info("Sending HUB_EXIT command...")
        self.hub.send("SendPayload", ["HUB_EXIT", None])

        await asyncio.sleep(10)
        
        # Check if process is still running
        logger.info("Checking if OmniSync.Hub.exe is still running...")
        result = subprocess.run("tasklist /FI \"IMAGENAME eq OmniSync.Hub.exe\"", shell=True, capture_output=True, text=True)
        
        if "OmniSync.Hub.exe" not in result.stdout:
            logger.info("SUCCESS: OmniSync.Hub.exe has exited.")
        else:
            logger.error("FAIL: OmniSync.Hub.exe is still running.")
            logger.error(result.stdout)

        self.hub.stop()

if __name__ == "__main__":
    tester = HubExitTester()
    asyncio.run(tester.run_test())
