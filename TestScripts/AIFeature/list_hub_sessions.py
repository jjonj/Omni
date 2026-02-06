import asyncio
import logging
from signalrcore.hub_connection_builder import HubConnectionBuilder

HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"

async def main():
    connection = HubConnectionBuilder()\
        .with_url(HUB_URL)\
        .configure_logging(logging.WARNING)\
        .build()

    sessions = []
    done = asyncio.Event()

    def on_sessions(args):
        nonlocal sessions
        # args is [ [pid1, pid2, ...] ]
        if args:
            sessions = args[0]
        done.set()

    connection.on("ReceiveAiSessions", on_sessions)
    connection.start()

    # Wait for connection
    await asyncio.sleep(2)
    connection.send("Authenticate", [API_KEY])
    await asyncio.sleep(1)
    
    print("Requesting AI sessions...")
    connection.send("GetAiSessions", [])
    
    try:
        await asyncio.wait_for(done.wait(), timeout=10)
        print(f"Active AI Session PIDs: {sessions}")
    except asyncio.TimeoutError:
        print("Timeout waiting for session list.")

    connection.stop()

if __name__ == "__main__":
    asyncio.run(main())
