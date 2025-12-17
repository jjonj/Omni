"""
Quick diagnostic script to check Browser Control system health.
Run this to identify where the problem is.
"""

import os
import sys

def check_file_exists(path, description):
    """Check if a file exists and report status."""
    exists = os.path.exists(path)
    status = "✅" if exists else "❌"
    print(f"{status} {description}: {path}")
    return exists

def main():
    print("\n" + "="*60)
    print("OmniSync Browser Control Diagnostic")
    print("="*60 + "\n")
    
    # Change to project directory
    project_dir = r"D:\SSDProjects\Omni"
    os.chdir(project_dir)
    
    all_good = True
    
    # Check Hub files
    print("Checking Hub Files...")
    hub_file = check_file_exists(
        "OmniSync.Hub/src/OmniSync.Hub/Presentation/Hubs/RpcApiHub.cs",
        "Hub RpcApiHub.cs"
    )
    all_good = all_good and hub_file
    
    # Check Chrome Extension files
    print("\nChecking Chrome Extension Files...")
    manifest = check_file_exists(
        "OmniSync.Chrome/manifest.json",
        "Extension manifest"
    )
    background = check_file_exists(
        "OmniSync.Chrome/background.js",
        "Extension background script"
    )
    signalr = check_file_exists(
        "OmniSync.Chrome/signalr.min.js",
        "SignalR library (CRITICAL)"
    )
    all_good = all_good and manifest and background and signalr
    
    # Check Android files
    print("\nChecking Android Files...")
    signalr_client = check_file_exists(
        "OmniSync.Android/app/src/main/java/com/omni/sync/data/repository/SignalRClient.kt",
        "SignalRClient.kt"
    )
    browser_vm = check_file_exists(
        "OmniSync.Android/app/src/main/java/com/omni/sync/viewmodel/BrowserViewModel.kt",
        "BrowserViewModel.kt"
    )
    browser_screen = check_file_exists(
        "OmniSync.Android/app/src/main/java/com/omni/sync/ui/screen/BrowserControlScreen.kt",
        "BrowserControlScreen.kt"
    )
    all_good = all_good and signalr_client and browser_vm and browser_screen
    
    # Check test scripts
    print("\nChecking Test Scripts...")
    test_script = check_file_exists(
        "TestScripts/test_browser_commands.py",
        "Browser test script"
    )
    
    # Check for SendBrowserCommand in Hub
    print("\nChecking Hub Code...")
    if hub_file:
        with open("OmniSync.Hub/src/OmniSync.Hub/Presentation/Hubs/RpcApiHub.cs", 'r', encoding='utf-8') as f:
            hub_content = f.read()
            has_method = "SendBrowserCommand" in hub_content
            status = "✅" if has_method else "❌"
            print(f"{status} SendBrowserCommand method exists in Hub")
            all_good = all_good and has_method
    
    # Check for sendBrowserCommand in SignalRClient
    print("\nChecking Android Code...")
    if signalr_client:
        with open("OmniSync.Android/app/src/main/java/com/omni/sync/data/repository/SignalRClient.kt", 'r', encoding='utf-8') as f:
            client_content = f.read()
            has_method = "fun sendBrowserCommand" in client_content
            has_cleanup = "_cleanupPatterns" in client_content
            status = "✅" if has_method else "❌"
            print(f"{status} sendBrowserCommand method exists in SignalRClient")
            status2 = "✅" if has_cleanup else "❌"
            print(f"{status2} cleanupPatterns flow exists (needed for BrowserViewModel)")
            all_good = all_good and has_method and has_cleanup
    
    # Summary
    print("\n" + "="*60)
    if all_good:
        print("All files and code checks passed!")
        print("\nNext Steps:")
        print("1. Make sure Hub is running: python run_omnihub.py")
        print("2. Load Chrome extension: chrome://extensions/")
        print("3. Check extension console for 'SignalR Connected.'")
        print("4. Test with: python TestScripts/test_browser_commands.py")
        print("5. If Python test works but Android doesn't:")
        print("   - Check Android app connection status (Dashboard screen)")
        print("   - Check Android Studio logcat for errors")
    else:
        print("Some checks failed!")
        print("\nFix the failed items above, then run this script again.")
        if not signalr:
            print("\nCRITICAL: signalr.min.js is missing!")
            print("   Download from: https://cdnjs.cloudflare.com/ajax/libs/microsoft-signalr/8.0.7/signalr.min.js")
            print("   Save to: D:\\SSDProjects\\Omni\\OmniSync.Chrome\\signalr.min.js")
    print("="*60 + "\n")

if __name__ == "__main__":
    main()
