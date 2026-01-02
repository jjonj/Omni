let tftData = null;
let currentConfig = null;
let optimizer = null;

let selectedMustInclude = []; 
let selectedCurrentTeam = [];
let selectedEmblems = [];
let unitFilter = 'all';
let emblemSearch = '';
let highlightedTrait = null;

const FACTORY_DEFAULT_DISABLED = [
    "Yorick", "Gwen", "LeBlanc", "Fizz", "Kalista", 
    "Nasus", "Xerath", "Singed", "Veigar", "Warwick", 
    "Aatrox", "Sett", "Tahm Kench", "Thresh"
];

let userDefaultDisabledUnits = []; 
let activeDisabledUnits = [];      

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
        
        const stored = localStorage.getItem('tft_user_defaults_disabled');
        if (stored) {
            userDefaultDisabledUnits = JSON.parse(stored);
        } else {
            userDefaultDisabledUnits = [...FACTORY_DEFAULT_DISABLED];
        }
        
        activeDisabledUnits = [...userDefaultDisabledUnits];
        
        const configTextarea = document.getElementById('disabled-units-config');
        if (configTextarea) configTextarea.value = userDefaultDisabledUnits.join(', ');

        optimizer = new TFTOptimizer(tftData.units, tftData.trait_metadata);
        updateUI();
    } catch (err) {
        console.error("Failed to load TFT data:", err);
    }
}

function updateUI() {
    if (!tftData) return;

    const setNameEl = document.getElementById('config-set-name');
    if (setNameEl) setNameEl.innerText = tftData.set_name;
    const jsonDisplayEl = document.getElementById('config-json-display');
    if (jsonDisplayEl) jsonDisplayEl.innerText = JSON.stringify(tftData, null, 2);

    renderUnitPools();
    renderEmblemPool();
    renderSelectionZones();
}

function renderUnitPools() {
    for (let cost = 1; cost <= 5; cost++) {
        const pool = document.getElementById(`unit-pool-${cost}`);
        if (!pool) continue;
        pool.innerHTML = '';
        
        const units = tftData.units.filter(u => u.cost === cost && u.name !== "Tibbers")
            .sort((a, b) => {
                const aDisabled = activeDisabledUnits.includes(a.name);
                const bDisabled = activeDisabledUnits.includes(b.name);
                if (aDisabled !== bDisabled) return aDisabled - bDisabled;
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
        userDefaultDisabledUnits = [...FACTORY_DEFAULT_DISABLED];
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
    
    activeDisabledUnits = [...userDefaultDisabledUnits];
    renderUnitPools();

    const resultsContainer = document.getElementById('results-container');
    if (resultsContainer) resultsContainer.innerHTML = '';
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
    const selectedLevels = Array.from(document.querySelectorAll('.lvl-cb:checked')).map(cb => parseInt(cb.value));
    const limit = parseInt(document.getElementById('results-limit').value);
    const excludeFiveCosts = document.getElementById('exclude-five-costs').checked;
    
    const resultsContainer = document.getElementById('results-container');
    resultsContainer.innerHTML = '<div style="color: var(--text-dim); padding: 20px;">Initializing...</div>';

    const progressContainer = document.getElementById('optimizer-progress-container');
    const progressBar = document.getElementById('progress-bar');
    const progressPercent = document.getElementById('progress-percent');
    const progressLabel = document.getElementById('progress-label');

    runBtn.style.display = 'none';
    cancelBtn.style.display = 'block';
    progressContainer.style.display = 'block';
    
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

            const suggestionsResult = optimizer.improveTeam(currentBoardUnits, pool, emblems, solverMode, mustIncludeTraits, mustIncludeNames, limit);
            renderImproveResults(suggestionsResult.suggestions, levelList, suggestionsResult.currentCounts);
        } else {
            for (const level of selectedLevels) {
                if (optimizer.isCancelled) break;
                progressLabel.innerText = `Level ${level}: Calculating...`;
                const levelHeader = document.createElement('h2');
                levelHeader.className = 'hub-status-label';
                levelHeader.style.display = 'block';
                levelHeader.style.margin = '20px 0 10px 0';
                levelHeader.innerText = `Level ${level} Results`;
                resultsContainer.appendChild(levelHeader);

                const levelList = document.createElement('div');
                levelList.className = 'results-list';
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
                
                const results = await optimizer.findBestBoards(pool, level, emblems, mustIncludeNames, solverMode, mustIncludeTraits, limit, (proc, tot) => {
                    const pct = Math.min(100, Math.floor((proc / tot) * 100));
                    progressBar.style.width = `${pct}%`;
                    progressPercent.innerText = `${pct}%`;
                });
                renderResults(results, levelList, level);
            }
        }
    } finally {
        runBtn.style.display = 'block';
        cancelBtn.style.display = 'none';
        progressContainer.style.display = 'none';
        if (resultsContainer.firstChild && resultsContainer.firstChild.innerText === 'Initializing...') {
            resultsContainer.removeChild(resultsContainer.firstChild);
        }
    }
}

function renderResults(results, container, level) {
    container.innerHTML = '';
    if (results.length === 0) {
        container.innerHTML = '<div style="color: var(--text-dim); font-size: 11px;">No valid compositions found.</div>';
        return;
    }
    const emblemTraits = selectedEmblems.map(e => e.trait);
    results.forEach((res, index) => {
        const card = document.createElement('div');
        card.className = 'result-card';
        card.style.marginBottom = '10px';
        let displayBoard = [...res.board];
        if (displayBoard.find(u => u.name === "Annie") && !displayBoard.find(u => u.name === "Tibbers")) {
            const tibbers = tftData.units.find(u => u.name === "Tibbers");
            if (tibbers) displayBoard.push(tibbers);
        }
        card.innerHTML = `<div style="display: flex; justify-content: space-between; margin-bottom: 8px;">
                <strong>Option ${index + 1}</strong>
                <span style="color: var(--accent); font-weight: 600;">${res.score}</span>
            </div>`;
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
                    
                    if (!breakpoints && !isTargon) return;
            const traitItem = document.createElement('div');
            traitItem.style.display = 'flex';
            traitItem.style.alignItems = 'center';
            traitItem.style.gap = '4px';
            traitItem.style.fontSize = '11px';
            let textColor = 'var(--text-dim)';
            let filter = isActive ? '' : 'opacity: 0.5; filter: grayscale(1);';
            if (hasEmblem) {
                if (isActive) { textColor = '#32d74b'; filter = 'invert(48%) sepia(79%) saturate(2476%) hue-rotate(86deg) brightness(118%) contrast(119%)'; }
                else { textColor = '#ff9500'; filter = 'invert(65%) sepia(91%) saturate(1831%) hue-rotate(3deg) brightness(103%) contrast(105%)'; }
            } else if (isActive) { textColor = 'var(--text-bright)'; }
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
    suggestions.forEach((group, index) => {
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
            </div>`;
        const bestCandidate = group.candidates[0];
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
                    
                    if (!breakpoints && !isTargon) return;
            const getTier = (c) => { if (!breakpoints) return 0; const reached = breakpoints.filter(b => b <= c); return reached.length > 0 ? Math.max(...reached) : 0; };
            const oldTier = getTier(prevCount);
            const newTier = getTier(count);
            const isActive = newTier > 0 || isTargon;
            const greenFilter = 'invert(48%) sepia(79%) saturate(2476%) hue-rotate(86deg) brightness(118%) contrast(119%)';
            const redFilter = 'invert(27%) sepia(91%) saturate(7484%) hue-rotate(351deg) brightness(101%) contrast(114%)';
            const yellowFilter = 'invert(85%) sepia(61%) saturate(1000%) hue-rotate(3deg) brightness(105%) contrast(105%)';
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
            if (status === 'added') { color = '#32d74b'; filter = greenFilter; }
            else if (status === 'removed') { color = '#ff453a'; filter = redFilter; }
            else if (status === 'changed') { color = '#ffd700'; filter = yellowFilter; }
            else if (isActive) { color = 'var(--text-bright)'; filter = ''; }
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
        runner.testForbiddenShurima, runner.testCarryRequirements, runner.testMustIncludeBypassLevelRestriction
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