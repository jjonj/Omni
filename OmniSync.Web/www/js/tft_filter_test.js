/**
 * Reproduce filter issue: "demacia 7" fails.
 */
async function reproduceFilterIssue() {
    console.log("[Test] Reproducing filter issue: 'demacia 7'");
    
    // We need to wait for tftData to be loaded
    if (typeof tftData === 'undefined' || !tftData) {
        console.error("tftData not loaded yet. Please wait or load it first.");
        return;
    }

    const query = "demacia 7";
    console.log(`[Test] Filtering emblems with: "${query}"`);
    
    // Simulate what filterEmblems(query) does
    // function filterEmblems(query) {
    //    emblemSearch = query;
    //    renderEmblemPool();
    // }
    
    const originalEmblemSearch = typeof emblemSearch !== 'undefined' ? emblemSearch : "";
    
    try {
        // Run the filter
        filterEmblems(query);
        
        // Wait for render (though renderEmblemPool is synchronous)
        
        const originPool = document.getElementById('emblem-pool-origins');
        const classPool = document.getElementById('emblem-pool-classes');
        
        const originItems = originPool.querySelectorAll('.draggable-item');
        const classItems = classPool.querySelectorAll('.draggable-item');
        
        console.log(`[Test] Results: ${originItems.length} origins, ${classItems.length} classes`);
        
        let foundDemacia = false;
        originItems.forEach(item => {
            if (item.dataset.name.toLowerCase().includes("demacia")) {
                foundDemacia = true;
            }
        });
        
        if (!foundDemacia) {
            console.error("[Test] FAILED: Demacia emblem NOT found when filtering for 'demacia 7'");
        } else {
            console.log("[Test] SUCCESS: Demacia emblem found (unexpected if it fails for user)");
        }

    } catch (e) {
        console.error("[Test] Error during filter test:", e);
    } finally {
        // Restore
        filterEmblems(originalEmblemSearch);
    }
}

// Add to window for easy access
window.reproduceFilterIssue = reproduceFilterIssue;

// Auto-run if requested via URL param
if (window.location.search.includes('runtest=filter')) {
    window.addEventListener('load', () => {
        // Wait for tftData to be loaded by tft.js
        const checkData = setInterval(() => {
            if (typeof tftData !== 'undefined' && tftData) {
                clearInterval(checkData);
                setTimeout(reproduceFilterIssue, 1000);
            }
        }, 100);
    });
}
