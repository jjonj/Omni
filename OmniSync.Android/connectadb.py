import subprocess
import sys
import os

# === ثابت values ===
PHONE_IP = "10.0.0.236"

ADB_PATH = r"E:\SDKS\AndroidSDK\platform-tools"

# Ensure adb is on PATH
if ADB_PATH not in os.environ.get("PATH", ""):
    os.environ["PATH"] = f"{ADB_PATH};{os.environ.get('PATH', '')}"


def run(cmd, check=True, input_text=None):
    return subprocess.run(
        cmd,
        input=input_text,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=check
    )


def list_devices():
    out = run(["adb", "devices"], check=False).stdout
    return [l.split()[0] for l in out.splitlines() if "\tdevice" in l]


def connect():
    devices = list_devices()
    if devices:
        print(f"Already connected: {devices[0]}")
        return devices[0]

    pairing_port = input("Pairing port: ").strip()
    pairing_code = input("Pairing code: ").strip()
    connect_port = input("Connect port: ").strip()  # Prompt for connect port

    print("Pairing...")
    run(
        ["adb", "pair", f"{PHONE_IP}:{pairing_port}"],
        input_text=pairing_code + "\n"
    )

    print("Connecting...")
    run(["adb", "connect", f"{PHONE_IP}:{connect_port}"])

    devices = list_devices()
    if not devices:
        sys.exit("Connection failed.")

    print(f"Connected: {devices[0]}")
    return devices[0]


if __name__ == "__main__":
    connect()
