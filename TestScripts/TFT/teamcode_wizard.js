const fs = require('fs');
const path = require('path');
const readline = require('readline');

const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout
});

const set17Path = path.join(__dirname, '../../OmniSync.Web/www/assets/tft/data/set17.json');
const mapPath = path.join(__dirname, '../../OmniSync.Web/www/assets/tft/data/unit_id_map.json');

// Load Set 17 data
const set17 = JSON.parse(fs.readFileSync(set17Path, 'utf8'));

// Sort units by cost (asc), then by name (alpha)
const units = [...set17.units].sort((a, b) => {
    if (a.cost !== b.cost) return a.cost - b.cost;
    return a.name.localeCompare(b.name);
});

const BATCH_SIZE = 10;

/**
 * Parses a teamcode and extracts multiple unit IDs.
 */
function extractIdsFromCode(code, count) {
    const HEADER = "02";
    const TRAILER = "TFTSet17";
    const SLOT_SIZE = 3;

    if (!code.startsWith(HEADER) || !code.endsWith(TRAILER)) {
        throw new Error("Invalid code format. Expected 02...TFTSet17");
    }

    const payload = code.substring(HEADER.length, code.length - TRAILER.length);
    const ids = [];
    for (let i = 0; i < count; i++) {
        ids.push(payload.substring(i * SLOT_SIZE, (i + 1) * SLOT_SIZE));
    }
    return ids;
}

async function runWizard() {
    console.log("=== TFT Set 17 TeamCode Wizard ===");
    console.log(`Total units to process: ${units.length}`);

    for (let i = 0; i < units.length; i += BATCH_SIZE) {
        const targets = units.slice(i, i + BATCH_SIZE);
        const namesList = targets.map(u => u.name).join(", ");
        const firstUnit = targets[0].name;
        const lastUnit = targets[targets.length - 1].name;
        
        console.log(`\n--- Batch ${Math.floor(i / BATCH_SIZE) + 1} ---`);
        targets.forEach((u, idx) => console.log(`${i + idx + 1}. ${u.name} (Cost: ${u.cost})`));

        let success = false;
        while (!success) {
            const code = await new Promise(resolve => {
                rl.question(`\nPaste Teamcode for ${firstUnit} to ${lastUnit} [${namesList}] (or 'q' to quit): `, resolve);
            });

            if (code.toLowerCase() === 'q') {
                console.log("Exiting...");
                rl.close();
                return;
            }

            try {
                const ids = extractIdsFromCode(code.trim(), targets.length);
                const unitMap = JSON.parse(fs.readFileSync(mapPath, 'utf8'));

                console.log("\nUpdating IDs:");
                targets.forEach((u, idx) => {
                    const oldId = unitMap[u.name];
                    const newId = ids[idx];
                    unitMap[u.name] = newId;
                    console.log(`  ${u.name}: ${oldId} -> ${newId}`);
                });

                fs.writeFileSync(mapPath, JSON.stringify(unitMap, null, 2), 'utf8');
                console.log("Successfully updated unit_id_map.json!");
                success = true;
            } catch (e) {
                console.error(`Error: ${e.message}. Please try again.`);
            }
        }
    }

    console.log("\nBatch limit reached!");
    rl.close();
}

runWizard();
