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
logger = logging.getLogger("TestAIIntegration")

class AICommandTester:
    def __init__(self):
        self.connection_started = False
        self.hub = None
        self.response_received = False

    def on_open(self):
        logger.info("Connection opened.")
        self.connection_started = True

    def on_ai_response(self, args):
        response = args[0]
        logger.info("--- AI RESPONSE RECEIVED ---")
        logger.info(response)
        logger.info("--- END RESPONSE ---")
        self.response_received = True

    async def run_test(self):
        self.hub = HubConnectionBuilder() \
            .with_url(HUB_URL) \
            .configure_logging(logging.INFO) \
            .build()

        self.hub.on_open(self.on_open)
        self.hub.on("ReceiveAiResponse", self.on_ai_response)

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

        # 1. Set directory context
        command1 = r"/dir add D:\SSDProjects\Omni"
        logger.info(f"Step 1: Sending AI command: {command1}")
        self.hub.send("SendAiMessage", [command1])

        # Wait for first response
        for _ in range(30):
            if self.response_received: break
            await asyncio.sleep(1)
        
        if not self.response_received:
            logger.error("Timed out waiting for first AI response.")
            return

        self.response_received = False

        # 2. Ask about Tasks.txt
        command2 = "Read Tasks.txt and tell me what is the first task in it."
        logger.info(f"Step 2: Sending message: {command2}")
        self.hub.send("SendAiMessage", [command2])

        # Wait for second response
        for _ in range(30):
            if self.response_received: break
            await asyncio.sleep(1)

        if not self.response_received:
            logger.error("Timed out waiting for second AI response.")

        self.hub.stop()

if __name__ == "__main__":
    tester = AICommandTester()
    asyncio.run(tester.run_test())
