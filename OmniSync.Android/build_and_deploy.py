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
from pathlib import Path
import connectadb

# Configuration
PACKAGE = "com.omni.sync"
PROJECT_DIR = Path(__file__).parent
APK_DEBUG = PROJECT_DIR / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
APK_RELEASE = PROJECT_DIR / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"

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
    cmd = ["adb"]
    if device:
        cmd += ["-s", device]
    cmd += args
    return run_command(cmd, check=check)

def ensure_device(specified_device=None):
    """Ensure a device is connected, either specified or auto-detected."""
    if specified_device:
        print(f"Using specified device: {specified_device}")
        devices = connectadb.list_devices()
        if specified_device in devices:
            return specified_device
        else:
            print(f"Specified device {specified_device} not found. Attempting to connect...")
            # We can try to connect to the specified device if it looks like an IP:Port
            if ":" in specified_device:
                 run_command(["adb", "connect", specified_device], check=False)
                 time.sleep(1)
                 if specified_device in connectadb.list_devices():
                     return specified_device
            
            print(f"Could not connect to {specified_device}. Falling back to standard connection...")

    # Use connectadb logic to connect
    return connectadb.connect()

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
    
    return apk_path

def install_apk(device, apk_path):
    """Install APK on the device."""
    print(f"\n=== Installing APK ===")
    print(f"Installing {apk_path.name} to {device}...")
    
    result = adb(["-s", device, "install", "-r", str(apk_path)], check=False)
    
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
    adb(["-s", device, "shell", "am", "force-stop", PACKAGE], check=False)
    time.sleep(0.5)
    
    print(f"Launching {PACKAGE}...")
    result = adb(
        ["-s", device, "shell", "monkey", "-p", PACKAGE, 
         "-c", "android.intent.category.LAUNCHER", "1"],
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
    
    args = parser.parse_args()
    
    print("=" * 60)
    print("OmniSync Android - Build & Deploy")
    print("=" * 60)
    
    # Build the APK
    apk_path = build_apk(clean=args.clean, release=args.release)
    
    if args.no_install:
        print("\n[OK] Build complete (installation skipped)")
        return
    
    # Ensure device is connected
    device = ensure_device(args.device)
    
    # Install the APK
    if not install_apk(device, apk_path):
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