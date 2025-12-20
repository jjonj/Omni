#!/usr/bin/env python3
import subprocess
import time
import os
import sys
import socket
import psutil

# --- CONFIGURATION ---
HUB_PORT = 5000
API_KEY = "test_api_key"
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT_DIR = os.path.abspath(os.path.join(SCRIPT_DIR, "..", ".."))

def is_port_in_use(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        return s.connect_ex(('localhost', port)) == 0

def kill_process_by_name(name):
    print(f"Killing processes matching: {name}")
    for proc in psutil.process_iter(['pid', 'name', 'cmdline']):
        try:
            if name.lower() in proc.info['name'].lower() or (proc.info['cmdline'] and any(name.lower() in part.lower() for part in proc.info['cmdline'])):
                print(f"  Killing PID {proc.info['pid']}")
                proc.kill()
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            pass

def run_script(path, new_console=False):
    full_path = os.path.join(ROOT_DIR, path)
    print(f"Launching: {path}")
    if new_console:
        return subprocess.Popen(
            [sys.executable, full_path],
            creationflags=subprocess.CREATE_NEW_CONSOLE,
            cwd=ROOT_DIR
        )
    else:
        return subprocess.run([sys.executable, full_path], cwd=ROOT_DIR)

def main():
    print("=== STARTING FULL AI INTEGRATION TEST ===")
    
    # 1. Check Hub
    if not is_port_in_use(HUB_PORT):
        print("Hub is not running on port 5000. Please run run_omnihub.py first.")
        # We don't auto-run hub as it might require rebuild which is slow
        return

    # 2. Cleanup existing AI components
    kill_process_by_name("ai_listener.py")
    # We don't necessarily kill Gemini CLI to preserve history, 
    # but we need to ensure the Listener can find it.

    # 3. Launch Gemini CLI (interactive)
    run_script("launch_gemini_cli.py")
    time.sleep(2)

    # 4. Launch AI Listener
    run_script("launch_ai_listener.py")
    print("Waiting for AI Listener to authenticate...")
    time.sleep(8)

    # 5. Run Integration Test
    print("\n--- Running Integration Test ---")
    # Note: path is relative to ROOT_DIR
    test_path = os.path.join("TestScripts", "AIFeature", "test_ai_integration.py")
    result = subprocess.run([sys.executable, test_path], cwd=ROOT_DIR)
    
    if result.returncode == 0:
        print("\nSUCCESS: AI Integration test passed!")
    else:
        print(f"\nFAILED: AI Integration test exited with code {result.returncode}")

    print("\n=== TEST COMPLETE ===")
    print("Gemini CLI and AI Listener are still running in their consoles.")

if __name__ == "__main__":
    main()
