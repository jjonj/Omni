importScripts('signalr.min.js');

const HUB_URL = "http://10.0.0.37:5000/signalrhub";
const API_KEY = "test_api_key";

// Custom cleanup patterns stored in chrome.storage
let customCleanupPatterns = [];

// Load custom patterns on startup
chrome.storage.local.get(['customCleanupPatterns'], (result) => {
    customCleanupPatterns = result.customCleanupPatterns || [];
    console.log("Loaded custom cleanup patterns:", customCleanupPatterns);
});

let connection = new signalR.HubConnectionBuilder()
    .withUrl(HUB_URL)
    .withAutomaticReconnect()
    .configureLogging(signalR.LogLevel.Information)
    .build();

async function start() {
    try {
        await connection.start();
        console.log("SignalR Connected.");
        // Authenticate
        await connection.invoke("Authenticate", API_KEY);
    } catch (err) {
        console.log(err);
        setTimeout(start, 5000);
    }
}

connection.onclose(async () => {
    await start();
});

// Helper function to check if URL matches a pattern (supports * wildcard)
function urlMatchesPattern(url, pattern) {
    // Convert pattern to regex: escape special chars, replace * with .*
    const regexPattern = pattern
        .replace(/[.+?^${}()|[\]\\]/g, '\\$&')  // Escape special regex chars except *
        .replace(/\*/g, '.*');  // Replace * with .*
    const regex = new RegExp(regexPattern, 'i');
    return regex.test(url);
}

// Helper function to check if a tab URL matches cleanup patterns
function shouldCleanTab(tabUrl) {
    if (!tabUrl) return false;
    
    // Twitch following directory
    if (tabUrl.includes("twitch.tv/directory/following")) return true;
    
    // YouTube tabs that are NOT watch pages or channel pages
    if (tabUrl.includes("youtube.com") && !tabUrl.includes("/watch?v=") && !tabUrl.includes("/@")) return true;
    
    // Google.com pages
    if (tabUrl.includes("google.com/")) return true;
    
    // Local file URLs
    if (tabUrl.startsWith("file:///")) return true;
    
    // Chrome start page / new tab
    if (tabUrl === "chrome://newtab/" || tabUrl === "about:blank" || tabUrl === "edge://newtab/") return true;
    
    // Check custom patterns
    for (const pattern of customCleanupPatterns) {
        if (urlMatchesPattern(tabUrl, pattern)) return true;
    }
    
    return false;
}

// Handle Commands from Android
connection.on("ReceiveBrowserCommand", async (command, url, newTab) => {
    console.log(`Command: ${command}, URL: ${url}, NewTab: ${newTab}`);

    if (command === "Navigate") {
        if (newTab) {
            chrome.tabs.create({ url: url, active: true });
        } else {
            // Update current tab
            chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
                if (tabs.length > 0) {
                    chrome.tabs.update(tabs[0].id, { url: url });
                } else {
                    // Fallback if no active tab found
                    chrome.tabs.create({ url: url, active: true });
                }
            });
        }
    } else if (command === "Refresh") {
        chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
            if (tabs[0]) chrome.tabs.reload(tabs[0].id);
        });
    } else if (command === "Back") {
        chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
            if (tabs[0]) chrome.tabs.goBack(tabs[0].id);
        });
    } else if (command === "Forward") {
        chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
            if (tabs[0]) chrome.tabs.goForward(tabs[0].id);
        });
    } else if (command === "CloseTab") {
        chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
            if (tabs[0]) chrome.tabs.remove(tabs[0].id);
        });
    } else if (command === "CleanTabs") {
        chrome.tabs.query({}, (tabs) => {
            const tabsToClose = tabs.filter(tab => shouldCleanTab(tab.url));
            console.log(`Cleaning ${tabsToClose.length} tabs`);
            tabsToClose.forEach(tab => chrome.tabs.remove(tab.id));
        });
    } else if (command === "AddCleanupPattern") {
        // url parameter contains the pattern to add
        if (url && !customCleanupPatterns.includes(url)) {
            customCleanupPatterns.push(url);
            chrome.storage.local.set({ customCleanupPatterns });
            console.log("Added cleanup pattern:", url);
        }
    } else if (command === "RemoveCleanupPattern") {
        // url parameter contains the pattern to remove
        const index = customCleanupPatterns.indexOf(url);
        if (index > -1) {
            customCleanupPatterns.splice(index, 1);
            chrome.storage.local.set({ customCleanupPatterns });
            console.log("Removed cleanup pattern:", url);
        }
    } else if (command === "GetCleanupPatterns") {
        // Send patterns back to hub which will forward to Android
        connection.invoke("SendCleanupPatterns", customCleanupPatterns);
    } else if (command === "AddCurrentTabToCleanup") {
        // Get current tab URL and add it to cleanup patterns
        chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
            if (tabs[0] && tabs[0].url) {
                const tabUrl = tabs[0].url;
                if (!customCleanupPatterns.includes(tabUrl)) {
                    customCleanupPatterns.push(tabUrl);
                    chrome.storage.local.set({ customCleanupPatterns });
                    console.log("Added current tab to cleanup:", tabUrl);
                    // Notify hub of updated patterns
                    connection.invoke("SendCleanupPatterns", customCleanupPatterns);
                }
            }
        });
    }
});

start();
