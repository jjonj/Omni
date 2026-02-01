
class TFTRoleTester {
    constructor(data) {
        this.data = data;
    }

    assert(condition, message) {
        if (!condition) throw new Error(message);
    }

    testUnitRolesAssigned() {
        console.log("[Test] Verifying all units have a role assigned...");
        const unitsWithoutRole = this.data.units.filter(u => !u.role);
        
        if (unitsWithoutRole.length > 0) {
            const names = unitsWithoutRole.map(u => u.name).join(', ');
            throw new Error(`The following ${unitsWithoutRole.length} units are missing a role: ${names}`);
        }
        console.log("[Test] All units have a role assigned.");
    }
}

// Global function to run the test
window.runRoleTest = async function() {
    if (!tftData) {
        console.error("tftData not loaded yet");
        return;
    }
    const tester = new TFTRoleTester(tftData);
    try {
        tester.testUnitRolesAssigned();
        console.log("%c[PASS] Role Test", "color: #32d74b; font-weight: bold;");
    } catch (err) {
        console.error("%c[FAIL] Role Test: " + err.message, "color: #ff453a; font-weight: bold;");
    }
};
