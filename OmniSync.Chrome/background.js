importScripts('signalr.min.js');

const HUB_URL = "http://10.0.0.37:5000/signalrhub";
const API_KEY = "test_api_key";

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
    }
});

start();
