import pygetwindow as gw
import psutil

print("--- Window Titles ---")
for title in gw.getAllTitles():
    if title.strip():
        print(f"'{title}'")

print("\n--- Node Processes ---")
for proc in psutil.process_iter(['pid', 'name', 'cmdline']):
    if 'node' in proc.info['name'].lower():
        print(f"PID: {proc.info['pid']}, Cmd: {proc.info['cmdline']}")
