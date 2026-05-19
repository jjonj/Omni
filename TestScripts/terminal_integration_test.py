import asyncio
import json
import time
import requests
from signalrcore.hub_connection_builder import HubConnectionBuilder

# Configuration
HUB_URL = "http://localhost:5000/terminalhub"
REST_URL = "http://localhost:5000/api/external"
API_KEY = "test_api_key"

def on_output_received(args):
    pid = args[0]
    data = args[1]
    print(f"[TERM {pid}] {data.strip()}")

async def main():
    print("--- OmniHub Terminal Integration Test ---")
    
    # 1. Setup SignalR Connection
    connection = HubConnectionBuilder() \
        .with_url(HUB_URL) \
        .with_automatic_reconnect({
            "type": "raw",
            "keep_alive_interval": 10,
            "intervals": [1, 2, 5, 10]
        }) \
        .build()

    connection.on("ReceiveOutput", on_output_received)
    connection.on_open(lambda: print("SignalR Connected."))
    connection.on_close(lambda: print("SignalR Disconnected."))

    connection.start()
    
    # Wait for connection
    time.sleep(3)

    # 2. Authenticate
    print("Sending Authentication...")
    # Using send without callback if it hangs. 
    # Actually, SignalR Python client usually uses .send(method, args)
    connection.send("Authenticate", [API_KEY])
    
    print("Wait for Hub to settle...")
    time.sleep(2)

    # 3. Spawn Terminal (with retries)
    print("Spawning terminal via REST...")
    pid = None
    for i in range(10):
        try:
            spawn_res = requests.post(f"{REST_URL}/terminal/spawn?key={API_KEY}&shell=cmd.exe", timeout=5)
            if spawn_res.status_code == 200:
                pid = spawn_res.json()['pid']
                break
            else:
                print(f"Attempt {i+1}: Spawn failed ({spawn_res.status_code}: {spawn_res.text})...")
        except Exception as e:
            print(f"Attempt {i+1}: Hub not ready... {e}")
        time.sleep(3)

    if pid is None:
        print("Spawn failed after retries. Aborting.")
        connection.stop()
        return
    print(f"Terminal spawned with PID: {pid}")

    # 4. Subscribe to PID
    print(f"Subscribing to output for PID {pid}...")
    connection.send("Subscribe", [pid])
    time.sleep(2)

    # 5. Send Command
    command = "ping google.com -n 4"
    print(f"Sending command: {command}")
    send_res = requests.post(
        f"{REST_URL}/terminal/send?key={API_KEY}", 
        json={"Pid": pid, "Command": command}
    )
    
    if send_res.status_code == 200:
        print("Command sent successfully. Waiting for output...")
    else:
        print(f"Failed to send command: {send_res.status_code}")

    # Wait for output
    await asyncio.sleep(15)

    print("Test finished. Closing connection...")
    connection.stop()

if __name__ == "__main__":
    asyncio.run(main())
