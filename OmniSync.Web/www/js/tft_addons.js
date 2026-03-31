if (typeof TFTAddon === 'undefined') {
    if (typeof module !== 'undefined' && module.exports) {
        // In Node.js, we might have it already if we required tft_optimizer.js
        // but since we aren't using a module system in the browser we can't easily cross-reference
    }
    
    // Define base class if missing
    class TFTAddonBase {
        constructor(optimizer) {
            this.optimizer = optimizer;
        }
        onInit() {}
        beforeScore(board, emblems, targetSize, mode) { return null; }
        modifyScore(result, board, emblems, targetSize, mode, mustIncludeNames) { return result; }
        expandMustInclude(expandedNames) { return expandedNames; }
        modifyPool(pool, size, emblems, mustIncludeNames, mustIncludeTraits, heuristic, mode) { return pool; }
        modifyCandidates(candidates, neededSlots, fixedUnits) { return { candidates, neededSlots, fixedUnits }; }
        modifySynergyBase(synergyBase, emblems, mode) { return synergyBase; }
        modifyTraitIncrement(u, trait) { return 1; }
    }
    
    // Assign to a variable that both browser and node can see
    if (typeof global !== 'undefined') {
        global.TFTAddon = TFTAddonBase;
    } else {
        window.TFTAddon = TFTAddonBase;
    }
}

class Set16RulesAddon extends TFTAddon {
    constructor(optimizer, compRules) {
        super(optimizer);
        this.compRules = compRules || {};
    }

    onInit() {
        // Initialize constants from compRules or defaults
        const gen = this.compRules.general || {};
        this.SYLAS_FORBIDDEN_NAMES = gen.sylas_forbidden_names || ["Jarvan IV", "Lux", "Garen"];
        this.ANNIE_ARCANIST_COUNT = 1;
        this.YORDLE_PENALTY = 200;
        this.BONUS_TRAITS = ["Quickstriker", "Piltover", "Targon"];
        this.FORBIDDEN_TRAITS = gen.forbidden_traits || { "Shurima": 3, "Ixtal": 3 };
        
        this.unitLevelRequirements = gen.unit_level_requirements || [
            { "name": "Kennen", "min_level": 6 },
            { "name": "Kobuko", "min_level": 7 }
        ];
        
        this.costLevelLimits = gen.cost_level_limits || [
            { "max_cost": 3, "below_level": 7 },
            { "max_cost": 4, "below_level": 8 }
        ];
    }

    modifySynergyBase(synergyBase, emblems, mode) {
        this.BONUS_TRAITS.forEach(t => synergyBase.add(t));
        return synergyBase;
    }

    modifyTraitIncrement(u, trait) {
        if (u.name === "Annie" && trait === "Arcanist") return this.ANNIE_ARCANIST_COUNT;
        return 1;
    }

    modifyScore(result, board, emblems, targetSize, mode, mustIncludeNames) {
        let { score, counts } = result;
        const names = new Set(board.map(u => u.name));
        const mustSet = new Set(mustIncludeNames);

        // Sylas Forbidden
        if (names.has("Sylas") && this.SYLAS_FORBIDDEN_NAMES.some(x => names.has(x))) {
            score -= this.optimizer.INVALID_COMP_PENALTY;
        }

        // Level-based restrictions
        for (const req of this.unitLevelRequirements) {
            if (targetSize < req.min_level && names.has(req.name) && !mustSet.has(req.name)) {
                score -= this.optimizer.INVALID_COMP_PENALTY;
            }
        }

        // Cost limits
        for (const limit of this.costLevelLimits) {
             if (targetSize < limit.below_level && board.some(u => u.cost > limit.max_cost && !mustSet.has(u.name))) {
                 score -= this.optimizer.INVALID_COMP_PENALTY;
             }
        }

        // Specific cost limits for high levels (hardcoded in original)
        if (board.length >= targetSize) {
            if (targetSize === 10) {
                const lowCostCount = board.filter(u => u.cost <= 2).length;
                const highCostCount = board.filter(u => u.cost >= 4).length;
                if (lowCostCount > 3) score -= this.optimizer.INVALID_COMP_PENALTY;
                if (highCostCount < 5) score -= this.optimizer.INVALID_COMP_PENALTY;
            }
            if (targetSize === 9) {
                const lowCostCount = board.filter(u => u.cost <= 2).length;
                const highCostCount = board.filter(u => u.cost >= 4).length;
                if (lowCostCount > 4) score -= this.optimizer.INVALID_COMP_PENALTY;
                if (highCostCount < 4) score -= this.optimizer.INVALID_COMP_PENALTY;
            }
        }

        // Forbidden Traits
        for (const trait in this.FORBIDDEN_TRAITS) {
            if (counts[trait] > this.FORBIDDEN_TRAITS[trait]) {
                score -= this.optimizer.INVALID_COMP_PENALTY;
            }
        }

        // Targon and Yordle logic
        const bonusTraitWeight = (targetSize >= 7) ? 100 : 1;
        for (const u of board) {
            let isTargonUnit = false;
            for (const t of u.traits) {
                if (t === "Yordle") score -= this.YORDLE_PENALTY;
                if (this.BONUS_TRAITS.includes(t)) score += bonusTraitWeight;
                if (t === "Targon") isTargonUnit = true;
            }
            if (isTargonUnit && mode !== 'bronze-for-life') {
                score += this.optimizer.BREAKPOINT_SCORE_MULTIPLIER * 0.2; 
            }
        }

        // Targon fixed bonus
        if (counts["Targon"] >= 1 && mode !== 'bronze-for-life') {
            score += 200;
        }

        // Annie Arcanist fix (already handled in trait counting but double check)
        // Note: The optimizer core will handle Annie increment if we provide it as a hook
        
        return { score, counts };
    }
}

class UnlockAddon extends TFTAddon {
    expandMustInclude(expandedNames) {
        let added;
        do {
            added = false;
            for (let i = 0; i < this.optimizer.UNITS.length; i++) {
                const u = this.optimizer.UNITS[i];
                if (expandedNames.has(u.name)) {
                    if (u.requires && !expandedNames.has(u.requires)) {
                        expandedNames.add(u.requires);
                        added = true;
                    }
                }
            }
        } while (added);
        return expandedNames;
    }

    modifyScore(result, board, emblems, targetSize, mode, mustIncludeNames) {
        let { score, counts } = result;
        const names = new Set(board.map(u => u.name));

        // Data-driven requirements check
        for (const u of board) {
            if (u.requires && !names.has(u.requires)) {
                score -= this.optimizer.INVALID_COMP_PENALTY;
            }
        }

        // Ryze Unlock / World Runes diversity
        if (mode === 'world-runes' || mode === 'ryze-unlock') {
            const activeOrigins = this.optimizer.getActiveOrigins(counts);
            const activeOriginsCount = activeOrigins.length;
            if (activeOriginsCount < 4) {
                if (board.length >= targetSize) {
                    score -= this.optimizer.INVALID_COMP_PENALTY;
                } else {
                    score += activeOriginsCount * 10000;
                }
            } else {
                score += 100000;
            }
        }

        return { score, counts };
    }
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { TFTAddon, Set16RulesAddon, UnlockAddon };
}
