// Persist tab birth times and the recoverable URLs removed by Clean Tabs.
// Chromium does not expose the original creation time of pre-existing tabs:
// those first discovered after installation are dated from first observation.
(() => {
  // v2 intentionally resets previously inferred ages: old lastAccessed values
  // could make freshly opened tabs appear several days old.
  const AGE_KEY = 'cleanupTabFirstSeenV2';
  const ARCHIVE_KEY = 'cleanupArchivedTabs';
  const MAX_AGE_MS = 3 * 24 * 60 * 60 * 1000;
  let ageQueue = Promise.resolve();
  let archiveQueue = Promise.resolve();
  let cleanupInProgress = null;

  const restorable = url => typeof url === 'string' && /^(https?:|file:)\/\//i.test(url);

  function serializeAges(change) {
    const task = ageQueue.then(async () => {
      const stored = await chrome.storage.local.get(AGE_KEY);
      const ages = { ...(stored[AGE_KEY] || {}) };
      const result = change(ages);
      await chrome.storage.local.set({ [AGE_KEY]: ages });
      return result;
    });
    ageQueue = task.catch(error => console.error('Failed to track tab age:', error));
    return task;
  }

  function serializeArchive(operation) {
    const task = archiveQueue.then(async () => {
      const stored = await chrome.storage.local.get(ARCHIVE_KEY);
      const items = Array.isArray(stored[ARCHIVE_KEY]) ? stored[ARCHIVE_KEY] : [];
      return operation(items);
    });
    archiveQueue = task.catch(error => console.error('Archive operation failed:', error));
    return task;
  }

  async function persistArchive(items) {
    await chrome.storage.local.set({ [ARCHIVE_KEY]: items });
  }

  async function reconcileAges(tabs) {
    return serializeAges(ages => {
      const live = new Set(tabs.map(tab => String(tab.id)));
      for (const id of Object.keys(ages)) if (!live.has(id)) delete ages[id];
      const now = Date.now();
      for (const tab of tabs) {
        const id = String(tab.id);
        if (!Number.isFinite(ages[id])) ages[id] = now;
      }
      return { ...ages };
    });
  }

  // Register tab listeners synchronously so MV3 can wake the service worker.
  chrome.tabs.onCreated.addListener(tab => {
    void serializeAges(ages => { ages[String(tab.id)] = Date.now(); });
  });
  chrome.tabs.onRemoved.addListener(tabId => {
    void serializeAges(ages => { delete ages[String(tabId)]; });
  });
  chrome.tabs.onReplaced.addListener((addedTabId, removedTabId) => {
    void serializeAges(ages => {
      ages[String(addedTabId)] = ages[String(removedTabId)] || Date.now();
      delete ages[String(removedTabId)];
    });
  });

  // Fill in tabs that predate this extension version, without treating them as old.
  void chrome.tabs.query({}).then(reconcileAges)
    .catch(error => console.error('Tab-age initialization failed:', error));

  function isEmptyStartPage(tab) {
    // Vivaldi can expose empty Start Page tabs without a URL. Do not treat
    // URL-less loading tabs, tabs with a pending navigation or other internal
    // extension views as blank Start Pages.
    if (tab.url || tab.pendingUrl || tab.status === 'loading') return false;
    return /^(start pages?|new tab|speed dial)$/i.test((tab.title || '').trim());
  }

  async function doCleanup(matchesPattern) {
    const tabs = await chrome.tabs.query({});
    const ages = await reconcileAges(tabs);
    const now = Date.now();
    const selected = tabs.filter(tab => {
      // Never touch actively viewed, pinned, or still-navigating tabs.
      if (tab.active || tab.pinned || tab.pendingUrl || tab.status === 'loading') return false;
      if (isEmptyStartPage(tab)) return true; // Has no URL worth archiving.
      const age = ages[String(tab.id)];
      // A matching cleanup pattern must NOT bypass the requested 3-day age
      // threshold. Old behavior (pattern OR age) archived fresh YouTube tabs.
      if (!Number.isFinite(age) || now - age < MAX_AGE_MS) return false;
      if (restorable(tab.url)) return true;
      // Browser-internal pages need an explicit cleanup pattern (normally the
      // seeded chrome:* or vivaldi:*); still never skip the 3-day age gate.
      return /^(chrome|vivaldi):/i.test(tab.url || '') &&
        matchesPattern(tab.url, tab.title);
    });
    let closed = 0;
    let archived = 0;
    let failed = 0;
    for (const tab of selected) {
      // Query and storage writes are asynchronous. Recheck immediately before
      // touching the tab: it might have been activated or navigated meanwhile.
      let current;
      try { current = await chrome.tabs.get(tab.id); }
      catch { continue; } // Already closed.
      if (current.active || current.pinned || current.pendingUrl ||
          current.status === 'loading' ||
          (current.url || '') !== (tab.url || '')) continue;
      const blank = isEmptyStartPage(tab);
      if (blank && !isEmptyStartPage(current)) continue;
      let item = null;
      if (!blank) {
        // Archive before closing; internal chrome:/vivaldi: entries retain
        // their URLs for recovery just like ordinary web pages.
        item = {
          id: crypto.randomUUID(),
          url: current.url,
          title: current.title || current.url,
          archivedAt: Date.now()
        };
        await serializeArchive(async items => persistArchive([item, ...items]));
      }
      try {
        await chrome.tabs.remove(tab.id);
        closed++;
        if (item) archived++;
      } catch (error) {
        failed++;
        if (item) {
          await serializeArchive(async items => persistArchive(items.filter(x => x.id !== item.id)));
        }
        console.error('Unable to close tab during cleanup:', tab.id, error);
      }
    }
    return { closed, archived, failed };
  }

  function run(matchesPattern) {
    if (!cleanupInProgress) {
      cleanupInProgress = doCleanup(matchesPattern).finally(() => { cleanupInProgress = null; });
    }
    return cleanupInProgress;
  }

  async function command(message) {
    switch (message.command) {
      case 'archive:list':
        return serializeArchive(items => items);
      case 'archive:remove':
        return serializeArchive(async items => {
          await persistArchive(items.filter(item => item.id !== message.id));
          return true;
        });
      case 'archive:restore':
        return serializeArchive(async items => {
          const item = items.find(item => item.id === message.id);
          if (!item || !(restorable(item.url) || /^(chrome|vivaldi):/i.test(item.url || '')))
            throw Error('Archived tab no longer available');
          // Remove only after Chrome has successfully created the replacement tab.
          await chrome.tabs.create({ url: item.url, active: true });
          await persistArchive(items.filter(x => x.id !== item.id));
          return true;
        });
      case 'archive:clear':
        return serializeArchive(async () => { await persistArchive([]); return true; });
      default:
        throw Error('Unknown archive command');
    }
  }

  chrome.runtime.onMessage.addListener((message, sender, reply) => {
    if (sender.id !== chrome.runtime.id || !message?.command?.startsWith('archive:')) return false;
    command(message).then(data => reply({ ok: true, data }))
      .catch(error => reply({ ok: false, error: String(error.message || error) }));
    return true;
  });

  globalThis.TabCleanup = { run };
})();
