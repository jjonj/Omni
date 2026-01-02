const TFTOptimizer = require('../../OmniSync.Web/www/js/tft_optimizer.js');
const fs = require('fs');
const path = require('path');

const jsonPath = path.resolve(__dirname, '../../OmniSync.Web/www/assets/tft/data/set16.json');
const data = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
const optimizer = new TFTOptimizer(data.units, data.traits);

const board = data.units.filter(u => u.name === "Kennen" || u.name === "Kobuko & Yuumi" || u.name === "Anivia" || u.name === "Blitzcrank");
console.log("Board Units:", board.map(u => u.name));
console.log("Board Costs:", board.map(u => u.cost));

const scoreResult = optimizer.scoreBoard(board, [], 4);
console.log("Level 4 Score for 2x 3-cost:", scoreResult.score);
