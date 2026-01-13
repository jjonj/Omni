import asyncio
from signalrcore.hub_connection_builder import HubConnectionBuilder
import logging
import time

"""
Test script for the Browser Control functionality in OmniSync.
This script validates that the SendBrowserCommand Hub method works correctly
and that the Chrome extension can receive and process browser commands.

Prerequisites:
1. Hub is running (run_omnihub.py)
2. Chrome extension is loaded and connected
3. Chrome browser is open

Test Cases:
1. Navigate to Google (current tab)
2. Navigate to YouTube (new tab if implemented)
3. Refresh the page
4. Go back
5. Go forward
"""

hub_url = "http://10.0.0.37:5000/signalrhub"
api_key = "test_api_key"

async def main():
    hub_connection = HubConnectionBuilder() \
        .with_url(hub_url) \
        .configure_logging(logging.INFO) \
        .with_automatic_reconnect({
            "type": "raw",
            "keep_alive_interval": 10,
            "intervals": [1, 2, 5, 10]
        }) \
        .build()

    hub_connection.on_open(lambda: print("✓ Connection opened."))
    hub_connection.on_close(lambda: print("✗ Connection closed."))
    hub_connection.on_error(lambda data: print(f"✗ Connection error: {data.error}"))

    # Start the connection
    print(f"\n🔌 Connecting to {hub_url}...")
    hub_connection.start()
    await asyncio.sleep(1)
    print("✓ Connection established.")

    # Authenticate
    print("\n🔐 Authenticating...")
    hub_connection.send("Authenticate", [api_key])
    await asyncio.sleep(1)
    print("✓ Authentication complete.")

    print("\n" + "="*50)
    print("🌐 Testing Browser Control Commands")
    print("="*50)

    # Test 1: Navigate to Google (current tab)
    print("\n[Test 1] Navigating to Google in current tab...")
    hub_connection.send("SendBrowserCommand", ["Navigate", "https://google.com", False])
    await asyncio.sleep(4)
    print("✓ Sent Navigate command to google.com")

    # Test 2: Refresh the page
    print("\n[Test 2] Refreshing the page...")
    hub_connection.send("SendBrowserCommand", ["Refresh", "", False])
    await asyncio.sleep(3)
    print("✓ Sent Refresh command")

    # Test 3: Navigate to YouTube in new tab
    print("\n[Test 3] Opening YouTube in new tab...")
    hub_connection.send("SendBrowserCommand", ["Navigate", "https://youtube.com", True])
    await asyncio.sleep(4)
    print("✓ Sent Navigate command to youtube.com (new tab)")

    # Test 4: Navigate to ChatGPT
    print("\n[Test 4] Navigating to ChatGPT...")
    hub_connection.send("SendBrowserCommand", ["Navigate", "https://chatgpt.com", False])
    await asyncio.sleep(4)
    print("✓ Sent Navigate command to chatgpt.com")

    # Test 5: Go back
    print("\n[Test 5] Going back...")
    hub_connection.send("SendBrowserCommand", ["Back", "", False])
    await asyncio.sleep(2)
    print("✓ Sent Back command")

    # Test 6: Go forward
    print("\n[Test 6] Going forward...")
    hub_connection.send("SendBrowserCommand", ["Forward", "", False])
    await asyncio.sleep(2)
    print("✓ Sent Forward command")

    print("\n" + "="*50)
    print("✅ All browser commands sent successfully!")
    print("="*50)
    print("\n📋 Validation Checklist:")
    print("  1. Did Chrome open google.com?")
    print("  2. Did the page refresh?")
    print("  3. Did YouTube open in a new tab?")
    print("  4. Did ChatGPT load in the current tab?")
    print("  5. Did the Back button work?")
    print("  6. Did the Forward button work?")
    print("\n💡 Check Chrome DevTools Console for extension logs")
    print("   (Look for 'Command: Navigate, URL: ...' messages)")

    print("\n🔌 Stopping connection...")
    hub_connection.stop()
    print("✓ Connection stopped.")

if __name__ == "__main__":
    print("\n" + "="*50)
    print("🧪 OmniSync Browser Control Test Suite")
    print("="*50)
    print("\n⚠️  Prerequisites:")
    print("  • Hub is running (run_omnihub.py)")
    print("  • Chrome extension is loaded and connected")
    print("  • Chrome browser is open")
    print("\nStarting tests in 3 seconds...")
    time.sleep(3)
    asyncio.run(main())
