import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time


PROJECT_DIR = Path(__file__).resolve().parent
LAST_TARGET_PATH = PROJECT_DIR / "last_adb_target.txt"
ADB_SERVICE_PATTERN = re.compile(
    r"^\s*(?P<name>\S+)\s+(?P<service>_adb\S*\._tcp\.?)\s+(?P<endpoint>\S+:\d+)\s*$"
)


def find_adb() -> str:
    candidates = []
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        sdk_root = os.environ.get(variable)
        if sdk_root:
            candidates.append(Path(sdk_root) / "platform-tools" / "adb.exe")
            candidates.append(Path(sdk_root) / "platform-tools" / "adb")
    candidates.extend(
        [
            Path(r"E:\SDKS\AndroidSDK\platform-tools\adb.exe"),
            Path(r"E:\SDKS\AndroidSDK\platform-tools\adb"),
        ]
    )

    adb_on_path = shutil.which("adb")
    if adb_on_path:
        return adb_on_path
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    raise FileNotFoundError(
        "adb was not found. Set ANDROID_SDK_ROOT or add Android SDK platform-tools to PATH."
    )


ADB = find_adb()


def run(args, check=True, input_text=None, timeout=30):
    result = subprocess.run(
        [ADB, *args],
        input=input_text,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
        timeout=timeout,
    )
    if check and result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or "unknown adb error"
        raise RuntimeError(f"adb {' '.join(args)} failed: {detail}")
    return result


def list_devices():
    result = run(["devices"], check=False)
    return [
        line.split()[0]
        for line in result.stdout.splitlines()
        if len(line.split()) >= 2 and line.split()[1] == "device"
    ]


def is_responsive(target):
    result = run(["-s", target, "shell", "echo", "1"], check=False, timeout=8)
    return result.returncode == 0 and result.stdout.strip() == "1"


def responsive_devices():
    return [target for target in list_devices() if is_responsive(target)]


def parse_mdns_services(output):
    services = []
    for line in output.splitlines():
        match = ADB_SERVICE_PATTERN.match(line)
        if match:
            services.append(match.groupdict())
    return services


def discover_mdns_services(service_fragment=None):
    result = run(["mdns", "services"], check=False)
    services = parse_mdns_services(result.stdout)
    if service_fragment:
        services = [entry for entry in services if service_fragment in entry["service"]]
    return services


def wait_for_mdns_services(service_fragment, timeout_seconds=8):
    deadline = time.monotonic() + timeout_seconds
    while True:
        services = discover_mdns_services(service_fragment)
        if services or time.monotonic() >= deadline:
            return services
        time.sleep(0.5)


def read_last_target():
    try:
        value = LAST_TARGET_PATH.read_text(encoding="utf-8").strip()
        return value or None
    except OSError:
        return None


def save_last_target(target):
    LAST_TARGET_PATH.write_text(target, encoding="utf-8")


def endpoint_host(endpoint):
    if endpoint.startswith("["):
        return endpoint.rsplit("]:", 1)[0] + "]"
    return endpoint.rsplit(":", 1)[0]


def resolve_endpoint(value, services, fallback_endpoints=()):
    value = (value or "").strip()
    if value and not value.isdigit():
        return value

    if value:
        matching = [
            entry["endpoint"]
            for entry in services
            if entry["endpoint"].rsplit(":", 1)[-1] == value
        ]
        if matching:
            return matching[0]

    if not value and services:
        return services[0]["endpoint"]

    if value:
        hosts = []
        for endpoint in fallback_endpoints:
            if endpoint:
                host = endpoint_host(endpoint)
                if host not in hosts:
                    hosts.append(host)
        if len(hosts) == 1:
            return f"{hosts[0]}:{value}"
    return None


def choose_connected_device(preferred=None):
    devices = responsive_devices()
    if preferred and preferred in devices:
        return preferred
    if len(devices) == 1:
        return devices[0]
    if len(devices) > 1:
        last_target = read_last_target()
        if last_target in devices:
            return last_target
        joined = ", ".join(devices)
        raise RuntimeError(f"Multiple Android devices are connected ({joined}); use --device.")
    return None


def try_connect(endpoint):
    if not endpoint:
        return None
    print(f"Connecting to {endpoint}...")
    result = run(["connect", endpoint], check=False)
    if result.returncode != 0 or "failed" in (result.stdout + result.stderr).lower():
        return None
    time.sleep(0.4)
    target = choose_connected_device(endpoint)
    if target:
        save_last_target(target)
    return target


def connect(
    pairing_code=None,
    pairing_endpoint=None,
    connect_endpoint=None,
    specified_device=None,
    interactive=True,
):
    run(["start-server"], check=False)

    if specified_device:
        if specified_device in responsive_devices():
            return specified_device
        target = try_connect(specified_device)
        if target:
            return target
        raise RuntimeError(f"Could not connect to requested device {specified_device}.")

    target = choose_connected_device()
    if target:
        print(f"Already connected to responsive device: {target}")
        return target

    connect_services = wait_for_mdns_services("_adb-tls-connect", timeout_seconds=3)
    for service in connect_services:
        target = try_connect(service["endpoint"])
        if target:
            print(f"Connected through mDNS: {target}")
            return target

    last_target = read_last_target()
    target = try_connect(last_target)
    if target:
        print(f"Reconnected to last known device: {target}")
        return target

    if not pairing_code and interactive:
        print("No paired wireless device was discovered.")
        pairing_code = input("Pairing code (blank to cancel): ").strip()
        if pairing_code:
            pairing_endpoint = input(
                "Pairing IP:port or pairing port (blank for mDNS): "
            ).strip()
            connect_endpoint = input(
                "Connect IP:port or connect port (blank for mDNS): "
            ).strip()

    if not pairing_code:
        raise RuntimeError(
            "No device was found. Enable Wireless debugging, then pair once with "
            "--pair-code or the app's WIFI DBG dialog."
        )
    if not re.fullmatch(r"\d{6}", pairing_code):
        raise ValueError("The wireless debugging pairing code must contain six digits.")

    pairing_services = wait_for_mdns_services("_adb-tls-pairing", timeout_seconds=10)
    fallback_endpoints = [
        *(entry["endpoint"] for entry in connect_services),
        last_target,
    ]
    resolved_pairing_endpoint = resolve_endpoint(
        pairing_endpoint, pairing_services, fallback_endpoints
    )
    if not resolved_pairing_endpoint:
        raise RuntimeError(
            "The pairing service was not discoverable. Supply its full IP:port, or check "
            "that the PC and phone can exchange mDNS traffic."
        )

    print(f"Pairing with {resolved_pairing_endpoint}...")
    pair_result = run(
        ["pair", resolved_pairing_endpoint],
        check=False,
        input_text=pairing_code + "\n",
        timeout=30,
    )
    pair_output = (pair_result.stdout + pair_result.stderr).strip()
    if pair_result.returncode != 0 or "failed" in pair_output.lower():
        raise RuntimeError(f"Pairing failed: {pair_output or 'unknown adb error'}")
    print(pair_output or "Pairing succeeded.")

    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        target = choose_connected_device()
        if target:
            save_last_target(target)
            print(f"Connected after pairing: {target}")
            return target

        connect_services = discover_mdns_services("_adb-tls-connect")
        resolved_connect_endpoint = resolve_endpoint(
            connect_endpoint,
            connect_services,
            [resolved_pairing_endpoint, last_target],
        )
        target = try_connect(resolved_connect_endpoint)
        if target:
            print(f"Connected after pairing: {target}")
            return target
        time.sleep(0.5)

    raise RuntimeError(
        "Pairing succeeded, but the ADB connection service did not become reachable."
    )


def print_diagnostics():
    for args in (["version"], ["server-status"], ["mdns", "check"], ["mdns", "services"]):
        result = run(list(args), check=False)
        print(f"$ adb {' '.join(args)}")
        print((result.stdout + result.stderr).strip())


def main(argv=None):
    parser = argparse.ArgumentParser(description="Discover, pair, and connect wireless ADB.")
    parser.add_argument("legacy", nargs="*", help=argparse.SUPPRESS)
    parser.add_argument("--pair-code")
    parser.add_argument("--pair-endpoint", help="Pairing IP:port or just the port")
    parser.add_argument("--connect-endpoint", help="Connection IP:port or just the port")
    parser.add_argument("--device", help="Specific connected serial or IP:port")
    parser.add_argument("--non-interactive", action="store_true")
    parser.add_argument("--diagnose", action="store_true")
    args = parser.parse_args(argv)

    if args.diagnose:
        print_diagnostics()
        return 0

    pairing_code = args.pair_code
    pairing_endpoint = args.pair_endpoint
    connect_endpoint = args.connect_endpoint
    if args.legacy:
        if len(args.legacy) != 3:
            parser.error("legacy usage requires: pairing_code pairing_port connect_port")
        pairing_code, pairing_endpoint, connect_endpoint = args.legacy

    try:
        target = connect(
            pairing_code=pairing_code,
            pairing_endpoint=pairing_endpoint,
            connect_endpoint=connect_endpoint,
            specified_device=args.device,
            interactive=not args.non_interactive,
        )
        print(f"ADB ready: {target}")
        return 0
    except (FileNotFoundError, RuntimeError, ValueError) as error:
        print(f"ADB connection failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
