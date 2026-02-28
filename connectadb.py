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


def main():
    # 1. Check if any device is already connected
    devices = list_devices()
    if devices:
        target = devices[0]
        try:
            run(["adb", "-s", target, "shell", "echo", "1"], check=True)
            print(f"Already connected to responsive device: {target}")
            return
        except:
            print(f"Device {target} is listed but not responsive. Disconnecting...")
            run(["adb", "disconnect", target], check=False)

    config_path = os.path.join(os.path.dirname(__file__), "last_adb_target.txt")
    
    if len(sys.argv) >= 4:
        pairing_code = sys.argv[1]
        pairing_port = sys.argv[2]
        connect_port = sys.argv[3]
    else:
        # Check for last target if no args provided
        if os.path.exists(config_path):
            with open(config_path, "r") as f:
                last_target = f.read().strip()
                if last_target:
                    print(f"Attempting to reconnect to last known target: {last_target}...")
                    run(["adb", "connect", last_target], check=False)
                    if last_target in list_devices():
                        print(f"Reconnected to {last_target}")
                        return

        pairing_port = input("Pairing port: ").strip()
        pairing_code = input("Pairing code: ").strip()
        connect_port = input("Connect port: ").strip()

    print(f"Pairing with code {pairing_code} at port {pairing_port}...")
    run(
        ["adb", "pair", f"{PHONE_IP}:{pairing_port}"],
        input_text=pairing_code + "\n",
        check=False
    )

    target = f"{PHONE_IP}:{connect_port}"
    print(f"Connecting to port {connect_port}...")
    run(["adb", "connect", target])

    devices = list_devices()
    if not devices:
        sys.exit("Connection failed.")

    print(f"Connected: {devices[0]}")
    # Save the successful target
    with open(config_path, "w") as f: f.write(devices[0])


if __name__ == "__main__":
    main()
