/**
 * Shared Navigation Bar for OMNISYNC Web
 * Handles consistent navigation across all pages and automatic active state highlighting.
 */

document.addEventListener('DOMContentLoaded', () => {
    renderNav();
});

function renderNav() {
    try {
        const navContainer = document.querySelector('.nav-menu');
        if (!navContainer) return;

        // Get current filename to highlight active item
        const path = window.location.pathname;
        const page = path.split('/').pop() || 'index.html';
        const isSubfolder = path.includes('/IslandGenerator/') || path.includes('/Island2/');
        
        const root = isSubfolder ? '../' : '';

        const navItems = [
            { name: 'Hub', href: root + 'index.html' },
            { name: 'Scheduler', href: root + 'Scheduler.html' },
            { name: 'TFT', href: root + 'TFT.html' },
            { name: 'Test', href: root + 'Test.html' },
            { name: 'Settings', href: root + 'Settings.html' },
            { name: 'Island', href: (isSubfolder && path.includes('/IslandGenerator/') ? 'index.html' : root + 'IslandGenerator/index.html') },
            { name: 'Island2', href: (isSubfolder && path.includes('/Island2/') ? 'index.html' : root + 'Island2/index.html') }
        ];

        // Preserve existing version if it exists in the DOM
        const existingVersion = document.querySelector('.nav-version')?.textContent || '2026.01.21.21.02';

        let html = `<div class="nav-brand">OMNISYNC</div>`;
        
        navItems.forEach(item => {
            // Special logic for active state
            let isActive = false;
            if (isSubfolder) {
                if (item.name === 'Island' && path.includes('/IslandGenerator/')) isActive = true;
                if (item.name === 'Island2' && path.includes('/Island2/')) isActive = true;
            } else {
                // Remove query params for comparison
                const cleanPage = page.split('?')[0];
                const cleanHref = item.href.split('?')[0];
                if (cleanPage === cleanHref) isActive = true;
                // Handle root case
                if ((cleanPage === '' || cleanPage === 'index.html') && cleanHref === 'index.html') isActive = true;
            }

            html += `<a href="${item.href}" class="nav-item ${isActive ? 'active' : ''}">${item.name}</a>`;
        });

        html += `
            <span class="nav-version" style="margin-left: 10px; font-size: 10px; color: var(--text-dimmer); font-family: var(--font-mono); padding: 0 10px; align-self: center;">${existingVersion}</span>
            <button id="toggle-dev-console" class="nav-item" style="font-size: 10px; background: transparent; border: 1px solid var(--border);">Console</button>
        `;

        navContainer.innerHTML = html;

        // Attach listeners
        const toggleBtn = document.getElementById('toggle-dev-console');
        if (toggleBtn) {
            toggleBtn.addEventListener('click', () => {
                const console = document.getElementById('dev-console');
                if (console) console.classList.toggle('hidden');
            });
        }
    } catch (err) {
        console.error('Failed to render navigation:', err);
    }
}
