import subprocess
import time
import threading
import sys
import os

def run_pipe_poc():
    """
    Demonstrates sending commands to a process and receiving output back
    using redirected standard streams. This is the most robust way for 
    OmniHub to interact with a shell programmatically.
    """
    print("=== Phase 1: Redirected I/O PoC (Direct Send/Receive) ===")
    
    # Start cmd.exe with pipes for stdin, stdout, and stderr
    # shell=False to avoid an extra cmd process
    process = subprocess.Popen(
        ['cmd.exe'],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1 # Line buffered
    )

    def output_reader(pipe, label):
        for line in iter(pipe.readline, ''):
            print(f"[{label}] {line.strip()}")
        pipe.close()

    # Start threads to read stdout and stderr in real-time
    stdout_thread = threading.Thread(target=output_reader, args=(process.stdout, "STDOUT"))
    stderr_thread = threading.Thread(target=output_reader, args=(process.stderr, "STDERR"))
    stdout_thread.daemon = True
    stderr_thread.daemon = True
    stdout_thread.start()
    stderr_thread.start()

    # Send a command
    command = "ping google.com -n 3"
    print(f"Sending command: {command}")
    process.stdin.write(command + "\n")
    process.stdin.flush()

    # Wait for output to flow
    time.sleep(5)

    print("Sending 'exit' to close the shell...")
    process.stdin.write("exit\n")
    process.stdin.flush()
    
    process.wait(timeout=2)
    print("Phase 1 Complete.\n")

def run_window_poc():
    """
    Demonstrates spawning a visible terminal window.
    Capturing output from a visible window on Windows is typically done 
    via ConPTY or by attaching to the console buffer.
    """
    print("=== Phase 2: Visible Terminal Window PoC ===")
    
    # CREATE_NEW_CONSOLE = 0x00000010
    print("Spawning a visible cmd.exe window...")
    title = f"OmniHub_Terminal_PoC_{int(time.time())}"
    proc = subprocess.Popen(['cmd.exe', '/K', f'title {title} && echo Hello from OmniHub! && echo I am a visible window.'], creationflags=0x00000010)
    
    print(f"Visible window spawned with PID: {proc.pid}")
    print("To capture output from this window while it is visible, OmniHub would use:")
    print("1. ConPTY (Windows 10/11) to create a pseudo-terminal.")
    print("2. AttachConsole API to read the screen buffer directly.")
    
    time.sleep(3)
    # We won't kill it so the user can see it
    print("Phase 2 Complete. (Window left open for inspection)\n")

if __name__ == "__main__":
    print("OmniHub Terminal Interaction Proof of Concept")
    print("---------------------------------------------")
    
    # Run the functional PoC (Send/Receive)
    run_pipe_poc()
    
    # Run the visual PoC (Window)
    run_window_poc()
    
    print("PoC Finished.")
