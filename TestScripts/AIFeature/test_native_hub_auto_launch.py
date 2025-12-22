import sys
import os
import time
import logging

# Add project root to path for imports if needed
sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..")))

from TestScripts.AIFeature.test_hub_mediated_roundtrip import run_test

if __name__ == "__main__":
    message = sys.argv[1] if len(sys.argv) > 1 else "Hello from Native Hub Auto-Launch Test"
    
    print("============================================================")
    print("      NATIVE HUB AI AUTO-LAUNCH TEST")
    print("============================================================")
    print("\n[1/2] Sending prompt via Hub (Expect auto-launch on PC)...")
    
    # We pass use_listener=False if the original script supported it, 
    # but the original script actually doesn't launch the listener itself, 
    # it's the FULL STACK script that does.
    # test_hub_mediated_roundtrip.py just talks to SignalR.
    
    try:
        run_test(message)
    except Exception as e:
        print(f"Test failed: {e}")
        sys.exit(1)
