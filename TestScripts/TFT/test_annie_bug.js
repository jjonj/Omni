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
    console.log("\n--- Reproduce Annie in Must Include Bug ---");
    const level = 8;
    const pool = set16Data.units; 
    const emblems = [];
    const mustInclude = ["Annie", "Tibbers"];

    console.log(`Running optimization: Level ${level}, Must Include: ${mustInclude.join(", ")}`);

    try {
        const { results } = await optimizer.findBestBoards(pool, level, emblems, mustInclude, 'default', {}, 3, null, 'super');

        if (results && results.length > 0) {
            console.log("SUCCESS: Results found!");
            console.log("Top Board:", results[0].board.map(u => u.name).join(", "));
            
            const boardNames = results[0].board.map(u => u.name);
            if (!boardNames.includes("Annie")) {
                console.error("FAIL: Annie missing from result!");
                process.exit(1);
            }
            if (!boardNames.includes("Tibbers")) {
                console.error("FAIL: Tibbers missing from result!");
                process.exit(1);
            }
            console.log("PASS: Both Annie and Tibbers are in the board.");
        } else {
            console.error("FAIL: No results found for Annie in must include!");
            process.exit(1);
        }
    } catch (error) {
        console.error("CRASH: Optimizer threw an error!");
        console.error(error);
        process.exit(1);
    }
}

runTest();