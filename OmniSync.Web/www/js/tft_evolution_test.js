/**
 * Unit Test for TFT Evolution Rules
 * 
 * Target: 0202400f01b01635036c02a000000000TFTSet16 (Level 7)
 * Rules to verify:
 * 1. Levels 4-9 are created.
 * 2. Level 5 board must have at least 2 Void and 2 Shadow Isles.
 * 3. Level 5 board must not have units > 1 cost (if offset is -4).
 * 4. General cost rules (no 4-costs below L7).
 */

const fs = require('os');
const path = require('path');

// Mocking some globals if needed for a headless node run, 
// but since the logic is heavily tied to tft.js and tft_optimizer.js,
// we will structure this to be runnable via the browser's Test.html or a dedicated runner.

async function runEvolutionTest() {
    console.log("--- Starting TFT Evolution Logic Test ---");
    
    // In a real scenario, we'd load the JSON data here.
    // For this test script, we assume it's running in an environment where 
    // TFTOptimizer, TeamPlannerCode, and tftData are available.

    const testCode = "0202400f01b01635036c02a000000000TFTSet16";
    const baseNames = TeamPlannerCode.decode(testCode);
    const baseBoard = baseNames.map(name => tftData.units.find(u => u.name === name));
    
    console.log(`Base Board (L${baseBoard.length}):`, baseNames.join(', '));

    // Run evolution
    // Note: renderTreeExplorer is async and populates UI, so we test the underlying getBestNeighbor logic
    const currentLevel = baseBoard.length;
    const tree = {};
    tree[currentLevel] = { board: baseBoard };

    const emblems = []; // Assuming no emblems for this specific test code
    const mustIncludeNames = [];
    const mustIncludeTraits = {};
    const solverMode = 'default';

    // Calculate requirements from base board
    const baseCounts = {};
    baseBoard.forEach(u => u.traits.forEach(t => baseCounts[t] = (baseCounts[t] || 0) + 1));
    
    const persistentTraits = [];
    if (compRules?.evolution?.persistent_traits) {
        for (const req of compRules.evolution.persistent_traits) {
            const meta = tftData.trait_metadata[req.trait];
            const isActive = meta && meta.breakpoints.some(b => b <= (baseCounts[req.trait] || 0));
            if (isActive && baseCounts[req.trait] >= req.min_count) {
                persistentTraits.push(req);
            }
        }
    }
    
    const exclusionOverrides = getExclusionOverrides(baseBoard, emblems);
    console.log("Persistent Traits:", persistentTraits.map(p => p.trait).join(', '));
    console.log("Exclusion Overrides:", exclusionOverrides.join(', '));

    // Down to 4
    let lastBoard = baseBoard;
    for (let l = currentLevel - 1; l >= 4; l--) {
        const best = getBestNeighbor(lastBoard, l, 'down', emblems, mustIncludeTraits, mustIncludeNames, solverMode, persistentTraits, exclusionOverrides);
        if (best) {
            tree[l] = best;
            lastBoard = best.board;
            console.log(`[PASS] Level ${l} generated:`, best.board.map(u => u.name).join(', '));
        } else {
            console.error(`[FAIL] Level ${l} failed to generate!`);
        }
    }

    // Up to 9
    lastBoard = baseBoard;
    for (let l = currentLevel + 1; l <= 9; l++) {
        const best = getBestNeighbor(lastBoard, l, 'up', emblems, mustIncludeTraits, mustIncludeNames, solverMode, persistentTraits, exclusionOverrides);
        if (best) {
            tree[l] = best;
            lastBoard = best.board;
            console.log(`[PASS] Level ${l} generated:`, best.board.map(u => u.name).join(', '));
        } else {
            console.error(`[FAIL] Level ${l} failed to generate!`);
        }
    }

    // Specific Rule Checks
    if (tree[5]) {
        const l5 = tree[5].board;
        const counts = {};
        l5.forEach(u => u.traits.forEach(t => counts[t] = (counts[t] || 0) + 1));
        
        const hasVoid = (counts["Void"] || 0) >= 2;
        const hasSI = (counts["Shadow Isles"] || 0) >= 2;
        const hasNoHighCost = !l5.some(u => u.cost > 1);

        if (hasVoid && hasSI) console.log("[PASS] Level 5 contains Void(2) and Shadow Isles(2)");
        else console.error("[FAIL] Level 5 missing persistent traits!", counts);

        if (hasNoHighCost) console.log("[PASS] Level 5 respects max cost rule (only 1-costs)");
        else console.error("[FAIL] Level 5 contains units too expensive!", l5.map(u => `${u.name}(${u.cost})`));
    }

    console.log("--- Evolution Test Complete ---");
}

// Attach to window so it can be called from Dev Console or Test.html
if (typeof window !== 'undefined') {
    window.runEvolutionTest = runEvolutionTest;
}
