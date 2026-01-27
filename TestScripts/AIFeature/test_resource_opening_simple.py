#!/usr/bin/env python3
import os
import requests
import time
import logging

# --- CONFIGURATION ---
HUB_BASE_URL = "http://127.0.0.1:5000"
REST_API_URL = f"{HUB_BASE_URL}/api/external"
API_KEY = "test_api_key"

# Setup Root Directory
ROOT_DIR = os.path.dirname(os.path.abspath(__file__))
if "TestScripts" in ROOT_DIR:
    ROOT_DIR = os.path.abspath(os.path.join(ROOT_DIR, "..", ".."))

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("SimpleResourceTest")

def test_open_resources():
    target_file = os.path.join(ROOT_DIR, "design.txt").replace("/", "\\")
    target_folder = ROOT_DIR.replace("/", "\\")
    target_html = os.path.join(ROOT_DIR, "OmniSync.Web", "www", "IslandGenerator", "index.html").replace("/", "\\")

    logger.info("Starting Simple Resource Opening Test...")
    
    # 1. Open File (Notepad++) at line 100
    logger.info(f"Requesting to open File at line 100: {target_file}")
    r1 = requests.post(f"{REST_API_URL}/command", params={"key": API_KEY, "cmd": "OPEN_RESOURCE"}, json={"Path": target_file, "LineNumber": 100})
    logger.info(f"Response 1: {r1.status_code} {r1.text}")
    
    # 2. Open Folder (Explorer)
    logger.info(f"Requesting to open Folder: {target_folder}")
    r2 = requests.post(f"{REST_API_URL}/command", params={"key": API_KEY, "cmd": "OPEN_RESOURCE"}, json={"Path": target_folder})
    logger.info(f"Response 2: {r2.status_code} {r2.text}")
    
    # 3. Open HTML (Browser)
    logger.info(f"Requesting to open HTML: {target_html}")
    r3 = requests.post(f"{REST_API_URL}/command", params={"key": API_KEY, "cmd": "OPEN_RESOURCE"}, json={"Path": target_html})
    logger.info(f"Response 3: {r3.status_code} {r3.text}")

    print("\n" + "="*60)
    print(" MANUAL VERIFICATION REQUIRED")
    print("="*60)
    
    f = input("1. Did 'design.txt' open in Notepad++? (y/n): ")
    d = input(f"2. Did the folder '{target_folder}' open in Explorer? (y/n): ")
    h = input("3. Did the IslandGenerator HTML file open in the browser? (y/n): ")

    if f.lower() == 'y' and d.lower() == 'y' and h.lower() == 'y':
        print("\nRESULT: SUCCESS")
        return 0
    else:
        print("\nRESULT: FAILED")
        return 1

if __name__ == "__main__":
    exit(test_open_resources())
