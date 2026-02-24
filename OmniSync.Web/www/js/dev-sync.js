/**
 * dev-sync.js
 * Extensionless Browser Refresh for Omni Assistant
 * 
 * To use: Include this script and signalr.min.js in your HTML.
 * <script src="/js/signalr.min.js"></script>
 * <script src="/js/dev-sync.js"></script>
 */

(function() {
    const HUB_URL = "/signalrhub";
    const API_KEY = "test_api_key"; // Match Hub's AuthApiKey

    let connection = new signalR.HubConnectionBuilder()
        .withUrl(HUB_URL)
        .withAutomaticReconnect()
        .build();

    async function start() {
        try {
            await connection.start();
            console.log("[DevSync] Connected to Hub at " + HUB_URL);
            const success = await connection.invoke("Authenticate", API_KEY);
            if (success) {
                console.log("[DevSync] Authenticated successfully.");
            } else {
                console.warn("[DevSync] Authentication failed with provided API key.");
            }
        } catch (err) {
            console.error("[DevSync] Connection failed, retrying in 5s...", err);
            setTimeout(start, 5000);
        }
    }

    connection.on("ReceiveDevRefresh", (url) => {
        const currentUrl = window.location.href;
        console.log("[DevSync] ReceiveDevRefresh event received. Target URL filter:", url || "(none)");

        // If no URL is specified, refresh if we are on localhost (dev safety check)
        if (!url || url.length === 0) {
            if (currentUrl.includes("localhost") || currentUrl.includes("127.0.0.1")) {
                console.log("[DevSync] Refreshing active local page.");
                window.location.reload();
            }
            return;
        }

        // If URL is specified, only refresh if it matches
        if (currentUrl.includes(url)) {
            console.log("[DevSync] Refreshing page as it matches target:", url);
            window.location.reload();
        }
    });

    // Also handle general browser commands if they target this URL
    connection.on("ReceiveBrowserCommand", (command, url, newTab) => {
        if (command === "Refresh") {
            if (!url || window.location.href.includes(url)) {
                console.log("[DevSync] Refreshing via ReceiveBrowserCommand.");
                window.location.reload();
            }
        }
    });

    connection.onclose(async () => {
        console.log("[DevSync] Connection closed.");
    });

    // Start connection
    if (typeof signalR !== 'undefined') {
        start();
    } else {
        console.error("[DevSync] SignalR not found. Please include signalr.min.js before dev-sync.js");
    }
})();
