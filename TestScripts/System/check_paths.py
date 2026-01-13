import os

def check_paths():
    # Base directory as per registry
    base_dir = r"D:\SSDProjects\Omni\OmniSync.Hub\src\OmniSync.Hub\bin\Debug\net9.0-windows"
    
    print(f"Base Dir: {base_dir}")
    
    # Check 6 levels up
    path_6 = os.path.abspath(os.path.join(base_dir, "../../../../../../OmniSync.Web/www"))
    print(f"6 levels up: {path_6}")
    print(f"Exists: {os.path.exists(path_6)}")
    
    # Check 7 levels up
    path_7 = os.path.abspath(os.path.join(base_dir, "../../../../../../../OmniSync.Web/www"))
    print(f"7 levels up: {path_7}")
    print(f"Exists: {os.path.exists(path_7)}")
    
    # Check 'dev' path from original code if CWD was src/OmniSync.Hub
    cwd_dev = r"D:\SSDProjects\Omni\OmniSync.Hub\src\OmniSync.Hub"
    path_dev = os.path.abspath(os.path.join(cwd_dev, "../../../OmniSync.Web/www"))
    print(f"Dev Path (3 levels from project root): {path_dev}")
    print(f"Exists: {os.path.exists(path_dev)}")

if __name__ == "__main__":
    check_paths()
