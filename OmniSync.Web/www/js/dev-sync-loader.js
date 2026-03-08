/**
 * dev-sync-loader.js
 * Centralized loader for Omni DevSync (SignalR Refresh).
 */
(function() {
    // We use absolute paths from the root to ensure it works from any depth
    const scripts = [
        '/js/signalr.min.js',
        '/js/dev-sync.js'
    ];

    function loadScript(src) {
        return new Promise((resolve, reject) => {
            const s = document.createElement('script');
            // Ensure we use the absolute path from the domain root
            s.src = src.startsWith('/') ? src : '/' + src;
            s.onload = () => {
                console.log('[DevSyncLoader] Loaded:', src);
                resolve();
            };
            s.onerror = (e) => {
                console.error('[DevSyncLoader] Error loading:', src, e);
                reject(e);
            };
            
            const target = document.head || document.documentElement;
            target.appendChild(s);
        });
    }

    async function init() {
        console.log('[DevSyncLoader] Initializing DevSync...');
        for (const src of scripts) {
            try {
                await loadScript(src);
            } catch (e) {
                // If one fails, try to continue or report
                console.error('[DevSyncLoader] Critical load failure for ' + src);
            }
        }
    }

    // Only run on localhost
    const isLocal = window.location.hostname === 'localhost' || 
                    window.location.hostname === '127.0.0.1' || 
                    window.location.hostname.startsWith('192.168.');

    if (isLocal) {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', init);
        } else {
            init();
        }
    } else {
        console.log('[DevSyncLoader] Not a local environment, skipping DevSync.');
    }
})();
