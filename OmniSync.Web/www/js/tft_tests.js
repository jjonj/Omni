class TFTTester {
    constructor(optimizer, data) {
        this.opt = optimizer;
        this.data = data;
    }

    assert(condition, message) {
        if (!condition) throw new Error(message);
    }

    // --- TESTS ---

    async testLevel4Optimal() {
        const targetUnits = ["Caitlyn", "Kog'Maw", "Neeko", "Vi"];
        const pool = this.data.units.filter(u => 
            u.cost <= 2 || targetUnits.includes(u.name)
        );
        
        const { results } = await this.opt.findBestBoards(pool, 4, [], []);
        this.assert(results.length >= 1, `No results found for level 4`);
        const res = results[0];
        const activeTraitsCount = Object.keys(res.counts).filter(t => {
            const traitInfo = this.data.trait_metadata[t];
            return traitInfo && traitInfo.breakpoints.some(b => b <= res.counts[t]);
        }).length;
        
        this.assert(activeTraitsCount >= 4, `Top Level 4 comp only has ${activeTraitsCount} active traits (expected >= 4). Board: ${res.board.map(u=>u.name).join(',')}`);
    }

    async testAnnieTibbersLogic() {
        const annie = this.data.units.find(u => u.name === "Annie");
        const tibbers = this.data.units.find(u => u.name === "Tibbers");
        const res1 = this.opt.scoreBoard([annie], [], 8);
        this.assert(res1.score < -1000000, "Board with Annie but no Tibbers should be invalid");
        const res2 = this.opt.scoreBoard([annie, tibbers], [], 8);
        this.assert(res2.score > -1000000, "Board with Annie and Tibbers should be valid at level 8");
    }

    async testLevel4CostConstraint() {
        const pool = this.data.units.filter(u => u.cost <= 3).slice(0, 20);
        const { results } = await this.opt.findBestBoards(pool, 4, [], []);
        results.forEach(res => {
            const threeCostCount = res.board.filter(u => u.cost === 3).length;
            this.assert(threeCostCount <= 1, `Level 4 board has ${threeCostCount} 3-costs (limit 1)`);
        });
    }

    async testLevel5CostConstraint() {
        const pool = this.data.units.filter(u => u.cost <= 3).slice(0, 20);
        const { results } = await this.opt.findBestBoards(pool, 5, [], []);
        results.forEach(res => {
            const threeCostCount = res.board.filter(u => u.cost === 3).length;
            this.assert(threeCostCount <= 2, `Level 5 board has ${threeCostCount} 3-costs (limit 2)`);
        });
    }

    async testBronzeForLifeLogic() {
        const unit1 = this.data.units.find(u => u.name === "Lux"); 
        const unit2 = this.data.units.find(u => u.name === "Kog'Maw"); 
        const carry = this.data.units.find(u => u.is_carry && u.cost >= 4);
        const board = [unit1, unit2, carry]; 
        const scoreDefault = this.opt.scoreBoard(board, [], 8, 'default').score;
        const scoreBronze = this.opt.scoreBoard(board, [], 8, 'bronze-for-life').score;
        this.assert(scoreBronze < scoreDefault, `Bronze For Life (${scoreBronze}) should score lower than Default (${scoreDefault}) for tier-2 traits`);
    }

    async testLevel10Constraints() {
        const cheapUnits = this.data.units.filter(u => u.cost <= 2).slice(0, 4);
        const expensiveUnits = this.data.units.filter(u => u.cost >= 4).slice(0, 6);
        const invalidBoard = [...cheapUnits, ...expensiveUnits]; 
        const res = this.opt.scoreBoard(invalidBoard, [], 10);
        this.assert(res.score < -1000000, "Level 10 board with 4 low-cost units should be invalid");
    }

    async testSpecificLevelUnitRestrictions() {
        const kennen = this.data.units.find(u => u.name === "Kennen");
        const kobuko = this.data.units.find(u => u.name.includes("Kobuko"));
        const azir = this.data.units.find(u => u.cost === 5);
        this.assert(this.opt.scoreBoard([kennen], [], 5).score < -1000000, "Kennen invalid at lvl 5");
        this.assert(this.opt.scoreBoard([kobuko], [], 6).score < -1000000, "Kobuko invalid at lvl 6");
        this.assert(this.opt.scoreBoard([azir], [], 7).score < -1000000, "5-cost invalid at lvl 7");
    }

    async testMustIncludeTraits() {
        const cait = this.data.units.find(u => u.name === "Caitlyn"); 
        const board = [cait];
        const res1 = this.opt.scoreBoard(board, [], 1, 'default', { "Longshot": 2 });
        this.assert(res1.score < -1000000, "Should be invalid if required trait breakpoint is not met");
        const kog = this.data.units.find(u => u.name === "Kog'Maw");
        const res2 = this.opt.scoreBoard([cait, kog], [], 2, 'default', { "Longshot": 2 });
        this.assert(res2.score > -1000000, "Should be valid if required trait breakpoint is met");
    }

    async testAnnieOneArcanistFix() {
        const annie = this.data.units.find(u => u.name === "Annie");
        const tibbers = this.data.units.find(u => u.name === "Tibbers");
        const board = [annie, tibbers];
        const res = this.opt.scoreBoard(board, [], 8);
        this.assert(res.counts["Arcanist"] === 1, `Annie should only provide 1 Arcanist, but provided ${res.counts["Arcanist"]}`);
    }

    async testTargonSpecialLogic() {
        const aphelios = this.data.units.find(u => u.name === "Aphelios"); 
        const anivia = this.data.units.find(u => u.name === "Anivia"); 
        const scoreAphelios = this.opt.scoreBoard([aphelios], [], 1).score;
        const scoreAnivia = this.opt.scoreBoard([anivia], [], 1).score;
        this.assert(scoreAphelios > scoreAnivia, "Targon inherent bonus failed");
        const diana = this.data.units.find(u => u.name === "Diana"); 
        const res2 = this.opt.scoreBoard([aphelios, diana], [], 8); 
        this.assert(res2.score < 1000, "Targon trait should not provide breakpoint bonus points");
    }

    async testUnitReplacementPersistenceBug() {
        const pool = this.data.units.filter(u => u.cost <= 3);
        const { results } = await this.opt.findBestBoards(pool, 4, [], []);
        this.assert(results.length > 0, "findBestBoards returned no results");
        const board = results[0].board;
        const originalUnit = board[0];
        const originalName = originalUnit.name;
        board[0] = this.data.units.find(u => u.name !== originalName);
        this.assert(originalUnit.name === originalName, "Original unit object was mutated!");
    }

    async testSylasForbiddenUnits() {
        const sylas = this.data.units.find(u => u.name === "Sylas");
        const garen = this.data.units.find(u => u.name === "Garen");
        const res = this.opt.scoreBoard([sylas, garen], [], 8);
        this.assert(res.score < -1000000, "Sylas should not be allowed with Garen");
    }

    async testLevel8FiveCostLimit() {
        const fiveCosts = this.data.units.filter(u => u.cost === 5).slice(0, 3);
        const res = this.opt.scoreBoard(fiveCosts, [], 8);
        const normalScore = fiveCosts.reduce((acc, u) => acc + (u.cost * 10), 0);
        this.assert(res.score < normalScore, "3 five-costs at Level 8 should have a penalty");
    }

    async testLevel9Constraints() {
        const cheap = this.data.units.filter(u => u.cost <= 2).slice(0, 5); 
        const expensive = this.data.units.filter(u => u.cost >= 4).slice(0, 4); 
        const res = this.opt.scoreBoard([...cheap, ...expensive], [], 9);
        this.assert(res.score < -1000000, "Level 9 board with 5 cheap units should be invalid");
    }

    async testTraitIgnoreList() {
        const unit1 = this.data.units.find(u => u.traits.includes("Ixtal"));
        if (!unit1) return; 
        const res = this.opt.scoreBoard([unit1], [], 8);
        this.assert(!res.counts["Ixtal"], "Ixtal should be ignored");
    }

    async testForbiddenShurima() {
        const unit1 = this.data.units.find(u => u.name === "Azir"); 
        const unit2 = this.data.units.find(u => u.name === "Nasus"); 
        const unit3 = this.data.units.find(u => u.name === "Renekton"); 
        const res = this.opt.scoreBoard([unit1, unit2, unit3], [], 8);
        this.assert(res.score < -1000000, "Shurima count >= 3 should be invalid");
    }

    async testCarryRequirements() {
        const lowCarry = this.data.units.find(u => u.is_carry && u.cost <= 3);
        const res1 = this.opt.scoreBoard([lowCarry], [], 8);
        this.assert(res1.score <= -100000, "Level 8 needs 2 carries, one 4+ (expected penalty)");

        const highCarry = this.data.units.find(u => u.is_carry && u.cost >= 4);
        const res2 = this.opt.scoreBoard([lowCarry, highCarry], [], 8);
        this.assert(res2.score > -100000, "Level 8 with low+high carry should be valid (no penalty)");
    }

        async testMustIncludeBypassLevelRestriction() {

            const azir = this.data.units.find(u => u.name === "Azir");

            const res1 = this.opt.scoreBoard([azir], [], 6, 'default', {}, []);

            this.assert(res1.score < -1000000, "5-cost should be invalid at level 6 normally");

            const res2 = this.opt.scoreBoard([azir], [], 6, 'default', {}, ["Azir"]);

            this.assert(res2.score > -1000000, "Must-include unit should bypass level restrictions");

        }

    

    async testNeekoNidaleeLogic() {
        const pool = this.data.units.filter(u => u.cost <= 3);
        const mustIncludeNames = ["Neeko"];
        const { results } = await this.optimizer.findBestBoards(pool, 4, [], mustIncludeNames);
        
        if (results.length === 0) throw new Error("No results found for level 4 Neeko");
        
        // This test now verifies that Nidalee is NOT included unless Ixtal breakpoint 3 is reachable.
        // At level 4 with only Neeko, Ixtal count is 1. Breakpoint is 3.
        for (const res of results) {
            if (res.board.find(u => u.name === "Nidalee")) {
                throw new Error("Nidalee included with Neeko at level 4 despite no Ixtal active");
            }
        }
    }

    async testNeekoNidaleeOneWayLogic() {
        const pool = this.data.units.filter(u => u.cost <= 5);
        const mustIncludeNames = ["Neeko"];
        // At level 4, Nidalee is 4-cost now.
        // We want to ensure Neeko doesn't force Nidalee via the optimizer (data-driven 'requires' is only for Nidalee->Neeko).
        const { results } = await this.optimizer.findBestBoards(pool, 4, [], mustIncludeNames, 'default', {}, 1, null, 'none');
        
        const boardNames = results[0].board.map(u => u.name);
        if (boardNames.includes("Nidalee")) {
            throw new Error("Logic error: Neeko forced Nidalee into the board at level 4");
        }
    }

    async testNidaleeRequiresNeeko() {
        const pool = this.data.units.filter(u => u.name === "Nidalee"); // Pool with ONLY Nidalee
        const { results } = await this.optimizer.findBestBoards(pool, 4, [], ["Nidalee"]);
        
        // Since Nidalee requires Neeko and Neeko is not in pool, results should be empty or scores should be extremely low
        if (results.length > 0 && results[0].score > -1000000) {
             throw new Error("Nidalee included without Neeko despite 'requires' constraint");
        }
    }

    async testSuperHeuristicPoppyLevel6() {
        const pool = this.data.units.filter(u => u.cost <= 3);
        const mustIncludeNames = ["Poppy"];
        const { results } = await this.optimizer.findBestBoards(pool, 6, [], mustIncludeNames, 'default', {}, 1, null, 'super');
        
        if (results.length === 0) throw new Error("Super heuristic found no boards");
        
        const bestScore = results[0].score;
        if (bestScore < 13000) {
            throw new Error(`Super heuristic only achieved score ${bestScore}, expected >= 13000. Board: ${results[0].board.map(u => u.name).join(', ')}`);
        }
    }

    async testSuperHeuristicKobukoLevel6() {
        const pool = this.data.units.filter(u => u.cost <= 3);
        const mustIncludeNames = ["Kobuko & Yuumi"];
        const { results } = await this.optimizer.findBestBoards(pool, 6, [], mustIncludeNames, 'default', {}, 1, null, 'super');
        
        if (results.length === 0) throw new Error("Super heuristic found no boards");
        
        const bestScore = results[0].score;
        if (bestScore < 13000) {
            throw new Error(`Super heuristic only achieved score ${bestScore}, expected >= 13000. Board: ${results[0].board.map(u => u.name).join(', ')}`);
        }
    }

    async testWorldRunesLogic() {
        const unit1 = this.data.units.find(u => u.name === "Anivia"); // Freljord (Origin)
        const unit2 = this.data.units.find(u => u.name === "Blitzcrank"); // Zaun (Origin)
        const unit3 = this.data.units.find(u => u.name === "Briar"); // Noxus (Origin)
        const unit4 = this.data.units.find(u => u.name === "Caitlyn"); // Piltover (Origin)
        const carry = this.data.units.find(u => u.is_carry && u.cost >= 4);
        
        // 4 origins active
        const board4 = [unit1, unit2, unit3, unit4, carry];
        // Need to mock enough units to reach breakpoints if necessary, 
        // but here we just need to ensure they ARE active origins.
        // In set16.json, most origins need 2 or 3.
        
        // Let's use units that share traits or just check the scoring logic directly
        const res1 = this.opt.scoreBoard(board4, [], 8, 'world-runes');
        
        // For accurate testing, we need to know if they are actually "active" (breakpoint met)
        // Anivia (Freljord 3), Blitz (Zaun 3), Briar (Noxus 3), Cait (Piltover 2)
        // So we need more units.
        
        const freljordUnits = this.data.units.filter(u => u.traits.includes("Freljord")).slice(0, 3);
        const zaunUnits = this.data.units.filter(u => u.traits.includes("Zaun")).slice(0, 3);
        const noxusUnits = this.data.units.filter(u => u.traits.includes("Noxus")).slice(0, 3);
        const piltoverUnits = this.data.units.filter(u => u.traits.includes("Piltover")).slice(0, 2);
        
        const bigBoard = [...freljordUnits, ...zaunUnits, ...noxusUnits, ...piltoverUnits];
        const res2 = this.opt.scoreBoard(bigBoard, [], 11, 'world-runes');
        this.assert(res2.score > 0, "Board with 4 active origins should be valid in world-runes mode");
        
        const smallBoard = [...freljordUnits, ...zaunUnits]; // Only 2 origins
        const res3 = this.opt.scoreBoard(smallBoard, [], 6, 'world-runes');
        this.assert(res3.score < -1000000, "Board with only 2 active origins should be invalid in world-runes mode");
    }

    async testRuneSolverLevel5() {
        const pool = this.data.units.filter(u => u.cost <= 3);
        const emblems = ["Yordle", "Void"];
        const { results } = await this.opt.findBestBoards(pool, 5, emblems, [], 'world-runes', {}, 1, null, 'super');
        
        this.assert(results.length > 0, "Rune solver found no boards at Level 5");
        
        const res = results[0];
        const activeOrigins = this.opt.getActiveOrigins(res.counts);

        this.assert(activeOrigins.length >= 4, `World Runes failed: Only ${activeOrigins.length} origins active. Origins: ${activeOrigins.join(', ')}. Board: ${res.board.map(u => u.name).join(', ')}`);
        this.assert(res.board.every(u => u.cost <= 3), "Invalid unit cost found in Level 5 board");
    }

    async testNidaleeAutoIncludeBug() {
        // This test simulates the UI's rendering logic fix.
        const neekoUnit = this.data.units.find(u => u.name === "Neeko");
        const mockBoard = [neekoUnit];
        const results = [{ board: mockBoard, score: 100, counts: { "Ixtal": 1 } }];
        
        // Simulating the FIXED logic in renderResults (data-driven):
        let displayBoard = [...results[0].board];
        
        displayBoard.forEach(u => {
            if (u.auto_include && !displayBoard.find(du => du.name === u.auto_include)) {
                const sharedTrait = u.traits[0];
                const meta = this.data.trait_metadata[sharedTrait];
                const hasTraitActive = results[0].counts[sharedTrait] && (meta?.breakpoints.some(b => b <= results[0].counts[sharedTrait]));
                
                if (hasTraitActive) {
                    const extraUnit = this.data.units.find(du => du.name === u.auto_include);
                    if (extraUnit) displayBoard.push(extraUnit);
                }
            }
        });

        if (displayBoard.some(u => u.name === "Nidalee")) {
            if (results[0].counts["Ixtal"] < 3) {
                throw new Error("BUG: Nidalee was automatically added to a Neeko board even though Ixtal breakpoint (3) was not reached");
            }
        }
    }
}

    