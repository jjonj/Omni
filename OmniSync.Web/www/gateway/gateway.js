'use strict';
// OmniHub Gateway: tools, OmniSweep setups/scenes/files and Tailscale URLs on one page.
// Dependency-free so it keeps working when the machine is offline.
const $ = id => document.getElementById(id);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const TYPES = ['Open folder','Run program','Terminal','Run once','Omni action','Macro'];
const EMPTY_ID = '00000000-0000-0000-0000-000000000000';
const storeKey = 'omnihub.gateway.v2';
const hue = text => { let h = 0; for (const c of String(text)) h = (h * 31 + c.charCodeAt(0)) >>> 0; return h % 360; };
const initials = name => String(name).split(/\s+/).filter(Boolean).slice(0,2).map(w => w[0]).join('').toUpperCase() || '?';
const stepTitle = step => step.path?.trim() || step.command?.trim() || TYPES[step.type] || 'Unnamed step';
// "D:\Apps\Obsidian.exe" -> "Obsidian"
const appLabel = step => {
  const raw = stepTitle(step);
  return (raw.split(/[\\/]/).filter(Boolean).pop() || raw).replace(/\.(exe|lnk|bat|cmd|ps1)$/i,'');
};
const readPreferences = () => {
  try {
    const parsed = JSON.parse(localStorage.getItem(storeKey) || '{}');
    return {pins: Array.isArray(parsed.pins) ? parsed.pins : [], recent: Array.isArray(parsed.recent) ? parsed.recent : [],
      more: Array.isArray(parsed.more) ? parsed.more.filter(id => typeof id === 'string') : []};
  } catch { return {pins:[],recent:[],more:[]}; }
};
const prefs = readPreferences();
const save = () => { try { localStorage.setItem(storeKey, JSON.stringify(prefs)); } catch {} };
const isPinned = id => prefs.pins.includes(id);
const isInMore = id => prefs.more.includes(id);
let moreExpanded = false;
const lastOpened = id => prefs.recent.find(r => r.id === id)?.time || 0;
const recentWindow = 7 * 24 * 60 * 60 * 1000;
const isRecent = id => !isPinned(id) && lastOpened(id) > Date.now() - recentWindow;
const priorityClass = id => isPinned(id) ? 'priority-pinned' : isRecent(id) ? 'priority-recent' : '';
const priorityRank = id => isPinned(id) ? 0 : isRecent(id) ? 1 : 2;
const portOf = url => { try { return new URL(url).port; } catch { return ''; } };
const isPublicHub = location.protocol === 'https:' && location.hostname === 'hub.rancorous.online';
const displayStatus = status => ({running:'Running',starting:'Starting…',error:'Needs attention',stopped:'Offline'}[status] || status);
const matches = (query, ...fields) => !query || fields.join(' ').toLocaleLowerCase().includes(query);
const timeFmt = {month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'};
const shortcut = id => location.origin + '/' + encodeURIComponent(id);

const state = {tools:[],snapshot:{revision:0,setups:[],macros:[],calendar:{}},network:null,
  initialized:false,request:0,hot:0,items:[],detailsId:null,fileItems:[],queryId:0,
  editing:null,editingRevision:0,editingChanged:false,busy:false,syncFailures:0,typeFilter:'all'};
let refreshTimer, networkTimer, fileTimer, toastTimer;
const editor = $('setup-editor');
const query = () => $('search').value.trim().toLocaleLowerCase();

function toast(message) {
  clearTimeout(toastTimer);
  $('toast').textContent = message;
  $('toast').hidden = false;
  toastTimer = setTimeout(() => { $('toast').hidden = true; }, 2600);
}
function markOpened(id) {
  prefs.recent = [{id,time:Date.now()},...prefs.recent.filter(r => r.id !== id)].slice(0,20);
  save();
  // Defer DOM replacement so a clicked tool link can finish navigation.
  setTimeout(() => { renderTools(); renderSetups(); renderItems(); }, 0);
}
function pin(id) {
  const willPin = !isPinned(id);
  prefs.pins = willPin ? [...prefs.pins,id] : prefs.pins.filter(x => x !== id);
  // Pinning a tucked-away tool promotes it to the main grid.
  if (willPin) prefs.more = prefs.more.filter(x => x !== id);
  save();
  renderTools();
  renderSetups();
  renderItems();
  toast(willPin ? 'Pinned' : 'Unpinned');
}
function moveTool(id) {
  if (!state.tools.some(t => t.id === id)) return;
  const toMore = !isInMore(id);
  prefs.more = toMore ? [...prefs.more,id] : prefs.more.filter(x => x !== id);
  if (toMore) prefs.pins = prefs.pins.filter(x => x !== id);
  save();
  renderTools();
  renderItems();
  toast(toMore ? 'Moved to More' : 'Moved to main tools');
}
function setConnection(online) {
  $('service-dot').className = 'dot ' + (online ? 'ok' : 'bad');
  $('conn').title = online ? 'Gateway reachable' : 'Gateway data unavailable';
}
const serviceLastOk = {};
let probingServices = false;
async function pollServiceHealth() {
  if (probingServices || document.hidden) return;
  probingServices = true;
  try {
    const response = await fetchBounded('/gateway/api/connections');
    if (!response.ok) throw new Error('Service diagnostics unavailable');
    const data = await response.json();
    if (!Array.isArray(data.services)) throw new Error('Unexpected service status response');
    const now = Date.now();
    const services = data.services.map(service => {
      const status = service.id === 'proxy'
        ? (location.hostname === 'hub.rancorous.online' ? 'online' : 'unknown')
        : service.status;
      if (status === 'online') serviceLastOk[service.id] = now;
      const last = serviceLastOk[service.id];
      const freshness = last ? 'Last OK ' + new Date(last).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit',second:'2-digit'}) : 'No successful check yet';
      return `<div class="service-row" title="${esc(service.reason || freshness)}"><span class="dot ${status === 'online' ? 'ok' : status === 'offline' ? 'bad' : 'unknown'}"></span><span>${esc(service.name)}</span><small>${esc(status)} · ${esc(freshness)}</small></div>`;
    });
    $('service-menu').innerHTML = services.join('');
  } catch {
    $('service-menu').textContent = 'Diagnostics unavailable; service states unverified.';
  } finally {
    probingServices = false;
  }
}
async function fetchBounded(url, options = {}) {
  const controller = new AbortController();
  const duration = options.method === 'POST' || options.method === 'PUT' ? 18000 : 9000;
  let timedOut = false;
  const timeout = setTimeout(() => { timedOut = true; controller.abort(); }, duration);
  try {
    const response = await fetch(url, {cache:'no-store', ...options, signal:controller.signal});
    if (response.redirected && /\/__rancorous_login(?:[?#]|$)/.test(response.url)) {
      throw new Error('Login session expired. Sign in again at hub.rancorous.online.');
    }
    return response;
  } catch (error) {
    if (timedOut) throw new Error('OmniHub did not respond within ' + (duration/1000) + ' seconds; retrying automatically.');
    if (error instanceof TypeError) throw new Error('Network connection failed. Check OmniHub or your internet connection; retrying automatically.');
    throw error;
  } finally { clearTimeout(timeout); }
}
async function request(path, method = 'GET', data) {
  const options = {method, headers:{}};
  if (data !== undefined) {
    options.headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(data);
  }
  const response = await fetchBounded('/gateway/api/sweep' + path, options);
  const text = await response.text();
  let result;
  try { result = text ? JSON.parse(text) : null; } catch { result = {error:'OmniHub returned invalid JSON (HTTP '+response.status+')'}; }
  if (!response.ok) {
    const e = new Error(result?.error || 'Request failed: HTTP ' + response.status);
    e.status = response.status;
    throw e;
  }
  return result;
}

// ---------- Tools ----------
function toolTile(t, tucked = false) {
  const port = portOf(t.url);
  const sub = isPublicHub && t.remote?.supported === false
    ? `<span class="err" title="${esc(t.remote.reason)}">Local only · ${esc(t.remote.reason)}</span>`
    : t.status === 'error' && t.error
    ? `<span class="err">${esc(t.error)}</span>`
    : `<i class="dot ${esc(t.status)}"></i>${esc(t.status === 'stopped' ? 'Offline · starts on open' : displayStatus(t.status))}${!isPublicHub && port ? ' · :' + esc(port) : ''}`;
  const pinLabel = (isPinned(t.id) ? 'Unpin ' : 'Pin ') + t.name;
  return `<article class="tile ${esc(t.status)} ${tucked ? 'in-more' : priorityClass(t.id)}" data-tool-id="${esc(t.id)}">
    <a class="tile-main" href="/${encodeURIComponent(t.id)}" target="_blank" rel="noopener" data-open="${esc(t.id)}" data-item title="${esc(t.description)}">
      <span class="mono" style="--h:${hue(t.id)}">${esc(initials(t.name))}</span>
      <span class="tile-text"><span class="tile-name">${isPinned(t.id) ? '<span class="pin-star" aria-label="Pinned">★</span> ' : ''}${esc(t.name)}</span><span class="tile-sub">${sub}</span></span>
      <span class="tile-go" aria-hidden="true">↗</span>
    </a>
    <div class="tile-tools">
      <button class="icon-btn pin ${isPinned(t.id)?'pinned':''}" data-pin="${esc(t.id)}" type="button" title="${esc(pinLabel)}" aria-label="${esc(pinLabel)}" aria-pressed="${isPinned(t.id)}">★</button>
      <button class="icon-btn" type="button" data-tool-menu="${esc(t.id)}" title="Tool options (or right-click tile)" aria-label="Options for ${esc(t.name)}">⋯</button>
    </div>
  </article>`;
}
function renderTools() {
  const q = query();
  const matched = state.tools.filter(t => matches(q,t.name,t.description,t.id,portOf(t.url)));
  const main = matched.filter(t => !isInMore(t.id))
    .sort((a,b) => priorityRank(a.id)-priorityRank(b.id) ||
      (isRecent(a.id) ? lastOpened(b.id)-lastOpened(a.id) : a.name.localeCompare(b.name)));
  const tucked = matched.filter(t => isInMore(t.id)).sort((a,b) => a.name.localeCompare(b.name));
  // Search can find everything, including collapsed More items.
  const visibleTucked = q || moreExpanded ? tucked : [];
  const moreCount = state.tools.filter(t => isInMore(t.id)).length;
  const moreTile = `<button type="button" class="tile tile-more" data-toggle-more
    aria-expanded="${moreExpanded}" aria-label="${moreExpanded ? 'Collapse' : 'Expand'} More tools">
    <span class="mono more-mono" aria-hidden="true">···</span>
    <span class="tile-text"><span class="tile-name">More</span>
      <span class="tile-sub">${moreCount ? moreCount + ' tucked-away tool' + (moreCount===1?'':'s') : 'Move tools here via right-click'}</span></span>
    <span class="more-chevron" aria-hidden="true">${moreExpanded ? '▴' : '▾'}</span>
  </button>`;
  $('tools').innerHTML = (main.length + visibleTucked.length
    ? main.map(t => toolTile(t)).join('') + visibleTucked.map(t => toolTile(t,true)).join('')
    : q ? '<div class="empty">No matching tools.</div>'
    : !state.initialized ? '<div class="empty">Checking registered tools…</div>' : '') + moreTile;
  $('tools-count').textContent = q ? `${matched.length}/${state.tools.length}` :
    (state.tools.length ? `${state.tools.length - moreCount} visible${moreCount ? ' · '+moreCount+' in More' : ''}` : '');
  return main.length + visibleTucked.length;
}

async function prepareToolDeletion(id) {
  const tool = state.tools.find(t => t.id === id);
  if (!tool) return;
  if (!confirm('Stop the verified ' + tool.name + ' server and open its folder in Fomni?\nNo files will be deleted.')) return;
  try {
    const response = await fetchBounded('/gateway/api/tools/' + encodeURIComponent(id) + '/prepare-deletion', {method:'POST'});
    const result = await response.json();
    if (!response.ok) throw new Error(result.error || 'Could not prepare tool for deletion');
    toast(result.stopped ? 'Server stopped. Folder opened in Fomni.' : 'Server was offline. Folder opened in Fomni.');
    void poll();
  } catch (error) { toast('Prepare deletion failed: ' + error.message); }
}
function hideToolMenu() {
  $('tool-context-menu').hidden = true;
}
function showToolMenu(id,x,y) {
  const tool = state.tools.find(t => t.id === id);
  if (!tool) return;
  const menu = $('tool-context-menu');
  menu.dataset.toolId = id;
  menu.innerHTML = `
    <button type="button" role="menuitem" data-tool-action="move">${isInMore(id) ? 'Move to Main' : 'Move to More'}</button>
    <button type="button" role="menuitem" data-tool-action="pin">${isPinned(id) ? 'Unpin' : 'Pin to top'}</button>
    <button type="button" role="menuitem" data-tool-action="details">Details and logs</button>
    <button type="button" role="menuitem" data-tool-action="prepare-deletion">Prepare deletion...</button>`;
  menu.hidden = false;
  menu.style.left = Math.max(8,Math.min(x,window.innerWidth-menu.offsetWidth-8))+'px';
  menu.style.top = Math.max(8,Math.min(y,window.innerHeight-menu.offsetHeight-8))+'px';
  menu.querySelector('button')?.focus({preventScroll:true});
}
document.addEventListener('contextmenu',event => {
  const tile = event.target.closest('.tile[data-tool-id]');
  if (!tile) { hideToolMenu(); return; }
  event.preventDefault();
  showToolMenu(tile.dataset.toolId,event.clientX,event.clientY);
});
document.addEventListener('keydown',event => {
  if (event.key === 'Escape' && !$('tool-context-menu').hidden) {
    hideToolMenu();
    event.stopPropagation();
  }
});
window.addEventListener('scroll',hideToolMenu,true);
window.addEventListener('resize',hideToolMenu);

let tooltipAnchor = null;
function hideSetupTooltip() {
  $('setup-tooltip').hidden = true;
  tooltipAnchor = null;
}
function showSetupTooltip(anchor) {
  const setup=state.snapshot.setups.find(s=>s.id===anchor.dataset.previewSetup);
  if (!setup) return;
  const steps=setup.actions || [];
  const descriptions = steps.map((action,index)=>
    `<li><strong>${index+1}. ${esc(TYPES[action.type]||'Action')}</strong><span>${esc(stepTitle(action))}</span></li>`).join('');
  const tooltip=$('setup-tooltip');
  tooltip.innerHTML = `<strong class="tooltip-title">${esc(setup.name)}</strong>
    <div class="tooltip-meta">${steps.length} ${steps.length===1?'action':'actions'} · ${setup.shortcut?esc(setup.shortcut):'No shortcut'}</div>
    <ol>${descriptions||'<li>No actions configured</li>'}</ol>
    <div class="tooltip-meta">Dependencies: not specified · Live run state: not tracked</div>`;
  tooltip.hidden = false;
  tooltipAnchor=anchor;
  const rect=anchor.getBoundingClientRect();
  const width=tooltip.getBoundingClientRect().width;
  const height=tooltip.getBoundingClientRect().height;
  tooltip.style.left=Math.max(8,Math.min(window.innerWidth-width-8,rect.left))+'px';
  tooltip.style.top=(rect.bottom+height+8<window.innerHeight?rect.bottom+8:Math.max(8,rect.top-height-8))+'px';
}
document.addEventListener('pointerover',event => {
  if (event.pointerType==='touch') return;
  const anchor=event.target.closest('[data-preview-setup]');
  if (anchor && tooltipAnchor!==anchor) showSetupTooltip(anchor);
});
document.addEventListener('pointerout',event => {
  const anchor=event.target.closest('[data-preview-setup]');
  if (anchor && !anchor.contains(event.relatedTarget)) hideSetupTooltip();
});
document.addEventListener('focusin',event => {
  const anchor=event.target.closest('[data-preview-setup]');
  if (anchor) showSetupTooltip(anchor);
});
document.addEventListener('focusout',event => {
  if (event.target.closest('[data-preview-setup]')) hideSetupTooltip();
});
document.addEventListener('pointerdown',event => {
  if (!event.target.closest('[data-preview-setup]') && !event.target.closest('#setup-tooltip')) hideSetupTooltip();
});
window.addEventListener('scroll',hideSetupTooltip,true);
window.addEventListener('resize',hideSetupTooltip);
// ---------- Setups ----------
function setupRow(s) {
  const index = state.snapshot.setups.findIndex(t => t.id === s.id);
  const count = state.snapshot.setups.length;
  const steps = s.actions || [];
  const shown = steps.slice(0,8);
  const apps = shown.map(a => `<span class="app t${Number(a.type)}" title="${esc(TYPES[a.type] + ': ' + stepTitle(a))}">${esc(appLabel(a))}</span>`).join('') +
    (steps.length > shown.length ? `<span class="app more">+${steps.length - shown.length}</span>` : '') +
    (steps.length ? '' : '<span class="app more">No steps — edit to add some</span>');
  return `<article class="setup-row ${priorityClass(s.id)}" data-setup="${esc(s.id)}" draggable="true">
    <span class="grip" title="Drag to reorder" aria-hidden="true">⋮⋮</span>
    <span class="key ${s.shortcut ? '' : 'none'}" title="OmniSweep shortcut">${s.shortcut ? esc(s.shortcut) : '—'}</span>
    <div class="setup-info"><div class="setup-heading"><span class="setup-name">${esc(s.name)}</span><button class="setup-preview" type="button" data-preview-setup="${esc(s.id)}" aria-describedby="setup-tooltip" title="Show setup actions" aria-label="Details for ${esc(s.name)}">ⓘ</button></div><div class="apps">${apps}</div></div>
    <div class="setup-actions">
      <button type="button" class="icon-btn pin ${isPinned(s.id)?'pinned':''}" data-pin="${esc(s.id)}" aria-pressed="${isPinned(s.id)}" title="${isPinned(s.id)?'Unpin':'Pin'} ${esc(s.name)}" aria-label="${isPinned(s.id)?'Unpin':'Pin'} ${esc(s.name)}">★</button>
      <button type="button" class="icon-btn" data-move="${esc(s.id)}" data-direction="-1" title="Move up" aria-label="Move up" ${index === 0 ? 'disabled' : ''}>↑</button>
      <button type="button" class="icon-btn" data-move="${esc(s.id)}" data-direction="1" title="Move down" aria-label="Move down" ${index === count - 1 ? 'disabled' : ''}>↓</button>
      <button type="button" class="icon-btn" data-snapshot="${esc(s.id)}" title="Re-capture window layout" aria-label="Re-capture windows">▣</button>
      <button type="button" class="btn" data-edit="${esc(s.id)}">Edit</button>
      <button type="button" class="btn primary run-btn" data-run-setup="${esc(s.id)}" data-name="${esc(s.name)}" data-item title="Run ${esc(s.name)}">▶ Run</button>
    </div></article>`;
}
function renderSetups() {
  const q = query();
  const setups = state.snapshot.setups.filter(s => matches(q, s.name, s.shortcut, ...(s.actions||[]).map(stepTitle)));
  $('setups-count').textContent = q ? `${setups.length}/${state.snapshot.setups.length}` : String(state.snapshot.setups.length || '');
  $('setups-grid').innerHTML = setups.length ? setups.map(setupRow).join('') :
    `<div class="empty">${state.snapshot.setups.length ? 'No matching setups.' : 'No setups yet — create one or capture your open windows.'}</div>`;
  return setups.length;
}

// ---------- Scenes & macros ----------
const macroChip = m => `<button type="button" class="chip" data-run-macro="${esc(m.id)}" data-name="${esc(m.name)}" data-item title="Run ${esc(m.name)}"><span class="glyph" aria-hidden="true">${m.isScene ? '◈' : '⌘'}</span>${esc(m.name)}</button>`;
function renderMacros() {
  const q = query();
  const macros = state.snapshot.macros.filter(m => matches(q, m.name, m.category)).sort((a,b) => a.name.localeCompare(b.name));
  const scenes = macros.filter(m => m.isScene), others = macros.filter(m => !m.isScene);
  $('scenes-grid').innerHTML = scenes.length ? scenes.map(macroChip).join('') : '<span class="muted">No matching scenes.</span>';
  $('macros-grid').innerHTML = others.length ? others.map(macroChip).join('') : '<span class="muted">No matching macros.</span>';
  $('scene-count').textContent = q ? scenes.length : String(state.snapshot.macros.filter(m => m.isScene).length || '');
  $('macro-count').textContent = q ? others.length : String(state.snapshot.macros.filter(m => !m.isScene).length || '');
  $('scenes').hidden = !scenes.length && !!q;
  $('macros-title').hidden = !others.length && !!q;
  $('macros-grid').hidden = !others.length && !!q;
  return macros.length;
}

// ---------- Calendar strip ----------
function renderCalendar() {
  const c = state.snapshot.calendar || {};
  const events = [];
  if (Array.isArray(c.ongoing)) for (const event of c.ongoing) events.push({label:'NOW',kind:'now',...event});
  if (c.next) events.push({label:'NEXT',kind:'next',...c.next});
  $('agenda').hidden = events.length === 0;
  $('agenda').innerHTML = events.map(e =>
    `<span class="agenda-item ${e.kind}"><span class="agenda-tag">${esc(e.label)}</span><strong>${esc(e.summary)}</strong><time>${esc(e.isAllDay ? 'all day' : new Date(e.start).toLocaleString([],timeFmt))}</time></span>`
  ).join('');
}

// ---------- Files ----------
async function lookupFiles() {
  const text = $('search').value.trim();
  const id = ++state.queryId;
  $('files').hidden = text.length < 2 || !['all','files'].includes(state.typeFilter);
  if (text.length < 2) { state.fileItems = []; $('file-results').innerHTML = ''; return; }
  $('file-results').innerHTML = '<div class="file-pending">Searching project roots…</div>';
  try {
    const result = await request('/search?query=' + encodeURIComponent(text));
    if (id !== state.queryId) return;
    state.fileItems = Array.isArray(result) ? result : [];
    $('file-results').innerHTML = state.fileItems.length ? state.fileItems.map((f,i) =>
      `<button type="button" class="file-result ${f.isFile ? 'file' : 'dir'}" data-file="${i}" data-item title="Open ${esc(f.path)}">
        <span class="file-icon" aria-hidden="true">${f.isFile ? '▤' : '▸'}</span>
        <span class="file-text"><strong>${esc(f.name)}</strong><small>${esc(f.path)}</small></span>
        <span class="muted" aria-hidden="true">↗</span></button>`).join('') :
      '<div class="file-pending">No files or folders match.</div>';
    renderItems();
  } catch (e) {
    if (id !== state.queryId) return;
    $('file-results').textContent = 'Search unavailable: ' + e.message;
  }
}
async function openFile(index, button) {
  const file = state.fileItems[index];
  if (!file) return;
  button.disabled = true;
  try {
    await request('/search/open', 'POST', {query: $('search').value.trim(), path: file.path});
    toast('Opened ' + file.name);
  } catch (e) { toast(e.message); }
  finally { button.disabled = false; }
}

// ---------- Tailscale ----------
function copyButton(value, label) {
  return `<button type="button" class="icon-btn copy" data-copy="${esc(value)}" title="Copy ${esc(label)}" aria-label="Copy ${esc(label)}">⧉</button>`;
}
function renderNetwork() {
  const n = state.network;
  const body = $('network-body');
  if (!n) { body.innerHTML = '<div class="empty">Checking Tailscale…</div>'; return; }
  if (n.unavailable) { body.innerHTML = `<div class="empty">${esc(n.unavailable)}</div>`; $('network-status').textContent = ''; return; }
  const self = n.self || {};
  $('network-status').textContent = self.online ? 'online' : (n.backend || 'offline');
  const q = query();
  const urls = (n.urls || []).filter(u => matches(q, u.url, ...(u.routes || []).map(r => r.path + ' ' + r.target)));
  const devices = (n.devices || []).filter(d => matches(q, d.name, d.dns, d.ip, d.os));
  const urlRows = urls.length ? urls.map(u => `
    <div class="net-row">
      <a class="net-url" href="${esc(u.url)}" target="_blank" rel="noopener">${esc(u.url.replace(/^https:\/\//,''))} <span aria-hidden="true">↗</span></a>
      <span class="badge ${u.funnel ? 'public' : 'tailnet'}" title="${u.funnel ? 'Reachable from the public internet (Funnel)' : 'Reachable only from your tailnet (Serve)'}">${u.funnel ? 'Public' : 'Tailnet'}</span>
      <span class="net-routes">${(u.routes || []).map(r => `<span class="route"><code>${esc(r.path)}</code> → <span class="muted">${esc(r.target.replace(/^https?:\/\//,''))}</span></span>`).join('')}</span>
      ${copyButton(u.url, u.url)}
    </div>`).join('') : `<p class="muted net-empty">${n.urls?.length ? 'No hosted URL matches.' : 'Nothing is hosted yet. Use <code>tailscale serve</code> or <code>tailscale funnel</code> to publish a local port.'}</p>`;
  const deviceChips = devices.length ? devices.map(d =>
    `<button type="button" class="device" data-copy="${esc(d.dns || d.ip)}" title="${esc((d.dns || d.name) + (d.ip ? ' · ' + d.ip : '') + ' · ' + (d.os || '') + ' · click to copy')}"><i class="dot ${d.online ? 'ok' : ''}"></i>${esc(d.name)}</button>`).join('') :
    '<span class="muted">No other devices match.</span>';
  body.innerHTML = `
    <div class="net-self">
      <span class="net-name"><i class="dot ${self.online ? 'ok' : 'bad'}"></i>${esc(self.name || 'This PC')}</span>
      <code>${esc(self.dns || '')}</code>
      <code>${esc(self.ip || '')}</code>
      ${self.dns ? copyButton(self.dns, 'DNS name') : ''}
    </div>
    <h3 class="sub-head">Hosted URLs <span class="count">${urls.length}</span></h3>
    <div class="net-list">${urlRows}</div>
    <h3 class="sub-head">Devices <span class="count">${devices.length}/${(n.devices || []).length}</span></h3>
    <div class="devices">${deviceChips}</div>`;
}
async function loadNetwork() {
  try {
    const response = await fetchBounded('/gateway/api/tailscale');
    if (response.status === 404) {
      state.network = {unavailable: 'Tailscale status needs a Hub restart to load. Redeploy the Hub to enable it.'};
    } else if (!response.ok) {
      throw new Error('HTTP ' + response.status);
    } else {
      const data = await response.json();
      state.network = data.available ? data : {unavailable: data.error || 'Tailscale is not running on this PC.'};
    }
  } catch (e) {
    // Keep the last good snapshot during a transient failure.
    if (!state.network || state.network.unavailable) state.network = {unavailable: 'Cannot read Tailscale status: ' + e.message + '.'};
  }
  renderNetwork();
}

const searchablePages = [
  {name:'Gateway',url:'/gateway/',description:'Tools and setups'},
  {name:'Presentations',url:'/presentations/',description:'Review and share presentations'},
  {name:'Scheduler',url:'/Scheduler.html',description:'Archived scheduler'},
  {name:'Settings',url:'/Settings.html',description:'OmniHub settings'},
  {name:'TFT',url:'/TFT.html',description:'TFT utility'}
];
function renderPages() {
  const q = query();
  const allowed = state.typeFilter === 'all' || state.typeFilter === 'pages';
  const pages = allowed && q ? searchablePages.filter(p => matches(q,p.name,p.description)) : [];
  $('pages').hidden = !pages.length;
  $('page-results').innerHTML = pages.map(p => `<a class="chip" data-item href="${esc(p.url)}" title="${esc(p.description)}">${esc(p.name)}</a>`).join('');
}
// ---------- Rendering & keyboard hot item ----------
function render() {
  renderTools();
  renderSetups();
  renderMacros();
  renderPages();
  const filter = state.typeFilter, q = query();
  $('search-filters').hidden = !q;
  $('search-filters').querySelectorAll('[data-filter]').forEach(button => {
    button.classList.toggle('selected', button.dataset.filter === filter);
    button.setAttribute('aria-pressed',button.dataset.filter === filter);
  });
  $('tools-section').hidden = filter !== 'all' && filter !== 'tools';
  $('setups').hidden = filter !== 'all' && filter !== 'setups';
  $('scenes').hidden = !['all','scenes','macros'].includes(filter) || (!state.snapshot.macros.some(m => matches(q,m.name,m.category)) && !!q);
  $('scenes-title').hidden = filter === 'macros';
  $('scenes-grid').hidden = filter === 'macros';
  $('macros-title').hidden = filter === 'scenes';
  $('macros-grid').hidden = filter === 'scenes';
  if (filter !== 'all' && filter !== 'files') $('files').hidden = true;
  renderCalendar();
  renderNetwork();
  if (editor.open && state.editing && state.snapshot.revision !== state.editingRevision) {
    state.editingChanged = true;
    $('edit-warning').hidden = false;
    $('save-setup').disabled = true;
  }
  renderItems();
}
function renderItems() {
  state.items = Array.from(document.querySelectorAll('[data-item]')).filter(el => el.offsetParent !== null || el.getClientRects().length);
  state.hot = Math.min(state.hot, Math.max(0, state.items.length - 1));
  $('no-results').hidden = !query() || state.items.length > 0;
  paintHot();
}
function paintHot() {
  const active = !!query() && state.items.length > 0;
  state.items.forEach((el, i) => {
    const row = el.closest('.tile, .setup-row, .file-result') || el;
    row.classList.toggle('hot', active && i === state.hot);
  });
  if (active) state.items[state.hot]?.scrollIntoView({block:'nearest'});
}

// ---------- Sync ----------
async function loadAll() {
  const seq = ++state.request;
  $('refresh').disabled = true;
  const [toolsResult, sweepResult, remoteResult] = await Promise.allSettled([
    fetchBounded('/gateway/api/tools').then(async r => {
      if (!r.ok) throw new Error(r.status === 401 || r.status === 403 ? 'Sign-in required (HTTP ' + r.status + ')' : 'OmniHub returned HTTP ' + r.status);
      const data = await r.json();
      if (!Array.isArray(data)) throw new Error('OmniHub returned an invalid tools list');
      return data;
    }),
    request(''),
    isPublicHub ? fetchBounded('/gateway/api/remote-tools').then(async r => {
      if (!r.ok) throw new Error('Remote proxy unavailable');
      return r.json();
    }) : Promise.resolve({}),
  ]);
  if (seq !== state.request) return;
  const problems = [];
  if (toolsResult.status === 'fulfilled') {
    const remote = remoteResult.status === 'fulfilled' ? remoteResult.value : {};
    state.tools = toolsResult.value.map(t => ({...t, remote: isPublicHub
      ? remote[t.id] || {supported:false,reason:'Authenticated HTTPS route unavailable.'} : null}));
    state.initialized = true;
  }
  else problems.push('tools (' + toolsResult.reason.message + ')');
  if (sweepResult.status === 'fulfilled' && sweepResult.value && Array.isArray(sweepResult.value.setups) && Array.isArray(sweepResult.value.macros)) {
    state.snapshot = sweepResult.value;
  } else if (sweepResult.status === 'rejected') problems.push('setups (' + sweepResult.reason.message + ')');
  const ok = toolsResult.status === 'fulfilled' || sweepResult.status === 'fulfilled';
  state.syncFailures = problems.length ? Math.min(16,state.syncFailures+1) : 0;
  setConnection(ok);
  $('error').hidden = !problems.length;
  if (problems.length) $('error-text').textContent = 'Cannot load ' + problems.join(' and ') + '. Last known data remains visible; retrying automatically.';
  if (ok) $('last-updated').textContent = new Date().toLocaleTimeString([],{hour:'2-digit',minute:'2-digit',second:'2-digit'});
  if (!state.initialized) $('tools').innerHTML = '<div class="empty">Cannot reach the local Hub. Check that OmniHub is running.</div>';
  render();
  $('refresh').disabled = false;
}
let polling = false;
async function poll() {
  clearTimeout(refreshTimer);
  if (polling) return;
  polling = true;
  try {
    if (!document.hidden) await loadAll();
  } catch (error) {
    state.syncFailures = Math.min(16,state.syncFailures+1);
    setConnection(false);
    $('error').hidden = false;
    $('error-text').textContent = 'Connection interrupted. Retrying automatically; previously loaded data is retained.';
  } finally {
    polling = false;
    // Retry quickly after a failure, then back off to avoid a reconnect storm.
    const delay = state.syncFailures ? Math.min(30000,2500*Math.pow(1.5,state.syncFailures-1)) : 5000;
    refreshTimer = setTimeout(poll,delay);
  }
}
async function pollNetwork() {
  clearTimeout(networkTimer);
  if (!document.hidden) await loadNetwork();
  networkTimer = setTimeout(pollNetwork, 30000);
}
async function mutate(path, method, data, success) {
  if (state.busy) return false;
  state.busy = true;
  try {
    await request(path, method, data);
    await loadAll();
    if (success) toast(success);
    return true;
  } catch (e) {
    if (e.status === 409) await loadAll();
    toast(e.message);
    return false;
  } finally { state.busy = false; }
}

// ---------- Runs ----------
async function run(kind, id, button) {
  const name = button?.dataset.name || '';
  if (button) button.disabled = true;
  try {
    await request('/' + kind + '/' + encodeURIComponent(id) + '/run', 'POST');
    markOpened(id);
    toast((kind === 'setups' ? 'Starting ' : 'Ran ') + name);
    const row = button?.closest('.setup-row, .chip') || button;
    row?.classList.add('ran');
    setTimeout(() => row?.classList.remove('ran'), 1200);
  } catch (e) { toast(e.message); }
  finally { if (button) button.disabled = false; }
}
async function captureWindows(id) {
  if (!confirm('Start the native OmniSweep window snapshot? Your PC will enter window capture mode.')) return;
  try {
    await request('/snapshot', 'POST', {id: id || null, replace: false});
    toast('Window capture started on the PC');
  } catch (e) { toast(e.message); }
}
async function moveSetup(id, direction) {
  const ids = state.snapshot.setups.map(s => s.id);
  const index = ids.indexOf(id), next = index + direction;
  if (index < 0 || next < 0 || next >= ids.length) return;
  [ids[index], ids[next]] = [ids[next], ids[index]];
  await mutate('/order', 'PUT', {revision: state.snapshot.revision, ids}, 'Order saved');
}

// ---------- New setup ----------
function newSetup() {
  $('new-name').value = '';
  $('new-dialog').showModal();
  $('new-name').focus();
}
async function createSetup(event) {
  event.preventDefault();
  const name = $('new-name').value.trim();
  if (!name) return;
  $('new-dialog').close();
  const before = new Set(state.snapshot.setups.map(s => s.id));
  if (await mutate('/setups', 'POST', {revision: state.snapshot.revision, name}, 'Created ' + name)) {
    const created = state.snapshot.setups.find(s => !before.has(s.id));
    if (created) openEditor(created.id);
  }
}

// ---------- Setup editor ----------
function openEditor(id) {
  const found = state.snapshot.setups.find(s => s.id === id);
  if (!found) return;
  state.editing = JSON.parse(JSON.stringify(found));
  state.editingRevision = state.snapshot.revision;
  state.editingChanged = false;
  $('editor-title').textContent = found.name;
  $('edit-name').value = found.name;
  $('edit-shortcut').value = found.shortcut || '';
  $('edit-warning').hidden = true;
  $('save-setup').disabled = false;
  editorError('');
  drawSteps();
  editor.showModal();
  $('edit-name').focus();
}
function closeEditor() { editor.close(); state.editing = null; state.editingChanged = false; }
function editorError(message) { $('edit-error').textContent = message; $('edit-error').hidden = !message; }
function drawSteps() {
  const editing = state.editing;
  if (!editing) return;
  const last = editing.actions.length - 1;
  $('step-count').textContent = editing.actions.length;
  $('edit-steps').innerHTML = editing.actions.length ? editing.actions.map((step, i) =>
    `<section class="step" data-index="${i}">
      <span class="step-num">${i + 1}</span>
      <select data-index="${i}" data-field="type" aria-label="Step type">${TYPES.map((name, type) => `<option value="${type}" ${Number(step.type) === type ? 'selected' : ''}>${esc(name)}</option>`).join('')}</select>
      <input class="path-input" data-index="${i}" data-field="path" value="${esc(step.path)}" placeholder="Program, folder or macro name" aria-label="Path">
      <span class="step-tools">
        <button type="button" class="icon-btn" data-step-move="${i}" data-direction="-1" aria-label="Move step up" ${i === 0 ? 'disabled' : ''}>↑</button>
        <button type="button" class="icon-btn" data-step-move="${i}" data-direction="1" aria-label="Move step down" ${i === last ? 'disabled' : ''}>↓</button>
        <button type="button" class="icon-btn remove" data-step-remove="${i}" aria-label="Remove step" title="Remove step">✕</button>
      </span>
      <div class="step-more">
        <input data-index="${i}" data-field="arguments" value="${esc(step.arguments)}" placeholder="Arguments" aria-label="Arguments">
        <input data-index="${i}" data-field="workingDirectory" value="${esc(step.workingDirectory)}" placeholder="Working directory" aria-label="Working directory">
        <textarea class="wide" rows="1" data-index="${i}" data-field="command" placeholder="Command (Omni action / macro fallback)" aria-label="Command">${esc(step.command)}</textarea>
      </div>
      <div class="step-flags">
        <label><input type="checkbox" data-index="${i}" data-field="reuseWindow" ${step.reuseWindow ? 'checked' : ''}> Reuse window</label>
        <label><input type="checkbox" data-index="${i}" data-field="positionWindow" ${step.positionWindow ? 'checked' : ''}> Restore placement</label>
        ${step.isCaptured ? '<span class="captured">● captured layout</span>' : ''}
      </div>
    </section>`).join('') : '<p class="no-steps">No steps yet.</p>';
}
function stepChange(event) {
  const input = event.target.closest('[data-field]');
  if (!input || !state.editing) return;
  const i = Number(input.dataset.index), field = input.dataset.field;
  if (!Number.isInteger(i) || !state.editing.actions[i]) return;
  state.editing.actions[i][field] = input.type === 'checkbox' ? input.checked : field === 'type' ? Number(input.value) : input.value;
}
async function saveEditor(event) {
  event.preventDefault();
  const editing = state.editing;
  if (!editing || state.editingChanged) return;
  editing.name = $('edit-name').value.trim();
  editing.shortcut = $('edit-shortcut').value.trim();
  if (!editing.name) { editorError('Name is required.'); return; }
  $('save-setup').disabled = true;
  try {
    await request('/setups/' + encodeURIComponent(editing.id), 'PUT', {
      revision: state.editingRevision, name: editing.name, shortcut: editing.shortcut,
      actions: editing.actions.map(a => ({
        id: a.id || EMPTY_ID,
        type: Number(a.type), path: a.path || '', arguments: a.arguments || '',
        workingDirectory: a.workingDirectory || '', command: a.command || '',
        reuseWindow: !!a.reuseWindow, positionWindow: !!a.positionWindow
      }))
    });
    closeEditor();
    await loadAll();
    toast('Saved — desktop OmniSweep updated');
  } catch (e) {
    if (e.status === 409) {
      state.editingChanged = true;
      $('edit-warning').hidden = false;
      await loadAll();
    }
    editorError(e.message);
    $('save-setup').disabled = state.editingChanged;
  }
}
async function deleteEditor() {
  const editing = state.editing;
  if (!editing) return;
  if (!confirm('Delete setup "' + editing.name + '"? This also removes it from desktop OmniSweep.')) return;
  try {
    await request('/setups/' + encodeURIComponent(editing.id) + '/delete', 'POST', {revision: state.editingRevision});
    closeEditor();
    await loadAll();
    toast('Setup deleted');
  } catch (e) {
    if (e.status === 409) { state.editingChanged = true; $('edit-warning').hidden = false; await loadAll(); }
    editorError(e.message);
  }
}
function reloadEditor() {
  const id = state.editing?.id;
  closeEditor();
  if (id) openEditor(id);
}

// ---------- Tool details ----------
async function showDetails(id) {
  const t = state.tools.find(tool => tool.id === id);
  if (!t) return;
  state.detailsId = id;
  $('details-name').textContent = t.name;
  $('details-description').textContent = t.description;
  $('details-status').textContent = isPublicHub && t.remote?.supported === false
    ? 'Local only — ' + t.remote.reason
    : displayStatus(t.status) + (t.error ? ' — ' + t.error : '');
  $('details-url').textContent = isPublicHub ? (t.remote?.url || 'Not reachable remotely') : t.url;
  $('details-launch').href = '/' + encodeURIComponent(id);
  $('details-launch').dataset.open = id;
  $('details-launch').target = '_blank';
  $('details-launch').rel = 'noopener';
  $('details-log').textContent = 'Loading latest logs…';
  $('details-dialog').showModal();
  await loadLog(id);
}
async function loadLog(id) {
  $('details-refresh').disabled = true;
  try {
    const response = await fetchBounded('/gateway/api/log/' + encodeURIComponent(id));
    if (!response.ok) throw new Error('HTTP ' + response.status);
    const body = await response.text();
    if (state.detailsId === id) $('details-log').textContent = body || 'No launcher output.';
  } catch (e) {
    if (state.detailsId === id) $('details-log').textContent = 'Unable to retrieve launcher log: ' + e.message;
  } finally { $('details-refresh').disabled = false; }
}
async function copyText(value, label) {
  try { await navigator.clipboard.writeText(value); toast('Copied ' + (label || value)); }
  catch { toast('Copy unavailable: ' + value); }
}

// ---------- Command palette ----------
let paletteMatches = [], paletteSelected = 0;
const paletteSources = () => [
  ...state.tools.map(t => ({kind:'tool',id:t.id,name:t.name,detail:'Tool · '+displayStatus(t.status),description:t.description})),
  ...state.snapshot.setups.map(s => ({kind:'setup',id:s.id,name:s.name,detail:'Setup · '+(s.actions?.length||0)+' steps'})),
  ...state.snapshot.macros.map(m => ({kind:'macro',id:m.id,name:m.name,detail:m.isScene?'Scene':'Macro'})),
  ...[{id:'gateway',name:'Gateway',url:'/gateway/'},{id:'presentations',name:'Presentations',url:'/presentations/'},{id:'scheduler',name:'Scheduler',url:'/Scheduler.html'},{id:'settings',name:'Settings',url:'/Settings.html'}]
    .map(p => ({kind:'page',...p,detail:'Page'}))
];
function updatePalette() {
  const q = $('palette-input').value.trim().toLocaleLowerCase();
  paletteMatches = paletteSources()
    .filter(item => matches(q,item.name,item.detail,item.description||''))
    .sort((a,b) => {
      const score = x => (isPinned(x.id)?200:0)+(lastOpened(x.id)?Math.min(80,80/(1+(Date.now()-lastOpened(x.id))/86400000)):0)
        +(q && x.name.toLocaleLowerCase().startsWith(q)?100:0);
      return score(b)-score(a);
    }).slice(0,25);
  paletteSelected = Math.min(paletteSelected,Math.max(0,paletteMatches.length-1));
  $('palette-results').innerHTML = paletteMatches.length ? paletteMatches.map((item,i) =>
    `<button type="button" class="palette-item ${i===paletteSelected?'active':''}" data-palette-index="${i}" role="option" aria-selected="${i===paletteSelected}"><span class="palette-kind">${esc(item.kind)}</span><span class="palette-name">${esc(item.name)}</span><small>${esc(item.detail)}</small></button>`).join('')
    : '<p class="empty">No matching commands</p>';
}
function openPalette() {
  if (editor.open || $('new-dialog').open || $('details-dialog').open) return;
  if (!$('command-palette').open) $('command-palette').showModal();
  $('palette-input').value = '';
  paletteSelected = 0;
  updatePalette();
  $('palette-input').focus();
}
function activatePalette(index) {
  const item = paletteMatches[index];
  if (!item) return;
  $('command-palette').close();
  if (item.kind === 'tool') {
    markOpened(item.id);
    window.open('/'+encodeURIComponent(item.id),'_blank','noopener');
  } else if (item.kind === 'setup') {
    run('setups',item.id,null);
  } else if (item.kind === 'macro') {
    run('macros',item.id,null);
  } else if (item.kind === 'page') {
    location.assign(item.url);
  }
}
$('palette-open').addEventListener('click',openPalette);
$('palette-input').addEventListener('input',() => {paletteSelected=0;updatePalette();});
$('palette-input').addEventListener('keydown',event => {
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault();
    if (paletteMatches.length) paletteSelected=(paletteSelected+(event.key==='ArrowDown'?1:-1)+paletteMatches.length)%paletteMatches.length;
    updatePalette();
    $('palette-results').querySelector('.active')?.scrollIntoView({block:'nearest'});
  } else if (event.key === 'Enter') {event.preventDefault();activatePalette(paletteSelected);}
});
$('palette-results').addEventListener('click',event => {
  const button = event.target.closest('[data-palette-index]');
  if (button) activatePalette(Number(button.dataset.paletteIndex));
});

// ---------- Activity and incident history ----------
let activityEvents = [], activityFilter = 'all', loadingActivity = false;
function renderActivity() {
  const items=activityEvents.filter(event => activityFilter==='all'
    || (activityFilter==='incident' ? event.kind==='incident' : event.kind!=='incident')).slice(0,35);
  $('activity-count').textContent = String(items.length);
  $('activity-rows').innerHTML = items.length ? items.map(event => {
    const date=new Date(event.time);
    const time=Number.isNaN(date.getTime()) ? 'Unknown time' : date.toLocaleString();
    const status=['success','failed','recovering','recovered'].includes(event.status) ? event.status : 'unknown';
    return `<details class="activity-row">
      <summary><span class="activity-state ${esc(status)}"></span><span class="activity-text">${esc(event.title)}</span><span class="activity-status">${esc(status)}</span><time>${esc(time)}</time></summary>
      <div class="activity-detail">Type: ${esc(event.kind)} · Source: ${esc(event.source||'Gateway')}${Number.isFinite(event.durationMs)?' · Duration: '+esc(event.durationMs)+' ms':''}</div>
    </details>`;
  }).join('') : '<div class="empty">No matching activity yet.</div>';
  document.querySelectorAll('[data-activity-filter]').forEach(button =>
    button.classList.toggle('selected',button.dataset.activityFilter===activityFilter));
}
async function loadActivity() {
  if (loadingActivity || document.hidden) return;
  loadingActivity=true;
  try {
    const response=await fetchBounded('/gateway/api/activity');
    if (!response.ok) throw new Error('HTTP '+response.status);
    const data=await response.json();
    if (!Array.isArray(data.events)) throw new Error('Invalid activity data');
    activityEvents=data.events;
    renderActivity();
  } catch {
    if (!activityEvents.length) $('activity-rows').innerHTML='<div class="empty">History temporarily unavailable. Retrying automatically.</div>';
  } finally {loadingActivity=false;}
}
document.querySelector('.activity-filters').addEventListener('click',event => {
  const button=event.target.closest('[data-activity-filter]');
  if (button) {activityFilter=button.dataset.activityFilter;renderActivity();}
});
$('activity-refresh').addEventListener('click',loadActivity);
// ---------- Events ----------
function clearSearch() {
  $('search').value = '';
  state.queryId++;
  $('file-results').innerHTML = '';
  $('files').hidden = true;
  state.hot = 0;
  state.typeFilter = 'all';
  render();
}
$('search-filters').addEventListener('click', event => {
  const button=event.target.closest('[data-filter]');
  if (!button) return;
  state.typeFilter=button.dataset.filter;
  state.hot=0;
  render();
  if (query().length >= 2 && ['all','files'].includes(state.typeFilter)) lookupFiles();
});
document.addEventListener('click', event => {
  const copy = event.target.closest('[data-copy]');
  if (copy) { copyText(copy.dataset.copy, copy.dataset.copy); return; }
  const preview = event.target.closest('[data-preview-setup]');
  if (preview) { if (tooltipAnchor===preview && !$('setup-tooltip').hidden) hideSetupTooltip(); else showSetupTooltip(preview); return; }
  const action = event.target.closest('[data-tool-action]');
  if (action) {
    const id = $('tool-context-menu').dataset.toolId;
    hideToolMenu();
    if (action.dataset.toolAction === 'move') moveTool(id);
    else if (action.dataset.toolAction === 'pin') pin(id);
    else if (action.dataset.toolAction === 'details') showDetails(id);
    else if (action.dataset.toolAction === 'prepare-deletion') void prepareToolDeletion(id);
    return;
  }
  const menuButton = event.target.closest('[data-tool-menu]');
  if (menuButton) {
    const rect = menuButton.getBoundingClientRect();
    showToolMenu(menuButton.dataset.toolMenu,rect.right-180,rect.bottom+4);
    return;
  }
  hideToolMenu();
  const moreToggle = event.target.closest('[data-toggle-more]');
  if (moreToggle) { moreExpanded = !moreExpanded; renderTools(); renderItems(); return; }
  const pinned = event.target.closest('[data-pin]');
  if (pinned) { pin(pinned.dataset.pin); return; }
  const open = event.target.closest('[data-open]');
  if (open) { markOpened(open.dataset.open); return; }
  const snapshotBtn = event.target.closest('[data-snapshot]');
  if (snapshotBtn) { captureWindows(snapshotBtn.dataset.snapshot); return; }
  const edit = event.target.closest('[data-edit]');
  if (edit) { openEditor(edit.dataset.edit); return; }
  const move = event.target.closest('[data-move]');
  if (move) { moveSetup(move.dataset.move, Number(move.dataset.direction)); return; }
  const setup = event.target.closest('[data-run-setup]');
  if (setup) { run('setups', setup.dataset.runSetup, setup); return; }
  const macro = event.target.closest('[data-run-macro]');
  if (macro) { run('macros', macro.dataset.runMacro, macro); return; }
  const file = event.target.closest('[data-file]');
  if (file) { openFile(Number(file.dataset.file), file); }
});
$('search').addEventListener('input', () => {
  if (!query()) state.typeFilter = 'all';
  state.hot = 0;
  render();
  clearTimeout(fileTimer);
  state.queryId++;
  fileTimer = setTimeout(lookupFiles, 350);
});
$('search').addEventListener('keydown', event => {
  const max = state.items.length;
  if (event.key === 'ArrowDown' && max) { event.preventDefault(); state.hot = (state.hot + 1) % max; paintHot(); }
  if (event.key === 'ArrowUp' && max) { event.preventDefault(); state.hot = (state.hot + max - 1) % max; paintHot(); }
  if (event.key === 'Enter' && query() && max) {
    event.preventDefault();
    const el = state.items[state.hot];
    if (el?.dataset.open) { markOpened(el.dataset.open); window.open(el.href, '_blank', 'noopener'); }
    else el?.click();
  }
  if (event.key === 'Escape') { if ($('search').value) clearSearch(); else $('search').blur(); }
});
$('refresh').addEventListener('click', poll);
$('retry-data').addEventListener('click', poll);
$('network-refresh').addEventListener('click', loadNetwork);
$('clear-search').addEventListener('click', clearSearch);
$('new-setup').addEventListener('click', newSetup);
$('new-form').addEventListener('submit', createSetup);
$('new-cancel').addEventListener('click', () => $('new-dialog').close());
$('capture-setup').addEventListener('click', () => captureWindows(null));
$('close-editor').addEventListener('click', closeEditor);
$('cancel-editor').addEventListener('click', closeEditor);
$('reload-editor').addEventListener('click', reloadEditor);
$('edit-form').addEventListener('submit', saveEditor);
$('delete-setup').addEventListener('click', deleteEditor);
$('add-step').addEventListener('click', () => {
  if (!state.editing) return;
  state.editing.actions.push({id:EMPTY_ID,type:1,path:'',arguments:'',workingDirectory:'',command:'',reuseWindow:true,positionWindow:true,isCaptured:false});
  drawSteps();
  $('edit-steps').querySelector('.step:last-child .path-input')?.focus();
});
$('edit-steps').addEventListener('input', stepChange);
$('edit-steps').addEventListener('change', stepChange);
$('edit-steps').addEventListener('click', event => {
  const editing = state.editing;
  const remove = event.target.closest('[data-step-remove]');
  if (remove && editing) { editing.actions.splice(Number(remove.dataset.stepRemove), 1); drawSteps(); return; }
  const move = event.target.closest('[data-step-move]');
  if (move && editing) {
    const i = Number(move.dataset.stepMove), j = i + Number(move.dataset.direction);
    if (j < 0 || j >= editing.actions.length) return;
    [editing.actions[i], editing.actions[j]] = [editing.actions[j], editing.actions[i]];
    drawSteps();
  }
});

// Drag to reorder setups.
let dragged = null;
const clearDrop = () => $('setups-grid').querySelectorAll('.drop-target,.dragging').forEach(el => el.classList.remove('drop-target', 'dragging'));
$('setups-grid').addEventListener('dragstart', event => {
  const card = event.target.closest('[data-setup]');
  if (!card) return;
  dragged = card.dataset.setup;
  event.dataTransfer.effectAllowed = 'move';
  card.classList.add('dragging');
});
$('setups-grid').addEventListener('dragover', event => {
  const target = event.target.closest('[data-setup]');
  if (!dragged || !target) return;
  event.preventDefault();
  $('setups-grid').querySelectorAll('.drop-target').forEach(el => el !== target && el.classList.remove('drop-target'));
  if (target.dataset.setup !== dragged) target.classList.add('drop-target');
});
$('setups-grid').addEventListener('drop', event => {
  const target = event.target.closest('[data-setup]');
  if (!target || !dragged) return;
  event.preventDefault();
  const id = dragged;
  dragged = null;
  clearDrop();
  const ids = state.snapshot.setups.map(x => x.id), from = ids.indexOf(id), to = ids.indexOf(target.dataset.setup);
  if (from < 0 || to < 0 || from === to) return;
  ids.splice(from, 1);
  ids.splice(to, 0, id);
  mutate('/order', 'PUT', {revision: state.snapshot.revision, ids}, 'Order saved');
});
$('setups-grid').addEventListener('dragend', () => { dragged = null; clearDrop(); });

document.addEventListener('keydown', event => {
  const editable = ['INPUT','TEXTAREA','SELECT'].includes(document.activeElement?.tagName);
  const dialogOpen = editor.open || $('new-dialog').open || $('details-dialog').open;
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); openPalette(); return; }
  if (editable || dialogOpen || event.ctrlKey || event.metaKey || event.altKey) return;
  if (event.key === '/') { event.preventDefault(); $('search').focus(); }
  else if (event.key.toLowerCase() === 'r') poll();
});
$('details-close').addEventListener('click', () => $('details-dialog').close());
$('details-copy').addEventListener('click', () => state.detailsId && copyText(shortcut(state.detailsId), 'shortcut'));
$('details-refresh').addEventListener('click', () => state.detailsId && loadLog(state.detailsId));
$('details-dialog').addEventListener('close', () => { state.detailsId = null; });
document.addEventListener('visibilitychange', () => { if (!document.hidden) { poll(); pollNetwork(); pollServiceHealth(); loadActivity(); } });
setInterval(pollServiceHealth, 20000);
setInterval(loadActivity, 30000);
window.addEventListener('online', () => { state.syncFailures=0; poll(); });
poll();
pollNetwork();
pollServiceHealth();
loadActivity();
