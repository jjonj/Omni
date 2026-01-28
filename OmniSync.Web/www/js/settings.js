let hubConnection = null;
let currentHotkeys = [];
let currentProjects = [];
let recordingHotkeyIndex = -1;

async function init() {
    hubConnection = new signalR.HubConnectionBuilder()
        .withUrl(HUB_URL)
        .withAutomaticReconnect()
        .build();

    hubConnection.on("UpdateRunOnStartup", (enabled) => {
        const cb = document.getElementById("run-on-startup");
        if (cb) cb.checked = enabled;
    });

    try {
        await hubConnection.start();
        console.log("Connected to Hub");
        await hubConnection.invoke("Authenticate", API_KEY);
        
        loadSettings();
    } catch (err) {
        console.error("SignalR Connection Error:", err);
    }
}

async function loadSettings() {
    try {
        const settings = await hubConnection.invoke("GetSettings");
        currentHotkeys = settings.hotkeys;
        currentProjects = settings.projects || [];
        renderHotkeys();
        renderProjects();
        renderExeMappings(settings.exeMappings);
        
        // Load Tell PC settings
        const wsInput = document.getElementById("tell-pc-workspace");
        const ctxInput = document.getElementById("tell-pc-context");
        const soundCb = document.getElementById("tell-pc-sound");
        if (wsInput) wsInput.value = settings.tellPcWorkspace || "";
        if (ctxInput) ctxInput.value = settings.tellPcSystemContext || "";
        if (soundCb) soundCb.checked = settings.tellPcSoundEnabled !== false;

        const status = await hubConnection.invoke("GetHubStatus");
        const cb = document.getElementById("run-on-startup");
        if (cb) cb.checked = status.isRunOnStartupEnabled;
    } catch (err) {
        console.error("Error loading settings:", err);
    }
}

async function saveAiSettings() {
    const workspace = document.getElementById("tell-pc-workspace").value;
    const context = document.getElementById("tell-pc-context").value;
    const soundEnabled = document.getElementById("tell-pc-sound").checked;

    try {
        await hubConnection.invoke("UpdateTellPcSettings", workspace, context, soundEnabled);
        const btn = event.currentTarget;
        const originalText = btn.innerText;
        btn.innerText = "SAVED!";
        btn.classList.add("success");
        setTimeout(() => {
            btn.innerText = originalText;
            btn.classList.remove("success");
        }, 2000);
    } catch (err) {
        console.error("Error saving AI settings:", err);
        alert("Failed to save AI settings.");
    }
}

function renderProjects() {
    const list = document.getElementById("projects-list");
    if (!list) return;
    list.innerHTML = "";
    
    if (currentProjects.length === 0) {
        list.innerHTML = '<div class="text-dim" style="font-size: 12px;">No projects defined yet.</div>';
        return;
    }

    currentProjects.forEach((proj, index) => {
        const row = document.createElement("div");
        row.className = "hub-box";
        row.style.background = "var(--bg)";
        row.style.padding = "12px";
        
        const actionsSummary = proj.actions ? `${proj.actions.length} action(s)` : "No actions";
        
        row.innerHTML = `
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
                <div style="font-weight: bold; color: var(--accent);">${proj.name}</div>
                <div>
                    <button class="btn" onclick="editProject(${index})">Edit</button>
                    <button class="btn" onclick="duplicateProject(${index})">Copy</button>
                    <button class="btn danger" onclick="removeProject(${index})">Delete</button>
                </div>
            </div>
            <div class="text-dim" style="font-size: 11px;">${actionsSummary}</div>
        `;
        list.appendChild(row);
    });
}

function addNewProject() {
    const name = prompt("Enter project name:");
    if (!name) return;
    
    currentProjects.push({
        id: generateGuid(),
        name: name,
        actions: [],
        hotkeyName: ""
    });
    renderProjects();
}

function duplicateProject(index) {
    const source = currentProjects[index];
    const duplicated = JSON.parse(JSON.stringify(source));
    duplicated.id = generateGuid();
    duplicated.name = duplicated.name + " (Copy)";
    
    currentProjects.push(duplicated);
    renderProjects();
}

function removeProject(index) {
    if (confirm(`Delete project '${currentProjects[index].name}'?`)) {
        currentProjects.splice(index, 1);
        renderProjects();
    }
}

function editProject(index) {
    const proj = currentProjects[index];
    const newName = prompt("Edit project name:", proj.name);
    if (newName) proj.name = newName;
    
    // For now, simple editing. In a full implementation, 
    // we'd open a modal to manage project actions.
    // The C# side has a full editor, this is a web placeholder.
    renderProjects();
}

async function saveProjects() {
    try {
        await hubConnection.invoke("UpdateProjects", currentProjects);
        const btn = event.currentTarget;
        const originalText = btn.innerText;
        btn.innerText = "SAVED!";
        btn.classList.add("success");
        setTimeout(() => {
            btn.innerText = originalText;
            btn.classList.remove("success");
        }, 2000);
    } catch (err) {
        console.error("Error saving projects:", err);
        alert("Failed to save projects.");
    }
}

function generateGuid() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
        var r = Math.random() * 16 | 0, v = c == 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
    });
}

function renderHotkeys() {
    const list = document.getElementById("hotkeys-list");
    list.innerHTML = "";
    
    // Group hotkeys by category
    const groups = {};
    currentHotkeys.forEach(hk => {
        const cat = hk.category || "General";
        if (!groups[cat]) groups[cat] = [];
        groups[cat].push(hk);
    });

    Object.keys(groups).sort().forEach(cat => {
        const catId = `cat-${cat.replace(/\s+/g, '-')}`;
        const groupContainer = document.createElement("div");
        groupContainer.className = "hotkey-category";
        groupContainer.style.marginBottom = "10px";
        
        groupContainer.innerHTML = `
            <div class="category-header" onclick="toggleCategory('${catId}')" 
                 style="background: #252526; padding: 10px; cursor: pointer; border: 1px solid #3F3F46; font-weight: bold; display: flex; justify-content: space-between;">
                <span>${cat}</span>
                <span class="arrow">▶</span>
            </div>
            <div id="${catId}" class="category-content" style="display: none; padding: 10px; background: #1e1e1e; border: 1px solid #3F3F46; border-top: none;">
            </div>
        `;
        
        const content = groupContainer.querySelector(".category-content");
        
        groups[cat].forEach((hk) => {
            const index = currentHotkeys.indexOf(hk);
            const row = document.createElement("div");
            row.className = "hotkey-row";
            row.style.display = "flex";
            row.style.alignItems = "center";
            row.style.gap = "10px";
            row.style.marginBottom = "5px";
            
            row.innerHTML = `
                <div class="hotkey-name" onclick="editHotkeyInfo(${index})" style="cursor: pointer; text-decoration: underline; flex: 1;">${hk.name}</div>
                <input type="text" class="hotkey-input" id="hk-${index}" 
                    value="${hk.key || 'Click to set'}" readonly 
                    onclick="startRecording(${index})"
                    onblur="setTimeout(stopRecording, 200)"
                    style="width: 150px;">
                <button class="btn danger" onclick="removeHotkey(${index})">Delete</button>
            `;
            content.appendChild(row);
        });
        
        list.appendChild(groupContainer);
    });
}

function toggleCategory(id) {
    const content = document.getElementById(id);
    const header = content.previousElementSibling;
    const arrow = header.querySelector(".arrow");
    
    if (content.style.display === "none") {
        content.style.display = "block";
        arrow.innerText = "▼";
    } else {
        content.style.display = "none";
        arrow.innerText = "▶";
    }
}

function addNewHotkey() {
    const name = prompt("Enter hotkey name (e.g., 'Sleep PC'):");
    if (!name) return;
    const action = prompt("Enter command action (e.g., 'SLEEP_PC', 'TOGGLE_MUTE', or full command):");
    if (!action) return;
    const category = prompt("Enter category (e.g., 'TFT', 'System', 'General'):", "General") || "General";
    
    currentHotkeys.push({
        name: name,
        action: action,
        category: category,
        key: ""
    });
    renderHotkeys();
}

function removeHotkey(index) {
    if (confirm(`Delete hotkey '${currentHotkeys[index].name}'?`)) {
        currentHotkeys.splice(index, 1);
        renderHotkeys();
    }
}

function editHotkeyInfo(index) {
    const hk = currentHotkeys[index];
    const newName = prompt("Edit hotkey name:", hk.name);
    if (newName) hk.name = newName;
    const newAction = prompt("Edit hotkey action:", hk.action);
    if (newAction) hk.action = newAction;
    renderHotkeys();
}

function renderExeMappings(mappings) {
    const list = document.getElementById("exe-mappings-list");
    list.innerHTML = "";
    
    if (!mappings || Object.keys(mappings).length === 0) {
        list.innerHTML = '<div class="text-dim" style="font-size: 12px;">No executable mappings configured.</div>';
        return;
    }

    for (const [key, path] of Object.entries(mappings)) {
        const row = document.createElement("div");
        row.className = "row";
        row.style.gap = "8px";
        row.innerHTML = `
            <label style="width: 120px; font-family: var(--font-mono); font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">${key}</label>
            <input type="text" value="${path}" style="flex: 1; font-size: 11px;" readonly title="${path}">
            <button class="btn primary" onclick="testMapping('${key}')">Test</button>
            <button class="btn danger" onclick="removeMapping('${key}')">Delete</button>
        `;
        list.appendChild(row);
    }
}

async function promptForMapping() {
    const key = prompt("Enter executable name (e.g. 'vivaldi.exe'):");
    if (!key) return;
    const path = prompt("Enter full path to executable:");
    if (!path) return;

    try {
        await hubConnection.invoke("AddMapping", key, path);
        loadSettings(); // Reload to show new mapping
    } catch (err) {
        console.error("Error adding mapping:", err);
        alert("Failed to add mapping.");
    }
}

async function removeMapping(key) {
    if (!confirm(`Delete mapping for '${key}'?`)) return;

    try {
        await hubConnection.invoke("RemoveMapping", key);
        loadSettings(); // Reload to show updated list
    } catch (err) {
        console.error("Error removing mapping:", err);
        alert("Failed to remove mapping.");
    }
}

async function testMapping(key) {
    try {
        await hubConnection.invoke("ExecuteCommand", key);
        console.log(`Test command sent for: ${key}`);
    } catch (err) {
        console.error("Error testing mapping:", err);
        alert("Failed to test mapping.");
    }
}

function startRecording(index) {
    if (recordingHotkeyIndex !== -1) stopRecording();
    
    recordingHotkeyIndex = index;
    const input = document.getElementById(`hk-${index}`);
    input.classList.add("recording");
    input.value = "Press keys...";
    
    window.addEventListener("keydown", handleKeyDown);
}

function stopRecording() {
    if (recordingHotkeyIndex === -1) return;
    
    const input = document.getElementById(`hk-${recordingHotkeyIndex}`);
    if (input) {
        input.classList.remove("recording");
        if (input.value === "Press keys...") {
            input.value = currentHotkeys[recordingHotkeyIndex].key || "Click to set";
        }
    }
    
    window.removeEventListener("keydown", handleKeyDown);
    recordingHotkeyIndex = -1;
}

function handleKeyDown(e) {
    if (recordingHotkeyIndex === -1) return;
    
    // Don't capture just modifiers
    if (["Control", "Shift", "Alt", "Meta"].includes(e.key)) {
        return;
    }

    e.preventDefault();
    e.stopPropagation();
    
    const keys = [];
    if (e.ctrlKey) keys.push("Ctrl");
    if (e.altKey) keys.push("Alt");
    if (e.shiftKey) keys.push("Shift");
    if (e.metaKey) keys.push("Win");
    
    let key = e.key.toUpperCase();
    if (key === " ") key = "SPACE";
    
    keys.push(key);
    
    const hotkeyStr = keys.join("+");
    currentHotkeys[recordingHotkeyIndex].key = hotkeyStr;
    
    const input = document.getElementById(`hk-${recordingHotkeyIndex}`);
    if (input) {
        input.value = hotkeyStr;
        input.blur();
    }
}

function clearHotkey(index) {
    currentHotkeys[index].key = "";
    renderHotkeys();
}

async function saveHotkeys() {
    try {
        await hubConnection.invoke("UpdateHotkeys", currentHotkeys);
        // Visual feedback
        const btn = event.currentTarget;
        const originalText = btn.innerText;
        btn.innerText = "SAVED!";
        btn.classList.add("success");
        setTimeout(() => {
            btn.innerText = originalText;
            btn.classList.remove("success");
        }, 2000);
    } catch (err) {
        console.error("Error saving hotkeys:", err);
        alert("Failed to save hotkeys.");
    }
}

function switchTab(tabId) {
    document.querySelectorAll(".tab-panel").forEach(p => p.classList.remove("active"));
    document.querySelectorAll(".settings-tab").forEach(t => t.classList.remove("active"));
    
    document.getElementById(tabId).classList.add("active");
    event.currentTarget.classList.add("active");
}

function toggleStartup(enabled) {
    if (hubConnection) {
        hubConnection.invoke("SetRunOnStartup", enabled);
    }
}

init();
