import asyncio
import logging
from signalrcore.hub_connection_builder import HubConnectionBuilder
import sys
import json

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("TestPcgPersistence")

class PcgTester:
    def __init__(self, hub_url, api_key):
        self.hub_url = hub_url
        self.api_key = api_key
        self.connection = None
        
    async def start(self):
        self.connection = HubConnectionBuilder() \
            .with_url(self.hub_url) \
            .configure_logging(logging.WARNING) \
            .build()

        self.connection.on_open(lambda: logger.info("Connection opened."))
        self.connection.on_close(lambda: logger.info("Connection closed."))
        
        try:
            self.connection.start()
            await asyncio.sleep(2)
            
            # Authenticate
            self.connection.send("Authenticate", [self.api_key])
            await asyncio.sleep(1)

            world_id = "test_world_01"
            x, y = 123.45, 678.90
            test_data = json.dumps({"type": "rock", "variant": "mossy"})

            # 1. Save state
            logger.info(f"Saving state for {world_id} at ({x}, {y})...")
            self.connection.send("PcgSaveState", [world_id, x, y, test_data, False])
            await asyncio.sleep(1)

            # 2. Get state
            logger.info("Retrieving state...")
            # We use invoke because we want the return value
            # Actually signalrcore 'send' doesn't return value, we might need a callback or use another method
            # But the Hub method PcgGetState returns an object.
            # Signalrcore's build().invoke is for request-response
            
            # Note: signalrcore might not support direct synchronous-like invoke with return value in this way
            # without a callback for a specific event if the hub sends it back.
            # However, SignalR Hub methods can return values.
            
            # Let's try to use a callback approach if possible, or just rely on the fact that if it doesn't crash it's at least callable.
            # Wait, the Hub returns PcgObjectState. Let's see if we can catch it.
            
            # Re-creating connection to use a better client if needed, but let's try to use what we have.
            # In signalrcore-anywhere or similar, invoke() returns a promise/future.
            
            # If the user is using the standard 'signalrcore', it's a bit limited for return values without callbacks.
            # But let's try to send a command and see if we can get ALL states for world.
            
            # 3. Test via CommandDispatcher (PCG_SAVE_STATE)
            logger.info("Testing via CommandDispatcher...")
            payload = {
                "WorldId": world_id,
                "X": 10.0,
                "Y": 20.0,
                "Data": "DispatcherTest",
                "IsExclusion": True
            }
            self.connection.send("SendPayload", ["PCG_SAVE_STATE", payload])
            await asyncio.sleep(1)

            logger.info("POC Test complete. Check Hub logs and pcg_state.json")
            
        except Exception as e:
            logger.error(f"FAILURE: An error occurred: {e}")
            sys.exit(1)
        finally:
            self.connection.stop()

if __name__ == "__main__":
    bot = PcgTester("http://127.0.0.1:5000/signalrhub", "test_api_key")
    asyncio.run(bot.start())
