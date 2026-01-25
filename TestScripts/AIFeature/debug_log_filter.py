import os

log_file = "hub_log.txt"
search_patterns = ["[AiCliService]", "INIT_DEBUG", "Launch check iteration", "Critical", "Exception"]

if os.path.exists(log_file):
    try:
        with open(log_file, "r", encoding="utf-8", errors="ignore") as f:
            lines = f.readlines()
            # Only look at the last 200 lines
            last_lines = lines[-200:]
            for line in last_lines:
                line_lower = line.lower()
                if any(p.lower() in line_lower for p in search_patterns):
                    print(line.strip())
    except Exception as e:
        print(f"Error reading log file: {e}")
else:
    print(f"Log file {log_file} not found.")
