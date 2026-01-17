/**
 * Test for Smart Sort Visibility
 */
async function testSmartSortVisibility() {
    console.log("[Test] Testing Smart Sort Visibility...");
    
    if (typeof tftData === 'undefined' || !tftData) {
        console.error("tftData not loaded yet.");
        return;
    }

    // Find a common unit like "Garen" or just the first one
    const testUnit = tftData.units.find(u => u.name === "Garen") || tftData.units[0];
    console.log(`[Test] Using unit: ${testUnit.name}`);

    // 1. Enable Smart Sort
    toggleUnitSortMode(true);
    console.log("[Test] Smart Sort Enabled.");

    // 2. Clear selections and set active zone
    selectedMustInclude = [];
    selectedCurrentTeam = [];
    setActiveZone('must-include');
    renderUnitPools();

    // 3. Verify unit is visible initially (as a .unit-node in a .unit-pool)
    let poolItems = Array.from(document.querySelectorAll('.unit-pool .unit-node'));
    let foundItem = poolItems.find(item => item.querySelector('.item-name')?.innerText === testUnit.name);
    
    if (!foundItem) {
        console.error("[Test] Unit not found in pool initially!");
        return;
    }
    console.log("[Test] Unit visible initially.");

    // 4. Add unit to active zone
    addToMustInclude(testUnit);
    console.log("[Test] Unit added to Must Include.");

    // 5. Render pools (should hide the unit if implemented)
    renderUnitPools();

    // 6. Check if it's still in the pool
    poolItems = Array.from(document.querySelectorAll('.unit-pool .unit-node'));
    foundItem = poolItems.find(item => item.querySelector('.item-name')?.innerText === testUnit.name);

    if (foundItem) {
        console.error("[Test] FAIL: Unit still visible in pool after selection in Smart Sort mode!");
    } else {
        console.log("[Test] SUCCESS: Unit hidden from pool.");
    }
    
    // Cleanup
    toggleUnitSortMode(false);
    selectedMustInclude = selectedMustInclude.filter(u => u.name !== testUnit.name);
    renderUnitPools();
    renderSelectionZones();
}

window.testSmartSortVisibility = testSmartSortVisibility;
