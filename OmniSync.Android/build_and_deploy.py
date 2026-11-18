#!/usr/bin/env python3
"""
Build and Deploy Script for OmniSync Android App

This script builds the Android APK using Gradle and deploys it to a connected
Android device using ADB. It can also optionally launch the app after installation.

Usage:
    python build_and_deploy.py [options]

Options:
    --clean         Clean build (removes build cache)
    --release       Build release variant (default is debug)
    --no-install    Build only, skip installation
    --no-launch     Install only, skip launching
    --device IP:PORT  Specify device (default: auto-detect or use saved)
"""

import subprocess
import sys
import os
import time
import argparse
import hashlib
import json
import threading
import queue
import socket
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
import connectadb

# Configuration
PACKAGE = "com.omni.sync"
PROJECT_DIR = Path(__file__).parent
APK_DEBUG = PROJECT_DIR / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
APK_RELEASE = PROJECT_DIR / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
HUB_CONFIG = Path(os.environ.get("OMNIHUB_AUTH_CONFIG") or
                  Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "OmniHub" / "config" / "appsettings.json")

def run_command(cmd, check=True, capture_output=True):
    """Run a shell command and return the result."""
    print(f"$ {' '.join(cmd)}")
    is_windows_batch = sys.platform == "win32" and cmd[0].endswith(".bat")
    result = subprocess.run(
        cmd,
        stdout=subprocess.PIPE if capture_output else None,
        stderr=subprocess.PIPE if capture_output else None,
        text=True,
        check=check,
        cwd=PROJECT_DIR,
        shell=is_windows_batch # Use shell=True for .bat files on Windows
    )
    return result


def adb(args, device=None, check=True):
    """Run an ADB command."""
    cmd = [connectadb.ADB]
    if device:
        cmd += ["-s", device]
    cmd += args
    return run_command(cmd, check=check)

def ensure_device(specified_device=None, pairing_code=None, pairing_endpoint=None, connect_endpoint=None):
    """Ensure a device is connected, either specified or auto-detected."""
    return connectadb.connect(
        pairing_code=pairing_code,
        pairing_endpoint=pairing_endpoint,
        connect_endpoint=connect_endpoint,
        specified_device=specified_device,
        interactive=sys.stdin.isatty(),
    )


def publish_update_metadata(apk_path):
    digest = hashlib.sha256()
    with apk_path.open("rb") as apk_file:
        for block in iter(lambda: apk_file.read(1024 * 1024), b""):
            digest.update(block)
    metadata_path = apk_path.with_suffix(".update.json")
    if not metadata_path.exists():
        raise RuntimeError(f"Android build did not publish update metadata: {metadata_path}")
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    if metadata.get("sha256") != digest.hexdigest():
        raise RuntimeError("Android build published metadata for a different APK.")
    print(f"  Published update metadata: {metadata_path}")

def get_local_ipv4():
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("8.8.8.8", 80))
        return probe.getsockname()[0]
    finally:
        probe.close()


def request_json(url, api_key, method="GET", body=None, timeout=15):
    encoded_body = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(url, data=encoded_body, method=method)
    request.add_header("X-Omni-Api-Key", api_key)
    if encoded_body is not None:
        request.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def deploy_via_hub(explicit_public_base_url=None):
    print("\n=== Deploying through Rust OmniHub ===")
    if not HUB_CONFIG.exists():
        print(f"[FAIL] Rust OmniHub configuration was not found at {HUB_CONFIG}")
        return False

    settings = json.loads(HUB_CONFIG.read_text(encoding="utf-8"))
    api_key = settings.get("AuthApiKey")
    listen_url = settings.get("SignalR", {}).get("Url", "http://0.0.0.0:5000")
    if not api_key:
        print("[FAIL] AuthApiKey is missing from the Hub configuration.")
        return False

    parsed_listen_url = urllib.parse.urlparse(listen_url)
    port = parsed_listen_url.port or 5000
    controller_base_url = f"http://127.0.0.1:{port}"
    public_base_url = explicit_public_base_url or f"http://{get_local_ipv4()}:{port}"
    deploy_url = f"{controller_base_url}/api/android-update/deploy"
    print(f"Requesting Hub deployment; phone download base: {public_base_url}")

    try:
        response = request_json(
            deploy_url,
            api_key,
            method="POST",
            body={"publicBaseUrl": public_base_url},
            timeout=75,
        )
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        print(f"[FAIL] Hub rejected deployment: HTTP {error.code} {detail}")
        return False
    except Exception as error:
        print(f"[FAIL] Could not reach the Hub deployment API: {error}")
        return False

    request_id = response.get("requestId")
    mode = response.get("mode", "unknown")
    status = response.get("status", "dispatched")
    print(f"Hub deployment {request_id}: mode={mode}, status={status}")
    print(response.get("message", "Deployment command dispatched."))

    if mode == "browser":
        print("[PENDING] APK download sent to phone; waiting for user to update OmniSync. Installation is not confirmed.")
        return True
    if not request_id:
        print("[FAIL] Hub did not return a deployment request ID.")
        return False

    terminal_statuses = {
        "current",
        "permission_required",
        "confirmation_required",
        "installed",
        "waiting_for_user_update",
        "error",
        "busy",
    }
    last_status = None
    installer_started_at = None
    status_url = f"{controller_base_url}/api/android-update/deploy/{request_id}"
    # Installer commit is not installation. A verification rejection may arrive
    # just afterward; report pending user action instead of a failed deploy.
    while True:
        try:
            status_response = request_json(status_url, api_key)
        except Exception as error:
            print(f"[WARN] Could not read deployment status: {error}")
            time.sleep(1)
            continue

        current_status = status_response.get("status", "unknown")
        status_message = status_response.get("message", "")
        if current_status != last_status:
            print(f"Android deployment status: {current_status} - {status_message}")
            last_status = current_status

        if current_status == "waiting_for_user_update" or (
            current_status == "error" and "INSTALL_FAILED_VERIFICATION_FAILURE" in status_message
        ):
            print("[PENDING] Waiting for user to update OmniSync on the phone; installation is not confirmed.")
            return True
        if current_status == "installer_started":
            if installer_started_at is None:
                installer_started_at = time.monotonic()
            if time.monotonic() - installer_started_at >= 6:
                print("[PENDING] Waiting for user to update OmniSync on the phone; installation is not confirmed.")
                return True

        if current_status in terminal_statuses:
            if current_status in {"error", "busy"}:
                print(f"[FAIL] Android update failed: {status_message}")
                return False
            if current_status in {"permission_required", "confirmation_required"}:
                print("[PENDING] Waiting for user to approve the update on the phone; installation is not confirmed.")
                return True
            if current_status == "installed":
                print("[OK] Hub-to-Android deployment installed successfully.")
                return True
            print("[OK] The phone already has this build.")
            return True

        time.sleep(1)

def build_apk(clean=False, release=False):
    """Build the Android APK."""
    print("\n=== Building APK ===")
    
    # Use gradlew.bat on Windows, gradlew on Unix
    gradle_cmd = ["gradlew.bat"] if sys.platform == "win32" else ["./gradlew"]
    
    if clean:
        print("Cleaning project...")
        run_command(gradle_cmd + ["clean"], capture_output=False)
    
    variant = "Release" if release else "Debug"
    print(f"Building {variant} APK...")
    
    build_task = f"assemble{variant}"
    result = run_command(gradle_cmd + [build_task], capture_output=False, check=False)
    
    if result.returncode != 0:
        print(f"\n[FAIL] Build failed with exit code {result.returncode}")
        sys.exit(1)
    
    apk_path = APK_RELEASE if release else APK_DEBUG
    
    if not apk_path.exists():
        print(f"\n[FAIL] APK not found at {apk_path}")
        sys.exit(1)
    
    print(f"\n[OK] Build successful!")
    print(f"  APK: {apk_path}")
    print(f"  Size: {apk_path.stat().st_size / 1024 / 1024:.2f} MB")
    publish_update_metadata(apk_path)
    
    return apk_path

def install_apk(device, apk_path):
    """Install APK on the device."""
    print(f"\n=== Installing APK ===")
    print(f"Installing {apk_path.name} to {device}...")
    
    result = adb(["install", "-r", str(apk_path)], device=device, check=False)
    
    if result.returncode != 0:
        print(f"\n[FAIL] Installation failed")
        print(result.stderr if result.stderr else "Unknown error")
        return False
    
    print(f"[OK] Installation successful!")
    return True

def launch_app(device):
    """Launch the app on the device."""
    print(f"\n=== Launching App ===")
    
    # Stop the app first if it's running
    print("Stopping existing app instance...")
    adb(["shell", "am", "force-stop", PACKAGE], device=device, check=False)
    time.sleep(0.5)
    
    print(f"Launching {PACKAGE}...")
    result = adb(
        ["shell", "monkey", "-p", PACKAGE,
         "-c", "android.intent.category.LAUNCHER", "1"],
        device=device,
        check=False
    )
    
    if result.returncode != 0:
        print(f"[FAIL] Failed to launch app")
        return False
    
    print(f"[OK] App launched successfully!")
    return True

def main():
    parser = argparse.ArgumentParser(
        description="Build and deploy OmniSync Android app",
        formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--clean", action="store_true", help="Clean build")
    parser.add_argument("--release", action="store_true", help="Build release variant")
    parser.add_argument("--no-install", action="store_true", help="Skip installation")
    parser.add_argument("--no-launch", action="store_true", help="Skip launching")
    parser.add_argument("--device", help="Specify device IP:PORT")
    parser.add_argument("--pair-code", help="Six-digit wireless debugging pairing code")
    parser.add_argument("--pair-endpoint", help="Pairing IP:port, or just the port")
    parser.add_argument("--connect-endpoint", help="Connection IP:port, or just the port")
    parser.add_argument("--hub-deploy", action="store_true", help="Deploy through the connected Hub client without ADB")
    parser.add_argument("--hub-base-url", help="LAN URL the phone should use for the Hub, such as http://192.168.1.37:5000")
    
    args = parser.parse_args()
    
    print("=" * 60)
    print("OmniSync Android - Build & Deploy")
    print("=" * 60)
    
    # Build the APK
    apk_path = build_apk(clean=args.clean, release=args.release)
    
    if args.no_install:
        print("\n[OK] Build complete (installation skipped)")
        return

    if args.hub_deploy:
        if deploy_via_hub(args.hub_base_url):
            return
        sys.exit(1)
    
    # Ensure device is connected with timeout
    print("\n=== Connecting to Device ===")
    print("Waiting for device connection (5 minute timeout)...")
    device_queue = queue.Queue()
    
    def connect_worker():
        try:
            print("Connection worker started...")
            dev = ensure_device(
                args.device,
                pairing_code=args.pair_code,
                pairing_endpoint=args.pair_endpoint,
                connect_endpoint=args.connect_endpoint,
            )
            print(f"Connection worker finished. Result: {dev}")
            device_queue.put(dev)
        except SystemExit as e:
            print(f"Connection worker exited with SystemExit: {e}")
            device_queue.put(None)
        except Exception as e:
            print(f"\nConnection error in worker: {e}")
            device_queue.put(None)

    connect_thread = threading.Thread(target=connect_worker, daemon=True)
    connect_thread.start()
    
    try:
        device = device_queue.get(timeout=300)
    except queue.Empty:
        print("\n[TIMEOUT] Device connection timed out after 5 minutes.")
        print("Falling back to Hub-to-Android deployment.")
        if deploy_via_hub(args.hub_base_url):
            return
        sys.exit(1)

    if not device:
        print("\nADB could not connect; falling back to Hub-to-Android deployment.")
        if deploy_via_hub(args.hub_base_url):
            return
        print("\n[FAIL] Neither ADB nor Hub deployment could reach the device.")
        sys.exit(1)
    
    # Install the APK
    if not install_apk(device, apk_path):
        print("ADB installation failed; falling back to Hub-to-Android deployment.")
        if deploy_via_hub(args.hub_base_url):
            return
        sys.exit(1)
    
    if args.no_launch:
        print("\n[OK] Installation complete (launch skipped)")
        return
    
    # Launch the app
    launch_app(device)
    
    print("\n" + "=" * 60)
    print("[OK] All done!")
    print("=" * 60)


if __name__ == "__main__":
    main()
