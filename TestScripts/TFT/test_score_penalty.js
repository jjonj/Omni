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
    const carry1 = data.units.find(u => u.is_carry && u.cost === 4);
    const carry2 = data.units.find(u => u.is_carry && u.cost === 5);
    const carry3 = data.units.find(u => u.is_carry && u.cost === 4 && u.name !== carry1.name);
    
    // Board with only Targon units + 3 carries
    const board = [aphelios, taric, carry1, carry2, carry3];
    const targetSize = 9;
    
    // Normal mode score (should include Targon bonus)
    const resDefault = optimizer.scoreBoard(board, [], targetSize, 'default');
    console.log("Default Score:", resDefault.score);
    
    // Bronze mode score (should EXCLUDE Targon bonus)
    const resBronze = optimizer.scoreBoard(board, [], targetSize, 'bronze-for-life');
    console.log("Bronze Score:", resBronze.score);
    console.log("Bronze Counts:", JSON.stringify(resBronze.counts));
    
    console.log("Is Targon in counts?", resBronze.counts["Targon"]);
    
    // Base cost score: (2 + 4 + 4 + 5 + 4) * 10 = 190
    // Carries: 3 carries at level 9 = no penalty.
    // Guidance for carries on partial board (5 < 9): carryCount * 2000 + highCostCarryCount * 3000
    // 3 carries, 3 high cost carries: 3 * 2000 + 3 * 3000 = 6000 + 9000 = 15000.
    // Total expected roughly 15190 + other active non-bronze traits.
    
    if (resBronze.score > 20000) { 
         console.error("FAIL: Bronze score seems to include Targon or other breakpoints unexpectedly");
    } else {
         console.log("PASS: Bronze score is low as expected (Targon excluded from scoring)");
    }
}

runTest();
