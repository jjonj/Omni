let tftData = null;
let currentConfig = null;
let compRules = null;
let optimizer = null;

let selectedMustInclude = []; 
let selectedCurrentTeam = [];
let selectedEmblems = [];
let activeDropZone = 'must-include';
let unitFilter = 'all';
let unitAlphaFilter = null;
let alphaFilterTimeout = null;
let unitSortMode = 'alpha'; // 'alpha' or 'smart'
let emblemSearch = '';
let highlightedTrait = null;

let factoryDefaultDisabled = [];
let userDefaultDisabledUnits = []; 
let activeDisabledUnits = [];      

let hubConnection = null;
let lastReceivedClipboard = "";

// Add Mode State
let isAddMode = false;
let addModeBuffer = "";
let addModeIndicator = null;
let addedItemsHistory = []; // History of { name, type, zone } for undo
let highlightedUnitIndex = -1;

const CUSTOM_UNIT_SHORTCUTS = {
    "an": "Anivia", "ai": "Annie", "br": "Briar", "bm": "Braum", "bk": "Brock",
    "ko": "Kog'Maw", "kb": "Kobuko & Yuumi", "lu": "Lulu", "lx": "Lux",
    "lc": "Lucian & Senna", "sh": "Shen", "sv": "Shyvana", "vg": "Viego",
    "vi": "Vi", "oi": "Orianna", "or": "Ornn",
    "si": "Sion", "sd": "Singed", "tr": "Tristana", "ty": "Tryndamere",
    "yo": "Yorick", "ye": "Yone", "dm": "Dr. Mundo", "dr": "Draven",
    "gp": "Gangplank", "ga": "Garen", "go": "Galio", "lb": "LeBlanc",
    "le": "Leona", "mi": "Milio", "mf": "Miss Fortune", "na": "Nautilus",
    "ns": "Nasus", "se": "Sejuani", "sp": "Seraphine", "st": "Sett",
    "fi": "Fizz", "fs": "Fiddlesticks", "ks": "Kai'Sa", "ka": "Kalista",
    "tc": "Taric", "tk": "Tahm Kench", "zi": "Ziggs", "zn": "Zilean",
    "ba": "Bard", "bn": "Baron Naashor"
};

function cycleUnitHighlight() {
    const activeZoneId = activeDropZone === 'current-team' ? 'current-team-zone' : 'must-include-zone';
    const zone = document.getElementById(activeZoneId);
    if (!zone) return;

    const units = zone.querySelectorAll('.draggable-item');
    if (units.length === 0) {
        highlightedUnitIndex = -1;
        return;
    }

    // Remove old highlight
    if (highlightedUnitIndex >= 0 && highlightedUnitIndex < units.length) {
        units[highlightedUnitIndex].classList.remove('unit-highlight-active');
    }

    highlightedUnitIndex++;
    if (highlightedUnitIndex >= units.length) highlightedUnitIndex = 0;

    // Add new highlight
    units[highlightedUnitIndex].classList.add('unit-highlight-active');
    
    // Inject style if not present
    if (!document.getElementById('unit-highlight-style')) {
        const style = document.createElement('style');
        style.id = 'unit-highlight-style';
        style.innerHTML = `
            .draggable-item.unit-highlight-active {
                outline: 3px solid white !important;
                transform: scale(1.1);
                z-index: 100;
                transition: all 0.1s ease;
            }
        `;
        document.head.appendChild(style);
    }
}
let selectedResultIndex = -1;

function toggleLevelCheckbox(lvl) {
    const cb = document.querySelector(`.lvl-cb[value="${lvl}"]`);
    if (cb) {
        cb.checked = !cb.checked;
        cb.dispatchEvent(new Event('change'));
    }
}

function cycleResults(direction) {
    const cards = document.querySelectorAll('.result-card');
    if (cards.length === 0) return;

    // Remove old highlight
    if (selectedResultIndex >= 0 && selectedResultIndex < cards.length) {
        cards[selectedResultIndex].classList.remove('result-highlighted');
    }

    selectedResultIndex += direction;
    
    // Wrap around
    if (selectedResultIndex >= cards.length) selectedResultIndex = 0;
    if (selectedResultIndex < 0) selectedResultIndex = cards.length - 1;

    // Add new highlight and scroll into view
    const targetCard = cards[selectedResultIndex];
    targetCard.classList.add('result-highlighted');
    targetCard.scrollIntoView({ behavior: 'smooth', block: 'center' });

    // Inject temporary style for result highlight if not present
    if (!document.getElementById('result-highlight-style')) {
        const style = document.createElement('style');
        style.id = 'result-highlight-style';
        style.innerHTML = `
            .result-card.result-highlighted {
                border-color: var(--accent) !important;
                box-shadow: 0 0 15px rgba(10, 132, 255, 0.4) !important;
                background: rgba(10, 132, 255, 0.05) !important;
                transform: scale(1.02);
                transition: all 0.2s ease;
            }
        `;
        document.head.appendChild(style);
    }
}

function enterAddMode() {
    if (isAddMode) {
        exitAddMode();
        return;
    }
    
    // Resume audio context if needed
    if (typeof CortexAudio !== 'undefined') CortexAudio.init();

    isAddMode = true;
    addModeBuffer = "";
    showAddModeIndicator();
    
    // We only need local listener if we're focused and NOT using global hub input
    // But for simplicity, we'll keep it and just ensure it doesn't double-fire if hub is also sending
    // Actually, hub input is better for "no focus".
    document.addEventListener('keydown', handleAddModeKeydown);
    
    // Add orange glow to active zone
    const activeZoneId = activeDropZone === 'current-team' ? 'current-team-zone' : 'must-include-zone';
    const activeZone = document.getElementById(activeZoneId);
    if (activeZone) {
        activeZone.classList.add('add-mode-active');
        // Inject temporary style if not present
        if (!document.getElementById('add-mode-style')) {
            const style = document.createElement('style');
            style.id = 'add-mode-style';
            style.innerHTML = `
                .drop-zone.add-mode-active {
                    border-color: #ff9500 !important;
                    background: rgba(255, 149, 0, 0.1) !important;
                    box-shadow: 0 0 20px rgba(255, 149, 0, 0.4) !important;
                    transition: all 0.2s ease;
                }
                .add-mode-active .placeholder-text {
                    color: #ff9500 !important;
                }
                .add-mode-match {
                    animation: add-mode-flash 0.5s ease;
                }
                @keyframes add-mode-flash {
                    0% { background: #32d74b; transform: scale(1); }
                    50% { background: #32d74b; transform: scale(1.1); }
                    100% { background: #ff9500; transform: scale(1); }
                }
                .add-mode-error {
                    animation: add-mode-shake 0.3s ease;
                    background: #ff453a !important;
                }
                @keyframes add-mode-shake {
                    0%, 100% { transform: translateX(0); }
                    25% { transform: translateX(-5px); }
                    75% { transform: translateX(5px); }
                }
            `;
            document.head.appendChild(style);
        }
    }
    console.log("TFT: Entered Add Mode");
}

function handleTftGlobalInput(key) {
    if (!isAddMode) return;

    if (key === "Tab") {
        cycleActiveZone();
    } else if (key === "Backspace") {
        if (addModeBuffer.length > 0) {
            addModeBuffer = addModeBuffer.slice(0, -1);
        } else {
            // Check if we have a highlighted unit first
            const activeZoneId = activeDropZone === 'current-team' ? 'current-team-zone' : 'must-include-zone';
            const zone = document.getElementById(activeZoneId);
            const items = zone ? zone.querySelectorAll('.draggable-item') : [];
            
            if (highlightedUnitIndex >= 0 && highlightedUnitIndex < items.length) {
                const item = items[highlightedUnitIndex];
                const name = item.dataset.name;
                const type = item.dataset.type;
                removeItem(name, type, activeDropZone);
                highlightedUnitIndex = -1;
            } else if (addedItemsHistory.length > 0) {
                const lastItem = addedItemsHistory.pop();
                removeItem(lastItem.name, lastItem.type, lastItem.zone);
            }
        }
    } else if (key === "Enter") {
        addModeBuffer = "";
    } else if (key.length === 1) {
        addModeBuffer += key.toLowerCase();
        if (tryProcessBuffer()) {
            addModeBuffer = "";
        }
    }
    updateAddModeIndicator();
}

function exitAddMode() {
    isAddMode = false;
    addModeBuffer = "";
    hideAddModeIndicator();
    document.removeEventListener('keydown', handleAddModeKeydown);
    
    // Remove orange glow from all zones
    document.querySelectorAll('.drop-zone').forEach(el => {
        el.classList.remove('add-mode-active');
    });
    console.log("TFT: Exited Add Mode");
}

function showAddModeIndicator() {
    if (!addModeIndicator) {
        addModeIndicator = document.createElement('div');
        addModeIndicator.id = 'add-mode-indicator';
        addModeIndicator.style.position = 'absolute';
        addModeIndicator.style.padding = '8px 12px';
        addModeIndicator.style.background = '#ff9500';
        addModeIndicator.style.color = 'black';
        addModeIndicator.style.borderRadius = '6px';
        addModeIndicator.style.boxShadow = '0 4px 12px rgba(0,0,0,0.4)';
        addModeIndicator.style.zIndex = '9999';
        addModeIndicator.style.fontWeight = 'bold';
        addModeIndicator.style.fontFamily = 'var(--font-mono)';
        addModeIndicator.style.display = 'flex';
        addModeIndicator.style.flexDirection = 'column';
        addModeIndicator.style.gap = '2px';
        addModeIndicator.style.pointerEvents = 'none'; // Don't block clicks
        
        // Append to the pools zone for relative positioning
        const poolsZone = document.querySelector('.pools-zone');
        if (poolsZone) {
            poolsZone.style.position = 'relative'; // Ensure context
            poolsZone.appendChild(addModeIndicator);
            // Position top-left of pools zone
            addModeIndicator.style.top = '10px';
            addModeIndicator.style.left = '10px';
        } else {
            document.body.appendChild(addModeIndicator);
            addModeIndicator.style.position = 'fixed';
            addModeIndicator.style.top = '100px';
            addModeIndicator.style.left = '50%';
        }
    }
    addModeIndicator.style.display = 'flex';
    updateAddModeIndicator();
}

function hideAddModeIndicator() {
    if (addModeIndicator) addModeIndicator.style.display = 'none';
}

function updateAddModeIndicator() {
    if (addModeIndicator) {
        addModeIndicator.innerHTML = `
            <div style="font-size: 9px; opacity: 0.9; text-transform: uppercase;">ACTIVE MODE</div>
            <div style="font-size: 16px; white-space: nowrap;">${addModeBuffer || 'Type name...'}</div>
        `;
    }
}

function cycleActiveZone() {
    const next = activeDropZone === 'current-team' ? 'must-include' : 'current-team';
    setActiveZone(next);
}

function handleAddModeKeydown(e) {
    // Only handle local keys if we are focused and the event isn't coming from the Hub
    // Actually, if the Hub is active, it consumes the key globally, so the browser won't see it anyway.
    // This listener is only a fallback for when Hub isn't running or for some reason not capturing.
    
    if (e.key === 'Tab') {
        e.preventDefault();
        if (e.ctrlKey) {
            cycleUnitHighlight();
        } else {
            cycleActiveZone();
        }
        return;
    }

    if (e.key === 'm' && e.ctrlKey) {
        e.preventDefault();
        toggleUnitSortMode(unitSortMode !== 'smart');
        const sortToggle = document.getElementById('smart-sort-toggle');
        if (sortToggle) sortToggle.checked = (unitSortMode === 'smart');
        return;
    }

    if (e.key === 'a' && e.altKey) {
        e.preventDefault();
        exitAddMode();
        return; 
    }

    if (e.key.length === 1 && !e.ctrlKey && !e.altKey && !e.metaKey) {
        e.preventDefault();
        addModeBuffer += e.key.toLowerCase();
        
        if (tryProcessBuffer()) {
            addModeBuffer = "";
        }
        updateAddModeIndicator();
    } else if (e.key === 'Backspace') {
        e.preventDefault();
        if (addModeBuffer.length > 0) {
            addModeBuffer = addModeBuffer.slice(0, -1);
        } else {
            // Check if we have a highlighted unit first
            const activeZoneId = activeDropZone === 'current-team' ? 'current-team-zone' : 'must-include-zone';
            const zone = document.getElementById(activeZoneId);
            const items = zone ? zone.querySelectorAll('.draggable-item') : [];
            
            if (highlightedUnitIndex >= 0 && highlightedUnitIndex < items.length) {
                const item = items[highlightedUnitIndex];
                const name = item.dataset.name;
                const type = item.dataset.type;
                removeItem(name, type, activeDropZone);
                highlightedUnitIndex = -1; // Reset highlight
            } else if (addedItemsHistory.length > 0) {
                // Multi-step undo
                const lastItem = addedItemsHistory.pop();
                removeItem(lastItem.name, lastItem.type, lastItem.zone);
            }
        }
        updateAddModeIndicator();
    } else if (e.key === 'Enter') {
        e.preventDefault();
        addModeBuffer = "";
        updateAddModeIndicator();
    }
}

function performAdd(item, type) {
    if (type === 'emblem') {
        addToSelectedEmblems(item);
        const added = { name: item.name, type: 'emblem', zone: 'emblems' };
        addedItemsHistory.push(added);
    } else if (type === 'trait') {
        addToMustIncludeTrait(item.name);
        addedItemsHistory.push({ name: item.name, type: 'trait', zone: 'must-include' });
    } else {
        if (activeDropZone === 'must-include') {
            addToMustInclude(item);
            addedItemsHistory.push({ name: item.name, type: 'unit', zone: 'must-include' });
        } else {
            addToCurrentTeam(item);
            addedItemsHistory.push({ name: item.name, type: 'unit', zone: 'current-team' });
        }
    }

    if (addedItemsHistory.length > 10) addedItemsHistory.shift();
    
    if (typeof CortexAudio !== 'undefined') CortexAudio.playTone('work');
    
    if (addModeIndicator) {
        addModeIndicator.classList.remove('add-mode-match');
        void addModeIndicator.offsetWidth;
        addModeIndicator.classList.add('add-mode-match');
    }
    console.log(`TFT ActiveMode: Added ${type}`, item.name);
}

function tryProcessBuffer() {

    if (!tftData) return false;

    

    const query = addModeBuffer.toLowerCase();

    if (query.length < 2) return false;



    // 0. Handle e/t prefixes

    if (query.startsWith('e') && query.length >= 3) {

        const sub = query.substring(1);

        const emblemMatches = tftData.items.filter(i => i.is_emblem && i.trait.toLowerCase().startsWith(sub));

        if (emblemMatches.length === 1) {

            performAdd(emblemMatches[0], 'emblem');

            return true;

        }

    }

    if (query.startsWith('t') && query.length >= 3) {

        const sub = query.substring(1);

        const traitMatches = Object.keys(tftData.trait_metadata).filter(t => t.toLowerCase().startsWith(sub));

        if (traitMatches.length === 1) {

            performAdd({ name: traitMatches[0] }, 'trait');

            return true;

        }

    }



    // 1. Check Custom Shortcuts (2 chars)


    if (query.length === 2 && CUSTOM_UNIT_SHORTCUTS[query]) {
        const targetName = CUSTOM_UNIT_SHORTCUTS[query];
        // Check if it's an emblem (some shortcuts might be traits)
        const emblem = tftData.items.find(i => i.is_emblem && i.name === targetName);
        if (emblem) {
            performAdd(emblem, 'emblem');
            return true;
        }
        const unit = tftData.units.find(u => u.name === targetName);
        if (unit) {
            performAdd(unit, 'unit');
            return true;
        }
    }

    // 2. Filter units and emblems that start with the query
    const unitMatches = tftData.units.filter(u => u.name.toLowerCase().startsWith(query));
    const emblemMatches = tftData.items.filter(i => i.is_emblem && i.trait.toLowerCase().startsWith(query));
    
    const totalMatches = unitMatches.length + emblemMatches.length;

    if (query.length === 2) {
        if (totalMatches === 1) {
            // Unique match at 2 characters!
            const match = unitMatches.length > 0 ? unitMatches[0] : emblemMatches[0];
            const type = unitMatches.length > 0 ? 'unit' : 'emblem';
            performAdd(match, type);
            return true;
        }
        return false; // Multiple matches or 0, wait for more input
    }

    if (query.length === 3) {
        // Use full logic for 3 characters (Priority: StartsWith -> Contains)
        processAddModeBuffer();
        return true;
    }
    return false;
}

function processAddModeBuffer() {
    if (!tftData) return;
    
    const query = addModeBuffer.toLowerCase();
    let matchedItem = null;
    let matchedType = null;
    
    // 0. Prefixed search
    if (query.startsWith('e') && query.length >= 2) {
        const sub = query.substring(1);
        let emblem = tftData.items.find(i => i.is_emblem && i.trait.toLowerCase().startsWith(sub));
        if (!emblem) emblem = tftData.items.find(i => i.is_emblem && i.trait.toLowerCase().includes(sub));
        if (emblem) {
            performAdd(emblem, 'emblem');
            return;
        }
    }
    if (query.startsWith('t') && query.length >= 2) {
        const sub = query.substring(1);
        let traitName = Object.keys(tftData.trait_metadata).find(t => t.toLowerCase().startsWith(sub));
        if (!traitName) traitName = Object.keys(tftData.trait_metadata).find(t => t.toLowerCase().includes(sub));
        if (traitName) {
            performAdd({ name: traitName }, 'trait');
            return;
        }
    }

    // 1. Starts With (Unit)
    let unit = tftData.units.find(u => u.name.toLowerCase().startsWith(query));
    if (unit) {
        matchedItem = unit;
        matchedType = 'unit';
    } else {
        // 2. Starts With (Emblem)
        const emblem = tftData.items.find(i => i.is_emblem && i.trait.toLowerCase().startsWith(query));
        if (emblem) {
            matchedItem = emblem;
            matchedType = 'emblem';
        } else {
            // 3. Contains (Unit)
            unit = tftData.units.find(u => u.name.toLowerCase().includes(query));
            if (unit) {
                matchedItem = unit;
                matchedType = 'unit';
            } else {
                // 4. Contains (Emblem)
                const emblemContains = tftData.items.find(i => i.is_emblem && i.trait.toLowerCase().includes(query));
                if (emblemContains) {
                    matchedItem = emblemContains;
                    matchedType = 'emblem';
                }
            }
        }
    }

    if (matchedItem) {
        performAdd(matchedItem, matchedType);
    } else {
        if (typeof CortexAudio !== 'undefined') CortexAudio.playTone('chaos');
        
        if (addModeIndicator) {
            addModeIndicator.classList.remove('add-mode-error');
            void addModeIndicator.offsetWidth;
            addModeIndicator.classList.add('add-mode-error');
            setTimeout(() => addModeIndicator.classList.remove('add-mode-error'), 300);
        }
        console.log("TFT ActiveMode: No match for", query);
    }
}

// Migrate old quiz progress keys to persistent ones
[
    'quiz_lp', 'quiz_rank_index', 'quiz_division_index', 'quiz_history', 
    'quiz_xp', 'quiz_level', 'quiz_best_streak', 'quiz_high_score'
].forEach(suffix => {
    const oldKey = 'tft_' + suffix;
    const newKey = 'tft_persistent_' + suffix;
    if (localStorage.getItem(oldKey) !== null && localStorage.getItem(newKey) === null) {
        localStorage.setItem(newKey, localStorage.getItem(oldKey));
    }
});

// Quiz State
const RANKS = ['IRON', 'BRONZE', 'SILVER', 'GOLD', 'PLATINUM', 'EMERALD', 'DIAMOND', 'MASTER', 'GRANDMASTER', 'CHALLENGER'];
const DIVISIONS = ['IV', 'III', 'II', 'I'];
const SCORE_THRESHOLD = 50; // Points within best score to be considered correct

let quizState = {
    score: 0,
    highScore: parseInt(localStorage.getItem('tft_persistent_quiz_high_score') || '0'),
    streak: 0,
    bestStreak: parseInt(localStorage.getItem('tft_persistent_quiz_best_streak') || '0'),
    xp: parseInt(localStorage.getItem('tft_persistent_quiz_xp') || '0'),
    level: parseInt(localStorage.getItem('tft_persistent_quiz_level') || '1'),
    
    // LoL Rank System
    lp: parseInt(localStorage.getItem('tft_persistent_quiz_lp') || '0'),
    rankIndex: parseInt(localStorage.getItem('tft_persistent_quiz_rank_index') || '0'), // Index in RANKS
    divisionIndex: parseInt(localStorage.getItem('tft_persistent_quiz_division_index') || '0'), // Index in DIVISIONS (0=IV, 3=I)
    history: JSON.parse(localStorage.getItem('tft_persistent_quiz_history') || '[]'), // Array of {t: timestamp, lp: cumulative_lp}

    currentBoard: null,
    hiddenUnits: [], // Now an array for multiple hidden units
    validAlternativeUnits: [], // Array of arrays, matching hiddenUnits length. Each inner array contains alternative unit objects.
    guessedUnits: [], // Units correctly guessed in current turn
    activeEmblems: [],
    baseCounts: {},
    finalCounts: {},
    showTraits: true,
    difficulty: 'classic',
    isAnswered: false,
    isGenerating: false,
    timer: null,
    secondsLeft: 0,
    lastLevel: 6, // Track current question level for LP calculation
    debugMode: false // When true, rank changes are not saved
};

function getQuizTime() {
    if (quizState.difficulty === 'zen') return 0;
    
    // Ranks: 0: Iron, 1: Bronze, 2: Silver, 3: Gold, 4: Platinum, 5: Emerald, 6: Diamond, 7: Master, 8: GM, 9: Challenger
    const times = [120, 100, 80, 60, 45, 35, 25, 20, 15, 10];
    let baseTime = times[quizState.rankIndex] || 20;
    
    // Blitz and Hardcore modifiers
    if (quizState.difficulty === 'blitz') baseTime = Math.min(baseTime, 10);
    if (quizState.difficulty === 'hard') baseTime = Math.min(baseTime, 15);
    
    // 50% extra time for 2-unit questions
    if (quizState.hiddenUnits && quizState.hiddenUnits.length > 1) {
        baseTime = Math.floor(baseTime * 1.5);
    }
    
    return baseTime;
}

function cheatRank(val) {
    quizState.rankIndex = parseInt(val);
    quizState.divisionIndex = 0;
    quizState.lp = 0;
    quizState.debugMode = true; // Flag to disable history/saving
    
    const label = document.getElementById('rank-cheat-value');
    if (label) label.innerText = RANKS[quizState.rankIndex];
    
    const slider = document.getElementById('rank-cheat-slider');
    if (slider) slider.value = val;
    
    updateQuizStats();
}

function getCumulativeLP(rankIdx, divIdx, lp) {
    if (rankIdx >= 7) { // Master+
        return (7 * 400) + lp;
    }
    return (rankIdx * 400) + (divIdx * 100) + lp;
}

function saveQuizProgress() {
    localStorage.setItem('tft_persistent_quiz_lp', quizState.lp);
    localStorage.setItem('tft_persistent_quiz_rank_index', quizState.rankIndex);
    localStorage.setItem('tft_persistent_quiz_division_index', quizState.divisionIndex);
    localStorage.setItem('tft_persistent_quiz_history', JSON.stringify(quizState.history));
    localStorage.setItem('tft_persistent_quiz_xp', quizState.xp);
    localStorage.setItem('tft_persistent_quiz_level', quizState.level);
    localStorage.setItem('tft_persistent_quiz_best_streak', quizState.bestStreak);
    localStorage.setItem('tft_persistent_quiz_high_score', quizState.highScore);
}

function updateRank(lpChange) {
    if (quizState.debugMode) {
        quizState.lp += lpChange;
        updateQuizStats();
        return;
    }
    let oldCumulative = getCumulativeLP(quizState.rankIndex, quizState.divisionIndex, quizState.lp);
    quizState.lp += lpChange;

    // Rank Up / Down Logic
    if (quizState.rankIndex < 7) { // Below Master
        while (quizState.lp >= 100) {
            if (quizState.divisionIndex < 3) {
                quizState.divisionIndex++;
                quizState.lp -= 100;
            } else {
                if (quizState.rankIndex < RANKS.length - 1) {
                    quizState.rankIndex++;
                    quizState.divisionIndex = 0;
                    quizState.lp -= 100;
                    triggerConfetti();
                } else {
                    quizState.lp = 100; // Cap? Or just let it grow in Challenger?
                    break;
                }
            }
        }
        while (quizState.lp < 0) {
            if (quizState.divisionIndex > 0) {
                quizState.divisionIndex--;
                quizState.lp += 100;
            } else {
                if (quizState.rankIndex > 0) {
                    quizState.rankIndex--;
                    quizState.divisionIndex = 3;
                    quizState.lp += 100;
                } else {
                    quizState.lp = 0;
                    break;
                }
            }
        }
    } else { // Master+ (No divisions)
        // Check for promotion to GM/Challenger based on LP thresholds
        // Simplified: Master starts at 2800 cumulative (7 * 400)
        // GM at 3300 (+500), Challenger at 3800 (+1000)
        let totalMasterLP = quizState.lp; // In Master+, lp is just uncapped
        if (totalMasterLP >= 1000) {
            quizState.rankIndex = 9; // Challenger
        } else if (totalMasterLP >= 500) {
            quizState.rankIndex = 8; // Grandmaster
        } else {
            quizState.rankIndex = 7; // Master
        }
        
        if (quizState.lp < 0 && quizState.rankIndex === 7) {
            // Drop back to Diamond I
            quizState.rankIndex = 6;
            quizState.divisionIndex = 3;
            quizState.lp = 75; // Safety buffer
        }
    }

    // Add to history
    let newCumulative = getCumulativeLP(quizState.rankIndex, quizState.divisionIndex, quizState.lp);
    quizState.history.push({
        t: Date.now(),
        lp: newCumulative
    });
    
    // Keep history manageable (last 50 games)
    if (quizState.history.length > 50) {
        quizState.history.shift();
    }

    saveQuizProgress();
    updateQuizStats();
    renderRankGraph();
}

function renderRankGraph() {
    const container = document.getElementById('quiz-history-graph');
    if (!container || quizState.history.length < 2) return;

    const width = container.clientWidth;
    const height = container.clientHeight;
    const padding = 20;

    const history = quizState.history;
    const minLP = Math.min(...history.map(h => h.lp));
    const maxLP = Math.max(...history.map(h => h.lp));
    const lpRange = Math.max(100, maxLP - minLP);
    
    const points = history.map((h, i) => {
        const x = padding + (i / (history.length - 1)) * (width - 2 * padding);
        const y = height - padding - ((h.lp - minLP) / lpRange) * (height - 2 * padding);
        return `${x},${y}`;
    }).join(' ');

    container.innerHTML = `
        <svg width="100%" height="100%" viewBox="0 0 ${width} ${height}" style="overflow: visible;">
            <defs>
                <linearGradient id="graphGradient" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stop-color="var(--accent)" stop-opacity="0.3"/>
                    <stop offset="100%" stop-color="var(--accent)" stop-opacity="0"/>
                </linearGradient>
            </defs>
            <path d="M ${points} L ${width - padding},${height - padding} L ${padding},${height - padding} Z" fill="url(#graphGradient)" />
            <polyline points="${points}" fill="none" stroke="var(--accent)" stroke-width="2" stroke-linejoin="round" />
            ${history.map((h, i) => {
                const x = padding + (i / (history.length - 1)) * (width - 2 * padding);
                const y = height - padding - ((h.lp - minLP) / lpRange) * (height - 2 * padding);
                if (i === history.length - 1 || i === 0) {
                    return `<circle cx="${x}" cy="${y}" r="3" fill="var(--accent)" />`;
                }
                return '';
            }).join('')}
        </svg>
    `;
}

function getXPToNextLevel(level) {
    return level * 1000;
}

function gainXP(amount) {
    quizState.xp += amount;
    let nextXP = getXPToNextLevel(quizState.level);
    
    while (quizState.xp >= nextXP) {
        quizState.xp -= nextXP;
        quizState.level++;
        nextXP = getXPToNextLevel(quizState.level);
        
        // Level up visual feedback
        const levelText = document.getElementById('quiz-level-text');
        if (levelText) {
            levelText.style.color = 'var(--success)';
            levelText.style.transform = 'scale(1.5)';
            setTimeout(() => {
                levelText.style.color = '';
                levelText.style.transform = '';
            }, 1000);
        }
    }
    
    localStorage.setItem('tft_persistent_quiz_xp', quizState.xp);
    localStorage.setItem('tft_persistent_quiz_level', quizState.level);
    updateQuizStats();
}

function triggerConfetti() {
    for (let i = 0; i < 50; i++) {
        const confetti = document.createElement('div');
        confetti.style.position = 'fixed';
        confetti.style.width = '8px';
        confetti.style.height = '8px';
        confetti.style.backgroundColor = ['#0a84ff', '#32d74b', '#ff9500', '#ff453a', '#bf5af2'][Math.floor(Math.random() * 5)];
        confetti.style.top = '-10px';
        confetti.style.left = Math.random() * 100 + 'vw';
        confetti.style.zIndex = '10000';
        confetti.style.borderRadius = '2px';
        confetti.style.pointerEvents = 'none';
        
        document.body.appendChild(confetti);
        
        const animation = confetti.animate([
            { transform: `translate3d(0, 0, 0) rotate(0deg)`, opacity: 1 },
            { transform: `translate3d(${(Math.random() - 0.5) * 200}px, 105vh, 0) rotate(${Math.random() * 360}deg)`, opacity: 0 }
        ], {
            duration: 2000 + Math.random() * 3000,
            easing: 'cubic-bezier(0, .9, .57, 1)'
        });
        
        animation.onfinish = () => confetti.remove();
    }
}

function toggleQuizTraits(enabled) {
    quizState.showTraits = enabled;
    if (!quizState.isAnswered) {
        renderQuizTraits();
    }
}

function changeQuizDifficulty(val) {
    quizState.difficulty = val;
    startNewQuiz();
}

function calculateBoardCounts(units, emblems) {
    const counts = {};
    units.forEach(u => {
        if (!u || !u.traits) return;
        u.traits.forEach(t => {
            counts[t] = (counts[t] || 0) + 1;
        });
    });
    if (emblems) {
        emblems.forEach(t => {
            counts[t] = (counts[t] || 0) + 1;
        });
    }
    return counts;
}

function setActiveZone(zoneId) {
    if (zoneId === 'emblems') return; // Cannot activate emblems zone

    activeDropZone = zoneId;
    document.querySelectorAll('.drop-zone').forEach(el => {
        el.classList.remove('active-zone');
        el.classList.remove('add-mode-active');
    });
    
    const zoneMap = {
        'current-team': 'current-team-zone',
        'must-include': 'must-include-zone'
    };
    
    const elId = zoneMap[zoneId];
    if (elId) {
        const el = document.getElementById(elId);
        if (el) {
            el.classList.add('active-zone');
            if (isAddMode) {
                el.classList.add('add-mode-active');
            }
        }
    }
    
    // Reset unit highlight when switching zones
    highlightedUnitIndex = -1;
}

async function initHubConnection() {
    hubConnection = new signalR.HubConnectionBuilder()
        .withUrl(HUB_URL)
        .withAutomaticReconnect()
        .build();

    try {
        hubConnection.on("ClipboardUpdated", (text) => {
            lastReceivedClipboard = text;
        });

        hubConnection.on("ReceiveTftCommand", (command, payload) => {
            console.log("TFT Hotkey Received:", command);
            handleTftHotkey(command, payload);
        });

        await hubConnection.start();
        console.log("TFT Hub Connected");
        
        await hubConnection.invoke("Authenticate", API_KEY);
    } catch (err) {
        console.error("TFT Hub Connection Error:", err);
        setTimeout(initHubConnection, 5000);
    }
}

function toggleActiveDisableUnitByCost(cost) {
    if (!tftData) return;
    const unitsOfCost = tftData.units.filter(u => u.cost === cost);
    const allDisabled = unitsOfCost.every(u => activeDisabledUnits.includes(u.name));
    
    if (allDisabled) {
        // Enable all of this cost
        activeDisabledUnits = activeDisabledUnits.filter(name => !unitsOfCost.find(u => u.name === name));
    } else {
        // Disable all of this cost
        unitsOfCost.forEach(u => {
            if (!activeDisabledUnits.includes(u.name)) activeDisabledUnits.push(u.name);
        });
    }
    renderUnitPools();
}

function cycleSelectedLevels() {
    const checkBoxes = Array.from(document.querySelectorAll('.lvl-cb'));
    if (checkBoxes.length === 0) return;
    
    // Find highest currently checked level
    const checked = checkBoxes.filter(cb => cb.checked).map(cb => parseInt(cb.value));
    const maxChecked = checked.length > 0 ? Math.max(...checked) : 3;
    
    // Uncheck everything
    checkBoxes.forEach(cb => cb.checked = false);
    
    // Cycle: 6&8 -> 8 -> 9 -> 10 -> 4 -> 5 -> 6 -> 7 -> 6&8
    let nextLevels = [6, 8];
    if (checked.length === 2 && checked.includes(6) && checked.includes(8)) nextLevels = [8];
    else if (maxChecked === 8) nextLevels = [9];
    else if (maxChecked === 9) nextLevels = [10];
    else if (maxChecked === 10) nextLevels = [4];
    else if (maxChecked === 4) nextLevels = [5];
    else if (maxChecked === 5) nextLevels = [6];
    else if (maxChecked === 6) nextLevels = [7];
    else if (maxChecked === 7) nextLevels = [6, 8];
    
    checkBoxes.forEach(cb => {
        if (nextLevels.includes(parseInt(cb.value))) cb.checked = true;
    });
}

function cycleHeuristics() {
    const radios = Array.from(document.querySelectorAll('input[name="heuristic-mode"]'));
    if (radios.length === 0) return;
    
    const currentIndex = radios.findIndex(r => r.checked);
    const nextIndex = (currentIndex + 1) % radios.length;
    radios[nextIndex].checked = true;
    
    // Trigger change event if needed
    radios[nextIndex].dispatchEvent(new Event('change'));
}

function toggleSmartSortHotkey() {
    const toggle = document.getElementById('smart-sort-toggle');
    if (toggle) {
        toggle.checked = !toggle.checked;
        toggleUnitSortMode(toggle.checked);
    }
}

function toggleExclude5CostsHotkey() {
    const cb = document.getElementById('exclude-five-costs');
    if (cb) {
        cb.checked = !cb.checked;
    }
}

function toggleImproveModeHotkey() {
    const cb = document.getElementById('improve-mode');
    if (cb) {
        cb.checked = !cb.checked;
    }
}

async function handleTftHotkey(command, payload) {
    switch (command) {
        case "TFT_ACTIVATE_CURRENT_TEAM":
            setActiveZone('current-team');
            break;
        case "TFT_ACTIVATE_MUST_INCLUDE":
            setActiveZone('must-include');
            break;
        case "TFT_ENTER_ADD_MODE":
            if (payload && typeof payload.Active === 'boolean') {
                if (payload.Active) {
                    if (!isAddMode) enterAddMode();
                } else {
                    if (isAddMode) exitAddMode();
                }
            } else {
                // Fallback to toggle
                if (isAddMode) exitAddMode();
                else enterAddMode();
            }
            break;
        case "TFT_INPUT":
            if (payload && payload.Key) {
                handleTftGlobalInput(payload.Key);
            }
            break;
        case "TFT_CLIPBOARD_MUST_INCLUDE_SOLVE_NEXT":
            await pasteToZone('must-include');
            runOptimization();
            break;
        case "TFT_CLIPBOARD_CURRENT_TEAM":
            await pasteToZone('current-team');
            break;
        case "TFT_CYCLE_UNIT_HIGHLIGHT":
            cycleUnitHighlight();
            break;
        case "TFT_CYCLE_RESULT_NEXT":
            // Dual purpose Tab: if results exist, cycle them. Otherwise cycle boxes.
            if (document.querySelectorAll('.result-card').length > 0) {
                cycleResults(1);
            } else {
                cycleActiveZone();
            }
            break;
        case "TFT_CYCLE_RESULT_PREV":
            cycleResults(-1);
            break;
        case "TFT_DEBUG_SOLVE_1":
        case "TFT_DEBUG_SOLVE_2":
        case "TFT_DEBUG_SOLVE_3":
        case "TFT_RUN_OPTIMIZATION":
            runOptimization();
            break;
        case "TFT_DEBUG_COPY_1":
        case "TFT_COPY_ACTIVE":
            if (selectedResultIndex >= 0) {
                copyResultCode(selectedResultIndex);
            } else if (activeDropZone === 'current-team') {
                copyZoneCode('current-team');
            } else if (activeDropZone === 'must-include') {
                copyZoneCode('must-include');
            }
            break;
        case "TFT_DEBUG_PASTE_1":
        case "TFT_PASTE_ACTIVE":
            await pasteToZone(activeDropZone);
            break;
        case "TFT_CLEAR_ALL":
            resetAll();
            break;
        case "TFT_SAVE_COMP":
            handleSaveComp();
            break;
        case "TFT_TOGGLE_SMART_SORT":
            toggleUnitSortMode(unitSortMode !== 'smart');
            const sortToggle = document.getElementById('smart-sort-toggle');
            if (sortToggle) sortToggle.checked = (unitSortMode === 'smart');
            break;
        case "TFT_SWITCH_TAB_SOLVER":
            switchTab('solver');
            break;
        case "TFT_SWITCH_TAB_QUIZ":
            switchTab('quiz');
            break;
        case "TFT_SWITCH_TAB_DIRECTOR":
            switchTab('director');
            break;
        case "TFT_SWITCH_TAB_CONFIG":
            switchTab('config');
            break;
        case "TFT_TOGGLE_COST_1":
            toggleActiveDisableUnitByCost(1);
            break;
        case "TFT_TOGGLE_COST_2":
            toggleActiveDisableUnitByCost(2);
            break;
        case "TFT_TOGGLE_COST_3":
            toggleActiveDisableUnitByCost(3);
            break;
        case "TFT_TOGGLE_COST_4":
            toggleActiveDisableUnitByCost(4);
            break;
        case "TFT_TOGGLE_COST_5":
            toggleActiveDisableUnitByCost(5);
            break;
        case "TFT_TOGGLE_LEVEL_4": toggleLevelCheckbox(4); break;
        case "TFT_TOGGLE_LEVEL_5": toggleLevelCheckbox(5); break;
        case "TFT_TOGGLE_LEVEL_6": toggleLevelCheckbox(6); break;
        case "TFT_TOGGLE_LEVEL_7": toggleLevelCheckbox(7); break;
        case "TFT_TOGGLE_LEVEL_8": toggleLevelCheckbox(8); break;
        case "TFT_TOGGLE_LEVEL_9": toggleLevelCheckbox(9); break;
        case "TFT_TOGGLE_LEVEL_10": toggleLevelCheckbox(10); break;
        case "TFT_CYCLE_HEURISTICS":
            cycleHeuristics();
            break;
        case "TFT_POC":
            alert("TFT Hotkey POC Successful!");
            break;
    }
}

function switchTab(tabId) {
    document.querySelectorAll('.tft-tab').forEach(tab => {
        tab.classList.remove('active');
        const text = tab.innerText.toLowerCase().replace(/ /g, '-');
        if (text === tabId || 
            (tabId === 'solver' && tab.innerText === 'Solver') ||
            (tabId === 'quiz' && tab.innerText === 'Quiz') ||
            (tabId === 'director' && tab.innerText === 'Director') ||
            (tabId === 'config' && tab.innerText === 'Configuration')) {
            tab.classList.add('active');
        }
    });

    const tabMap = {
        'Solver': 'solver',
        'Quiz': 'quiz',
        'Director': 'director',
        'Configuration': 'config'
    };

    document.querySelectorAll('.tab-panel').forEach(panel => {
        panel.classList.remove('active');
    });
    
    const targetId = tabMap[tabId] || tabId;
    const el = document.getElementById(targetId);
    if (el) el.classList.add('active');

    if (targetId === 'quiz' && !quizState.currentBoard) {
        startNewQuiz();
    }
}

async function loadTFTData() {
    try {
        const configResp = await fetch('assets/tft/data/set_config.json');
        currentConfig = await configResp.json();
        
        const dataResp = await fetch(`assets/tft/data/${currentConfig.current_set}.json`);
        tftData = await dataResp.json();

        try {
            const rulesResp = await fetch('assets/tft/data/comp_rules.json');
            compRules = await rulesResp.json();
        } catch (e) {
            console.warn("Failed to load comp rules, using defaults", e);
        }

        // Load unit ID mapping for Team Planner
        const mappingResp = await fetch('assets/tft/data/unit_id_map.json');
        const unitIdMap = await mappingResp.json();
        TeamPlannerCode.setMapping(unitIdMap);

        // Load factory default disabled units
        const disabledResp = await fetch('assets/tft/data/default_disabled.json');
        factoryDefaultDisabled = await disabledResp.json();
        
        const stored = localStorage.getItem('tft_user_defaults_disabled');
        if (stored) {
            userDefaultDisabledUnits = JSON.parse(stored);
        } else {
            userDefaultDisabledUnits = [...factoryDefaultDisabled];
        }
        
        activeDisabledUnits = [...userDefaultDisabledUnits];
        
        const configTextarea = document.getElementById('disabled-units-config');
        if (configTextarea) configTextarea.value = userDefaultDisabledUnits.join(', ');

        optimizer = new TFTOptimizer(tftData.units, tftData.trait_metadata);
        setupSolverListeners();
        updateUI();
        setActiveZone('must-include');
        renderSavedComps();
        initHubConnection();
        loadTFTSelection();
    } catch (err) {
        console.error("Failed to load TFT data:", err);
    }
}

function setupSolverListeners() {
    const solverRadios = document.querySelectorAll('input[name="solver-mode"]');
    solverRadios.forEach(radio => {
        radio.addEventListener('change', (e) => {
            if (e.target.checked) {
                if (e.target.value === 'world-runes') {
                    // Auto-select levels 5 and 6
                    document.querySelectorAll('.lvl-cb').forEach(cb => {
                        cb.checked = (cb.value === "5" || cb.value === "6");
                    });

                    // Highlight the emblem drop zone
                    const emblemZone = document.getElementById('emblem-drop-zone')?.closest('.control-box');
                    if (emblemZone) {
                        emblemZone.classList.remove('pulse-highlight');
                        void emblemZone.offsetWidth; // Force reflow
                        emblemZone.classList.add('pulse-highlight');
                    }
                } else if (e.target.value === 'ryze-unlock') {
                    // Auto-select level 9
                    document.querySelectorAll('.lvl-cb').forEach(cb => {
                        cb.checked = (cb.value === "9");
                    });

                    // Add Ryze to must-include
                    forceAddMustIncludeUnit("Ryze");
                }
            }
        });
    });
}

function forceAddMustIncludeUnit(unitName) {
    if (!tftData) return;
    const unit = tftData.units.find(u => u.name === unitName);
    if (unit && !selectedMustInclude.find(i => i.name === unitName)) {
        selectedMustInclude.push({
            name: unit.name,
            iconUrl: unit.icon_url,
            type: 'unit',
            cost: unit.cost
        });
        renderSelectionZones();
    }
}

function updateUI() {
    if (!tftData) return;

    const setNameEl = document.getElementById('config-set-name');
    if (setNameEl) setNameEl.innerText = tftData.set_name;
    const jsonDisplayEl = document.getElementById('config-json-display');
    if (jsonDisplayEl) jsonDisplayEl.innerText = JSON.stringify(tftData, null, 2);

    renderUnitPools();
    renderAlphaFilter();
    renderEmblemPool();
    renderSelectionZones();
    renderHotkeysButton();
}

function renderHotkeysButton() {
    if (document.getElementById('hotkeys-btn')) return;
    
    const btn = document.createElement('div');
    btn.id = 'hotkeys-btn';
    btn.innerHTML = '?';
    btn.style.position = 'fixed';
    btn.style.bottom = '20px';
    btn.style.left = '20px';
    btn.style.width = '30px';
    btn.style.height = '30px';
    btn.style.background = 'var(--bg-elevated)';
    btn.style.color = 'var(--text-dim)';
    btn.style.border = '1px solid var(--border)';
    btn.style.borderRadius = '50%';
    btn.style.display = 'flex';
    btn.style.alignItems = 'center';
    btn.style.justifyContent = 'center';
    btn.style.cursor = 'pointer';
    btn.style.zIndex = '10000';
    btn.style.fontSize = '14px';
    btn.style.fontWeight = 'bold';
    btn.style.boxShadow = '0 2px 8px rgba(0,0,0,0.2)';
    
    btn.onmouseenter = () => {
        const tooltip = document.getElementById('hotkeys-tooltip');
        if (tooltip) tooltip.style.display = 'block';
    };
    
    btn.onmouseleave = () => {
        const tooltip = document.getElementById('hotkeys-tooltip');
        if (tooltip) tooltip.style.display = 'none';
    };
    
    document.body.appendChild(btn);
    
    const tooltip = document.createElement('div');
    tooltip.id = 'hotkeys-tooltip';
    tooltip.style.display = 'none';
    tooltip.style.position = 'fixed';
    tooltip.style.bottom = '60px';
    tooltip.style.left = '20px';
    tooltip.style.background = 'var(--bg-elevated)';
    tooltip.style.border = '1px solid var(--border)';
    tooltip.style.borderRadius = '8px';
    tooltip.style.padding = '12px';
    tooltip.style.zIndex = '10000';
    tooltip.style.boxShadow = '0 4px 12px rgba(0,0,0,0.4)';
    tooltip.style.fontSize = '11px';
    tooltip.style.width = '250px';
    
    tooltip.innerHTML = `
        <h3 style="margin: 0 0 8px 0; color: var(--accent);">Global Hotkeys</h3>
        <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 4px;">
            <span style="color: var(--text-dim);">Active Mode</span> <span style="text-align: right; font-family: monospace;">Alt+A</span>
            <span style="color: var(--text-dim);">Current Team</span> <span style="text-align: right; font-family: monospace;">Ctrl+1</span>
            <span style="color: var(--text-dim);">Must Include</span> <span style="text-align: right; font-family: monospace;">Ctrl+2</span>
            <span style="color: var(--text-dim);">Run Solver</span> <span style="text-align: right; font-family: monospace;">Alt+S</span>
            <span style="color: var(--text-dim);">Smart Sort</span> <span style="text-align: right; font-family: monospace;">Ctrl+M</span>
            <span style="color: var(--text-dim);">Clear All</span> <span style="text-align: right; font-family: monospace;">Alt+X</span>
            <span style="color: var(--text-dim);">Clear Active</span> <span style="text-align: right; font-family: monospace;">Shift+X</span>
            <span style="color: var(--text-dim);">Copy Active</span> <span style="text-align: right; font-family: monospace;">Alt+C</span>
            <span style="color: var(--text-dim);">Paste Active</span> <span style="text-align: right; font-family: monospace;">Alt+V</span>
            <span style="color: var(--text-dim);">Toggle Levels</span> <span style="text-align: right; font-family: monospace;">Shift+4-0</span>
            <span style="color: var(--text-dim);">Cycle Heuristics</span> <span style="text-align: right; font-family: monospace;">Alt+H</span>
            <span style="color: var(--text-dim);">Cycle Units</span> <span style="text-align: right; font-family: monospace;">Ctrl+Tab</span>
            <span style="color: var(--text-dim);">Cycle Results</span> <span style="text-align: right; font-family: monospace;">Tab</span>
            <span style="color: var(--text-dim);">Cost Filter</span> <span style="text-align: right; font-family: monospace;">Alt+1-5</span>
        </div>
    `;
    
    document.body.appendChild(tooltip);
}

function renderAlphaFilter() {
    const container = document.getElementById('alpha-filter');
    if (!container) return;
    container.innerHTML = '';

    const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".split("");
    const availableLetters = new Set();
    if (tftData && tftData.units) {
        tftData.units.forEach(u => {
            if (u.name && u.name.length > 0) {
                availableLetters.add(u.name[0].toUpperCase());
            }
        });
    }

    alphabet.forEach(letter => {
        if (!availableLetters.has(letter)) return;

        const btn = document.createElement('button');
        btn.className = 'alpha-btn';
        if (unitAlphaFilter === letter) btn.classList.add('active');
        btn.innerText = letter;
        btn.onclick = () => setAlphaFilter(letter);
        container.appendChild(btn);
    });
}

function setAlphaFilter(letter) {
    if (alphaFilterTimeout) {
        clearTimeout(alphaFilterTimeout);
        alphaFilterTimeout = null;
    }

    if (unitAlphaFilter === letter) {
        unitAlphaFilter = null;
    } else {
        unitAlphaFilter = letter;
        alphaFilterTimeout = setTimeout(() => {
            unitAlphaFilter = null;
            renderAlphaFilter();
            renderUnitPools();
        }, 5000);
    }

    renderAlphaFilter();
    renderUnitPools();
}

function toggleUnitSortMode(isSmart) {
    unitSortMode = isSmart ? 'smart' : 'alpha';
    renderUnitPools();
}

function renderUnitPools() {
    // Collect active traits from the larger of currentTeam or mustInclude
    let referenceUnits = [];
    if (selectedCurrentTeam.length >= selectedMustInclude.filter(i => i.type === 'unit').length) {
        referenceUnits = selectedCurrentTeam.map(u => tftData.units.find(du => du.name === u.name)).filter(u => u);
    } else {
        referenceUnits = selectedMustInclude.filter(i => i.type === 'unit').map(u => tftData.units.find(du => du.name === u.name)).filter(u => u);
    }

    const referenceTraits = new Set();
    referenceUnits.forEach(u => u.traits.forEach(t => referenceTraits.add(t)));

    const roles = ["Tank", "AP Carry", "AD Carry", "Fighter"];

    for (let cost = 1; cost <= 5; cost++) {
        roles.forEach(role => {
            const poolId = `unit-pool-${cost}-${role}`;
            const pool = document.getElementById(poolId);
            if (!pool) return;
            pool.innerHTML = '';
            
            const units = tftData.units.filter(u => {
                if (u.cost !== cost) return false;
                if (u.role !== role) return false;
                if (unitAlphaFilter && !u.name.toUpperCase().startsWith(unitAlphaFilter)) return false;
                
                // In smart sort mode, hide units already in the active input field (must include or current team)
                if (unitSortMode === 'smart') {
                    const inMustInclude = selectedMustInclude.some(item => item.type === 'unit' && item.name === u.name);
                    const inCurrentTeam = selectedCurrentTeam.some(u_on_board => u_on_board.name === u.name);
                    if (inMustInclude || inCurrentTeam) return false;
                }

                return true;
            })
                .sort((a, b) => {
                    const aDisabled = activeDisabledUnits.includes(a.name);
                    const bDisabled = activeDisabledUnits.includes(b.name);
                    if (aDisabled !== bDisabled) return aDisabled - bDisabled;

                    if (unitSortMode === 'smart' && referenceTraits.size > 0) {
                        const aMatches = a.traits.filter(t => referenceTraits.has(t)).length;
                        const bMatches = b.traits.filter(t => referenceTraits.has(t)).length;
                        if (aMatches !== bMatches) return bMatches - aMatches;
                    }

                    return a.name.localeCompare(b.name);
                });

            units.forEach(u => {
                const item = createDraggableItem(u.name, u.icon_url, 'unit', u.cost, null, false, 0, false, null, u.traits);
                // Ensure it has the right class for the matrix cell
                item.classList.add('unit-node');

                if (activeDisabledUnits.includes(u.name)) {
                    item.style.opacity = '0.55';
                    item.style.filter = 'grayscale(0.7)';
                }
                
                item.addEventListener('click', (e) => {
                    if (activeDropZone === 'must-include') {
                        addToMustInclude(u);
                    } else {
                        addToCurrentTeam(u);
                    }
                });

                item.addEventListener('contextmenu', (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    toggleActiveDisableUnit(u.name);
                });

                pool.appendChild(item);
            });
        });
    }
    applyTraitHighlight();
}

function toggleActiveDisableUnit(name) {
    if (activeDisabledUnits.includes(name)) {
        activeDisabledUnits = activeDisabledUnits.filter(n => n !== name);
    } else {
        activeDisabledUnits.push(name);
    }
    renderUnitPools();
}

function addToMustInclude(unit) {
    if (!selectedMustInclude.find(item => item.name === unit.name)) {
        selectedMustInclude.push({
            name: unit.name,
            iconUrl: unit.icon_url,
            type: 'unit',
            cost: unit.cost
        });
        renderSelectionZones();
    }
}

function addToMustIncludeTrait(traitName) {
    const meta = tftData.trait_metadata[traitName];
    if (!meta) return;

    if (!selectedMustInclude.find(item => item.name === traitName && item.type === 'trait')) {
        selectedMustInclude.push({
            name: traitName,
            iconUrl: `assets/tft/${currentConfig.current_set}/traits/${traitName.replace(/ /g, '')}.svg`,
            type: 'trait',
            trait: traitName,
            targetBreakpointIndex: 0
        });
        renderSelectionZones();
    }
}

function addToMustIncludeEmblem(emblem) {
    if (!selectedMustInclude.find(item => item.name === emblem.name)) {
        selectedMustInclude.push({
            name: emblem.name,
            iconUrl: emblem.icon_url,
            type: 'emblem',
            trait: emblem.trait,
            targetBreakpointIndex: -1
        });
        renderSelectionZones();
    }
}

function addToSelectedEmblems(emblem) {
    if (!selectedEmblems.find(em => em.name === emblem.name)) {
        selectedEmblems.push({
            name: emblem.name,
            iconUrl: emblem.icon_url,
            type: 'emblem',
            trait: emblem.trait
        });
        renderSelectionZones();
    }
}

function addToCurrentTeam(unit) {
    if (!selectedCurrentTeam.find(u => u.name === unit.name)) {
        selectedCurrentTeam.push({
            name: unit.name,
            iconUrl: unit.icon_url,
            type: 'unit',
            cost: unit.cost
        });
        renderSelectionZones();
    }
}

function saveDisabledConfig() {
    const val = document.getElementById('disabled-units-config').value;
    userDefaultDisabledUnits = val.split(',').map(s => s.trim()).filter(s => s);
    localStorage.setItem('tft_user_defaults_disabled', JSON.stringify(userDefaultDisabledUnits));
    activeDisabledUnits = [...userDefaultDisabledUnits];
    renderUnitPools();
    alert("Master Exclusion List Saved");
}

function resetDisabledToDefault() {
    if (confirm("Reset Master Exclusions to Factory Defaults?")) {
        userDefaultDisabledUnits = [...factoryDefaultDisabled];
        localStorage.setItem('tft_user_defaults_disabled', JSON.stringify(userDefaultDisabledUnits));
        document.getElementById('disabled-units-config').value = userDefaultDisabledUnits.join(', ');
        activeDisabledUnits = [...userDefaultDisabledUnits];
        renderUnitPools();
    }
}

function renderEmblemPool() {
    const originPool = document.getElementById('emblem-pool-origins');
    const classPool = document.getElementById('emblem-pool-classes');
    if (!originPool || !classPool) return;
    
    originPool.innerHTML = '<div class="pool-subheader">Origins</div>';
    classPool.innerHTML = '<div class="pool-subheader">Classes</div>';
    
    const emblems = tftData.items.filter(i => {
        if (!i.is_emblem) return false;
        if (!emblemSearch) return true;
        return i.name.toLowerCase().includes(emblemSearch.toLowerCase());
    }).sort((a, b) => a.name.localeCompare(b.name));

    emblems.forEach(e => {
        const displayName = e.name.replace(" Emblem", "");
        const item = createDraggableItem(displayName, e.icon_url, 'emblem', null, e.trait);
        
        // Add active state if already selected
        if (selectedEmblems.find(se => se.name === e.name) || selectedMustInclude.find(mi => mi.name === e.name)) {
            item.classList.add('selected-in-pool');
        }

        item.addEventListener('click', (ev) => {
             if (activeDropZone === 'must-include') {
                 addToMustIncludeEmblem(e);
             } else {
                 addToSelectedEmblems(e);
             }
             renderEmblemPool(); // Refresh to update highlights
        });

        item.addEventListener('contextmenu', (ev) => {
            ev.preventDefault();
            ev.stopPropagation();
            toggleTraitHighlight(e.trait);
        });

        const meta = (tftData.trait_metadata && tftData.trait_metadata[e.trait]) ? tftData.trait_metadata[e.trait] : null;
        if (meta && meta.type === "origin") {
            originPool.appendChild(item);
        } else {
            classPool.appendChild(item);
        }
    });
}

function toggleTraitHighlight(trait) {
    if (highlightedTrait === trait) {
        highlightedTrait = null;
    } else {
        highlightedTrait = trait;
    }
    applyTraitHighlight();
}

function applyTraitHighlight() {
    document.querySelectorAll('.draggable-item.highlight-source').forEach(el => {
        el.classList.remove('highlight-source');
    });

    document.querySelectorAll('.unit-node').forEach(item => {
        const traits = JSON.parse(item.dataset.traits || '[]');
        item.classList.remove('highlighted', 'dimmed');
        
        if (highlightedTrait) {
            if (traits.includes(highlightedTrait)) {
                item.classList.add('highlighted');
            } else {
                item.classList.add('dimmed');
            }
        }
    });

    if (highlightedTrait) {
        document.querySelectorAll(`.draggable-item[data-trait="${highlightedTrait}"]`).forEach(el => {
            el.classList.add('highlight-source');
        });
    }
}

function clearTraitHighlight() {
    highlightedTrait = null;
    applyTraitHighlight();
}

document.addEventListener('click', (e) => {
    if (highlightedTrait && !e.target.closest('.draggable-item')) {
        clearTraitHighlight();
    }
});

function createDraggableItem(name, iconUrl, type, cost, trait, isSelected = false, breakpointIdx = 0, isMustInclude = false, zoneId = null, traits = []) {
    const div = document.createElement('div');
    div.className = 'draggable-item';
    div.draggable = true; 
    div.dataset.name = name;
    div.dataset.type = type;
    if (cost) div.dataset.cost = cost;
    if (trait) div.dataset.trait = trait;
    div.dataset.icon = iconUrl;
    div.dataset.selected = isSelected;
    if (traits && traits.length > 0) div.dataset.traits = JSON.stringify(traits);

    const tierColors = ['#cd7f32', '#c0c0c0', '#ffd700', '#e5e4e2']; 
    const costColors = { 1: '#808080', 2: '#11b288', 3: '#207ac7', 4: '#c440da', 5: '#ffb93b' };
    
    let borderColor = cost ? (costColors[cost] || '#ccc') : '#0a84ff';
    let badgeText = '';
    
    if (type === 'emblem' && isMustInclude) {
        if (breakpointIdx === -1) {
            borderColor = '#0a84ff'; 
            badgeText = '*';
        } else {
            borderColor = tierColors[Math.min(breakpointIdx, tierColors.length - 1)];
            badgeText = tftData.trait_metadata[trait].breakpoints[breakpointIdx];
        }
    }

    div.innerHTML = `
        <div style="position: relative;">
            <img src="${iconUrl}" class="item-icon" style="border-color: ${borderColor}" 
                 onerror="this.src='data:image/svg+xml;base64,PHN2ZyB4bWxucz0naHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmcnIHdpZHRoPSc0OCcgaGVpZ2h0PSc0OCcgc3R5bGU9J2JhY2tncm91bmQ6IzIyMjsnPjx0ZXh0IHg9JzUwJScgeT0nNTAlJyBkb20tYmFzZWxpbmU9J21pZGRsZScgdGV4dC1hbmNob3I9J21pZGRsZScgZmlsbD0nI2ZmZicgZm9udC1zaXplPScxMic+PyA8L3RleHQ+PC9zdmc+'">
            ${(type === 'emblem' && isMustInclude) ? `<div style="position: absolute; bottom: 2px; right: 2px; background: rgba(0,0,0,0.7); color: white; padding: 1px 4px; border-radius: 3px; font-size: 10px; font-weight: bold; border: 1px solid ${borderColor}">${badgeText}</div>` : ''}
            ${type === 'unit' ? `<div class="unit-shortcut-badge" id="shortcut-${name.replace(/\s+/g, '')}"></div>` : ''}
        </div>
        <div class="item-name">${name}</div>
    `;

    // Tooltip logic
    if (type === 'unit') {
        let shortcut = Object.keys(CUSTOM_UNIT_SHORTCUTS).find(key => CUSTOM_UNIT_SHORTCUTS[key] === name);
        if (!shortcut) {
            // Check if 2-char prefix is unique
            const prefix = name.substring(0, 2).toLowerCase();
            const unitMatches = tftData.units.filter(u => u.name.toLowerCase().startsWith(prefix));
            const emblemMatches = tftData.items.filter(i => i.is_emblem && i.trait.toLowerCase().startsWith(prefix));
            if (unitMatches.length + emblemMatches.length === 1) {
                shortcut = prefix;
            } else {
                shortcut = name.substring(0, 3).toLowerCase(); // Default to 3 chars
            }
        }
        
        const badge = div.querySelector('.unit-shortcut-badge');
        if (badge) badge.innerText = shortcut;

        div.title = `[${shortcut.toUpperCase()}] ${traits.join(', ')}`;
    } else if (type === 'emblem') {
        div.title = trait;
    }

    div.addEventListener('dragstart', (e) => {
        e.dataTransfer.setData('text/plain', JSON.stringify({
            name, type, cost, trait, iconUrl, isSelected, targetBreakpointIndex: breakpointIdx, sourceZone: zoneId
        }));
    });

    if (isSelected) {
        div.title = type === 'emblem' ? "Left-click to cycle, right-click to remove" : "Click or drag out to remove";
        div.style.cursor = 'pointer';
        
        div.addEventListener('click', (ev) => {
            if (type === 'emblem' && zoneId === 'must-include') {
                const item = selectedMustInclude.find(i => i.name === name);
                if (item) {
                    const breakpoints = tftData.trait_metadata[trait].breakpoints;
                    if (item.targetBreakpointIndex === breakpoints.length - 1) {
                        item.targetBreakpointIndex = -1;
                    } else {
                        item.targetBreakpointIndex = (item.targetBreakpointIndex || 0) + 1;
                    }
                    renderSelectionZones();
                }
            } else {
                removeItem(name, type, zoneId);
            }
        });

        if (type === 'emblem') {
            div.addEventListener('contextmenu', (ev) => {
                ev.preventDefault();
                ev.stopPropagation();
                removeItem(name, type, zoneId);
            });
        }
    }

    return div;
}

function removeItem(name, type, zoneId) {
    if (zoneId === 'must-include') {
        selectedMustInclude = selectedMustInclude.filter(item => item.name !== name);
    } else if (zoneId === 'current-team') {
        selectedCurrentTeam = selectedCurrentTeam.filter(u => u.name !== name);
    } else if (zoneId === 'emblems') {
        selectedEmblems = selectedEmblems.filter(e => e.name !== name);
    } else {
        selectedMustInclude = selectedMustInclude.filter(item => item.name !== name);
        selectedCurrentTeam = selectedCurrentTeam.filter(u => u.name !== name);
        selectedEmblems = selectedEmblems.filter(e => e.name !== name);
    }
    renderSelectionZones();
}

function allowDrop(e) {
    e.preventDefault();
    if (e.currentTarget.classList.contains('drop-zone') || e.currentTarget.classList.contains('pools-zone')) {
        e.currentTarget.classList.add('drag-over');
    }
}

document.addEventListener('dragleave', (e) => {
    if (e.target.classList.contains('drop-zone') || e.target.classList.contains('pools-zone')) {
        e.target.classList.remove('drag-over');
    }
});

document.addEventListener('drop', (e) => {
    if (e.target.classList.contains('drop-zone') || e.target.classList.contains('pools-zone')) {
        e.target.classList.remove('drag-over');
    }
});

function dropOnPools(e) {
    e.preventDefault();
    try {
        const data = JSON.parse(e.dataTransfer.getData('text/plain'));
        if (data.isSelected) {
            removeItem(data.name, data.type, data.sourceZone);
        }
    } catch(err) {}
}

function dropToCurrentTeam(e) {
    e.preventDefault();
    try {
        const data = JSON.parse(e.dataTransfer.getData('text/plain'));
        if (data.type === 'unit') {
            if (!selectedCurrentTeam.find(u => u.name === data.name)) {
                selectedCurrentTeam.push(data);
                renderSelectionZones();
            }
        }
    } catch(err) {}
}

function dropToMustInclude(e) {
    e.preventDefault();
    try {
        const data = JSON.parse(e.dataTransfer.getData('text/plain'));
        if (!selectedMustInclude.find(item => item.name === data.name)) {
            if (data.type === 'emblem') data.targetBreakpointIndex = -1;
            selectedMustInclude.push(data);
            renderSelectionZones();
        }
    } catch(err) {}
}

function dropEmblem(e) {
    e.preventDefault();
    try {
        const data = JSON.parse(e.dataTransfer.getData('text/plain'));
        if (data.type === 'emblem') {
            if (!selectedEmblems.find(em => em.name === data.name)) {
                selectedEmblems.push(data);
                renderSelectionZones();
            }
        }
    } catch(err) {}
}

function renderTraitsSummary(counts, displayBoard, container, mode = 'default') {
    const traitsList = document.createElement('div');
    traitsList.className = 'trait-summary';
    traitsList.style.display = 'flex';
    traitsList.style.flexWrap = 'wrap';
    traitsList.style.gap = '8px';
    traitsList.style.marginTop = '8px';
    
    const emblemTraits = selectedEmblems.map(e => e.trait);
    const sortedTraits = Object.entries(counts).sort((a, b) => b[1] - a[1]);
    
    sortedTraits.forEach(([trait, count]) => {
        const traitInfo = (tftData.trait_metadata && tftData.trait_metadata[trait]) ? tftData.trait_metadata[trait] : null;
        const breakpoints = traitInfo ? traitInfo.breakpoints : null;
        const isActive = breakpoints && breakpoints.some(b => b <= count);
        const isTargon = trait === 'Targon';
        const hasEmblem = emblemTraits.includes(trait);
        const isOrigin = traitInfo && traitInfo.type === 'origin';
        
        if (!breakpoints && !isTargon) return;
        
        const traitItem = document.createElement('div');
        traitItem.style.display = 'flex';
        traitItem.style.alignItems = 'center';
        traitItem.style.gap = '3px';
        traitItem.style.fontSize = '10px';
        
        let textColor = 'var(--text-dim)';
        let filter = isActive ? '' : 'opacity: 0.5; filter: grayscale(1);';
        
        const greenFilter = 'invert(48%) sepia(79%) saturate(2476%) hue-rotate(86deg) brightness(118%) contrast(119%)';
        const orangeFilter = 'invert(65%) sepia(91%) saturate(1831%) hue-rotate(3deg) brightness(103%) contrast(105%)';

        if (mode === 'world-runes' || mode === 'ryze-unlock') {
            if (isOrigin && isActive) {
                textColor = '#32d74b';
                filter = greenFilter;
            } else if (isActive) {
                textColor = 'var(--text-bright)';
            }
        } else {
            if (hasEmblem) {
                if (isActive) { textColor = '#32d74b'; filter = greenFilter; }
                else { textColor = '#ff9500'; filter = orangeFilter; }
            } else if (isActive) { textColor = 'var(--text-bright)'; }
        }

        traitItem.style.color = textColor;
        if (isActive || hasEmblem) traitItem.style.fontWeight = '600';
        
        const iconUrl = `assets/tft/${currentConfig.current_set}/traits/${trait.replace(/ /g, '')}.svg`;
        
        const contributors = displayBoard.filter(u => u.traits.includes(trait)).map(u => u.name);
        const tooltip = `${trait} (${count}): ${contributors.join(', ')}`;
        
        traitItem.innerHTML = `<img src="${iconUrl}" style="width: 14px; height: 14px; filter: ${filter}" title="${tooltip}" onerror="this.style.display='none'"><span title="${tooltip}">${count}</span>`; 
        traitsList.appendChild(traitItem);
    });
    
    container.appendChild(traitsList);
}

function renderSelectionZones() {
    const mustZone = document.getElementById('must-include-zone');
    const currentZone = document.getElementById('current-team-zone');
    const emblemZone = document.getElementById('emblem-drop-zone');
    if (!mustZone || !currentZone || !emblemZone) return;

    currentZone.innerHTML = '';
    if (selectedCurrentTeam.length > 0) {
        selectedCurrentTeam.forEach(u => {
            const unitData = tftData.units.find(du => du.name === u.name);
            const el = createDraggableItem(u.name, u.iconUrl, 'unit', u.cost, null, true, 0, false, 'current-team', unitData ? unitData.traits : []);
            currentZone.appendChild(el);
        });
    } else {
        currentZone.innerHTML = '<div class="placeholder-text">Units Only</div>';
    }

    mustZone.innerHTML = '';
    if (selectedMustInclude.length > 0) {
        selectedMustInclude.forEach(item => {
            let itemTraits = [];
            if (item.type === 'unit') {
                const unitData = tftData.units.find(du => du.name === item.name);
                if (unitData) itemTraits = unitData.traits;
            }
            const el = createDraggableItem(item.name, item.iconUrl, item.type, item.cost, item.trait, true, item.hasOwnProperty('targetBreakpointIndex') ? item.targetBreakpointIndex : 0, true, 'must-include', itemTraits);
            mustZone.appendChild(el);
        });
    } else {
        mustZone.innerHTML = '<div class="placeholder-text">Units or Traits</div>';
    }

    // Calculate and render traits summary for Must Include
    const mustBoardRaw = [];
    selectedMustInclude.forEach(item => {
        if (item.type === 'unit') {
            const unitData = tftData.units.find(du => du.name === item.name);
            if (unitData) mustBoardRaw.push(unitData);
        }
    });
    
    const mustDisplayBoard = getDisplayBoard(mustBoardRaw);
    const mustDisplayCounts = calculateCounts(mustDisplayBoard, selectedEmblems.map(e => e.trait));

    if (mustDisplayBoard.length > 0) {
        const solverModeEl = document.querySelector('input[name="solver-mode"]:checked');
        const solverMode = solverModeEl ? solverModeEl.value : 'default';
        const summaryContainer = document.createElement('div');
        summaryContainer.style.borderTop = '1px dashed var(--border-light)';
        summaryContainer.style.marginTop = '10px';
        summaryContainer.style.paddingTop = '5px';
        renderTraitsSummary(mustDisplayCounts, mustDisplayBoard, summaryContainer, solverMode);
        mustZone.appendChild(summaryContainer);
    }

    emblemZone.innerHTML = '';
    if (selectedEmblems.length > 0) {
        selectedEmblems.forEach(emb => {
            const item = createDraggableItem(emb.name, emb.iconUrl, 'emblem', null, emb.trait, true, 0, false, 'emblems');
            emblemZone.appendChild(item);
        });
    } else {
        emblemZone.innerHTML = '<div class="placeholder-text">Emblems</div>';
    }

    if (unitSortMode === 'smart') {
        renderUnitPools();
    }

    // Re-apply highlight if needed
    if (highlightedUnitIndex >= 0) {
        const zoneId = activeDropZone === 'current-team' ? 'current-team-zone' : 'must-include-zone';
        const zone = document.getElementById(zoneId);
        const items = zone ? zone.querySelectorAll('.draggable-item') : [];
        if (highlightedUnitIndex < items.length) {
            items[highlightedUnitIndex].classList.add('unit-highlight-active');
        } else {
            highlightedUnitIndex = -1;
        }
    }

    saveTFTSelection();
}

function clearCurrentTeam() {
    selectedCurrentTeam = [];
    renderSelectionZones();
}

function clearMustInclude() {
    selectedMustInclude = [];
    renderSelectionZones();
}

function clearEmblems() {
    selectedEmblems = [];
    renderSelectionZones();
}

function resetAll() {
    selectedMustInclude = [];
    selectedCurrentTeam = [];
    selectedEmblems = [];
    const defRadio = document.querySelector('input[name="solver-mode"][value="default"]');
    if (defRadio) defRadio.checked = true;
    const defHeuristic = document.querySelector('input[name="heuristic-mode"][value="super"]');
    if (defHeuristic) defHeuristic.checked = true;
    document.querySelectorAll('.lvl-cb').forEach(cb => {
        cb.checked = (cb.value === "7");
    });
    const slider = document.getElementById('results-limit');
    if (slider) {
        slider.value = 3;
        const valDisplay = document.getElementById('results-limit-value');
        if (valDisplay) valDisplay.innerText = "3";
    }
    const excludeFive = document.getElementById('exclude-five-costs');
    if (excludeFive) excludeFive.checked = true;
    
    unitAlphaFilter = null;
    if (alphaFilterTimeout) {
        clearTimeout(alphaFilterTimeout);
        alphaFilterTimeout = null;
    }

    unitSortMode = 'alpha';
    const sortToggle = document.getElementById('smart-sort-toggle');
    if (sortToggle) sortToggle.checked = false;

    activeDisabledUnits = [...userDefaultDisabledUnits];
    renderUnitPools();
    renderAlphaFilter();

    const resultsContainer = document.getElementById('results-container');
    if (resultsContainer) resultsContainer.innerHTML = '';
    
    const finalDisplay = document.getElementById('final-combinations-display');
    if (finalDisplay) finalDisplay.style.display = 'none';

    const emblemZone = document.getElementById('emblem-drop-zone')?.closest('.control-box');
    if (emblemZone) emblemZone.classList.remove('pulse-highlight');

    clearTraitHighlight();
    renderSelectionZones();
}

function filterEmblems(query) {
    emblemSearch = query;
    renderEmblemPool();
}

function cancelOptimization() {
    if (optimizer) {
        optimizer.cancel();
    }
    if (window.activeWorkers) {
        window.activeWorkers.forEach(w => w.terminate());
        window.activeWorkers = null;
    }
    document.getElementById('cancel-btn').style.display = 'none';
    document.getElementById('run-btn').style.display = 'block';
}

function createOptimizerWorker() {
    // Get the base path for scripts to ensure workers can find tft_optimizer.js
    const scripts = document.getElementsByTagName('script');
    let jsPath = 'js/';
    for (let s of scripts) {
        if (s.src.includes('js/tft.js')) {
            const parts = s.src.split('js/tft.js');
            if (parts.length > 0) {
                // Keep the part before js/tft.js and add js/
                jsPath = parts[0] + 'js/';
            }
            break;
        }
    }

    const blob = new Blob([`
        importScripts('${jsPath}tft_optimizer.js?v=${Date.now()}');
        
        let optimizer = null;

        onmessage = async function(e) {
            const { type, data } = e.data;
            
            if (type === 'init') {
                optimizer = new TFTOptimizer(data.units, data.traitsData);
                return;
            }

            if (type === 'findBestBoards') {
                const { candidates, neededSlots, fixedUnits, size, emblems, mustIncludeNames, mode, mustIncludeTraits, limit, workerId, totalWorkers } = data;
                
                const targetSlots = size;
                const generator = optimizer.getCombos(neededSlots, candidates);
                let processed = 0;
                let results = [];
                let comboIdx = 0;

                for (const combo of generator) {
                    // Simple search space partitioning
                    if (comboIdx % totalWorkers === workerId) {
                        processed++;
                        const currentBoard = [...combo, ...fixedUnits];
                        const { score, counts } = optimizer.scoreBoard(currentBoard, emblems, targetSlots, mode, mustIncludeTraits, mustIncludeNames);
                        if (score > -1000000) { 
                            results.push({ score, board: currentBoard, counts });
                        }
                        
                        if (processed % 1000 === 0) {
                            postMessage({ type: 'progress', data: { processed, workerId } });
                        }
                    }
                    comboIdx++;
                }

                results.sort((a, b) => b.score - a.score);
                const finalResults = results.slice(0, limit);
                postMessage({ type: 'result', data: { results: finalResults, processed, workerId } });
            }
        };
    `], { type: 'application/javascript' });
    return new Worker(URL.createObjectURL(blob));
}

async function runOptimization() {
    if (!optimizer) return;
    optimizer.isCancelled = false; 

    const runBtn = document.getElementById('run-btn');
    const cancelBtn = document.getElementById('cancel-btn');
    
    const improveMode = document.getElementById('improve-mode').checked;
    const mustIncludeNames = selectedMustInclude.filter(i => i.type === 'unit').map(u => u.name);
    
    const mustIncludeTraits = {};
    selectedMustInclude.filter(i => i.type === 'emblem').forEach(item => {
        const meta = tftData.trait_metadata[item.trait];
        if (meta) {
            if (item.targetBreakpointIndex === -1) {
                mustIncludeTraits[item.trait] = meta.breakpoints[0];
            } else {
                mustIncludeTraits[item.trait] = meta.breakpoints[item.targetBreakpointIndex];
            }
        }
    });

    const emblems = selectedEmblems.map(e => e.trait);
    const solverModeEl = document.querySelector('input[name="solver-mode"]:checked');
    const solverMode = solverModeEl ? solverModeEl.value : 'default';
    const heuristicModeEl = document.querySelector('input[name="heuristic-mode"]:checked');
    const heuristicMode = heuristicModeEl ? heuristicModeEl.value : 'standard';

    const selectedLevels = Array.from(document.querySelectorAll('.lvl-cb:checked')).map(cb => parseInt(cb.value));
    const limit = parseInt(document.getElementById('results-limit').value);
    const excludeFiveCosts = document.getElementById('exclude-five-costs').checked;
    
    const resultsContainer = document.getElementById('results-container');
    resultsContainer.innerHTML = '<div style="color: var(--text-dim); padding: 20px;">Initializing...</div>';

    const cancelProgressFill = document.getElementById('cancel-progress-fill');
    const cancelText = document.getElementById('cancel-text');
    const liveExploredEl = document.getElementById('live-explored-count');

    const finalDisplay = document.getElementById('final-combinations-display');
    if (finalDisplay) finalDisplay.style.display = 'none';

    runBtn.style.display = 'none';
    cancelBtn.style.display = 'block';
    
    if (cancelProgressFill) cancelProgressFill.style.width = '0%';
    if (cancelText) cancelText.innerText = 'CANCEL (0%)';
    if (liveExploredEl) liveExploredEl.innerText = 'Explored: 0';
    
    let totalCombinationsExplored = 0;
    
    try {
        if (improveMode) {
            if (selectedCurrentTeam.length === 0) {
                resultsContainer.innerHTML = '<div style="color: var(--danger); padding: 20px;">Error: Current Team is empty!</div>';
                return;
            }
            resultsContainer.innerHTML = '<h2 class="hub-status-label" style="display: block; margin: 20px 0 10px 0;">Team Improvements</h2>';
            const levelList = document.createElement('div');
            levelList.className = 'results-list';
            resultsContainer.appendChild(levelList);

            const currentBoardUnits = selectedCurrentTeam.map(u => tftData.units.find(du => du.name === u.name));
            
            // Calculate exclusion overrides for global solver
            // Based on mustInclude or currentTeam active traits
            const baseForOverrides = mustIncludeNames.length > 0 ? selectedMustInclude.filter(i => i.type === 'unit').map(u => tftData.units.find(du => du.name === u.name)) : currentBoardUnits;
            const exclusionOverrides = getExclusionOverrides(baseForOverrides, emblems);

            const pool = tftData.units.filter(u => {
                if (activeDisabledUnits.includes(u.name)) {
                    if (!u.traits.some(t => exclusionOverrides.includes(t))) return false;
                }
                if (mustIncludeNames.includes(u.name)) return true;
                if (excludeFiveCosts && u.cost === 5) return false;
                return true;
            });

            totalCombinationsExplored = currentBoardUnits.filter(u => !mustIncludeNames.includes(u.name)).length * pool.length;
            if (cancelText) cancelText.innerText = 'IMPROVING...';
            if (cancelProgressFill) cancelProgressFill.style.width = '100%';
            if (liveExploredEl) liveExploredEl.innerText = `Explored: ${totalCombinationsExplored.toLocaleString()}`;

            const suggestionsResult = optimizer.improveTeam(currentBoardUnits, pool, emblems, solverMode, mustIncludeTraits, mustIncludeNames, limit);
            renderImproveResults(suggestionsResult.suggestions, levelList, suggestionsResult.currentCounts);
        } else {
            for (const level of selectedLevels) {
                if (optimizer.isCancelled) break;
                
                const levelHeader = document.createElement('h2');
                levelHeader.className = 'hub-status-label';
                levelHeader.style.display = 'block';
                levelHeader.style.margin = '20px 0 10px 0';
                levelHeader.innerText = `Level ${level} Results`;
                resultsContainer.appendChild(levelHeader);

                const levelList = document.createElement('div');
                levelList.className = 'results-list';
                levelList.innerHTML = ''; 
                resultsContainer.appendChild(levelList);

                // Calculate exclusion overrides for this level's solver
                const baseForOverrides = mustIncludeNames.length > 0 ? selectedMustInclude.filter(i => i.type === 'unit').map(u => tftData.units.find(du => du.name === u.name)) : currentBoardUnits;
                const exclusionOverrides = getExclusionOverrides(baseForOverrides, emblems);

                const pool = tftData.units.filter(u => {
                    if (activeDisabledUnits.includes(u.name)) {
                        if (!u.traits.some(t => exclusionOverrides.includes(t))) return false;
                    }
                    if (mustIncludeNames.includes(u.name)) return true;
                    if (excludeFiveCosts && u.cost === 5) return false;
                    
                    // Cost and Level rules
                    if (level <= 6 && u.cost > 3) return false;
                    if (u.name === "Kennen" && level < 6) return false;
                    if (u.name.includes("Kobuko") && level < 7) return false;
                    if (u.cost === 5 && level < 8) return false;
                    return true;
                });
                
                let results, totalProcessed;

                if (heuristicMode !== 'super' && window.Worker) {
                    const { candidates, neededSlots, fixedUnits } = optimizer.getCandidates(pool, level, emblems, mustIncludeNames, mustIncludeTraits, heuristicMode);
                    const totalForThisLevel = optimizer.countTotalCombos(neededSlots, candidates);

                    const numWorkers = navigator.hardwareConcurrency || 4;
                    const workers = [];
                    window.activeWorkers = workers;
                    
                    const workerResults = [];
                    let finishedWorkers = 0;
                    let workerProgress = new Array(numWorkers).fill(0);

                    const p = new Promise((resolve) => {
                        for (let i = 0; i < numWorkers; i++) {
                            const w = createOptimizerWorker();
                            workers.push(w);
                            w.postMessage({ type: 'init', data: { units: tftData.units, traitsData: tftData.trait_metadata } });
                            w.postMessage({ type: 'findBestBoards', data: { 
                                candidates, neededSlots, fixedUnits,
                                size: level, emblems, mustIncludeNames, 
                                mode: solverMode, mustIncludeTraits, limit, 
                                workerId: i, totalWorkers: numWorkers 
                            } });
                            
                            w.onmessage = (e) => {
                                if (e.data.type === 'progress') {
                                    workerProgress[i] = e.data.data.processed;
                                    const totalProc = workerProgress.reduce((a, b) => a + b, 0);
                                    const pct = Math.min(99, Math.floor((totalProc / totalForThisLevel) * 100));
                                    if (cancelProgressFill) cancelProgressFill.style.width = `${pct}%`;
                                    if (cancelText) cancelText.innerText = `CANCEL LVL ${level} (${pct}%)`;
                                    if (liveExploredEl) liveExploredEl.innerText = `Explored: ${(totalCombinationsExplored + totalProc).toLocaleString()}`;
                                } else if (e.data.type === 'result') {
                                    if (e.data.data && e.data.data.results) {
                                        workerResults.push(...e.data.data.results);
                                    }
                                    workerProgress[i] = e.data.data.processed;
                                    finishedWorkers++;
                                    if (finishedWorkers === numWorkers) {
                                        const totalProc = workerProgress.reduce((a, b) => a + b, 0);
                                        if (cancelProgressFill) cancelProgressFill.style.width = `100%`;
                                        if (cancelText) cancelText.innerText = `CANCEL LVL ${level} (100%)`;
                                        workerResults.sort((a, b) => b.score - a.score);
                                        resolve({ results: workerResults.slice(0, limit), totalProcessed: totalProc });
                                    }
                                }
                            };
                        }
                    });
                    
                    const mtResult = await p;
                    results = mtResult.results;
                    totalProcessed = mtResult.totalProcessed;
                    workers.forEach(w => w.terminate());
                    window.activeWorkers = null;
                } else {
                    const res = await optimizer.findBestBoards(pool, level, emblems, mustIncludeNames, solverMode, mustIncludeTraits, limit, (proc, tot, procOverride) => {
                        const pct = Math.min(100, Math.floor((proc / tot) * 100));
                        if (cancelProgressFill) cancelProgressFill.style.width = `${pct}%`;
                        if (cancelText) cancelText.innerText = `CANCEL LVL ${level} (${pct}%)`;
                        
                        const currentCount = (procOverride !== undefined) ? procOverride : proc;
                        if (liveExploredEl) liveExploredEl.innerText = `Explored: ${(totalCombinationsExplored + currentCount).toLocaleString()}`;
                    }, heuristicMode);
                    results = res.results;
                    totalProcessed = res.totalProcessed;
                }
                
                totalCombinationsExplored += totalProcessed;
                
                const combinationsLabel = document.createElement('div');
                combinationsLabel.style.fontSize = '0.8em';
                combinationsLabel.style.color = '#888';
                combinationsLabel.style.marginBottom = '10px';
                combinationsLabel.innerText = `Explored ${totalProcessed.toLocaleString()} combinations`;
                levelList.appendChild(combinationsLabel);

                renderResults(results, levelList, level);
            }
        }
    } finally {
        runBtn.style.display = 'block';
        cancelBtn.style.display = 'none';
        
        const finalDisplay = document.getElementById('final-combinations-display');
        const finalText = document.getElementById('final-combinations-text');
        
        if (totalCombinationsExplored > 0 && finalDisplay && finalText) {
            finalDisplay.style.display = 'block';
            finalText.innerText = totalCombinationsExplored.toLocaleString();
        }

        if (resultsContainer.firstChild && resultsContainer.firstChild.innerText === 'Initializing...') {
            resultsContainer.removeChild(resultsContainer.firstChild);
        }
    }
}

function calculateCounts(board, emblemTraits) {
    const counts = {};
    board.forEach(u => {
        u.traits.forEach(t => {
            counts[t] = (counts[t] || 0) + 1;
        });
    });
    emblemTraits.forEach(trait => {
        counts[trait] = (counts[trait] || 0) + 1;
    });
    return counts;
}

function getDisplayBoard(board) {
    return [...board].map((u, i) => ({ 
        ...u, 
        originalIdx: (u.originalIdx !== undefined ? u.originalIdx : i) 
    }));
}

function renderResults(results, container, level) {
    container.innerHTML = '';
    if (results.length === 0) {
        container.innerHTML = '<div style="color: var(--text-dim); font-size: 11px;">No valid compositions found.</div>';
        return;
    }
    const emblemTraits = selectedEmblems.map(e => e.trait);
    const solverModeEl = document.querySelector('input[name="solver-mode"]:checked');
    const solverMode = solverModeEl ? solverModeEl.value : 'default';

    const mustIncludeNames = selectedMustInclude.filter(i => i.type === 'unit').map(u => u.name);
    const mustIncludeTraits = {};
    selectedMustInclude.filter(i => i.type === 'emblem').forEach(item => {
        const meta = tftData.trait_metadata[item.trait];
        if (meta) {
            if (item.targetBreakpointIndex === -1) {
                mustIncludeTraits[item.trait] = meta.breakpoints[0];
            } else {
                mustIncludeTraits[item.trait] = meta.breakpoints[item.targetBreakpointIndex];
            }
        }
    });

    results.forEach((res, index) => {
        const card = document.createElement('div');
        card.className = 'result-card';
        card.style.marginBottom = '10px';
        
        const displayBoard = getDisplayBoard(res.board);
        const displayCounts = calculateCounts(displayBoard, emblemTraits);

        let bronzeInfo = "";
        if (solverMode === 'bronze-for-life') {
            const activeCount = Object.keys(displayCounts).filter(t => {
                if (t === 'Targon') return false; 
                const traitInfo = tftData.trait_metadata[t];
                return traitInfo && traitInfo.breakpoints.some(b => b <= displayCounts[t]);
            }).length;
            bronzeInfo = `<span style="font-size: 10px; color: var(--text-dim); margin-left: 8px;">(${activeCount} active)</span>`;
        }

        card.innerHTML = `<div style="display: flex; justify-content: space-between; margin-bottom: 8px;">
                <div style="display: flex; align-items: center; gap: 8px;">
                    <strong>Option ${index + 1} ${bronzeInfo}</strong>
                    <button class="icon-btn" onclick="copyResultCode(this)" title="Copy Team Code" style="background: none; border: none; cursor: pointer; padding: 2px; display: flex; align-items: center; color: var(--text-dim); transition: color 0.2s;">
                        <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
                    </button>
                    <button class="icon-btn" onclick="openTreeExplorer(this)" title="Open Tree Explorer" style="background: none; border: none; cursor: pointer; padding: 2px; display: flex; align-items: center; color: var(--text-dim); transition: color 0.2s;">
                        <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 3h6v6"></path><path d="M9 21H3v-6"></path><path d="M21 3l-7 7"></path><path d="M3 21l7-7"></path></svg>
                    </button>
                </div>
                <span style="color: var(--accent); font-weight: 600;">${res.score}</span>
            </div>`;
        
        // Store the result board for easy access
        card.dataset.board = JSON.stringify(res.board);
        
        const list = document.createElement('div');
        list.className = 'unit-list';
        list.style.gap = '4px';
        const sortedBoard = displayBoard.sort((a, b) => a.cost - b.cost || a.name.localeCompare(b.name));
        sortedBoard.forEach((u) => {
            const activeTraits = Object.keys(res.counts).filter(t => (tftData.trait_metadata && tftData.trait_metadata[t] && tftData.trait_metadata[t].breakpoints.some(b => b <= res.counts[t])));
            const contributedTraits = u.traits.filter(t => activeTraits.includes(t));
            const isFlex = contributedTraits.length === 1 && u.originalIdx !== -1;
            const unitItem = document.createElement('div');
            unitItem.className = 'unit-item';
            const costColors = { 1: '#808080', 2: '#11b288', 3: '#207ac7', 4: '#c440da', 5: '#ffb93b' };
            const borderColor = costColors[u.cost] || '#ccc';
            unitItem.innerHTML = `<img src="${u.icon_url}" class="unit-icon" style="border-color: ${borderColor};" 
                     onerror="this.src='data:image/svg+xml;base64,PHN2ZyB4bWxucz0naHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmcnIHdpZHRoPSczNicgaGVpZ2h0PSczNicgc3R5bGU9J2JhY2tncm91bmQ6IzIyMjsnPjx0ZXh0IHg9JzUwJScgeT0nNTAlJyBkb20tYmFzZWxpbmU9J21pZGRsZScgdGV4dC1hbmNob3I9J21pZGRsZScgZmlsbD0nI2ZmZicgZm9udC1zaXplPSc4Jz4/IDwvdGV4dD48L3N2Zz4='">
                ${isFlex ? '<div class="replacement-indicator">*</div>' : ''}
                <div class="unit-name">${u.name}</div>`;
            const repList = document.createElement('div');
            repList.className = 'replacement-list';
            repList.style.top = '90%'; 
            repList.style.left = '50%';
            repList.style.transform = 'translateX(-50%)';
            const traitHeader = document.createElement('div');
            traitHeader.style.padding = '4px';
            traitHeader.style.borderBottom = contributedTraits.length > 0 ? '1px solid var(--border)' : 'none';
            traitHeader.style.color = 'var(--accent)';
            traitHeader.style.fontWeight = 'bold';
            traitHeader.style.fontSize = '9px';
            traitHeader.innerText = contributedTraits.length > 0 ? contributedTraits.join(', ') : 'No Active Traits';
            repList.appendChild(traitHeader);
            if (isFlex) {
                const flexTrait = contributedTraits[0];
                const boardNames = displayBoard.map(b => b.name);
                const replacements = tftData.units.filter(alt => !boardNames.includes(alt.name) && alt.traits.includes(flexTrait) && !activeDisabledUnits.includes(alt.name) && (level <= 6 ? alt.cost <= 3 : true) && !(alt.name === "Kennen" && level < 6) && !(alt.name.includes("Kobuko") && level < 7) && !(alt.cost === 5 && level < 8)).sort((a, b) => a.cost - b.cost);
                if (replacements.length > 0) {
                    replacements.forEach(rep => {
                        const opt = document.createElement('div');
                        opt.className = 'replacement-option';
                        opt.innerHTML = `<img src="${rep.icon_url}" onerror="this.style.background='#333'"> <span>${rep.name} (${rep.cost})</span>`;
                        opt.onclick = (e) => { 
                            e.stopPropagation(); 
                            if (u.originalIdx !== -1) {
                                res.board[u.originalIdx] = rep; 
                                // Re-calculate counts for accurate trait display after replacement
                                const newCounts = {};
                                res.board.forEach(unit => {
                                    unit.traits.forEach(t => newCounts[t] = (newCounts[t] || 0) + 1);
                                });
                                selectedEmblems.forEach(emb => newCounts[emb.trait] = (newCounts[emb.trait] || 0) + 1);
                                res.counts = newCounts;
                                
                                renderResults(results, container, level); 
                            }
                        };
                        repList.appendChild(opt);
                    });
                }
            }
            unitItem.appendChild(repList);
            unitItem.onmouseenter = () => repList.classList.add('active');
            unitItem.onmouseleave = () => repList.classList.remove('active');
            list.appendChild(unitItem);
        });
        card.appendChild(list);
        
        // Traits Section
        renderTraitsSummary(displayCounts, displayBoard, card, solverMode);

        // Next Unit Suggestions Section
        const nextUnits = optimizer.getBestNextUnits(res.board, tftData.units.filter(u => !activeDisabledUnits.includes(u.name)), selectedEmblems.map(e => e.trait), solverMode, mustIncludeTraits, mustIncludeNames, 3);
        if (nextUnits.length > 0) {
            const suggestionsDiv = document.createElement('div');
            suggestionsDiv.className = 'next-suggestions';
            suggestionsDiv.style.marginTop = '12px';
            suggestionsDiv.style.paddingTop = '8px';
            suggestionsDiv.style.borderTop = '1px dashed var(--border-light)';
            
            const suggestTitle = document.createElement('div');
            suggestTitle.style.fontSize = '9px';
            suggestTitle.style.color = 'var(--accent)';
            suggestTitle.style.fontWeight = 'bold';
            suggestTitle.style.marginBottom = '6px';
            suggestTitle.style.textTransform = 'uppercase';
            suggestTitle.innerText = 'Best Next Units (Level Up)';
            suggestionsDiv.appendChild(suggestTitle);

            const suggestList = document.createElement('div');
            suggestList.style.display = 'flex';
            suggestList.style.gap = '8px';
            
            nextUnits.forEach(s => {
                const item = document.createElement('div');
                item.style.position = 'relative';
                item.style.width = '32px';
                item.style.cursor = 'help';
                
                const costColors = { 1: '#808080', 2: '#11b288', 3: '#207ac7', 4: '#c440da', 5: '#ffb93b' };
                const borderColor = costColors[s.unit.cost] || '#ccc';
                
                // Calculate which traits are improved
                const improvedTraits = Object.entries(s.counts)
                    .filter(([t, c]) => {
                        const prevC = displayCounts[t] || 0;
                        if (c <= prevC) return false;
                        const meta = tftData.trait_metadata[t];
                        if (!meta) return false;
                        return meta.breakpoints.some(b => b > prevC && b <= c);
                    })
                    .map(([t, c]) => t);

                const tooltip = `+ ${s.unit.name} (${s.unit.cost}g)\nScore Boost: ${s.scoreBoost}\n${improvedTraits.length > 0 ? 'Powers Up: ' + improvedTraits.join(', ') : 'No new breakpoints'}`;
                
                item.innerHTML = `
                    <img src="${s.unit.icon_url}" style="width: 32px; height: 32px; border: 1px solid ${borderColor}; border-radius: 4px;" title="${tooltip}">
                    <div style="position: absolute; top: -4px; right: -4px; background: #32d74b; color: black; border-radius: 50%; width: 12px; height: 12px; display: flex; align-items: center; justify-content: center; font-size: 10px; font-weight: bold; border: 1px solid #121212;">+</div>
                `;
                suggestList.appendChild(item);
            });
            suggestionsDiv.appendChild(suggestList);
            card.appendChild(suggestionsDiv);
        }

        container.appendChild(card);
    });
}

function renderImproveResults(suggestions, container, currentCounts) {
    if (suggestions.length === 0) {
        container.innerHTML = '<div style="color: var(--text-dim); font-size: 11px;">No improvements found.</div>';
        return;
    }
    const emblemTraits = selectedEmblems.map(e => e.trait);
    const solverModeEl = document.querySelector('input[name="solver-mode"]:checked');
    const solverMode = solverModeEl ? solverModeEl.value : 'default';

    suggestions.forEach((group, index) => {
        const bestCandidate = group.candidates[0];
        const card = document.createElement('div');
        card.className = 'result-card';
        card.style.border = '1px solid var(--accent)';
        let candidatesHtml = group.candidates.map(c => `
            <div style="display: flex; flex-direction: column; align-items: center; gap: 2px;">
                <img src="${c.unit.icon_url}" style="width: 40px; height: 40px; border: 2px solid #32d74b; border-radius: 4px; box-shadow: 0 0 10px rgba(50, 215, 75, 0.3);">
                <span style="font-size: 9px; color: var(--accent); font-weight: bold;">${c.score}</span>
            </div>
        `).join('<span style="color: var(--text-dimer); margin-top: 15px;">,</span>');
        card.innerHTML = `<div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12px;">
                <div style="display: flex; align-items: center; gap: 12px;">
                    <div style="display: flex; flex-direction: column; align-items: center; gap: 2px;">
                        <img src="${group.replacedUnit.icon_url}" style="width: 32px; height: 32px; border: 1px solid #ff453a; border-radius: 4px; opacity: 0.6;">
                        <span style="font-size: 8px; color: var(--text-dim);">Current</span>
                    </div>
                    <span style="color: var(--text-dim); font-size: 20px;">➜</span>
                    <div style="display: flex; gap: 10px;">
                        ${candidatesHtml}
                    </div>
                </div>
                <button class="icon-btn" onclick="copyResultCode(this)" title="Copy Improved Team Code" style="background: none; border: none; cursor: pointer; padding: 2px; display: flex; align-items: center; color: var(--text-dim); transition: color 0.2s;">
                    <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
                </button>
            </div>`;
        
        // Store the best candidate board
        card.dataset.board = JSON.stringify(bestCandidate.board);
        
        const displayBoard = getDisplayBoard(bestCandidate.board);
        const displayCounts = calculateCounts(displayBoard, emblemTraits);
        
        const currentDisplayBoard = getDisplayBoard(selectedCurrentTeam);
        const currentDisplayCounts = calculateCounts(currentDisplayBoard, emblemTraits);

        const list = document.createElement('div');
        list.className = 'unit-list';
        list.style.gap = '4px';
        const sortedBoard = [...displayBoard].sort((a, b) => a.cost - b.cost || a.name.localeCompare(b.name));
        sortedBoard.forEach(u => {
            const item = document.createElement('div');
            item.className = 'unit-item';
            const costColors = { 1: '#808080', 2: '#11b288', 3: '#207ac7', 4: '#c440da', 5: '#ffb93b' };
            const isNew = u.name === bestCandidate.unit.name;
            const borderColor = isNew ? '#32d74b' : (costColors[u.cost] || '#ccc');
            item.innerHTML = `<img src="${u.icon_url}" class="unit-icon" style="border-color: ${borderColor}; border-width: ${isNew ? '2px' : '1px'}"><div class="unit-name">${u.name}</div>`;
            list.appendChild(item);
        });
        card.appendChild(list);

        const traitsList = document.createElement('div');
        traitsList.className = 'trait-summary';
        traitsList.style.display = 'flex';
        traitsList.style.flexWrap = 'wrap';
        traitsList.style.gap = '10px';
        traitsList.style.marginTop = '10px';
        traitsList.style.paddingTop = '8px';
        traitsList.style.borderTop = '1px solid var(--border-light)';
        const allTraitNames = new Set([...Object.keys(currentDisplayCounts), ...Object.keys(displayCounts)]);
                Array.from(allTraitNames).sort().forEach(trait => {
                    const count = displayCounts[trait] || 0;
                    const prevCount = currentDisplayCounts[trait] || 0;
                    const traitInfo = (tftData.trait_metadata && tftData.trait_metadata[trait]) ? tftData.trait_metadata[trait] : null;
                    const breakpoints = traitInfo ? traitInfo.breakpoints : null;
                    const isTargon = trait === 'Targon';
                    const isOrigin = traitInfo && traitInfo.type === 'origin';
                    
                    if (!breakpoints && !isTargon) return;
            const getTier = (c) => { if (!breakpoints) return 0; const reached = breakpoints.filter(b => b <= c); return reached.length > 0 ? Math.max(...reached) : 0; };
            const oldTier = getTier(prevCount);
            const newTier = getTier(count);
            const isActive = newTier > 0 || (isTargon && count >= 1);
            
            const greenFilter = 'invert(48%) sepia(79%) saturate(2476%) hue-rotate(86deg) brightness(118%) contrast(119%)';
            const redFilter = 'invert(27%) sepia(91%) saturate(7484%) hue-rotate(351deg) brightness(101%) contrast(114%)';
            const orangeFilter = 'invert(85%) sepia(61%) saturate(1000%) hue-rotate(3deg) brightness(105%) contrast(105%)';
            
            let status = 'none'; 
            if (newTier > oldTier) status = 'added';
            else if (newTier < oldTier && count === 0) status = 'removed';
            else if (newTier !== oldTier || count !== prevCount) status = 'changed';
            
            const traitItem = document.createElement('div');
            traitItem.style.display = 'flex';
            traitItem.style.alignItems = 'center';
            traitItem.style.gap = '4px';
            traitItem.style.fontSize = '11px';
            let color = 'var(--text-dim)';
            let filter = 'opacity: 0.5; filter: grayscale(1);';
            
            if (solverMode === 'world-runes' || solverMode === 'ryze-unlock') {
                if (isOrigin && isActive) {
                    color = '#32d74b';
                    filter = greenFilter;
                } else if (isActive) {
                    color = 'var(--text-bright)';
                    filter = '';
                }
            } else {
                if (status === 'added') { color = '#32d74b'; filter = greenFilter; }
                else if (status === 'removed') { color = '#ff453a'; filter = redFilter; }
                else if (status === 'changed') { color = '#ffd700'; filter = orangeFilter; }
                else if (isActive) { color = 'var(--text-bright)'; filter = ''; }
            }

            const iconUrl = `assets/tft/${currentConfig.current_set}/traits/${trait.replace(/ /g, '')}.svg`;
            traitItem.style.color = color;
            traitItem.style.fontWeight = status !== 'none' ? 'bold' : 'normal';
            traitItem.innerHTML = `<img src="${iconUrl}" style="width: 16px; height: 16px; filter: ${filter}" title="${trait}" onerror="this.style.display='none'"><span>${count}</span>`;
            traitsList.appendChild(traitItem);
        });
        card.appendChild(traitsList);
        container.appendChild(card);
    });
}

async function runLogicTests() {
    const resultsDiv = document.getElementById('test-results');
    if (!resultsDiv) return;
    resultsDiv.innerHTML = "";
    if (!tftData || !optimizer) return;
    const runner = new TFTTester(optimizer, tftData);
            const tests = [
                runner.testLevel4Optimal, runner.testLevel4CostConstraint,
                runner.testLevel5CostConstraint, runner.testBronzeForLifeLogic, runner.testLevel10Constraints,
                runner.testSpecificLevelUnitRestrictions, runner.testMustIncludeTraits, runner.testAnnieOneArcanistFix,
                runner.testTargonSpecialLogic, runner.testUnitReplacementPersistenceBug, runner.testSylasForbiddenUnits,
                runner.testLevel8FiveCostLimit, runner.testLevel9Constraints, runner.testTraitIgnoreList,
                runner.testForbiddenShurima, runner.testCarryRequirements, runner.testMustIncludeBypassLevelRestriction,
                runner.testNeekoNidaleeLogic, runner.testNeekoNidaleeOneWayLogic,
                runner.testNidaleeRequiresNeeko, runner.testSuperHeuristicPoppyLevel6, runner.testSuperHeuristicKobukoLevel6,
                runner.testWorldRunesLogic, runner.testRuneSolverLevel5, runner.testRuneSolverLevel5PiltoverDemacia,
                runner.testRyzeUnlockSolver, runner.testSaveLoadComps, runner.testFullLoadFlow,
                runner.testDemacia7AtLevel8
            ];    for (const test of tests) {
        const statusEl = document.createElement('div');
        statusEl.style.color = "#aaa";
        statusEl.innerText = `[WAIT] Running ${test.name}...`;
        resultsDiv.appendChild(statusEl);
        await new Promise(resolve => setTimeout(resolve, 50));
        const start = performance.now();
        try {
            await test.call(runner);
            const duration = (performance.now() - start).toFixed(1);
            statusEl.style.color = "#32d74b";
            statusEl.innerText = `[PASS] ${test.name} (${duration}ms)`;
        } catch (err) {
            const duration = (performance.now() - start).toFixed(1);
            statusEl.style.color = "#ff453a";
            statusEl.innerText = `[FAIL] ${test.name} (${duration}ms)`;
            const errEl = document.createElement('div');
            errEl.style.color = "#666"; errEl.style.marginLeft = "20px"; errEl.style.marginBottom = "10px";
            errEl.innerText = err.message;
            resultsDiv.appendChild(errEl);
        }
    }

    // Run custom evolution rules test
    if (typeof window.runEvolutionTest === 'function') {
        const evoStatus = document.createElement('div');
        evoStatus.style.color = "#aaa";
        evoStatus.innerText = "[WAIT] Running Evolution Rules Test...";
        resultsDiv.appendChild(evoStatus);
        try {
            await window.runEvolutionTest();
            evoStatus.style.color = "#32d74b";
            evoStatus.innerText = "[PASS] Evolution Rules Test (See Console for details)";
        } catch (e) {
            evoStatus.style.color = "#ff453a";
            evoStatus.innerText = `[FAIL] Evolution Rules Test: ${e.message}`;
        }
    }
}

loadTFTData();

// --- Composition Templates Storage ---

function handleSaveComp() {
    if (selectedCurrentTeam.length === 0) {
        alert("Current team is empty!");
        return;
    }
    
    const selectedLevels = Array.from(document.querySelectorAll('.lvl-cb:checked')).map(cb => parseInt(cb.value));
    saveComp(selectedCurrentTeam, selectedLevels);
}

function saveComp(units, levels) {
    if (!units || units.length === 0) return;
    
    const comps = loadComps();
    const newComp = {
        id: Date.now(),
        units: JSON.parse(JSON.stringify(units)), // Deep copy
        levels: [...levels],
        iconUrl: units[0].iconUrl || units[0].icon_url
    };
    
    comps.push(newComp);
    localStorage.setItem('tft_saved_comps', JSON.stringify(comps));
    renderSavedComps();
}

function loadComps() {
    const stored = localStorage.getItem('tft_saved_comps');
    return stored ? JSON.parse(stored) : [];
}

function deleteComp(compId) {
    let comps = loadComps();
    comps = comps.filter(c => c.id !== compId);
    localStorage.setItem('tft_saved_comps', JSON.stringify(comps));
    renderSavedComps();
}

function renderSavedComps() {
    const container = document.getElementById('saved-comps-container');
    if (!container) return;
    container.innerHTML = '';

    const comps = loadComps();
    if (comps.length === 0) {
        container.innerHTML = '<div class="placeholder-text" style="width: 100%; text-align: center; line-height: 36px;">No Saved Comps</div>';
        return;
    }

    comps.forEach(comp => {
        const div = document.createElement('div');
        div.className = 'comp-item';
        div.title = "Click to load, X to delete";
        
        div.innerHTML = `
            <img src="${comp.iconUrl}" class="comp-icon" onerror="this.src='data:image/svg+xml;base64,PHN2ZyB4bWxucz0naHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmcnIHdpZHRoPSc0OCcgaGVpZ2h0PSc0OCcgc3R5bGU9J2JhY2tncm91bmQ6IzIyMjsnPjx0ZXh0IHg9JzUwJScgeT0nNTAlJyBkb20tYmFzZWxpbmU9J21pZGRsZScgdGV4dC1hbmNob3I9J21pZGRsZScgZmlsbD0nI2ZmZicgZm9udC1zaXplPScxMic+PyA8L3RleHQ+PC9zdmc+'">
            <button class="comp-delete-btn" onclick="event.stopPropagation(); deleteComp(${comp.id})">×</button>
        `;
        
        div.onclick = () => loadComp(comp);
        container.appendChild(div);
    });
}

function loadComp(comp) {
    if (!comp || !comp.units) return;

    // Re-map units to ensure we have fresh data from tftData if available
    const restoredUnits = comp.units.map(savedUnit => {
        const freshUnit = tftData.units.find(u => u.name === savedUnit.name);
        if (freshUnit) {
            return {
                name: freshUnit.name,
                iconUrl: freshUnit.icon_url,
                type: 'unit',
                cost: freshUnit.cost
            };
        }
        return savedUnit;
    });

    // 1. Replace Current Team
    selectedCurrentTeam = [...restoredUnits];

    // 2. Replace Must Include with a copy (units only)
    // Preserve existing emblems in must include
    const existingEmblems = selectedMustInclude.filter(i => i.type === 'emblem');
    selectedMustInclude = [...restoredUnits, ...existingEmblems];

    // 3. Restore Levels
    document.querySelectorAll('.lvl-cb').forEach(cb => {
        cb.checked = comp.levels.includes(parseInt(cb.value));
    });

    // 4. Update UI
    renderSelectionZones();
    renderUnitPools(); // Trigger smart sort if active
}

// --- Team Planner Code Integration ---

function importTeamPlannerCode() {
    const codeArea = document.getElementById('team-planner-code');
    if (!codeArea) return;
    
    const code = codeArea.value.trim();
    if (!code) return;

    try {
        const unitNames = TeamPlannerCode.decode(code);
        if (unitNames.length === 0) {
            alert('No units found in code.');
            return;
        }

        // Clear current team
        selectedCurrentTeam = [];
        
        // Add decoded units
        unitNames.forEach(name => {
            const freshUnit = tftData.units.find(u => u.name === name);
            if (freshUnit) {
                selectedCurrentTeam.push({
                    name: freshUnit.name,
                    iconUrl: freshUnit.icon_url,
                    type: 'unit',
                    cost: freshUnit.cost
                });
            } else {
                console.warn('Imported unit not found in current set:', name);
            }
        });

        renderSelectionZones();
        renderUnitPools();
        
        // Switch to Solver tab to see the result
        switchTab('solver');
        
        alert(`Successfully imported ${selectedCurrentTeam.length} units.`);
    } catch (err) {
        alert('Failed to import code: ' + err.message);
    }
}

function copyResultCode(target) {
    let card;
    if (typeof target === 'number') {
        const cards = document.querySelectorAll('.result-card');
        card = cards[target];
    } else {
        card = target.closest('.result-card');
    }

    if (card) {
        try {
            const board = JSON.parse(card.dataset.board);
            const code = TeamPlannerCode.encode(board);
            
            if (hubConnection && hubConnection.state === signalR.HubConnectionState.Connected) {
                hubConnection.invoke("UpdateClipboard", code);
                // Visual feedback
                const btn = card.querySelector('.icon-btn');
                if (btn) {
                    const originalColor = btn.style.color;
                    btn.style.color = 'var(--accent)';
                    setTimeout(() => btn.style.color = originalColor, 1000);
                }
            } else {
                const tempInput = document.createElement('input');
                document.body.appendChild(tempInput);
                tempInput.value = code;
                tempInput.select();
                document.execCommand('copy');
                document.body.removeChild(tempInput);
                alert('Copied to local clipboard (Hub disconnected)');
            }
        } catch (err) {
            alert('Failed to copy: ' + err.message);
        }
    }
}


function copyZoneCode(zone, event) {
    let units = [];
    if (zone === 'current-team') {
        units = selectedCurrentTeam;
    } else if (zone === 'must-include') {
        units = selectedMustInclude.filter(i => i.type === 'unit');
    }

    if (units.length === 0) {
        alert('Zone is empty!');
        return;
    }

    try {
        const code = TeamPlannerCode.encode(units);
        if (hubConnection && hubConnection.state === signalR.HubConnectionState.Connected) {
            hubConnection.invoke("UpdateClipboard", code);
            // Visual feedback
            if (event && event.currentTarget) {
                const btn = event.currentTarget;
                const originalColor = btn.style.color;
                btn.style.color = 'var(--accent)';
                setTimeout(() => btn.style.color = originalColor, 1000);
            }
        } else {
            // Fallback to local clipboard if possible, but browser usually requires user gesture
            const tempInput = document.createElement('input');
            document.body.appendChild(tempInput);
            tempInput.value = code;
            tempInput.select();
            document.execCommand('copy');
            document.body.removeChild(tempInput);
            alert('Copied to local clipboard (Hub disconnected)');
        }
    } catch (err) {
        alert('Failed to copy: ' + err.message);
    }
}

async function pasteToZone(zone, event) {
    let code = "";
    
    // 1. Try Hub clipboard first (Instant fetch)
    if (hubConnection && hubConnection.state === signalR.HubConnectionState.Connected) {
        try {
            code = await hubConnection.invoke("GetClipboardText");
        } catch (e) {
            console.error("Hub GetClipboardText failed:", e);
            code = lastReceivedClipboard;
        }
    }

    // 2. If Hub code doesn't look like a TFT code, try browser clipboard (may show menu)
    if (!code || !code.startsWith("02") || !code.endsWith("TFTSet16")) {
        try {
            code = await navigator.clipboard.readText();
        } catch (e) {
            console.warn("Clipboard API failed and no Hub code available.");
        }
    }

    if (!code || !code.trim()) {
        alert("Clipboard is empty.");
        return;
    }

    code = code.trim();
    if (!code.startsWith("02") || !code.endsWith("TFTSet16")) {
        alert("Invalid code format in clipboard.");
        return;
    }

    try {
        const unitNames = TeamPlannerCode.decode(code);
        const restoredUnits = [];
        
        unitNames.forEach(name => {
            const freshUnit = tftData.units.find(u => u.name === name);
            if (freshUnit) {
                restoredUnits.push({
                    name: freshUnit.name,
                    iconUrl: freshUnit.icon_url,
                    type: 'unit',
                    cost: freshUnit.cost
                });
            }
        });

        if (zone === 'current-team') {
            selectedCurrentTeam = restoredUnits;
        } else if (zone === 'must-include') {
            const existingEmblems = selectedMustInclude.filter(i => i.type === 'emblem');
            selectedMustInclude = [...restoredUnits, ...existingEmblems];
        }

        renderSelectionZones();
        renderUnitPools();
        
        // Visual feedback
        if (event && event.currentTarget) {
            const btn = event.currentTarget;
            const originalColor = btn.style.color;
            btn.style.color = 'var(--accent)';
            setTimeout(() => btn.style.color = originalColor, 1000);
        }
        
    } catch (err) {
        alert("Failed to paste: " + err.message);
    }
}

function exportTeamPlannerCode() {
    if (selectedCurrentTeam.length === 0) {
        alert('Current team is empty!');
        return;
    }

    try {
        const code = TeamPlannerCode.encode(selectedCurrentTeam);
        const codeArea = document.getElementById('team-planner-code');
        if (codeArea) {
            codeArea.value = code;
            codeArea.select();
        }
        
        if (hubConnection && hubConnection.state === signalR.HubConnectionState.Connected) {
            hubConnection.invoke("UpdateClipboard", code);
            alert('Code exported and sent to Hub clipboard!');
        } else {
            document.execCommand('copy');
            alert('Code exported and copied to local clipboard (Hub disconnected)!');
        }
    } catch (err) {
        alert('Failed to export code: ' + err.message);
    }
}

async function startNewQuiz() {
    if (!optimizer || !tftData) return;
    
    // UI Reset
    quizState.isAnswered = false;
    quizState.guessedUnits = [];
    document.getElementById('next-quiz-btn').style.display = 'none';
    document.getElementById('quiz-loading').style.display = 'block';
    document.getElementById('quiz-feedback').className = 'quiz-feedback';
    if (quizState.timer) clearInterval(quizState.timer);
    
    // Random Level 4-8
    let level = Math.floor(Math.random() * 5) + 4;
    if (quizState.difficulty === 'hard') level = Math.min(8, level + 1);
    
    quizState.lastLevel = level;
    document.getElementById('quiz-board-title').innerText = `${quizState.difficulty.toUpperCase()} - Level ${level} Board`;
    
    // Pick seeds
    const seeds = [];
    const seedCount = level >= 6 ? 2 : 1;
    while (seeds.length < seedCount) {
        const randUnit = tftData.units[Math.floor(Math.random() * tftData.units.length)];
        if (!activeDisabledUnits.includes(randUnit.name) && 
            randUnit.cost <= (level <= 6 ? 3 : 5) && !seeds.includes(randUnit.name)) {
            seeds.push(randUnit.name);
        }
    }
    
    let quizEmblems = [];
    if (Math.random() < 0.3) {
        const emblemItems = tftData.items.filter(i => i.is_emblem);
        quizEmblems.push(emblemItems[Math.floor(Math.random() * emblemItems.length)].trait);
    }
    
    const pool = tftData.units.filter(u => {
        if (activeDisabledUnits.includes(u.name)) return false;
        if (level <= 6 && u.cost > 3) return false;
        if (level < 8 && u.cost === 5) return false;
        return true;
    });
    
    try {
        const res = await optimizer.findBestBoards(pool, level, quizEmblems, seeds, 'default', {}, 1, null, 'aggressive');
        if (res.results.length > 0) {
            const board = res.results[0].board;
            quizState.currentBoard = board;
            quizState.activeEmblems = quizEmblems;
            quizState.finalCounts = res.results[0].counts;
            
            const hideable = board.filter(u => !seeds.includes(u.name));
            
            // Logic for multiple hidden units in Master+
            let numToHide = 1;
            if (quizState.rankIndex >= 7 && Math.random() > 0.4 && hideable.length >= 2) {
                numToHide = 2;
            }

            quizState.hiddenUnits = [];
            let poolToHide = [...hideable];
            for (let i = 0; i < numToHide && poolToHide.length > 0; i++) {
                const idx = Math.floor(Math.random() * poolToHide.length);
                quizState.hiddenUnits.push(poolToHide.splice(idx, 1)[0]);
            }
            
            // Find valid alternatives for each hidden unit
            quizState.validAlternativeUnits = [];
            const bestScore = res.results[0].score;
            
            for (let i = 0; i < quizState.hiddenUnits.length; i++) {
                const hu = quizState.hiddenUnits[i];
                const alternatives = [hu]; // Best unit is always an alternative
                
                // Try replacing ONLY this hidden unit with others from pool
                for (const candidate of pool) {
                    if (board.some(u => u.name === candidate.name)) continue;
                    
                    const testBoard = board.map(u => u.name === hu.name ? candidate : u);
                    const { score } = optimizer.scoreBoard(testBoard, quizEmblems, level);
                    
                    if (score >= bestScore - SCORE_THRESHOLD) {
                        alternatives.push(candidate);
                    }
                }
                quizState.validAlternativeUnits.push(alternatives);
            }

            // Calculate base counts (excluding ALL hidden units)
            const hiddenNames = quizState.hiddenUnits.map(u => u.name);
            const visibleUnits = board.filter(u => !hiddenNames.includes(u.name));
            quizState.baseCounts = calculateBoardCounts(visibleUnits, quizEmblems);
            
            renderQuizBoard();
            renderQuizOptions();
            renderQuizTraits();
            
            let time = getQuizTime();
            if (quizState.difficulty === 'zen') {
                document.getElementById('quiz-timer').innerText = "Zen Mode";
            } else {
                startQuizTimer(time);
            }
        }
    } catch (err) {
        console.error("Quiz generation failed:", err);
    } finally {
        document.getElementById('quiz-loading').style.display = 'none';
    }
}

function startQuizTimer(seconds) {
    quizState.secondsLeft = seconds || 15;
    updateTimerDisplay();
    quizState.timer = setInterval(() => {
        quizState.secondsLeft--;
        updateTimerDisplay();
        if (quizState.secondsLeft <= 0) {
            clearInterval(quizState.timer);
            if (!quizState.isAnswered) handleQuizGuess(null);
        }
    }, 1000);
}

function updateTimerDisplay() {
    const el = document.getElementById('quiz-timer');
    if (el) {
        if (quizState.difficulty === 'zen') {
            el.innerText = "Zen Mode";
            el.style.color = 'var(--text-dim)';
        } else {
            el.innerText = `Time: ${quizState.secondsLeft}s`;
            el.style.color = quizState.secondsLeft <= 5 ? 'var(--danger)' : 'var(--accent)';
        }
    }
}

function renderQuizTraits() {
    const container = document.getElementById('quiz-board-traits');
    if (!container) return;
    container.innerHTML = '';
    
    if (!quizState.showTraits && !quizState.isAnswered) {
        container.innerHTML = '<div style="color: var(--text-dimmer); font-size: 10px;">Traits Hidden</div>';
        return;
    }

    if (quizState.difficulty === 'hard' && !quizState.isAnswered) {
        container.innerHTML = '<div style="color: var(--danger); font-size: 10px; font-weight: bold;">HARDCORE: No Traits Hint</div>';
        return;
    }

    const countsToUse = quizState.isAnswered ? quizState.finalCounts : quizState.baseCounts;
    const sortedTraits = Object.entries(countsToUse).sort((a, b) => b[1] - a[1]);

    sortedTraits.forEach(([trait, count]) => {
        const traitInfo = tftData.trait_metadata[trait];
        if (!traitInfo) return;
        
        const breakpoints = traitInfo.breakpoints;
        const reached = breakpoints.filter(b => b <= count);
        const isActive = reached.length > 0 || trait === 'Targon';
        
        // During guessing, if showTraits is ON, we show everything.
        // If OFF, we show nothing (handled above).
        // If Hardcore, we show nothing (handled above).
        // After reveal, we show everything.

        const traitEl = document.createElement('div');
        traitEl.style.display = 'flex';
        traitEl.style.alignItems = 'center';
        traitEl.style.gap = '4px';
        traitEl.style.padding = '2px 6px';
        traitEl.style.borderRadius = '4px';
        traitEl.style.background = isActive ? 'rgba(255,255,255,0.05)' : 'transparent';
        traitEl.style.border = '1px solid ' + (isActive ? 'var(--border-light)' : 'rgba(255,255,255,0.05)');
        
        // Highlight logic
        if (quizState.isAnswered) {
            const oldCount = quizState.baseCounts[trait] || 0;
            const oldReached = breakpoints.filter(b => b <= oldCount);
            const newReached = reached;
            
            if (newReached.length > oldReached.length || (count > oldCount && isActive)) {
                traitEl.style.borderColor = 'var(--success)';
                traitEl.style.background = 'rgba(50, 215, 75, 0.1)';
                traitEl.style.boxShadow = '0 0 8px rgba(50, 215, 75, 0.2)';
            }
        }

        const iconUrl = `assets/tft/${currentConfig.current_set}/traits/${trait.replace(/ /g, '')}.svg`;
        const filter = isActive ? '' : 'opacity: 0.3; filter: grayscale(1);';
        
        traitEl.innerHTML = `
            <img src="${iconUrl}" style="width: 14px; height: 14px; ${filter}" onerror="this.style.display='none'">
            <span style="font-size: 10px; color: ${isActive ? 'var(--text-bright)' : 'var(--text-dim)'}">${count}</span>
        `;
        container.appendChild(traitEl);
    });
}

function renderQuizBoard() {
    const container = document.getElementById('quiz-board-units');
    if (!container) return;
    container.innerHTML = '';
    
    quizState.currentBoard.forEach((u, uIdx) => {
        const slot = document.createElement('div');
        slot.className = 'quiz-unit-slot';
        
        const hiddenIdx = quizState.hiddenUnits.findIndex(hu => hu.name === u.name);
        const isHiddenUnit = hiddenIdx !== -1;
        const isAlreadyGuessed = isHiddenUnit && quizState.guessedUnits.some(gu => 
            quizState.validAlternativeUnits[hiddenIdx].some(alt => alt.name === gu)
        );
        const isHidden = isHiddenUnit && !quizState.isAnswered && !isAlreadyGuessed;
        
        if (isHidden) {
            slot.classList.add('hidden');
            slot.innerHTML = `
                <div class="quiz-unit-icon">?</div>
                <div class="quiz-unit-name">???</div>
            `;
        } else {
            const costColors = { 1: '#808080', 2: '#11b288', 3: '#207ac7', 4: '#c440da', 5: '#ffb93b' };
            const borderColor = costColors[u.cost] || '#ccc';
            
            // If it's a hidden slot that was revealed or guessed, show alternatives if any
            if (isHiddenUnit) {
                const alternatives = quizState.validAlternativeUnits[hiddenIdx];
                if (alternatives.length > 1) {
                    slot.innerHTML = `
                        <div style="position: relative;">
                            <img src="${u.icon_url}" class="quiz-unit-icon" style="border-color: ${borderColor}">
                            <div style="position: absolute; -1px; right: -1px; background: var(--accent); color: white; border-radius: 50%; width: 16px; height: 16px; font-size: 10px; display: flex; align-items: center; justify-content: center; font-weight: bold; border: 1px solid var(--bg);" title="Multiple valid options: ${alternatives.map(a => a.name).join(', ')}">${alternatives.length}</div>
                        </div>
                        <div class="quiz-unit-name">${u.name}*</div>
                    `;
                } else {
                    slot.innerHTML = `
                        <img src="${u.icon_url}" class="quiz-unit-icon" style="border-color: ${borderColor}">
                        <div class="quiz-unit-name">${u.name}</div>
                    `;
                }
            } else {
                slot.innerHTML = `
                    <img src="${u.icon_url}" class="quiz-unit-icon" style="border-color: ${borderColor}">
                    <div class="quiz-unit-name">${u.name}</div>
                `;
            }

            if (isAlreadyGuessed && !quizState.isAnswered) {
                slot.style.boxShadow = '0 0 10px var(--success)';
            }
        }
        container.appendChild(slot);
    });

    // Also show active emblems for the quiz
    if (quizState.activeEmblems && quizState.activeEmblems.length > 0) {
        quizState.activeEmblems.forEach(trait => {
            const slot = document.createElement('div');
            slot.className = 'quiz-unit-slot';
            const iconUrl = `assets/tft/${currentConfig.current_set}/traits/${trait.replace(/ /g, '')}.svg`;
            slot.innerHTML = `
                <img src="${iconUrl}" class="quiz-unit-icon" style="border-color: var(--accent); background: rgba(10,132,255,0.1); padding: 8px;">
                <div class="quiz-unit-name">${trait} Emb</div>
            `;
            container.appendChild(slot);
        });
    }
    
    renderQuizTraits();
}

function renderQuizOptions() {
    const container = document.getElementById('quiz-options');
    if (!container) return;
    container.innerHTML = '';
    
    // Show all units in the set sorted by cost then name (filtering out disabled)
    const sortedUnits = [...tftData.units]
        .filter(u => !activeDisabledUnits.includes(u.name))
        .sort((a, b) => a.cost - b.cost || a.name.localeCompare(b.name));
    
    sortedUnits.forEach(u => {
        const item = document.createElement('div');
        item.className = 'quiz-option-item';
        if (quizState.isAnswered || quizState.guessedUnits.includes(u.name)) item.classList.add('disabled');
        
        const costColors = { 1: '#808080', 2: '#11b288', 3: '#207ac7', 4: '#c440da', 5: '#ffb93b' };
        const borderColor = costColors[u.cost] || '#ccc';
        
        item.innerHTML = `
            <img src="${u.icon_url}" class="unit-icon" style="border-color: ${borderColor}; width: 44px; height: 44px;">
        `;
        
        item.onclick = () => handleQuizGuess(u.name);
        container.appendChild(item);
    });
}

function handleQuizGuess(unitName) {
    if (quizState.isAnswered) return;
    
    // Find which hidden slot this guess might correspond to
    let hiddenSlotIdx = -1;
    for (let i = 0; i < quizState.hiddenUnits.length; i++) {
        // Skip slots already correctly guessed
        const isAlreadyGuessed = quizState.guessedUnits.some(gu => 
            quizState.validAlternativeUnits[i].some(alt => alt.name === gu)
        );
        if (isAlreadyGuessed) continue;

        // Check if guess is in alternatives for this slot
        if (quizState.validAlternativeUnits[i].some(alt => alt.name === unitName)) {
            hiddenSlotIdx = i;
            break;
        }
    }
    
    const isCorrectChoice = hiddenSlotIdx !== -1;
    
    if (isCorrectChoice) {
        quizState.guessedUnits.push(unitName);
        
        if (quizState.guessedUnits.length === quizState.hiddenUnits.length) {
            // Fully Correct!
            if (quizState.timer) clearInterval(quizState.timer);
            quizState.isAnswered = true;
            
            // Base LP values per level
            const baseLPValues = { 4: 15, 5: 20, 6: 25, 7: 30, 8: 40, 9: 50, 10: 60 };
            let lpChange = baseLPValues[quizState.lastLevel] || 25;
            
            let multiplier = 1;
            if (quizState.difficulty === 'blitz') multiplier = 1.2;
            if (quizState.difficulty === 'hard') multiplier = 1.5;
            if (quizState.difficulty === 'zen') multiplier = 0.5;
            
            // 2-unit bonus
            if (quizState.hiddenUnits.length > 1) multiplier *= 1.5;

            const speedBonus = quizState.secondsLeft * 2;
            const gain = Math.floor((100 + (quizState.streak * 20) + speedBonus) * multiplier);
            quizState.score += gain;
            quizState.streak++;
            
            let lpGain = Math.floor(lpChange * multiplier);
            if (quizState.streak > 3) lpGain += 5; 
            
            updateRank(lpGain);
            gainXP(lpGain * 10);
            
            if (quizState.streak > quizState.bestStreak) {
                quizState.bestStreak = quizState.streak;
                localStorage.setItem('tft_persistent_quiz_best_streak', quizState.bestStreak);
            }
            if (quizState.score > quizState.highScore) {
                quizState.highScore = quizState.score;
                localStorage.setItem('tft_persistent_quiz_high_score', quizState.highScore);
            }
            
            showQuizFeedback(true);
            updateQuizStats();
            renderQuizBoard();
            renderQuizOptions();
            renderQuizTraits();
            document.getElementById('next-quiz-btn').style.display = 'block';
        } else {
            // Found one, but more to go
            showQuizFeedback(true, "Found One! Find the rest...");
            renderQuizBoard();
            renderQuizOptions();
            
            // Re-calculate traits hint for visible + newly guessed units
            const hiddenLeft = quizState.hiddenUnits.filter(u => !quizState.guessedUnits.includes(u.name));
            const hiddenLeftNames = hiddenLeft.map(u => u.name);
            const currentVisibleUnits = quizState.currentBoard.filter(u => !hiddenLeftNames.includes(u.name));
            quizState.baseCounts = calculateBoardCounts(currentVisibleUnits, quizState.activeEmblems);
            renderQuizTraits();
        }
    } else {
        // WRONG
        if (quizState.timer) clearInterval(quizState.timer);
        quizState.isAnswered = true;
        quizState.streak = 0;
        
        const baseLPValues = { 4: 15, 5: 20, 6: 25, 7: 30, 8: 40, 9: 50, 10: 60 };
        let lpChange = baseLPValues[quizState.lastLevel] || 25;
        
        let multiplier = 1;
        if (quizState.difficulty === 'zen') multiplier = 0.5;
        
        let lpLoss = Math.floor(lpChange * multiplier);
        updateRank(-lpLoss);
        
        showQuizFeedback(false);
        updateQuizStats();
        renderQuizBoard();
        renderQuizOptions();
        renderQuizTraits();
        document.getElementById('next-quiz-btn').style.display = 'block';
    }
}

function showQuizFeedback(correct, customMsg) {
    const el = document.getElementById('quiz-feedback');
    if (correct) {
        let msg = customMsg || "CORRECT!";
        if (!customMsg) {
            if (quizState.streak >= 10) msg = "LEGENDARY!!";
            else if (quizState.streak >= 7) msg = "UNSTOPPABLE!";
            else if (quizState.streak >= 5) msg = "GODLIKE!";
            else if (quizState.streak >= 3) msg = "GREAT!";
        }
        el.innerText = msg;
    } else {
        el.innerText = (quizState.secondsLeft <= 0 && !quizState.isAnswered) ? "TIMEOUT!" : "WRONG!";
    }
    el.className = `quiz-feedback show ${correct ? 'correct' : 'wrong'}`;
    
    setTimeout(() => {
        el.classList.remove('show');
    }, 1500);
}

let lastRankName = "IRON";
function updateQuizStats() {
    document.getElementById('quiz-score').innerText = quizState.score;
    document.getElementById('quiz-high-score').innerText = quizState.highScore;
    document.getElementById('quiz-streak').innerText = quizState.streak;
    document.getElementById('quiz-best-streak').innerText = quizState.bestStreak;
    
    // Update Level & XP
    const nextXP = getXPToNextLevel(quizState.level);
    const levelText = document.getElementById('quiz-level-text');
    const xpText = document.getElementById('quiz-xp-text');
    const xpBar = document.getElementById('quiz-xp-bar');
    
    if (levelText) levelText.innerText = `LVL ${quizState.level}`;
    if (xpText) xpText.innerText = `${quizState.xp} / ${nextXP} XP`;
    if (xpBar) xpBar.style.width = (quizState.xp / nextXP * 100) + '%';

    const rankName = RANKS[quizState.rankIndex];
    const divisionName = DIVISIONS[quizState.divisionIndex];
    const rankEl = document.getElementById('quiz-rank');
    
    const rankColors = {
        'IRON': '#a19d94',
        'BRONZE': '#cd7f32',
        'SILVER': '#c0c0c0',
        'GOLD': '#ffd700',
        'PLATINUM': '#32d74b',
        'EMERALD': '#2ecc71',
        'DIAMOND': '#00d9ff',
        'MASTER': '#bf5af2',
        'GRANDMASTER': '#ff453a',
        'CHALLENGER': '#64d2ff'
    };

    if (rankEl) {
        if (rankName !== lastRankName) {
            // Rank change animation
            rankEl.classList.remove('rank-changed');
            void rankEl.offsetWidth; // Force reflow
            rankEl.classList.add('rank-changed');
            lastRankName = rankName;
        }
        
        let rankText = rankName;
        if (quizState.rankIndex < 7) {
            rankText += ` ${divisionName}`;
        }
        rankText += ` ${quizState.lp} LP`;
        
        rankEl.innerText = rankText;
        rankEl.style.color = rankColors[rankName];
    }
    
    renderRankGraph();
}

function copyCardBoard(card, btn) {
    try {
        const board = JSON.parse(card.dataset.board);
        const code = TeamPlannerCode.encode(board);
        
        if (hubConnection && hubConnection.state === signalR.HubConnectionState.Connected) {
            hubConnection.invoke("UpdateClipboard", code);
            // Visual feedback
            if (btn) {
                const originalColor = btn.style.color;
                btn.style.color = 'var(--accent)';
                setTimeout(() => btn.style.color = originalColor, 1000);
            }
        } else {
            const tempInput = document.createElement('input');
            document.body.appendChild(tempInput);
            tempInput.value = code;
            tempInput.select();
            document.execCommand('copy');
            document.body.removeChild(tempInput);
            alert('Copied to local clipboard (Hub disconnected)');
        }
    } catch (err) {
        alert('Failed to copy: ' + err.message);
    }
}

function saveTFTSelection() {
    const data = {
        currentTeam: selectedCurrentTeam,
        mustInclude: selectedMustInclude,
        emblems: selectedEmblems
    };
    localStorage.setItem('tft_selection_state', JSON.stringify(data));
}

function loadTFTSelection() {
    const data = localStorage.getItem('tft_selection_state');
    if (data) {
        try {
            const parsed = JSON.parse(data);
            selectedCurrentTeam = parsed.currentTeam || [];
            selectedMustInclude = parsed.mustInclude || [];
            selectedEmblems = parsed.emblems || [];
            renderSelectionZones();
            renderEmblemPool();
        } catch(e) {
            console.error('Failed to load TFT state', e);
        }
    }
}

function getExclusionOverrides(board, emblems) {
    const specialTraits = ["Void", "Shadow Isles", "Bilgewater", "Zaun"];
    const overrides = [];
    
    const counts = {};
    board.forEach(u => u.traits.forEach(t => counts[t] = (counts[t] || 0) + 1));
    emblems.forEach(e => counts[e] = (counts[e] || 0) + 1);

    specialTraits.forEach(t => {
        const meta = tftData.trait_metadata[t];
        const isActive = meta && meta.breakpoints.some(b => b <= (counts[t] || 0));
        if (isActive) overrides.push(t);
    });
    return overrides;
}

function openTreeExplorer(btn) {
    const card = btn.closest('.result-card');
    const board = JSON.parse(card.dataset.board);
    document.querySelector('.optimizer-layout').classList.add('has-evolution');
    renderTreeExplorer(board);
}

function closeTreeExplorer() {
    document.querySelector('.optimizer-layout').classList.remove('has-evolution');
}

function getBestNeighbor(board, targetLevel, direction, emblems, mustIncludeTraits, mustIncludeNames, solverMode, persistentTraitRequirements, exclusionOverrides) {
    let bestBoard = null;
    let bestScore = -Infinity;

    const mustSet = new Set(mustIncludeNames);
    
    const costLimits = compRules?.general?.cost_level_limits || [];
    function isUnitAllowedAtLevel(unit, level) {
        if (mustSet.has(unit.name)) return true;
        
        // General Cost Limits
        for (const limit of costLimits) {
            if (level < limit.below_level && unit.cost > limit.max_cost) return false;
        }
        
        // Strict Level Enforcement (e.g. no 4-cost at level 5)
        if (level <= 5 && unit.cost >= 2) {
             if (compRules?.evolution?.new_unit_cost_offset !== undefined) {
                 const maxAllowed = level + compRules.evolution.new_unit_cost_offset;
                 if (unit.cost > maxAllowed) return false;
             }
        }
        if (level === 6 && unit.cost >= 4) return false;
        if (level === 7 && unit.cost >= 5) return false;

        return true;
    }

    const globalPool = tftData.units.filter(u => {
        const isExcluded = activeDisabledUnits.includes(u.name);
        if (isExcluded) {
            const hasOverride = u.traits.some(t => exclusionOverrides.includes(t));
            if (!hasOverride) return false;
        }
        return isUnitAllowedAtLevel(u, targetLevel);
    });

    function validateBoard(testBoard, level) {
        for (const u of testBoard) {
            if (!isUnitAllowedAtLevel(u, level)) return false;
        }

        if (compRules?.general?.unit_level_requirements) {
            for (const req of compRules.general.unit_level_requirements) {
                if (testBoard.some(u => u.name === req.name) && level < req.min_level) return false;
            }
        }

        if (persistentTraitRequirements) {
            const counts = {};
            testBoard.forEach(u => u.traits.forEach(t => counts[t] = (counts[t] || 0) + 1));
            emblems.forEach(e => counts[e] = (counts[e] || 0) + 1);
            for (const req of persistentTraitRequirements) {
                if ((counts[req.trait] || 0) < req.min_count) return false;
            }
        }

        const scoreObj = optimizer.scoreBoard(testBoard, emblems, level, solverMode, mustIncludeTraits, mustIncludeNames);
        return scoreObj.score > -1000000 ? scoreObj : null;
    }

    function optimizeBoard(startBoard, swapsRemaining) {
        const startResult = validateBoard(startBoard, targetLevel);
        let currentBest = { 
            board: startBoard, 
            score: startResult ? startResult.score : -Infinity
        };

        if (swapsRemaining <= 0) return currentBest;

        for (let i = 0; i < startBoard.length; i++) {
            const originalUnit = startBoard[i];
            if (mustSet.has(originalUnit.name)) continue;

            const currentBoardNames = new Set(startBoard.map(u => u.name));
            for (const candidate of globalPool) {
                if (currentBoardNames.has(candidate.name)) continue;
                const testBoard = [...startBoard];
                testBoard[i] = candidate;
                const result = validateBoard(testBoard, targetLevel);
                if (result && result.score > currentBest.score) {
                    currentBest = { board: testBoard, score: result.score };
                    const further = optimizeBoard(testBoard, swapsRemaining - 1);
                    if (further.score > currentBest.score) currentBest = further;
                }
            }
        }
        return currentBest;
    }

    if (direction === 'down') {
        for (let i = 0; i < board.length; i++) {
            const subset = [...board];
            subset.splice(i, 1);
            if (subset.length !== targetLevel) continue;
            const optimized = optimizeBoard(subset, compRules?.evolution?.max_swaps_down || 2);
            if (optimized.score > bestScore) {
                bestScore = optimized.score;
                bestBoard = optimized.board;
            }
        }
    } else {
        const currentBoardNames = new Set(board.map(u => u.name));
        for (const unit of globalPool) {
            if (currentBoardNames.has(unit.name)) continue;
            const expanded = [...board, unit];
            const optimized = optimizeBoard(expanded, compRules?.evolution?.max_swaps_up || 2);
            if (optimized.score > bestScore) {
                bestScore = optimized.score;
                bestBoard = optimized.board;
            }
        }
    }

    if (!bestBoard) return null;
    const finalResult = validateBoard(bestBoard, targetLevel);
    return finalResult ? { board: bestBoard, score: finalResult.score, counts: finalResult.counts } : null;
}

async function renderTreeExplorer(baseBoard) {
    const content = document.getElementById('tree-explorer-content');
    content.innerHTML = '<div style="color: var(--text-dim); padding: 20px; text-align: center; font-size: 11px;">Calculating evolution...</div>';

    await new Promise(r => setTimeout(r, 10));

    const currentLevel = baseBoard.reduce((acc, u) => acc + (u.slots || 1), 0);
    const emblems = selectedEmblems.map(e => e.trait);
    const mustIncludeNames = selectedMustInclude.filter(i => i.type === 'unit').map(u => u.name);
    const mustIncludeTraits = {};
    selectedMustInclude.filter(i => i.type === 'emblem').forEach(item => {
        const meta = tftData.trait_metadata[item.trait];
        if (meta) {
            if (item.targetBreakpointIndex === -1) {
                mustIncludeTraits[item.trait] = meta.breakpoints[0];
            } else {
                mustIncludeTraits[item.trait] = meta.breakpoints[item.targetBreakpointIndex];
            }
        }
    });
    
    const solverModeEl = document.querySelector('input[name="solver-mode"]:checked');
    const solverMode = solverModeEl ? solverModeEl.value : 'default';

    const baseCounts = {};
    baseBoard.forEach(u => u.traits.forEach(t => baseCounts[t] = (baseCounts[t] || 0) + 1));
    emblems.forEach(e => baseCounts[e] = (baseCounts[e] || 0) + 1);
    
    const exclusionOverrides = getExclusionOverrides(baseBoard, emblems);

    const persistentTraits = [];
    if (compRules?.evolution?.persistent_traits) {
        for (const req of compRules.evolution.persistent_traits) {
            const meta = tftData.trait_metadata[req.trait];
            const isActive = meta && meta.breakpoints.some(b => b <= (baseCounts[req.trait] || 0));
            if (isActive && baseCounts[req.trait] >= req.min_count) {
                persistentTraits.push(req);
            }
        }
    }

    const tree = {};
    const baseScoreObj = optimizer.scoreBoard(baseBoard, emblems, currentLevel, solverMode, mustIncludeTraits, mustIncludeNames);
    tree[currentLevel] = { board: baseBoard, score: baseScoreObj.score, counts: baseScoreObj.counts };

    let lastBoard = baseBoard;
    for (let l = currentLevel - 1; l >= 4; l--) {
        const best = getBestNeighbor(lastBoard, l, 'down', emblems, mustIncludeTraits, mustIncludeNames, solverMode, persistentTraits, exclusionOverrides);
        if (best) {
            tree[l] = best;
            lastBoard = best.board;
        }
    }

    lastBoard = baseBoard;
    for (let l = currentLevel + 1; l <= 9; l++) {
        const best = getBestNeighbor(lastBoard, l, 'up', emblems, mustIncludeTraits, mustIncludeNames, solverMode, persistentTraits, exclusionOverrides);
        if (best) {
            tree[l] = best;
            lastBoard = best.board;
        }
    }

    content.innerHTML = '';
    const sortedLevels = Object.keys(tree).map(Number).sort((a, b) => a - b);

    sortedLevels.forEach(l => {
        const cardContainer = document.createElement('div');
        cardContainer.style.width = '100%';
        renderSingleResult(tree[l], cardContainer, l, solverMode, l === currentLevel);
        content.appendChild(cardContainer);

        if (l < sortedLevels[sortedLevels.length - 1]) {
            const arrow = document.createElement('div');
            arrow.innerHTML = '<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="4" stroke-linecap="round" stroke-linejoin="round" style="opacity: 0.15;"><path d="M7 13l5 5 5-5"/></svg>';
            arrow.style.color = 'var(--text-dimmer)';
            arrow.style.margin = '1px 0';
            content.appendChild(arrow);
        }
    });
}

function renderSingleResult(res, container, level, solverMode, isHighlighted = false) {
    const card = document.createElement('div');
    card.className = 'result-card';
    card.style.padding = '4px 8px';
    card.style.marginBottom = '0';
    card.style.position = 'relative';
    card.style.display = 'flex';
    card.style.flexDirection = 'column';
    card.style.gap = '2px';

    if (isHighlighted) {
        card.style.borderColor = 'var(--accent)';
        card.style.boxShadow = '0 0 10px rgba(10, 132, 255, 0.1)';
        card.style.background = 'rgba(255,255,255,0.03)';
    }
    
    card.dataset.board = JSON.stringify(res.board);

    // Header Row with Level and Score
    const header = document.createElement('div');
    header.style.display = 'flex';
    header.style.justifyContent = 'space-between';
    header.style.alignItems = 'center';
    header.innerHTML = `
        <div style="display: flex; align-items: center; gap: 4px;">
            <div style="font-size: 7px; font-weight: 900; color: ${isHighlighted ? 'white' : 'var(--text-dimmer)'}; background: ${isHighlighted ? 'var(--accent)' : 'rgba(255,255,255,0.05)'}; padding: 0px 3px; border-radius: 2px; border: 1px solid ${isHighlighted ? 'var(--accent)' : 'var(--border)'};">L${level}</div>
            <button class="icon-btn" onclick="copyCardBoard(this.closest('.result-card'), this)" title="Copy Team Code" style="background: none; border: none; cursor: pointer; padding: 1px; display: flex; align-items: center; color: var(--text-dimer); transition: color 0.2s;">
                <svg xmlns="http://www.w3.org/2000/svg" width="8" height="8" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
            </button>
        </div>
        <span style="font-size: 8px; color: var(--text-dimmer); font-weight: 600;">${Math.floor(res.score)}</span>
    `;
    card.appendChild(header);
    
    const list = document.createElement('div');
    list.className = 'unit-list';
    list.style.marginTop = '0';
    list.style.gap = '2px';
    const sortedBoard = [...res.board].sort((a, b) => a.cost - b.cost || a.name.localeCompare(b.name));
    sortedBoard.forEach((u) => {
        const unitItem = document.createElement('div');
        unitItem.className = 'unit-item';
        unitItem.style.width = '28px';
        const costColors = { 1: '#808080', 2: '#11b288', 3: '#207ac7', 4: '#c440da', 5: '#ffb93b' };
        const borderColor = costColors[u.cost] || '#ccc';
        unitItem.innerHTML = `<img src="${u.icon_url}" class="unit-icon" style="width: 24px; height: 24px; border-color: ${borderColor};" onerror="this.src='data:image/svg+xml;base64,PHN2ZyB4bWxucz0naHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmcnIHdpZHRoPSczNicgaGVpZ2h0PSczNicgc3R5bGU9J2JhY2tncm91bmQ6IzIyMjsnPjx0ZXh0IHg9JzUwJScgeT0nNTAlJyBkb20tYmFzZWxpbmU9J21pZGRsZScgdGV4dC1hbmNob3I9J21pZGRsZScgZmlsbD0nI2ZmZicgZm9udC1zaXplPSc4Jz4/IDwvdGV4dD48L3N2Zz4='">
            <div class="unit-name" style="font-size: 5px;">${u.name}</div>`;
        list.appendChild(unitItem);
    });
    card.appendChild(list);

    const traitsList = document.createElement('div');
    traitsList.className = 'trait-summary';
    traitsList.style.display = 'flex';
    traitsList.style.flexWrap = 'wrap';
    traitsList.style.gap = '3px';
    traitsList.style.marginTop = '2px';
    traitsList.style.paddingTop = '2px';
    traitsList.style.borderTop = '1px solid var(--border-light)';
    
    const sortedTraits = Object.entries(res.counts).sort((a, b) => b[1] - a[1]);
    sortedTraits.forEach(([trait, count]) => {
        const traitInfo = (tftData.trait_metadata && tftData.trait_metadata[trait]) ? tftData.trait_metadata[trait] : null;
        const breakpoints = traitInfo ? traitInfo.breakpoints : null;
        const isActive = (breakpoints && breakpoints.some(b => b <= count)) || (trait === 'Targon' && count >= 1);
        if (!isActive && trait !== 'Targon') return;
        const traitItem = document.createElement('div');
        traitItem.style.display = 'flex';
        traitItem.style.alignItems = 'center';
        traitItem.style.gap = '1px';
        traitItem.style.fontSize = '7px';
        const iconUrl = `assets/tft/${currentConfig.current_set}/traits/${trait.replace(/ /g, '')}.svg`;
        traitItem.innerHTML = `<img src="${iconUrl}" style="width: 8px; height: 8px;" title="${trait}" onerror="this.style.display='none'"><span>${count}</span>`;
        traitsList.appendChild(traitItem);
    });
    card.appendChild(traitsList);
    container.appendChild(card);
}

function renderSingleResult(res, container, level, solverMode, isHighlighted = false) {
    const emblemTraits = selectedEmblems.map(e => e.trait);
    const card = document.createElement('div');
    card.className = 'result-card';
    card.style.padding = '8px 12px';
    card.style.marginBottom = '0';
    if (isHighlighted) {
        card.style.borderColor = 'var(--accent)';
        card.style.boxShadow = '0 0 15px rgba(10, 132, 255, 0.1)';
        card.style.background = 'rgba(255,255,255,0.03)';
    }
    
    // Store board for copy support
    card.dataset.board = JSON.stringify(res.board);

    card.innerHTML = `<div style="display: flex; justify-content: space-between; margin-bottom: 4px; align-items: center;">
            <div style="display: flex; align-items: center; gap: 8px;">
                <button class="icon-btn" onclick="copyCardBoard(this.closest('.result-card'), this)" title="Copy Team Code" style="background: none; border: none; cursor: pointer; padding: 2px; display: flex; align-items: center; color: var(--text-dimer); transition: color 0.2s;">
                    <svg xmlns="http://www.w3.org/2000/svg" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
                </button>
                <span style="font-size: 10px; color: var(--text-dim); font-weight: 600;">Score: ${Math.floor(res.score)}</span>
            </div>
        </div>`;
    
    const list = document.createElement('div');
    list.className = 'unit-list';
    list.style.marginTop = '0';
    list.style.gap = '4px';
    const sortedBoard = [...res.board].sort((a, b) => a.cost - b.cost || a.name.localeCompare(b.name));
    sortedBoard.forEach((u) => {
        const unitItem = document.createElement('div');
        unitItem.className = 'unit-item';
        unitItem.style.width = '36px';
        const costColors = { 1: '#808080', 2: '#11b288', 3: '#207ac7', 4: '#c440da', 5: '#ffb93b' };
        const borderColor = costColors[u.cost] || '#ccc';
        unitItem.innerHTML = `<img src="${u.icon_url}" class="unit-icon" style="width: 32px; height: 32px; border-color: ${borderColor};" onerror="this.src='data:image/svg+xml;base64,PHN2ZyB4bWxucz0naHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmcnIHdpZHRoPSczNicgaGVpZ2h0PSczNicgc3R5bGU9J2JhY2tncm91bmQ6IzIyMjsnPjx0ZXh0IHg9JzUwJScgeT0nNTAlJyBkb20tYmFzZWxpbmU9J21pZGRsZScgdGV4dC1hbmNob3I9J21pZGRsZScgZmlsbD0nI2ZmZicgZm9udC1zaXplPSc4Jz4/IDwvdGV4dD48L3N2Zz4='">
            <div class="unit-name" style="font-size: 7px;">${u.name}</div>`;
        list.appendChild(unitItem);
    });
    card.appendChild(list);

    const traitsList = document.createElement('div');
    traitsList.className = 'trait-summary';
    traitsList.style.display = 'flex';
    traitsList.style.flexWrap = 'wrap';
    traitsList.style.gap = '6px';
    traitsList.style.marginTop = '6px';
    traitsList.style.paddingTop = '4px';
    traitsList.style.borderTop = '1px solid var(--border-light)';
    
    const sortedTraits = Object.entries(res.counts).sort((a, b) => b[1] - a[1]);
    sortedTraits.forEach(([trait, count]) => {
        const traitInfo = (tftData.trait_metadata && tftData.trait_metadata[trait]) ? tftData.trait_metadata[trait] : null;
        const breakpoints = traitInfo ? traitInfo.breakpoints : null;
        const isActive = (breakpoints && breakpoints.some(b => b <= count)) || (trait === 'Targon' && count >= 1);
        
        if (!isActive && trait !== 'Targon') return;

        const traitItem = document.createElement('div');
        traitItem.style.display = 'flex';
        traitItem.style.alignItems = 'center';
        traitItem.style.gap = '2px';
        traitItem.style.fontSize = '9px';
        
        const iconUrl = `assets/tft/${currentConfig.current_set}/traits/${trait.replace(/ /g, '')}.svg`;
        traitItem.innerHTML = `<img src="${iconUrl}" style="width: 12px; height: 12px;" title="${trait}" onerror="this.style.display='none'"><span>${count}</span>`;
        traitsList.appendChild(traitItem);
    });
    card.appendChild(traitsList);
    container.appendChild(card);
}