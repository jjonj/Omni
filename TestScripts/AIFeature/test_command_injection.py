import os
import json
import psutil
import win32file
import win32pipe
import asyncio
import time
import subprocess

GEMINI_DIR = r"D:\\SSDProjects\\Tools\\gemini-cli"
OMNI_DIR = r"D:\\SSDProjects\\Omni"

def kill_gemini_instances():
    """Kills all Gemini CLI node processes, avoiding ancestors."""
    print("Cleaning up existing Gemini instances...")
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

            cmdline = " ".join(proc.info['cmdline'] or [])
            if 'node' in proc.info['name'].lower() and 'gemini' in cmdline.lower():
                if 'bundle/gemini.js' in cmdline.replace('\\', '/'):
                    print(f"Killing PID {proc.info['pid']}")
                    proc.kill()
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            pass
    time.sleep(1)

def get_gemini_pid():
    """Finds the PID of the Gemini CLI bundle process."""
    for proc in psutil.process_iter(['pid', 'name', 'cmdline']):
        try:
            cmdline = " ".join(proc.info['cmdline'] or [])
            if 'node' in proc.info['name'].lower() and 'gemini' in cmdline.lower():
                if 'bundle/gemini.js' in cmdline.replace('\\', '/'):
                    return proc.info['pid']
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            pass
    return None

def launch_gemini():
    """Launches a Gemini CLI instance."""
    kill_gemini_instances()
    cmd = f'title OMNI_COMMAND_TEST && cd /d {GEMINI_DIR} && node bundle/gemini.js'
    print("Launching new Gemini instance...")
    subprocess.Popen(['cmd.exe', '/K', cmd], creationflags=subprocess.CREATE_NEW_CONSOLE)
    
    # Wait for pipe to become available
    for _ in range(20):
        time.sleep(1)
        pid = get_gemini_pid()
        if pid:
            pipe_path = f"\\\\.\\pipe\\gemini-cli-{pid}"
            # Check if pipe exists by trying to open it briefly or using os.path.exists (though pipes are special)
            try:
                handle = win32file.CreateFile(
                    pipe_path, win32file.GENERIC_READ | win32file.GENERIC_WRITE,
                    0, None, win32file.OPEN_EXISTING, 0, None
                )
                win32file.CloseHandle(handle)
                print(f"Gemini ready at PID {pid}")
                return pid
            except:
                continue
    return None

async def send_and_get_response(pid, command_text, timeout=120):
    pipe_path = f"\\\\.\\pipe\\gemini-cli-{pid}"
    print(f"Connecting to {pipe_path} for command: {command_text[:50]}...")
    
    try:
        handle = win32file.CreateFile(
            pipe_path, win32file.GENERIC_READ | win32file.GENERIC_WRITE,
            0, None, win32file.OPEN_EXISTING, 0, None
        )
        
        payload = json.dumps({"command": "prompt", "text": command_text}) + "\n"
        win32file.WriteFile(handle, payload.encode())
        
        print("Command sent. Collecting response parts...")
        
        full_text = ""
        start_time = time.time()
        
        while time.time() - start_time < timeout:
            try:
                # PeekNamedPipe returns (data, total_bytes_avail, bytes_left_this_message)
                _, bytes_avail, _ = win32pipe.PeekNamedPipe(handle, 0)
                if bytes_avail > 0:
                    hr, data = win32file.ReadFile(handle, bytes_avail)
                    chunk = data.decode().strip()
                    if not chunk:
                        continue
                        
                    # Split by newline in case multiple JSON messages arrived together
                    for line in chunk.split('\n'):
                        if not line.strip(): continue
                        try:
                            msg = json.loads(line)
                            if msg.get('type') == 'response':
                                text = msg.get('text', '')
                                if text == '[TURN_FINISHED]':
                                    win32file.CloseHandle(handle)
                                    return full_text.strip() or "[No Output]"
                                elif text == '[Command Handled]':
                                    # For slash commands, we might just get this
                                    # But let's keep waiting for TURN_FINISHED just in case
                                    full_text += "[Command Handled]\n"
                                else:
                                    full_text += text + "\n"
                        except json.JSONDecodeError:
                            continue
                else:
                    await asyncio.sleep(0.2)
            except Exception as e:
                print(f"Read error: {e}")
                break
                
        win32file.CloseHandle(handle)
        return full_text.strip() or "Error: Timeout waiting for [TURN_FINISHED]"
            
    except Exception as e:
        return f"Error: {str(e)}"

async def run_command_test():
    pid = get_gemini_pid() or launch_gemini()
    if not pid:
        print("Failed to find or launch Gemini.")
        return

    # 1. Inject /dir add command
    dir_cmd = f"/dir add {OMNI_DIR}"
    print(f"Injecting command: {dir_cmd}")
    res1 = await send_and_get_response(pid, dir_cmd, timeout=10)
    print(f"Response 1: {res1}")

    # 2. Ask to read tasks.txt
    prompt = "Read tasks.txt inside the directory I just added and report back only the first task in there. Do not implement it."
    print(f"Sending prompt: {prompt}")
    res2 = await send_and_get_response(pid, prompt, timeout=120)
    print(f"\n--- AI Response ---")
    print(res2)

if __name__ == "__main__":
    asyncio.run(run_command_test())
