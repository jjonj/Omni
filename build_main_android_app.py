import os
import shutil
import time
import subprocess
import glob

def build_and_copy_apk():
    """
    Builds the main OmniSync Android application, waits for 2 seconds, and copies the generated APK
    to the root directory of the Omni project.
    """
    script_dir = os.path.dirname(os.path.abspath(__file__))
    android_project_dir = os.path.abspath(os.path.join(script_dir, "OmniSync.Android"))
    apk_dir = os.path.join(android_project_dir, "app", "build", "outputs", "apk", "debug")
    omni_root_dir = os.path.abspath(os.path.join(script_dir))

    print(f"Script directory: {script_dir}")
    print(f"Android project directory: {android_project_dir}")
    print(f"APK directory: {apk_dir}")
    print(f"Omni root directory: {omni_root_dir}")

    # 1. Build the Android app
    build_command = [os.path.join(android_project_dir, "gradlew"), "clean", "assembleDebug"]
    print(f"Running build command: {' '.join(build_command)} in {android_project_dir}")
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
        print(f"Error: gradlew.bat not found in {android_project_dir}. Make sure you are in the correct directory.")
        return


    # 2. Wait for 2 seconds
    print("Waiting for 2 seconds...")
    time.sleep(2)

    # 3. Find the generated APK
    apk_path = os.path.join(apk_dir, "app-debug.apk")
    if not os.path.exists(apk_path):
        print(f"'{apk_path}' not found. Searching for any APK in the debug directory...")
        apk_files = glob.glob(os.path.join(apk_dir, "*.apk"))
        if not apk_files:
            print(f"Error: No APK file found in {apk_dir}")
            return
        apk_path = apk_files[0]

    # 4. Copy the APK to the Omni root directory
    apk_filename = "OmniSync.Android-debug.apk" # Renaming to avoid conflict with PoC APK
    destination_path = os.path.join(omni_root_dir, apk_filename)
    print(f"Copying '{os.path.basename(apk_path)}' to '{destination_path}'...")
    try:
        shutil.copy(apk_path, destination_path)
        print("APK copied successfully!")
    except FileNotFoundError:
        print(f"Error: APK file not found at '{apk_path}'")
    except Exception as e:
        print(f"An error occurred during file copy: {e}")

if __name__ == "__main__":
    build_and_copy_apk()
