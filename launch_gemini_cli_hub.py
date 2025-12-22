#!/usr/bin/env python3
import time
import sys
import logging
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"
WORKSPACE = r"D:/SSDProjects"
# ---------------------

def launch_via_hub():
    logging.basicConfig(level=logging.INFO)
    logger = logging.getLogger("HubLauncher")

    connection_started = False

    def on_open():
        nonlocal connection_started
        logger.info("Connection opened.")
        connection_started = True

    hub = HubConnectionBuilder()\
        .with_url(HUB_URL)\
        .configure_logging(logging.INFO)\
        .build()

    hub.on_open(on_open)
    hub.on_error(lambda data: logger.error(f"Error: {data}"))
    hub.start()

    # Wait for connection
    for _ in range(10):
        if connection_started: break
        time.sleep(1)
    
    if not connection_started:
        logger.error("Failed to connect to Hub.")
        sys.exit(1)

    # Authenticate
    logger.info("Authenticating...")
    hub.send("Authenticate", [API_KEY])
    time.sleep(1)

    # Launch
    logger.info(f"Telling Hub to launch CLI at: {WORKSPACE}")
    hub.send("StartCliAtWorkspace", [WORKSPACE])
    
    # Wait a bit for the command to be sent
    time.sleep(2)
    
    logger.info("Command sent. Closing connection.")
    hub.stop()

if __name__ == "__main__":
    launch_via_hub()