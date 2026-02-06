import time
import requests
import json
import sys
import os

# Configuration
HUB_URL = "http://10.0.0.37:5000/signalrhub"
API_KEY = "test_api_key"

def test_browser_sequence():
    print(f"--- Browser Control Diagnostic Test ---")
    print(f"Target Hub: {HUB_URL}")
    
    from signalrcore.hub_connection_builder import HubConnectionBuilder
    
    hub_connection = HubConnectionBuilder()\
        .with_url(HUB_URL)\
        .with_automatic_reconnect({
            "type": "raw",
            "keep_alive_interval": 10,
            "reconnect_interval": 5,
            "max_attempts": 5
        }).build()

    connected = threading.Event()
    
    # Listeners for extension feedback
    def on_tab_info(data):
        print(f"FEEDBACK: Received Tab Info: {data}")

    def on_tab_list(data):
        print(f"FEEDBACK: Received Tab List: {len(data)} tabs")
        for tab in data:
            print(f"  - {tab.get('title')} ({tab.get('url')})")

    def on_cleanup_patterns(data):
        print(f"FEEDBACK: Received Cleanup Patterns: {data}")

    def on_browser_broadcast(data):
        # This is the Hub broadcasting the command we just sent
        print(f"HUB BROADCAST: {data}")

    hub_connection.on_open(lambda: (print("SignalR: Connected to Hub"), connected.set()))
    hub_connection.on_error(lambda data: print(f"SignalR Error: {data}"))
    
    # Register listeners for Hub events that the extension triggers
    hub_connection.on("ReceiveTabInfo", on_tab_info)
    hub_connection.on("ReceiveTabList", on_tab_list)
    hub_connection.on("ReceiveCleanupPatterns", on_cleanup_patterns)
    hub_connection.on("ReceiveBrowserCommand", on_browser_broadcast)

    print("Connecting...")
    hub_connection.start()
    
    if not connected.wait(timeout=10):
        print("Error: Connection timeout")
        return

    # Authenticate
    hub_connection.send("Authenticate", [API_KEY])
    time.sleep(1)
    
    # Diagnostic: Check Hub status to see if extension is connected
    print("Checking extension responsiveness with Ping...")
    hub_connection.send("SendBrowserCommand", ["Ping", "", False])
    time.sleep(2)

    print("Step 1: Requesting Tab List from extension...")
    hub_connection.send("SendBrowserCommand", ["ListTabs", "", False])
    
    print("Step 2: Requesting current Tab Info...")
    hub_connection.send("SendBrowserCommand", ["GetTabInfo", "", False])
    
    print("Step 3: Opening Google in new tab")
    hub_connection.send("SendBrowserCommand", ["OpenTab", "https://www.google.com", True])
    
    print("Waiting 10 seconds for feedback from extension...")
    time.sleep(10)

    print("Step 4: Attempting to close active tab...")
    hub_connection.send("SendBrowserCommand", ["CloseTab", "", False])
    
    time.sleep(2)
    print("Test sequence finished.")
    hub_connection.stop()

import threading
import time
if __name__ == "__main__":
    test_browser_sequence()
