"""Build/run the real Android task scheduling regression on a connected device."""
import argparse
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / "OmniSync.Android"))
import connectadb

parser = argparse.ArgumentParser()
parser.add_argument("--device")
args = parser.parse_args()
devices = connectadb.list_devices()
device = args.device or (devices[0] if len(devices) == 1 else None)
if not device:
    raise RuntimeError("Specify a connected Android device")
subprocess.run([str(root / "OmniSync.Android/gradlew.bat"), ":app:assembleDebugAndroidTest"], cwd=root / "OmniSync.Android", check=True)
subprocess.run([connectadb.ADB, "-s", device, "install", "-r", str(root / "OmniSync.Android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")], check=True)
result = subprocess.run([connectadb.ADB, "-s", device, "shell", "am", "instrument", "-w", "-e", "class", "com.omni.sync.ReminderDeliveryDeviceTest", "com.omni.sync.test/androidx.test.runner.AndroidJUnitRunner"], capture_output=True, text=True)
print(result.stdout)
print(result.stderr)
if result.returncode != 0 or "OK (1 test)" not in result.stdout:
    raise SystemExit(1)
