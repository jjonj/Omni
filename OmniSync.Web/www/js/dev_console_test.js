/**
 * Dev Console Unit Test
 * 
 * Verifies that the custom dev console captures console.log and runtime errors.
 */
async function runDevConsoleTest() {
    // Helper to log test progress to original console so we can debug the debugger
    const log = (...args) => window.console.log.apply(window.console, ["[TestRunner]", ...args]);
    const error = (...args) => window.console.error.apply(window.console, ["[TestRunner]", ...args]);

    log("Starting Dev Console Unit Test...");

    try {
        const consoleOutput = document.getElementById('console-output');
        const devConsole = document.getElementById('dev-console');
        const toggleBtn = document.getElementById('toggle-dev-console');

        if (!consoleOutput || !devConsole || !toggleBtn) {
            throw new Error("Console elements missing from DOM");
        }

        // Ensure console is visible for the test
        devConsole.classList.remove('hidden');

        // Step 1: Verify capture of console.log
        const testMessage = "VERIFY_LOG_CAPTURE_" + Math.random();
        console.log(testMessage);

        // Wait for DOM update
        await new Promise(r => setTimeout(r, 50));

        const lines = Array.from(consoleOutput.querySelectorAll('.log-line'));
        const foundLog = lines.some(l => l.innerText.includes(testMessage));
        
        if (!foundLog) {
            throw new Error("Failed to capture console.log in dev console");
        }
        log("SUCCESS: console.log capture verified.");

        // Step 2: Verify capture of runtime error
        log("Introducing intentional runtime error...");
        const errorMessage = "INTENTIONAL_TEST_ERROR_" + Math.random();
        
        // We use setTimeout to throw outside the try-catch of this test function
        // so window.onerror can catch it.
        setTimeout(() => {
            throw new Error(errorMessage);
        }, 10);

        // Wait for error to propagate
        await new Promise(r => setTimeout(r, 200));

        const linesAfterError = Array.from(consoleOutput.querySelectorAll('.log-line'));
        const foundError = linesAfterError.some(l => l.innerText.includes(errorMessage) && l.classList.contains('log-error'));

        if (!foundError) {
            throw new Error("Failed to capture runtime error in dev console");
        }
        log("SUCCESS: runtime error capture verified.");

        log("DEV CONSOLE UNIT TEST PASSED");
        console.log("%c DEV CONSOLE UNIT TEST PASSED ", "background: #22c55e; color: white; font-weight: bold;");

    } catch (err) {
        error("DEV CONSOLE UNIT TEST FAILED:", err.message);
        console.error("DEV CONSOLE UNIT TEST FAILED: " + err.message);
    }
}

// Auto-run if requested via URL param
if (window.location.search.includes('runtest=console')) {
    window.addEventListener('load', () => {
        setTimeout(runDevConsoleTest, 1000);
    });
}
