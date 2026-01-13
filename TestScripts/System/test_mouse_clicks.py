import asyncio
from signalrcore.hub_connection_builder import HubConnectionBuilder
import logging
import json
import time

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
    await asyncio.sleep(1) # Give it a moment to authenticate
    print("Authentication sent.")

    # Move mouse to the right by 1200 pixels
    print("Moving mouse to the right by 1000 pixels...")
    mouse_move_payload = {"X": 1200, "Y": 0}
    hub_connection.send("MouseMove", [mouse_move_payload]) # Assuming MouseMove is a direct method on RpcApiHub
    await asyncio.sleep(3)

    # Perform a left click
    print("Performing a left click...")
    hub_connection.send("SendPayload", ["LEFT_CLICK", json.dumps({})]) # Explicitly empty JSON string
    await asyncio.sleep(1) # Wait for 1 second

    # Perform a right click
    print("Performing a right click...")
    hub_connection.send("SendPayload", ["RIGHT_CLICK", json.dumps({})]) # Explicitly empty JSON string
    await asyncio.sleep(1) # Wait for 1 second

    print("Stopping connection...")
    hub_connection.stop()
    print("Connection stopped.")

if __name__ == "__main__":
    asyncio.run(main())
