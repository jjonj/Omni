#!/usr/bin/env node
// End-to-end read-only SignalR commands through flight-link-proxy into the LIVE Rust OmniHub.
// Uses real negotiate/WebSocket/Authenticate/GetAvailableDrives, not a mock Hub.
'use strict';
const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const {spawn,execFileSync}=require('node:child_process');
const argv=process.argv.slice(2);
const arg=(name,fallback)=>{const i=argv.indexOf('--'+name);return i<0?fallback:argv[i+1];};
const full=argv.includes('--full');
const reliable=argv.includes('--reliable');
// HTTPS-only runs use the real Hub through a TLS-terminating local listener.
// Trust the isolated test CA with NODE_EXTRA_CA_CERTS before launching Node.
const httpsOnly=argv.includes('--https-only');
const tlsCert=arg('tls-cert',null), tlsKey=arg('tls-key',null);
if(httpsOnly && (!tlsCert || !tlsKey))throw Error('--https-only requires --tls-cert and --tls-key');
const httpScheme=httpsOnly?'https':'http', wsScheme=httpsOnly?'wss':'ws';
// Each run has private ports so a 120-second outage cannot impact another run.
const port=Number(arg('port',String(16000+(process.pid%30000))));
const controlPort=Number(arg('control-port',String(port+1)));
const upstreamPort=Number(arg('upstream-port','5000'));
const dir=path.resolve(arg('out',path.join(process.env.LOCALAPPDATA||'.','OmniHub','flight-link','run-'+Date.now()+'-'+process.pid)));
fs.mkdirSync(dir,{recursive:true});
const resultPath=path.join(dir,'commands.jsonl'), tracePath=path.join(dir,'proxy.jsonl');
if(fs.existsSync(resultPath)||fs.existsSync(tracePath))throw Error('Evidence already exists in '+dir+'; use a fresh --out folder');
const result=fs.createWriteStream(resultPath,{flags:'wx'});
const allRecords=[];
let runId=crypto.randomUUID(),seq=0;
const keyFile=process.env.OMNIHUB_AUTH_CONFIG||path.join(process.env.LOCALAPPDATA||'', 'OmniHub','config','appsettings.json');
const key=JSON.parse(fs.readFileSync(keyFile,'utf8')).AuthApiKey;
if(typeof key!=='string'||!key)throw Error('Missing Rust Hub key in OMNIHUB_AUTH_CONFIG or local appsettings');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
function record(type,fields={}){const obj={ts:new Date().toISOString(),runId,type,...fields};allRecords.push(obj);const line=JSON.stringify(obj);result.write(line+'\n');process.stdout.write(line+'\n');}
async function control(route,body){
  const response=await fetch('http://127.0.0.1:'+controlPort+route,{method:'POST',headers:{'Content-Type':'application/json','X-Flight-Instance':runId},body:JSON.stringify(body),signal:AbortSignal.timeout(10000)});
  if(!response.ok)throw Error('Control '+route+' failed: '+response.status+' '+await response.text());
}
class Session{
  constructor(ws){this.ws=ws;this.records=[];this.partial='';this.closed=null;this.ws.addEventListener('message',event=>{
    this.partial+=String(event.data);
    const parts=this.partial.split('\x1e');this.partial=parts.pop();
    for(const item of parts){try{this.records.push(JSON.parse(item))}catch{}}
  });
  this.ws.addEventListener('close',e=>{this.closed='ws_closed_'+e.code;});
  this.ws.addEventListener('error',()=>{this.closed='ws_error';});
  }
  async next(predicate,ms){
    const until=Date.now()+ms;
    while(Date.now()<until){
      const i=this.records.findIndex(predicate);
      if(i>=0)return this.records.splice(i,1)[0];
      if(this.closed)throw Error(this.closed);
      await sleep(20);
    }
    throw Error('response_timeout_'+ms+'ms');
  }
  send(payload){if(this.ws.readyState!==WebSocket.OPEN)throw Error('ws_not_open');this.ws.send(JSON.stringify(payload)+'\x1e');}
  async invoke(scenario,target,args=[],timeout=18000){
    const invocationId='flight-'+runId.slice(0,8)+'-'+(++seq);
    const started=Date.now();
    try {
      this.send({type:1,invocationId,target,arguments:args});
      const response=await this.next(record=>record.type===3&&record.invocationId===invocationId,timeout);
      if(response.error)throw Error('hub_error:'+String(response.error).slice(0,140));
      record('command',{scenario,invocationId,target,outcome:'completed',hubReceipt:'confirmed',elapsedMs:Date.now()-started});
      return response.result;
    } catch(e) {
      record('command',{scenario,invocationId,target,outcome:'failed',hubReceipt:'unknown',elapsedMs:Date.now()-started,reason:e.message});
      throw e;
    }
  }
  close(){try{this.ws.close()}catch{}}
}
async function connect(scenario,timeout=10000){
  const begun=Date.now();
  const response=await fetch(httpScheme+'://127.0.0.1:'+port+'/signalrhub/negotiate?negotiateVersion=1',
    {method:'POST',signal:AbortSignal.timeout(timeout)});
  if(!response.ok)throw Error('negotiate_status_'+response.status);
  const data=await response.json();
  if(!data.connectionToken)throw Error('negotiate_missing_token');
  const ws=new WebSocket(wsScheme+'://127.0.0.1:'+port+'/signalrhub?id='+encodeURIComponent(data.connectionToken));
  try {
    await Promise.race([
      new Promise((resolve,reject)=>{
        ws.addEventListener('open',resolve,{once:true});
        ws.addEventListener('error',()=>reject(Error('websocket_connect_error')),{once:true});
      }),
      sleep(timeout).then(()=>{throw Error('websocket_connect_timeout');})
    ]);
  }catch(e){ws.close();throw e;}
  const conn=new Session(ws);
  try{
    conn.send({protocol:'json',version:1});
    const handshake=await conn.next(m=>!('type' in m),timeout);
    if(handshake.error)throw Error('signalr_handshake_error');
    const authorized=await conn.invoke(scenario,'Authenticate',[key],timeout);
    if(authorized!==true)throw Error('hub_authentication_failed');
  }catch(e){conn.close();throw e;}
  record('connected',{scenario,elapsedMs:Date.now()-begun,transport:httpsOnly?'HTTPS negotiate + WSS':'HTTP negotiate + WebSocket'});
  return conn;
}
// GetAvailableDrives includes a freshly generated last_modified timestamp.
// Compare stable drive identity, not ephemeral timestamps or free-space counters.
function fingerprint(data) {
  const identity=Array.isArray(data)?data.map(d=>String(d.path||d.name||'')).sort():data;
  return crypto.createHash('sha256').update(JSON.stringify(identity)).digest('hex').slice(0,16);
}

// Optional OMN-0086 safe end-to-end receipt probe. ProcessMacro only rewrites
// text; it does not launch any process or modify the PC. The production Hub
// must already contain RunReliableCommand / GetReliableCommandStatus.
async function reliableAttempt(scenario) {
  const id=crypto.randomUUID(), issuedAt=Date.now();
  const method='ProcessMacro', args=['echo flight-receipt-probe'];
  const started=Date.now();
  let first=null, received=false;
  let conn=null;
  try {
    conn=await connect(scenario+'_reliable_initial',30000);
    first=await conn.invoke(scenario+'_reliable_initial','RunReliableCommand',
      [id,method,args,issuedAt],30000);
    received=true;
  } catch(error) {
    record('reliable_initial_uncertain',{scenario,id,reason:error.message,
      elapsedMs:Date.now()-started});
  } finally {conn?.close();}
  // Lost completion does NOT justify reissuing with a different request ID.
  // Reconnect and query the durable Hub receipt first.
  let queried=null, replays=0, lastError=null;
  for(let i=0;i<5;i++){
    let next;
    try {
      next=await connect(scenario+'_reliable_resume',30000);
      queried=await next.invoke(scenario+'_reliable_status',
        'GetReliableCommandStatus',[id],30000);
      if(queried.state==='not_found'){
        replays++;
        queried=await next.invoke(scenario+'_reliable_replay',
          'RunReliableCommand',[id,method,args,issuedAt],30000);
      } else if(queried.state==='pending'){
        lastError='hub_pending';
        continue;
      }
      const duplicate=await next.invoke(scenario+'_reliable_duplicate',
        'RunReliableCommand',[id,method,args,issuedAt],30000);
      const passed=queried.state==='succeeded' &&
        duplicate.state==='succeeded' && duplicate.id===id;
      record('reliable_outcome',{scenario,id,initialReceipt:received?first?.state:'lost',
        finalState:queried.state,duplicateState:duplicate.state,
        replayWithSameId:replays,passed,elapsedMs:Date.now()-started});
      return;
    }catch(error){lastError=error.message;}
    finally{next?.close();}
    await sleep(1000*Math.min(i+1,5));
  }
  record('reliable_outcome',{scenario,id,initialReceipt:received?first?.state:'lost',
    finalState:queried?.state||'unknown',replayWithSameId:replays,
    passed:false,elapsedMs:Date.now()-started,reason:lastError});
}

async function attempt(scenario,profile){
  await control('/profile',{profile});
  let conn;
  try{
    conn=await connect(scenario);
    const data=await conn.invoke(scenario,'GetAvailableDrives',[],18000);
    const state=fingerprint(data);
    record('state',{scenario,stateFingerprint:state,entries:Array.isArray(data)?data.length:null});
    return state;
  }catch(e){record('scenario',{scenario,status:'failed',reason:e.message});return null;}
  finally{
    conn?.close();
    if(reliable)await reliableAttempt(scenario);
  }
}
async function recover(scenario,expected,from){
  let connected,attempts=0,reason;
  const deadline=Date.now()+45000;
  while(Date.now()<deadline&&!connected){
    attempts++;
    try{connected=await connect(scenario);}catch(e){
      reason=e.message;
      await sleep(Math.min(2000*2**Math.min(attempts,4),10000));
    }
  }
  if(!connected){record('recovery',{scenario,status:'failed',attempts,reason});return;}
  try{
    const state=fingerprint(await connected.invoke(scenario,'GetAvailableDrives',[],18000));
    record('recovery',{scenario,status:'connected',attempts,
      afterLinkUpMs:Date.now()-from,converged:expected?state===expected:null,stateFingerprint:state});
  }catch(e){record('recovery',{scenario,status:'connected_but_state_failed',attempts,reason:e.message});}
  finally{connected.close();}
}
async function disruption(scenario,duration,expected) {
  await control('/profile',{profile:'clean'});
  let conn;
  try{
    conn=await connect(scenario+'_pre');
    const state=fingerprint(await conn.invoke(scenario+'_pre','GetAvailableDrives'));
    const began=Date.now();
    await control('/outage',{seconds:duration});
    // An invocation attempted after disconnection is NOT proof the Hub got it.
    try{await conn.invoke(scenario+'_during','GetAvailableDrives',[],3000);}
    catch(e){record('disconnected_attempt',{scenario,reason:e.message,hubReceipt:'unknown'});}
    conn.close();conn=null;
    await sleep(duration*1000+200);
    await recover(scenario,expected||state,began+duration*1000);
  }catch(e){record('scenario',{scenario,status:'failed',reason:e.message});}
  finally{conn?.close();}
}
async function switchPath(expected){
  await control('/profile',{profile:'clean'});
  let conn;
  try{
    conn=await connect('route_change_pre',10000);
    const state=fingerprint(await conn.invoke('route_change_pre','GetAvailableDrives'));
    const begin=Date.now();
    await control('/switch',{profile:'slow300'});
    conn.close();conn=null;
    await recover('route_change',expected||state,begin);
  }catch(e){record('scenario',{scenario:'route_change',status:'failed',reason:e.message});}
  finally{conn?.close();}
}
function reconcile() {
  // Proxy observes the Hub completion BEFORE it is delayed/dropped downstream:
  // distinguish "Hub received it" from "Android-style client saw a completion".
  const events=fs.existsSync(tracePath)?fs.readFileSync(tracePath,'utf8').split(/\r?\n/).filter(Boolean).map(line=>JSON.parse(line)):[];
  const observed=new Map(),receipts=new Map();
  for(const e of events){
    if(e.event==='command'&&e.invocationId)observed.set(e.invocationId,e);
    if(e.event==='command_outcome'&&e.invocationId)receipts.set(e.invocationId,e);
  }
  const ledger=allRecords.filter(e=>e.type==='command').map(item=>{
    const seen=observed.get(item.invocationId), receipt=receipts.get(item.invocationId);
    const hubDelivery=receipt?.hubReceipt==='confirmed'?'confirmed':
      (!seen&&item.reason==='ws_not_open'?'not_sent':'unknown');
    const classification=item.outcome==='completed'?'completed':
      hubDelivery==='confirmed'?'completion_lost_after_hub':
      hubDelivery==='not_sent'?'not_sent':'delivery_unknown';
    return {ts:item.ts,scenario:item.scenario,invocationId:item.invocationId,target:item.target,
      clientOutcome:item.outcome,clientReason:item.reason||null,elapsedMs:item.elapsedMs,
      hubDelivery,hubOutcome:receipt?.status||null,proxyReason:receipt?.reason||null,classification};
  });
  const ledgerPath=path.join(dir,'delivery-ledger.jsonl');
  fs.writeFileSync(ledgerPath,ledger.map(e=>JSON.stringify(e)).join('\n')+'\n');
  const summary={commands:ledger.length,clientFailed:ledger.filter(e=>e.clientOutcome==='failed').length,
    hubConfirmed:ledger.filter(e=>e.hubDelivery==='confirmed').length,
    completedButNotDelivered:ledger.filter(e=>e.classification==='completion_lost_after_hub').length,
    unknown:ledger.filter(e=>e.classification==='delivery_unknown').length,
    notSent:ledger.filter(e=>e.classification==='not_sent').length,
    recovered:allRecords.filter(e=>e.type==='recovery'&&e.status==='connected').length,
    converged:allRecords.filter(e=>e.type==='recovery'&&e.converged===true).length};
  fs.writeFileSync(path.join(dir,'summary.json'),JSON.stringify(summary,null,2)+'\n');
  record('summary',{...summary,ledgerPath});
}
async function run(){
  let child;
  try{
    const options={cwd:__dirname,stdio:['ignore','ignore','pipe'],windowsHide:true};
    child=spawn(process.execPath,[path.join(__dirname,'flight-link-proxy.cjs'),'--port',String(port),
      '--control-port',String(controlPort),'--upstream-port',String(upstreamPort),'--trace',tracePath,'--instance-token',runId,
      ...(httpsOnly?['--tls-cert',tlsCert,'--tls-key',tlsKey]:[])],options);
    child.on('error',e=>record('proxy_error',{reason:e.message}));
    let ready=false;
    for(let i=0;i<70;i++){
      if(child.exitCode!==null)throw Error('Proxy failed to start');
      try{
        const s=await fetch('http://127.0.0.1:'+controlPort+'/status');
        if(s.ok){
          const state=await s.json();
          if(state.instanceToken!==runId)throw Error('Another flight test owns control port '+controlPort);
          ready=true;break;
        }
      }catch(e){if(String(e.message).includes('owns control port'))throw e;}
      await sleep(100);
    }
    if(!ready)throw Error('Proxy startup timeout');
    record('run_start',{dir,full,reliable,httpsOnly,port,controlPort,profiles:['clean','slow300','slow800','slow2000','noUdp',...(httpsOnly?['httpsOnly']:[]),'route_change','outage10',...(full?['outage120']:[])]});
    await attempt('clean','clean');
    for(const profile of ['slow300','slow800','slow2000','noUdp',...(httpsOnly?['httpsOnly']:[])])await attempt(profile,profile);
    await switchPath();
    await disruption('outage10',10);
    if(full)await disruption('outage120',120);
    record('run_end',{dir});
  }finally{
    if(child){
      child.kill('SIGTERM');
      await sleep(350);
      if(child.exitCode===null&&process.platform==='win32'){
        try{execFileSync('taskkill',['/PID',String(child.pid),'/T','/F'],{timeout:5000,stdio:'ignore'});}catch{}
      }else if(child.exitCode===null){child.kill('SIGKILL');}
    }
    await sleep(150);
    reconcile();
    result.end();
  }
}
run().catch(e=>{record('fatal',{reason:e.message});process.exitCode=1;result.end();});
