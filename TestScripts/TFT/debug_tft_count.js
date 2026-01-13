const TFTOptimizer = require('../../OmniSync.Web/www/js/tft_optimizer.js');
const fs = require('fs');
const path = require('path');

const jsonPath = path.resolve(__dirname, '../../OmniSync.Web/www/assets/tft/data/set16.json');
const data = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
const optimizer = new TFTOptimizer(data.units, data.traits);

const pool = data.units.filter(u => u.cost <= 3);
const level = 4;
const results = optimizer.findBestBoards(pool, level, [], []);

console.log(`Level ${level} results:`);
results.forEach((res, i) => {
    console.log(`Option ${i+1}: ${res.board.length} units - ${res.board.map(u => u.name).join(', ')}`);
});