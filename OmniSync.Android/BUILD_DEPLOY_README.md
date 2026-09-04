# OmniSync Android - Build & Deploy Guide

## Quick Start

### Build and Deploy to Phone
```bash
python build_and_deploy.py
```

This will:
1. Build the debug APK
2. Auto-detect or connect to your Android device
3. Install and launch through ADB when available
4. Automatically fall back to Hub-to-Android deployment when ADB is unavailable

### Common Options

**Clean Build**
```bash
python build_and_deploy.py --clean
```

**Build Only (No Install)**
```bash
python build_and_deploy.py --no-install
```

**Install Without Launching**
```bash
python build_and_deploy.py --no-launch
```

**Deploy Through the Hub (Skip ADB)**
```bash
python build_and_deploy.py --hub-deploy
```

The script derives the phone-facing Hub URL from the PC's active LAN interface. Override it only when necessary:
```bash
python build_and_deploy.py --hub-deploy --hub-base-url http://192.168.1.37:5000
```

**Specify Device**
```bash
python build_and_deploy.py --device 10.0.0.236:41391
```

**Pair Once from the Command Line**
```bash
python build_and_deploy.py --pair-code 123456
```

The pairing and connection endpoints are discovered through mDNS. If the network blocks mDNS, provide the values shown by Android:
```bash
python build_and_deploy.py --pair-code 123456 --pair-endpoint 10.0.0.236:40111 --connect-endpoint 10.0.0.236:37123
```

**Release Build**
```bash
python build_and_deploy.py --release
```

### Combining Options
```bash
# Clean release build, install but don't launch
python build_and_deploy.py --clean --release --no-launch

# Quick deploy to specific device
python build_and_deploy.py --device 192.168.1.100:5555
```

## First-Time Device Setup

If no device is connected, the script will:

1. Reuse any responsive ADB connection.
2. Discover paired phones through `_adb-tls-connect._tcp` mDNS.
3. Retry the last successful endpoint.
4. Prompt for a pairing code only if the phone has not been paired before.

On the Android Dashboard, **WIFI DBG** opens Developer options and sends the six-digit pairing code to the Hub. Pairing and connection endpoints are optional unless the network blocks mDNS. The pairing code is never written to the Hub log.

Run diagnostics with:
```bash
python connectadb.py --diagnose
```

Current Android tooling expects a recent SDK Platform Tools installation. If diagnostics report an obsolete mDNS backend, update Platform Tools and verify that the PC firewall/router permits mDNS between Ethernet and Wi-Fi clients.

## Updating Without ADB

After installing this version, later builds are pushed through the same live SignalR connection the app already uses:

1. Build, publish, and request installation:
   ```bash
   python build_and_deploy.py --hub-deploy
   ```
2. The app downloads from the exact Hub base URL that SignalR successfully connected to.
3. The first update may open Android's **Install unknown apps** setting for one-time approval. Deployment resumes when the app returns to the foreground.

Running `python build_and_deploy.py` also uses this route automatically whenever ADB discovery, pairing, or installation cannot start. Legacy app builds that do not understand the native deployment command receive a time-limited authenticated APK download in the phone browser as a bootstrap fallback.

The Hub serves the debug APK from `app/build/outputs/apk/debug/app-debug.apk` through an API-key-protected endpoint. The app verifies the downloaded SHA-256 hash, package name, and signing certificate before handing it to Android. Android may still show its own confirmation screen depending on OS policy.

To serve a different APK, set `AndroidUpdate:ApkPath` in the Hub's `appsettings.json` to an absolute path or a path relative to the repository root. Always use the same signing key as the installed app.

## Dashboard Features

The new Dashboard screen includes:

### Connection Status
- Real-time connection state indicator
- Visual status (green = connected, red = error, orange = disconnected)
- Manual reconnect button

### Quick Actions
- **Test Echo**: Send a test command to verify connection
- **List Notes**: Test the notes API endpoint
- **Test Clipboard**: Send clipboard data to the hub
- **Clear Logs**: Reset the activity log

### Activity Log
- Real-time logging of all connection events
- Color-coded messages (green = success, red = error, orange = warning)
- Timestamps for each entry
- Auto-scrolls to latest entry
- Keeps last 100 entries

## Troubleshooting

### Build Fails
- Ensure you have Java JDK 17+ installed
- Run `python build_and_deploy.py --clean` to clean the build cache

### Cannot Connect to Device
- Ensure "Wireless debugging" is enabled on your Android device
- Make sure your computer and phone are on the same network
- Run `python connectadb.py --diagnose` and confirm `_adb-tls-connect._tcp` appears
- Try using USB debugging first, then enable wireless:
  ```bash
  adb tcpip 5555
  ```

### App Crashes
- Use `extractcrash.py` to capture crash logs:
  ```bash
  python extractcrash.py
  ```
- Check the generated `crash.txt` file

### Connection Issues
- Check the Dashboard's connection status indicator
- Use the reconnect button to manually restart the connection
- Verify the Hub is running on your PC
- Check that the API key in the app matches your Hub's configuration

## Development Workflow

1. Make code changes
2. Build and deploy: `python build_and_deploy.py`
3. Test on the Dashboard screen
4. Check logs if something doesn't work
5. If crash occurs, run `python extractcrash.py` for details

## Files

- `build_and_deploy.py` - Main build and deploy script
- `extractcrash.py` - Crash log extraction tool
- `gradlew.bat` / `gradlew` - Gradle wrapper (don't modify)
- `app/src/main/java/com/omni/sync/ui/screen/DashboardScreen.kt` - Enhanced dashboard UI
