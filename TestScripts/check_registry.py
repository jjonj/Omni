import winreg

def check_registry():
    key_path = r"SOFTWARE\Microsoft\Windows\CurrentVersion\Run"
    app_name = "OmniSync Hub"
    
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path, 0, winreg.KEY_READ) as key:
            try:
                value, type_ = winreg.QueryValueEx(key, app_name)
                print(f"Registry key found: {value}")
                return value
            except FileNotFoundError:
                print("Registry key not found.")
                return None
    except Exception as e:
        print(f"Error accessing registry: {e}")
        return None

if __name__ == "__main__":
    check_registry()
