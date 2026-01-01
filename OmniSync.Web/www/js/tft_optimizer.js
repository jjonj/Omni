
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
        this.ANNIE_ARCANIST_COUNT = 2;
        this.SYLAS_FORBIDDEN_NAMES = ["Jarvan IV", "Lux", "Garen"];
        
        // Algorithm Settings
        this.CANDIDATE_POOL_SIZE = 22;
    }

    scoreBoard(board, emblems, targetSize) {
        let counts = {};
        const names = board.map(u => u.name);
        
        // Check Special Rule: Sylas vs Demacia Trio
        if (names.includes("Sylas") && this.SYLAS_FORBIDDEN_NAMES.some(x => names.includes(x))) {
            return -this.INVALID_COMP_PENALTY;
        }
        
        // Trait Counting Logic
        for (const u of board) {
            for (const t of u.traits) {
                if (this.IGNORE_TRAITS.includes(t)) continue;
                
                // Annie logic
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
        
        // Evaluate Traits
        for (const trait in counts) {
            const count = counts[trait];
            
            if (count > this.MAX_TRAIT_COUNT || (trait === "Shurima" && count >= this.FORBIDDEN_SHURIMA_MIN)) {
                score -= this.INVALID_COMP_PENALTY;
            }
            
            if (this.TRAITS_DATA[trait]) {
                const breakpoints = this.TRAITS_DATA[trait];
                const reached = breakpoints.filter(b => b <= count);
                if (reached.length > 0) {
                    score += Math.max(...reached) * this.BREAKPOINT_SCORE_MULTIPLIER;
                }
            } else if (count === 1) {
                // Traits not in TRAIT_BREAKPOINTS but counted (implied single count score?)
                // Python code: elif count == 1: score += UNIQUE_TRAIT_SCORE
                // But only if NOT in TRAIT_BREAKPOINTS. 
                // Wait, python code checks `if trait in TRAIT_BREAKPOINTS`.
                // If the trait is NOT in the breakpoints map (like a unique trait 5-cost might have?), it gets score.
                score += this.UNIQUE_TRAIT_SCORE;
            }
        }
        
        // Carry Logic
        const carries = board.filter(u => u.is_carry);
        if (targetSize === 6) {
            if (carries.length < 1) score -= this.MISSING_CARRY_PENALTY;
        } else {
            // Level 8 requirement
            const highCostCarries = carries.filter(u => u.cost >= 4);
            if (carries.length < 2 || highCostCarries.length < 1) {
                score -= this.MISSING_CARRY_PENALTY;
            }
        }
        
        // Unit-based Scoring
        let fiveCostCount = 0;
        for (const u of board) {
            if (u.cost === 5) fiveCostCount++;
            if (u.locked) score -= this.LOCKED_UNIT_PENALTY;
            
            for (const t of u.traits) {
                if (t === "Yordle") score -= this.YORDLE_PENALTY;
                if (this.BONUS_TRAITS.includes(t)) score += this.SMALL_TRAIT_BONUS;
            }
            
            score += u.cost * this.UNIT_COST_TIEBREAKER_WEIGHT;
        }
        
        // Level 8 high-cost preference management
        if (targetSize >= 8 && fiveCostCount > this.MAX_FIVE_COSTS_LVL_8) {
            score -= (fiveCostCount - this.MAX_FIVE_COSTS_LVL_8) * this.EXCESS_FIVE_COST_PENALTY;
        }
        
        return score;
    }

    *getCombos(targetSlots, pool) {
        if (targetSlots === 0) {
            yield [];
            return;
        }
        if (targetSlots < 0 || pool.length === 0) return;
        
        const u = pool[0];
        const uSlots = u.slots || 1;
        
        // Include u
        for (const c of this.getCombos(targetSlots - uSlots, pool.slice(1))) {
            yield [u, ...c];
        }
        
        // Exclude u
        for (const c of this.getCombos(targetSlots, pool.slice(1))) {
            yield c;
        }
    }

    findBestBoards(pool, size, emblems, mustIncludeName = null, extraSlot = false) {
        const targetSlots = extraSlot ? size + 1 : size;
        
        let fixedUnits = [];
        if (mustIncludeName) {
            fixedUnits = this.UNITS.filter(u => u.name === mustIncludeName);
        }
        
        const synergyBase = new Set([...emblems, ...this.BONUS_TRAITS]);
        fixedUnits.forEach(f => f.traits.forEach(t => synergyBase.add(t)));
        
        // Filter candidates
        // Exclude units already fixed
        let candidates = pool.filter(u => !fixedUnits.some(f => f.name === u.name));
        
        // Sort by synergy/cost heuristic to reduce search space
        candidates.sort((a, b) => {
            const aSyn = a.traits.some(t => synergyBase.has(t));
            const bSyn = b.traits.some(t => synergyBase.has(t));
            
            if (aSyn !== bSyn) return bSyn - aSyn; // True (1) > False (0)
            return b.cost - a.cost; // Descending cost
        });
        
        candidates = candidates.slice(0, this.CANDIDATE_POOL_SIZE);
        
        const fixedSlots = fixedUnits.reduce((acc, u) => acc + (u.slots || 1), 0);
        const neededSlots = targetSlots - fixedSlots;
        
        let results = [];
        
        // Since generator in JS isn't as fast/optimised as Python's for massive recursions in browser,
        // we might need to be careful. But pool size 22 is small enough.
        
        for (const combo of this.getCombos(neededSlots, candidates)) {
            const currentBoard = [...combo, ...fixedUnits];
            const score = this.scoreBoard(currentBoard, emblems, targetSlots);
            results.push({ score, board: currentBoard });
        }
        
        results.sort((a, b) => b.score - a.score);
        return results.slice(0, 3);
    }
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = TFTOptimizer;
}
