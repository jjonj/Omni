import os
import json
import psutil
import asyncio
import time
import subprocess
import sys
import socket

# --- CONFIGURATION ---
HUB_PORT = 5000
API_KEY = "test_api_key"
GEMINI_DIR = r"D:\\SSDProjects\\Tools\\gemini-cli"
ROOT_DIR = os.path.dirname(os.path.abspath(__file__))
if "TestScripts" in ROOT_DIR:
    ROOT_DIR = os.path.abspath(os.path.join(ROOT_DIR, "..", ".."))

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

def launch_gemini_instance(index):
    """Launches a single Gemini CLI instance in a new console."""
    title = f"OMNI_GEMINI_MULTI_{index}"
    cmd = f'title {title} && cd /d {GEMINI_DIR} && node bundle/gemini.js'
    print(f"Launching instance {index} with title: {title}")
    return subprocess.Popen(
        ['cmd.exe', '/K', cmd],
        creationflags=subprocess.CREATE_NEW_CONSOLE,
        close_fds=True
    )

def get_gemini_pids():
    """Finds all PIDs of the Gemini CLI bundle processes."""
    pids = []
    for proc in psutil.process_iter(['pid', 'name', 'cmdline']):
        try:
            cmdline = " ".join(proc.info['cmdline'] or [])
            if 'node' in proc.info['name'].lower() and 'gemini' in cmdline.lower():
                if 'bundle/gemini.js' in cmdline.replace('\\', '/'):
                    pids.append(proc.info['pid'])
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            pass
    return pids

def cleanup_all_gemini_windows():
    print("[0/5] Thoroughly cleaning up all Gemini windows...")
    cleanup_script = os.path.join(ROOT_DIR, "TestScripts", "AIFeature", "cleanup_gemini_windows.py")
    subprocess.run([sys.executable, cleanup_script], cwd=ROOT_DIR)

async def run_hub_multi_test(num_instances=2):
    print("\n" + "="*60)
    print("      OMNISYNC: FULL HUB-MEDIATED MULTI-INSTANCE TEST")
    print("="*60 + "\n")

    # 0. Cleanup
    cleanup_all_gemini_windows()
    kill_process_by_name("ai_listener.py")

    # 1. Check Hub
    if not is_port_in_use(HUB_PORT):
        print("!! Hub is not running on port 5000. Please run run_omnihub.py first.")
        return

    # 2. Launch Gemini Instances
    popens = []
    for i in range(num_instances):
        p = launch_gemini_instance(i + 1)
        popens.append(p)
    
    print("\nWaiting for instances to initialize...")
    await asyncio.sleep(5) 

    # 3. Discover PIDs of the specific instances we launched
    pids = []
    for p in popens:
        try:
            parent = psutil.Process(p.pid)
            for child in parent.children(recursive=True):
                if 'node' in child.name().lower():
                    cmdline = " ".join(child.cmdline())
                    if 'gemini' in cmdline.lower():
                        pids.append(child.pid)
                        break
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            pass

    print(f"Discovered {len(pids)} PIDs from launched instances: {pids}")
    
    if len(pids) < num_instances:
        print(f"Warning: Only found {len(pids)} specific child PIDs, falling back to general search...")
        pids = get_gemini_pids()
        print(f"General search found: {pids}")

    # 4. Launch a single Listener
    print("\nLaunching a single AI Listener...")
    subprocess.Popen(
        [sys.executable, os.path.join(ROOT_DIR, "launch_ai_listener.py")],
        cwd=ROOT_DIR
    )
    
    print("Waiting 10s for Listener to initialize and authenticate...")
    await asyncio.sleep(10)

    # 5. Run Integration Test
    print("\nRunning SignalR Multi-Response Test...")
    print("-"*60)
    test_path = os.path.join(ROOT_DIR, "TestScripts", "AIFeature", "test_hub_mediated_multi_cli.py")
    
    cmd = [sys.executable, test_path]
    if pids:
        cmd.extend(["--pids"] + [str(pid) for pid in pids])
        
    result = subprocess.run(cmd, cwd=ROOT_DIR)
    print("-"*60)
    
    if result.returncode == 0:
        print("\nOVERALL STATUS: SUCCESS")
    else:
        print(f"\nOVERALL STATUS: FAILED (Exit Code: {result.returncode})")

    print("\n" + "="*60)
    print("TEST COMPLETE. Gemini CLI and Listener consoles remain open.")
    print("="*60)

if __name__ == "__main__":
    asyncio.run(run_hub_multi_test(2))
