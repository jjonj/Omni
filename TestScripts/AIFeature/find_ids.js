const fs = require('fs');
const content = fs.readFileSync('OmniSync.Web/www/assets/tft/Teamplannerencoding.txt', 'utf8');
const regex = /02([0-9a-f]{30})TFTSet16/g;
let match;
console.log("Searching for 33c in codes...");
while ((match = regex.exec(content)) !== null) {
    const payload = match[1];
    if (payload.includes('33c')) {
        const index = payload.indexOf('33c');
        const slot = (index / 3) + 1;
        console.log(`Found 33c in code at slot ${slot}: ${match[0]}`);
    }
}

console.log("\nSearching for 01b in codes...");
regex.lastIndex = 0;
while ((match = regex.exec(content)) !== null) {
    const payload = match[1];
    if (payload.includes('01b')) {
        const index = payload.indexOf('01b');
        const slot = (index / 3) + 1;
        console.log(`Found 01b in code at slot ${slot}: ${match[0]}`);
    }
}
