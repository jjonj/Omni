import os
import json
import psutil
import win32file
import win32pipe
import asyncio
import time
import subprocess

GEMINI_DIR = r"D:\SSDProjects\Tools\gemini-cli"

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

def sync_pipe_comm(pid, command_text):
    """Synchronous pipe communication with retries for readiness."""
    pipe_path = f"\\\\.\\pipe\\gemini-cli-{pid}"
    max_retries = 10
    
    for attempt in range(max_retries):
        try:
            # Connect to the named pipe
            handle = win32file.CreateFile(
                pipe_path,
                win32file.GENERIC_READ | win32file.GENERIC_WRITE,
                0,
                None,
                win32file.OPEN_EXISTING,
                0,
                None
            )
            
            payload = json.dumps({"command": "prompt", "text": command_text}) + "\n"
            win32file.WriteFile(handle, payload.encode())
            
            # Read response
            hr, data = win32file.ReadFile(handle, 1024 * 1024)
            full_resp = data.decode().strip()
            win32file.CloseHandle(handle)
            
            if not full_resp:
                return f"[PID {pid}] Error: Empty response."
                
            response_msg = json.loads(full_resp)
            return response_msg.get('text', 'No output detected.')
                
        except Exception as e:
            if attempt < max_retries - 1:
                time.sleep(1.0) # Wait for pipe to be created/ready
                continue
            return f"[PID {pid}] Pipe communication failed after {max_retries} retries: {e}"

async def run_auto_multi_test(num_instances=2):
    # 1. Kill existing instances to start fresh, but avoid ancestors
    my_pid = os.getpid()
    ancestor_pids = set()
    try:
        curr = psutil.Process(my_pid)
        while curr:
            ancestor_pids.add(curr.pid)
            curr = curr.parent()
    except: pass

    existing_pids = get_gemini_pids()
    killed_count = 0
    for pid in existing_pids:
        if pid in ancestor_pids:
            continue
        try:
            psutil.Process(pid).terminate()
            killed_count += 1
        except: pass
    
    if killed_count:
        print(f"Cleaned up {killed_count} existing instances.")
        time.sleep(1)

    # 2. Launch new instances
    for i in range(num_instances):
        launch_gemini_instance(i + 1)
    
    print("\nWaiting for instances to initialize...")
    await asyncio.sleep(5) # Give node time to start and open pipes

    # 3. Discover PIDs
    pids = get_gemini_pids()
    print(f"Discovered {len(pids)} PIDs: {pids}")
    
    if len(pids) < num_instances:
        print(f"Warning: Only found {len(pids)} instances, expected {num_instances}")

    # 4. Communicate
    prompts = [
        f"You are instance #{i+1} (PID {pid}). Please respond with: 'Instance {i+1} reporting for duty from PID {pid}' and then a very short fact about space."
        for i, pid in enumerate(pids)
    ]

    print("\nSending prompts to all instances in parallel...")
    start_time = time.time()
    
    tasks = [asyncio.to_thread(sync_pipe_comm, pid, prompt) for pid, prompt in zip(pids, prompts)]
    results = await asyncio.gather(*tasks)
    
    end_time = time.time()
    
    print(f"\nAll responses received in {end_time - start_time:.2f} seconds:")
    for pid, res in zip(pids, results):
        print(f"\n--- Response from PID {pid} ---")
        print(res)

    # 5. Cleanup
    print("\nCleaning up spawned instances...")
    for pid in pids:
        if pid not in ancestor_pids:
            try:
                psutil.Process(pid).terminate()
            except: pass
    print("Test finished.")

if __name__ == "__main__":
    # Test with 2 instances by default
    asyncio.run(run_auto_multi_test(2))
