#!/usr/bin/env python3
import sys
import time
import logging
import asyncio 
import traceback 
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://10.0.0.37:5000/signalrhub" # Your PC IP
API_KEY = "test_api_key"                     # Your Hub Secret
# ---------------------

if len(sys.argv) < 2:
    print("Usage: omni <command>")
    sys.exit(1)

command_to_run = " ".join(sys.argv[1:])

is_done = False 
connection_started = False 

# Configure logging for signalrcore
logging.basicConfig(level=logging.DEBUG, 
                    format='%(asctime)s - %(levelname)s - %(message)s')

def on_output(args):
    print(args[0], end="")

def on_close():
    global is_done, connection_started 
    print("Connection closed.")
    is_done = True
    connection_started = False

def on_open():
    global connection_started
    print("Connection opened.")
    connection_started = True

def on_error(error):
    print(f"Connection error: {error}")

async def main():
    global is_done, connection_started 

    # Start the stopwatch immediately at the beginning of the main function
    total_execution_start_time = time.time() 

    hub = HubConnectionBuilder()\
        .with_url(HUB_URL)\
        .configure_logging(logging.DEBUG, socket_trace=True)\
        .with_automatic_reconnect({
            "type": "raw",
            "keep_alive_interval": 10,
            "reconnect_interval": 5,
            "max_attempts": 5
        }).build()

    hub.on("ReceiveCommandOutput", on_output)
    hub.on_close(on_close) 
    hub.on_open(on_open)   
    hub.on_error(on_error) 

    try:
        print(f"Attempting to connect to {HUB_URL}...")
        hub.start() 
        print("hub.start() initiated (without await).")

        timeout = 15 
        start_time = time.time()
        while not connection_started and (time.time() - start_time) < timeout:
            print(f"Waiting for connection... ({int(time.time() - start_time)}s/{timeout}s)")
            await asyncio.sleep(1) 
        
        if not connection_started:
            print("Failed to establish connection within timeout.")
            hub.stop() 
            sys.exit(1)

        print("Connection established. Authenticating and executing command...")
        hub.send("Authenticate", [API_KEY]) 
        print("Authentication sent.")
        
        hub.send("ExecuteCommand", [command_to_run]) 
        print("Command execution sent.")

        start_time = time.time()
        while not is_done and (time.time() - start_time) < 30: 
             await asyncio.sleep(0.5)

        if not is_done:
            print("Command output might not have completed within timeout or connection hung.")
        
        hub.stop() 
        print("Connection stopped.")

    except Exception as e: 
        print(f"Error during execution: {e}") 
        traceback.print_exc() 
        try:
            hub.stop()
        except Exception as stop_e:
            print(f"Error stopping hub: {stop_e}")
        sys.exit(1)
    finally:
        elapsed_time = time.time() - total_execution_start_time
        print(f"Total script execution duration: {elapsed_time:.2f} seconds")


if __name__ == "__main__":
    asyncio.run(main())
