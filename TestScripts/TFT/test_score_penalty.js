const TFTOptimizer = require('../../OmniSync.Web/www/js/tft_optimizer.js');
const fs = require('fs');
const path = require('path');

const jsonPath = path.resolve(__dirname, '../../OmniSync.Web/www/assets/tft/data/set16.json');
const data = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
const optimizer = new TFTOptimizer(data.units, data.trait_metadata);

async function runTest() {
    console.log("\n--- Testing Bronze For Life: Targon Exclusion ---");
    
    const aphelios = data.units.find(u => u.name === "Aphelios"); // Targon
    const taric = data.units.find(u => u.name === "Taric"); // Targon
    const carry = data.units.find(u => u.is_carry && u.cost >= 4);
    
    // Board with only Targon units + carry
    const board = [aphelios, taric, carry];
    
    // Normal mode score (should include Targon bonus)
    const resDefault = optimizer.scoreBoard(board, [], 8, 'default');
    console.log("Default Score:", resDefault.score);
    
    // Bronze mode score (should EXCLUDE Targon bonus)
    const resBronze = optimizer.scoreBoard(board, [], 8, 'bronze-for-life');
    console.log("Bronze Score:", resBronze.score);
    
    // Verify Targon isn't giving points in Bronze
    // Each other trait breakpoint normally gives 1000 in Bronze (multiplier)
    // If Targon was counted, score would be higher.
    
    const baseScore = carry.cost * 10 + aphelios.cost * 10 + taric.cost * 10;
    // Note: scoreBoard uses -10 or +10 based on level. at level 8 it is +10.
    
    console.log("Is Targon in counts?", resBronze.counts["Targon"]);
    
    if (resBronze.score > 5000) { // If breakpoints were counted, score would be thousands
         console.error("FAIL: Bronze score seems to include Targon or other breakpoints unexpectedly");
    } else {
         console.log("PASS: Bronze score is low as expected (Targon excluded from scoring)");
    }
}

runTest();