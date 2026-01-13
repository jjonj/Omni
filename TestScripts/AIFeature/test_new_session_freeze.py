import time
import logging
import sys
import os
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"
# ---------------------

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("FreezeTest")

def on_open():
    logger.info("Connection opened.")

def on_error(data):
    logger.error(f"SignalR Error: {data}")

def on_ai_status(status):
    logger.info(f"AI Status Received: {status}")

def on_ai_sessions(sessions):
    logger.info(f"AI Sessions Received: {sessions}")

def on_ai_history(history):
    logger.info(f"AI History Received (length: {len(history)})")

def test_freeze():
    hub = HubConnectionBuilder()\
        .with_url(HUB_URL)\
        .configure_logging(logging.INFO)\
        .build()

    hub.on_open(on_open)
    hub.on_error(on_error)
    hub.on("ReceiveAiStatus", on_ai_status)
    hub.on("ReceiveAiSessions", on_ai_sessions)
    hub.on("ReceiveAiHistory", on_ai_history)
    
    hub.start()

    # Wait for connection
    time.sleep(2)
    
    logger.info("Authenticating...")
    hub.send("Authenticate", [API_KEY])
    time.sleep(2)

    logger.info("Requesting new AI session...")
    start_time = time.time()
    try:
        hub.send("StartNewAiSession", [])
        
        for i in range(10):
            time.sleep(2)
            logger.info(f"Heartbeat {i+1}: Hub still responding? Requesting volume...")
            hub.send("GetVolume", [])
            
    except Exception as e:
        logger.error(f"Error during StartNewAiSession: {e}")

    end_time = time.time()
    logger.info(f"Total test time: {end_time - start_time:.2f} seconds")
    
    time.sleep(5)
    hub.stop()

if __name__ == "__main__":
    test_freeze()
