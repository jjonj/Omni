const $ = id => document.getElementById(id);
async function send(command, fields = {}) {
  const answer = await chrome.runtime.sendMessage({ command, ...fields });
  if (!answer?.ok) throw Error(answer?.error || 'Extension background unavailable');
  return answer.data;
}
function show(message, isError = false) {
  $('msg').textContent = message;
  $('msg').className = isError ? 'error' : '';
}
async function refresh() {
  const state = await send('status');
  $('state').textContent = (state.enabled ? 'Enabled' : 'Paused') +
    (state.connected ? ' · Local bridge worker active' : ' · Waiting for local bridge');
}
// Hide leading unread counters such as "(100) " without altering the saved title.
function displayArchiveTitle(title, url) {
  const cleaned = String(title || '').replace(/^(?:(?:\(\d+\)|\[\d+\])\s+)+/, '').trim();
  return cleaned || url;
}
function archiveSiteIcon(url) {
  const icon = document.createElement('span');
  icon.className = 'archive-icon';
  icon.setAttribute('aria-hidden', 'true');
  let host = '';
  try { host = new URL(url).hostname.toLowerCase(); } catch {}
  if (/(^|\.)youtube\.com$|(^|\.)youtu\.be$/.test(host)) {
    icon.classList.add('youtube'); icon.textContent = '▶';
  } else if (/(^|\.)twitch\.tv$/.test(host)) {
    icon.classList.add('twitch'); icon.textContent = 'T';
  } else if (host === 'mail.google.com' || host === 'gmail.com') {
    icon.classList.add('gmail'); icon.textContent = 'M';
  } else if (host === 'chatgpt.com' || host === 'chat.openai.com') {
    icon.classList.add('chatgpt'); icon.textContent = '✳';
  } else {
    icon.textContent = host ? host[0].toUpperCase() : '•';
  }
  // The browser's own cached favicon works for both new and existing archive entries;
  // no page screenshot, remote favicon service, or stored thumbnail is required.
  if (/^https?:\/\//i.test(url)) {
    const img = document.createElement('img');
    img.alt = '';
    img.loading = 'lazy';
    const faviconURL = new URL(chrome.runtime.getURL('/_favicon/'));
    faviconURL.searchParams.set('pageUrl', url);
    faviconURL.searchParams.set('size', '32');
    img.src = faviconURL.toString();
    img.addEventListener('error', () => img.remove());
    icon.append(img);
  }
  return icon;
}
let archived = [];
async function refreshArchive() {
  archived = await send('archive:list');
  $('archive-count').textContent = `(${archived.length})`;
  $('clear-archive').disabled = archived.length === 0;
  $('archive-empty').hidden = archived.length !== 0;
  const list = $('archive-list');
  list.replaceChildren();
  for (const item of archived) {
    const li = document.createElement('li');
    const open = document.createElement('button');
    open.type = 'button';
    open.className = 'archive-open';
    open.title = item.url;
    const title = document.createElement('span');
    title.className = 'archive-title';
    title.textContent = displayArchiveTitle(item.title, item.url);
    open.append(archiveSiteIcon(item.url), title);
    open.addEventListener('click', async () => {
      open.disabled = true;
      try {
        await send('archive:restore', { id: item.id });
        await refreshArchive();
        show('Tab restored.');
      } catch (error) { show(error.message, true); open.disabled = false; }
    });
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'archive-remove';
    remove.title = 'Remove from archive';
    remove.setAttribute('aria-label', `Remove ${item.title || item.url} from archive`);
    remove.textContent = '×';
    remove.addEventListener('click', async () => {
      remove.disabled = true;
      try {
        await send('archive:remove', { id: item.id });
        await refreshArchive();
      } catch (error) { show(error.message, true); remove.disabled = false; }
    });
    li.append(open, remove);
    list.append(li);
  }
}
function cleanupMessage(result, immediate) {
  const summary = `${result.closed} tab${result.closed === 1 ? '' : 's'} closed, ${result.archived} archived` +
    (result.failed ? `, ${result.failed} failed` : '') + '.';
  if (immediate || result.closed || !result.skippedRecent) return summary;
  const next = result.nextEligibleAt ? ` Next eligible: ${new Date(result.nextEligibleAt).toLocaleString()}.` : '';
  return `${summary} ${result.skippedRecent} tabs are younger than 72 hours since first tracked` +
    ` (${result.matchedRecent} matching patterns).${next} Clean matching now can explicitly override this limit.`;
}
async function cleanTabs(immediate = false) {
  $('clean-tabs').disabled = true;
  $('clean-matches').disabled = true;
  try {
    const result = await send(immediate ? 'cleanup:matching-now' : 'cleanup:run');
    await refreshArchive();
    show(cleanupMessage(result, immediate), result.failed > 0);
  } catch (error) { show(error.message, true); }
  finally { $('clean-tabs').disabled = false; $('clean-matches').disabled = false; }
}
$('clean-tabs').addEventListener('click', () => void cleanTabs());
$('clean-matches').addEventListener('click', () => $('confirm-matching').showModal());
$('cancel-matching').addEventListener('click', () => $('confirm-matching').close());
$('confirm-matching-button').addEventListener('click', () => {
  $('confirm-matching').close();
  void cleanTabs(true);
});
$('enable').addEventListener('click', async () => {
  try { await send('enable'); await refresh(); show('Enabled.'); }
  catch (error) { show(error.message, true); }
});
$('disable').addEventListener('click', async () => {
  try { await send('disable'); await refresh(); show('Paused.'); }
  catch (error) { show(error.message, true); }
});
$('clear-archive').addEventListener('click', () => $('confirm-clear').showModal());
$('cancel-clear').addEventListener('click', () => $('confirm-clear').close());
$('confirm-clear-button').addEventListener('click', async () => {
  $('confirm-clear-button').disabled = true;
  try {
    await send('archive:clear');
    $('confirm-clear').close();
    await refreshArchive();
    show('Archive cleared.');
  } catch (error) { show(error.message, true); }
  finally { $('confirm-clear-button').disabled = false; }
});
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === 'local' && changes.cleanupArchivedTabs) {
    void refreshArchive().catch(error => show(error.message, true));
  }
});
void Promise.all([refresh(), refreshArchive()]).then(() => {
  const query = new URLSearchParams(window.location.search);
  if (query.has('cleanupResult')) show(query.get('cleanupResult'));
  if (query.get('confirmMatching') === '1') $('confirm-matching').showModal();
}).catch(error => show(error.message, true));
