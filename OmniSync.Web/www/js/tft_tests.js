class TFTTester {
    constructor(optimizer, data) {
        this.opt = optimizer;
        this.data = data;
    }

    assert(condition, message) {
        if (!condition) throw new Error(message);
    }

    runAll() {
        // This is a placeholder for the individual test calls managed by js/tft.js
        return [];
    }

    // --- EXISTING TESTS ---

    testLevel4Optimal() {
        const targetUnits = ["Caitlyn", "Kog'Maw", "Neeko", "Vi"];
        const pool = this.data.units.filter(u => 
            u.cost <= 2 || targetUnits.includes(u.name)
        );
        
        const results = this.opt.findBestBoards(pool, 4, [], []);
        this.assert(results.length >= 1, `No results found for level 4`);
        const res = results[0];
        const activeTraitsCount = Object.keys(res.counts).filter(t => {
            const breakpoints = this.data.traits[t];
            return breakpoints && breakpoints.some(b => b <= res.counts[t]);
        }).length;
        
        this.assert(activeTraitsCount >= 4, `Top Level 4 comp only has ${activeTraitsCount} active traits (expected >= 4). Board: ${res.board.map(u=>u.name).join(',')}`);
    }

    testAnnieTibbersLogic() {
        const annie = this.data.units.find(u => u.name === "Annie");
        const tibbers = this.data.units.find(u => u.name === "Tibbers");
        const res1 = this.opt.scoreBoard([annie], [], 8);
        this.assert(res1.score < -1000000, "Board with Annie but no Tibbers should be invalid");
        const res2 = this.opt.scoreBoard([annie, tibbers], [], 8);
        this.assert(res2.score > -1000000, "Board with Annie and Tibbers should be valid at level 8");
    }

    testLevel4CostConstraint() {
        const pool = this.data.units.filter(u => u.cost <= 3).slice(0, 20);
        const results = this.opt.findBestBoards(pool, 4, [], []);
        results.forEach(res => {
            const threeCostCount = res.board.filter(u => u.cost === 3).length;
            this.assert(threeCostCount <= 1, `Level 4 board has ${threeCostCount} 3-costs (limit 1)`);
        });
    }

    testLevel5CostConstraint() {
        // Optimization: Use a smaller pool for the constraint check to avoid long runtimes
        const pool = this.data.units.filter(u => u.cost <= 3).slice(0, 20);
        const results = this.opt.findBestBoards(pool, 5, [], []);
        
        results.forEach(res => {
            const threeCostCount = res.board.filter(u => u.cost === 3).length;
            this.assert(threeCostCount <= 2, `Level 5 board has ${threeCostCount} 3-costs (limit 2)`);
        });
    }

    testBronzeForLifeLogic() {
        const unit1 = this.data.units.find(u => u.name === "Nasus"); 
        const unit2 = this.data.units.find(u => u.name === "Renekton"); 
        const board = [unit1, unit2]; 
        const scoreDefault = this.opt.scoreBoard(board, [], 2, 'default').score;
        const scoreBronze = this.opt.scoreBoard(board, [], 2, 'bronze-for-life').score;
        this.assert(scoreBronze < scoreDefault, "Bronze For Life should score lower for high-tier traits than default mode");
    }

    testLevel10Constraints() {
        const cheapUnits = this.data.units.filter(u => u.cost <= 2).slice(0, 4);
        const expensiveUnits = this.data.units.filter(u => u.cost >= 4).slice(0, 6);
        const invalidBoard = [...cheapUnits, ...expensiveUnits]; 
        const res = this.opt.scoreBoard(invalidBoard, [], 10);
        this.assert(res.score < -1000000, "Level 10 board with 4 low-cost units should be invalid");
    }

    testSpecificLevelUnitRestrictions() {
        const kennen = this.data.units.find(u => u.name === "Kennen");
        const kobuko = this.data.units.find(u => u.name.includes("Kobuko"));
        const azir = this.data.units.find(u => u.cost === 5);
        this.assert(this.opt.scoreBoard([kennen], [], 5).score < -1000000, "Kennen invalid at lvl 5");
        this.assert(this.opt.scoreBoard([kobuko], [], 6).score < -1000000, "Kobuko invalid at lvl 6");
        this.assert(this.opt.scoreBoard([azir], [], 7).score < -1000000, "5-cost invalid at lvl 7");
    }

    testMustIncludeTraits() {
        const cait = this.data.units.find(u => u.name === "Caitlyn"); 
        const board = [cait];
        const res1 = this.opt.scoreBoard(board, [], 1, 'default', ["Longshot"]);
        this.assert(res1.score < -1000000, "Should be invalid if required trait is not active");
        const kog = this.data.units.find(u => u.name === "Kog'Maw");
        const res2 = this.opt.scoreBoard([cait, kog], [], 2, 'default', ["Longshot"]);
        this.assert(res2.score > -1000000, "Should be valid if required trait is active");
    }

    testAnnieOneArcanistFix() {
        const annie = this.data.units.find(u => u.name === "Annie");
        const tibbers = this.data.units.find(u => u.name === "Tibbers");
        const board = [annie, tibbers];
        const res = this.opt.scoreBoard(board, [], 8);
        this.assert(res.counts["Arcanist"] === 1, `Annie should only provide 1 Arcanist, but provided ${res.counts["Arcanist"]}`);
    }

    testTargonSpecialLogic() {
        const aphelios = this.data.units.find(u => u.name === "Aphelios"); 
        const anivia = this.data.units.find(u => u.name === "Anivia"); 
        const scoreAphelios = this.opt.scoreBoard([aphelios], [], 1).score;
        const scoreAnivia = this.opt.scoreBoard([anivia], [], 1).score;
        this.assert(scoreAphelios > scoreAnivia, "Targon inherent bonus failed");
        const diana = this.data.units.find(u => u.name === "Diana"); 
        const res2 = this.opt.scoreBoard([aphelios, diana], [], 8); 
        this.assert(res2.score < 1000, "Targon trait should not provide breakpoint bonus points");
    }

    testUnitReplacementPersistenceBug() {
        const pool = this.data.units.filter(u => u.cost <= 3);
        const results = this.opt.findBestBoards(pool, 4, [], []);
        const board = results[0].board;
        const originalUnit = board[0];
        const originalName = originalUnit.name;
        board[0] = this.data.units.find(u => u.name !== originalName);
        this.assert(originalUnit.name === originalName, "Original unit object was mutated!");
    }

    // --- NEW TESTS ---

    testSylasForbiddenUnits() {
        const sylas = this.data.units.find(u => u.name === "Sylas");
        const garen = this.data.units.find(u => u.name === "Garen");
        const res = this.opt.scoreBoard([sylas, garen], [], 8);
        this.assert(res.score < -1000000, "Sylas should not be allowed with Garen");
    }

    testLevel8FiveCostLimit() {
        const fiveCosts = this.data.units.filter(u => u.cost === 5).slice(0, 3);
        // Requirement says max 2 five costs at lvl 8.
        const res = this.opt.scoreBoard(fiveCosts, [], 8);
        const normalScore = fiveCosts.reduce((acc, u) => acc + (u.cost * 10), 0);
        this.assert(res.score < normalScore, "3 five-costs at Level 8 should have a penalty");
    }

    testLevel9Constraints() {
        const cheap = this.data.units.filter(u => u.cost <= 2).slice(0, 5); // 5 cheap
        const expensive = this.data.units.filter(u => u.cost >= 4).slice(0, 4); // 4 expensive
        const res = this.opt.scoreBoard([...cheap, ...expensive], [], 9);
        this.assert(res.score < -1000000, "Level 9 board with 5 cheap units should be invalid");
    }

    testTraitIgnoreList() {
        const unit1 = this.data.units.find(u => u.traits.includes("Ixtal"));
        if (!unit1) return; 
        const res = this.opt.scoreBoard([unit1], [], 8);
        this.assert(!res.counts["Ixtal"], "Ixtal should be ignored");
    }

    testForbiddenShurima() {
        const unit1 = this.data.units.find(u => u.name === "Azir"); // Shurima
        const unit2 = this.data.units.find(u => u.name === "Nasus"); // Shurima
        const unit3 = this.data.units.find(u => u.name === "Renekton"); // Shurima
        const res = this.opt.scoreBoard([unit1, unit2, unit3], [], 8);
        this.assert(res.score < -1000000, "Shurima count >= 3 should be invalid");
    }

    testCarryRequirements() {
        // Lvl 7-8: 2 carries, one must be 4+
        const lowCarry = this.data.units.find(u => u.is_carry && u.cost <= 3);
        const res1 = this.opt.scoreBoard([lowCarry], [], 8);
        this.assert(res1.score <= -100000, "Level 8 needs 2 carries, one 4+ (expected penalty)");

        const highCarry = this.data.units.find(u => u.is_carry && u.cost >= 4);
        const res2 = this.opt.scoreBoard([lowCarry, highCarry], [], 8);
        this.assert(res2.score > -100000, "Level 8 with low+high carry should be valid (no penalty)");
    }
}
