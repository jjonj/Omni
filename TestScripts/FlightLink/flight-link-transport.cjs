#!/usr/bin/env node
// OMN-0085: read-only host-side Tailscale route snapshot for the FlightLink evidence.
// A localhost shaper cannot prove that Android reached this machine by direct UDP or DERP.
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');

const argv = process.argv.slice(2);
const flag = name => {
  const i = argv.indexOf('--' + name);
  return i < 0 ? null : argv[i + 1];
};
const output = flag('out');
const peerSelector = flag('peer');
if (!output || output.startsWith('--') || (argv.includes('--peer') && !peerSelector)) {
  throw Error('Usage: node flight-link-transport.cjs --out <new.json> [--peer <phone-hostname-or-tailnet-IP>]');
}
const out = path.resolve(output);
if (fs.existsSync(out)) throw Error('Refusing to overwrite existing evidence: ' + out);

function command(args, timeout = 30000) {
  try {
    return {ok: true, output: execFileSync('tailscale', args, {
      encoding: 'utf8', timeout, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']
    }).trim()};
  } catch (error) {
    // Never include the full Tailscale status JSON or token-bearing command arguments.
    return {ok: false, output: String(error.stdout || '').trim(),
      error: String(error.stderr || error.message || 'command_failed').trim().slice(0, 300)};
  }
}
const statusCall = command(['status', '--json']);
if (!statusCall.ok) throw Error('tailscale status failed: ' + statusCall.error);
const status = JSON.parse(statusCall.output);
const peers = Object.values(status.Peer || {});
const isIngress = p => p.HostName === 'funnel-ingress-node';
const devices = peers.filter(p => !isIngress(p));
const peerNames = p => [p.HostName, p.DNSName, ...(p.TailscaleIPs || [])]
  .filter(Boolean).map(v => String(v).toLowerCase().replace(/\.$/, ''));
const selected = peerSelector
  ? devices.filter(p => peerNames(p).includes(peerSelector.toLowerCase().replace(/\.$/, '')))
  : [];
if (peerSelector && selected.length !== 1) {
  throw Error('Expected exactly one existing tailnet peer for --peer; found ' + selected.length +
    '. Check the phone is signed into this tailnet first.');
}
const netcheckCall = command(['netcheck', '--format=json'], 40000);
let netcheck = null;
if (netcheckCall.ok) {
  try { netcheck = JSON.parse(netcheckCall.output); }
  catch { /* Explicit unknown rather than fabricated metrics. */ }
}
let ping = null;
if (selected.length) {
  const target = selected[0];
  const host = (target.TailscaleIPs || []).find(ip => ip.includes('.')) || target.DNSName || target.HostName;
  const result = command(['ping', '--c', '5', '--until-direct=false', '--timeout=3s', host], 25000);
  // Echo only route classification and timings, not arbitrary shell output.
  const responses = result.output.split(/\r?\n/).filter(line => /pong from/i.test(line));
  const routes = responses.map(line => /via\s+DERP\b/i.test(line) ? 'derp'
    : /via\s+(?:\d{1,3}\.){3}\d{1,3}:\d+/i.test(line) ||
      /via\s+\[[0-9a-f:]+\]:\d+/i.test(line) ? 'direct'
    : 'unclassified');
  ping = {
    responses: routes.length,
    direct: routes.filter(route => route === 'direct').length,
    derp: routes.filter(route => route === 'derp').length,
    unclassified: routes.filter(route => route === 'unclassified').length,
    lastObservedPath: routes.at(-1) || 'none',
    error: result.ok ? null : result.error
  };
}
const snapshot = {
  capturedAt: new Date().toISOString(),
  source: 'read-only tailscale status/netcheck; optional explicitly selected peer ping',
  host: {backend: status.BackendState || null,
    hostname: status.Self?.HostName || null,
    ipv4: (status.Self?.TailscaleIPs || []).find(ip => ip.includes('.')) || null},
  peers: {knownDevices: devices.length, onlineDevices: devices.filter(p => p.Online).length,
    activeDevices: devices.filter(p => p.Active).length,
    funnelIngressNodesExcluded: peers.length - devices.length},
  localNetcheck: netcheck ? {
    udpAvailableHere: netcheck.UDP ?? null,
    ipv4AvailableHere: netcheck.IPv4 ?? null,
    ipv6AvailableHere: netcheck.IPv6 ?? null,
    nearestDerpRegionId: netcheck.PreferredDERP ?? null
  } : {error: netcheckCall.error || 'no_parseable_netcheck'},
  targetPeer: selected.length ? {
    hostname: selected[0].HostName || null,
    tailnetOnline: selected[0].Online ?? null,
    tailnetActive: selected[0].Active ?? null,
    ping
  } : null,
  actualAndroidPath: !selected.length ? 'unverified_no_selected_phone_peer'
    : ping.direct ? 'direct_observed_from_this_host'
    : ping.derp ? 'derp_observed_from_this_host'
    : 'unverified_no_successful_route_probe',
  limitations: [
    'Host UDP support does not establish UDP availability from the aircraft, Android phone, or China.',
    'A DERP ping does not establish that the Android OmniHub app can negotiate or reconnect.',
    'Without --peer, no phone path was probed; no evidence of direct or relay connectivity is implied.',
    'A localhost impairment profile named noUdp is not a Tailscale UDP-block or DERP simulation.'
  ]
};
fs.mkdirSync(path.dirname(out), {recursive: true});
fs.writeFileSync(out, JSON.stringify(snapshot, null, 2) + '\n', {flag: 'wx'});
console.log(JSON.stringify(snapshot, null, 2));
