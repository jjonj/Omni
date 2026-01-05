/**
 * Utility for parsing and generating TFT Team Planner codes for Set 16.
 * Format: 02 (Header) + 10 slots * 3 hex chars (Payload) + TFTSet16 (Trailer)
 */
class TeamPlannerCode {
    static HEADER = "02";
    static TRAILER = "TFTSet16";
    static SLOT_SIZE = 3;
    static MAX_SLOTS = 10;

    static UNIT_MAP = {};
    static ID_MAP = {};

    /**
     * Initializes the mapping from an external source.
     * @param {Object} map { "UnitName": "HexID", ... }
     */
    static setMapping(map) {
        this.UNIT_MAP = map;
        this.ID_MAP = Object.fromEntries(Object.entries(map).map(([k, v]) => [v, k]));
    }

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