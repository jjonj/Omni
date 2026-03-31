const fs = require('fs');
const path = require('path');

// Load the optimizer class
const TFTOptimizer = require('../../OmniSync.Web/www/js/tft_optimizer.js');
const { Set16RulesAddon, UnlockAddon } = require('../../OmniSync.Web/www/js/tft_addons.js');

// Load data
const set16Path = path.join(__dirname, '../../OmniSync.Web/www/assets/tft/data/set16.json');
const set16Data = JSON.parse(fs.readFileSync(set16Path, 'utf8'));

const compRulesPath = path.join(__dirname, '../../OmniSync.Web/www/assets/tft/data/comp_rules.json');
const compRules = JSON.parse(fs.readFileSync(compRulesPath, 'utf8'));

console.log("Loaded Set 16 data:", set16Data.set_name);

const optimizer = new TFTOptimizer(set16Data.units, set16Data.trait_metadata);
optimizer.addAddon(new Set16RulesAddon(optimizer, compRules));
optimizer.addAddon(new UnlockAddon(optimizer));

async function runTests() {
    // Test Case 1: Level 6, Demacia Emblem
    console.log("\n--- Test Case 1: Level 6, Demacia Emblem, Must Include Garen ---");
    const pool6 = set16Data.units.filter(u => u.cost <= 3);
    
    // We use expandMustInclude to handle requirements like Tibbers/Annie if they were set up that way
    const mustInclude = optimizer.expandMustInclude(["Garen"]);
    const results = await optimizer.findBestBoards(pool6, 6, ["Demacia"], mustInclude, 'default', {}, 3, null, 'super');

    if (results.results && results.results.length > 0) {
        console.log("Top Score:", results.results[0].score);
        console.log("Top Board:", results.results[0].board.map(u => u.name).join(", "));
        
        // Validation
        const boardNames = results.results[0].board.map(u => u.name);
        if (!boardNames.includes("Garen")) throw new Error("Must include unit missing!");
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

    // Test Case 3: Unlock System (Neeko requires Nidalee or vice versa)
    console.log("\n--- Test Case 3: Unlock System (Tibbers requires Annie) ---");
    const tibbers = set16Data.units.find(u => u.name === "Tibbers");
    const annie = set16Data.units.find(u => u.name === "Annie");
    
    if (tibbers && annie) {
        // Board with Tibbers but no Annie should be penalized heavily
        const badBoard = [tibbers, ...set16Data.units.slice(0, 7)];
        const resBad = optimizer.scoreBoard(badBoard, [], 8);
        console.log("Tibbers alone score:", resBad.score);
        
        const goodBoard = [tibbers, annie, ...set16Data.units.slice(0, 6)];
        const resGood = optimizer.scoreBoard(goodBoard, [], 8);
        console.log("Tibbers + Annie score:", resGood.score);
        
        if (resBad.score >= resGood.score) throw new Error("Unlock system penalty not applied!");
        console.log("PASS: Unlock system penalty verified.");
    }
}

runTests().catch(err => {
    console.error(err);
    process.exit(1);
});
