let tftData = null;
let currentConfig = null;
let optimizer = null;

let selectedMustInclude = []; 
let selectedCurrentTeam = [];
let selectedEmblems = [];
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

async function initHubConnection() {
    hubConnection = new signalR.HubConnectionBuilder()
        .withUrl(HUB_URL)
        .withAutomaticReconnect()
        .build();

    try {
        await hubConnection.start();
        console.log("TFT Hub Connected");
        
        hubConnection.on("ClipboardUpdated", (text) => {
            lastReceivedClipboard = text;
        });

        await hubConnection.invoke("Authenticate", API_KEY);
    } catch (err) {
        console.error("TFT Hub Connection Error:", err);
        setTimeout(initHubConnection, 5000);
    }
}

function switchTab(tabId) {
    document.querySelectorAll('.tft-tab').forEach(tab => {
        tab.classList.remove('active');
        const text = tab.innerText.toLowerCase().replace(/ /g, '-');
        if (text === tabId || 
            (tabId === 'emblem-portal' && tab.innerText === 'Emblem Portal') ||
            (tabId === 'director' && tab.innerText === 'Director') ||
            (tabId === 'config' && tab.innerText === 'Configuration')) {
            tab.classList.add('active');
        }
    });

    const tabMap = {
        'Emblem Portal': 'emblem-portal',
        'Director': 'director',
        'Configuration': 'config'
    };

    document.querySelectorAll('.tab-panel').forEach(panel => {
        panel.classList.remove('active');
    });
    
    const targetId = tabMap[tabId] || tabId;
    const el = document.getElementById(targetId);
    if (el) el.classList.add('active');
}

async function loadTFTData() {
    try {
        const configResp = await fetch('assets/tft/data/set_config.json');
        currentConfig = await configResp.json();
        
        const dataResp = await fetch(`assets/tft/data/${currentConfig.current_set}.json`);
        tftData = await dataResp.json();

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
        renderSavedComps();
        initHubConnection();
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

    for (let cost = 1; cost <= 5; cost++) {
        const pool = document.getElementById(`unit-pool-${cost}`);
        if (!pool) continue;
        pool.innerHTML = '';
        
        const units = tftData.units.filter(u => {
            if (u.cost !== cost || u.name === "Tibbers") return false;
            if (unitAlphaFilter && !u.name.toUpperCase().startsWith(unitAlphaFilter)) return false;
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
            const item = createDraggableItem(u.name, u.icon_url, 'unit', u.cost);
            item.classList.add('unit-node');
            item.dataset.traits = JSON.stringify(u.traits);

            if (activeDisabledUnits.includes(u.name)) {
                item.style.opacity = '0.3';
                item.style.filter = 'grayscale(1)';
            }
            
            item.addEventListener('click', (e) => {
                if (!activeDisabledUnits.includes(u.name)) {
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
    
    originPool.innerHTML = '';
    classPool.innerHTML = '';
    
    const emblems = tftData.items.filter(i => {
        if (!i.is_emblem) return false;
        if (!emblemSearch) return true;
        return i.name.toLowerCase().includes(emblemSearch.toLowerCase());
    }).sort((a, b) => a.name.localeCompare(b.name));

    emblems.forEach(e => {
        const displayName = e.name.replace(" Emblem", "");
        const item = createDraggableItem(displayName, e.icon_url, 'emblem', null, e.trait);
        
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

function createDraggableItem(name, iconUrl, type, cost, trait, isSelected = false, breakpointIdx = 0, isMustInclude = false, zoneId = null) {
    const div = document.createElement('div');
    div.className = 'draggable-item';
    div.draggable = true; 
    div.dataset.name = name;
    div.dataset.type = type;
    if (cost) div.dataset.cost = cost;
    if (trait) div.dataset.trait = trait;
    div.dataset.icon = iconUrl;
    div.dataset.selected = isSelected;

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
        </div>
        <div class="item-name">${name}</div>
    `;

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

function renderSelectionZones() {
    const mustZone = document.getElementById('must-include-zone');
    const currentZone = document.getElementById('current-team-zone');
    const emblemZone = document.getElementById('emblem-drop-zone');
    if (!mustZone || !currentZone || !emblemZone) return;

    currentZone.innerHTML = '';
    if (selectedCurrentTeam.length > 0) {
        selectedCurrentTeam.forEach(u => {
            const el = createDraggableItem(u.name, u.iconUrl, 'unit', u.cost, null, true, 0, false, 'current-team');
            currentZone.appendChild(el);
        });
    } else {
        currentZone.innerHTML = '<div class="placeholder-text">Units Only</div>';
    }

    mustZone.innerHTML = '';
    if (selectedMustInclude.length > 0) {
        selectedMustInclude.forEach(item => {
            const el = createDraggableItem(item.name, item.iconUrl, item.type, item.cost, item.trait, true, item.hasOwnProperty('targetBreakpointIndex') ? item.targetBreakpointIndex : 0, true, 'must-include');
            mustZone.appendChild(el);
        });
    } else {
        mustZone.innerHTML = '<div class="placeholder-text">Units or Traits</div>';
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
    const defHeuristic = document.querySelector('input[name="heuristic-mode"][value="standard"]');
    if (defHeuristic) defHeuristic.checked = true;
    document.querySelectorAll('.lvl-cb').forEach(cb => {
        cb.checked = (cb.value === "6" || cb.value === "8");
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
            const pool = tftData.units.filter(u => {
                if (activeDisabledUnits.includes(u.name)) return false;
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

                const pool = tftData.units.filter(u => {
                    if (activeDisabledUnits.includes(u.name)) return false;
                    if (mustIncludeNames.includes(u.name)) return true;
                    if (excludeFiveCosts && u.cost === 5) return false;
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

function renderResults(results, container, level) {
    if (results.length === 0) {
        container.innerHTML = '<div style="color: var(--text-dim); font-size: 11px;">No valid compositions found.</div>';
        return;
    }
    const emblemTraits = selectedEmblems.map(e => e.trait);
    const solverModeEl = document.querySelector('input[name="solver-mode"]:checked');
    const solverMode = solverModeEl ? solverModeEl.value : 'default';

    results.forEach((res, index) => {
        const card = document.createElement('div');
        card.className = 'result-card';
        card.style.marginBottom = '10px';
        let displayBoard = [...res.board];
        if (displayBoard.find(u => u.name === "Annie") && !displayBoard.find(u => u.name === "Tibbers")) {
            const tibbers = tftData.units.find(u => u.name === "Tibbers");
            if (tibbers) displayBoard.push(tibbers);
        }
        
        // Data-driven auto-include (e.g. Neeko auto-includes Nidalee if Ixtal is active)
        displayBoard.forEach(u => {
            if (u.auto_include && !displayBoard.find(du => du.name === u.auto_include)) {
                // Check if the trait shared by these two is actually active
                const sharedTrait = u.traits[0]; // Heuristic: first trait is usually the origin
                const hasTraitActive = res.counts[sharedTrait] && (tftData.trait_metadata[sharedTrait]?.breakpoints.some(b => b <= res.counts[sharedTrait]));
                
                if (hasTraitActive) {
                    const extraUnit = tftData.units.find(du => du.name === u.auto_include);
                    if (extraUnit) displayBoard.push(extraUnit);
                }
            }
        });

        let bronzeInfo = "";
        if (solverMode === 'bronze-for-life') {
            const activeCount = Object.keys(res.counts).filter(t => {
                if (t === 'Targon') return false; // Exclude Targon from active count in Bronze mode
                const traitInfo = tftData.trait_metadata[t];
                return traitInfo && traitInfo.breakpoints.some(b => b <= res.counts[t]);
            }).length;
            bronzeInfo = `<span style="font-size: 10px; color: var(--text-dim); margin-left: 8px;">(${activeCount} active)</span>`;
        }

        card.innerHTML = `<div style="display: flex; justify-content: space-between; margin-bottom: 8px;">
                <div style="display: flex; align-items: center; gap: 8px;">
                    <strong>Option ${index + 1} ${bronzeInfo}</strong>
                    <button class="icon-btn" onclick="copyResultCode(this)" title="Copy Team Code" style="background: none; border: none; cursor: pointer; padding: 2px; display: flex; align-items: center; color: var(--text-dim); transition: color 0.2s;">
                        <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
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
        sortedBoard.forEach((u, uIdx) => {
            const activeTraits = Object.keys(res.counts).filter(t => (tftData.trait_metadata && tftData.trait_metadata[t] && tftData.trait_metadata[t].breakpoints.some(b => b <= res.counts[t])));
            const contributedTraits = u.traits.filter(t => activeTraits.includes(t));
            const isFlex = contributedTraits.length === 1;
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
                        opt.onclick = (e) => { e.stopPropagation(); res.board[uIdx] = rep; renderResults(results, container, level); };
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
        const traitsList = document.createElement('div');
        traitsList.className = 'trait-summary';
        traitsList.style.display = 'flex';
        traitsList.style.flexWrap = 'wrap';
        traitsList.style.gap = '10px';
        traitsList.style.marginTop = '10px';
        traitsList.style.paddingTop = '8px';
        traitsList.style.borderTop = '1px solid var(--border-light)';
        const sortedTraits = Object.entries(res.counts).sort((a, b) => b[1] - a[1]);
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
            traitItem.style.gap = '4px';
            traitItem.style.fontSize = '11px';
            let textColor = 'var(--text-dim)';
            let filter = isActive ? '' : 'opacity: 0.5; filter: grayscale(1);';
            
            const greenFilter = 'invert(48%) sepia(79%) saturate(2476%) hue-rotate(86deg) brightness(118%) contrast(119%)';
            const orangeFilter = 'invert(65%) sepia(91%) saturate(1831%) hue-rotate(3deg) brightness(103%) contrast(105%)';

            if (solverMode === 'world-runes' || solverMode === 'ryze-unlock') {
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
            traitItem.innerHTML = `<img src="${iconUrl}" style="width: 16px; height: 16px; filter: ${filter}" title="${trait}" onerror="this.style.display='none'"><span title="${trait}">${count}</span>`;
            traitsList.appendChild(traitItem);
        });
        card.appendChild(traitsList);
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
                <button class="icon-btn" onclick="copyImprovementCode(this)" title="Copy Improved Team Code" style="background: none; border: none; cursor: pointer; padding: 2px; display: flex; align-items: center; color: var(--text-dim); transition: color 0.2s;">
                    <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
                </button>
            </div>`;
        
        // Store the best candidate board
        card.dataset.board = JSON.stringify(bestCandidate.board);
        
        const list = document.createElement('div');
        list.className = 'unit-list';
        list.style.gap = '4px';
        const sortedBoard = [...bestCandidate.board].sort((a, b) => a.cost - b.cost || a.name.localeCompare(b.name));
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
        const allTraitNames = new Set([...Object.keys(currentCounts), ...Object.keys(bestCandidate.counts)]);
                Array.from(allTraitNames).sort().forEach(trait => {
                    const count = bestCandidate.counts[trait] || 0;
                    const prevCount = currentCounts[trait] || 0;
                    const traitInfo = (tftData.trait_metadata && tftData.trait_metadata[trait]) ? tftData.trait_metadata[trait] : null;
                    const breakpoints = traitInfo ? traitInfo.breakpoints : null;
                    const isTargon = trait === 'Targon';
                    const isOrigin = traitInfo && traitInfo.type === 'origin';
                    const hasEmblem = emblemTraits.includes(trait);
                    
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
        runner.testLevel4Optimal, runner.testAnnieTibbersLogic, runner.testLevel4CostConstraint,
        runner.testLevel5CostConstraint, runner.testBronzeForLifeLogic, runner.testLevel10Constraints,
        runner.testSpecificLevelUnitRestrictions, runner.testMustIncludeTraits, runner.testAnnieOneArcanistFix,
        runner.testTargonSpecialLogic, runner.testUnitReplacementPersistenceBug, runner.testSylasForbiddenUnits,
        runner.testLevel8FiveCostLimit, runner.testLevel9Constraints, runner.testTraitIgnoreList,
        runner.testForbiddenShurima, runner.testCarryRequirements, runner.testMustIncludeBypassLevelRestriction,
        runner.testNeekoNidaleeLogic, runner.testNeekoNidaleeOneWayLogic, runner.testNidaleeAutoIncludeBug,
        runner.testNidaleeRequiresNeeko, runner.testSuperHeuristicPoppyLevel6, runner.testSuperHeuristicKobukoLevel6,
        runner.testWorldRunesLogic, runner.testRuneSolverLevel5, runner.testRuneSolverLevel5PiltoverDemacia,
        runner.testRyzeUnlockSolver, runner.testSaveLoadComps, runner.testFullLoadFlow
    ];
    for (const test of tests) {
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
        
        // Switch to Emblem Portal tab to see the result
        switchTab('emblem-portal');
        
        alert(`Successfully imported ${selectedCurrentTeam.length} units.`);
    } catch (err) {
        alert('Failed to import code: ' + err.message);
    }
}

function copyResultCode(index) {
    const cards = document.querySelectorAll('.result-card');
    if (cards[index]) {
        try {
            const board = JSON.parse(cards[index].dataset.board);
            const code = TeamPlannerCode.encode(board);
            
            if (hubConnection && hubConnection.state === signalR.HubConnectionState.Connected) {
                hubConnection.invoke("UpdateClipboard", code);
                // Visual feedback
                const btn = cards[index].querySelector('.icon-btn');
                const originalColor = btn.style.color;
                btn.style.color = 'var(--accent)';
                setTimeout(() => btn.style.color = originalColor, 1000);
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

function copyResultCode(btn) {
    const card = btn.closest('.result-card');
    if (card) {
        copyCardBoard(card, btn);
    }
}

function copyImprovementCode(btn) {
    const card = btn.closest('.result-card');
    if (card) {
        copyCardBoard(card, btn);
    }
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