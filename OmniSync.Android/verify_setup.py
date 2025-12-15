#!/usr/bin/env python3
"""
Quick verification script to check OmniSync Android setup.
Verifies that all required tools and files are present.
"""

import subprocess
import sys
from pathlib import Path
import os

# ADB Configuration - Update this if ADB is in a different location
ADB_PATH = r"E:\SDKS\AndroidSDK\platform-tools"
# Add ADB to PATH if not already there
if ADB_PATH not in os.environ.get("PATH", ""):
    os.environ["PATH"] = f"{ADB_PATH};{os.environ.get('PATH', '')}"

def check(description, cmd=None, file_path=None):
    """Check if a command exists or file exists."""
    try:
        if cmd:
            result = subprocess.run(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                check=False
            )
            if result.returncode == 0:
                print(f"[OK] {description}")
                return True
            else:
                print(f"[FAIL] {description}")
                return False
        elif file_path:
            if Path(file_path).exists():
                print(f"[OK] {description}")
                return True
            else:
                print(f"[FAIL] {description}")
                return False
    except Exception as e:
        print(f"[FAIL] {description} - {e}")
        return False

def main():
    print("=" * 60)
    print("OmniSync Android - Setup Verification")
    print("=" * 60)
    print()
    
    all_ok = True
    
    # Check tools
    print("Checking Tools:")
    all_ok &= check("Python 3", cmd=["python", "--version"])
    all_ok &= check("ADB (Android Debug Bridge)", cmd=["adb", "version"])
    all_ok &= check("Java JDK", cmd=["java", "-version"])
    
    print()
    print("Checking Project Files:")
    all_ok &= check("Gradle wrapper", file_path="gradlew.bat" if sys.platform == "win32" else "gradlew")
    all_ok &= check("build.gradle.kts", file_path="build.gradle.kts")
    all_ok &= check("DashboardScreen.kt", file_path="app/src/main/java/com/omni/sync/ui/screen/DashboardScreen.kt")
    all_ok &= check("MainActivity.kt", file_path="app/src/main/java/com/omni/sync/MainActivity.kt")
    all_ok &= check("SignalRClient.kt", file_path="app/src/main/java/com/omni/sync/data/repository/SignalRClient.kt")
    
    print()
    print("Checking Scripts:")
    all_ok &= check("build_and_deploy.py", file_path="build_and_deploy.py")
    all_ok &= check("extractcrash.py", file_path="extractcrash.py")
    
    print()
    print("Checking Devices:")
    
    try:
        result = subprocess.run(
            ["adb", "devices"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            check=False
        )
        
        devices = []
        for line in result.stdout.splitlines():
            if "\tdevice" in line:
                devices.append(line.split()[0])
        
        if devices:
            print(f"[OK] Found {len(devices)} device(s):")
            for device in devices:
                print(f"  - {device}")
        else:
            print("[FAIL] No devices connected")
            print("  Run: python build_and_deploy.py")
            print("  The script will guide you through device setup")
            all_ok = False
    except FileNotFoundError:
        print("[FAIL] Cannot check devices - ADB not found")
        all_ok = False
    
    print()
    print("=" * 60)
    
    if all_ok:
        print("[OK] All checks passed!")
        print()
        print("Ready to build and deploy:")
        print("  python build_and_deploy.py")
    else:
        print("[FAIL] Some checks failed")
        print()
        print("Installation guides:")
        print("  - Python: https://www.python.org/downloads/")
        print("  - ADB: Install Android SDK Platform Tools")
        print("  - Java JDK 17+: https://adoptium.net/")
    
    print("=" * 60)
    
    return 0 if all_ok else 1

if __name__ == "__main__":
    sys.exit(main())
