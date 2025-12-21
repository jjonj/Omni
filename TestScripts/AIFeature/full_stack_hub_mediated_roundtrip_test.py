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
    print(f"Killing processes matching: {name}, avoiding ancestors...")
    my_pid = os.getpid()
    ancestor_pids = set()
    try:
        curr = psutil.Process(my_pid)
        while curr:
            ancestor_pids.add(curr.pid)
            curr = curr.parent()
    except: pass

    for proc in psutil.process_iter(['pid', 'name', 'cmdline']):
        try:
            if proc.info['pid'] in ancestor_pids:
                continue

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

def cleanup_all_gemini_windows():
    print("[0/5] Thoroughly cleaning up all Gemini windows...")
    cleanup_script = os.path.join(ROOT_DIR, "TestScripts", "AIFeature", "cleanup_gemini_windows.py")
    subprocess.run([sys.executable, cleanup_script], cwd=ROOT_DIR)

def main():
    print("\n" + "="*60)
    print("      OMNISYNC: FULL AI INTEGRATION REGRESSION TEST")
    print("="*60 + "\n")

    # 0. Thorough cleanup
    cleanup_all_gemini_windows()

    # 1. Check OmniSync Hub status...
    print("[1/5] Checking OmniSync Hub status...")
    if not is_port_in_use(HUB_PORT):
        print("!! Hub is not running on port 5000. Please run run_omnihub.py first.")
        return
    print("  - Hub is ONLINE.")

    # 2. Cleanup existing AI components
    print("[2/5] Cleaning up stale AI components...")
    kill_process_by_name("ai_listener.py")
    time.sleep(1)

    # 3. Launch Gemini CLI (interactive)
    print("[3/5] Launching Gemini CLI...")
    run_script("launch_gemini_cli.py")
    time.sleep(3)

    # 4. Launch AI Listener
    print("[4/5] Launching AI Listener...")
    run_script("launch_ai_listener.py")
    print("  - Waiting 8s for Listener to discover pipe and authenticate...")
    time.sleep(8)

    # 5. Run Integration Test
    print("[5/5] Running SignalR Roundtrip Test...")
    print("-"*60)
    # Note: path is relative to ROOT_DIR
    test_path = os.path.join("TestScripts", "AIFeature", "test_hub_mediated_roundtrip.py")
    result = subprocess.run([sys.executable, test_path], cwd=ROOT_DIR)
    print("-"*60)
    
    if result.returncode == 0:
        print("\nOVERALL STATUS: SUCCESS")
    else:
        print(f"\nOVERALL STATUS: FAILED (Exit Code: {result.returncode})")

    print("\n" + "="*60)
    print("TEST COMPLETE. Gemini CLI and Listener consoles remain open.")
    print("="*60)

if __name__ == "__main__":
    main()
