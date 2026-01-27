const fs = require('fs');
const path = require('path');
const TFTOptimizer = require('../../OmniSync.Web/www/js/tft_optimizer.js');

const set16Path = path.join(__dirname, '../../OmniSync.Web/www/assets/tft/data/set16.json');
const set16Data = JSON.parse(fs.readFileSync(set16Path, 'utf8'));

// Filter out disabled units
const disabledUnitsPath = path.join(__dirname, '../../OmniSync.Web/www/assets/tft/data/default_disabled.json');
const disabledUnits = JSON.parse(fs.readFileSync(disabledUnitsPath, 'utf8'));
const activeUnits = set16Data.units.filter(u => !disabledUnits.includes(u.name));

const optimizer = new TFTOptimizer(activeUnits, set16Data.trait_metadata);

async function runTest() {
    console.log("--- Test: Level 8 with Rek'Sai and 4 Piltover (Active Units Only) ---");
    const mustIncludeNames = ["Rek'Sai"];
    const mustIncludeTraits = { "Piltover": 4 };
    const level = 8;
    
    console.log("Starting optimization (Super Heuristic)...");
    const startTime = Date.now();
    
    const results = await optimizer.findBestBoards(
        activeUnits, 
        level, 
        [], 
        mustIncludeNames, 
        'default', 
        mustIncludeTraits,
        3, // limit
        null, // onProgress
        'super' // heuristic
    );

    const duration = (Date.now() - startTime) / 1000;
    console.log(`Optimization finished in ${duration.toFixed(2)}s`);

    if (results.results && results.results.length > 0) {
        const topBoard = results.results[0];
        console.log("Top Score:", topBoard.score);
        console.log("Board:", topBoard.board.map(u => u.name).join(", "));
        console.log("Traits:", JSON.stringify(topBoard.counts));
        
        const boardNames = topBoard.board.map(u => u.name);
        if (!boardNames.includes("Rek'Sai")) {
            console.error("FAIL: Missing Rek'Sai!");
            process.exit(1);
        }
        if ((topBoard.counts["Piltover"] || 0) < 4) {
            console.error("FAIL: Piltover trait not met!");
            process.exit(1);
        }
        
        console.log("PASS: Found valid board with Rek'Sai and 4 Piltover.");
    } else {
        console.error("FAIL: No results found.");
        process.exit(1);
    }
}

runTest().catch(err => {
    console.error("ERROR during test execution:");
    console.error(err);
    process.exit(1);
});
