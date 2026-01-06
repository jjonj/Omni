(function(global) {
    const originalLog = console.log;
    const originalError = console.error;
    const originalWarn = console.warn;

    const addToConsole = (msg, type = 'log') => {
        const consoleOutput = document.getElementById('console-output');
        if (!consoleOutput) return;

        const line = document.createElement('div');
        line.className = `log-line ${type === 'error' ? 'log-error' : type === 'warn' ? 'log-warn' : ''}`;
        
        const time = new Date().toLocaleTimeString([], { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
        line.innerHTML = `<span class="log-time">[${time}]</span>${msg}`;
        
        consoleOutput.appendChild(line);
        consoleOutput.scrollTop = consoleOutput.scrollHeight;
    };
    
    console.log = (...args) => {
        originalLog(...args);
        addToConsole(args.map(a => typeof a === 'object' ? JSON.stringify(a) : a).join(' '));
    };
    
    console.error = (...args) => {
        originalError(...args);
        addToConsole(args.map(a => typeof a === 'object' ? JSON.stringify(a) : a).join(' '), 'error');
    };

    console.warn = (...args) => {
        originalWarn(...args);
        addToConsole(args.map(a => typeof a === 'object' ? JSON.stringify(a) : a).join(' '), 'warn');
    };
    
    window.onerror = (msg, url, lineNo, columnNo, error) => {
        addToConsole(`${msg} (at ${lineNo}:${columnNo})`, 'error');
        return false;
    };

    // Initialize toggle logic
    document.addEventListener('DOMContentLoaded', () => {
        const devConsole = document.getElementById('dev-console');
        const btnConsole = document.getElementById('toggle-dev-console');
        const btnClear = document.getElementById('btn-clear-console');
        
        if (btnConsole && devConsole) {
            btnConsole.addEventListener('click', (e) => {
                e.preventDefault();
                devConsole.classList.toggle('hidden');
                btnConsole.classList.toggle('active', !devConsole.classList.contains('hidden'));
            });
        }
        
        if (btnClear) {
            btnClear.addEventListener('click', () => { 
                const consoleOutput = document.getElementById('console-output');
                if(consoleOutput) consoleOutput.innerHTML = ''; 
            });
        }
    });
})(typeof self !== 'undefined' ? self : window);
