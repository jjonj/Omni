import time
import requests
import json
import sys
import os

# Configuration
HUB_URL = "http://localhost:5000/signalrhub"
API_KEY = "your_api_key_here" # Needs to be updated or fetched

def get_api_key():
    # Try to load from appsettings.json
    try:
        with open("OmniSync.Hub/src/OmniSync.Hub/appsettings.json", "r") as f:
            config = json.load(f)
            return config.get("AuthApiKey")
    except:
        return "DEV_KEY_123" # Fallback for dev environment

def test_browser_sequence():
    api_key = get_api_key()
    print(f"Using API Key: {api_key}")
    
    # We use the SignalR Hub via simple HTTP if possible, 
    # but for testing we can just call the internal logic via a test controller if it exists
    # or use a python signalr client.
    
    # Given the environment, let's create a specialized test script that uses the existing hub.
    # We'll use the 'SendBrowserCommand' RPC method.
    
    from signalrcore.hub_connection_builder import HubConnectionBuilder
    
    hub_connection = HubConnectionBuilder()\
        .with_url(HUB_URL)\
        .with_automatic_reconnect({
            "type": "raw",
            "keep_alive_interval": 10,
            "reconnect_interval": 5,
            "max_attempts": 5
        }).build()

    connected = False
    
    @hub_connection.on_open
    def on_connect():
        nonlocal connected
        connected = True
        print("Connected to Hub")

    hub_connection.start()
    
    # Wait for connection
    for _ in range(10):
        if connected: break
        time.sleep(0.5)
        
    if not connected:
        print("Failed to connect to Hub")
        return

    # Authenticate
    auth_result = hub_connection.send("Authenticate", [api_key])
    time.sleep(1) # Wait for auth to process
    
    print("Step 1: Opening Google in new tab")
    hub_connection.send("SendBrowserCommand", ["OpenTab", "https://www.google.com", True])
    time.sleep(3)
    
    print("Step 2: Navigating current tab to Bing")
    hub_connection.send("SendBrowserCommand", ["OpenTab", "https://www.bing.com", False])
    time.sleep(3)
    
    print("Step 3: Scrolling down")
    hub_connection.send("SendBrowserCommand", ["ScrollDown", "", False])
    time.sleep(1)
    hub_connection.send("SendBrowserCommand", ["ScrollDown", "", False])
    time.sleep(2)
    
    print("Step 4: Scrolling up")
    hub_connection.send("SendBrowserCommand", ["ScrollUp", "", False])
    time.sleep(2)
    
    print("Step 5: Closing tab")
    # Note: CloseTab usually requires a tab ID or just closes active. 
    # Our implementation: await Clients.All.SendAsync("ReceiveBrowserCommand", command, url, newTab);
    hub_connection.send("SendBrowserCommand", ["CloseTab", "", False])
    time.sleep(2)

    print("Sequence completed!")
    hub_connection.stop()

if __name__ == "__main__":
    # Check if signalrcore is installed
    try:
        import signalrcore
    except ImportError:
        print("Installing signalrcore-client...")
        os.system(f"{sys.executable} -m pip install signalrcore-client")
    
    test_browser_sequence()
