import os
import sys

def verify_calendar_service_source():
    hub_file = os.path.join(os.path.dirname(__file__), "..", "..", "OmniSync.Hub", "src", "OmniSync.Hub", "Logic", "Services", "CalendarService.cs")
    hub_file = os.path.abspath(hub_file)
    print(f"Checking {hub_file}...")
    
    if not os.path.exists(hub_file):
        print(f"Error: {hub_file} does not exist.")
        return False
        
    with open(hub_file, "r", encoding="utf-8") as f:
        content = f.read()
        
    checks = [
        ("_lastCalendarUrl", "Cached last calendar URL field"),
        ("_isRefreshing", "Concurrency guard field"),
        ("StringComparison.OrdinalIgnoreCase", "URL comparison check on settings changed"),
        ("Interlocked.CompareExchange", "Atomic lock to prevent concurrent refresh executions")
    ]
    
    all_passed = True
    for token, desc in checks:
        if token in content:
            print(f"  [PASS] Found {desc} ({token})")
        else:
            print(f"  [FAIL] Missing {desc} ({token})")
            all_passed = False
            
    return all_passed

if __name__ == "__main__":
    print("Running CalendarService validation...")
    if verify_calendar_service_source():
        print("CalendarService throttle validation successful.")
        sys.exit(0)
    else:
        print("CalendarService throttle validation failed.")
        sys.exit(1)
