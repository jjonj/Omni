class TFTOptimizer {
    constructor(units, traitsData) {
        this.UNITS = units;
        this.TRAITS_DATA = traitsData;
        this.isCancelled = false;

        // Speed Optimization: Pre-index traits
        this.traitNames = Object.keys(traitsData || {});
        this.traitMap = {};
        this.traitNames.forEach((name, i) => this.traitMap[name] = i);

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
        this.FORBIDDEN_TRAITS = {
            "Shurima": 3,
            "Ixtal": 3
        };
        
        this.SMALL_TRAIT_BONUS = 1;
        this.BONUS_TRAITS = ["Quickstriker", "Piltover", "Targon"];
        
        // Data-driven ignored traits
        this.IGNORE_TRAITS = [];
        if (this.TRAITS_DATA) {
            for (const t in this.TRAITS_DATA) {
                if (this.TRAITS_DATA[t].ignored) {
                    this.IGNORE_TRAITS.push(t);
                }
            }
        }
        
        // Unit Specifics
        this.ANNIE_ARCANIST_COUNT = 1;
        this.SYLAS_FORBIDDEN_NAMES = ["Jarvan IV", "Lux", "Garen"];
        
        // Algorithm Settings
        this.CANDIDATE_POOL_SIZE = 40; 
    }

    cancel() {
        this.isCancelled = true;
    }

    expandMustInclude(names) {
        if (!names) return [];
        let namesArray = Array.isArray(names) ? [...names] : [names];
        const expandedNames = new Set(namesArray);
        
        let added;
        do {
            added = false;
            for (let i = 0; i < this.UNITS.length; i++) {
                const u = this.UNITS[i];
                
                // Forward dependency: If we have forced a unit, we must force what it requires.
                if (expandedNames.has(u.name)) {
                    if (u.requires && !expandedNames.has(u.requires)) {
                        expandedNames.add(u.requires);
                        added = true;
                    }
                }
            }
        } while (added);
        return Array.from(expandedNames);
    }

    getActiveOrigins(counts) {
        return Object.keys(counts).filter(t => {
            const traitData = this.TRAITS_DATA[t];
            if (!traitData || traitData.type !== 'origin') return false;
            
            const breakpoints = traitData.breakpoints;
            // Targon is active at 1 unit, others check breakpoints
            if (t === 'Targon') return counts[t] >= 1;
            return breakpoints && breakpoints.some(b => b <= counts[t]);
        });
    }

    scoreBoard(board, emblems, targetSize, mode = 'default', mustIncludeTraits = {}, mustIncludeNames = []) {
        let counts = {};
        const names = new Set();
        for (let i = 0; i < board.length; i++) names.add(board[i].name);
        
        if (names.has("Sylas") && this.SYLAS_FORBIDDEN_NAMES.some(x => names.has(x))) {
            return { score: -this.INVALID_COMP_PENALTY, counts };
        }

        // Data-driven requirements check
        for (let i = 0; i < board.length; i++) {
            const u = board[i];
            if (u.requires && !names.has(u.requires)) {
                return { score: -this.INVALID_COMP_PENALTY, counts };
            }
        }

        // Hard Level Rules (Exempting Must-Include)
        const mustSet = new Set(mustIncludeNames);
        if (targetSize < 6 && names.has("Kennen") && !mustSet.has("Kennen")) return { score: -this.INVALID_COMP_PENALTY, counts };
        if (targetSize < 7 && board.some(u => u.name.includes("Kobuko") && !mustSet.has(u.name))) return { score: -this.INVALID_COMP_PENALTY, counts };
        
        // Strict cost limits
        if (targetSize < 7 && board.some(u => u.cost >= 4 && !mustSet.has(u.name))) return { score: -this.INVALID_COMP_PENALTY, counts };
        if (targetSize < 8 && board.some(u => u.cost === 5 && !mustSet.has(u.name))) return { score: -this.INVALID_COMP_PENALTY, counts };
        
        for (let i = 0; i < board.length; i++) {
            const u = board[i];
            for (let j = 0; j < u.traits.length; j++) {
                const t = u.traits[j];
                // In ryze-unlock mode, we need all origins, even ignored ones like Ixtal
                if (this.IGNORE_TRAITS.includes(t) && mode !== 'ryze-unlock') continue;
                const increment = (u.name === "Annie" && t === "Arcanist") ? this.ANNIE_ARCANIST_COUNT : 1;
                counts[t] = (counts[t] || 0) + increment;
            }
        }
        
        for (let i = 0; i < emblems.length; i++) {
            const emb = emblems[i];
            if (!this.IGNORE_TRAITS.includes(emb) || mode === 'ryze-unlock') {
                counts[emb] = (counts[emb] || 0) + 1;
            }
        }
        
        let score = 0;
        let activeTraits = new Set();

        for (const trait in counts) {
            const count = counts[trait];
            if (count > this.MAX_TRAIT_COUNT) {
                score -= this.INVALID_COMP_PENALTY;
            }
            if (this.FORBIDDEN_TRAITS[trait] !== undefined && count > this.FORBIDDEN_TRAITS[trait]) {
                score -= this.INVALID_COMP_PENALTY;
            }
            
            const traitData = this.TRAITS_DATA[trait];
            if (traitData) {
                const breakpoints = traitData.breakpoints;
                let highest = 0;
                for (let i = 0; i < breakpoints.length; i++) {
                    if (breakpoints[i] <= count) highest = breakpoints[i];
                }

                if (highest > 0 || (traitData.type === "origin" && !breakpoints)) {
                    // Bronze mode excludes Targon from counting as an active trait for scoring purposes
                    const isBronzeExclusion = (mode === 'bronze-for-life' && trait === "Targon");
                    
                    if (!isBronzeExclusion) {
                        activeTraits.add(trait);
                    }

                    if (trait === "Targon") {
                        // Small fixed bonus at breakpoint 1, no scaling
                        // Exclude from scoring in bronze mode
                        if (highest >= 1 && mode !== 'bronze-for-life') {
                            score += 200; 
                        }
                    } else {
                        if (mode === 'bronze-for-life') {
                            // In bronze mode, non-excluded traits get fixed score per reached breakpoint
                            score += 1 * this.BREAKPOINT_SCORE_MULTIPLIER;
                        } else {
                            score += highest * this.BREAKPOINT_SCORE_MULTIPLIER;
                        }
                    }
                } else {
                    // Penalize units with single traits if that trait breakpoint is not reached.
                    const isBronzeExclusion = (mode === 'bronze-for-life' && trait === "Targon");
                    if (!isBronzeExclusion) {
                        for (let i = 0; i < board.length; i++) {
                            const u = board[i];
                            if (u.traits.length === 1 && u.traits[0] === trait) {
                                score -= this.MISSING_CARRY_PENALTY;
                            }
                        }
                    }
                }
            } else if (count === 1 && mode !== 'bronze-for-life') {
                score += this.UNIQUE_TRAIT_SCORE;
            }
        }

        const activeOrigins = this.getActiveOrigins(counts);
        const activeOriginsCount = activeOrigins.length;

        if (mode === 'world-runes' || mode === 'ryze-unlock') {
            if (activeOriginsCount < 4) {
                if (board.length >= targetSize) {
                    score -= this.INVALID_COMP_PENALTY;
                } else {
                    // Extremely aggressive guidance for intermediate steps
                    score += activeOriginsCount * 10000;
                }
            } else {
                score += 100000; // Requirement met massive bonus
            }
        }

        for (const targetTrait in mustIncludeTraits) {
            const requiredValue = mustIncludeTraits[targetTrait];
            const currentCount = (counts[targetTrait] || 0);
            if (board.length >= targetSize) {
                if (currentCount < requiredValue) {
                    score -= this.INVALID_COMP_PENALTY;
                }
            } else {
                // Guidance for beam search: partial progress towards must-include traits is good
                score += currentCount * 5000;
            }
        }
        
        let carryCount = 0;
        let highCostCarryCount = 0;
        let threeCostCount = 0;
        let lowCostCount = 0; 
        let highCostCount = 0;

        for (let i = 0; i < board.length; i++) {
            const u = board[i];
            if (u.is_carry) {
                carryCount++;
                if (u.cost >= 4) highCostCarryCount++;
            }
            if (u.cost === 3) threeCostCount++;
            if (u.cost <= 2) lowCostCount++;
            if (u.cost >= 4) highCostCount++;
        }

        if (board.length >= targetSize) {
            if (targetSize <= 6) {
                if (carryCount < 1) score -= this.MISSING_CARRY_PENALTY;
            } else if (targetSize <= 8) {
                if (carryCount < 2 || highCostCarryCount < 1) score -= this.MISSING_CARRY_PENALTY;
            } else {
                if (carryCount < 3 || highCostCarryCount < 2) score -= this.MISSING_CARRY_PENALTY;
            }
        } else {
            // Guidance for carries in partial boards
            score += carryCount * 2000;
            score += highCostCarryCount * 3000;
        }
        
        const costWeight = (targetSize >= 7) ? this.UNIT_COST_TIEBREAKER_WEIGHT : -10; 
        const bonusTraitWeight = (targetSize >= 7) ? 100 : 1;

        for (let i = 0; i < board.length; i++) {
            const u = board[i];
            if (u.locked) score -= this.LOCKED_UNIT_PENALTY;
            
            let isTargonUnit = false;
            for (let j = 0; j < u.traits.length; j++) {
                const t = u.traits[j];
                if (t === "Yordle") score -= this.YORDLE_PENALTY;
                if (this.BONUS_TRAITS.includes(t)) score += bonusTraitWeight;
                if (t === "Targon") isTargonUnit = true;
            }

            if (isTargonUnit && mode !== 'bronze-for-life') {
                score += this.BREAKPOINT_SCORE_MULTIPLIER * 0.2; 
            }

            score += u.cost * costWeight;
        }
        
        if (board.length >= targetSize) {
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
        }
        
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

    countTotalCombos(targetSlots, pool) {
        const memo = new Map();
        const count = (slots, pIdx) => {
            if (slots === 0) return 1;
            if (slots < 0 || pIdx >= pool.length) return 0;
            const key = `${slots}-${pIdx}`;
            if (memo.has(key)) return memo.get(key);
            const uSlots = pool[pIdx].slots || 1;
            const result = count(slots - uSlots, pIdx + 1) + count(slots, pIdx + 1);
            memo.set(key, result);
            return result;
        };
        return count(targetSlots, 0);
    }

    calculateHeuristicScore(u, size, synergyBase, mustIncludeTraits, heuristic) {
        let score = 0;
        const mustTraitNames = Object.keys(mustIncludeTraits);
        
        for (let i = 0; i < u.traits.length; i++) {
            const t = u.traits[i];
            if (synergyBase.has(t)) score += 20;
            if (mustTraitNames.includes(t)) score += 30;
        }
        
        score += u.cost * 3;
        if (u.is_carry) score += 12;
        score += u.traits.length * 2;
        
        return score;
    }

    getCandidates(pool, size, emblems, mustIncludeNames, mustIncludeTraits, heuristic, mode = 'default') {
        const targetSlots = size;
        let fixedUnits = [];
        
        // Ensure mustIncludeNames is an array for processing
        let namesArray = Array.isArray(mustIncludeNames) ? mustIncludeNames : (mustIncludeNames ? [mustIncludeNames] : []);
        
        if (namesArray.length > 0) {
            fixedUnits = this.UNITS.filter(u => namesArray.includes(u.name));
        }
        const synergyBase = new Set([...emblems, ...this.BONUS_TRAITS]);
        fixedUnits.forEach(f => f.traits.forEach(t => synergyBase.add(t)));
        
        let candidates = pool.filter(u => !fixedUnits.some(f => f.name === u.name));
        
        // Pass 1: Direct Synergy Scoring
        const unitScores = new Map();
        const mustTraitNames = Object.keys(mustIncludeTraits);
        for (const u of candidates) {
            let score = 0;
            for (const t of u.traits) {
                if (synergyBase.has(t)) score += 20;
                if (mustTraitNames.includes(t)) score += 30;
                
                // Prioritize origins if in world-runes mode
                if (mode === 'world-runes') {
                    const traitData = this.TRAITS_DATA[t];
                    if (traitData && traitData.type === 'origin') {
                        score += 50; 
                    }
                }
            }
            score += u.cost * 2;
            if (u.is_carry) score += 10;
            unitScores.set(u.name, score);
        }

        // Pass 2: Identify "Bridge Traits" 
        const bridgeTraitWeights = {};
        const topDirectUnits = [...candidates]
            .sort((a, b) => unitScores.get(b.name) - unitScores.get(a.name))
            .slice(0, 20); 
        
        topDirectUnits.forEach(u => {
            u.traits.forEach(t => {
                if (!synergyBase.has(t)) {
                    bridgeTraitWeights[t] = (bridgeTraitWeights[t] || 0) + 15; 
                }
            });
        });

        // Pass 3: Final Graph-based Re-scoring
        candidates.sort((a, b) => {
            const getTotalScore = (u) => {
                let s = unitScores.get(u.name);
                for (const t of u.traits) {
                    if (bridgeTraitWeights[t]) {
                        s += bridgeTraitWeights[t];
                        if (bridgeTraitWeights[t] > 30) s += 20;
                    }
                }
                s += u.traits.length * 8; 
                return s;
            };
            return getTotalScore(b) - getTotalScore(a);
        });
        
        let poolSize = 40;
        if (mode === 'world-runes' || mode === 'ryze-unlock') poolSize = 100;
        else if (heuristic === 'none') poolSize = 100;
        else if (heuristic === 'aggressive') poolSize = 30;
        else if (heuristic === 'blitz') poolSize = 22;
        else { // standard
            poolSize = size >= 9 ? 45 : 40;
        }

        // Apply dynamic threshold
        let finalCandidates = candidates.slice(0, poolSize);
        const lastScoreCandidate = finalCandidates[finalCandidates.length - 1];
        if (lastScoreCandidate) {
            const lastScore = this.calculateHeuristicScore(lastScoreCandidate, size, synergyBase, mustIncludeTraits, heuristic);
            for (let i = poolSize; i < candidates.length; i++) {
                const score = this.calculateHeuristicScore(candidates[i], size, synergyBase, mustIncludeTraits, heuristic);
                if (score >= lastScore * 0.95) {
                    finalCandidates.push(candidates[i]);
                } else break;
                if (finalCandidates.length >= poolSize + 10) break;
            }
        }

        const fixedSlots = fixedUnits.reduce((acc, u) => acc + (u.slots || 1), 0);
        const neededSlots = targetSlots - fixedSlots;

        return { 
            candidates: finalCandidates, 
            neededSlots, 
            fixedUnits 
        };
    }

    async findBestBoards(pool, size, emblems, mustIncludeNames = [], mode = 'default', mustIncludeTraits = {}, limit = 3, onProgress = null, heuristic = 'standard') {
        this.isCancelled = false;
        
        const expandedMustInclude = this.expandMustInclude(mustIncludeNames);

        if (mode === 'world-runes' || mode === 'ryze-unlock') {
            return this.runeSearch(pool, size, emblems, expandedMustInclude, mode, mustIncludeTraits, limit, onProgress);
        }

        if (heuristic === 'super') {
            return this.beamSearch(pool, size, emblems, expandedMustInclude, mode, mustIncludeTraits, limit, onProgress);
        }

        const { candidates, neededSlots, fixedUnits } = this.getCandidates(pool, size, emblems, expandedMustInclude, mustIncludeTraits, heuristic, mode);
        const targetSlots = size;
        
        const total = this.countTotalCombos(neededSlots, candidates);
        let processed = 0;
        let results = [];
        
        const batchSize = 50000; // Increased throughput
        const generator = this.getCombos(neededSlots, candidates);
        
        let done = false;
        while (!done) {
            if (this.isCancelled) return { results: [], totalProcessed: processed };

            for (let i = 0; i < batchSize; i++) {
                const { value: combo, done: comboDone } = generator.next();
                if (comboDone) {
                    done = true;
                    break;
                }
                processed++;
                const currentBoard = [...combo, ...fixedUnits];
                const { score, counts } = this.scoreBoard(currentBoard, emblems, targetSlots, mode, mustIncludeTraits, expandedMustInclude);
                if (score > -1000000) { 
                    results.push({ score, board: currentBoard, counts });
                    
                    // Keep results array small
                    if (results.length > limit * 50) {
                        results.sort((a, b) => b.score - a.score);
                        results = results.slice(0, limit * 10);
                    }
                }
            }
            if (onProgress) onProgress(processed, total);
            await new Promise(resolve => setTimeout(resolve, 0));
        }
        results.sort((a, b) => b.score - a.score);
        const finalResults = results.slice(0, limit);
        return {
            results: finalResults,
            totalProcessed: processed
        };
    }

    async runeSearch(pool, targetSize, emblems, mustIncludeNames, mode, mustIncludeTraits, limit, onProgress) {
        // Specialized beam search that prioritizes origin diversity
        const { candidates, neededSlots, fixedUnits } = this.getCandidates(pool, targetSize, emblems, mustIncludeNames, mustIncludeTraits, 'super', mode);
        
        let currentBeams = [{
            board: fixedUnits,
            score: 0,
            counts: {},
            origins: new Set()
        }];

        if (neededSlots <= 0) {
             const res = this.scoreBoard(fixedUnits, emblems, targetSize, mode, mustIncludeTraits, mustIncludeNames);
             return { results: [res], totalProcessed: 1 };
        }

        const BEAM_WIDTH_PER_ORIGIN_COUNT = 100;
        let totalEvaluated = 0;

        for (let step = 0; step < neededSlots; step++) {
            if (this.isCancelled) return { results: [], totalProcessed: totalEvaluated };
            let nextBeamsByOriginCount = {}; // Map originCount -> array of beams
            
            for (const beam of currentBeams) {
                for (const unit of candidates) {
                    if (beam.board.some(u => u.name === unit.name)) continue;
                    
                    const nextBoard = [...beam.board, unit];
                    const { score, counts } = this.scoreBoard(nextBoard, emblems, targetSize, mode, mustIncludeTraits, mustIncludeNames);
                    totalEvaluated++;

                    const activeOrigins = Object.keys(counts).filter(t => {
                        const meta = this.TRAITS_DATA[t];
                        if (!meta || meta.type !== 'origin') return false;
                        const breakpoints = meta.breakpoints;
                        
                        // Origin is active if it has a breakpoint met OR it is Targon with 1 unit
                        const hasBreakpoint = breakpoints && breakpoints.some(b => b <= counts[t]);
                        const isTargonActive = (t === 'Targon' && counts[t] >= 1);
                        
                        return hasBreakpoint || isTargonActive;
                    });
                    
                    const originCount = activeOrigins.length;
                    if (!nextBeamsByOriginCount[originCount]) nextBeamsByOriginCount[originCount] = [];
                    
                    nextBeamsByOriginCount[originCount].push({ 
                        board: nextBoard, 
                        score, 
                        counts,
                        originCount
                    });
                }
            }

            // Keep top N for each origin count to maintain diversity
            currentBeams = [];
            for (const count in nextBeamsByOriginCount) {
                const sorted = nextBeamsByOriginCount[count].sort((a, b) => b.score - a.score);
                const seen = new Set();
                let added = 0;
                for (const b of sorted) {
                    const key = b.board.map(u => u.name).sort().join('|');
                    if (!seen.has(key)) {
                        seen.add(key);
                        currentBeams.push(b);
                        added++;
                    }
                    if (added >= BEAM_WIDTH_PER_ORIGIN_COUNT) break;
                }
            }
            
            if (onProgress) onProgress(step + 1, neededSlots, totalEvaluated);
            await new Promise(resolve => setTimeout(resolve, 0));
        }

        currentBeams.sort((a, b) => b.score - a.score);
        const finalResults = currentBeams.slice(0, limit);
        return {
            results: finalResults,
            totalProcessed: totalEvaluated
        };
    }

    async beamSearch(pool, targetSize, emblems, mustIncludeNames, mode, mustIncludeTraits, limit, onProgress) {
        let fixedUnits = this.UNITS.filter(u => mustIncludeNames.includes(u.name));
        let currentBeams = [{
            board: fixedUnits,
            score: 0,
            counts: {}
        }];

        const fixedSlots = fixedUnits.reduce((acc, u) => acc + (u.slots || 1), 0);
        const neededSlots = targetSize - fixedSlots;
        if (neededSlots <= 0) {
             const res = this.scoreBoard(fixedUnits, emblems, targetSize, mode, mustIncludeTraits, mustIncludeNames);
             return { results: [res], totalProcessed: 1 };
        }

        const { candidates } = this.getCandidates(pool, targetSize, emblems, mustIncludeNames, mustIncludeTraits, 'super', mode);
        const BEAM_WIDTH = (mode === 'world-runes' || mode === 'ryze-unlock') ? 2000 : 200; 
        let totalEvaluated = 0;

        for (let step = 0; step < neededSlots; step++) {
            if (this.isCancelled) return { results: [], totalProcessed: totalEvaluated };
            let nextBeams = [];
            
            for (const beam of currentBeams) {
                for (const unit of candidates) {
                    if (beam.board.some(u => u.name === unit.name)) continue;
                    
                    const nextBoard = [...beam.board, unit];
                    // Score as if it was a full board to trigger trait logic
                    const { score, counts } = this.scoreBoard(nextBoard, emblems, targetSize, mode, mustIncludeTraits, mustIncludeNames);
                    totalEvaluated++;
                    nextBeams.push({ board: nextBoard, score, counts });
                }
            }

            // Sort and Deduplicate
            nextBeams.sort((a, b) => b.score - a.score);
            const seen = new Set();
            currentBeams = [];
            for (let i = 0; i < nextBeams.length; i++) {
                const b = nextBeams[i];
                const key = b.board.map(u => u.name).sort().join('|');
                if (!seen.has(key)) {
                    seen.add(key);
                    currentBeams.push(b);
                }
                if (currentBeams.length >= BEAM_WIDTH) break;
            }
            
            if (onProgress) onProgress(step + 1, neededSlots, totalEvaluated);
            await new Promise(resolve => setTimeout(resolve, 0));
        }

        currentBeams.sort((a, b) => b.score - a.score);
        const finalResults = currentBeams.slice(0, limit);
        return {
            results: finalResults,
            totalProcessed: totalEvaluated
        };
    }

    improveTeam(currentBoard, pool, emblems, mode = 'default', mustIncludeTraits = {}, mustIncludeNames = [], limit = 3) {
        const currentResult = this.scoreBoard(currentBoard, emblems, currentBoard.length, mode, mustIncludeTraits, mustIncludeNames);
        let bestScore = currentResult.score;
        let unitSuggestions = []; 

        for (let i = 0; i < currentBoard.length; i++) {
            const originalUnit = currentBoard[i];
            if (mustIncludeNames.includes(originalUnit.name)) continue;
            
            let candidatesForThisSlot = [];

            for (let j = 0; j < pool.length; j++) {
                const candidate = pool[j];
                if (currentBoard.some(u => u.name === candidate.name)) continue;
                
                let testBoard = [...currentBoard];
                testBoard[i] = candidate;
                const { score, counts } = this.scoreBoard(testBoard, emblems, testBoard.length, mode, mustIncludeTraits, mustIncludeNames);
                
                if (score > bestScore) {
                    candidatesForThisSlot.push({
                        score,
                        board: testBoard,
                        counts,
                        unit: candidate
                    });
                }
            }

            if (candidatesForThisSlot.length > 0) {
                candidatesForThisSlot.sort((a, b) => b.score - a.score);
                unitSuggestions.push({
                    replacedUnit: originalUnit,
                    candidates: candidatesForThisSlot.slice(0, 3),
                    bestScore: candidatesForThisSlot[0].score
                });
            }
        }

        unitSuggestions.sort((a, b) => b.bestScore - a.bestScore);
        
        return {
            currentCounts: currentResult.counts,
            suggestions: unitSuggestions.slice(0, limit)
        };
    }

    getBestNextUnits(currentBoard, pool, emblems, mode = 'default', mustIncludeTraits = {}, mustIncludeNames = [], limit = 5) {
        let suggestions = [];
        const boardNames = new Set(currentBoard.map(u => u.name));
        const currentScore = this.scoreBoard(currentBoard, emblems, currentBoard.length, mode, mustIncludeTraits, mustIncludeNames).score;

        for (const candidate of pool) {
            if (boardNames.has(candidate.name)) continue;

            const nextBoard = [...currentBoard, candidate];
            const result = this.scoreBoard(nextBoard, emblems, nextBoard.length, mode, mustIncludeTraits, mustIncludeNames);
            const scoreBoost = result.score - currentScore;

            if (scoreBoost > -100000) { // Only consider non-penalized additions
                suggestions.push({
                    unit: candidate,
                    scoreBoost: scoreBoost,
                    counts: result.counts
                });
            }
        }

        suggestions.sort((a, b) => b.scoreBoost - a.scoreBoost);
        return suggestions.slice(0, limit);
    }
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = TFTOptimizer;
}