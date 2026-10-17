let hubConnection = null;
let currentHotkeys = [];
let currentSetups = [];
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
    hubConnection.onreconnected(async () => {
        await hubConnection.invoke("Authenticate", API_KEY);
        if (!editingSetupId) await loadSettings();
    });
    hubConnection.on("SettingsUpdated", async () => {
        if (!waitingForSnapshot && !editingSetupId && recordingHotkeyIndex === -1) await loadSettings();
    });
    hubConnection.on("SetupSnapshotCompleted", async result => {
        if (!waitingForSnapshot) return;
        waitingForSnapshot = false;
        if (result.id) {
            editingSetupId = null;
            document.getElementById("setup-editor").hidden = true;
            await loadSettings();
        }
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
        document.getElementById("task-resurfacing-times").value = settings.taskResurfacingTimes || "11:00,18:00";
        currentHotkeys = settings.hotkeys;
        currentSetups = settings.setups || [];
        renderHotkeys();
        renderSetups();
        renderExeMappings(settings.exeMappings);
        renderAiModels(settings.aiModels || [], settings.defaultAiModel || "");
        
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

async function saveTaskResurfacingTimes() {
    try {
        await hubConnection.invoke("SetTaskResurfacingTimes", document.getElementById("task-resurfacing-times").value);
    } catch (error) {
        alert("Could not save reminder times: " + error.message);
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


const setupBaseKeys = [..."1234567890QERTYUIOPFGHJKLZXCVBNM", ...Array.from({length: 24}, (_, i) => "F" + (i + 1))];
const setupShortcutKeys = [...setupBaseKeys, ...setupBaseKeys.map(k => "SHIFT+" + k)];
let setupOptions = { monitors: [], zones: [] };
let editingSetupId = null;
let draggedSetupId = null;
let waitingForSnapshot = false;

function setupElement(tag, text, parent) {
    const element = document.createElement(tag);
    if (text !== null) element.textContent = text;
    if (parent) parent.appendChild(element);
    return element;
}
function setupButton(parent, text, action) {
    const button = setupElement("button", text, parent);
    button.className = "modern-btn";
    button.onclick = action;
    return button;
}
function nextSetupShortcut() {
    const used = new Set(currentSetups.map(s => (s.shortcut || "").toUpperCase()));
    return setupShortcutKeys.find(k => !used.has(k)) || "";
}
function renderSetups() {
    const list = document.getElementById("setups-list");
    if (!list) return;
    list.replaceChildren();
    if (!currentSetups.length) setupElement("p", "No setups yet. Snapshot your desktop or create an empty setup.", list);
    currentSetups.forEach(setup => {
        const row = setupElement("div", null, list);
        row.className = "hub-box";
        row.style.padding = "12px";
        row.draggable = true;
        row.ondragstart = () => { draggedSetupId = setup.id; };
        row.ondragover = event => event.preventDefault();
        row.ondrop = async event => {
            event.preventDefault();
            const from = currentSetups.findIndex(s => s.id === draggedSetupId);
            const to = currentSetups.findIndex(s => s.id === setup.id);
            if (from < 0 || from === to) return;
            const moving = currentSetups.splice(from, 1)[0];
            currentSetups.splice(to, 0, moving);
            renderSetups();
            await saveSetups();
        };
        setupElement("strong", setup.name + "  [" + (setup.shortcut || "") + "]", row);
        setupElement("p", (setup.actions || []).length + " steps", row);
        setupButton(row, "Launch", async () => {
            if (await saveSetups()) await hubConnection.invoke("LaunchSetup", setup.id);
        });
        setupButton(row, "Edit", () => editSetup(setup.id));
        setupButton(row, "Re-snapshot", () => snapshotSetup(setup.id));
        setupButton(row, "Copy", () => {
            const copy = JSON.parse(JSON.stringify(setup));
            copy.id = generateGuid(); copy.name += " (Copy)"; copy.shortcut = nextSetupShortcut(); copy.globalHotkey = "";
            currentSetups.push(copy); renderSetups(); editSetup(copy.id);
        });
        setupButton(row, "Delete", async () => {
            if (!confirm("Delete setup '" + setup.name + "'?")) return;
            currentSetups = currentSetups.filter(s => s.id !== setup.id);
            if (editingSetupId === setup.id) { editingSetupId = null; document.getElementById("setup-editor").hidden = true; }
            renderSetups(); await saveSetups();
        });
    });
}
function addNewSetup() {
    const setup = { id: generateGuid(), name: "New Setup", shortcut: nextSetupShortcut(), globalHotkey: "", actions: [] };
    currentSetups.push(setup);
    renderSetups(); editSetup(setup.id);
}
function setupField(parent, label, object, key, kind = "text") {
    const wrapper = setupElement("label", null, parent);
    wrapper.style.display = "block"; wrapper.style.margin = "8px 0";
    setupElement("span", label, wrapper).style.display = "block";
    const input = setupElement(kind === "textarea" ? "textarea" : "input", null, wrapper);
    input.style.width = kind === "checkbox" ? "auto" : "100%";
    if (kind !== "textarea") input.type = kind;
    if (kind === "checkbox") input.checked = object[key] === true;
    else input.value = object[key] ?? "";
    input.oninput = () => {
        object[key] = kind === "checkbox" ? input.checked : kind === "number" ? Number(input.value) : input.value;
    };
    return input;
}
async function editSetup(id) {
    editingSetupId = id;
    try { setupOptions = await hubConnection.invoke("GetSetupOptions"); } catch (error) { console.error(error); }
    const setup = currentSetups.find(s => s.id === id);
    if (!setup || editingSetupId !== id) return;
    const editor = document.getElementById("setup-editor");
    editor.hidden = false; editor.replaceChildren();
    setupElement("h3", "Edit setup", editor);
    setupField(editor, "Name", setup, "name");
    setupField(editor, "Sweep shortcut (WASD reserved; F1-F24 and Shift supported)", setup, "shortcut").maxLength = 12;
    setupField(editor, "Optional global hotkey", setup, "globalHotkey");
    setupElement("p", "Steps run in order. Terminal stays open; RunOnce exits. Scripts can read OMNI_CLIPBOARD.", editor);
    const typeNames = ["Folder", "Program", "Terminal", "Run once", "Omni action", "Macro"];
    setup.actions ||= [];
    setup.actions.forEach((action, index) => {
        action.layout ||= { useRatio: true, x: 0, y: 0, width: 800, height: 600, ratioX: 0, ratioY: 0, ratioWidth: .5, ratioHeight: .5 };
        const box = setupElement("div", null, editor);
        box.className = "hub-box"; box.style.padding = "12px"; box.style.marginBottom = "12px";
        const type = setupElement("select", null, box);
        typeNames.forEach((name, value) => { const option = setupElement("option", name, type); option.value = value; });
        type.value = action.type ?? 1;
        type.onchange = () => { action.type = Number(type.value); if (action.type === 2) action.reuseWindow = false; editSetup(id); };
        setupField(box, "Program / folder / Omni action / macro name", action, "path");
        setupField(box, "Arguments (JSON payload for Omni action)", action, "arguments");
        setupField(box, "Working directory", action, "workingDirectory");
        setupField(box, "Command (Terminal / Run once / inline Macro)", action, "command", "textarea");
        setupField(box, "Window title to match", action, "windowTitle");
        setupField(box, "Reuse existing window", action, "reuseWindow", "checkbox");
        setupField(box, "Restore position and size", action, "positionWindow", "checkbox");
        setupElement("span", "Target monitor", box);
        const monitor = setupElement("select", null, box);
        const saved = setupElement("option", "Saved monitor", monitor); saved.value = action.layout.monitorDevice || "";
        (setupOptions.monitors || []).forEach(m => { const option = setupElement("option", m.label, monitor); option.value = m.key; });
        monitor.value = action.layout.monitorDevice || "";
        monitor.onchange = () => {
            const selected = setupOptions.monitors.find(m => m.key === monitor.value);
            if (!selected) return;
            const old = action.layout, target = selected.layout;
            action.layout = { ...old, monitorDevice: target.monitorDevice, monitorIdentity: target.monitorIdentity,
                monitorPrimary: target.monitorPrimary, monitorX: target.monitorX, monitorY: target.monitorY,
                monitorWidth: target.monitorWidth, monitorHeight: target.monitorHeight,
                x: target.monitorX + old.ratioX * target.monitorWidth, y: target.monitorY + old.ratioY * target.monitorHeight,
                width: old.ratioWidth * target.monitorWidth, height: old.ratioHeight * target.monitorHeight };
            editSetup(id);
        };
        setupField(box, "Use proportions instead of exact pixels", action.layout, "useRatio", "checkbox");
        ["x", "y", "width", "height", "ratioX", "ratioY", "ratioWidth", "ratioHeight"].forEach(key => setupField(box, key, action.layout, key, "number"));
        setupElement("span", "OmniZones placement", box);
        const zone = setupElement("select", null, box);
        (setupOptions.zones || []).forEach(z => { const option = setupElement("option", z.label, zone); option.value = z.key; });
        zone.value = action.layout.zoneId ? action.layout.zoneLayoutId + "|" + action.layout.zoneId : "";
        zone.onchange = () => { const parts = zone.value.split("|"); action.layout.zoneLayoutId = parts[0] || ""; action.layout.zoneId = parts[1] || ""; };
        setupField(box, "Maximized", action.layout, "maximized", "checkbox");
        setupButton(box, "Up", () => { if (index > 0) { const step = setup.actions.splice(index, 1)[0]; setup.actions.splice(index - 1, 0, step); editSetup(id); } });
        setupButton(box, "Down", () => { if (index + 1 < setup.actions.length) { const step = setup.actions.splice(index, 1)[0]; setup.actions.splice(index + 1, 0, step); editSetup(id); } });
        setupButton(box, "Remove step", () => { setup.actions.splice(index, 1); editSetup(id); });
    });
    setupButton(editor, "+ Add step", () => {
        setup.actions.push({ id: generateGuid(), type: 1, path: "", arguments: "", command: "", workingDirectory: "", reuseWindow: true, positionWindow: true, isCaptured: false });
        editSetup(id);
    });
    setupButton(editor, "Save setup", async () => { if (await saveSetups()) renderSetups(); });
    setupButton(editor, "Close editor", () => { editingSetupId = null; editor.hidden = true; });
    editor.scrollIntoView({ behavior: "smooth", block: "start" });
}
async function saveSetups() {
    const used = new Set();
    for (const setup of currentSetups) {
        setup.shortcut = (setup.shortcut || nextSetupShortcut()).trim().toUpperCase();
        if (!setupShortcutKeys.includes(setup.shortcut) || used.has(setup.shortcut)) {
            alert("Each setup needs an unused key. W, A, S and D are reserved; F1-F24 and Shift are supported."); return false;
        }
        used.add(setup.shortcut);
    }
    try {
        await hubConnection.invoke("UpdateSetups", currentSetups);
        const settings = await hubConnection.invoke("GetSettings");
        currentSetups = settings.setups || []; currentHotkeys = settings.hotkeys;
        renderSetups(); renderHotkeys(); return true;
    } catch (error) { alert("Could not save setups: " + error.message); return false; }
}
async function snapshotSetup(id = null) {
    if (id && !(await saveSetups())) return;
    try { waitingForSnapshot = true; await hubConnection.invoke("RequestSetupSnapshot", id, false); }
    catch (error) { waitingForSnapshot = false; alert("Could not start snapshot: " + error.message); }
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

function renderAiModels(models, defaultModel) {
    const list = document.getElementById("ai-models-list");
    if (!list) return;
    list.innerHTML = "";
    
    // Always show a "None (Default)" option
    const noneRow = document.createElement("div");
    noneRow.className = "row";
    noneRow.style.gap = "8px";
    noneRow.innerHTML = `
        <input type="radio" name="default-model" value="" ${defaultModel === "" ? "checked" : ""} onchange="setDefaultAiModel('')">
        <label style="flex: 1; font-size: 13px; cursor: pointer;" onclick="this.previousElementSibling.click()">None (Default)</label>
    `;
    list.appendChild(noneRow);

    if (models && models.length > 0) {
        models.forEach((model) => {
            const row = document.createElement("div");
            row.className = "row";
            row.style.gap = "8px";
            row.innerHTML = `
                <input type="radio" name="default-model" value="${model}" ${defaultModel === model ? "checked" : ""} onchange="setDefaultAiModel('${model}')">
                <input type="text" value="${model}" style="flex: 1; font-size: 11px;" readonly>
                <button class="btn danger" onclick="removeAiModel('${model}')">Delete</button>
            `;
            list.appendChild(row);
        });
    }
}

async function setDefaultAiModel(model) {
    try {
        await hubConnection.invoke("SetDefaultAiModel", model);
        console.log("Default AI model updated:", model);
    } catch (err) {
        console.error("Error setting default AI model:", err);
        alert("Failed to set default AI model.");
    }
}

async function promptForAiModel() {
    const model = prompt("Enter AI model name (e.g. 'gemini-2.0-flash'):");
    if (!model) return;

    try {
        await hubConnection.invoke("AddAiModel", model);
        loadSettings();
    } catch (err) {
        console.error("Error adding AI model:", err);
        alert("Failed to add AI model.");
    }
}

async function removeAiModel(model) {
    if (!confirm(`Delete AI model '${model}'?`)) return;

    try {
        await hubConnection.invoke("RemoveAiModel", model);
        loadSettings();
    } catch (err) {
        console.error("Error removing AI model:", err);
        alert("Failed to remove AI model.");
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
