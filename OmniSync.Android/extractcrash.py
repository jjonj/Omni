import subprocess
import sys
import time
from datetime import datetime

PACKAGE = "com.omni.sync"
DEFAULT_DEVICE = "10.0.0.236:41391"
LOG_TIMEOUT_SECONDS = 5


def run(cmd, check=True):
    return subprocess.run(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=check
    )


def adb(args, device=None, check=True):
    cmd = ["adb"]
    if device:
        cmd += ["-s", device]
    cmd += args
    return run(cmd, check=check)


def list_devices():
    out = run(["adb", "devices"]).stdout
    return [l.split()[0] for l in out.splitlines() if "\tdevice" in l]


def ensure_device():
    devices = list_devices()
    if devices:
        print(f"Using device: {devices[0]}")
        return devices[0]

    print("No connected devices.")
    ip = input("Phone IP: ").strip()
    pairing_port = input("Pairing port: ").strip()
    pairing_code = input("Pairing code: ").strip()

    p = subprocess.Popen(
        ["adb", "pair", f"{ip}:{pairing_port}"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )
    p.communicate(pairing_code + "\n")

    connect_port = input("Connect port: ").strip()
    run(["adb", "connect", f"{ip}:{connect_port}"], check=True)

    devices = list_devices()
    if not devices:
        sys.exit("No device after pairing.")

    return devices[0]

def capture_crash(device):
    print(f"Clearing logcat on {device}...")
    adb(["logcat", "-c"], device=device)

    print(f"Launching {PACKAGE}...")
    adb(
        ["shell", "monkey", "-p", PACKAGE, "-c",
         "android.intent.category.LAUNCHER", "1"],
        device=device,
        check=False
    )

    # Allow app to start
    time.sleep(1)

    print("Monitoring for crashes...")

    # We use -v threadtime to get PIDs and timestamps. 
    # We use *:E to capture ALL errors, ensuring we don't miss part of the stack trace.
    proc = subprocess.Popen(
        ["adb", "-s", device, "logcat", "-v", "threadtime", "*:E"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding='utf-8',
        errors='replace'
    )

    # We will read until we find a crash, then read a bit longer, then stop.
    max_duration = time.time() + LOG_TIMEOUT_SECONDS
    crash_detected_time = None
    lines = []

    while True:
        # Check for overall timeout if no crash found yet
        if crash_detected_time is None and time.time() > max_duration:
            break
        
        # If crash was found, give it 1 second to flush the full stack trace then stop
        if crash_detected_time and time.time() > crash_detected_time + 1.0:
            break

        # Non-blocking read line hack (or just rely on line buffering)
        line = proc.stdout.readline()
        
        if not line:
            # If process ended or stream closed
            break
            
        lines.append(line)

        # Detect the start of a crash
        if "FATAL EXCEPTION" in line and crash_detected_time is None:
            print("Crash detected! Capturing stack trace...")
            crash_detected_time = time.time()

    proc.terminate()

    # Filter logic: We want the block around the crash
    crash_report = []
    
    # If we found a crash, let's try to grab the relevant chunk
    if crash_detected_time:
        recording = False
        # We look for the "FATAL" line and grab everything after it
        # (and maybe a few lines of context before it)
        for i, line in enumerate(lines):
            if "FATAL EXCEPTION" in line:
                recording = True
                # Add a few lines of context before the crash if available
                start_context = max(0, i - 5)
                crash_report.extend(lines[start_context:i])
            
            if recording:
                crash_report.append(line)
    else:
        # If no crash detected, maybe dump the whole error log or just say so
        pass

    filename = f"crash.txt"

    with open(filename, "w", encoding="utf-8") as f:
        if crash_report:
            f.writelines(crash_report)
            print(f"Fatal crash captured and saved to: {filename}")
        else:
            f.write("No fatal crash detected in the timeframe.\n")
            f.write("-" * 20 + "\n")
            f.writelines(lines) # Write all errors captured just in case
            print(f"No crash detected. Full error log saved to: {filename}")


def main():
    device = ensure_device()
    capture_crash(device)


if __name__ == "__main__":
    main()
