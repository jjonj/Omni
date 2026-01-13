const TFTOptimizer = require('../../OmniSync.Web/www/js/tft_optimizer.js');
const fs = require('fs');
const path = require('path');

const jsonPath = path.resolve(__dirname, '../../OmniSync.Web/www/assets/tft/data/set16.json');
const data = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
const optimizer = new TFTOptimizer(data.units, data.traits);

const nasus = data.units.find(u => u.name === "Nasus");
const renekton = data.units.find(u => u.name === "Renekton");
const board = [nasus, renekton];

// nasus: Shurima, Juggernaut
// renekton: Shurima, Juggernaut
// Juggernaut breakpoints: [2, 4, 6]
// Shurima: [3, 5, 7, 10] (wait, 3 is min?)

console.log("Nasus traits:", nasus.traits);
console.log("Renekton traits:", renekton.traits);

const scoreDefault = optimizer.scoreBoard(board, [], 8, 'default');
const scoreBronze = optimizer.scoreBoard(board, [], 8, 'bronze-for-life');

console.log("Default Score:", scoreDefault.score);
console.log("Bronze Score:", scoreBronze.score);
console.log("Counts:", JSON.stringify(scoreDefault.counts));
