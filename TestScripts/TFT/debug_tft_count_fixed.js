const TFTOptimizer = require('../../OmniSync.Web/www/js/tft_optimizer.js');
const fs = require('fs');
const path = require('path');

const jsonPath = path.resolve(__dirname, '../../OmniSync.Web/www/assets/tft/data/set16.json');
const data = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
const optimizer = new TFTOptimizer(data.units, data.traits);

const pool = data.units.filter(u => u.cost <= 3);
const level = 5;
const mustInclude = ["Anivia"]; // Cost 1
const results = optimizer.findBestBoards(pool, level, [], mustInclude);

console.log(`Level ${level} with Must Include ${mustInclude.join(', ')} results:`);
results.forEach((res, i) => {
    const totalSlots = res.board.reduce((acc, u) => acc + (u.slots || 1), 0);
    console.log(`Option ${i+1}: ${res.board.length} units, ${totalSlots} total slots - ${res.board.map(u => u.name).join(', ')}`);
});
