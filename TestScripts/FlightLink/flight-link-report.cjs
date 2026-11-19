#!/usr/bin/env node
// Redacted delivery ledger for REAL Android SignalR traffic through FlightLink.
// An observed request is not proof of Hub admission. Only a Hub completion
// proves delivery; an unacknowledged/fire-and-forget call remains unknown.
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const argv = process.argv.slice(2);
const arg = (name, fallback) => {
  const index = argv.indexOf('--' + name);
  return index < 0 ? fallback : argv[index + 1];
};
const trace = arg('trace', null);
if (!trace) throw Error('Usage: node flight-link-report.cjs --trace <proxy.jsonl> --out <folder>');
const out = path.resolve(arg('out', path.dirname(path.resolve(trace))));
fs.mkdirSync(out, {recursive: true});
const events = fs.readFileSync(trace, 'utf8')
  .split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line));

const pending = new Map();
const ledger = [];
let condition = null;
let matched = 0;
let unmatchedCompletions = 0;
const epochKey = event => String(event.connection) + ':' + String(event.invocationId);
const commandTimestamp = event => Number(event.timeMs) || Date.parse(event.ts);

for (const event of events) {
  if (event.event === 'link') {
    if (event.action === 'chaos_phase') {
      condition = {profile: event.profile, phase: event.phase, ...event.values};
    } else if (event.action === 'profile' || event.action === 'route_switch') {
      condition = {profile: event.profile, phase: null, ...event.values};
    }
  }
  if (event.event === 'command') {
    const hasReceipt = event.invocationId !== null && event.invocationId !== undefined;
    const row = {
      ts: event.ts,
      connection: event.connection,
      profile: event.profile,
      // Effective test condition when the proxy *observed* the client command.
      conditionAtStart: condition?.profile === event.profile ? {...condition} : null,
      invocationId: event.invocationId,
      target: event.target,
      delivery: event.delivery,
      state: hasReceipt ? 'pending_or_lost' : 'fire_and_forget',
      hubReceipt: 'unknown',
      elapsedMs: null,
      failureReason: null,
    };
    ledger.push(row);
    if (hasReceipt) {
      const key = epochKey(event);
      if (!pending.has(key)) pending.set(key, []);
      pending.get(key).push({row, time: commandTimestamp(event)});
    }
  }
  if (event.event === 'command_outcome' && event.invocationId != null) {
    const key = epochKey(event);
    const queue = pending.get(key);
    if (!queue?.length) {unmatchedCompletions++; continue;}
    const {row} = queue.shift();
    if (!queue.length) pending.delete(key);
    matched++;
    row.hubReceipt = event.hubReceipt === 'confirmed' ? 'confirmed' : 'unknown';
    row.elapsedMs = event.elapsedMs ?? null;
    row.failureReason = event.reason || null;
    row.state = row.hubReceipt === 'confirmed'
      ? (event.status === 'hub_error' ? 'hub_error' : 'hub_completed')
      : event.status === 'no_completion' ? 'completion_missing' : 'pending_or_lost';
  }
  if (event.event === 'connection_closed') {
    // Do NOT mark requests unreceived: this only explains why no Hub
    // completion was available at the proxy when the socket closed.
    for (const [key, queue] of pending) {
      for (const item of queue) {
        if (item.row.connection !== event.connection) continue;
        item.row.failureReason = 'connection_closed_before_hub_completion:' + (event.reason || 'unknown');
        item.row.state = 'completion_missing';
        item.row.elapsedMs = Math.max(0, commandTimestamp(event) - item.time);
      }
      const stillOpen = queue.filter(item => item.row.connection !== event.connection);
      if (stillOpen.length) pending.set(key, stillOpen);
      else pending.delete(key);
    }
  }
}
for (const queue of pending.values()) for (const {row} of queue) {
  row.failureReason = 'no_matching_hub_completion_before_trace_end';
}

// A completed invocation can still be lost on the proxy->Android leg. The
// server completion below is evidence of Hub execution, NOT Android UI success.
const phaseCounts = {};
for (const row of ledger) {
  const phase = row.conditionAtStart?.phase || row.profile || 'unspecified';
  if (!phaseCounts[phase]) phaseCounts[phase] = {commands: 0, confirmed: 0, unknown: 0};
  phaseCounts[phase].commands++;
  phaseCounts[phase][row.hubReceipt === 'confirmed' ? 'confirmed' : 'unknown']++;
}
const summary = {
  trace: path.resolve(trace),
  commands: ledger.length,
  hubConfirmed: ledger.filter(row => row.hubReceipt === 'confirmed').length,
  hubError: ledger.filter(row => row.state === 'hub_error').length,
  completionMissing: ledger.filter(row => row.state === 'completion_missing').length,
  fireAndForget: ledger.filter(row => row.state === 'fire_and_forget').length,
  pendingOrLost: ledger.filter(row => row.state === 'pending_or_lost').length,
  confirmedSlowOver10s: ledger.filter(row => row.hubReceipt === 'confirmed' && row.elapsedMs > 10000).length,
  unmatchedCompletions,
  matchedCompletions: matched,
  transportResets: events.filter(event => event.event === 'link' && event.action === 'simulated_loss_reset').length,
  chaosPhases: events.filter(event => event.event === 'link' && event.action === 'chaos_phase').length,
  simulatedBlackouts: events.filter(event => event.event === 'link' && event.action === 'chaos_phase' && event.phase === 'blackout').length,
  outages: events.filter(event => event.event === 'link' && event.action === 'outage').length,
  byConditionAtStart: phaseCounts,
  methods: [...new Set(ledger.map(row => row.target))],
};
const ledgerPath = path.join(out, 'android-delivery-ledger.jsonl');
fs.writeFileSync(ledgerPath, ledger.map(row => JSON.stringify(row)).join('\n') + '\n');
fs.writeFileSync(path.join(out, 'android-summary.json'), JSON.stringify(summary, null, 2) + '\n');
console.log(JSON.stringify(summary, null, 2));
console.log('Ledger: ' + ledgerPath);
