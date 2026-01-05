const fs = require('fs');
const path = require('path');

// Mock global/window
const mockGlobal = {
    Utils: {
        SeededRandom: class { constructor() {} next() { return 0.5; } range() { return 0.5; } },
        hslToRgb: () => ({ r: 0, g: 0, b: 0 }),
        getBucket: () => 0
    },
    BiomeData: {
        getBiomeIndex: () => 0,
        getClosestCore: () => ({ index: 0 })
    }
};

// Function to load a script into the mock global
function loadScript(filePath) {
    const code = fs.readFileSync(filePath, 'utf8');
    const wrappedCode = `(function(window) { ${code} })(mockGlobal);`;
    eval(wrappedCode);
}

// Load dependencies (order matters)
const baseDir = path.join(__dirname, '../../OmniSync.Web/www/IslandGenerator/src');
loadScript(path.join(baseDir, 'config/Config.js'));
loadScript(path.join(baseDir, 'core/IslandGenerator.js'));

const { DEFAULT_CONFIG, IslandGenerator } = mockGlobal;

console.log("--- Testing Infrastructure & Buffer Management ---");

// Test Task 1.1: Buffer existence
const gen = new IslandGenerator(100, 100, class {});
const expectedBuffers = [
    'heightStage0', 'heightStage1', 'heightStage2', 
    'heightStage3', 'heightStage4', 'heightStage5', 'heightStage6'
];

console.log("Checking for diagnostic buffers...");
expectedBuffers.forEach(bufName => {
    if (gen.buffers[bufName]) {
        console.log(`PASS: Buffer ${bufName} exists.`);
    } else {
        console.warn(`FAIL: Buffer ${bufName} missing.`);
    }
});

// Test Task 1.2: Hillshade toggle in config
console.log("\nChecking for showHillshade in DEFAULT_CONFIG...");
if (DEFAULT_CONFIG.showHillshade !== undefined) {
    console.log(`PASS: showHillshade is defined as ${DEFAULT_CONFIG.showHillshade}.`);
} else {
    console.warn("FAIL: showHillshade is missing from DEFAULT_CONFIG.");
}

// Exit with error if any failed (we'll just check if output contains FAIL)
if (expectedBuffers.some(b => !gen.buffers[b]) || DEFAULT_CONFIG.showHillshade === undefined) {
    console.log("\nRED PHASE: Tests failed as expected.");
    // process.exit(0); // For Conductor, we want it to run and show failure
} else {
    console.log("\nGREEN PHASE: All tests passed.");
}
