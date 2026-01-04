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
    console.log("\n--- Running Test: Rune Solver Heuristic + Level 5 + Yordle & Void Emblems ---");
    const pool = set16Data.units.filter(u => u.cost <= 3);
    const mustIncludeNames = [];
    const emblems = ["Yordle", "Void"];
    
    // findBestBoards(pool, size, emblems, mustIncludeNames, mode, mustIncludeTraits, limit, onProgress, heuristic)
    const { results } = await optimizer.findBestBoards(
        pool, 
        5, 
        emblems, 
        mustIncludeNames, 
        'world-runes', 
        {}, 
        3, 
        null, 
        'super' // Note: findBestBoards now internal routes 'world-runes' to runeSearch
    );
    
    if (results.length === 0) {
        console.error("FAIL: Rune solver found no boards");
        process.exit(1);
    }
    
    const res = results[0];
    const activeOrigins = Object.keys(res.counts).filter(t => {
        const meta = set16Data.trait_metadata[t];
        if (!meta || meta.type !== 'origin') return false;
        if (t === 'Targon') return res.counts[t] >= 1;
        return meta.breakpoints.some(b => b <= res.counts[t]);
    });

    console.log("Top Board:", res.board.map(u => u.name).join(", "));
    console.log("Active Origins:", activeOrigins.join(', '));
    console.log("Origin Count:", activeOrigins.length);
    console.log("Score:", res.score);

    if (activeOrigins.length < 4) {
        console.error(`FAIL: World Runes failed: Only ${activeOrigins.length} origins active.`);
        process.exit(1);
    }

    console.log("PASS: World Runes requirement met at Level 5 with Yordle/Void emblems!");

    console.log("\n--- Running Test: Rune Solver Heuristic + Level 5 + Piltover & Demacia Emblems + Sona ---");
    const emblems2 = ["Piltover", "Demacia"];
    const mustIncludeNames2 = ["Sona"];
    
    const { results: results2 } = await optimizer.findBestBoards(
        pool, 
        5, 
        emblems2, 
        mustIncludeNames2, 
        'world-runes', 
        {}, 
        1, 
        null, 
        'super'
    );
    
    if (results2.length === 0) {
        console.error("FAIL: Rune solver found no boards for Piltover/Demacia/Sona");
        process.exit(1);
    }
    
    const res2 = results2[0];
    const activeOrigins2 = optimizer.getActiveOrigins(res2.counts);

    console.log("Top Board:", res2.board.map(u => u.name).join(", "));
    console.log("Active Origins:", activeOrigins2.join(', '));
    console.log("Origin Count:", activeOrigins2.length);
    console.log("Score:", res2.score);

    if (activeOrigins2.length < 4) {
        console.error(`FAIL: World Runes failed: Only ${activeOrigins2.length} origins active.`);
        process.exit(1);
    }

    if (!res2.board.some(u => u.name === "Sona")) {
        console.error("FAIL: Sona missing from board");
        process.exit(1);
    }

    console.log("PASS: World Runes requirement met at Level 5 with Piltover/Demacia/Sona!");
}
runTest().catch(err => {
    console.error(err);
    process.exit(1);
});
