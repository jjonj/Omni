const TFTOptimizer = require('../../OmniSync.Web/www/js/tft_optimizer.js');
const fs = require('fs');
const path = require('path');

const jsonPath = path.resolve(__dirname, '../../OmniSync.Web/www/assets/tft/data/set16.json');
const data = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
const optimizer = new TFTOptimizer(data.units, data.traits);

const level = 4;
const pool = data.units.filter(u => u.cost <= 3);
const results = optimizer.findBestBoards(pool, level, [], []);

console.log(`Level ${level} Results:`);
results.forEach((res, i) => {
    const counts = res.board.reduce((acc, u) => {
        acc[u.cost] = (acc[u.cost] || 0) + 1;
        return acc;
    }, {});
    console.log(`Option ${i+1}: Score=${res.score}, Units=[${res.board.map(u => u.name).join(', ')}], CostCounts=${JSON.stringify(counts)}`);
});
