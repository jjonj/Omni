#!/usr/bin/env python3
import uuid
import argparse
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

parser = argparse.ArgumentParser(description="Omni CLI for sending commands to OmniSync.Hub.")
group = parser.add_mutually_exclusive_group(required=True)
group.add_argument("command", nargs='?', 
                   help="The command to run in single-command mode.")
group.add_argument("--persistent", "-p", action="store_true", 
                   help="Enter persistent mode for rapid, successive command execution. Reads commands from stdin.")
group.add_argument("--sequence", "-s", type=str, 
                   help="Enter sequence mode. Sends semicolon-separated commands, e.g., 'cmd1;cmd2;cmd3'")
args = parser.parse_args()

command_to_run_single_mode = None
commands_to_run = [] # Initialize as empty list

if args.command:
    command_to_run_single_mode = args.command
elif args.sequence:
    commands_to_run = [c.strip() for c in args.sequence.split(';') if c.strip()]
    if not commands_to_run:
        print("Error: --sequence requires at least one command.")
        sys.exit(1)
    
IS_PERSISTENT_MODE = args.persistent
IS_SEQUENCE_MODE = args.sequence is not None
IS_SINGLE_COMMAND_MODE = not IS_PERSISTENT_MODE and not IS_SEQUENCE_MODE

is_done = False 
connection_started = False 

# For sequence mode, we need a single event to signal completion of *any* command
sequence_command_completed_event = asyncio.Event()

def on_command_completed(args):
    global is_done
    command_str = args[0]
    
    # Check if this command completion is relevant for the current mode
    if IS_SINGLE_COMMAND_MODE and command_str == command_to_run_single_mode:
        print(f"Command '{command_str}' completed.")
        is_done = True
    elif IS_SEQUENCE_MODE:
        print(f"Command '{command_str}' completed. Scheduling event set to main loop.")
        GLOBAL_EVENT_LOOP.call_soon_threadsafe(sequence_command_completed_event.set)

async def connect_and_authenticate():
    global connection_started

    hub = HubConnectionBuilder()\
        .with_url(HUB_URL)\
        .configure_logging(logging.INFO)\
        .with_automatic_reconnect({
            "type": "raw",
            "keep_alive_interval": 10,
            "reconnect_interval": 5,
            "max_attempts": 5
        }).build()

    hub.on("ReceiveCommandOutput", on_output)
    if not IS_PERSISTENT_MODE:
        hub.on("CommandExecutionCompleted", on_command_completed)
    hub.on_close(on_close) 
    hub.on_open(on_open)   
    hub.on_error(on_error) 

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

    print("Connection established. Authenticating...")
    # Authentication does not need an invocationId to be tracked
    hub.send("Authenticate", [API_KEY]) 
    print("Authentication sent.")
    
    return hub

# Configure logging for signalrcore
logging.basicConfig(level=logging.INFO, 
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

GLOBAL_EVENT_LOOP = None

async def main():
    global is_done, connection_started, command_to_run_single_mode, GLOBAL_EVENT_LOOP

    total_execution_start_time = time.time() # Start stopwatch

    GLOBAL_EVENT_LOOP = asyncio.get_running_loop() # Get reference to the main event loop

    hub = None # Initialize hub to None
    try:
        hub = await connect_and_authenticate()
    
        if IS_SINGLE_COMMAND_MODE:
            print(f"Executing single command: {command_to_run_single_mode}")
            hub.send("ExecuteCommand", [command_to_run_single_mode]) 
            print("Command execution sent.")

            start_time = time.time()
            while not is_done and (time.time() - start_time) < 30: 
                 await asyncio.sleep(0.5)

            if not is_done:
                print("Command output might not have completed within timeout or connection hung.")
        elif IS_PERSISTENT_MODE:
            print("Entering persistent mode. Type commands and press Enter. Type '_QUIT_' to exit.")
            while True:
                try:
                    command = await asyncio.to_thread(input, "> ")
                    if command == "_QUIT_":
                        break
                    elif command.startswith("_SLEEP_"):
                        try:
                            sleep_time = float(command.split('_')[2])
                            print(f"Sleeping for {sleep_time} seconds...")
                            await asyncio.sleep(sleep_time)
                        except ValueError:
                            print("Invalid _SLEEP_X_ format. Use _SLEEP_SECONDS_.")
                        continue
                    hub.send("ExecuteCommand", [command])
                except EOFError: # Ctrl-D or stdin closed
                    print("EOF received. Exiting persistent mode.")
                    break
        elif IS_SEQUENCE_MODE:
            print(f"Executing sequence of {len(commands_to_run)} commands...")
            for cmd in commands_to_run:
                print(f"Sending command: {cmd}")
                sequence_command_completed_event.clear() # Clear the event before sending the next command
                hub.send("ExecuteCommand", [cmd])
                
                try:
                    # Wait for completion of this specific command with a timeout
                    await asyncio.wait_for(sequence_command_completed_event.wait(), timeout=30) 
                except asyncio.TimeoutError:
                    print(f"Timeout waiting for command '{cmd}' to complete.")

            print("All sequence commands sent and processed (or timed out).")

    except Exception as e: 
        print(f"Error during execution: {e}") 
        traceback.print_exc() 
    finally:
        if hub and connection_started: # Only try to stop if it was actually started
            hub.stop() 
            print("Connection stopped.")
        
        elapsed_time = time.time() - total_execution_start_time
        print(f"Total script execution duration: {elapsed_time:.2f} seconds")


if __name__ == "__main__":
    asyncio.run(main())
