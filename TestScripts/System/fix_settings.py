import json
import os

path = os.path.expandvars(r'%APPDATA%\OmniSync\settings.json')
if os.path.exists(path):
    with open(path, 'r', encoding='utf-8') as f:
        settings = json.load(f)

    # 1. Fix Rider mapping
    if "Rider" in settings.get("ExeMappings", {}):
        settings["ExeMappings"]["Rider"] = settings["ExeMappings"]["Rider"].replace('"', '')

    # 2. Fix Wartribes project
    for project in settings.get("Projects", []):
        if project.get("Name") == "Wartribes":
            for action in project.get("Actions", []):
                # Fix the broken Rider action
                if action.get("Type") == 1 and action.get("Arguments") == "Rider" and action.get("Path") == "C:\\":
                    action["Path"] = "Rider"
                    action["Arguments"] = ""
    
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(settings, f, indent=2)
    print("Settings fixed.")
else:
    print("Settings not found.")

