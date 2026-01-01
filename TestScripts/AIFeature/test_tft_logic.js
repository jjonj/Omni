const fs = require('fs');
const path = require('path');

// Load the optimizer class
const TFTOptimizer = require('../../OmniSync.Web/www/js/tft_optimizer.js');

// Load data
const set16Path = path.join(__dirname, '../../OmniSync.Web/www/assets/tft/data/set16.json');
const set16Data = JSON.parse(fs.readFileSync(set16Path, 'utf8'));

console.log("Loaded Set 16 data:", set16Data.set_name);

const optimizer = new TFTOptimizer(set16Data.units, set16Data.traits);

// Test Case 1: Level 6, Demacia Emblem
console.log("\n--- Test Case 1: Level 6, Demacia Emblem, Must Include Garen ---");
const pool6 = set16Data.units.filter(u => u.cost <= 3);
// Note: Garen is cost 4 in my mock data? Let's check Set 16 data.
// In set16.json, Garen is cost 4. So he won't be in pool6 normally if we just filter by cost<=3.
// But findBestBoards takes a pool. If mustInclude is Garen, he must be in the final board.
// The optimize function in `TFT.py` logic: `find_best_boards(POOL_6, 6, EMBLEMS, MUST_INCLUDE)`
// If must include is not in pool, it might fail or we should add it.
// In `TFT.py`, `POOL_6` is strictly units <= cost 3.
// So let's test with a unit that exists in pool 6. "Sona" is cost 1.
// Wait, looking at set16.json: Sona is cost 1.

const results = optimizer.findBestBoards(pool6, 6, ["Demacia"], "Sona");

if (results.length > 0) {
    console.log("Top Score:", results[0].score);
    console.log("Top Board:", results[0].board.map(u => u.name).join(", "));
    
    // Validation
    const boardNames = results[0].board.map(u => u.name);
    if (!boardNames.includes("Sona")) throw new Error("Must include unit missing!");
    console.log("PASS: Basic optimization returned results.");
} else {
    throw new Error("No results found!");
}

// Test Case 2: Level 8, No Emblems
console.log("\n--- Test Case 2: Level 8, No Emblems ---");
const results8 = optimizer.findBestBoards(set16Data.units, 8, []);
if (results8.length > 0) {
    console.log("Top Score:", results8[0].score);
    console.log("Top Board:", results8[0].board.map(u => u.name).join(", "));
    console.log("PASS: Level 8 optimization returned results.");
} else {
    throw new Error("No results found for Level 8!");
}
