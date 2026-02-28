import subprocess
import sys
import os

# === ثابت values ===
PHONE_IP = "192.168.0.236"

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
    # 1. Check if any device is already connected
    devices = list_devices()
    if devices:
        # Check if the established device is still actually responsive
        target = devices[0]
        try:
            # Simple check if device responds
            run(["adb", "-s", target, "shell", "echo", "1"], check=True)
            print(f"Already connected to responsive device: {target}")
            return target
        except:
            print(f"Device {target} is listed but not responsive. Disconnecting...")
            run(["adb", "disconnect", target], check=False)

    # 2. Try last known successful connection from file
    config_path = os.path.join(os.path.dirname(__file__), "last_adb_target.txt")
    if os.path.exists(config_path):
        with open(config_path, "r") as f:
            last_target = f.read().strip()
            if last_target and last_target != f"{PHONE_IP}:5555": # Skip 5555 as we try it next
                print(f"Attempting to reconnect to last known target: {last_target}...")
                run(["adb", "connect", last_target], check=False)
                devices = list_devices()
                if last_target in devices:
                    print(f"Reconnected to {last_target}")
                    return last_target

    # 3. Try connecting to default IP (port 5555)
    default_target = f"{PHONE_IP}:5555"
    print(f"Attempting to connect to {default_target}...")
    run(["adb", "connect", default_target], check=False)

    devices = list_devices()
    if devices:
        if default_target in devices:
            print(f"Connected: {default_target}")
            with open(config_path, "w") as f: f.write(default_target)
            return default_target
        return devices[0]

    print(f"Could not connect to {default_target}. Falling back to manual pairing.")
    pairing_port = input("Pairing port: ").strip()
    pairing_code = input("Pairing code: ").strip()
    connect_port = input("Connect port: ").strip()

    target = f"{PHONE_IP}:{connect_port}"
    print(f"Pairing with {PHONE_IP}:{pairing_port}...")
    run(
        ["adb", "pair", f"{PHONE_IP}:{pairing_port}"],
        input_text=pairing_code + "\n"
    )

    print(f"Connecting to {target}...")
    run(["adb", "connect", target])

    devices = list_devices()
    if not devices:
        sys.exit("Connection failed.")

    print(f"Connected: {devices[0]}")
    # Save the successful target
    with open(config_path, "w") as f: f.write(devices[0])
    return devices[0]


if __name__ == "__main__":
    connect()
