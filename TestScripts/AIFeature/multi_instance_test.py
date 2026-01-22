#!/usr/bin/env python3
import asyncio
import time
import os
import sys
import socket
import logging
import subprocess
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"
WORKSPACE = r"D:/SSDProjects"
HUB_PORT = 5000

# Setup Root Directory
ROOT_DIR = os.path.dirname(os.path.abspath(__file__))
if "TestScripts" in ROOT_DIR:
    ROOT_DIR = os.path.abspath(os.path.join(ROOT_DIR, "..", ".."))

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("MultiInstanceTest")

class MultiInstanceTester:
    def __init__(self, num_instances=2):
        self.connection_started = False
        self.hub = None
        self.loop = None
        self.num_instances = num_instances
        self.pids = []
        self.new_pids = []
        self.current_test_pid = None
        self.sessions_received_event = asyncio.Event()
        self.response_received_event = asyncio.Event()
        self.new_pid_received_event = asyncio.Event()
        self.captured_responses = []

    def on_open(self):
        logger.info("SignalR Connection opened.")
        self.connection_started = True

    def on_ai_sessions(self, args):
        sessions_data = args[0]
        if isinstance(sessions_data, dict):
            pids = [int(pid) for pid in sessions_data.keys()]
        else:
            pids = sessions_data
        logger.info(f"Received AI Sessions from Hub: {pids}")
        self.pids = pids
        self.sessions_received_event.set()

    def on_ai_response(self, args):
        if len(args) >= 2:
            response, pid = args[0], args[1]
            if str(pid) != str(self.current_test_pid):
                return
            # logger.info(f"AI Response from PID {pid} received.")
            self.captured_responses.append(response)

    def on_ai_status(self, args):
        if len(args) >= 2:
            status, pid = args[0], args[1]
            if str(pid) != str(self.current_test_pid):
                return
            logger.info(f"AI Status (PID {pid}): {status}")
            if status == "FINISHED":
                if self.loop:
                    self.loop.call_soon_threadsafe(self.response_received_event.set)

    def on_new_session_pid(self, args):
        pid = args[0]
        logger.info(f"Received new session PID from Hub: {pid}")
        self.new_pids.append(pid)
        if len(self.new_pids) >= self.num_instances:
            if self.loop:
                self.loop.call_soon_threadsafe(self.new_pid_received_event.set)

    async def run_test(self):
        # 0. Cleanup BEFORE anything else
        cleanup_all_gemini_windows()

        self.loop = asyncio.get_running_loop()
        self.hub = HubConnectionBuilder()\
            .with_url(HUB_URL)
            .configure_logging(logging.WARNING)
            .build()

        self.hub.on_open(self.on_open)
        self.hub.on("ReceiveAiSessions", self.on_ai_sessions)
        self.hub.on("ReceiveAiResponse", self.on_ai_response)
        self.hub.on("ReceiveAiStatus", self.on_ai_status)
        self.hub.on("ReceiveNewAiSessionPid", self.on_new_session_pid)
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

        # 2. Launch Instances via Hub
        logger.info(f"Requesting Hub to launch {self.num_instances} instances...")
        self.new_pids = []
        self.new_pid_received_event.clear()
        
        for i in range(self.num_instances):
            self.hub.send("StartCliAtWorkspace", [WORKSPACE])
            await asyncio.sleep(1)
        
        logger.info(f"Waiting for {self.num_instances} new PIDs from Hub...")
        try:
            await asyncio.wait_for(self.new_pid_received_event.wait(), timeout=45)
        except asyncio.TimeoutError:
            logger.warning(f"Timed out waiting for all {self.num_instances} PIDs. Found {len(self.new_pids)} so far.")

        if not self.new_pids:
            logger.error("No new session PIDs received!")
            self.hub.stop()
            return 1

        # 3. Test each session
        for i, pid in enumerate(self.new_pids):
            instance_id = i + 1
            logger.info(f"--- Testing Session {instance_id} (PID: {pid}) ---")
            self.current_test_pid = pid
            
            # Send targeted prompt
            prompt = f"Multi-instance test. You are Instance {instance_id}. Repeat: 'I am Instance {instance_id}'"
            logger.info(f"Sending prompt to PID {pid}: {prompt}")
            
            self.response_received_event.clear()
            # Pass PID as second argument
            self.hub.send("SendAiMessage", [prompt, pid])
            
            try:
                await asyncio.wait_for(self.response_received_event.wait(), timeout=60)
                logger.info(f"Instance {instance_id} responded successfully.")
            except asyncio.TimeoutError:
                logger.error(f"Timed out waiting for response from Instance {instance_id}")

        self.hub.stop()
        return 0 if len(self.captured_responses) >= len(self.new_pids) else 1

def is_port_in_use(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        return s.connect_ex(('localhost', port)) == 0

def cleanup_all_gemini_windows():
    print("[CLEANUP] Cleaning up all Gemini windows...")
    cleanup_script = os.path.join(ROOT_DIR, "TestScripts", "AIFeature", "cleanup_gemini_windows.py")
    subprocess.run([sys.executable, cleanup_script], cwd=ROOT_DIR)

async def main():
    print("\n" + "="*60)
    print("      OMNISYNC: HUB-MEDIATED MULTI-INSTANCE TEST")
    print("="*60 + "\n")

    if not is_port_in_use(HUB_PORT):
        print(f"!! Hub is not running on port {HUB_PORT}. Please run run_omnihub.py first.")
        sys.exit(1)

    cleanup_all_gemini_windows()
    
    tester = MultiInstanceTester(num_instances=2)
    exit_code = await tester.run_test()
    
    if exit_code == 0:
        print("\nOVERALL STATUS: SUCCESS")
    else:
        print("\nOVERALL STATUS: FAILED")
    
    print("\n" + "="*60)
    sys.exit(exit_code)

if __name__ == "__main__":
    asyncio.run(main())