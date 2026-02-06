import json
import os

def check_unit_prefixes():
    config_path = r'OmniSync.Web/www/assets/tft/data/set_config.json'
    with open(config_path, 'r') as f:
        config = json.load(f)
    
    set_file = f"OmniSync.Web/www/assets/tft/data/{config['current_set']}.json"
    with open(set_file, 'r', encoding='utf-8') as f:
        data = json.load(f)
    
    units = data['units']
    names = [u['name'] for u in units]
    
    prefixes = {}
    conflicts = []
    
    for name in names:
        prefix = name[:2].lower()
        if prefix in prefixes:
            prefixes[prefix].append(name)
        else:
            prefixes[prefix] = [name]
            
    for prefix, matched_names in prefixes.items():
        if len(matched_names) > 1:
            conflicts.append(f"Prefix '{prefix}': {', '.join(matched_names)}")
            
    if conflicts:
        print("Units sharing the first two characters:")
        for c in conflicts:
            print(f"  {c}")
    else:
        print("No units share the first two characters.")

if __name__ == "__main__":
    check_unit_prefixes()
