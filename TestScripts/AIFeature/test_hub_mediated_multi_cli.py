import asyncio
import logging
import time
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"
# ---------------------

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("MultiCliHubTester")

class MultiCliHubTester:
    def __init__(self):
        self.connection_started = False
        self.hub = None
        self.responses = []
        self.response_event = asyncio.Event()
        self.loop = None

    def on_open(self):
        logger.info("Connection opened.")
        self.connection_started = True

    def _add_response(self, response):
        self.responses.append(response)
        self.response_event.set()

    def on_ai_response(self, args):
        response = args[0]
        logger.info(f"Received response ({len(self.responses) + 1})")
        if self.loop:
            self.loop.call_soon_threadsafe(self._add_response, response)

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
        print("      HUB-MEDIATED MULTI-CLI TEST")
        print("="*60 + "\n")

        prompt = "Please identify yourself by responding with your PID and tell me what time it is (roughly)."
        logger.info(f"Broadcasting prompt to all listeners via Hub: {prompt}")
        
        self.hub.send("SendAiMessage", [prompt])

        print("Waiting for responses (broadcast mode - all listeners should respond)...")
        
        # Wait up to 30 seconds for at least one response, then wait a bit more for others
        start_wait = time.time()
        while time.time() - start_wait < 60:
            try:
                await asyncio.wait_for(self.response_event.wait(), timeout=10)
                self.response_event.clear()
                # Give a small window for other responses to arrive
                await asyncio.sleep(5) 
                if len(self.responses) > 0:
                    break
            except asyncio.TimeoutError:
                if len(self.responses) > 0:
                    break
                continue

        print(f"\nCaptured {len(self.responses)} response(s):")
        for i, res in enumerate(self.responses):
            print(f"\n--- Response {i+1} ---")
            print(res)

        self.hub.stop()
        print("\nTest Complete.")

if __name__ == "__main__":
    asyncio.run(MultiCliHubTester().run_test())
