const fs = require('fs');
const path = require('path');
const TeamPlannerCode = require('../../OmniSync.Web/www/js/TeamPlannerCode.js');

console.log("Starting TeamPlannerCode tests...");

// Test 1: Encoding 1-cost units (Anivia to Qiyana)
try {
    const units1 = ["Anivia", "Blitzcrank", "Briar", "Caitlyn", "Illaoi", "Jarvan IV", "Jhin", "Kog'Maw", "Lulu", "Qiyana"];
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
    const units2 = ["Ambessa", "Bel'Veth", "Braum", "Diana", "Fizz", "Garen", "Kai'Sa", "Kalista", "Lissandra", "Lux"];
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