let tftData = null;
let currentConfig = null;
let optimizer = null;

function switchTab(tabId) {
    // Update tab buttons
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

    // Handle exact text matching for convenience
    const tabMap = {
        'Emblem Portal': 'emblem-portal',
        'World Runes': 'world-runes',
        'BronzeForLife': 'bronze-for-life',
        'Director': 'director',
        'Configuration': 'config'
    };

    // Update panels
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

    // Update Must Include dropdown
    const mustInclude = document.getElementById('must-include');
    const currentValue = mustInclude.value;
    mustInclude.innerHTML = '<option value="">None</option>';
    
    const sortedUnits = [...tftData.units].sort((a, b) => a.name.localeCompare(b.name));
    sortedUnits.forEach(unit => {
        const opt = document.createElement('option');
        opt.value = unit.name;
        opt.innerText = unit.name;
        mustInclude.appendChild(opt);
    });
    mustInclude.value = currentValue;

    // Update Emblems list
    const emblemList = document.getElementById('emblem-list');
    emblemList.innerHTML = '';
    
    tftData.items.filter(item => item.is_emblem).forEach(emblem => {
        const label = document.createElement('label');
        label.className = 'hub-status-label';
        label.style.display = 'flex';
        label.style.align_items = 'center';
        label.style.gap = '8px';
        label.style.cursor = 'pointer';
        label.style.textTransform = 'none';
        
        const cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.className = 'emblem-cb';
        cb.value = emblem.trait;
        
        label.appendChild(cb);
        label.append(emblem.name);
        emblemList.appendChild(label);
    });
}

function runOptimization() {
    if (!optimizer) return;

    const mustInclude = document.getElementById('must-include').value;
    const emblems = Array.from(document.querySelectorAll('.emblem-cb:checked')).map(cb => cb.value);
    
    const results6Div = document.getElementById('lvl6-list');
    const results8Div = document.getElementById('lvl8-list');
    
    results6Div.innerHTML = '<div style="color: var(--text-dim);">Calculating...</div>';
    results8Div.innerHTML = '<div style="color: var(--text-dim);">Calculating...</div>';

    // Use setTimeout to allow UI to render "Calculating..." before blocking thread
    setTimeout(() => {
        // Level 6 Calculation
        const pool6 = tftData.units.filter(u => u.cost <= 3);
        const results6 = optimizer.findBestBoards(pool6, 6, emblems, mustInclude);
        renderResults(results6, results6Div);

        // Level 8 Calculation
        // Pool 8 uses all units
        const results8 = optimizer.findBestBoards(tftData.units, 8, emblems, mustInclude);
        renderResults(results8, results8Div);
    }, 50);
}

function renderResults(results, container) {
    container.innerHTML = '';
    
    if (results.length === 0) {
        container.innerHTML = '<div style="color: var(--text-dim);">No valid compositions found.</div>';
        return;
    }

    results.forEach((res, index) => {
        const card = document.createElement('div');
        card.className = 'result-card';
        
        const header = document.createElement('div');
        header.style.display = 'flex';
        header.style.justifyContent = 'space-between';
        header.style.marginBottom = '10px';
        header.innerHTML = `<strong>Option ${index + 1}</strong> <span style="color: var(--accent);">Score: ${res.score}</span>`;
        card.appendChild(header);

        const list = document.createElement('div');
        list.className = 'unit-list';
        
        // Sort units by cost
        const sortedBoard = [...res.board].sort((a, b) => a.cost - b.cost);
        
        sortedBoard.forEach(u => {
            const item = document.createElement('div');
            item.className = 'unit-item';
            
            // Try to use icon if available, otherwise placeholder color based on cost
            const costColors = { 1: '#808080', 2: '#11b288', 3: '#207ac7', 4: '#c440da', 5: '#ffb93b' };
            const borderColor = costColors[u.cost] || '#ccc';
            
            // Use a placeholder image if icon not set, or a default path
            // For now we assume a standard path structure: assets/tft/set16/champions/{name}.png
            const iconPath = `assets/tft/${currentConfig.current_set}/champions/${u.name.replace(/ /g, '').replace(/'/g, '')}.png`;
            
            item.innerHTML = `
                <img src="${iconPath}" class="unit-icon" style="border-color: ${borderColor};" onerror="this.src='data:image/svg+xml;base64,PHN2ZyB4bWxucz0naHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmcnIHdpZHRoPSc0OCcgaGVpZ2h0PSc0OCcgc3R5bGU9J2JhY2tncm91bmQ6IzIyMjsnPjx0ZXh0IHg9JzUwJScgeT0nNTAlJyBkb20tYmFzZWxpbmU9J21pZGRsZScgdGV4dC1hbmNob3I9J21pZGRsZScgZmlsbD0nI2ZmZicgZm9udC1zaXplPScxMic+PyA8L3RleHQ+PC9zdmc+'">
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