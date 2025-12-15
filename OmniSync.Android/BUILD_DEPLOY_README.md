# OmniSync Android - Build & Deploy Guide

## Quick Start

### Build and Deploy to Phone
```bash
python build_and_deploy.py
```

This will:
1. Build the debug APK
2. Auto-detect or connect to your Android device
3. Install the APK
4. Launch the app

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

**Specify Device**
```bash
python build_and_deploy.py --device 10.0.0.236:41391
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

If no device is connected, the script will guide you through:

1. Enter your phone's IP address
2. (Android 11+) If needed, pair with your phone:
   - Enable "Wireless debugging" on your phone
   - Enter the pairing port and code shown on your phone
3. Enter the connect port (usually 5555 or shown in "Wireless debugging")

The device connection is saved by ADB for future use.

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
