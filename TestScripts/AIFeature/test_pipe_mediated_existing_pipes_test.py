import os
import json
import psutil
import win32file
import win32pipe
import asyncio
import time

def get_gemini_pids():
    """Finds all PIDs of the Gemini CLI bundle processes."""
    pids = []
    for proc in psutil.process_iter(['pid', 'name', 'cmdline']):
        try:
            cmdline = " ".join(proc.info['cmdline'] or [])
            if 'node' in proc.info['name'].lower() and 'gemini' in cmdline.lower():
                # Target the bundle version specifically
                if 'bundle/gemini.js' in cmdline.replace('\\', '/'):
                    pids.append(proc.info['pid'])
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            pass
    return pids

def sync_pipe_comm(pid, command_text):
    """Synchronous pipe communication for a specific PID."""
    pipe_path = f"\\\\.\\pipe\\gemini-cli-{pid}"
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
        
        # Read response (blocking)
        hr, data = win32file.ReadFile(handle, 1024 * 1024)
        full_resp = data.decode().strip()
        win32file.CloseHandle(handle)
        
        if not full_resp:
            return f"[PID {pid}] Error: Empty response."
            
        response_msg = json.loads(full_resp)
        return response_msg.get('text', 'No output detected.')
            
    except Exception as e:
        return f"[PID {pid}] Pipe communication failed: {e}"

async def run_multi_test():
    pids = get_gemini_pids()
    if not pids:
        print("No Gemini CLI bundle processes found.")
        print("Please launch multiple instances using 'python launch_gemini_cli.py' in separate terminals.")
        return

    print(f"Found {len(pids)} Gemini instances: {pids}")
    
    # Prepare unique prompts for each
    prompts = [
        f"You are instance A (PID {pid}). Please respond with: 'Instance {i+1} reporting for duty from PID {pid}' and then a very short fact about space."
        for i, pid in enumerate(pids)
    ]

    print("\nSending prompts to all instances in parallel...")
    start_time = time.time()
    
    # Run all communications in parallel using threads
    tasks = [asyncio.to_thread(sync_pipe_comm, pid, prompt) for pid, prompt in zip(pids, prompts)]
    results = await asyncio.gather(*tasks)
    
    end_time = time.time()
    
    print(f"\nAll responses received in {end_time - start_time:.2f} seconds:")
    for pid, res in zip(pids, results):
        print(f"\n--- Response from PID {pid} ---")
        print(res)

if __name__ == "__main__":
    asyncio.run(run_multi_test())
