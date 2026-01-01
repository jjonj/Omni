let tftData = null;
let currentConfig = null;
let optimizer = null;

let selectedMustInclude = []; 
let selectedEmblems = [];
let unitFilter = 'all';
let emblemSearch = '';
let highlightedTrait = null;

const DEFAULT_DISABLED_UNITS = [
    "Yorick", "Gwen", "LeBlanc", "Fizz", "Kalista", 
    "Nasus", "Xerath", "Singed", "Veigar", "Warwick", 
    "Aatrox", "Sett", "Tahm Kench", "Thresh"
];

let disabledUnits = [];

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
        
        const stored = localStorage.getItem('tft_disabled_units');
        if (stored) {
            disabledUnits = JSON.parse(stored);
        } else {
            disabledUnits = [...DEFAULT_DISABLED_UNITS];
        }
        
        const configTextarea = document.getElementById('disabled-units-config');
        if (configTextarea) configTextarea.value = disabledUnits.join(', ');

        optimizer = new TFTOptimizer(tftData.units, tftData.traits);
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
            .sort((a, b) => a.name.localeCompare(b.name));

        units.forEach(u => {
            const item = createDraggableItem(u.name, u.icon_url, 'unit', u.cost);
            item.classList.add('unit-node');
            item.dataset.traits = JSON.stringify(u.traits);

            if (disabledUnits.includes(u.name)) {
                item.style.opacity = '0.3';
                item.style.filter = 'grayscale(1)';
            }
            
            item.addEventListener('contextmenu', (e) => {
                e.preventDefault();
                e.stopPropagation();
                toggleDisableUnit(u.name);
            });

            pool.appendChild(item);
        });
    }
    applyTraitHighlight();
}

function toggleDisableUnit(name) {
    if (disabledUnits.includes(name)) {
        disabledUnits = disabledUnits.filter(n => n !== name);
    } else {
        disabledUnits.push(name);
    }
    localStorage.setItem('tft_disabled_units', JSON.stringify(disabledUnits));
    const textarea = document.getElementById('disabled-units-config');
    if (textarea) textarea.value = disabledUnits.join(', ');
    renderUnitPools();
}

function saveDisabledConfig() {
    const val = document.getElementById('disabled-units-config').value;
    disabledUnits = val.split(',').map(s => s.trim()).filter(s => s);
    localStorage.setItem('tft_disabled_units', JSON.stringify(disabledUnits));
    renderUnitPools();
    alert("Configuration Saved");
}

function resetDisabledToDefault() {
    disabledUnits = [...DEFAULT_DISABLED_UNITS];
    localStorage.setItem('tft_disabled_units', JSON.stringify(disabledUnits));
    document.getElementById('disabled-units-config').value = disabledUnits.join(', ');
    renderUnitPools();
}

function renderEmblemPool() {
    const pool = document.getElementById('emblem-pool');
    if (!pool) return;
    pool.innerHTML = '';
    
    const emblems = tftData.items.filter(i => {
        if (!i.is_emblem) return false;
        if (!emblemSearch) return true;
        return i.name.toLowerCase().includes(emblemSearch.toLowerCase());
    }).sort((a, b) => a.name.localeCompare(b.name));

    emblems.forEach(e => {
        const item = createDraggableItem(e.name, e.icon_url, 'emblem', null, e.trait);
        
        item.addEventListener('contextmenu', (ev) => {
            ev.preventDefault();
            ev.stopPropagation();
            toggleTraitHighlight(e.trait);
        });

        pool.appendChild(item);
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

function createDraggableItem(name, iconUrl, type, cost, trait, isSelected = false) {
    const div = document.createElement('div');
    div.className = 'draggable-item';
    div.draggable = true; 
    div.dataset.name = name;
    div.dataset.type = type;
    if (cost) div.dataset.cost = cost;
    if (trait) div.dataset.trait = trait;
    div.dataset.icon = iconUrl;
    div.dataset.selected = isSelected;

    const costColors = { 1: '#808080', 2: '#11b288', 3: '#207ac7', 4: '#c440da', 5: '#ffb93b' };
    const borderColor = cost ? (costColors[cost] || '#ccc') : '#0a84ff';

    div.innerHTML = `
        <img src="${iconUrl}" class="item-icon" style="border-color: ${borderColor}" 
             onerror="this.src='data:image/svg+xml;base64,PHN2ZyB4bWxucz0naHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmcnIHdpZHRoPSc0OCcgaGVpZ2h0PSc0OCcgc3R5bGU9J2JhY2tncm91bmQ6IzIyMjsnPjx0ZXh0IHg9JzUwJScgeT0nNTAlJyBkb20tYmFzZWxpbmU9J21pZGRsZScgdGV4dC1hbmNob3I9J21pZGRsZScgZmlsbD0nI2ZmZicgZm9udC1zaXplPScxMic+PyA8L3RleHQ+PC9zdmc+'">
        <div class="item-name">${name}</div>
    `;

    div.addEventListener('dragstart', (e) => {
        e.dataTransfer.setData('text/plain', JSON.stringify({
            name, type, cost, trait, iconUrl, isSelected
        }));
    });

    if (isSelected) {
        div.title = "Click or drag out to remove";
        div.style.cursor = 'pointer';
        div.addEventListener('click', () => {
            removeItem(name, type);
        });
    }

    return div;
}

function removeItem(name, type) {
    selectedMustInclude = selectedMustInclude.filter(item => item.name !== name);
    selectedEmblems = selectedEmblems.filter(e => e.name !== name);
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
            removeItem(data.name, data.type);
        }
    } catch(err) {}
}

function dropToMustInclude(e) {
    e.preventDefault();
    try {
        const data = JSON.parse(e.dataTransfer.getData('text/plain'));
        if (!selectedMustInclude.find(item => item.name === data.name)) {
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
    const emblemZone = document.getElementById('emblem-drop-zone');
    if (!mustZone || !emblemZone) return;

    mustZone.innerHTML = '';
    if (selectedMustInclude.length > 0) {
        selectedMustInclude.forEach(item => {
            const el = createDraggableItem(item.name, item.iconUrl, item.type, item.cost, item.trait, true);
            mustZone.appendChild(el);
        });
    } else {
        mustZone.innerHTML = '<div class="placeholder-text">Drag Units or Traits Here</div>';
    }

    emblemZone.innerHTML = '';
    if (selectedEmblems.length > 0) {
        selectedEmblems.forEach(emb => {
            const item = createDraggableItem(emb.name, emb.iconUrl, 'emblem', null, emb.trait, true);
            emblemZone.appendChild(item);
        });
    } else {
        emblemZone.innerHTML = '<div class="placeholder-text">Drag Emblems Here</div>';
    }
}

function clearMustInclude() {
    selectedMustInclude = [];
    renderSelectionZones();
}

function clearEmblems() {
    selectedEmblems = [];
    renderSelectionZones();
}

function filterEmblems(query) {
    emblemSearch = query;
    renderEmblemPool();
}

async function runOptimization() {
    if (!optimizer) return;

    const mustIncludeNames = selectedMustInclude.filter(i => i.type === 'unit').map(u => u.name);
    const mustIncludeTraits = selectedMustInclude.filter(i => i.type === 'emblem').map(t => t.trait);
    const emblems = selectedEmblems.map(e => e.trait);
    const solverMode = document.querySelector('input[name="solver-mode"]:checked').value;
    const selectedLevels = Array.from(document.querySelectorAll('.lvl-cb:checked')).map(cb => parseInt(cb.value));
    const limit = parseInt(document.getElementById('results-limit').value);
    
    const resultsContainer = document.getElementById('results-container');
    resultsContainer.innerHTML = '<div style="color: var(--text-dim); padding: 20px;">Initializing...</div>';

    const progressContainer = document.getElementById('optimizer-progress-container');
    const progressBar = document.getElementById('progress-bar');
    const progressPercent = document.getElementById('progress-percent');
    const progressLabel = document.getElementById('progress-label');

    progressContainer.style.display = 'block';
    
    for (const level of selectedLevels) {
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
            if (disabledUnits.includes(u.name)) return false;
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

    progressContainer.style.display = 'none';
    if (resultsContainer.firstChild && resultsContainer.firstChild.innerText === 'Initializing...') {
        resultsContainer.removeChild(resultsContainer.firstChild);
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

        const unitCount = displayBoard.length;
        const totalSlots = displayBoard.reduce((acc, u) => acc + (u.slots || 1), 0);

        card.innerHTML = `
            <div style="display: flex; justify-content: space-between; margin-bottom: 8px;">
                <strong>Option ${index + 1} (${unitCount} units, ${totalSlots} slots)</strong>
                <span style="color: var(--accent); font-weight: 600;">${res.score}</span>
            </div>
        `;

        const list = document.createElement('div');
        list.className = 'unit-list';
        list.style.gap = '4px';
        
        const sortedBoard = displayBoard.sort((a, b) => a.cost - b.cost || a.name.localeCompare(b.name));
        
        sortedBoard.forEach((u, uIdx) => {
            const activeTraits = Object.keys(res.counts).filter(t => tftData.traits[t] && tftData.traits[t].some(b => b <= res.counts[t]));
            const contributedTraits = u.traits.filter(t => activeTraits.includes(t));
            const isFlex = contributedTraits.length === 1;

            const unitItem = document.createElement('div');
            unitItem.className = 'unit-item';
            
            const costColors = { 1: '#808080', 2: '#11b288', 3: '#207ac7', 4: '#c440da', 5: '#ffb93b' };
            const borderColor = costColors[u.cost] || '#ccc';
            
            unitItem.innerHTML = `
                <img src="${u.icon_url}" class="unit-icon" style="border-color: ${borderColor};" 
                     onerror="this.src='data:image/svg+xml;base64,PHN2ZyB4bWxucz0naHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmcnIHdpZHRoPSczNicgaGVpZ2h0PSczNicgc3R5bGU9J2JhY2tncm91bmQ6IzIyMjsnPjx0ZXh0IHg9JzUwJScgeT0nNTAlJyBkb20tYmFzZWxpbmU9J21pZGRsZScgdGV4dC1hbmNob3I9J21pZGRsZScgZmlsbD0nI2ZmZicgZm9udC1zaXplPSc4Jz4/IDwvdGV4dD48L3N2Zz4='">
                ${isFlex ? '<div class="replacement-indicator">*</div>' : ''}
                <div class="unit-name">${u.name}</div>
            `;

            const repList = document.createElement('div');
            repList.className = 'replacement-list';
            repList.style.top = '90%'; 
            repList.style.left = '50%';
            repList.style.transform = 'translateX(-50%)';
            repList.style.marginTop = '0px';

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
                const replacements = tftData.units.filter(alt => 
                    !boardNames.includes(alt.name) && 
                    alt.traits.includes(flexTrait) && 
                    !disabledUnits.includes(alt.name) &&
                    (level <= 6 ? alt.cost <= 3 : true) &&
                    !(alt.name === "Kennen" && level < 6) &&
                    !(alt.name.includes("Kobuko") && level < 7) &&
                    !(alt.cost === 5 && level < 8)
                ).sort((a, b) => a.cost - b.cost);

                if (replacements.length > 0) {
                    replacements.forEach(rep => {
                        const opt = document.createElement('div');
                        opt.className = 'replacement-option';
                        opt.innerHTML = `<img src="${rep.icon_url}" onerror="this.style.background='#333'"> <span>${rep.name} (${rep.cost})</span>`;
                        opt.onclick = (e) => {
                            e.stopPropagation();
                            res.board[uIdx] = rep;
                            renderResults(results, container, level);
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
            const breakpoints = tftData.traits[trait];
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
            if (hasEmblem) textColor = '#32d74b';
            else if (isActive) textColor = 'var(--text-bright)';

            traitItem.style.color = textColor;
            if (isActive || hasEmblem) traitItem.style.fontWeight = '600';

            const iconUrl = `assets/tft/${currentConfig.current_set}/traits/${trait.replace(/ /g, '')}.svg`;
            const greenFilter = 'invert(48%) sepia(79%) saturate(2476%) hue-rotate(86deg) brightness(118%) contrast(119%)';
            const normalFilter = isActive ? '' : 'opacity: 0.5; filter: grayscale(1);';

            traitItem.innerHTML = `
                <img src="${iconUrl}" style="width: 16px; height: 16px; ${hasEmblem ? 'filter: ' + greenFilter : normalFilter}" 
                     title="${trait}" onerror="this.style.display='none'">
                <span title="${trait}">${count}</span>
            `;
            traitsList.appendChild(traitItem);
        });
        
        card.appendChild(traitsList);
        container.appendChild(card);
    });
}

async function runLogicTests() {
    const resultsDiv = document.getElementById('test-results');
    if (!resultsDiv) return;
    resultsDiv.innerHTML = "Starting test suite...\n";
    
    if (!tftData || !optimizer) {
        resultsDiv.innerHTML += "FAIL: Data not loaded.";
        return;
    }

    const runner = new TFTTester(optimizer, tftData);
    const tests = [
        runner.testLevel4Optimal,
        runner.testAnnieTibbersLogic,
        runner.testLevel4CostConstraint,
        runner.testLevel5CostConstraint,
        runner.testBronzeForLifeLogic,
        runner.testLevel10Constraints,
        runner.testSpecificLevelUnitRestrictions,
        runner.testMustIncludeTraits,
        runner.testAnnieOneArcanistFix,
        runner.testTargonSpecialLogic,
        runner.testUnitReplacementPersistenceBug,
        runner.testSylasForbiddenUnits,
        runner.testLevel8FiveCostLimit,
        runner.testLevel9Constraints,
        runner.testTraitIgnoreList,
        runner.testForbiddenShurima,
        runner.testCarryRequirements
    ];

    resultsDiv.innerHTML = "";
    for (const test of tests) {
        const statusEl = document.createElement('div');
        statusEl.style.color = "#aaa";
        statusEl.innerText = `[WAIT] Running ${test.name}...`;
        resultsDiv.appendChild(statusEl);

        await new Promise(resolve => setTimeout(resolve, 50));

        const start = performance.now();
        try {
            // Need to await findBestBoards inside tests if they call it
            await test.call(runner);
            const duration = (performance.now() - start).toFixed(1);
            statusEl.style.color = "#32d74b";
            statusEl.innerText = `[PASS] ${test.name} (${duration}ms)`;
        } catch (err) {
            const duration = (performance.now() - start).toFixed(1);
            statusEl.style.color = "#ff453a";
            statusEl.innerText = `[FAIL] ${test.name} (${duration}ms)`;
            const errEl = document.createElement('div');
            errEl.style.color = "#666";
            errEl.style.marginLeft = "20px";
            errEl.style.marginBottom = "10px";
            errEl.innerText = err.message;
            resultsDiv.appendChild(errEl);
        }
    }
}

loadTFTData();