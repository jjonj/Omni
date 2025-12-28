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
    # Try connecting to default IP first
    default_target = f"{PHONE_IP}:5555"
    print(f"Attempting to connect to {default_target}...")
    run(["adb", "connect", default_target], check=False)

    devices = list_devices()
    if devices:
        # If we connected to the default target, return it
        if default_target in devices:
            print(f"Connected: {default_target}")
            return default_target
        # Otherwise return the first available device
        print(f"Already connected: {devices[0]}")
        return devices[0]

    print(f"Could not connect to {default_target}. Falling back to manual pairing.")
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
