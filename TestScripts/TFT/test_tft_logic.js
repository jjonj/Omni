const fs = require('fs');
const path = require('path');

// Load the optimizer class
const TFTOptimizer = require('../../OmniSync.Web/www/js/tft_optimizer.js');

// Load data
const set16Path = path.join(__dirname, '../../OmniSync.Web/www/assets/tft/data/set16.json');
const set16Data = JSON.parse(fs.readFileSync(set16Path, 'utf8'));

console.log("Loaded Set 16 data:", set16Data.set_name);

const optimizer = new TFTOptimizer(set16Data.units, set16Data.trait_metadata);

async function runTests() {
    // Test Case 1: Level 6, Demacia Emblem
    console.log("\n--- Test Case 1: Level 6, Demacia Emblem, Must Include Garen ---");
    const pool6 = set16Data.units.filter(u => u.cost <= 3);
    
    const results = await optimizer.findBestBoards(pool6, 6, ["Demacia"], "Sona", 'default', {}, 3, null, 'super');

    if (results.results && results.results.length > 0) {
        console.log("Top Score:", results.results[0].score);
        console.log("Top Board:", results.results[0].board.map(u => u.name).join(", "));
        
        // Validation
        const boardNames = results.results[0].board.map(u => u.name);
        if (!boardNames.includes("Sona")) throw new Error("Must include unit missing!");
        console.log("PASS: Basic optimization returned results.");
    } else {
        throw new Error("No results found!");
    }

    // Test Case 2: Level 8, No Emblems
    console.log("\n--- Test Case 2: Level 8, No Emblems ---");
    const results8 = await optimizer.findBestBoards(set16Data.units, 8, [], [], 'default', {}, 3, null, 'super');
    if (results8.results && results8.results.length > 0) {
        console.log("Top Score:", results8.results[0].score);
        console.log("Top Board:", results8.results[0].board.map(u => u.name).join(", "));
        console.log("PASS: Level 8 optimization returned results.");
    } else {
        throw new Error("No results found for Level 8!");
    }
}

runTests().catch(err => {
    console.error(err);
    process.exit(1);
});
