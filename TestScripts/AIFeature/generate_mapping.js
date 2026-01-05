const fs = require('fs');
const path = require('path');

const set16Path = 'OmniSync.Web/www/assets/tft/data/set16.json';
const encodingPath = 'OmniSync.Web/www/assets/tft/Teamplannerencoding.txt';

const set16Data = JSON.parse(fs.readFileSync(set16Path, 'utf8'));
const units = set16Data.units;

// Sort units by cost, then by name
const sortedUnits = [...units].sort((a, b) => {
    if (a.cost !== b.cost) return a.cost - b.cost;
    return a.name.localeCompare(b.name);
});

const encodingText = fs.readFileSync(encodingPath, 'utf8');

// Regex to find codes in the text
const codeRegex = /02([0-9a-f]{30})TFTSet16/g;
let match;
const foundIds = [];

while ((match = codeRegex.exec(encodingText)) !== null) {
    const payload = match[1];
    for (let i = 0; i < 30; i += 3) {
        const id = payload.substring(i, i + 3);
        if (id !== '000') {
            foundIds.push(id);
        }
    }
}

console.log("Found IDs:", foundIds.length);
console.log("Sorted Units:", sortedUnits.length);

const mapping = {};
// We assume they were added to the file in order of cost and then alphabet
for (let i = 0; i < Math.min(foundIds.length, sortedUnits.length); i++) {
    mapping[sortedUnits[i].name] = foundIds[i];
}

// Special case: Kog'Maw and Lulu swap?
// In 1st example: Kog'Maw (2e0) and Lulu (355)
// Alphabetical order: ... Jhin, Kog'Maw, Lulu, Qiyana
// My foundIds order from text for first 10:
// 33e (Anivia)
// 34a (Blitzcrank)
// 32f (Briar)
// 349 (Caitlyn)
// 32c (Illaoi)
// 338 (Jarvan IV)
// 374 (Jhin)
// 355 (Lulu?)
// 2e0 (Kog'Maw?)
// 02c (Qiyana)
// In alphabet: Kog'Maw, Lulu.
// So they are swapped in the text example compared to strict alphabet?
// Wait, the text says "Anivia to qiyanna"
// Let's re-verify alphabet:
// Jhin
// Kog'Maw
// Lulu
// Qiyana
// Yes. So if Lulu is 355 and Kog'Maw is 2e0, and 355 comes before 2e0 in the string, then Lulu is before Kog'Maw in the string.
// That means the sorting used in the game might be different, OR my unit list is different.
// BUT, the text says: "Based on the examples provided and your confirmation that 33c corresponds to Garen"
// Garen is cost 4.
// Let's just output the mapping and see if it looks sane.

fs.writeFileSync('OmniSync.Web/www/assets/tft/data/unit_id_map.json', JSON.stringify(mapping, null, 2));
console.log("Saved mapping to OmniSync.Web/www/assets/tft/data/unit_id_map.json");
