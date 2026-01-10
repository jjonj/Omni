#!/usr/bin/env python3
import asyncio
import os
import sys
import socket
import logging
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"
HUB_PORT = 5000

# Setup Root Directory
ROOT_DIR = os.path.dirname(os.path.abspath(__file__))
if "TestScripts" in ROOT_DIR:
    ROOT_DIR = os.path.abspath(os.path.join(ROOT_DIR, "..", ".."))

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("Phase1HubTest")

class Phase1Tester:
    def __init__(self):
        self.connection_started = False
        self.hub = None
        self.presets = []
        self.presets_received = asyncio.Event()

    def on_open(self):
        logger.info("SignalR Connection opened.")
        self.connection_started = True

    def on_receive_presets(self, args):
        self.presets = args[0]
        logger.info(f"Presets received: {self.presets}")
        self.presets_received.set()

    async def run_test(self):
        self.hub = HubConnectionBuilder()\
            .with_url(HUB_URL)\
            .configure_logging(logging.WARNING)\
            .build()

        self.hub.on("ReceiveAiPresets", self.on_receive_presets)
        self.hub.on_open(self.on_open)
        self.hub.on_error(lambda data: logger.error(f"SignalR Error: {data}"))

        self.hub.start()

        # 1. Connect and Authenticate
        for _ in range(10):
            if self.connection_started: break
            await asyncio.sleep(1)
        
        if not self.connection_started:
            logger.error("Failed to connect to Hub.")
            return 1

        self.hub.send("Authenticate", [API_KEY])
        await asyncio.sleep(1)

        # 2. Test Presets
        logger.info("Testing GetAiPresets...")
        self.presets_received.clear()
        self.hub.send("GetAiPresets", [])
        await asyncio.wait_for(self.presets_received.wait(), timeout=10)
        
        initial_count = len(self.presets)
        test_preset = "TEST_PRESET_" + str(os.getpid())
        
        logger.info(f"Testing AddAiPreset: {test_preset}")
        self.presets_received.clear()
        self.hub.send("AddAiPreset", [test_preset])
        await asyncio.wait_for(self.presets_received.wait(), timeout=10)
        if test_preset not in self.presets:
            logger.error("Failed to add preset.")
            return 1

        logger.info(f"Testing RemoveAiPreset: {test_preset}")
        self.presets_received.clear()
        self.hub.send("RemoveAiPreset", [test_preset])
        await asyncio.wait_for(self.presets_received.wait(), timeout=10)
        if test_preset in self.presets:
            logger.error("Failed to remove preset.")
            return 1
        
        if len(self.presets) != initial_count:
            logger.error(f"Preset count mismatch. Expected {initial_count}, got {len(self.presets)}")
            return 1

        # 3. Test ReloadAiSessions (Debug Report)
        report_file = os.path.join(ROOT_DIR, "OMNI_SESSION_DEBUG_REPORT.LOG")
        if os.path.exists(report_file):
            os.remove(report_file)

        logger.info("Testing ReloadAiSessions...")
        # Send some dummy android sessions
        dummy_sessions = [{"pid": 1234, "name": "Dummy", "startTime": "2026-01-10T10:00:00Z", "workspace": "C:/Temp"}]
        self.hub.send("ReloadAiSessions", [dummy_sessions])
        
        # Wait for file to be created
        for _ in range(10):
            if os.path.exists(report_file): break
            await asyncio.sleep(1)
        
        if not os.path.exists(report_file):
            logger.error("ReloadAiSessions failed to create debug report.")
            return 1
        logger.info("Debug report created successfully.")

        # 4. Test File Operations
        test_src = os.path.join(ROOT_DIR, "TestScripts", "phase1_test_src.txt")
        test_dest = os.path.join(ROOT_DIR, "TestScripts", "phase1_test_dest.txt")
        with open(test_src, "w") as f: f.write("Phase 1 Hub Test Content")
        
        if os.path.exists(test_dest): os.remove(test_dest)

        logger.info("Testing CopyFile via Hub...")
        # RpcApiHub.CopyFile returns bool
        # Since send() is fire-and-forget for SignalR-core client, we might need a callback if it was a Task<bool>
        # But here it's just a method on the Hub. 
        # Actually, in HubConnectionBuilder we use 'send' for void and 'invoke' for return values if supported.
        # Python signalrcore doesn't have a direct 'invoke' that waits for result easily in the base version.
        # But we can check if file appears.
        self.hub.send("CopyFile", [test_src, test_dest])
        
        for _ in range(5):
            if os.path.exists(test_dest): break
            await asyncio.sleep(1)
        
        if not os.path.exists(test_dest):
            logger.error("CopyFile failed.")
            return 1
        logger.info("CopyFile success.")

        # Move test
        test_move_dest = os.path.join(ROOT_DIR, "TestScripts", "phase1_test_moved.txt")
        if os.path.exists(test_move_dest): os.remove(test_move_dest)
        
        logger.info("Testing MoveFile via Hub...")
        self.hub.send("MoveFile", [test_dest, test_move_dest])
        
        for _ in range(5):
            if os.path.exists(test_move_dest) and not os.path.exists(test_dest): break
            await asyncio.sleep(1)
        
        if not os.path.exists(test_move_dest):
            logger.error("MoveFile failed.")
            return 1
        logger.info("MoveFile success.")

        # Cleanup test files
        if os.path.exists(test_src): os.remove(test_src)
        if os.path.exists(test_move_dest): os.remove(test_move_dest)

        self.hub.stop()
        return 0

async def main():
    if not is_port_in_use(HUB_PORT):
        print(f"!! Hub is not running on port {HUB_PORT}. Please run build_run_omnihub.py first.")
        sys.exit(1)

    tester = Phase1Tester()
    exit_code = await tester.run_test()
    
    if exit_code == 0:
        print("\nPHASE 1 HUB VERIFICATION: SUCCESS")
    else:
        print("\nPHASE 1 HUB VERIFICATION: FAILED")
    
    sys.exit(exit_code)

def is_port_in_use(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        return s.connect_ex(('localhost', port)) == 0

if __name__ == "__main__":
    asyncio.run(main())
