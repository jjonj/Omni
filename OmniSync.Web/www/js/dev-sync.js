/**
 * dev-sync.js
 * Extensionless Browser Refresh for Omni Assistant
 */

(function() {
    const HUB_URL = "http://localhost:5000/signalrhub";
    const API_KEY = "test_api_key"; // Match Hub's AuthApiKey

    let connection = null;

    async function start() {
        if (typeof signalR === 'undefined') {
            console.warn("[DevSync] SignalR not found yet, retrying in 500ms...");
            setTimeout(start, 500);
            return;
        }

        if (connection) return;

        connection = new signalR.HubConnectionBuilder()
            .withUrl(HUB_URL)
            .withAutomaticReconnect()
            .build();

        connection.on("ReceiveDevRefresh", (url) => {
            const currentUrl = window.location.href;
            console.log("[DevSync] REFRESH RECEIVED. Target:", url || "(active)", "Current:", currentUrl);

            if (!url || url.length === 0) {
                if (currentUrl.includes("localhost") || currentUrl.includes("127.0.0.1")) {
                    console.log("[DevSync] Refreshing active local page.");
                    window.location.reload();
                } else {
                    console.log("[DevSync] Not refreshing: not a local page.");
                }
                return;
            }

            const isMatch = url && currentUrl.toLowerCase().indexOf(url.toLowerCase()) !== -1;
            if (isMatch) {
                console.log("[DevSync] MATCH! Refreshing...");
                window.location.reload();
            } else {
                console.log("[DevSync] NO MATCH. Current URL:", currentUrl, "Target:", url);
            }
        });

        connection.on("ReceiveBrowserCommand", (command, url, newTab) => {
            if (command === "Refresh") {
                if (!url || window.location.href.includes(url)) {
                    console.log("[DevSync] Refreshing via ReceiveBrowserCommand.");
                    window.location.reload();
                }
            }
        });

        try {
            await connection.start();
            console.log("[DevSync] Connected to Hub at " + HUB_URL);
            const success = await connection.invoke("Authenticate", API_KEY);
            if (success) {
                console.log("[DevSync] Authenticated successfully.");
            } else {
                console.warn("[DevSync] Authentication failed.");
            }
        } catch (err) {
            console.error("[DevSync] Connection failed, retrying in 5s...", err);
            setTimeout(() => { connection = null; start(); }, 5000);
        }
    }

    // Start attempting to connect
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
})();
