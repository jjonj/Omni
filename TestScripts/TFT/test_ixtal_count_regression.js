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
    console.log("\n--- Debug Ixtal Count Case ---");
    const level = 9;
    const pool = set16Data.units; 
    const emblems = [];
    const mustInclude = ["Neeko", "Milio", "Qiyana", "Vi", "Renekton", "Azir", "Brock"];
    const mode = 'ryze-unlock';
    const heuristic = 'super';

    console.log(`Running optimization: Level ${level}`);
    console.log(`Must Include: ${mustInclude.join(", ")}`);
    console.log(`Mode: ${mode}, Heuristic: ${heuristic}`);

    try {
        const { results } = await optimizer.findBestBoards(pool, level, emblems, mustInclude, mode, {}, 3, null, heuristic);

        if (results && results.length > 0) {
            console.log(`Found ${results.length} results.`);
            results.forEach((res, i) => {
                const ixtalCount = res.counts["Ixtal"] || 0;
                const boardNames = res.board.map(u => u.name);
                console.log(`
Result ${i + 1}:
`);
                console.log(`Score: ${res.score}`);
                console.log(`Ixtal Count: ${ixtalCount}`);
                console.log(`Board: ${boardNames.join(", ")}`);
                
                if (ixtalCount === 4) {
                    console.error("FAIL: Board has exactly 4 Ixtal units!");
                }
            });

            const has4Ixtal = results.some(res => (res.counts["Ixtal"] || 0) === 4);
            if (has4Ixtal) {
                console.error("\nTEST FAILED: Solver is still producing boards with 4 Ixtal.");
                process.exit(1);
            } else {
                console.log("\nTEST PASSED: No boards with 4 Ixtal found.");
            }
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
