import json
import os

path = os.path.expandvars(r'%APPDATA%\OmniSync\settings.json')
if os.path.exists(path):
    with open(path, 'r', encoding='utf-8') as f:
        settings = json.load(f)

    # Check if Wartribes is already there (just in case)
    exists = any(p.get("Name") == "Wartribes" for p in settings.get("Projects", []))
    
    if not exists:
        wartribes = {
          "Id": "b8a753a1-4b86-4c0f-9bce-48d10e27c685",
          "Name": "Wartribes",
          "HotkeyName": "",
          "Actions": [
            {
              "Type": 0,
              "Path": "D:\\Wartribes",
              "Arguments": "",
              "Layout": {
                "UseRatio": True,
                "X": 0,
                "Y": 0,
                "Width": 0,
                "Height": 0,
                "RatioX": 0,
                "RatioY": 0,
                "RatioWidth": 0.1,
                "RatioHeight": 0.1
              }
            },
            {
              "Type": 1,
              "Path": "Rider",
              "Arguments": "",
              "Layout": {
                "UseRatio": True,
                "X": 0,
                "Y": 0,
                "Width": 0,
                "Height": 0,
                "RatioX": 0.2,
                "RatioY": 0.2,
                "RatioWidth": 0.5,
                "RatioHeight": 0.5
              }
            },
            {
              "Type": 0,
              "Path": "D:\\TheBlackTrees",
              "Arguments": "",
              "Layout": {
                "UseRatio": True,
                "X": 0,
                "Y": 0,
                "Width": 0,
                "Height": 0,
                "RatioX": 0,
                "RatioY": 0,
                "RatioWidth": 0,
                "RatioHeight": 0
              }
            },
            {
              "Type": 1,
              "Path": "gmi",
              "Arguments": "--workspace D:\\Wartribes",
              "Layout": {
                "UseRatio": True,
                "X": 0,
                "Y": 0,
                "Width": 0,
                "Height": 0,
                "RatioX": 0.8,
                "RatioY": 0.8,
                "RatioWidth": 0.3,
                "RatioHeight": 0.3
              }
            }
          ]
        }
        
        if "Projects" not in settings:
            settings["Projects"] = []
        settings["Projects"].insert(0, wartribes)
        
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(settings, f, indent=2)
        print("Wartribes project restored successfully.")
    else:
        print("Wartribes project already exists in settings.")
else:
    print("Settings not found.")
