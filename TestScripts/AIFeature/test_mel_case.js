const TFTOptimizer = require('../../OmniSync.Web/www/js/tft_optimizer.js');
const fs = require('fs');
const path = require('path');

const jsonPath = path.resolve(__dirname, '../../OmniSync.Web/www/assets/tft/data/set16.json');
const data = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));
const optimizer = new TFTOptimizer(data.units, data.traits);

async function test() {
    const level = 6;
    const mustInclude = ["Mel"]; // 5-cost unit
    
    console.log(`--- Testing Level ${level} with Must Include: ${mustInclude.join(', ')} ---`);
    
    // Simulate the pool filtering logic from tft.js
    const pool = data.units.filter(u => {
        if (level <= 6 && u.cost > 3) return false;
        // The rule: 5-costs only at lvl 8+
        if (u.cost === 5 && level < 8) return false;
        return true;
    });

    console.log(`Pool size (filtered for Lvl ${level}): ${pool.length}`);
    
    const results = await optimizer.findBestBoards(pool, level, [], mustInclude);
    
    console.log(`Found ${results.length} results.`);
    if (results.length > 0) {
        results.forEach((res, i) => {
            console.log(`Result ${i+1}: Score ${res.score}, Units: ${res.board.map(u => u.name).join(', ')}`);
        });
    } else {
        console.log("No valid compositions found (Expected behavior for 5-cost on Lvl 6).");
    }
}

test();
