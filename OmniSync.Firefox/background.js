/*
This is the background script for the FireCroves Firefox addon.
*/

const MUTED_SITES_KEY = "mutedSites";
const HUB_URL = "http://127.0.0.1:5000/signalrhub";
const API_KEY = "test_api_key";

let connection = new signalR.HubConnectionBuilder()
    .withUrl(HUB_URL)
    .withAutomaticReconnect()
    .build();

async function startSignalR() {
    try {
        await connection.start();
        console.log("Firefox SignalR Connected.");
        await connection.invoke("Authenticate", API_KEY);
    } catch (err) {
        console.warn("Firefox SignalR connection failed:", err);
        setTimeout(startSignalR, 5000);
    }
}

connection.on("ReceiveBrowserCommand", async (command, url, newTab) => {
    // Firefox specific commands
    if (command === "ToggleMuteTab") {
        const tabs = await browser.tabs.query({ active: true, currentWindow: true });
        if (tabs[0]) {
            toggleMute(tabs[0]);
        }
    }
});

connection.onreconnected(() => {
    connection.invoke("Authenticate", API_KEY);
});

startSignalR();

// --- Initialization ---

// On install, initialize the storage if it's not already set.
browser.runtime.onInstalled.addListener(() => {
  browser.storage.local.get(MUTED_SITES_KEY).then(result => {
    if (!result[MUTED_SITES_KEY]) {
      browser.storage.local.set({ [MUTED_SITES_KEY]: [] });
    }
  });
});


// --- Helper Functions ---

/**
 * Gets the hostname from a URL.
 * @param {string} urlString - The URL to parse.
 * @returns {string|null} - The hostname, or null if the URL is invalid.
 */
function getHostname(urlString) {
  try {
    // A fallback for invalid URLs that the tabs API might sometimes return (e.g., about:blank)
    if (!urlString || !urlString.startsWith("http")) {
      return null;
    }
    return new URL(urlString).hostname;
  } catch (e) {
    console.error(`Could not parse URL: ${urlString}`, e);
    return null;
  }
}

/**
 * Updates the browser action icon for a given tab.
 * @param {number} tabId - The ID of the tab.
 * @param {string} hostname - The hostname of the site in the tab.
 */
async function updateIcon(tabId, hostname) {
  if (!hostname) {
    browser.browserAction.setIcon({ path: "icons/unmuted.svg", tabId: tabId });
    browser.browserAction.setTitle({ title: "FireCroves Actions", tabId: tabId });
    return;
  }

  const { [MUTED_SITES_KEY]: mutedSites } = await browser.storage.local.get(MUTED_SITES_KEY);
  const isMuted = mutedSites.includes(hostname);

  const iconPath = isMuted ? "icons/muted.svg" : "icons/unmuted.svg";
  const title = isMuted ? `Unmute ${hostname}` : `Mute ${hostname}`;

  browser.browserAction.setIcon({ path: iconPath, tabId: tabId });
  browser.browserAction.setTitle({ title: title, tabId: tabId });
}


// --- Event Listeners ---

// 1. Listen for clicks on the browser action button.
browser.browserAction.onClicked.addListener((tab) => {
  toggleMute(tab);
});

async function toggleMute(tab) {
  const hostname = getHostname(tab.url);
  if (!hostname) {
    return; // Can't mute/unmute non-http pages.
  }

  const { [MUTED_SITES_KEY]: mutedSites } = await browser.storage.local.get(MUTED_SITES_KEY);
  const isMuted = mutedSites.includes(hostname);

  let newMutedSites;
  if (isMuted) {
    // Unmute the site
    newMutedSites = mutedSites.filter(site => site !== hostname);
    await browser.tabs.update(tab.id, { muted: false });
  } else {
    // Mute the site
    newMutedSites = [...mutedSites, hostname];
    await browser.tabs.update(tab.id, { muted: true });
  }

  await browser.storage.local.set({ [MUTED_SITES_KEY]: newMutedSites });
  updateIcon(tab.id, hostname);
}

// 2. Listen for tab updates (e.g., navigation, reload).
browser.tabs.onUpdated.addListener(async (tabId, changeInfo, tab) => {
  // We only need to act when the URL changes.
  if (changeInfo.url) {
    const hostname = getHostname(tab.url);
    if (hostname) {
        const { [MUTED_SITES_KEY]: mutedSites } = await browser.storage.local.get(MUTED_SITES_KEY);
        const isMuted = mutedSites.includes(hostname);
        
        // If the site is on our list, ensure it's muted.
        if (isMuted) {
            browser.tabs.update(tabId, { muted: true });
        }
    }
    // Always update the icon to reflect the status of the new URL.
    updateIcon(tabId, hostname);
  }
}, { properties: ["url"] });


// 3. Listen for when the active tab changes.
browser.tabs.onActivated.addListener(async (activeInfo) => {
  const tab = await browser.tabs.get(activeInfo.tabId);
  const hostname = getHostname(tab.url);
  updateIcon(tab.id, hostname);
});
