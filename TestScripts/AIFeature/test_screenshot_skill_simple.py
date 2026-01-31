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
logger = logging.getLogger("SimpleScreenshotTest")

def test_screenshot_skill():
    logger.info("Starting Simple Screenshot Skill Test...")
    
    # 1. Take Screenshot via Hub API
    logger.info(f"Requesting screenshot via Hub API...")
    try:
        response = requests.post(f"{REST_API_URL}/screenshot", params={"key": API_KEY})
        logger.info(f"Response: {response.status_code}")
        
        if response.status_code == 200:
            data = response.json()
            file_path = data.get("filePath")
            file_name = data.get("fileName")
            logger.info(f"SUCCESS: Screenshot saved to {file_path}")
            
            print("\n" + "="*60)
            print(" MANUAL VERIFICATION REQUIRED")
            print("="*60)
            print(f"File Path: {file_path}")
            
            if os.path.exists(file_path):
                print(f"Verified: File exists on disk.")
                # Try to open it to show the user
                os.startfile(file_path)
                
                check = input("\nDid the screenshot open and does it look correct? (y/n): ")
                if check.lower() == 'y':
                    print("\nRESULT: SUCCESS")
                    return 0
                else:
                    print("\nRESULT: FAILED (User reported incorrect screenshot)")
                    return 1
            else:
                print(f"FAILED: File does NOT exist on disk at the reported path.")
                return 1
        else:
            logger.error(f"FAILED: API returned {response.status_code} - {response.text}")
            return 1
            
    except Exception as e:
        logger.error(f"ERROR communicating with Hub: {e}")
        return 1

if __name__ == "__main__":
    exit(test_screenshot_skill())
