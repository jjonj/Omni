import os
import json
import time
import subprocess
import datetime

# Path to the startup state file (relative to executable)
# In dev, it might be in the bin folder or AppData.
# HubSettingsService uses AppData/OmniSync/settings.json
# Spec says startup_state.json. Let's assume it's in the same folder as settings.json or executable.
# Actually, HubSettingsService uses:
# Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "OmniSync");

APPDATA_PATH = os.path.join(os.environ['APPDATA'], 'OmniSync')
STARTUP_STATE_PATH = os.path.join(APPDATA_PATH, 'startup_state.json')

def test_startup_routine_logic():
    print("Testing Startup Routine Logic...")
    
    # 1. Ensure Hub is stopped
    subprocess.run("taskkill /IM OmniSync.Hub.exe /F", shell=True, capture_output=True)
    
    # 2. Clear or set old date in startup_state.json
    if not os.path.exists(APPDATA_PATH):
        os.makedirs(APPDATA_PATH)
        
    yesterday = datetime.datetime.now() - datetime.timedelta(days=1)
    state = {"LastRun": yesterday.isoformat()}
    with open(STARTUP_STATE_PATH, 'w') as f:
        json.dump(state, f)
    
    print(f"Set last run to yesterday: {yesterday.isoformat()}")

    # 3. Start Hub
    # Note: Hub starts in background
    subprocess.Popen(["python", "build_run_omnihub.py"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    
    # Wait for Hub to start and run routines
    print("Waiting for Hub to run routines (up to 30s)...")
    for _ in range(30):
        time.sleep(1)
        if os.path.exists(STARTUP_STATE_PATH):
            with open(STARTUP_STATE_PATH, 'r') as f:
                new_state = json.load(f)
            last_run_str = new_state.get("LastRun")
            if last_run_str:
                last_run = datetime.datetime.fromisoformat(last_run_str)
                if last_run.date() == datetime.datetime.now().date():
                    print(f"SUCCESS: startup_state.json was updated to today at second {_ + 1}.")
                    return
    
    print("FAIL: startup_state.json was not updated to today within 30s.")

if __name__ == "__main__":
    test_startup_routine_logic()
