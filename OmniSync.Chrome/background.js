// Keep browser control and tab cleanup independent: a failure to load one
// feature must not silently prevent the other from registering its listeners.
importScripts('signalr.min.js');
try {
  importScripts('browser-control.js');
} catch (error) {
  console.error('Omni browser control failed to load:', error);
}
try {
  importScripts('tab-cleanup.js');
} catch (error) {
  console.error('Omni tab cleanup failed to load:', error);
}

const HUB_URL = "http://localhost:5000/signalrhub";
const API_KEY = "test_api_key";

// Custom cleanup patterns stored in chrome.storage
let customCleanupPatterns = [];

// Load custom patterns on startup
chrome.storage.local.get(['customCleanupPatterns'], (result) => {
    customCleanupPatterns = Array.isArray(result.customCleanupPatterns)
        ? result.customCleanupPatterns.filter(pattern => typeof pattern === 'string') : [];
    console.log("Loaded custom cleanup patterns:", customCleanupPatterns);
});
chrome.storage.onChanged.addListener((changes, area) => {
    if (area === 'local' && changes.customCleanupPatterns) {
        customCleanupPatterns = Array.isArray(changes.customCleanupPatterns.newValue)
            ? changes.customCleanupPatterns.newValue.filter(pattern => typeof pattern === 'string') : [];
    }
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

// Periodic check alarm to keep things alive and ensure we haven't stayed disconnected
chrome.alarms.create("keep-alive-check", { periodInMinutes: 1 });

// --- Active Tab Tracking for TFT ---
let lastTftStatus = false;

function checkActiveTab() {
    chrome.windows.getLastFocused({ populate: false }, (window) => {
        // If no window is focused or browser is not active
        if (!window || !window.focused) {
            reportTftStatus(false);
            return;
        }
        
        chrome.tabs.query({ active: true, windowId: window.id }, (tabs) => {
            if (tabs && tabs.length > 0) {
                const url = tabs[0].url || "";
                const isTft = url.includes("TFT.html") || (url.includes("localhost") && url.includes("/TFT"));
                reportTftStatus(isTft);
            } else {
                reportTftStatus(false);
            }
        });
    });
}

function reportTftStatus(isActive) {
    if (isActive !== lastTftStatus) {
        lastTftStatus = isActive;
        console.log("Reporting TFT Active Status:", isActive);
        if (connection.state === signalR.HubConnectionState.Connected) {
            connection.invoke("ReportTftStatus", isActive).catch(err => console.error("Failed to report TFT status", err));
        }
    }
}

chrome.tabs.onActivated.addListener(checkActiveTab);
chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
    if (changeInfo.status === 'complete' || changeInfo.url) {
        checkActiveTab();
    }
});
chrome.tabs.onRemoved.addListener(checkActiveTab);
chrome.windows.onFocusChanged.addListener((windowId) => {
    if (windowId === chrome.windows.WINDOW_ID_NONE) {
        reportTftStatus(false);
    } else {
        checkActiveTab();
    }
});

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

// Helper function to check if a tab URL or title matches cleanup patterns
function shouldCleanTab(tabUrl, tabTitle) {
    if (!tabUrl) return false;
    
    // Check custom patterns (now contains all built-in ones too)
    for (const pattern of customCleanupPatterns) {
        if (pattern.toLowerCase().startsWith("title:")) {
            const titlePattern = pattern.substring(6).toLowerCase();
            if (tabTitle && tabTitle.toLowerCase().includes(titlePattern)) return true;
        } else {
            if (urlMatchesPattern(tabUrl, pattern)) return true;
        }
    }
    
    return false;
}

// OmniHub is the authoritative owner of cleanup patterns. The extension's
// local storage is only a cache, not a replacement source of truth.
const INTERNAL_CLEANUP_DEFAULTS = ['vivaldi:*', 'chrome:*'];
let internalDefaultSeedPromise = null;
let patternEditQueue = Promise.resolve();
async function readHubCleanupPatterns() {
    if (connection.state === signalR.HubConnectionState.Disconnected) {
        await start();
    }
    if (connection.state !== signalR.HubConnectionState.Connected) {
        throw Error('OmniHub is disconnected. Reconnect before editing cleanup patterns.');
    }
    const settings = await connection.invoke('GetSettings');
    const patterns = settings?.browserCleanupPatterns ?? settings?.BrowserCleanupPatterns;
    if (!Array.isArray(patterns) || !patterns.every(value => typeof value === 'string')) {
        throw Error('OmniHub did not return a valid browser cleanup pattern list.');
    }
    customCleanupPatterns = [...patterns];
    await chrome.storage.local.set({ customCleanupPatterns });
    return [...patterns];
}
function handleCleanupPatternsMessage(message) {
    // Serialize read-modify-write to avoid racing rapid popup edits.
    const request = patternEditQueue.then(async () => {
        const patterns = await readHubCleanupPatterns();
        if (message.command === 'patterns:list') return patterns;
        if (!['patterns:add', 'patterns:remove'].includes(message.command)) {
            throw Error('Unknown pattern action');
        }
        const pattern = typeof message.pattern === 'string' ? message.pattern.trim() : '';
        if (!pattern) throw Error('Enter a cleanup pattern');
        if (pattern.toLowerCase() === 'title:') throw Error('Provide text after title:');
        if (pattern.length > 2048) throw Error('Cleanup pattern exceeds 2048 characters');
        const next = message.command === 'patterns:add'
            ? (patterns.includes(pattern) ? patterns : [...patterns, pattern])
            : patterns.filter(value => value !== pattern);
        if (next.length > 256) throw Error('OmniHub supports at most 256 cleanup patterns');
        if (next.length !== patterns.length || next.some((value, index) => value !== patterns[index])) {
            // Existing Hub RPC persists the list and broadcasts the new value
            // to other clients. Failed RPCs never report a successful edit.
            await connection.invoke('SendCleanupPatterns', next);
        }
        customCleanupPatterns = next;
        await chrome.storage.local.set({ customCleanupPatterns });
        return next;
    });
    patternEditQueue = request.catch(() => {});
    return request;
}
chrome.runtime.onMessage.addListener((message, sender, reply) => {
    if (sender.id !== chrome.runtime.id || !message?.command?.startsWith('patterns:')) return false;
    handleCleanupPatternsMessage(message)
        .then(data => reply({ ok: true, data }))
        .catch(error => reply({ ok: false, error: String(error.message || error) }));
    return true;
});

function seedInternalCleanupDefaults() {
    if (internalDefaultSeedPromise) return internalDefaultSeedPromise;
    internalDefaultSeedPromise = (async () => {
        const stored = await chrome.storage.local.get('internalCleanupDefaultsSeeded');
        if (stored.internalCleanupDefaultsSeeded) return;
        // Seed once per extension installation, not on every startup: a
        // pattern explicitly deleted later must stay deleted.
        for (const pattern of INTERNAL_CLEANUP_DEFAULTS) {
            await handleCleanupPatternsMessage({ command: 'patterns:add', pattern });
        }
        await chrome.storage.local.set({ internalCleanupDefaultsSeeded: true });
    })().catch(error => {
        console.error('Failed to seed internal cleanup defaults:', error);
        throw error;
    }).finally(() => { internalDefaultSeedPromise = null; });
    return internalDefaultSeedPromise;
}

// Handle Commands from Android
connection.on("ReceiveCleanupPatterns", (patterns) => {
    console.log("SignalR: Received master cleanup patterns from Hub:", patterns);
    if (Array.isArray(patterns)) {
        customCleanupPatterns = patterns.filter(pattern => typeof pattern === 'string');
        chrome.storage.local.set({ customCleanupPatterns });
    }
});

connection.on("ReceiveBrowserCommand", async (command, url, newTab) => {
    try {
        console.log(`SignalR: Received command: ${command}, URL: ${url}, NewTab: ${newTab}`);
        
        if (command === "Ping") {
            connection.invoke("SendTabInfo", "Pong", "Extension Alive");
            return;
        }

        if (command === "Navigate" || command === "OpenTab") {
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
        if (url && url.length > 0) {
            // If a URL is provided, find all tabs matching that URL and reload them
            chrome.tabs.query({ url: url }, (tabs) => {
                if (tabs && tabs.length > 0) {
                    tabs.forEach(tab => chrome.tabs.reload(tab.id));
                } else {
                    // Fallback to query with wildcards if exact match fails
                    try {
                        const urlObj = new URL(url);
                        const wildcardUrl = url.includes('*') ? url : `*://${urlObj.hostname}${urlObj.pathname}*`;
                        chrome.tabs.query({ url: wildcardUrl }, (tabs) => {
                            tabs.forEach(tab => chrome.tabs.reload(tab.id));
                        });
                    } catch (e) {
                        // If not a full URL, just try a simple search
                        chrome.tabs.query({ url: `*${url}*` }, (tabs) => {
                            tabs.forEach(tab => chrome.tabs.reload(tab.id));
                        });
                    }
                }
            });
        } else {
            chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
                if (tabs[0]) chrome.tabs.reload(tabs[0].id);
            });
        }
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
        if (!globalThis.TabCleanup) throw Error('Tab cleanup failed to initialize; reload the extension and inspect its service worker errors.');
        const result = await globalThis.TabCleanup.run(shouldCleanTab);
        console.log(`Cleanup closed ${result.closed} tabs (${result.archived} archived); ${result.failed} could not be closed.`);
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
    } else if (command === "ReloadExtension") {
        console.log("SignalR: Reloading extension while preserving patterns and archived URLs...");
        chrome.runtime.reload();
    }
    } catch (err) {
        console.error("SignalR: Error processing browser command:", err);
        connection.invoke("SendTabInfo", "Error", err.toString());
    }
});

chrome.runtime.onStartup.addListener(() => {
    console.log("Extension startup - connecting...");
    start();
});

// Core URL quick-add works alongside the full pattern editor.
function getCoreUrlPattern(url) {
    try {
        const parsed = new URL(url);
        if (parsed.protocol === 'vivaldi:' || parsed.protocol === 'chrome:') {
            return `${parsed.protocol}*`;
        }
        if (parsed.protocol === 'file:') return 'file:///*';
        if (parsed.protocol === 'http:' || parsed.protocol === 'https:') {
            return `${parsed.protocol}//${parsed.hostname}/*`;
        }
    } catch {}
    return null;
}

chrome.runtime.onInstalled.addListener(() => {
    console.log("Extension installed/updated - connecting...");
    // Remove the retired context-menu entry on update.
    chrome.contextMenus.removeAll(() => {
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

    chrome.contextMenus.create({
        id: "cleanTabs",
        title: "Clean Tabs",
        contexts: ["action", "page"]
    });

    chrome.contextMenus.create({
        id: "editCleanupPatterns",
        title: "Edit cleanup patterns",
        contexts: ["action", "page"]
    });
    chrome.contextMenus.create({
        id: "addCoreUrlToCleanup",
        title: "Add core URL for cleanup",
        contexts: ["action", "page"]
    });
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
    } else if (info.menuItemId === "cleanTabs") {
        if (!globalThis.TabCleanup) {
            console.error("Clean Tabs unavailable: tab-cleanup.js failed to initialize.");
            return;
        }
        void globalThis.TabCleanup.run(shouldCleanTab)
            .then(result => console.log(`Cleanup closed ${result.closed} tabs (${result.archived} archived); ${result.failed} failed.`))
            .catch(error => console.error("Clean Tabs failed:", error));
    } else if (info.menuItemId === "editCleanupPatterns") {
        void chrome.windows.create({
            url: chrome.runtime.getURL("cleanup-patterns.html"),
            type: "popup",
            width: 520,
            height: 580
        });
    } else if (info.menuItemId === "addCoreUrlToCleanup") {
        const pattern = getCoreUrlPattern(tab?.url);
        if (!pattern) {
            console.warn("Add core URL skipped: no supported page URL.");
            return;
        }
        // Use the same OmniHub-authoritative write path as the editor.
        void handleCleanupPatternsMessage({ command: 'patterns:add', pattern })
            .then(() => console.log("Added core URL cleanup pattern:", pattern))
            .catch(error => console.error("Could not save core URL to OmniHub:", error));
    }
});

start();
// The independent OmniMCP browser bridge is opt-in and requires a locally
// configured bearer token. Existing Android/Hub functionality is separate.
void globalThis.BrowserControl?.start();

async function authenticate() {
    if (connection.state === signalR.HubConnectionState.Connected) {
        try {
            const success = await connection.invoke("Authenticate", API_KEY);
            console.log("SignalR: Authentication result:", success);
            if (success) {
                await seedInternalCleanupDefaults();
            }
            checkActiveTab(); // Check immediately after auth
        } catch (e) {
            console.error("SignalR: Authentication failed:", e);
        }
    }
}
