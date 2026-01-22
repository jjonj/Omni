const fs = require('fs');
const path = require('path');

// Load the optimizer class
const TFTOptimizer = require('../../OmniSync.Web/www/js/tft_optimizer.js');

// Load data
const set16Path = path.join(__dirname, '../../OmniSync.Web/www/assets/tft/data/set16.json');
const set16Data = JSON.parse(fs.readFileSync(set16Path, 'utf8'));

const optimizer = new TFTOptimizer(set16Data.units, set16Data.trait_metadata);

function runTest() {
    console.log("\n--- Debug Must-Include Ixtal Count ---");
    
    // Units: Neeko (Ixtal), Milio (Ixtal), Qiyana (Ixtal)
    const neeko = set16Data.units.find(u => u.name === "Neeko");
    const milio = set16Data.units.find(u => u.name === "Milio");
    const qiyana = set16Data.units.find(u => u.name === "Qiyana");

    const board = [neeko, milio, qiyana];
    const emblems = [];
    
    console.log("Board:", board.map(u => u.name).join(", "));
    
    // Score the board
    const { counts } = optimizer.scoreBoard(board, emblems, 3, 'ryze-unlock');
    
    const ixtalCount = counts["Ixtal"] || 0;
    console.log("Calculated Ixtal Count:", ixtalCount);

    if (ixtalCount === 4) {
        console.error("FAIL: Ixtal count is 4, should be 3!");
        process.exit(1);
    } else if (ixtalCount === 3) {
        console.log("PASS: Ixtal count is 3.");
    } else {
        console.log("Count is:", ixtalCount);
    }
}

runTest();
