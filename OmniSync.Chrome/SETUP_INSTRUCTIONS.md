# Chrome Extension Setup Instructions

## Step 1: Download SignalR JavaScript Library

You need to download `signalr.min.js` and place it in the `D:\SSDProjects\Omni\OmniSync.Chrome` folder.

### Option 1: Direct Download from CDN
Visit this URL in your browser and save the file as `signalr.min.js`:
https://cdnjs.cloudflare.com/ajax/libs/microsoft-signalr/8.0.7/signalr.min.js

### Option 2: Using NPM
If you have Node.js installed, you can:
```bash
cd D:\SSDProjects\Omni\OmniSync.Chrome
npm install @microsoft/signalr
# Then copy: node_modules/@microsoft/signalr/dist/browser/signalr.min.js
```

## Step 2: Load Extension in Chrome

1. Open Chrome and go to `chrome://extensions/`
2. Enable "Developer mode" (toggle in top right)
3. Click "Load unpacked"
4. Select the folder: `D:\SSDProjects\Omni\OmniSync.Chrome`
5. The extension should now appear in your extensions list

## Step 3: Verify Extension is Running

1. Click on the extension icon to see if it's active
2. Open Chrome DevTools (F12)
3. Go to Console tab
4. You should see: "SignalR Connected." if it successfully connected to the Hub

## Files Created:
- manifest.json ✓
- background.js ✓
- signalr.min.js (you need to download this)

## Troubleshooting:

If the extension doesn't connect:
- Make sure the Hub is running (run_omnihub.py)
- Check that the IP address in background.js matches your Hub IP (currently set to 10.0.0.37)
- Check Chrome DevTools console for error messages
