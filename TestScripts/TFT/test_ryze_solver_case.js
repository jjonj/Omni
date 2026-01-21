const fs = require('fs');
const path = require('path');

// Load the optimizer class
const TFTOptimizer = require('../../OmniSync.Web/www/js/tft_optimizer.js');

// Load data
const set16Path = path.join(__dirname, '../../OmniSync.Web/www/assets/tft/data/set16.json');
const set16Data = JSON.parse(fs.readFileSync(set16Path, 'utf8'));

console.log("Loaded Set 16 data:", set16Data.set_name);

const optimizer = new TFTOptimizer(set16Data.units, set16Data.trait_metadata);

async function runTest() {
    console.log("\n--- Debug Ryze Solver Case ---");
    const level = 9;
    // At level 9, we usually consider all units. 
    // Sometimes user filters pool by cost, but let's assume full pool first.
    const pool = set16Data.units; 
    const emblems = [];
    const mustInclude = ["Brock", "Azir", "Renekton", "Neeko", "Milio", "Taric"];
    const mode = 'ryze-unlock';
    const heuristic = 'super';

    console.log(`Running optimization: Level ${level}`);
    console.log(`Must Include: ${mustInclude.join(", ")}`);
    console.log(`Mode: ${mode}, Heuristic: ${heuristic}`);

    try {
        const { results } = await optimizer.findBestBoards(pool, level, emblems, mustInclude, mode, {}, 3, null, heuristic);

        if (results && results.length > 0) {
            console.log("SUCCESS: Results found!");
            console.log("Top Score:", results[0].score);
            console.log("Top Board:", results[0].board.map(u => u.name).join(", "));
            
            const boardNames = results[0].board.map(u => u.name);
            const missing = mustInclude.filter(name => !boardNames.includes(name));
            
            if (missing.length > 0) {
                console.error("FAIL: Missing must-include units:", missing.join(", "));
                process.exit(1);
            }
            
            console.log("PASS: All must-include units are present.");
        } else {
            console.error("FAIL: No results found!");
            process.exit(1);
        }
    } catch (error) {
        console.error("CRASH: Optimizer threw an error!");
        console.error(error);
        process.exit(1);
    }
}

runTest();
