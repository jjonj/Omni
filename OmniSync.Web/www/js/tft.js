

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
        
        alert(Successfully imported  units.);
    } catch (err) {
        alert('Failed to import code: ' + err.message);
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
            document.execCommand('copy');
            alert('Code exported and copied to clipboard!');
        }
    } catch (err) {
        alert('Failed to export code: ' + err.message);
    }
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
        
        alert(\Successfully imported \ units.\);
    } catch (err) {
        alert('Failed to import code: ' + err.message);
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
            document.execCommand('copy');
            alert('Code exported and copied to clipboard!');
        }
    } catch (err) {
        alert('Failed to export code: ' + err.message);
    }
}
