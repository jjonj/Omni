/**
 * Utility for parsing and generating TFT Team Planner codes for Set 16.
 * Format: 02 (Header) + 10 slots * 3 hex chars (Payload) + TFTSet16 (Trailer)
 */
class TeamPlannerCode {
    static HEADER = "02";
    static TRAILER = "TFTSet16";
    static SLOT_SIZE = 3;
    static MAX_SLOTS = 10;

    // Verified Set 16 Champion Mapping - Strictly Alphabetical within Costs
    static UNIT_MAP = {
        // 1-Costs (Anivia to Qiyana + Rumble to Viego)
        "Anivia": "33e", "Blitzcrank": "34a", "Briar": "32f", "Caitlyn": "349",
        "Illaoi": "32c", "Jarvan IV": "338", "Jhin": "374", "Kog'Maw": "355",
        "Lulu": "2e0", "Qiyana": "02c", "Rumble": "321", "Shen": "351",
        "Sona": "33a", "Viego": "024",
        
        // 2-Costs (Aphelios to Rek'Sai + Sion to Yorick)
        "Aphelios": "335", "Ashe": "33f", "Cho'Gath": "018", "Ekko": "354",
        "Graves": "015", "Neeko": "027", "Orianna": "343", "Poppy": "02b",
        "Rek'Sai": "36f", "Sion": "32e", "Teemo": "320", "Tristana": "2df",
        "Tryndamere": "011", "Twisted Fate": "323", "Vi": "34b", "Xin Zhao": "010",
        "Yasuo": "34d", "Yorick": "00f",
        
        // 3-Costs (Ahri to LeBlanc + Leona to Zoe)
        "Ahri": "34f", "Darius": "36b", "Dr. Mundo": "331", "Draven": "02f",
        "Gangplank": "324", "Gwen": "01d", "Jinx": "348", "Kennen": "370",
        "Kobuko & Yuumi": "035", "LeBlanc": "017", "Leona": "334", "Loris": "020",
        "Malzahar": "352", "Milio": "342", "Nautilus": "322", "Sejuani": "361",
        "Vayne": "004", "Zoe": "333",

        // 4-Costs (Ambessa to Lux + Nasus to Veigar + Warwick to Yunara)
        "Ambessa": "332", "Bel'Veth": "366", "Braum": "340", "Diana": "023",
        "Fizz": "36e", "Garen": "33c", "Kai'Sa": "01b", "Kalista": "01e",
        "Lissandra": "341", "Lux": "33d", "Miss Fortune": "32d", "Nasus": "022",
        "Nidalee": "014", "Renekton": "01c", "Rift Herald": "016", "Seraphine": "34c",
        "Singed": "367", "Skarner": "01a", "Swain": "025", "Taric": "336",
        "Veigar": "369", "Warwick": "36d", "Wukong": "350", "Yone": "36c",
        "Yunara": "02a",

        // 5-Costs (Aatrox to Lucian & Senna + Mel to Volibear + Azir to Zilean)
        "Aatrox": "372", "Annie": "356", "Aurelion Sol": "368", "Azir": "359",
        "Baron Naashor": "36a", "Brock": "363", "Fiddlesticks": "35b", "Galio": "35f",
        "Kindred": "358", "Lucian & Senna": "034", "Mel": "019", "Ornn": "357",
        "Ryze": "013", "Sett": "362", "Shyvana": "35d", "Sylas": "012",
        "Tahm Kench": "360", "T-Hex": "365", "Thresh": "021", "Volibear": "373",
        "Xerath": "01f", "Zaahen": "030", "Ziggs": "371", "Zilean": "35a"
    };

    static ID_MAP = Object.fromEntries(Object.entries(this.UNIT_MAP).map(([k, v]) => [v, k]));

    /**
     * Encodes a list of unit names into a team planner code.
     * @param {string[]|Object[]} units Array of names or objects with {name, cost}
     * @returns {string}
     */
    static encode(units) {
        let unitList = [...units];
        
        // Sort by cost, then by name
        unitList.sort((a, b) => {
            const costA = typeof a === 'object' ? a.cost : 0;
            const costB = typeof b === 'object' ? b.cost : 0;
            const nameA = typeof a === 'object' ? a.name : a;
            const nameB = typeof b === 'object' ? b.name : b;

            if (costA !== costB && costA !== 0 && costB !== 0) return costA - costB;
            return nameA.localeCompare(nameB);
        });

        const names = unitList.map(u => typeof u === 'object' ? u.name : u);
        
        let payload = "";
        for (let i = 0; i < this.MAX_SLOTS; i++) {
            if (i < names.length) {
                const id = this.UNIT_MAP[names[i]];
                if (id) {
                    payload += id;
                } else {
                    console.warn(`Unknown unit: ${names[i]}`);
                    payload += "000";
                }
            } else {
                payload += "000";
            }
        }

        return this.HEADER + payload + this.TRAILER;
    }

    /**
     * Decodes a team planner code into a list of unit names.
     * @param {string} code 
     * @returns {string[]}
     */
    static decode(code) {
        if (!code.startsWith(this.HEADER) || !code.endsWith(this.TRAILER)) {
            throw new Error("Invalid code format");
        }

        const payload = code.substring(this.HEADER.length, code.length - this.TRAILER.length);
        const units = [];

        for (let i = 0; i < payload.length; i += this.SLOT_SIZE) {
            const id = payload.substring(i, i + this.SLOT_SIZE);
            if (id !== "000") {
                const unit = this.ID_MAP[id];
                units.push(unit || `unknown_${id}`);
            }
        }

        return units;
    }
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = TeamPlannerCode;
}
