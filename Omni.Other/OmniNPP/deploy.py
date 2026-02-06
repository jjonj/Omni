import os
import shutil
import subprocess
import sys
import ctypes
import time
from pathlib import Path

# Configuration
PLUGIN_NAME = "OmniNPP"
SOURCE_DLL = Path(r"D:\SSDProjects\Omni\Omni.Other\OmniNPP\bin\Release\net9.0-windows\win-x64\publish\OmniNPP.dll")
NPP_PATH = Path(r"C:\Program Files\Notepad++")
DEST_DIR = NPP_PATH / "plugins" / PLUGIN_NAME
DEST_DLL = DEST_DIR / f"{PLUGIN_NAME}.dll"

def is_admin():
    try:
        return ctypes.windll.shell32.IsUserAnAdmin()
    except:
        return False

def kill_notepadpp():
    print("Checking for running Notepad++ instances...")
    try:
        # Use tasklist to check if notepad++.exe is running
        result = subprocess.run(["tasklist", "/FI", "IMAGENAME eq notepad++.exe"], capture_output=True, text=True)
        if "notepad++.exe" in result.stdout:
            print("Closing Notepad++...")
            subprocess.run(["taskkill", "/F", "/IM", "notepad++.exe", "/T"], capture_output=True)
            time.sleep(1) # Give it a second to release files
    except Exception as e:
        print(f"Error checking/closing Notepad++: {e}")

def build():
    print("Building OmniNPP (Native AOT)...")
    try:
        # Sanitize PATH to avoid CMD issues with '&' in paths (like "Scripts & macros")
        # which can break the Native AOT linker invocation.
        env = os.environ.copy()
        path_entries = env.get("PATH", "").split(os.pathsep)
        clean_path = [p for p in path_entries if "&" not in p]
        env["PATH"] = os.pathsep.join(clean_path)

        project_dir = Path(r"D:\SSDProjects\Omni\Omni.Other\OmniNPP")
        # Use dotnet publish for AOT
        subprocess.run(["dotnet", "publish", "-c", "Release", "-r", "win-x64", "--self-contained"], 
                       check=True, cwd=project_dir, env=env)
        print("[OK] Build successful!")
        return True
    except Exception as e:
        print(f"[FAIL] Build failed: {e}")
        return False

def deploy():
    print("=" * 60)
    print(f"{PLUGIN_NAME} - Build & Deploy to Notepad++")
    print("=" * 60)

    # Always build first
    if not build():
        return False

    # Check for admin (required to write to C:\Program Files)
    if not is_admin():
        print("[INFO] Requesting administrator privileges...")
        # Re-run the script with admin privileges
        script = os.path.abspath(__file__)
        params = f'"{script}"'
        ctypes.windll.shell32.ShellExecuteW(None, "runas", sys.executable, params, None, 1)
        return True

    kill_notepadpp()

    # Validate source after build
    source = SOURCE_DLL
    if not source.exists():
        print(f"[FAIL] Source DLL not found at: {SOURCE_DLL}")
        return False

    try:
        if not DEST_DIR.exists():
            print(f"Creating directory: {DEST_DIR}")
            DEST_DIR.mkdir(parents=True, exist_ok=True)

        print(f"Copying {source.name} to {DEST_DIR}...")
        shutil.copy2(source, DEST_DLL)
        
        # Also copy pdb if it exists
        source_pdb = source.with_suffix(".pdb")
        if source_pdb.exists():
            print(f"Copying {source_pdb.name}...")
            shutil.copy2(source_pdb, DEST_DIR / f"{PLUGIN_NAME}.pdb")

        print("\n[OK] Deployment successful!")
        
        # Always restart
        npp_exe = NPP_PATH / "notepad++.exe"
        if npp_exe.exists():
            print(f"Restarting Notepad++...")
            subprocess.Popen([str(npp_exe)])
        else:
            print(f"[WARN] Notepad++ executable not found at {npp_exe}")
        
        return True
        
    except PermissionError:
        print("[FAIL] Permission denied. Please ensure Notepad++ is closed and you have write access.")
        return False
    except Exception as e:
        print(f"[FAIL] An error occurred: {e}")
        return False

def main():
    success = deploy()
    
    if not success:
        sys.exit(1)
        
    # Keep window open for a short time to see result if run via double-click
    if "PROMPT" not in os.environ:
        time.sleep(3)

if __name__ == "__main__":
    main()
