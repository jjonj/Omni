import asyncio
from signalrcore.hub_connection_builder import HubConnectionBuilder
import logging
import json
import time

hub_url = "http://10.0.0.37:5000/signalrhub"
api_key = "test_api_key"
key_code_a = 0x41 # Virtual Key Code for 'A' is 65 (0x41 Hex)

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

    hub_connection.on_open(lambda: print("Connection opened."))
    hub_connection.on_close(lambda: print("Connection closed."))
    hub_connection.on_error(lambda data: print(f"Connection error: {data.error}"))

    # Start the connection
    print(f"Connecting to {hub_url}...")
    hub_connection.start()
    await asyncio.sleep(1) # Give it a moment to establish connection
    print("Connection started.")

    # Authenticate
    print("Authenticating...")
    hub_connection.send("Authenticate", [api_key])
    print("Authentication sent (response not captured).")

    # Send 'A' key press commands
    print("Sending 'A' key press commands...")
    for i in range(10):
        print(f"Sending 'A' key press ({i+1}/10)...")
        payload = {"KeyCode": key_code_a}
        hub_connection.send("SendPayload", ["INPUT_KEY_PRESS", payload]) # Pass the dictionary directly
        time.sleep(0.5) # Wait half a second between key presses

    print("Stopping connection...")
    hub_connection.stop()
    print("Connection stopped.")

if __name__ == "__main__":
    asyncio.run(main())
