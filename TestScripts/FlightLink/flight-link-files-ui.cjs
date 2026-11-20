#!/usr/bin/env node
// Drive the real Android Files screen on a disposable emulator while FlightLink
// shapes its actual SignalR traffic. Records UI-visible convergence, not just Hub RPCs.
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');

const argv = process.argv.slice(2);
const arg = (key, fallback) => {
  const i = argv.indexOf('--' + key);
  return i === -1 ? fallback : argv[i + 1];
};
const serial = arg('serial', '');
const out = arg('out', '');
const adb = process.env.ADB_PATH || 'adb';
const control = 'http://127.0.0.1:15001';
if (!/^emulator-\d+$/.test(serial)) throw Error('Requires --serial emulator-NNNN; physical phones are refused.');
if (!out) throw Error('Requires --out <unused JSON path> in task Evidence.');
if (fs.existsSync(out)) throw Error('Refusing to overwrite evidence: ' + out);

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const run = args => execFileSync(adb, ['-s', serial, ...args], {
  encoding: 'utf8', timeout: 30000, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']
});
function attribute(node, name) {
  const match = node.match(new RegExp('(?:^|\\s)' + name + '="([^"]*)"'));
  return match ? match[1].replace(/&quot;/g, '"').replace(/&amp;/g, '&') : '';
}
function nodes(xml) {
  return [...xml.matchAll(/<node\b[^>]*>/g)].map(match => ({
    text: attribute(match[0], 'text'),
    desc: attribute(match[0], 'content-desc'),
    bounds: attribute(match[0], 'bounds')
  }));
}
function snapshot() {
  run(['shell', 'uiautomator', 'dump', '/sdcard/flightlink-window.xml']);
  const xml = run(['shell', 'cat', '/sdcard/flightlink-window.xml']);
  const items = nodes(xml);
  const driveLetters = [...new Set(items.map(n => n.text).filter(t => /^[A-Z]:\\$/.test(t)))].sort();
  const statusLabels = items.map(n => n.text).filter(t =>
    /^(No files found\.|Not connected and root not cached\.|Loading.*|Connecting.*)$/i.test(t)
  );
  return {items, driveLetters, statusLabels, filesScreen: items.some(n => n.text === 'Files: /')};
}
function tap(items, desc, fallbackText) {
  const item = items.find(n => n.desc === desc) || items.find(n => n.text === fallbackText);
  if (!item) throw Error('Cannot locate ' + desc + ' via Android UI hierarchy; unlock and open OmniSync first.');
  const coords = [...item.bounds.matchAll(/\d+/g)].map(m => +m[0]);
  if (coords.length !== 4) throw Error('Missing bounds for ' + desc);
  const [x1, y1, x2, y2] = coords;
  run(['shell', 'input', 'tap', String(Math.round((x1+x2)/2)), String(Math.round((y1+y2)/2))]);
}
async function post(route, body) {
  const response = await fetch(control + route, {
    method: 'POST', headers: {'content-type': 'application/json'}, body: JSON.stringify(body)
  });
  if (!response.ok) throw Error('FlightLink control ' + route + ' HTTP ' + response.status);
}
const evidence = {kind:'real-android-files-ui', timestamp:new Date().toISOString(), serial, samples:[]};
function record(label) {
  const screen = snapshot();
  const observation = {
    label, timestamp:new Date().toISOString(), driveLetters:screen.driveLetters,
    statusLabels:screen.statusLabels, filesScreen:screen.filesScreen
  };
  evidence.samples.push(observation);
  console.log(JSON.stringify(observation));
  return screen;
}
async function main() {
  if (run(['get-state']).trim() !== 'device') throw Error('Emulator not ready');
  const pkg = run(['shell', 'dumpsys', 'package', 'com.omni.sync']);
  evidence.androidPackage = {
    versionName: pkg.match(/versionName=([^\r\n]+)/)?.[1] || 'unknown',
    versionCode: pkg.match(/versionCode=(\d+)/)?.[1] || 'unknown'
  };
  const status = await fetch(control + '/status');
  if (!status.ok) throw Error('Isolated FlightLink proxy is not running');
  let screen = snapshot();
  if (!screen.filesScreen) {
    tap(screen.items, 'Files', 'Files');
    await sleep(1500);
    screen = snapshot();
  }
  if (!screen.filesScreen) throw Error('Could not navigate to Files root; clear emulator permission dialogs.');
  try {
    await post('/profile', {profile:'clean'});
    tap(screen.items, 'Refresh', 'Refresh');
    await sleep(4500);
    screen = record('clean');
    const baseline = screen.driveLetters.join(',');
    if (!baseline) throw Error('Clean baseline has no drives; cannot assess convergence');

    await post('/profile', {profile:'slow800'});
    tap(screen.items, 'Refresh', 'Refresh');
    await sleep(8500);
    screen = record('slow800');
    await post('/outage', {seconds:10});
    tap(screen.items, 'Refresh', 'Refresh');
    await sleep(2500);
    record('outage_active');
    await sleep(10500);
    screen = record('outage_after_link_up_without_refresh');

    await post('/profile', {profile:'clean'});
    tap(screen.items, 'Refresh', 'Refresh');
    await sleep(5000);
    screen = record('clean_manual_recovery');
    evidence.summary = {
      baselineDrives:baseline,
      manualRecovered:screen.driveLetters.join(',') === baseline,
      // Existing cached root entries may survive disconnection; this is NOT
      // evidence of automatic post-outage backend state revalidation.
      postOutageUiVisible: evidence.samples.find(s=>s.label==='outage_after_link_up_without_refresh').driveLetters.join(',') === baseline
    };
  } finally {
    // Never leave an emulator scenario's impairment mode on the local proxy.
    await post('/profile', {profile:'clean'}).catch(()=>{});
    fs.mkdirSync(path.dirname(out), {recursive:true});
    fs.writeFileSync(out, JSON.stringify(evidence,null,2) + '\n', {flag:'wx'});
  }
  console.log('UI evidence: ' + out);
  if (!evidence.summary.manualRecovered) process.exitCode=2;
}
main().catch(error => { console.error('FLIGHTLINK_UI_FAILED: ' + error.message); process.exitCode=1; });
