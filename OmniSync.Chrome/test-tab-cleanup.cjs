// Focused simulated browser tests; no real tabs are modified.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const crypto = require('node:crypto');
const DAY = 86400000;
const now = Date.now();
const state = {
  cleanupTabFirstSeenV2: {
    '1': now - 4 * DAY, '2': now, '5': now,
    '6': now - 4 * DAY, '7': now - 4 * DAY, '8': now - 4 * DAY,
    '9': now - 4 * DAY, '10': now - 4 * DAY, '11': now - 4 * DAY
  },
  // Old, unreliable timestamps should no longer be consulted.
  cleanupTabFirstSeen: { '2': now - 15 * DAY, '4': now - 15 * DAY },
  cleanupArchivedTabs: []
};
const tabs = [
  { id: 1, url: 'https://old.example/article', title: 'Old' },
  { id: 2, url: 'https://www.youtube.com/watch?v=fresh', title: 'New patterned tab',
    lastAccessed: now - 10 * DAY },
  { id: 4, url: 'https://preexisting.example/', title: 'Old lastAccessed',
    lastAccessed: now - 5 * DAY },
  { id: 5, url: '', title: 'Start pages', active: false },
  { id: 6, url: '', title: 'Loading', active: false },
  { id: 7, url: 'vivaldi:extensions', title: 'Extensions' },
  { id: 8, url: 'chrome://extensions/', title: 'Extensions' },
  { id: 9, url: 'https://pinned.example/', title: 'Pinned', pinned: true },
  { id: 10, url: 'https://active.example/', title: 'Active', active: true },
  { id: 11, url: 'https://loading.example/', title: 'Loading', status: 'loading' },
  { id: 12, url: '', title: 'Start pages', status: 'loading' }
];
const listeners = {};
let nextId = 100;
function event(name) { return { addListener(fn) { (listeners[name] ||= []).push(fn); } }; }
const clone = x => structuredClone(x);
const chrome = {
  runtime: { id: 'mock-extension', onMessage: event('message') },
  storage: { local: {
    async get(key) { return { [key]: clone(state[key]) }; },
    async set(patch) { Object.assign(state, clone(patch)); }
  } },
  tabs: {
    onCreated: event('created'), onRemoved: event('removed'), onReplaced: event('replaced'),
    async query() { return clone(tabs); },
    async get(id) {
      const found = tabs.find(t => t.id === id);
      if (!found) throw Error('Tab not found');
      return clone(found);
    },
    async remove(id) {
      const i = tabs.findIndex(t => t.id === id);
      if (i < 0) throw Error('Tab not found');
      tabs.splice(i, 1);
      for (const fn of listeners.removed || []) fn(id);
    },
    async create(data) {
      const tab = { id: nextId++, url: data.url, title: data.url };
      tabs.push(tab);
      for (const fn of listeners.created || []) fn(tab);
      return tab;
    }
  }
};
const sandbox = { chrome, console, crypto, Date };
vm.runInNewContext(fs.readFileSync(__dirname + '/tab-cleanup.js', 'utf8'), sandbox);
async function message(command, id) {
  return new Promise((resolve, reject) => {
    const handler = listeners.message[0];
    if (!handler) return reject(Error('Archive listener missing'));
    handler({ command, id }, { id: chrome.runtime.id }, response =>
      response.ok ? resolve(response.data) : reject(Error(response.error)));
  });
}
(async () => {
  // Previously ANY matching pattern bypassed age gating, archiving fresh tabs.
  const result = await sandbox.TabCleanup.run(() => true);
  assert.equal(result.closed, 4);
  assert.equal(result.archived, 3);
  assert.deepEqual(tabs.map(t => t.id), [2, 4, 6, 9, 10, 11, 12]);
  assert.equal(state.cleanupArchivedTabs.length, 3,
    'Empty Start pages close without producing useless URL-less archive entries');
  assert(state.cleanupArchivedTabs.some(item => item.url === 'vivaldi:extensions'));
  assert(state.cleanupArchivedTabs.some(item => item.url === 'chrome://extensions/'));
  assert(tabs.some(t => t.id === 2), 'Recent matching YouTube tab must stay open');
  assert(tabs.some(t => t.id === 4), 'Old lastAccessed must never infer tab creation time');

  tabs.push({ id: 3, url: 'https://pattern.example/', title: 'Match' });
  for (const fn of listeners.created || []) fn(tabs[tabs.length - 1]);
  const recentResult = await sandbox.TabCleanup.run(() => true);
  assert.equal(recentResult.closed, 0, 'Newly created tabs are protected even if pattern matches');

  const vivaldi = state.cleanupArchivedTabs.find(t => t.url === 'vivaldi:extensions');
  await message('archive:restore', vivaldi.id);
  assert(tabs.some(t => t.url === vivaldi.url), 'Internal URL can reopen from archive');
  assert.equal(state.cleanupArchivedTabs.length, 2);

  await message('archive:clear');
  assert.equal(state.cleanupArchivedTabs.length, 0);
  assert(tabs.some(t => t.id === 9), 'Pinned tab stays open');
  assert(tabs.some(t => t.id === 10), 'Active tab stays open');
  console.log('Tab cleanup: strict age gate, fresh pattern protection, internal pages, blank Start pages, active/pinned, restore, clear PASS');
})().catch(error => { console.error(error); process.exitCode = 1; });