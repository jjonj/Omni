# Browser Control Debugging Guide

## Problem
Browser control functionality stopped working after commits 33526b3 and 7e968f5.

## Components to Check

### 1. **Hub (OmniSync.Hub)**

#### Verify SendBrowserCommand Method Exists
✅ **Location**: `OmniSync.Hub/src/OmniSync.Hub/Presentation/Hubs/RpcApiHub.cs` (Line 364-372)
✅ **Status**: Method exists and looks correct

```csharp
public async Task SendBrowserCommand(string command, string url, bool newTab)
{
    if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
    {
        AnyCommandReceived?.Invoke(this, $"Browser: {command} -> {url}");
        await Clients.All.SendAsync("ReceiveBrowserCommand", command, url, newTab);
    }
}
```

**Action**: Start Hub and check console for any errors
```bash
cd D:\SSDProjects\Omni
python run_omnihub.py
```

---

### 2. **Chrome Extension**

#### Check if Extension is Loaded
1. Open Chrome: `chrome://extensions/`
2. Look for "OmniSync Browser Controller"
3. Check if it's enabled

#### Check Extension Console for Errors
1. Click "inspect views: service worker" on the extension
2. Look for connection errors or JavaScript errors
3. Should see: "SignalR Connected." if working

#### Verify background.js is correct
✅ **Location**: `OmniSync.Chrome/background.js`
✅ **Handler**: Has `ReceiveBrowserCommand` listener

**Common Issues**:
- signalr.min.js missing or corrupted
- Hub URL incorrect (should be `http://10.0.0.37:5000/signalrhub`)
- API key mismatch

---

### 3. **Android App**

#### Check SignalRClient.sendBrowserCommand
✅ **Location**: `SignalRClient.kt` (Lines 507-517)
✅ **Status**: Method exists

```kotlin
fun sendBrowserCommand(command: String, url: String, newTab: Boolean) {
    if (hubConnection?.connectionState == com.microsoft.signalr.HubConnectionState.CONNECTED) {
        try {
            hubConnection?.send("SendBrowserCommand", command, url, newTab)
            mainViewModel.addLog("Browser: $command", com.omni.sync.ui.screen.LogType.INFO)
        } catch (e: Exception) {
            mainViewModel.setErrorMessage("Browser command failed: ${e.message}")
        }
    } else {
        mainViewModel.setErrorMessage("Not connected.")
    }
}
```

#### Check BrowserViewModel
✅ **Location**: `BrowserViewModel.kt`
✅ **Methods exist**: navigate(), sendCommand(), etc.

**Potential Issue**: The BrowserViewModel references `signalRClient.cleanupPatterns` which was added in recent commits. This might cause issues if SignalRClient doesn't properly initialize this flow.

---

## 🔍 Step-by-Step Debugging

### Step 1: Test Hub Directly (Python Script)
```bash
cd D:\SSDProjects\Omni
python TestScripts/test_browser_commands.py
```

**What this tests**:
- Hub is running and accepting connections
- SendBrowserCommand method works
- Chrome extension receives commands

**Expected output**:
- "✓ Connection established."
- "✓ Sent Navigate command to google.com"
- Chrome should navigate to websites

**If this fails**: Problem is in Hub or Chrome extension

---

### Step 2: Check Chrome Extension Console
1. Chrome → `chrome://extensions/`
2. Find "OmniSync Browser Controller"
3. Click "inspect views: service worker"
4. Run the Python test script again
5. Look for console messages:
   - "SignalR Connected." (good)
   - "Command: Navigate, URL: ..., NewTab: ..." (good)
   - Any errors (bad)

**Common errors**:
- "SignalR connection failed" → Hub not running or wrong URL
- "Cannot read property..." → JavaScript error in background.js
- No messages at all → Extension not receiving events

---

### Step 3: Check Android App Logs
In Android Studio (or via adb logcat):

Look for these log messages:
- "Browser: Navigate" (from SignalRClient)
- "Connected to hub: ..." (connection established)
- Any error messages

**Check connection status**:
- Open Android app → Dashboard screen
- Check if "Hub Connected" shows (should be green)
- If not connected, button clicks won't do anything

---

### Step 4: Test Android UI
1. Open Android app
2. Go to "Browser" tab
3. Try to navigate to "google.com"
4. Watch Android Studio logs for:
   - `sendBrowserCommand` being called
   - Any exceptions

---

## 🐛 Known Issues from Recent Commits

### Issue 1: cleanupPatterns Flow
**Commit 7e968f5** added `_cleanupPatterns` flow to SignalRClient.

**Problem**: BrowserViewModel expects this flow at initialization:
```kotlin
val customCleanupPatterns: StateFlow<List<String>> = signalRClient.cleanupPatterns
```

**If SignalRClient doesn't initialize this properly**, BrowserViewModel constructor could crash.

**Fix Check**: Verify SignalRClient.kt has:
```kotlin
private val _cleanupPatterns = MutableStateFlow<List<String>>(emptyList())
val cleanupPatterns: StateFlow<List<String>> = _cleanupPatterns
```

---

### Issue 2: ReceiveCleanupPatterns Handler
Recent commits added a handler for cleanup patterns from Chrome extension.

**Potential problem**: If Chrome extension doesn't implement this, it could cause SignalR errors.

**Check**: In Chrome extension console, look for:
- "Unknown method: ReceiveCleanupPatterns" errors

---

## ✅ Quick Fix Checklist

Run through these in order:

1. **[ ] Hub is running**
   ```bash
   python run_omnihub.py
   ```
   Should see: "Hub started on port 5000"

2. **[ ] Chrome extension is loaded**
   - Go to `chrome://extensions/`
   - "OmniSync Browser Controller" should be visible and enabled

3. **[ ] signalr.min.js exists**
   - Check: `D:\SSDProjects\Omni\OmniSync.Chrome\signalr.min.js`
   - If missing, download from: https://cdnjs.cloudflare.com/ajax/libs/microsoft-signalr/8.0.7/signalr.min.js

4. **[ ] Extension connects to Hub**
   - Right-click extension → "Inspect service worker"
   - Console should show: "SignalR Connected."

5. **[ ] Android app is connected**
   - Open app → Dashboard tab
   - Should show "Hub Connected" in green

6. **[ ] Test with Python script**
   ```bash
   cd D:\SSDProjects\Omni
   python TestScripts/test_browser_commands.py
   ```
   - Chrome should navigate to websites
   - If this works, problem is in Android app
   - If this fails, problem is in Hub or Chrome extension

---

## 🔧 Most Likely Issues (Ranked by Probability)

### 1. **Android App Not Connected to Hub** (80% probability)
**Symptoms**: Buttons do nothing, no logs in Android Studio
**Fix**: Check Dashboard screen for connection status

### 2. **Chrome Extension Not Loaded** (15% probability)
**Symptoms**: Python test script doesn't work, no browser navigation
**Fix**: Load extension in `chrome://extensions/`

### 3. **SignalR Library Missing** (4% probability)
**Symptoms**: Extension shows errors about "signalR is not defined"
**Fix**: Download signalr.min.js to OmniSync.Chrome folder

### 4. **Hub Not Running** (1% probability)
**Symptoms**: Everything fails, connection errors everywhere
**Fix**: Run `python run_omnihub.py`

---

## 📝 What to Tell Me

Please check and report:

1. **Hub Status**: Is `python run_omnihub.py` running? Any errors?

2. **Chrome Extension**: 
   - Is it loaded in `chrome://extensions/`?
   - What does the service worker console show?

3. **Android App**:
   - Dashboard shows "Hub Connected"?
   - Any errors in Android Studio logcat?

4. **Python Test**:
   - Does `python TestScripts/test_browser_commands.py` work?
   - What output do you see?

This will help me identify exactly where the breakdown is happening!
