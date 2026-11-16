#!/usr/bin/env node
// Redacted evidence ledger for a REAL Android SignalR session through flight-link-proxy.
// Never reads or prints payloads/authentication arguments.
'use strict';
const fs=require('node:fs'),path=require('node:path');
const argv=process.argv.slice(2);
const arg=(n,d)=>{const i=argv.indexOf('--'+n);return i<0?d:argv[i+1];};
const trace=arg('trace',null);
if(!trace)throw Error('Usage: node flight-link-report.cjs --trace <proxy.jsonl> --out <folder>');
const out=path.resolve(arg('out',path.dirname(path.resolve(trace))));
fs.mkdirSync(out,{recursive:true});
const events=fs.readFileSync(trace,'utf8').split(/\r?\n/).filter(Boolean).map(line=>JSON.parse(line));
const completions=new Map();
for(const event of events){
  if(event.event==='command_outcome'&&event.invocationId!=null)
    completions.set(event.connection+':'+event.invocationId,event);
}
const ledger=events.filter(event=>event.event==='command').map(command=>{
  const outcome=command.invocationId==null?null:completions.get(command.connection+':'+command.invocationId);
  const state=command.invocationId==null?'fire_and_forget':
    outcome?.hubReceipt==='confirmed'?'hub_completed':
    outcome?.status==='no_completion'?'completion_missing':'pending_or_lost';
  return {
    ts:command.ts,connection:command.connection,profile:command.profile,
    invocationId:command.invocationId,target:command.target,delivery:command.delivery,
    state,hubReceipt:outcome?.hubReceipt||'unknown',elapsedMs:outcome?.elapsedMs??null,
    failureReason:outcome?.reason||null,
  };
});
const summary={
  trace:path.resolve(trace),commands:ledger.length,
  hubConfirmed:ledger.filter(e=>e.hubReceipt==='confirmed').length,
  completionMissing:ledger.filter(e=>e.state==='completion_missing').length,
  fireAndForget:ledger.filter(e=>e.state==='fire_and_forget').length,
  pendingOrLost:ledger.filter(e=>e.state==='pending_or_lost').length,
  transportResets:events.filter(e=>e.event==='link'&&e.action==='simulated_loss_reset').length,
  outages:events.filter(e=>e.event==='link'&&e.action==='outage').length,
  methods:[...new Set(ledger.map(e=>e.target))],
};
const ledgerPath=path.join(out,'android-delivery-ledger.jsonl');
fs.writeFileSync(ledgerPath,ledger.map(e=>JSON.stringify(e)).join('\n')+'\n');
fs.writeFileSync(path.join(out,'android-summary.json'),JSON.stringify(summary,null,2)+'\n');
console.log(JSON.stringify(summary,null,2));
console.log('Ledger: '+ledgerPath);
