import pygetwindow as gw
from screeninfo import get_monitors
import time

def debug_move_clis():
    print("--- Monitor Information ---")
    monitors = get_monitors()
    for i, m in enumerate(monitors):
        print(f"Monitor {i}: {m}")
    
    if len(monitors) < 2:
        print("Error: Only one monitor detected by screeninfo.")
        # We will proceed anyway using a hardcoded offset as a fallback
        target_x = 2000
    else:
        # Move to the first non-primary monitor found
        secondary = next((m for m in monitors if not m.is_primary), monitors[0])
        target_x = secondary.x + 100
        target_y = secondary.y + 100
        print(f"Targeting monitor at X={secondary.x}, Y={secondary.y}")

    targets = ['cmd', 'powershell', 'omni_gemini', 'terminal', 'conhost']
    print(f"\n--- Scanning Windows (Targeting: {targets})")
    
    all_windows = gw.getAllWindows()
    found_count = 0
    
    for window in all_windows:
        title = window.title
        if not title: continue
        
        is_target = any(t.lower() in title.lower() for t in targets)
        
        if is_target:
            found_count += 1
            print(f"[{found_count}] Found: '{title}'")
            print(f"    Current Pos: {window.topleft}, Size: {window.size}")
            
            try:
                if window.isMinimized:
                    print("    Restoring minimized window...")
                    window.restore()
                    time.sleep(0.1)
                
                print(f"    Moving to {target_x}, 100...")
                window.moveTo(target_x, 100)
                window.resize(1200, 800)
                print("    Move command sent.")
            except Exception as e:
                print(f"    Failed to move: {e}")

    if found_count == 0:
        print("No matching windows found. Printing first 10 window titles for debug:")
        for w in all_windows[:10]:
            print(f"  - '{w.title}'")

if __name__ == "__main__":
    debug_move_clis()
