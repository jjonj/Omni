let tftData = null;
let currentConfig = null;

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
    const mustInclude = document.getElementById('must-include').value;
    const emblems = Array.from(document.querySelectorAll('.emblem-cb:checked')).map(cb => cb.value);
    
    console.log("Optimizing for:", { mustInclude, emblems });
    alert("Optimization logic not ported yet!");
}

// Initial load
loadTFTData();