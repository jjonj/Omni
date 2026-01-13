import threading
import time
import json
import sys
import os
from signalrcore.hub_connection_builder import HubConnectionBuilder

# Configuration
HUB_URL = "http://10.0.0.37:5000/signalrhub"
API_KEY = "test_api_key"

class ExtensionTester:
    def __init__(self):
        self.connected = threading.Event()
        self.feedback_received = threading.Condition()
        self.last_tab_info = None
        self.hub_connection = HubConnectionBuilder()\
            .with_url(HUB_URL)\
            .with_automatic_reconnect({
                "type": "raw",
                "keep_alive_interval": 10,
                "reconnect_interval": 5,
                "max_attempts": 5
            }).build()
        
        self.hub_connection.on_open(self.on_connect)
        self.hub_connection.on_error(lambda data: print(f"SignalR Error: {data}"))
        
        # Feedback Listeners
        self.hub_connection.on("ReceiveTabInfo", self.on_tab_info)
        self.hub_connection.on("ReceiveTabList", lambda data: print(f"FEEDBACK: Received list of {len(data)} tabs"))
        self.hub_connection.on("ReceiveBrowserCommand", lambda data: print(f"HUB BROADCAST: {data}"))

    def on_connect(self):
        print("SignalR: Connected to Hub")
        self.connected.set()

    def on_tab_info(self, data):
        with self.feedback_received:
            self.last_tab_info = data
            print(f"VERIFICATION: Browser is at: {data[1]}")
            self.feedback_received.notify_all()

    def wait_for_url(self, expected_part, timeout=5):
        start_time = time.time()
        while time.time() - start_time < timeout:
            with self.feedback_received:
                self.hub_connection.send("SendBrowserCommand", ["GetTabInfo", "", False])
                if self.feedback_received.wait(timeout=1):
                    if expected_part.lower() in self.last_tab_info[1].lower():
                        return True
        return False

    def send_and_verify(self, cmd, url="", new_tab=False, expected_url_part=None):
        print(f"\n>>> TEST: {cmd} (URL: {url}, NewTab: {new_tab})")
        self.hub_connection.send("SendBrowserCommand", [cmd, url, new_tab])
        
        if expected_url_part:
            print(f"Waiting for browser to reach: {expected_url_part}...")
            if self.wait_for_url(expected_url_part):
                print(f"SUCCESS: Verified {cmd}")
                return True
            else:
                print(f"FAILED: {cmd} did not reach expected state.")
                return False
        time.sleep(1)
        return True

    def run_all_tests(self):
        print("Starting Hub connection...")
        self.hub_connection.start()
        if not self.connected.wait(timeout=10): return

        self.hub_connection.send("Authenticate", [API_KEY])
        time.sleep(1)

        print("\n--- Phase 1: New Tab vs Existing Tab ---")
        
        # 1. Open in NEW tab
        if not self.send_and_verify("OpenTab", "https://www.wikipedia.org", True, "wikipedia.org"):
            return

        # 2. Navigate EXISTING tab (the one we just opened)
        # Note: Navigate in our extension updates the active tab.
        if not self.send_and_verify("Navigate", "https://www.google.com", False, "google.com"):
            return

        print("\n--- Phase 2: History (Back/Forward) ---")
        
        # 3. Back (Should go from Google back to Wikipedia)
        if not self.send_and_verify("Back", "", False, "wikipedia.org"):
            print("Hint: History might take a moment to register in some browsers.")
        
        # 4. Forward (Should go back to Google)
        self.send_and_verify("Forward", "", False, "google.com")

        print("\n--- Phase 3: Utility Commands ---")
        self.send_and_verify("Refresh")
        self.send_and_verify("Ping")
        self.send_and_verify("CleanTabs") # Close matches (Google is a default match)
        
        print("\nStep: Closing verified tab...")
        self.send_and_verify("CloseTab")

        print("\nVerification Test Finished.")
        self.hub_connection.stop()

if __name__ == "__main__":
    tester = ExtensionTester()
    tester.run_all_tests()
