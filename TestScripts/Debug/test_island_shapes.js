
const fs = require('fs');
const path = require('path');

// Mock browser environment
global.self = global;
global.window = global;
global.localStorage = {
    getItem: () => null,
    setItem: () => {}
};
global.importScripts = () => {};
global.ImageData = class {};
global.Worker = class {};

// Mock SimplexNoise
class MockSimplexNoise {
    constructor(seed) { this.seed = seed; }
    noise2D(x, y) { 
        // Deterministic mock noise
        return Math.sin(x * 10 + this.seed) * Math.cos(y * 10);
    }
}
global.SimplexNoise = MockSimplexNoise;

// Helper to load files
function loadFile(relPath) {
    const fullPath = path.join(__dirname, '../../OmniSync.Web/www/IslandGenerator', relPath);
    const content = fs.readFileSync(fullPath, 'utf8');
    eval(content);
}

console.log("Loading IslandGenerator components...");
global.BiomeData = { getClosestCore: () => ({index: 0}), getBiomeIndex: () => 0 };
global.Utils = { 
    getBucket: (v) => Math.floor(v * 3),
    SeededRandom: class { constructor(s) { this.s = s; } next() { return 0.5; } range(a, b) { return (a + b) / 2; } },
    PriorityQueue: class { push() {} pop() { return {node: {}}; } size() { return 0; } },
    SpatialGrid: class { insert() {} queryRadius() { return []; } }
};

loadFile('src/config/Config.js');
loadFile('src/core/NoiseGenerator.js');
loadFile('src/core/ErosionSystem.js');
loadFile('src/core/ShapeProcessor.js');
loadFile('src/core/BiomeGenerator.js');
loadFile('src/core/HydrologyGraph.js');
loadFile('src/core/HydrologySystem.js');
loadFile('src/core/IslandGenerator.js');

const gen = new global.IslandGenerator(128, 128, MockSimplexNoise);
const seeds = { m: 0.1, t: 0.2, d: 0.3, i: 0.4, w: 0.5, c: 0.6, h: 0.7, river: 0.8 };
const baseParams = {
    iMass: 0.6, contrast: 1.0, jagged: 1.0, ridge: 0.5,
    hFreq: 0.01, hOct: 4, hPers: 0.5, hLac: 2.0,
    mFreq: 0.01, mOct: 4, mLevel: 1.0,
    tFreq: 0.01, tOct: 4, tLevel: 1.0,
    dFreq: 0.01, dOct: 4, dLevel: 1.0,
    minIslandSize: 0, maxLakeSize: 0, shapeSmooth: 0,
    biomeDenoise: 0, biomeSmooth: 0
};

const modes = ['classic', 'warped', 'angular', 'metrics', 'vignette', 'attractors'];
const results = {};

console.log("\nRunning Shape Mode Tests...");

modes.forEach(mode => {
    const params = { ...baseParams, shapeMode: mode };
    const buffers = gen.calculate(seeds, params);
    // Copy buffer to avoid reference sharing if internal buffers are reused
    results[mode] = new Float32Array(buffers.land);
    const sum = results[mode].reduce((a, b) => a + b, 0);
    console.log(`Mode [${mode.padEnd(10)}]: Sum = ${sum.toFixed(4)}`);
});

// Compare results
console.log("\nComparison Matrix (Should be different):");
let allDifferent = true;
for (let i = 0; i < modes.length; i++) {
    for (let j = i + 1; j < modes.length; j++) {
        const m1 = modes[i];
        const m2 = modes[j];
        const b1 = results[m1];
        const b2 = results[m2];
        
        let identical = true;
        for (let k = 0; k < b1.length; k++) {
            if (Math.abs(b1[k] - b2[k]) > 0.0001) {
                identical = false;
                break;
            }
        }
        
        if (identical) {
            console.log(`[FAIL] ${m1} and ${m2} are IDENTICAL!`);
            allDifferent = false;
        } else {
            console.log(`[PASS] ${m1} vs ${m2}: Different.`);
        }
    }
}

if (allDifferent) {
    console.log("\nALL MODES PRODUCE UNIQUE GEOMETRY.");
    process.exit(0);
} else {
    console.log("\nSOME MODES ARE PRODUCING IDENTICAL GEOMETRY.");
    process.exit(1);
}
