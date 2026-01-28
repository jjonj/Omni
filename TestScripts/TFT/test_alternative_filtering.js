const fs = require('fs');
const path = require('path');
const TFTOptimizer = require('../../OmniSync.Web/www/js/tft_optimizer.js');

const set16Path = path.join(__dirname, '../../OmniSync.Web/www/assets/tft/data/set16.json');
const set16Data = JSON.parse(fs.readFileSync(set16Path, 'utf8'));

// Filter out disabled units for realism
const disabledUnitsPath = path.join(__dirname, '../../OmniSync.Web/www/assets/tft/data/default_disabled.json');
const disabledUnits = JSON.parse(fs.readFileSync(disabledUnitsPath, 'utf8'));
const activeUnits = set16Data.units.filter(u => !disabledUnits.includes(u.name));

const optimizer = new TFTOptimizer(activeUnits, set16Data.trait_metadata);

async function runTest() {
    console.log("--- Test: Alternative Filtering (Ryze, 4 Bruiser, 3 Freljord) ---");
    const level = 9;
    const mustIncludeNames = ["Ryze"];
    const mustIncludeTraits = { "Bruiser": 4, "Freljord": 3 };
    
    console.log("Starting optimization...");
    const { results } = await optimizer.findBestBoards(
        activeUnits, 
        level, 
        [], 
        mustIncludeNames, 
        'default', 
        mustIncludeTraits,
        10, // Get more results to see if there are alternatives
        null,
        'super'
    );

    console.log(`Found ${results.length} results.`);

    // An "alternative" board is one that has the same traits/breakpoints but different units
    // or specifically, units that are marked as "Flex" (*) in the UI.
    // In our case, let's look for boards that are identical except for one unit.
    
    const boardSignatures = results.map(res => {
        // Sort names to make comparison easier, but keep track of the board
        const names = res.board.map(u => u.name).sort();
        return {
            names: names,
            score: res.score,
            counts: res.counts
        };
    });

    // We define "functionally equivalent" as having the same set of active trait breakpoints.
    function getBreakpointSignature(counts) {
        const sig = {};
        for (const trait in counts) {
            const traitInfo = set16Data.trait_metadata[trait];
            if (!traitInfo) continue;
            const count = counts[trait];
            const breakpoints = traitInfo.breakpoints;
            let highest = 0;
            for (const b of breakpoints) {
                if (b <= count) highest = b;
            }
            if (highest > 0 || (traitInfo.type === 'origin' && trait === 'Targon' && count >= 1)) {
                sig[trait] = highest || 1;
            }
        }
        return JSON.stringify(Object.entries(sig).sort());
    }

    // However, the user specifically says: "An alternative is the same comp except a star marked unit is replaced."
    // A unit gets a star if it only contributes to ONE active trait.
    
    function getStarUnits(board, counts) {
        const activeTraits = Object.keys(counts).filter(t => {
            const meta = set16Data.trait_metadata[t];
            return meta && (meta.breakpoints.some(b => b <= counts[t]) || (t === 'Targon' && counts[t] >= 1));
        });
        
        return board.filter(u => {
            const contributed = u.traits.filter(t => activeTraits.includes(t));
            return contributed.length === 1;
        }).map(u => u.name);
    }

    const uniqueComps = [];
    let duplicateFound = false;

    results.forEach((res, i) => {
        const stars = getStarUnits(res.board, res.counts);
        const nonStars = res.board.filter(u => !stars.includes(u.name)).map(u => u.name).sort();
        
        // A "Comp Signature" for filtering alternatives:
        // Everything that ISN'T a star must be the same.
        // And the set of active traits must be the same? 
        // Or just the non-star units.
        const sig = nonStars.join('|');
        
        console.log(`Result ${i+1}: Score=${res.score}, Stars=[${stars.join(', ')}], Non-Stars=[${nonStars.join(', ')}]`);
        
        if (uniqueComps.includes(sig)) {
            duplicateFound = true;
            console.warn(`FOUND ALTERNATIVE: Result ${i+1} is functionally equivalent to a previous result!`);
        } else {
            uniqueComps.push(sig);
        }
    });

    if (duplicateFound) {
        console.error("FAIL: Solver reported multiple functionally equivalent 'Alternative' compositions.");
        process.exit(1);
    } else {
        console.log("PASS: No functional duplicates found.");
    }
}

runTest().catch(err => {
    console.error(err);
    process.exit(1);
});
