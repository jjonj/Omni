import time
import sys
import subprocess

def test_focus(pid):
    print(f"Testing focus for PID {pid}...")
    # RpcApiHub has FocusAiSession(int pid)
    # We can trigger it via omni_cli_script.py (this is a bit indirect but works for testing)
    # Actually, omni_cli_script.py uses ExecuteCommand which maps to Hub methods.
    # Let's use the Hub's custom methods if possible, but MOVE_WINDOW_OPPOSITE is easiest to trigger via ExecuteCommand now.
    
    # Wait, RpcApiHub.FocusAiSession is not in the ParseCommand switch.
    # But RpcApiHub.ExecuteCommand falls back to ProcessService.ExecuteCommand.
    # The Hub doesn't have a direct CLI command for FocusAiSession yet.
    
    # Let's test MOVE_WINDOW_OPPOSITE again since I just generalized its backend.
    subprocess.run(["python", "OmniSync.Cli\omni_cli_script.py", f"MOVE_WINDOW_OPPOSITE {pid}"], capture_output=True)

if __name__ == "__main__":
    pid = 38756
    if len(sys.argv) > 1:
        pid = int(sys.argv[1])
    
    print("Starting Hub integration test...")
    for i in range(3):
        print(f"Toggle {i+1}...")
        test_focus(pid)
        time.sleep(5)
    print("Test complete.")
