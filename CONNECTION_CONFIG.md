# OmniSync Connection Configuration

## Current Setup

### PC (Hub) Configuration
- **Location**: `D:\SSDProjects\Omni\OmniSync.Hub`
- **IP Address**: `10.0.0.37` (get with `ipconfig`)
- **Port**: `5000`
- **Endpoint**: `/signalrhub`
- **Full URL**: `http://10.0.0.37:5000/signalrhub`
- **API Key**: `test_api_key` (in `appsettings.json`)

### Android App Configuration
- **Location**: `D:\SSDProjects\Omni\OmniSync.Android\app\src\main\java\com\omni\sync\OmniSyncApplication.kt`
- **Hub URL**: `http://10.0.0.37:5000/signalrhub`
- **API Key**: `test_api_key`

## Icon Configuration

### Centralized Icon Location
- **Path**: `D:\SSDProjects\Omni\Resources\`
  - `OmniIcon.ico` - For Windows (Hub tray icon)
  - `OmniIcon.png` - For Android (app icon)

### Hub Icon Reference
- **File**: `OmniSync.Hub\src\OmniSync.Hub\OmniSync.Hub.csproj`
- **Configuration**: Copies `Resources\OmniIcon.ico` to output directory

### Android Icon Reference
- **File**: `OmniSync.Android\app\src\main\res\drawable\ic_launcher.png`
- **Generated from**: `Resources\OmniIcon.png` (resized to 192x192)

## Troubleshooting Connection Issues

### If Android Can't Connect:

1. **Verify PC IP Address**
   ```cmd
   ipconfig
   ```
   Look for "IPv4 Address" under your WiFi adapter.
   
2. **Update Android App IP**
   If your PC IP changed, update `OmniSyncApplication.kt`:
   ```kotlin
   val pcIpAddress = "YOUR_NEW_IP"  // Line 33
   ```

3. **Check Hub is Running**
   Run: `python D:\SSDProjects\Omni\run_omnihub.py`

4. **Test from Android Browser**
   Open: `http://10.0.0.37:5000` in phone browser
   Should see connection refused (that's okay - means it's reachable)

5. **Check Firewall**
   Windows Firewall may block port 5000:
   - Open Windows Defender Firewall
   - Allow app through firewall
   - Allow dotnet.exe (or OmniSync.Hub.exe)

6. **Verify Same Network**
   - PC and phone must be on same WiFi network
   - Corporate/guest networks may block device-to-device communication

### Debug Logs to Check

On Android (via `adb logcat` or Android Studio Logcat):
```
SignalRClient: === CONNECTION DEBUG START ===
NetworkDebugger: === NETWORK DEBUG START ===
NetworkDebugger: Network available: true
NetworkDebugger: Connected via WiFi
NetworkDebugger: Target host: 10.0.0.37
```

## Quick Connection Test

### Python CLI (working reference)
```bash
cd D:\SSDProjects\Omni\OmniSync.Cli\deprecated
python omnisync_cli.py "echo test" "http://10.0.0.37:5000/signalrhub" "test_api_key"
```

If this works but Android doesn't:
- Network discovery is working
- Issue is likely in Android SignalR client configuration
- Check Android logs for specific error messages
