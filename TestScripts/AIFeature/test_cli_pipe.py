import subprocess
import time
import os
import sys
import json
import win32file
import win32pipe
import pywintypes

GEMINI_DIR = r"D:\SSDProjects\Tools\gemini-cli"
BUNDLE_PATH = os.path.join(GEMINI_DIR, "bundle", "gemini.js")
WORKSPACE = r"D:\SSDProjects"
STDOUT_LOG = r"D:\SSDProjects\Omni\cli_stdout.log"
DEBUG_LOG = r"D:\SSDProjects\Omni\gemini_cli_debug.log"

def test_pipe():
    print(f"Launching CLI from {GEMINI_DIR}...")
    
    # Launch CLI
    env = os.environ.copy()
    env["GEMINI_DEBUG_LOG_FILE"] = DEBUG_LOG
    
    with open(STDOUT_LOG, "w") as out:
        # We need to set cwd to GEMINI_DIR so it finds assets if needed
        # Use shell=True to handle node in path if needed, but shell=False is cleaner for PID
        proc = subprocess.Popen(
            ["node", BUNDLE_PATH, "--workspace", WORKSPACE, "--yolo"],
            cwd=GEMINI_DIR,
            stdout=out,
            stderr=subprocess.STDOUT,
            shell=False,
            env=env
        )
    
    print(f"CLI launched. PID: {proc.pid}")
    pipe_name = f"\\\\.\\pipe\\gemini-cli-{proc.pid}"
    
    print(f"Waiting for pipe {pipe_name}...")
    
    handle = None
    start_wait = time.time()
    while time.time() - start_wait < 30:
        try:
            handle = win32file.CreateFile(
                pipe_name,
                win32file.GENERIC_READ | win32file.GENERIC_WRITE,
                0,
                None,
                win32file.OPEN_EXISTING,
                0,
                None
            )
            print("Connected to pipe!")
            break
        except pywintypes.error as e:
            if e.args[0] == 2: # File not found
                time.sleep(0.5)
                continue
            else:
                print(f"Connection error: {e}")
                break
                
    if not handle:
        print("Failed to connect to pipe.")
        proc.kill()
        return

    try:
        # Wait for "ready" handshake
        print("Waiting for CLI to be ready (handshake)...")
        ready = False
        start_wait = time.time()
        while time.time() - start_wait < 60:
            _, avail, _ = win32pipe.PeekNamedPipe(handle, 0)
            if avail > 0:
                err, data = win32file.ReadFile(handle, avail)
                text = data.decode('utf-8')
                print(f"INITIAL READ: {text}")
                if '"dialogType":"ready"' in text:
                    print("CLI is READY (handshake received).")
                    ready = True
                    break
                if '"dialogType":"auth_in_progress"' in text:
                    print("CLI is in auth. Still waiting...")
            time.sleep(1)

        if not ready:
            print("Timed out waiting for handshake.")
            return

        print("Sending prompt...")
        msg = json.dumps({"command": "prompt", "text": "Hello from python. Respond briefly."}) + "\n"
        win32file.WriteFile(handle, msg.encode('utf-8'))
        print("Sent. Reading response...")
        
        # Read with timeout
        start_read = time.time()
        while time.time() - start_read < 60:
            try:
                _, avail, _ = win32pipe.PeekNamedPipe(handle, 0)
                if avail > 0:
                    err, data = win32file.ReadFile(handle, avail)
                    text = data.decode('utf-8')
                    print(f"Received: {text}")
                    if '"type":"response"' in text:
                        print("Success! Got response.")
                        if '[TURN_FINISHED]' in text:
                            break
                else:
                    time.sleep(1)
            except pywintypes.error as e:
                print(f"Read error: {e}")
                break
    except Exception as e:
        print(f"Error during test: {e}")
    finally:
        win32file.CloseHandle(handle)
        
    print("Terminating CLI...")
    proc.kill()

if __name__ == "__main__":
    test_pipe()
