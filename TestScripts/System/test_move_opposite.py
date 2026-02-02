import subprocess
import time
import os

def test_move_opposite(target):
    print(f"Testing move opposite for target: {target}")
    script_path = os.path.abspath("TestScripts/System/move_window_opposite.py")
    cmd = f"python \"{script_path}\" \"{target}\""
    print(f"Running: {cmd}")
    result = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    print("STDOUT:", result.stdout)
    print("STDERR:", result.stderr)

if __name__ == "__main__":
    # Test with firefox if it's running
    test_move_opposite("firefox")
    time.sleep(1)
    # Move it back
    test_move_opposite("firefox")
