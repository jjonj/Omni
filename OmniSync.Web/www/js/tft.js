let tftData = null;
let currentConfig = null;
let optimizer = null;

let selectedMustInclude = null;
let selectedEmblems = [];
let unitFilter = 'all';
let emblemSearch = '';

function switchTab(tabId) {
    document.querySelectorAll('.tft-tab').forEach(tab => {
        tab.classList.remove('active');
        if (tab.innerText.toLowerCase().replace(/ /g, '-') === tabId || 
            (tabId === 'emblem-portal' && tab.innerText === 'Emblem Portal') ||
            (tabId === 'bronze-for-life' && tab.innerText === 'BronzeForLife') ||
            (tabId === 'world-runes' && tab.innerText === 'World Runes') ||
            (tabId === 'director' && tab.innerText === 'Director') ||
            (tabId === 'config' && tab.innerText === 'Configuration')) {
            tab.classList.add('active');
        }
    });

    const tabMap = {
        'Emblem Portal': 'emblem-portal',
        'World Runes': 'world-runes',
        'BronzeForLife': 'bronze-for-life',
        'Director': 'director',
        'Configuration': 'config'
    };

    document.querySelectorAll('.tab-panel').forEach(panel => {
        panel.classList.remove('active');
    });
    
    const targetId = tabMap[tabId] || tabId;
    document.getElementById(targetId).classList.add('active');
}

async function loadTFTData() {
    try {
        const configResp = await fetch('assets/tft/data/set_config.json');
        currentConfig = await configResp.json();
        
        const dataResp = await fetch(`assets/tft/data/${currentConfig.current_set}.json`);
        tftData = await dataResp.json();
        
        optimizer = new TFTOptimizer(tftData.units, tftData.traits);
        updateUI();
    } catch (err) {
        console.error("Failed to load TFT data:", err);
    }
}

function updateUI() {
    if (!tftData) return;

    // Update Config Tab
    document.getElementById('config-set-name').innerText = tftData.set_name;
    document.getElementById('config-json-display').innerText = JSON.stringify(tftData, null, 2);

    renderUnitPool();
    renderEmblemPool();
    renderSelectionZones();
}

function renderUnitPool() {
    const pool = document.getElementById('unit-pool');
    pool.innerHTML = '';
    
    const units = tftData.units.filter(u => {
        if (unitFilter === 'all') return true;
        return u.cost === parseInt(unitFilter);
    }).sort((a, b) => a.cost - b.cost || a.name.localeCompare(b.name));

    units.forEach(u => {
        const item = createDraggableItem(u.name, u.icon_url, 'unit', u.cost);
        pool.appendChild(item);
    });
}

function renderEmblemPool() {
    const pool = document.getElementById('emblem-pool');
    pool.innerHTML = '';
    
    const emblems = tftData.items.filter(i => {
        if (!i.is_emblem) return false;
        if (!emblemSearch) return true;
        return i.name.toLowerCase().includes(emblemSearch.toLowerCase());
    }).sort((a, b) => a.name.localeCompare(b.name));

    emblems.forEach(e => {
        const item = createDraggableItem(e.name, e.icon_url, 'emblem', null, e.trait);
        pool.appendChild(item);
    });
}

function createDraggableItem(name, iconUrl, type, cost, trait) {
    const div = document.createElement('div');
    div.className = 'draggable-item';
    div.draggable = true;
    div.dataset.name = name;
    div.dataset.type = type;
    if (cost) div.dataset.cost = cost;
    if (trait) div.dataset.trait = trait;
    div.dataset.icon = iconUrl;

    const costColors = { 1: '#808080', 2: '#11b288', 3: '#207ac7', 4: '#c440da', 5: '#ffb93b' };
    const borderColor = cost ? (costColors[cost] || '#ccc') : '#0a84ff';

    div.innerHTML = `
        <img src="${iconUrl}" class="item-icon" style="border-color: ${borderColor}" 
             onerror="this.src='data:image/svg+xml;base64,PHN2ZyB4bWxucz0naHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmcnIHdpZHRoPSc0OCcgaGVpZ2h0PSc0OCcgc3R5bGU9J2JhY2tncm91bmQ6IzIyMjsnPjx0ZXh0IHg9JzUwJScgeT0nNTAlJyBkb20tYmFzZWxpbmU9J21pZGRsZScgdGV4dC1hbmNob3I9J21pZGRsZScgZmlsbD0nI2ZmZicgZm9udC1zaXplPScxMic+PyA8L3RleHQ+PC9zdmc+'">
        <div class="item-name">${name}</div>
    `;

    div.addEventListener('dragstart', (e) => {
        e.dataTransfer.setData('text/plain', JSON.stringify({
            name, type, cost, trait, iconUrl
        }));
    });

    return div;
}

function allowDrop(e) {
    e.preventDefault();
    e.currentTarget.classList.add('drag-over');
}

// Global cleanup for drag-over
document.addEventListener('dragleave', (e) => {
    if (e.target.classList.contains('drop-zone')) {
        e.target.classList.remove('drag-over');
    }
});

document.addEventListener('drop', (e) => {
    if (e.target.classList.contains('drop-zone')) {
        e.target.classList.remove('drag-over');
    }
});

function dropUnit(e) {
    e.preventDefault();
    const data = JSON.parse(e.dataTransfer.getData('text/plain'));
    if (data.type === 'unit') {
        selectedMustInclude = data;
        renderSelectionZones();
    }
}

function dropEmblem(e) {
    e.preventDefault();
    const data = JSON.parse(e.dataTransfer.getData('text/plain'));
    if (data.type === 'emblem') {
        if (!selectedEmblems.find(em => em.name === data.name)) {
            selectedEmblems.push(data);
            renderSelectionZones();
        }
    }
}

function renderSelectionZones() {
    const unitZone = document.getElementById('must-include-zone');
    const emblemZone = document.getElementById('emblem-drop-zone');

    // Render Unit
    unitZone.innerHTML = '';
    if (selectedMustInclude) {
        const item = createDraggableItem(selectedMustInclude.name, selectedMustInclude.iconUrl, 'unit', selectedMustInclude.cost);
        item.draggable = false; // Disable dragging out for now, or implement removal
        unitZone.appendChild(item);
    } else {
        unitZone.innerHTML = '<div class="placeholder-text">Drag Unit Here</div>';
    }

    // Render Emblems
    emblemZone.innerHTML = '';
    if (selectedEmblems.length > 0) {
        selectedEmblems.forEach(emb => {
            const item = createDraggableItem(emb.name, emb.iconUrl, 'emblem', null, emb.trait);
            item.draggable = false;
            emblemZone.appendChild(item);
        });
    } else {
        emblemZone.innerHTML = '<div class="placeholder-text">Drag Emblems Here</div>';
    }
}

function clearMustInclude() {
    selectedMustInclude = null;
    renderSelectionZones();
}

function clearEmblems() {
    selectedEmblems = [];
    renderSelectionZones();
}

function filterUnits(cost) {
    unitFilter = cost;
    document.querySelectorAll('.filter-btn').forEach(btn => {
        btn.classList.toggle('active', btn.innerText.toLowerCase() === cost.toString() || (cost === 'all' && btn.innerText.toLowerCase() === 'all'));
    });
    renderUnitPool();
}

function filterEmblems(query) {
    emblemSearch = query;
    renderEmblemPool();
}

function runOptimization() {
    if (!optimizer) return;

    const mustIncludeName = selectedMustInclude ? selectedMustInclude.name : null;
    const emblems = selectedEmblems.map(e => e.trait);
    
    const results6Div = document.getElementById('lvl6-list');
    const results8Div = document.getElementById('lvl8-list');
    
    results6Div.innerHTML = '<div style="color: var(--text-dim);">Calculating...</div>';
    results8Div.innerHTML = '<div style="color: var(--text-dim);">Calculating...</div>';

    setTimeout(() => {
        const pool6 = tftData.units.filter(u => u.cost <= 3);
        const results6 = optimizer.findBestBoards(pool6, 6, emblems, mustIncludeName);
        renderResults(results6, results6Div);

        const results8 = optimizer.findBestBoards(tftData.units, 8, emblems, mustIncludeName);
        renderResults(results8, results8Div);
    }, 50);
}

function renderResults(results, container) {
    container.innerHTML = '';
    
    if (results.length === 0) {
        container.innerHTML = '<div style="color: var(--text-dim); font-size: 11px;">No valid compositions found.</div>';
        return;
    }

    results.forEach((res, index) => {
        const card = document.createElement('div');
        card.className = 'result-card';
        card.style.marginBottom = '10px';
        
        card.innerHTML = `
            <div style="display: flex; justify-content: space-between; margin-bottom: 8px;">
                <strong>Option ${index + 1}</strong>
                <span style="color: var(--accent); font-weight: 600;">${res.score}</span>
            </div>
        `;

        const list = document.createElement('div');
        list.className = 'unit-list';
        list.style.gap = '4px';
        
        const sortedBoard = [...res.board].sort((a, b) => a.cost - b.cost);
        
        sortedBoard.forEach(u => {
            const item = document.createElement('div');
            item.className = 'unit-item';
            
            const costColors = { 1: '#808080', 2: '#11b288', 3: '#207ac7', 4: '#c440da', 5: '#ffb93b' };
            const borderColor = costColors[u.cost] || '#ccc';
            
            item.innerHTML = `
                <img src="${u.icon_url}" class="unit-icon" style="border-color: ${borderColor};" 
                     onerror="this.src='data:image/svg+xml;base64,PHN2ZyB4bWxucz0naHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmcnIHdpZHRoPSczNicgaGVpZ2h0PSczNicgc3R5bGU9J2JhY2tncm91bmQ6IzIyMjsnPjx0ZXh0IHg9JzUwJScgeT0nNTAlJyBkb20tYmFzZWxpbmU9J21pZGRsZScgdGV4dC1hbmNob3I9J21pZGRsZScgZmlsbD0nI2ZmZicgZm9udC1zaXplPSc4Jz4/IDwvdGV4dD48L3N2Zz4='">
                <div class="unit-name">${u.name}</div>
            `;
            list.appendChild(item);
        });
        
        card.appendChild(list);
        container.appendChild(card);
    });
}

// Initial load
loadTFTData();