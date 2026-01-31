import json
import time
import sys

# We'll use the omni_cli_script.py to send the command
# The command format for MOVE_WINDOW_OPPOSITE is handled in CommandDispatcher.cs
# { "MOVE_WINDOW_OPPOSITE", payload => { ... } }

def trigger_move(pid):
    command = f"MOVE_WINDOW_OPPOSITE {pid}"
    print(f"Triggering Hub command: {command}")
    
    import subprocess
    # Run the omni_cli_script.py with the command
    result = subprocess.run(["python", "OmniSync.Cli\omni_cli_script.py", command], capture_output=True, text=True)
    print(result.stdout)
    if result.stderr:
        print("ERROR:", result.stderr)

if __name__ == "__main__":
    pid = 38756
    if len(sys.argv) > 1:
        pid = int(sys.argv[1])
    
    for i in range(5):
        print(f"Test {i+1}/5...")
        trigger_move(pid)
        time.sleep(5)
