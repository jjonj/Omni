import asyncio
import logging
import sys
import time
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"
OMNI_DIR = r"D:\\SSDProjects\\Omni"
# ---------------------

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("AICommandTesterHub")

class AICommandTesterHub:
    def __init__(self):
        self.connection_started = False
        self.hub = None
        self.responses = asyncio.Queue()
        self.loop = None

    def on_open(self):
        logger.info("Connection opened.")
        self.connection_started = True

    def on_ai_response(self, args):
        response = args[0]
        logger.info("--- AI RESPONSE RECEIVED (Relaying to Queue) ---")
        if self.loop:
            self.loop.call_soon_threadsafe(self.responses.put_nowait, response)
        else:
            logger.error("Loop not yet initialized in tester!")

    async def wait_for_response(self, timeout=60):
        try:
            return await asyncio.wait_for(self.responses.get(), timeout=timeout)
        except asyncio.TimeoutError:
            return None

    async def run_test(self):
        self.loop = asyncio.get_running_loop()
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

        print("\n" + "="*60)
        print("      HUB-MEDIATED AI COMMAND TEST")
        print("="*60 + "\n")

        # 1. Set directory context
        command1 = f"/dir add {OMNI_DIR}"
        logger.info(f"Step 1: Sending AI command via Hub: {command1}")
        self.hub.send("SendAiMessage", [command1])

        res1 = await self.wait_for_response(timeout=15)
        if res1:
            logger.info(f"Response 1 Captured: {res1}")
        else:
            logger.error("Timed out waiting for first AI response.")
            self.hub.stop()
            return

        # 2. Ask about Tasks.txt
        command2 = "Read Tasks.txt and tell me what is the first task in it."
        logger.info(f"Step 2: Sending message via Hub: {command2}")
        self.hub.send("SendAiMessage", [command2])

        res2 = await self.wait_for_response(timeout=120)
        if res2:
            logger.info("--- AI RESPONSE 2 ---")
            print(res2)
            logger.info("--- END RESPONSE 2 ---")
        else:
            logger.error("Timed out waiting for second AI response.")

        self.hub.stop()
        print("\nTest Complete.")

if __name__ == "__main__":
    tester = AICommandTesterHub()
    asyncio.run(tester.run_test())
