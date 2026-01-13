const fs = require('fs');
const path = require('path');
const TeamPlannerCode = require('../../OmniSync.Web/www/js/TeamPlannerCode.js');

console.log("Starting TeamPlannerCode tests...");

// Initialize mapping from JSON
const mappingPath = path.join(__dirname, '../../OmniSync.Web/www/assets/tft/data/unit_id_map.json');
const mapping = JSON.parse(fs.readFileSync(mappingPath, 'utf8'));
TeamPlannerCode.setMapping(mapping);

// Test 1: Encoding 1-cost units (Anivia to Qiyana)
try {
    const units1 = [
        {name: "Anivia", cost: 1}, 
        {name: "Blitzcrank", cost: 1}, 
        {name: "Briar", cost: 1}, 
        {name: "Caitlyn", cost: 1}, 
        {name: "Illaoi", cost: 1}, 
        {name: "Jarvan IV", cost: 1}, 
        {name: "Jhin", cost: 1}, 
        {name: "Kog'Maw", cost: 1}, 
        {name: "Lulu", cost: 1}, 
        {name: "Qiyana", cost: 1}
    ];
    const expected1 = "0233e34a32f34932c3383743552e002cTFTSet16";
    const actual1 = TeamPlannerCode.encode(units1);
    
    if (actual1 === expected1) {
        console.log("PASS: Test 1 (Encoding 1-cost units)");
    } else {
        console.error(`FAIL: Test 1 (Encoding 1-cost units)\nExpected: ${expected1}\nActual:   ${actual1}`);
    }
} catch (e) {
    console.error("ERROR: Test 1 failed with error:", e.message);
}

// Test 2: Encoding 4-cost units (Ambessa to Lux)
try {
    const units2 = [
        {name: "Ambessa", cost: 4}, {name: "Bel'Veth", cost: 4}, {name: "Braum", cost: 4}, 
        {name: "Diana", cost: 4}, {name: "Fizz", cost: 4}, {name: "Garen", cost: 4}, 
        {name: "Kai'Sa", cost: 4}, {name: "Kalista", cost: 4}, {name: "Lissandra", cost: 4}, 
        {name: "Lux", cost: 4}
    ];
    const expected2 = "0233236634002336e33c01b01e34133dTFTSet16";
    const actual2 = TeamPlannerCode.encode(units2);
    
    if (actual2 === expected2) {
        console.log("PASS: Test 2 (Encoding 4-cost units)");
    } else {
        console.error(`FAIL: Test 2 (Encoding 4-cost units)\nExpected: ${expected2}\nActual:   ${actual2}`);
    }
} catch (e) {
    console.error("ERROR: Test 2 failed with error:", e.message);
}

// Test 3: Decoding Lulu and Garen
try {
    const code3 = "022e033c000000000000000000000000TFTSet16";
    const decoded3 = TeamPlannerCode.decode(code3);
    const expectedUnits3 = ["Lulu", "Garen"];
    
    if (JSON.stringify(decoded3) === JSON.stringify(expectedUnits3)) {
        console.log("PASS: Test 3 (Decoding Lulu and Garen)");
    } else {
        console.error(`FAIL: Test 3 (Decoding Lulu and Garen)\nExpected: ${JSON.stringify(expectedUnits3)}\nActual:   ${JSON.stringify(decoded3)}`);
    }
} catch (e) {
    console.error("ERROR: Test 3 failed with error:", e.message);
}
