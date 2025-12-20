import asyncio
import logging
import sys
import time
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"
# ---------------------

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("TestShutdownSound")

class ShutdownSoundTester:
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

        # Toggle mode test
        logger.info("Toggling shutdown mode to Sleep...")
        self.hub.send("ToggleShutdownMode", [])
        await asyncio.sleep(2)
        logger.info("Toggling shutdown mode back to Shutdown...")
        self.hub.send("ToggleShutdownMode", [])
        await asyncio.sleep(2)

        # Schedule shutdown for 2 minutes
        # Command: SCHEDULE_SHUTDOWN, Payload: { "Minutes": 2 }
        logger.info("Scheduling shutdown for 2 minutes...")
        self.hub.send("SendPayload", ["SCHEDULE_SHUTDOWN", {"Minutes": 2}])

        logger.info("Waiting 65 seconds to allow the sound to trigger...")
        await asyncio.sleep(65)

        logger.info("Cancelling shutdown...")
        self.hub.send("SendPayload", ["SCHEDULE_SHUTDOWN", {"Minutes": 0}])
        await asyncio.sleep(1)

        self.hub.stop()
        logger.info("Test script finished. Please check hub_output.log for 'Playing shutdown warning sound.'")

if __name__ == "__main__":
    tester = ShutdownSoundTester()
    asyncio.run(tester.run_test())
