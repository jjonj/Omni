import os
import json

def test_serialization():
    appdata = os.environ.get('APPDATA')
    settings_path = os.path.join(appdata, 'OmniSync', 'settings.json')
    
    if not os.path.exists(settings_path):
        print(f"Error: {settings_path} not found")
        return

    with open(settings_path, 'r') as f:
        settings = json.load(f)

    # Add sample project
    sample_project = {
        "Id": "00000000-0000-0000-0000-000000000001",
        "Name": "Test Project",
        "HotkeyName": "Test Hotkey",
        "Actions": [
            {
                "Type": 0, # OpenFolder
                "Path": "C:\\Windows",
                "Arguments": "",
                "Layout": {
                    "UseRatio": True,
                    "RatioX": 0.0,
                    "RatioY": 0.0,
                    "RatioWidth": 0.5,
                    "RatioHeight": 1.0
                }
            },
            {
                "Type": 1, # RunProgram
                "Path": "notepad.exe",
                "Arguments": "test.txt",
                "Layout": None
            }
        ]
    }

    if "Projects" not in settings:
        settings["Projects"] = []
    
    # Replace if exists, else append
    existing = next((p for p in settings["Projects"] if p["Id"] == sample_project["Id"]), None)
    if existing:
        settings["Projects"].remove(existing)
    
    settings["Projects"].append(sample_project)

    # Backup original
    with open(settings_path + '.bak', 'w') as f:
        json.dump(settings, f, indent=2)
    
    # Write new
    with open(settings_path, 'w') as f:
        json.dump(settings, f, indent=2)
    
    print("Successfully updated settings.json with a sample project.")

if __name__ == "__main__":
    test_serialization()
