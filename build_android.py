import os
import shutil
import time
import subprocess
import glob
import sys

def build_and_copy_apk(build_type="debug"):
    """
    Builds the Android application and copies the generated APK to the root directory.
    
    Args:
        build_type: "debug" or "release" (default: "debug")
    """
    script_dir = os.path.dirname(os.path.abspath(__file__))
    android_project_dir = os.path.abspath(os.path.join(script_dir, "OmniSync.Android"))
    apk_dir = os.path.join(android_project_dir, "app", "build", "outputs", "apk", build_type)
    omni_root_dir = os.path.abspath(os.path.join(script_dir))

    print(f"Script directory: {script_dir}")
    print(f"Android project directory: {android_project_dir}")
    print(f"Build type: {build_type}")
    print(f"APK directory: {apk_dir}")
    print(f"Omni root directory: {omni_root_dir}")

    # Determine the gradle wrapper based on OS
    if os.name == 'nt':  # Windows
        gradlew = os.path.join(android_project_dir, "gradlew.bat")
    else:  # Unix-like (Linux, macOS)
        gradlew = os.path.join(android_project_dir, "gradlew")
    
    if not os.path.exists(gradlew):
        print(f"Error: Gradle wrapper not found at {gradlew}")
        return

    # Build command based on build type
    if build_type == "debug":
        gradle_task = "assembleDebug"
        expected_apk = "app-debug.apk"
        output_apk = "OmniSync.Android-debug.apk"
    else:  # release
        gradle_task = "assembleRelease"
        expected_apk = "app-release.apk"
        output_apk = "OmniSync.Android-release.apk"

    # 1. Build the Android app
    build_command = [gradlew, "clean", gradle_task]
    print(f"\nRunning build command: {' '.join(build_command)}")
    print(f"Working directory: {android_project_dir}\n")
    
    try:
        process = subprocess.run(
            build_command,
            cwd=android_project_dir,
            check=True,
            capture_output=True,
            text=True
        )
        print("Build successful!")
        print(process.stdout)
    except subprocess.CalledProcessError as e:
        print(f"Error during build: {e}")
        print(e.stdout)
        print(e.stderr)
        return
    except FileNotFoundError:
        print(f"Error: Gradle wrapper not found at {gradlew}")
        return

    # 2. Wait for 2 seconds
    print("\nWaiting for 2 seconds...")
    time.sleep(2)

    # 3. Find the generated APK
    apk_path = os.path.join(apk_dir, expected_apk)
    if not os.path.exists(apk_path):
        print(f"'{apk_path}' not found. Searching for any APK in the {build_type} directory...")
        apk_files = glob.glob(os.path.join(apk_dir, "*.apk"))
        if not apk_files:
            print(f"Error: No APK file found in {apk_dir}")
            return
        apk_path = apk_files[0]
        print(f"Found: {apk_path}")

    # 4. Copy the APK to the Omni root directory
    destination_path = os.path.join(omni_root_dir, output_apk)
    print(f"\nCopying '{os.path.basename(apk_path)}' to '{destination_path}'...")
    try:
        shutil.copy(apk_path, destination_path)
        print(f"APK copied successfully to: {destination_path}")
    except FileNotFoundError:
        print(f"Error: APK file not found at '{apk_path}'")
    except Exception as e:
        print(f"An error occurred during file copy: {e}")

if __name__ == "__main__":
    # Check if build type is specified in command line arguments
    build_type = "debug"  # Default to debug
    
    if len(sys.argv) > 1:
        arg = sys.argv[1].lower()
        if arg in ["release", "r"]:
            build_type = "release"
        elif arg in ["debug", "d"]:
            build_type = "debug"
        else:
            print(f"Unknown build type: {arg}")
            print("Usage: python build_android.py [debug|release]")
            print("Defaulting to debug build...")
    
    print(f"\n{'='*60}")
    print(f"Building Android APK ({build_type.upper()})")
    print(f"{'='*60}\n")
    
    build_and_copy_apk(build_type)
