import os
import json
import re
import requests
from urllib.parse import urlparse

def scrape_mobalytics(html_path, output_dir, set_num="17"):
    """
    Processes a Mobalytics Champions HTML file to download icons and generate a set JSON.
    """
    if not os.path.exists(html_path):
        print(f"Error: HTML file not found at {html_path}")
        return

    with open(html_path, "r", encoding="utf8") as f:
        content = f.read()

    # 1. Download Assets
    resource_dir = os.path.join(output_dir, f"set{set_num}_assets")
    if not os.path.exists(resource_dir):
        os.makedirs(resource_dir)

    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    champion_urls = re.findall(r'https?://cdn\.mobalytics\.gg/assets/tft/images/champions/thumbnail/set' + set_num + r'/[^\s\"\'\)]+\.jpg', content)
    synergy_urls = re.findall(r'https?://cdn\.mobalytics\.gg/assets/common/icons/tft-synergies-set' + set_num + r'/[^\s\"\'\)]+\.svg', content)

    all_urls = set(champion_urls + synergy_urls)
    print(f"Found {len(all_urls)} assets. Downloading...")

    for url in all_urls:
        name = os.path.basename(urlparse(url).path)
        if name.startswith("24-"):
            name = name[3:]
        path = os.path.join(resource_dir, name)
        try:
            r = requests.get(url, headers=headers, timeout=10)
            if r.status_code == 200:
                with open(path, "wb") as f:
                    f.write(r.content)
        except Exception as e:
            print(f"Failed to download {url}: {e}")

    # 2. Extract Data
    print("Extracting champion data...")
    marker = 'window.__PRELOADED_STATE__='
    start_idx = content.find(marker) + len(marker)
    json_start = content.find('{', start_idx)
    brace_count = 0
    actual_end = -1
    for i in range(json_start, len(content)):
        if content[i] == '{': brace_count += 1
        elif content[i] == '}': 
            brace_count -= 1
            if brace_count == 0:
                actual_end = i + 1
                break

    data = json.loads(content[json_start:actual_end])

    def find_data_dict(d):
        if not isinstance(d, dict): return None
        if any(isinstance(v, dict) and v.get("__typename") == "ChampionsV1DataFlatDto" for v in d.values()):
            return d
        for v in d.values():
            if isinstance(v, (dict, list)):
                res = find_data_dict(v) if isinstance(v, dict) else None
                if not res and isinstance(v, list):
                    for item in v:
                        res = find_data_dict(item)
                        if res: break
                if res: return res
        return None

    flat_data = find_data_dict(data)
    synergies = {k: v for k, v in flat_data.items() if isinstance(v, dict) and v.get("__typename") == "SynergiesV1DataFlatDto"}
    lookup = {k: v.get("flatData", {}).get("__ref") for k, v in flat_data.items() if isinstance(v, dict) and v.get("__typename") == "SynergiesV1"}

    # Heuristics for Role Mapping
    TANK_TRAITS = ["Bastion", "Brawler", "Warden", "Juggernaut", "Defender", "Sentinel", "Bulwark", "Mecha", "Vanguard", "Titan"]
    AP_TRAITS = ["Arcanist", "Invoker", "Sorcerer", "Psionic", "Channeler", "Oracle", "Fateweaver"]
    AD_TRAITS = ["Sniper", "Gunslinger", "Deadeye", "Vanquisher", "Longshot", "Challenger", "Marauder", "Galaxy Hunter", "Gun Goddess"]
    
    def get_role(name, cost, traits):
        # Specific overrides
        if name in ["Aurelion Sol", "Ryze"]: return "AP Carry"
        if name in ["Sylas"]: return "Tank"
        
        # Check traits
        has_tank = any(t in TANK_TRAITS for t in traits)
        has_ap = any(t in AP_TRAITS for t in traits)
        has_ad = any(t in AD_TRAITS for t in traits)
        
        if has_tank: return "Tank"
        if has_ap: return "AP Carry"
        if has_ad: return "AD Carry"
        
        # Default based on cost/heuristic
        if cost >= 4: return "AP Carry" # Safe guess
        return "Fighter"

    units = []
    trait_counts = {} # track unit counts per trait

    for key, value in flat_data.items():
        if isinstance(value, dict) and value.get("__typename") == "ChampionsV1DataFlatDto":
            traits = []
            for syn_ref in value.get("synergies", []):
                ref_id = syn_ref.get("__ref")
                actual_ref = lookup.get(ref_id, ref_id)
                if actual_ref in synergies:
                    trait_name = synergies[actual_ref]["name"]
                    traits.append(trait_name)
                    trait_counts[trait_name] = trait_counts.get(trait_name, 0) + 1
            
            slug = value["slug"]
            cost = value["cost"]
            name = value["name"]
            
            units.append({
                "name": name,
                "cost": cost,
                "traits": traits,
                "is_carry": cost >= 4,
                "locked": False,
                "icon_url": f"assets/tft/set{set_num}/champions/{slug}.jpg",
                "role": get_role(name, cost, traits)
            })

    # Filter out unique/singleton traits
    valid_traits = {t for t, count in trait_counts.items() if count > 1}
    print(f"Filtering out {len(trait_counts) - len(valid_traits)} unique traits.")

    # Clean units trait lists
    for u in units:
        u["traits"] = [t for t in u["traits"] if t in valid_traits]

    trait_metadata = {}
    items = []
    for key, s in synergies.items():
        name = s.get("name")
        slug = s.get("slug")
        if name and name in valid_traits:
            trait_metadata[name] = {
                "breakpoints": [2, 4, 6],
                "type": s.get("type", "origin"),
                "icon_url": f"assets/tft/set{set_num}/traits/{slug}.svg"
            }
            items.append({
                "name": f"{name} Emblem",
                "is_emblem": True,
                "trait": name,
                "icon_url": f"assets/tft/set{set_num}/traits/{slug}.svg"
            })

    set_json = {
        "set_name": f"Set {set_num}",
        "units": sorted(units, key=lambda x: (x["cost"], x["name"]), reverse=True),
        "items": items,
        "trait_metadata": trait_metadata
    }

    output_json = os.path.join(output_dir, f"set{set_num}.json")
    with open(output_json, "w", encoding="utf8") as f:
        json.dump(set_json, f, indent=2)

    print(f"Successfully generated {output_json}")
    print(f"Extracted {len(units)} units, {len(items)} emblems, and {len(trait_metadata)} traits.")

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="TFT Set Scraper for Mobalytics")
    parser.add_argument("html", help="Path to the saved Mobalytics champions HTML file")
    parser.add_argument("--out", default=".", help="Output directory")
    parser.add_argument("--set", default="17", help="Set number")
    
    args = parser.parse_args()
    scrape_mobalytics(args.html, args.out, args.set)
