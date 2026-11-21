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
  const dumped = run(['shell', 'uiautomator', 'dump', '/sdcard/flightlink-window.xml']);
  // On an overloaded emulator uiautomator may print an error yet exit 0.
  // Never mistake a stale XML dump for the current app state.
  if (!/dumped to:/i.test(dumped)) throw Error('Android UI hierarchy unavailable: '+dumped.trim().slice(0,160));
  const xml = run(['shell', 'cat', '/sdcard/flightlink-window.xml']);
  const items = nodes(xml);
  const driveLetters = [...new Set(items.map(n => n.text).filter(t => /^[A-Z]:\\$/.test(t)))].sort();
  const statusLabels = items.map(n => n.text).filter(t =>
    /^(No files found\.|Not connected and root not cached\.|Loading.*|Connecting.*)$/i.test(t)
  );
  const systemDialog = items.map(n=>n.text).find(t=>/keeps stopping|isn.t responding/i.test(t))||null;
  const activity = run(['shell', 'dumpsys', 'activity', 'activities']);
  const foregroundPackage = /topResumedActivity=.*?\su\d+\s+([^\s/]+)\//.exec(activity)?.[1] || null;
  return {items, driveLetters, statusLabels, systemDialog, foregroundPackage, filesScreen: items.some(n => n.text === 'Files: /')};
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
    method: 'POST', headers: {'content-type': 'application/json', connection:'close'}, body: JSON.stringify(body)
  });
  if (!response.ok) throw Error('FlightLink control ' + route + ' HTTP ' + response.status);
}
const evidence = {kind:'real-android-files-ui', timestamp:new Date().toISOString(), serial, samples:[]};
let proxyTrace = null;
function traceEvents() {
  if (!proxyTrace || !fs.existsSync(proxyTrace)) return [];
  // The proxy writes JSONL concurrently; ignore the trailing incomplete line.
  return fs.readFileSync(proxyTrace, 'utf8').split(/\r?\n/)
    .filter(Boolean).flatMap(line => {try{return [JSON.parse(line)];}catch{return [];}});
}
const eventTime = e => Number(e.timeMs) || Date.parse(e.ts);
function observedRefresh(tapMs, endMs) {
  const events = traceEvents();
  const outcomes = new Map(events.filter(e=>e.event==='command_outcome')
    .map(e=>[e.connection+':'+e.invocationId, e]));
  const closures = new Map(events.filter(e=>e.event==='connection_closed')
    .map(e=>[e.connection, e]));
  const requests = events.filter(e=>e.event==='command' &&
    ['GetAvailableDrives','ListDirectory'].includes(e.target) &&
    eventTime(e)>=tapMs && eventTime(e)<=endMs);
  return {
    // Time-window attribution: a background refresh may share this interval.
    attribution:'time_window_only',
    observed:requests.length,
    confirmed:requests.filter(e=>outcomes.get(e.connection+':'+e.invocationId)?.hubReceipt==='confirmed').length,
    hubErrors:requests.filter(e=>outcomes.get(e.connection+':'+e.invocationId)?.status==='hub_error').length,
    commands:requests.map(e=>{
      const outcome=outcomes.get(e.connection+':'+e.invocationId);
      const closed=closures.get(e.connection);
      return {target:e.target, observedAfterTapMs:eventTime(e)-tapMs,
        hubReceipt:outcome?.hubReceipt||'unknown',
        hubStatus:outcome?.status||null,
        hubCompletionAfterTapMs:outcome?.hubReceipt==='confirmed'?eventTime(outcome)-tapMs:null,
        reason:outcome?.reason||(outcome?.hubReceipt==='confirmed'?null:closed?'socket_closed:'+closed.reason:null)};
    })
  };
}
function postOutageAuth(linkUpMs) {
  const events = traceEvents();
  const outcomes = new Map(events.filter(e=>e.event==='command_outcome')
    .map(e=>[e.connection+':'+e.invocationId,e]));
  const authenticated = events.find(e=>e.event==='command' && e.target==='Authenticate' &&
    eventTime(e)>=linkUpMs && outcomes.get(e.connection+':'+e.invocationId)?.status==='hub_completed');
  // No Authenticate event may simply mean the original session survived the outage.
  return {reAuthenticatedAfterLinkUpMs:authenticated?
    eventTime(outcomes.get(authenticated.connection+':'+authenticated.invocationId))-linkUpMs:null,
    interpretation:authenticated?'hub_completion_observed':'not_observed_session_may_be_reused'};
}
async function awaitRefreshReceipt(tapMs, maxWaitMs=12000) {
  const deadline=Date.now()+maxWaitMs;
  while(Date.now()<deadline) {
    const state=observedRefresh(tapMs,Date.now());
    if(state.confirmed>0 || state.hubErrors>0) return;
    await sleep(600);
  }
}
async function awaitAuthAfterLinkUp(linkUpMs, maxWaitMs=45000) {
  // The Android reconnect backoff can exceed the original fixed UI delay.
  // Poll proxy receipts, not the potentially cached Connected badge.
  while(Date.now()<linkUpMs+maxWaitMs) {
    if (postOutageAuth(linkUpMs).reAuthenticatedAfterLinkUpMs!==null) return;
    await sleep(700);
  }
}
function record(label, meta={}) {
  const screen = snapshot();
  const observation = {
    label, timestamp:new Date().toISOString(), driveLetters:screen.driveLetters,
    statusLabels:screen.statusLabels, filesScreen:screen.filesScreen,
    foregroundPackage:screen.foregroundPackage, systemDialog:screen.systemDialog,
    ...(meta.tapMs?{refresh:observedRefresh(meta.tapMs,Date.now())}:{}),
    ...(meta.linkUpMs?{reconnect:postOutageAuth(meta.linkUpMs)}:{})
  };
  evidence.samples.push(observation);
  console.log(JSON.stringify(observation));
  return {...screen, ...observation};
}
async function main() {
  if (run(['get-state']).trim() !== 'device') throw Error('Emulator not ready');
  const pkg = run(['shell', 'dumpsys', 'package', 'com.omni.sync']);
  evidence.androidPackage = {
    versionName: pkg.match(/versionName=([^\r\n]+)/)?.[1] || 'unknown',
    versionCode: pkg.match(/versionCode=(\d+)/)?.[1] || 'unknown'
  };
  const status = await fetch(control + '/status', {headers:{connection:'close'}});
  if (!status.ok) throw Error('Isolated FlightLink proxy is not running');
  const proxyState = await status.json();
  if (typeof proxyState.trace !== 'string' || !proxyState.trace) throw Error('Proxy does not expose its evidence trace');
  proxyTrace = proxyState.trace;
  evidence.proxyTrace = proxyTrace;
  let screen = snapshot();
  if (!screen.filesScreen) {
    tap(screen.items, 'Files', 'Files');
    await sleep(1500);
    screen = snapshot();
  }
  if (!screen.filesScreen) throw Error('Could not navigate to Files root; clear emulator permission dialogs.');
  try {
    await post('/profile', {profile:'clean'});
    let tapMs=Date.now();
    tap(screen.items, 'Refresh', 'Refresh');
    await sleep(4500);
    screen = record('clean',{tapMs});
    const baseline = screen.driveLetters.join(',');
    if (!baseline) throw Error(screen.systemDialog?'Emulator blocked by Android system dialog: '+screen.systemDialog:'Clean baseline has no drives; cannot assess convergence');

    await post('/profile', {profile:'slow800'});
    tapMs=Date.now();
    tap(screen.items, 'Refresh', 'Refresh');
    await sleep(8500);
    screen = record('slow800',{tapMs});
    const outageMs=Date.now();
    await post('/outage', {seconds:10});
    tapMs=Date.now();
    tap(screen.items, 'Refresh', 'Refresh');
    await sleep(2500);
    record('outage_active',{tapMs});
    await sleep(10500);
    const linkUpMs=outageMs+10000;
    screen = record('outage_after_link_up_without_refresh',{linkUpMs});

    // Measure eventual authenticated recovery rather than assuming that 10s
    // after link-up is enough for Android's reconnect backoff.
    await awaitAuthAfterLinkUp(linkUpMs);
    await post('/profile', {profile:'clean'});
    tapMs=Date.now();
    tap(screen.items, 'Refresh', 'Refresh');
    await sleep(5000);
    screen = record('clean_manual_recovery',{tapMs,linkUpMs});
    if(screen.refresh.confirmed===0) {
      // A cached drive list is never enough. Give the Hub another bounded
      // opportunity to complete the read, then snapshot the actual app again.
      await awaitRefreshReceipt(tapMs);
      screen = record('clean_manual_recovery_settled',{tapMs,linkUpMs});
    }
    evidence.summary = {
      baselineDrives:baseline,
      uiDriveIdentityRecovered:screen.driveLetters.join(',') === baseline,
      reAuthenticatedAfterLinkUpMs:screen.reconnect.reAuthenticatedAfterLinkUpMs,
      manualRecoveryHubConfirmed:screen.refresh.confirmed>0 && screen.refresh.hubErrors===0,
      manualRecovered:screen.filesScreen && screen.foregroundPackage==='com.omni.sync' &&
        screen.driveLetters.join(',')===baseline &&
        screen.refresh.confirmed>0 && screen.refresh.hubErrors===0,
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
main().catch(error => { console.error('FLIGHTLINK_UI_FAILED: ' + (error.stack || error.message)); process.exitCode=1; });
