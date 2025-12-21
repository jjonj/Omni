import asyncio
import logging
import time
import argparse
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"
# ---------------------

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("MultiCliHubTester")

class MultiCliHubTester:
    def __init__(self, target_pids=None):
        self.connection_started = False
        self.hub = None
        self.responses = []
        self.response_event = asyncio.Event()
        self.loop = None
        self.target_pids = target_pids
        self.sessions_received_event = asyncio.Event()
        self.pids = []

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

    def on_ai_sessions(self, args):
        pids = args[0]
        logger.info(f"Received AI Sessions: {pids}")
        self.pids = pids
        self.sessions_received_event.set()

    async def run_test(self):
        self.loop = asyncio.get_running_loop()
        self.sessions_received_event = asyncio.Event()
        self.pids = []
        
        self.hub = HubConnectionBuilder() \
            .with_url(HUB_URL) \
            .configure_logging(logging.INFO) \
            .build()

        self.hub.on_open(self.on_open)
        self.hub.on("ReceiveAiResponse", self.on_ai_response)
        self.hub.on("ReceiveAiSessions", self.on_ai_sessions)

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

        # Step 1: Resolve PIDs
        if self.target_pids:
            logger.info(f"Using explicitly provided PIDs: {self.target_pids}")
            self.pids = self.target_pids
        else:
            logger.info("Requesting AI sessions from Hub...")
            self.hub.send("GetAiSessions", [])
            try:
                await asyncio.wait_for(self.sessions_received_event.wait(), timeout=10)
            except asyncio.TimeoutError:
                logger.error("Timed out waiting for session list.")
                self.hub.stop()
                return

        if not self.pids:
            logger.error("No active AI sessions found.")
            self.hub.stop()
            return

        # Step 2: Iterate through sessions and test
        for i, pid in enumerate(self.pids):
            instance_id = i + 1
            logger.info(f"--- Testing Session {instance_id} (PID: {pid}) ---")
            
            # Switch to this session
            self.hub.send("SwitchAiSession", [pid])
            await asyncio.sleep(2) 
            
            # Send targeted prompt
            prompt = f"Confirming multi-instance test. You are designated as Instance {instance_id}. Please repeat back: 'I confirm I am Instance {instance_id}'"
            logger.info(f"Sending prompt to PID {pid}: {prompt}")
            
            self.response_event.clear()
            self.hub.send("SendAiMessage", [prompt])
            
            # Wait for response
            try:
                await asyncio.wait_for(self.response_event.wait(), timeout=60)
                logger.info(f"Response received for Instance {instance_id}")
            except asyncio.TimeoutError:
                logger.error(f"Timed out waiting for response from Instance {instance_id}")

        print(f"\nCaptured {len(self.responses)} total response(s) across all instances.")
        for i, res in enumerate(self.responses):
            print(f"\n--- Response {i+1} ---")
            print(res)

        self.hub.stop()
        print("\nTest Complete.")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--pids", type=int, nargs='+', help="Specific PIDs to test")
    args = parser.parse_args()
    
    asyncio.run(MultiCliHubTester(target_pids=args.pids).run_test())