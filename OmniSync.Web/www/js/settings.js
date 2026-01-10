let hubConnection = null;
let currentHotkeys = [];
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
        renderHotkeys();
        renderExeMappings(settings.exeMappings);
        
        const status = await hubConnection.invoke("GetHubStatus");
        const cb = document.getElementById("run-on-startup");
        if (cb) cb.checked = status.isRunOnStartupEnabled;
    } catch (err) {
        console.error("Error loading settings:", err);
    }
}

function renderHotkeys() {
    const list = document.getElementById("hotkeys-list");
    list.innerHTML = "";
    
    currentHotkeys.forEach((hk, index) => {
        const row = document.createElement("div");
        row.className = "hotkey-row";
        
        row.innerHTML = `
            <div class="hotkey-name" onclick="editHotkeyInfo(${index})" style="cursor: pointer; text-decoration: underline;">${hk.name}</div>
            <input type="text" class="hotkey-input" id="hk-${index}" 
                value="${hk.key || 'Click to set'}" readonly 
                onclick="startRecording(${index})"
                onblur="setTimeout(stopRecording, 200)">
            <button class="btn danger" onclick="removeHotkey(${index})">Delete</button>
        `;
        list.appendChild(row);
    });
}

function addNewHotkey() {
    const name = prompt("Enter hotkey name (e.g., 'Sleep PC'):");
    if (!name) return;
    const action = prompt("Enter command action (e.g., 'SLEEP_PC', 'TOGGLE_MUTE', or full command):");
    if (!action) return;
    
    currentHotkeys.push({
        name: name,
        action: action,
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
        row.innerHTML = `
            <label style="width: 120px; font-family: var(--font-mono); font-size: 12px;">${key}</label>
            <input type="text" value="${path}" style="flex: 1; font-size: 11px;" readonly>
        `;
        list.appendChild(row);
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
