"""Verify launcher and explicit screen intents stay alive without clearing device logs or app data."""
import argparse
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "OmniSync.Android"))
import connectadb


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device")
    args = parser.parse_args()
    devices = connectadb.list_devices()
    device = args.device or (devices[0] if len(devices) == 1 else None)
    if not device:
        raise RuntimeError("Specify a connected Android device")

    def adb(*arguments):
        return subprocess.check_output([connectadb.ADB, "-s", device, *arguments], text=True, encoding="utf-8", errors="replace", timeout=30)

    before = adb("logcat", "-b", "crash", "-d", "-v", "epoch")
    for screen in (None, "ALARM", "DASHBOARD"):
        command = ["shell", "am", "start", "-W", "-n", "com.omni.sync/.MainActivity"]
        if screen:
            command += ["--es", "OPEN_SCREEN", screen]
        adb(*command)
        time.sleep(3)
        pid = adb("shell", "pidof", "com.omni.sync").strip()
        if not pid:
            raise AssertionError(f"App exited after opening {screen or 'launcher'}")
        print(f"PASS: {screen or 'launcher'} stays alive (PID {pid})")
    time.sleep(12)
    after = adb("logcat", "-b", "crash", "-d", "-v", "epoch")
    new_lines = [line for line in after.splitlines() if line not in before.splitlines()]
    if any("Process: com.omni.sync" in line for line in new_lines):
        raise AssertionError("New Android crash:\n" + "\n".join(new_lines))
    adb("shell", "pidof", "com.omni.sync")
    print("PASS: no new Omni crashes during startup and intent checks")


if __name__ == "__main__":
    main()
