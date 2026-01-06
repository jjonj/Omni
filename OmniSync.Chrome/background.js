try {
  importScripts('signalr.min.js');
} catch (e) {
  console.error(e);
}

const HUB_URL = "http://127.0.0.1:5000/signalrhub";
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
    .withAutomaticReconnect({
        nextRetryDelayInMilliseconds: retryContext => {
            // Keep retrying forever, max 10s between attempts
            return Math.min(2000 + retryContext.previousRetryCount * 2000, 10000);
        }
    })
    .configureLogging(signalR.LogLevel.Error) // Only log errors to reduce noise
    .build();

async function start() {
    if (connection.state !== signalR.HubConnectionState.Disconnected) {
        console.log("SignalR: Skipping start, current state is", connection.state);
        return;
    }
    
    console.log("SignalR: Attempting to connect...");
    try {
        await connection.start();
        console.log("SignalR: Connected.");
        await authenticate();
        // Clear any retry alarms
        chrome.alarms.clear("reconnect-alarm");
    } catch (err) {
        const errStr = err.toString();
        if (!errStr.includes("Failed to fetch") && !errStr.includes("TypeError")) {
            console.error("SignalR: Connection failed:", err);
        } else {
            console.log("SignalR: Hub unreachable, will try again via alarm.");
        }
        
        // Schedule a retry via alarm (more reliable in MV3 than setTimeout)
        chrome.alarms.create("reconnect-alarm", { delayInMinutes: 0.2 }); // Try in ~12 seconds
    }
}

async function authenticate() {
    if (connection.state === signalR.HubConnectionState.Connected) {
        try {
            const success = await connection.invoke("Authenticate", API_KEY);
            console.log("SignalR: Authentication result:", success);
        } catch (e) {
            console.error("SignalR: Authentication failed:", e);
        }
    }
}

connection.onclose(async (error) => {
    console.log("SignalR: Connection closed.", error || "");
    // Ensure we try to reconnect
    chrome.alarms.create("reconnect-alarm", { delayInMinutes: 0.1 });
});

connection.onreconnecting((error) => {
    console.log("SignalR: Reconnecting...");
});

connection.onreconnected(async (connectionId) => {
    console.log("SignalR: Reconnected. ID:", connectionId);
    await authenticate();
});

// Alarm listener to wake up the service worker and try connecting
chrome.alarms.onAlarm.addListener((alarm) => {
    if (alarm.name === "reconnect-alarm") {
        start();
    }
});

// Periodic check alarm to keep things alive and ensure we haven't stayed disconnected
chrome.alarms.create("keep-alive-check", { periodInMinutes: 1 });
chrome.alarms.onAlarm.addListener((alarm) => {
    if (alarm.name === "keep-alive-check") {
        if (connection.state === signalR.HubConnectionState.Disconnected) {
            start();
        } else if (connection.state === signalR.HubConnectionState.Connected) {
            // Optional: minimal ping to keep connection from idling
            connection.invoke("GetHubStatus").catch(() => {});
        }
    }
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
        // First check for any audible tabs (currently playing sound)
        chrome.tabs.query({ audible: true }, (audibleTabs) => {
            if (audibleTabs.length > 0) {
                console.log(`Found ${audibleTabs.length} audible tabs. Pausing them.`);
                audibleTabs.forEach(tab => {
                    if (tab.url && !tab.url.startsWith("chrome://") && !tab.url.startsWith("edge://")) {
                        chrome.scripting.executeScript({
                            target: { tabId: tab.id, allFrames: true },
                            func: () => {
                                const media = document.querySelectorAll('video, audio');
                                media.forEach(m => m.pause());
                                // Also try clicking common pause buttons
                                ['.ytp-play-button', '[aria-label="Pause"]', '.spoticon-pause-32', 'button.pause'].forEach(s => {
                                    document.querySelectorAll(s).forEach(el => {
                                        el.click();
                                    });
                                });
                            }
                        }).catch(err => console.log("Failed to pause tab:", tab.id, err));
                    }
                });
            } else {
                // No audible tabs, fall back to active tab toggle
                chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
                    if (tabs[0] && tabs[0].url && !tabs[0].url.startsWith("chrome://") && !tabs[0].url.startsWith("edge://")) {
                        chrome.scripting.executeScript({
                            target: { tabId: tabs[0].id, allFrames: true },
                            func: () => {
                                const media = document.querySelectorAll('video, audio');
                                if (media.length > 0) {
                                    let anyPaused = false;
                                    media.forEach(m => { if (m.paused) anyPaused = true; });
                                    if (anyPaused) media.forEach(m => m.play().catch(() => {}));
                                    else media.forEach(m => m.pause());
                                } else {
                                    // Fallback to clicking or spacebar
                                    const commonSelectors = [
                                        '.ytp-play-button', '.play-pause-button', '[aria-label="Play"]', 
                                        '[aria-label="Pause"]', '.spoticon-play-32', '.spoticon-pause-32'
                                    ];
                                    let clicked = false;
                                    commonSelectors.forEach(selector => {
                                        document.querySelectorAll(selector).forEach(el => { el.click(); clicked = true; });
                                    });
                                    if (!clicked) {
                                        const spaceEvent = new KeyboardEvent('keydown', { 'view': window, 'bubbles': true, 'cancelable': true, 'keyCode': 32 });
                                        document.dispatchEvent(spaceEvent);
                                    }
                                }
                            }
                        }).catch(err => console.log("Failed to toggle media on tab:", tabs[0].id, err));
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
    } else if (command === "SendLatestYouTubeToPhone") {
        if (chrome.history) {
            chrome.history.search({ text: 'youtube.com/watch', maxResults: 1 }, (results) => {
                if (results && results.length > 0) {
                    console.log("Found latest YouTube video in history:", results[0].url);
                    connection.invoke("SendTabToPhone", results[0].url);
                }
            });
        }
    } else if (command === "OpenLatestYouTubeOnPC") {
        if (chrome.history) {
            chrome.history.search({ text: 'youtube.com/watch', maxResults: 1 }, (results) => {
                if (results && results.length > 0) {
                    console.log("Opening latest YouTube video on PC:", results[0].url);
                    chrome.tabs.create({ url: results[0].url, active: true });
                }
            });
        }
    }
});

chrome.runtime.onStartup.addListener(() => {
    console.log("Extension startup - connecting...");
    start();
});

chrome.runtime.onInstalled.addListener(() => {
    console.log("Extension installed/updated - connecting...");
    
    // Create Context Menu Items
    chrome.contextMenus.create({
        id: "sendPageToPhone",
        title: "Send Page to Phone",
        contexts: ["page"]
    });
    
    chrome.contextMenus.create({
        id: "sendLatestYouTubeToPhone",
        title: "Latest YouTube to Phone",
        contexts: ["action", "page"]
    });

    chrome.contextMenus.create({
        id: "openLatestYouTubeOnPC",
        title: "Latest YouTube on PC",
        contexts: ["action", "page"]
    });

    start();
});

// Handle Context Menu Clicks
chrome.contextMenus.onClicked.addListener((info, tab) => {
    if (info.menuItemId === "sendPageToPhone") {
        if (tab && tab.url) {
            connection.invoke("SendTabToPhone", tab.url);
        }
    } else if (info.menuItemId === "sendLatestYouTubeToPhone") {
        chrome.history.search({ text: 'youtube.com/watch', maxResults: 1 }, (results) => {
            if (results && results.length > 0) {
                connection.invoke("SendTabToPhone", results[0].url);
            }
        });
    } else if (info.menuItemId === "openLatestYouTubeOnPC") {
        chrome.history.search({ text: 'youtube.com/watch', maxResults: 1 }, (results) => {
            if (results && results.length > 0) {
                chrome.tabs.create({ url: results[0].url, active: true });
            }
        });
    }
});

start();
