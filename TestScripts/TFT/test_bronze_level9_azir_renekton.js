const TFTOptimizer = require('../../OmniSync.Web/www/js/tft_optimizer.js');
const { Set16RulesAddon, UnlockAddon } = require('../../OmniSync.Web/www/js/tft_addons.js');
const fs = require('fs');
const path = require('path');

const jsonPath = path.resolve(__dirname, '../../OmniSync.Web/www/assets/tft/data/set16.json');
const data = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));

const compRulesPath = path.resolve(__dirname, '../../OmniSync.Web/www/assets/tft/data/comp_rules.json');
const compRules = JSON.parse(fs.readFileSync(compRulesPath, 'utf8'));

const optimizer = new TFTOptimizer(data.units, data.trait_metadata);
optimizer.addAddon(new Set16RulesAddon(optimizer, compRules));
optimizer.addAddon(new UnlockAddon(optimizer));

async function runTest() {
    console.log("Running Bronze Level 9 Azir/Renekton Test...");
    
    const pool = data.units;
    const mustIncludeNames = ["Azir", "Renekton"];
    const targetSize = 9;
    const mode = 'bronze-for-life';
    const heuristic = 'super';

    const { candidates, neededSlots, fixedUnits } = optimizer.getCandidates(pool, targetSize, [], mustIncludeNames, {}, heuristic, mode);
    console.log("Top Candidates:", candidates.map(u => u.name).join(", "));

    const start = Date.now();
    const { results, totalProcessed } = await optimizer.findBestBoards(
        pool, 
        targetSize, 
        [], 
        mustIncludeNames, 
        mode, 
        {}, 
        10, 
        null, 
        heuristic
    );
    const duration = Date.now() - start;

    console.log(`Search completed in ${duration}ms. Processed ${totalProcessed} boards.`);

    if (results.length === 0) {
        console.error("FAIL: No results found.");
        process.exit(1);
    }

    const topResult = results[0];
    const boardNames = topResult.board.map(u => u.name);
    console.log("Top Result Board:", boardNames.join(", "));

    const activeTraits = Object.keys(topResult.counts).filter(t => {
        const traitInfo = data.trait_metadata[t];
        if (t === "Targon") return false;
        return traitInfo && traitInfo.breakpoints.some(b => b <= topResult.counts[t]);
    });

    console.log("Active Traits Count:", activeTraits.length);
    console.log("Active Traits:", activeTraits.join(", "));

    const targetComp = ["Briar", "Neeko", "Vi", "Gangplank", "Ambessa", "Renekton", "Azir", "Seraphine", "Swain"];
    const hasTargetComp = results.some(res => {
        const names = res.board.map(u => u.name);
        return targetComp.every(name => names.includes(name));
    });

    if (activeTraits.length >= 9) {
        console.log("PASS: Found a board with 9+ traits.");
    } else {
        console.error(`FAIL: Top board only has ${activeTraits.length} traits.`);
    }

    if (hasTargetComp) {
        console.log("SUCCESS: Target composition found in results.");
    } else {
        console.log("INFO: Target composition not found in results.");
    }

    console.log("\n--- Manual Scoring for Target Comp ---");
    const targetUnits = targetComp.map(name => data.units.find(u => u.name === name));
    const manualResult = optimizer.scoreBoard(targetUnits, [], 9, 'bronze-for-life', {}, mustIncludeNames);
    console.log("Target Comp Total Score:", manualResult.score);
    
    const activeTraitsManual = Object.keys(manualResult.counts).filter(t => {
        const traitInfo = data.trait_metadata[t];
        if (t === "Targon") return false;
        return traitInfo && traitInfo.breakpoints.some(b => b <= manualResult.counts[t]);
    });
    console.log("Active Traits Count:", activeTraitsManual.length);
    console.log("Active Traits:", activeTraitsManual.join(", "));
    console.log("Counts:", JSON.stringify(manualResult.counts));
}

runTest().catch(err => {
    console.error(err);
    process.exit(1);
});
