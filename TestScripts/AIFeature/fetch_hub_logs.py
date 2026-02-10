#!/usr/bin/env python3
import asyncio
import sys
import logging
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"

logging.basicConfig(level=logging.ERROR, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("FetchLogs")

class LogFetcher:
    def __init__(self):
        self.connection_started = False
        self.hub = None
        self.logs_received = asyncio.Event()
        self.logs = []

    def on_open(self):
        self.connection_started = True

    def on_receive_logs(self, args):
        self.logs = args[0]
        self.logs_received.set()

    async def run(self):
        self.hub = (HubConnectionBuilder()
            .with_url(HUB_URL)
            .configure_logging(logging.ERROR)
            .build())

        self.hub.on_open(self.on_open)
        self.hub.on("ReceiveHubLogs", self.on_receive_logs)
        self.hub.start()

        for _ in range(5):
            if self.connection_started: break
            await asyncio.sleep(1)
        
        if not self.connection_started:
            print("Failed to connect to Hub.")
            return

        self.hub.send("Authenticate", [API_KEY])
        await asyncio.sleep(1)

        self.hub.send("BroadcastHubLogs", [])
        
        try:
            await asyncio.wait_for(self.logs_received.wait(), timeout=5.0)
            for log in self.logs:
                print(log)
        except asyncio.TimeoutError:
            print("Timed out waiting for logs from Hub.")
        
        self.hub.stop()

if __name__ == "__main__":
    asyncio.run(LogFetcher().run())
