try {
  importScripts('signalr.min.js');
} catch (e) {
  console.error(e);
}

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
            if (tabs[0]) {
                chrome.tabs.goBack(tabs[0].id).catch(err => console.log("No back history"));
            }
        });
    } else if (command === "Forward") {
        chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
            if (tabs[0]) {
                chrome.tabs.goForward(tabs[0].id).catch(err => console.log("No forward history"));
            }
        });
    } else if (command === "CloseTab") {
        if (url && !isNaN(url)) {
            // Close specific tab ID passed in 'url' field
            chrome.tabs.remove(parseInt(url));
        } else {
            // Default: close active tab
            chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
                if (tabs[0]) chrome.tabs.remove(tabs[0].id);
            });
        }
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
            connection.invoke("SendCleanupPatterns", customCleanupPatterns);
        }
    } else if (command === "RemoveCleanupPattern") {
        // url parameter contains the pattern to remove
        const index = customCleanupPatterns.indexOf(url);
        if (index > -1) {
            customCleanupPatterns.splice(index, 1);
            chrome.storage.local.set({ customCleanupPatterns });
            console.log("Removed cleanup pattern:", url);
            connection.invoke("SendCleanupPatterns", customCleanupPatterns);
        }
    } else if (command === "GetCleanupPatterns") {
        // Send patterns back to hub which will forward to Android
        connection.invoke("SendCleanupPatterns", customCleanupPatterns);
    } else if (command === "GetTabInfo") {
        chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
            if (tabs[0]) {
                connection.invoke("SendTabInfo", tabs[0].title || "", tabs[0].url || "");
            }
        });
    } else if (command === "ListTabs") {
        chrome.tabs.query({}, (tabs) => {
            const tabList = tabs.map(t => ({ id: t.id, title: t.title || "", url: t.url || "" }));
            connection.invoke("SendTabList", tabList);
        });
    } else if (command === "MediaPlayPause") {
        chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
            if (tabs[0]) {
                chrome.scripting.executeScript({
                    target: { tabId: tabs[0].id, allFrames: true },
                    func: () => {
                        // Strategy 1: Media Session API (Modern standard)
                        if (navigator.mediaSession && navigator.mediaSession.playbackState !== 'none') {
                            if (navigator.mediaSession.playbackState === 'playing') {
                                document.querySelectorAll('video, audio').forEach(m => m.pause());
                            } else {
                                document.querySelectorAll('video, audio').forEach(m => m.play());
                            }
                            // Note: playbackState is sometimes read-only or inaccurate, proceed to other strategies
                        }

                        // Strategy 2: Direct Video/Audio elements
                        const media = document.querySelectorAll('video, audio');
                        if (media.length > 0) {
                            media.forEach(m => {
                                if (m.paused) m.play().catch(() => {});
                                else m.pause();
                            });
                        }

                        // Strategy 3: Common Play/Pause button classes/IDs (YouTube, Spotify, etc.)
                        const commonSelectors = [
                            '.ytp-play-button', // YouTube
                            '.play-pause-button', 
                            '[aria-label="Play"]', 
                            '[aria-label="Pause"]',
                            '.spoticon-play-32', 
                            '.spoticon-pause-32',
                            'button.play',
                            'button.pause'
                        ];
                        commonSelectors.forEach(selector => {
                            document.querySelectorAll(selector).forEach(el => el.click());
                        });

                        // Strategy 4: Keyboard events (Spacebar fallback)
                        // Only if no media was found
                        if (media.length === 0) {
                            const spaceEvent = new KeyboardEvent('keydown', {
                                'view': window,
                                'bubbles': true,
                                'cancelable': true,
                                'keyCode': 32
                            });
                            document.dispatchEvent(spaceEvent);
                        }
                    }
                });
            }
        });
    } else if (command === "OpenCurrentTabOnPhone") {
        chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
            if (tabs[0] && tabs[0].url) {
                connection.invoke("SendTabToPhone", tabs[0].url);
            }
        });
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
