import asyncio
import logging
import sys
import time
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://10.0.0.37:5000/signalrhub"
API_KEY = "test_api_key"
# ---------------------

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("TestAI")

class AiTester:
    def __init__(self):
        self.connection_started = False
        self.message_received = False
        self.response_received = False
        self.hub = None

    def on_open(self):
        logger.info("Connection opened.")
        self.connection_started = True

    def on_close(self):
        logger.info("Connection closed.")

    def on_error(self, error):
        logger.error(f"Error: {error}")

    def on_ai_message(self, args):
        sender_id, message = args
        logger.info(f"HUB BROADCAST: Message from {sender_id}: {message}")
        self.message_received = True

    def on_ai_response(self, args):
        response = args[0]
        logger.info(f"HUB BROADCAST: AI Response: {response}")
        self.response_received = True

    async def run_test(self):
        self.hub = HubConnectionBuilder()\
            .with_url(HUB_URL)\
            .configure_logging(logging.INFO)\
            .build()

        self.hub.on("ReceiveAiMessage", self.on_ai_message)
        self.hub.on("ReceiveAiResponse", self.on_ai_response)
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

        # Send test message
        test_msg = "Hello AI, this is a automated test."
        logger.info(f"Sending message: {test_msg}")
        self.hub.send("SendAiMessage", [test_msg])

        # Wait for events
        start_time = time.time()
        while time.time() - start_time < 30:
            if self.message_received and self.response_received:
                break
            await asyncio.sleep(1)

        if not self.message_received:
            logger.error("FAIL: Did not receive ReceiveAiMessage broadcast from Hub.")
        if not self.response_received:
            logger.error("FAIL: Did not receive ReceiveAiResponse broadcast (is ai_listener.py running?)")
        
        if self.message_received and self.response_received:
            logger.info("SUCCESS: Full AI roundtrip confirmed.")

        self.hub.stop()

if __name__ == "__main__":
    tester = AiTester()
    asyncio.run(tester.run_test())