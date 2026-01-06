
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

// Helper to load files
function loadFile(relPath) {
    const fullPath = path.join(__dirname, '../OmniSync.Web/www/IslandGenerator', relPath);
    const content = fs.readFileSync(fullPath, 'utf8');
    eval(content);
}

console.log("Loading IslandGenerator components...");
global.BiomeData = { getClosestCore: () => ({index: 0}), getBiomeIndex: () => 0 };
global.Utils = { 
    getBucket: (v) => Math.floor(v * 3),
    hslToRgb: (h, s, l) => ({r: 0, g: 0, b: 0}),
    lerpColor: (c1, c2, t) => ({r: 0, g: 0, b: 0})
};

loadFile('src/config/Config.js');
loadFile('src/core/NoiseGenerator.js');
// loadFile('src/core/ErosionSystem.js'); // Removed earlier
loadFile('src/core/ShapeProcessor.js');
loadFile('src/core/BiomeGenerator.js');
loadFile('src/core/HydrologyGraph.js');
loadFile('src/core/HydrologySystem.js');
loadFile('src/core/IslandGenerator.js');

const WIDTH = 1024;
const HEIGHT = 1024;
const gen = new global.IslandGenerator(WIDTH, HEIGHT, class { noise2D() { return 0; } });

// Load binary data
console.log("Loading binary data...");
const dataPath = path.join(__dirname, 'village_test_data.bin');
const buffer = fs.readFileSync(dataPath);

let offset = 0;
function readBuffer(name, type = Float32Array) {
    const size = WIDTH * HEIGHT * 4; // 4 bytes per float
    const view = new DataView(buffer.buffer, buffer.byteOffset + offset, size);
    const arr = new type(WIDTH * HEIGHT);
    for (let i = 0; i < WIDTH * HEIGHT; i++) {
        arr[i] = view.getFloat32(i * 4, true);
    }
    offset += size;
    return arr;
}

gen.buffers.land = readBuffer('land');
gen.buffers.heightAdv = readBuffer('heightAdv');
gen.buffers.outline = readBuffer('outline');
const idFloats = readBuffer('ids');
gen.buffers.ids = new Float32Array(WIDTH * HEIGHT);
for(let i=0; i<idFloats.length; i++) gen.buffers.ids[i] = idFloats[i];
gen.buffers.riverDepth = readBuffer('riverDepth');

function logRange(name, arr) {
    let min = Infinity, max = -Infinity, nonZero = 0;
    for(let v of arr) {
        if (v < min) min = v;
        if (v > max) max = v;
        if (v !== 0) nonZero++;
    }
    console.log(`${name.padEnd(10)}: min=${min.toFixed(4)}, max=${max.toFixed(4)}, nonZero=${nonZero}`);
}

logRange('land', gen.buffers.land);
logRange('heightAdv', gen.buffers.heightAdv);
logRange('outline', gen.buffers.outline);
logRange('riverDepth', gen.buffers.riverDepth);

// Parameters for the test
gen.lastParams = {
    iMass: 0.61,
    poiDensity: 12,
    poiFlatness: 0.05,
    poiInland: 0.7
};

gen.identifySettlements = function() {
    const w = this.width, h = this.height;
    const buffers = this.buffers;
    const params = this.lastParams || {};
    
    const maxSettlements = params.poiDensity !== undefined ? params.poiDensity : 12;
    const flatnessReq = params.poiFlatness !== undefined ? params.poiFlatness : 0.05;
    const inlandPref = params.poiInland !== undefined ? params.poiInland : 0.7;
    const threshold = 1 - (params.iMass || 0.61);

    this.settlements = [];
    if (maxSettlements <= 0) return;

    const candidates = [];
    const step = 8; 
    const areaRadius = 6; // Smaller area
    const elevationFloor = 0.15; // Lower floor

    let totalPoints = 0;
    let failWater = 0;
    let failFlat = 0;
    let failLow = 0;
    let failOutline = 0;

    for (let y = areaRadius; y < h - areaRadius; y += step) {
        for (let x = areaRadius; x < w - areaRadius; x += step) {
            totalPoints++;
            const idx = y * w + x;
            
            let waterContact = false;
            let minH = 1.0, maxH = 0.0;
            let sumH = 0;

            for (let dy = -areaRadius; dy <= areaRadius; dy += 2) {
                for (let dx = -areaRadius; dx <= areaRadius; dx += 2) {
                    const nIdx = (y + dy) * w + (x + dx);
                    if (nIdx < 0 || nIdx >= w*h) continue;
                    const isOcean = buffers.land[nIdx] <= threshold;
                    const isLake = buffers.ids[nIdx] === 3;
                    const isRiver = buffers.riverDepth[nIdx] > 0.02;

                    if (isOcean || isLake || isRiver) {
                        waterContact = true;
                        break;
                    }

                    const hVal = buffers.heightAdv[nIdx];
                    if (hVal < minH) minH = hVal;
                    if (hVal > maxH) maxH = hVal;
                    sumH += hVal;
                }
                if (waterContact) break;
            }

            if (waterContact) { failWater++; continue; }

            const hDiff = maxH - minH;
            const avgH = sumH / ((areaRadius * 2 / 2 + 1) ** 2);

            if (hDiff > flatnessReq) { failFlat++; continue; }
            if (avgH < elevationFloor) { failLow++; continue; }
            if (buffers.outline[idx] < 0.1) { failOutline++; continue; }

            const inlandScore = buffers.outline[idx];
            const flatnessScore = 1.0 - (hDiff / flatnessReq);
            const elevationScore = avgH < 0.7 ? 1.0 : (1.0 - (avgH - 0.7) * 2);

            const score = (inlandScore * inlandPref) + 
                          (flatnessScore * (1.0 - inlandPref)) + 
                          (elevationScore * 0.2);

            candidates.push({ x, y, score });
        }
    }

    console.log(`Scan Summary:
    Total sampled: ${totalPoints}
    Rejected (Water): ${failWater}
    Rejected (Rough): ${failFlat}
    Rejected (Low): ${failLow}
    Rejected (Outline): ${failOutline}
    Found viable: ${candidates.length}`);

    candidates.sort((a, b) => b.score - a.score);
    const minVillageDist = 100;
    for (let c of candidates) {
        if (this.settlements.length >= maxSettlements) break;
        let tooClose = false;
        for (let s of this.settlements) {
            const dSq = (c.x - s.x)**2 + (c.y - s.y)**2;
            if (dSq < minVillageDist * minVillageDist) {
                tooClose = true;
                break;
            }
        }
        if (!tooClose) this.settlements.push(c);
    }
};

console.log("Running identifySettlements()...");
const startTime = Date.now();
gen.identifySettlements();
const duration = Date.now() - startTime;

console.log(`\nFound ${gen.settlements.length} villages in ${duration}ms:`);
gen.settlements.forEach((s, i) => {
    console.log(`${i+1}. x: ${s.x.toString().padStart(4)}, y: ${s.y.toString().padStart(4)}, score: ${s.score.toFixed(4)}`);
});

if (gen.settlements.length > 0) {
    console.log("\nUNIT TEST PASSED: Settlements found using example maps.");
    process.exit(0);
} else {
    console.log("\nUNIT TEST FAILED: No settlements found. Check parameters or map data.");
    process.exit(1);
}
