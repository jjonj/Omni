import os
import subprocess
import json
import sys
from pathlib import Path

def run_command(command, description):
    print(f"--- {description} ---")
    try:
        result = subprocess.run(command, check=True, capture_output=True, text=True, shell=True)
        print(result.stdout)
        return True
    except subprocess.CalledProcessError as e:
        print(f"Error: {e.stderr}")
        return False

def main():
    project_dir = os.path.dirname(os.path.abspath(__file__))
    os.chdir(project_dir)

    # 1. Build OPC in Release mode
    if not run_command("dotnet build OmniProjectContext.csproj -c Release", "Building OPC Release"):
        print("Build failed. Aborting.")
        return

    # 2. Path to the built executable
    opc_exe = os.path.join(project_dir, "bin", "Release", "net9.0", "opc.exe")
    if not os.path.exists(opc_exe):
        print(f"Executable not found at: {opc_exe}")
        return

    # 3. Locate Gemini Settings
    gemini_settings_path = Path.home() / ".gemini" / "settings.json"
    if not gemini_settings_path.exists():
        print(f"Gemini settings not found at: {gemini_settings_path}")
        return

    print(f"Found Gemini settings at: {gemini_settings_path}")

    # 4. Load and update settings
    with open(gemini_settings_path, 'r', encoding='utf-8') as f:
        settings = json.load(f)

    hooks = {
        "SessionStart": [
            {
                "matcher": "",
                "hooks": [
                    {
                        "type": "command",
                        "command": f"{opc_exe} session"
                    }
                ]
            }
        ],
        "BeforeAgent": [],
        "SessionEnd": [
            {
                "matcher": "",
                "hooks": [
                    {
                        "type": "command",
                        "command": f"{opc_exe} sync"
                    }
                ]
            }
        ]
    }

    settings["hooks"] = hooks

    # 5. Save updated settings
    with open(gemini_settings_path, 'w', encoding='utf-8') as f:
        json.dump(settings, f, indent=2)

    # Add opc alias to PowerShell profile
    print("\n--- Registering PowerShell Alias ---")
    ps_profile_path = os.path.expanduser("~\\Documents\\PowerShell\\Microsoft.PowerShell_profile.ps1")
    profile_dir = os.path.dirname(ps_profile_path)
    if not os.path.exists(profile_dir):
        os.makedirs(profile_dir)
    
    alias_cmd = f'\nfunction opc {{ & "{opc_exe}" $args }}\n'
    try:
        content = ""
        if os.path.exists(ps_profile_path):
            with open(ps_profile_path, "r") as f:
                content = f.read()
        
        if opc_exe not in content:
            with open(ps_profile_path, "a") as f:
                f.write(alias_cmd)
            print(f"Alias 'opc' added to {ps_profile_path}")
        else:
            print("Alias 'opc' already exists in profile.")
    except Exception as e:
        print(f"Failed to add alias: {e}")

    print("\n--- Setup Complete ---")
    print("OPC has been integrated into Gemini CLI hooks.")
    print(f"Executable: {opc_exe}")

if __name__ == "__main__":
    main()
