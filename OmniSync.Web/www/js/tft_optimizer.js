class TFTOptimizer {
    constructor(units, traitsData) {
        this.UNITS = units;
        this.TRAITS_DATA = traitsData;

        // Constants
        this.BREAKPOINT_SCORE_MULTIPLIER = 1000;
        this.UNIQUE_TRAIT_SCORE = 1900;
        
        this.LOCKED_UNIT_PENALTY = 500;
        this.YORDLE_PENALTY = 200;
        this.UNIT_COST_TIEBREAKER_WEIGHT = 10;
        
        // Hard Constraints
        this.INVALID_COMP_PENALTY = 10**8;
        this.MISSING_CARRY_PENALTY = 10**5;
        this.MAX_FIVE_COSTS_LVL_8 = 2;
        this.EXCESS_FIVE_COST_PENALTY = 1000;
        
        // Trait Difficulty Constraints
        this.MAX_TRAIT_COUNT = 7;
        this.FORBIDDEN_SHURIMA_MIN = 3;
        
        this.SMALL_TRAIT_BONUS = 1;
        this.BONUS_TRAITS = ["Quickstriker", "Piltover", "Targon"];
        this.IGNORE_TRAITS = ["Ixtal", "Shadow Isles"];
        
        // Unit Specifics
        this.ANNIE_ARCANIST_COUNT = 1;
        this.SYLAS_FORBIDDEN_NAMES = ["Jarvan IV", "Lux", "Garen"];
        
        // Algorithm Settings
        this.CANDIDATE_POOL_SIZE = 40; 
    }

    scoreBoard(board, emblems, targetSize, mode = 'default', mustIncludeTraits = []) {
        let counts = {};
        const names = board.map(u => u.name);
        
        if (names.includes("Sylas") && this.SYLAS_FORBIDDEN_NAMES.some(x => names.includes(x))) {
            return { score: -this.INVALID_COMP_PENALTY, counts };
        }

        const hasAnnie = names.includes("Annie");
        const hasTibbers = names.includes("Tibbers");
        if (hasAnnie !== hasTibbers) {
            return { score: -this.INVALID_COMP_PENALTY, counts };
        }

        // Hard Level Rules
        if (targetSize < 6 && names.includes("Kennen")) return { score: -this.INVALID_COMP_PENALTY, counts };
        if (targetSize < 7 && names.some(n => n.includes("Kobuko"))) return { score: -this.INVALID_COMP_PENALTY, counts };
        if (targetSize < 8 && board.some(u => u.cost === 5)) return { score: -this.INVALID_COMP_PENALTY, counts };
        
        for (const u of board) {
            for (const t of u.traits) {
                if (this.IGNORE_TRAITS.includes(t)) continue;
                const increment = (u.name === "Annie" && t === "Arcanist") ? this.ANNIE_ARCANIST_COUNT : 1;
                counts[t] = (counts[t] || 0) + increment;
            }
        }
        
        for (const emb of emblems) {
            if (!this.IGNORE_TRAITS.includes(emb)) {
                counts[emb] = (counts[emb] || 0) + 1;
            }
        }
        
        let score = 0;
        let activeTraits = new Set();

        for (const trait in counts) {
            const count = counts[trait];
            if (count > this.MAX_TRAIT_COUNT || (trait === "Shurima" && count >= this.FORBIDDEN_SHURIMA_MIN)) {
                score -= this.INVALID_COMP_PENALTY;
            }
            
            if (this.TRAITS_DATA[trait]) {
                const breakpoints = this.TRAITS_DATA[trait];
                const reached = breakpoints.filter(b => b <= count);
                if (reached.length > 0) {
                    activeTraits.add(trait);
                    // Targon gives 0 points for breakpoints
                    if (trait !== "Targon") {
                        if (mode === 'bronze-for-life') {
                            score += 1 * this.BREAKPOINT_SCORE_MULTIPLIER;
                        } else {
                            score += Math.max(...reached) * this.BREAKPOINT_SCORE_MULTIPLIER;
                        }
                    }
                }
            } else if (count === 1) {
                score += this.UNIQUE_TRAIT_SCORE;
            }
        }

        for (const targetTrait of mustIncludeTraits) {
            if (!activeTraits.has(targetTrait)) score -= this.INVALID_COMP_PENALTY;
        }
        
        const carries = board.filter(u => u.is_carry);
        if (targetSize <= 6) {
            if (carries.length < 1) score -= this.MISSING_CARRY_PENALTY;
        } else if (targetSize <= 8) {
            const highCostCarries = carries.filter(u => u.cost >= 4);
            if (carries.length < 2 || highCostCarries.length < 1) score -= this.MISSING_CARRY_PENALTY;
        } else {
            const highCostCarries = carries.filter(u => u.cost >= 4);
            if (carries.length < 3 || highCostCarries.length < 2) score -= this.MISSING_CARRY_PENALTY;
        }
        
        let threeCostCount = 0;
        let lowCostCount = 0; 
        let highCostCount = 0;

        const costWeight = (targetSize >= 7) ? this.UNIT_COST_TIEBREAKER_WEIGHT : -10; 
        const bonusTraitWeight = (targetSize >= 7) ? 100 : 1;

        for (const u of board) {
            if (u.cost === 3) threeCostCount++;
            if (u.cost <= 2) lowCostCount++;
            if (u.cost >= 4) highCostCount++;

            if (u.locked) score -= this.LOCKED_UNIT_PENALTY;
            
            let isTargonUnit = false;
            for (const t of u.traits) {
                if (t === "Yordle") score -= this.YORDLE_PENALTY;
                if (this.BONUS_TRAITS.includes(t)) score += bonusTraitWeight;
                if (t === "Targon") isTargonUnit = true;
            }

            if (isTargonUnit) {
                score += this.BREAKPOINT_SCORE_MULTIPLIER * 0.2; 
            }

            score += u.cost * costWeight;
        }
        
        if (targetSize === 10) {
            if (lowCostCount > 3) score -= this.INVALID_COMP_PENALTY;
            if (highCostCount < 5) score -= this.INVALID_COMP_PENALTY;
        }
        if (targetSize === 9) {
            if (lowCostCount > 4) score -= this.INVALID_COMP_PENALTY;
            if (highCostCount < 4) score -= this.INVALID_COMP_PENALTY;
        }
        if (targetSize === 4 && threeCostCount > 1) score -= this.INVALID_COMP_PENALTY;
        if (targetSize === 5 && threeCostCount > 2) score -= this.INVALID_COMP_PENALTY;
        
        return { score, counts };
    }

    *getCombos(targetSlots, pool) {
        if (targetSlots === 0) {
            yield [];
            return;
        }
        if (targetSlots < 0 || pool.length === 0) return;
        const u = pool[0];
        const uSlots = u.slots || 1;
        for (const c of this.getCombos(targetSlots - uSlots, pool.slice(1))) {
            yield [u, ...c];
        }
        for (const c of this.getCombos(targetSlots, pool.slice(1))) {
            yield c;
        }
    }

    findBestBoards(pool, size, emblems, mustIncludeNames = [], mode = 'default', mustIncludeTraits = [], limit = 3, extraSlot = false) {
        const targetSlots = size;
        let fixedUnits = [];
        if (mustIncludeNames && mustIncludeNames.length > 0) {
            fixedUnits = this.UNITS.filter(u => mustIncludeNames.includes(u.name));
        }
        const synergyBase = new Set([...emblems, ...this.BONUS_TRAITS]);
        fixedUnits.forEach(f => f.traits.forEach(t => synergyBase.add(t)));
        
        let candidates = pool.filter(u => !fixedUnits.some(f => f.name === u.name));
        candidates.sort((a, b) => {
            const aSyn = a.traits.some(t => synergyBase.has(t));
            const bSyn = b.traits.some(t => synergyBase.has(t));
            if (aSyn !== bSyn) return bSyn - aSyn;
            if (size <= 6) return b.traits.length - a.traits.length;
            return b.cost - a.cost;
        });
        candidates = candidates.slice(0, 45); 
        
        const fixedSlots = fixedUnits.reduce((acc, u) => acc + (u.slots || 1), 0);
        const neededSlots = targetSlots - fixedSlots;
        
        let results = [];
        for (const combo of this.getCombos(neededSlots, candidates)) {
            const currentBoard = [...combo, ...fixedUnits];
            const { score, counts } = this.scoreBoard(currentBoard, emblems, targetSlots, mode, mustIncludeTraits);
            if (score > -1000000) { 
                results.push({ score, board: currentBoard, counts });
            }
        }
        results.sort((a, b) => b.score - a.score);
        return results.slice(0, limit);
    }
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = TFTOptimizer;
}