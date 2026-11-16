#!/usr/bin/env node
// OMN-0085: isolated TCP shaper for the real SignalR HTTP/WebSocket endpoint.
// This is NOT kernel packet loss / Tailscale DERP emulation; see README.
'use strict';
const net = require('node:net');
const http = require('node:http');
const tls = require('node:tls');
const fs = require('node:fs');
const path = require('node:path');

const profiles = {
  clean:    {rttMs:0,    jitterMs:0,   kbps:0,   lossPct:0},
  slow300:  {rttMs:300,  jitterMs:75,  kbps:64,  lossPct:1},
  slow800:  {rttMs:800,  jitterMs:200, kbps:32,  lossPct:3},
  slow2000: {rttMs:2000, jitterMs:500, kbps:16,  lossPct:10},
  noUdp:    {rttMs:800,  jitterMs:200, kbps:32,  lossPct:0, note:'TCP-only; Tailscale routing NOT emulated'},
  httpsOnly:{rttMs:800,  jitterMs:200, kbps:32,  lossPct:0, note:'requires --tls-key/--tls-cert; does NOT enforce external firewall'},
};
const argv = process.argv.slice(2);
function arg(name, fallback) { const i=argv.indexOf('--'+name); return i<0?fallback:argv[i+1]; }
const listen = arg('listen', '127.0.0.1'), port=Number(arg('port','15000'));
const instanceToken=arg('instance-token','');
const controlPort=Number(arg('control-port','15001'));
const upstreamHost=arg('upstream-host','127.0.0.1'), upstreamPort=Number(arg('upstream-port','5000'));
const tlsCert=arg('tls-cert',null), tlsKey=arg('tls-key',null);
if(Boolean(tlsCert)!==Boolean(tlsKey))throw Error('Provide both --tls-cert and --tls-key');
let profileName=arg('profile','clean'), current=profiles[profileName];
if(!current) throw Error('Unknown profile '+profileName);
if(profileName==='httpsOnly'&&!tlsCert)throw Error('httpsOnly requires --tls-cert/--tls-key and a trusted Android certificate');
const seed=Number(arg('seed','850085'))>>>0;
let randomState=seed||1;
function rand(){ randomState^=randomState<<13;randomState^=randomState>>>17;randomState^=randomState<<5;return (randomState>>>0)/4294967296; }
const output=arg('trace',path.join(process.cwd(),'flight-link-trace.jsonl'));
fs.mkdirSync(path.dirname(output),{recursive:true});
const trace=fs.createWriteStream(output,{flags:'a'});
let epoch=0, blockedUntil=0, connectionCounter=0;
const connections=new Set(), pending=new Map();
function log(event,fields={}) {
  const line=JSON.stringify({ts:new Date().toISOString(),timeMs:Date.now(),event,profile:profileName,epoch,...fields});
  trace.write(line+'\n');
  if(['command','command_outcome','link','startup','error'].includes(event))
    process.stdout.write(line+'\n');
}
function noteCommand(conn,record) {
  if(record.type===1) {
    const target=typeof record.target==='string'?record.target:'unknown';
    const invocationId=record.invocationId==null?null:String(record.invocationId);
    const token=invocationId?conn.id+':'+invocationId:null;
    // Never log arguments: Authenticate contains the API key.
    log('command',{connection:conn.id,invocationId,target,delivery:'observed_client_side',
      hubReceipt:'unknown',ackExpected:!!invocationId});
    if(token) pending.set(token,{connection:conn.id,invocationId,target,started:Date.now()});
  } else if(record.type===3 && record.invocationId!=null) {
    const key=conn.id+':'+record.invocationId, item=pending.get(key);
    if(item) {
      pending.delete(key);
      log('command_outcome',{...item,elapsedMs:Date.now()-item.started,
        status:record.error?'hub_error':'hub_completed',
        // A completion from the Hub is affirmative proof of delivery.
        hubReceipt:'confirmed',reason:record.error?String(record.error).slice(0,150):null});
    }
  }
}
// Observe only JSON metadata at the client/server boundary; never persist payload/keys.
class Observer {
  constructor(conn,direction) {this.conn=conn;this.direction=direction;this.buf=Buffer.alloc(0);this.stage='http';this.partial='';this.isWs=false;this.seenFirstFrame=false; }
  push(chunk) {
    if(this.stage==='ignore') return;
    this.buf=Buffer.concat([this.buf,chunk]);
    if(this.buf.length>4*1024*1024) {this.buf=Buffer.alloc(0);this.stage='ignore';return;}
    if(this.stage==='http'){
      const end=this.buf.indexOf('\r\n\r\n');
      if(end<0) return;
      const start=this.buf.subarray(0,end).toString('latin1');
      const isWs=this.direction==='c2s'? /^GET \S*signalrhub|^GET \S*terminalhub/i.test(start) && /upgrade:\s*websocket/i.test(start)
        : /^HTTP\/1\.\d 101/.test(start);
      if(this.direction==='c2s'){
        const method=start.split(' ')[0], route=start.split(' ')[1]?.split('?')[0];
        log('http_request',{connection:this.conn.id,method,route:route||'unknown',webSocketUpgrade:isWs});
      } else {
        log('http_response',{connection:this.conn.id,statusCode:(/^HTTP\/1\.\d (\d+)/.exec(start)||[])[1]||'unknown',webSocketUpgrade:isWs});
      }
      if(!isWs){this.stage='ignore';this.buf=Buffer.alloc(0);return;}
      this.stage='ws';this.isWs=true;this.buf=this.buf.subarray(end+4);
    }
    while(this.stage==='ws' && this.buf.length>=2){
      const masked=(this.buf[1]&128)!==0, opcode=this.buf[0]&15;
      let len=this.buf[1]&127, pos=2;
      if(len===126){if(this.buf.length<4)return;len=this.buf.readUInt16BE(2);pos=4;}
      else if(len===127){if(this.buf.length<10)return;const size=this.buf.readBigUInt64BE(2);if(size>4n*1024n*1024n){this.stage='ignore';return;}len=Number(size);pos=10;}
      const maskOffset=pos;if(masked)pos+=4;
      if(this.buf.length<pos+len)return;
      if(!this.seenFirstFrame){this.seenFirstFrame=true;log('ws_first_frame',{connection:this.conn.id,direction:this.direction,opcode,masked,length:len});}
      // Android Java SignalR sends JSON protocol records in binary WebSocket frames.
      if(opcode===1||opcode===2||opcode===0){
        const payload=Buffer.from(this.buf.subarray(pos,pos+len));
        if(masked)for(let i=0;i<payload.length;i++)payload[i]^=this.buf[maskOffset+i%4];
        this.partial+=payload.toString('utf8');
        const items=this.partial.split('\x1e');this.partial=items.pop();
        if(this.partial.length>1024*1024)this.partial='';
        for(const item of items) {
          try {
            const record=JSON.parse(item);
            if(this.direction==='c2s')noteCommand(this.conn,record);
            else if(record.type===3)noteCommand(this.conn,record);
          }catch{} // handshake and non-JSON frames
        }
      }
      this.buf=this.buf.subarray(pos+len);
    }
  }
}
function abandon(conn,reason) {
  for(const [key,item] of pending){
    if(item.connection!==conn.id)continue;
    pending.delete(key);
    log('command_outcome',{...item,elapsedMs:Date.now()-item.started,
      status:'no_completion',hubReceipt:'unknown',reason});
  }
}
function destroy(conn,reason) {
  if(conn.dead)return;
  conn.dead=true; connections.delete(conn);abandon(conn,reason);
  log('connection_closed',{connection:conn.id,reason});
  conn.client.destroy();conn.server.destroy();
}
function delayFor(bytes) {
  const latency=Math.max(0,current.rttMs/2+(rand()*2-1)*current.jitterMs/2);
  const transmit=current.kbps?bytes*8/current.kbps:0; // kbps equals bits/ms
  return latency+transmit;
}
function makePipe(conn,source,dest,direction) {
  let tail=0;
  let queued=0;
  const observer=new Observer(conn,direction);
  source.on('data',data=>{
    if(conn.dead)return;
    observer.push(data);
    // Reset probability per ~MTU-equivalent input packet. This models lost
    // transport, NOT TCP packet-level retransmission.
    const packetCount=Math.ceil(data.length/1400);
    if(current.lossPct>0 && rand()<1-Math.pow(1-current.lossPct/100,packetCount)){
      log('link',{action:'simulated_loss_reset',connection:conn.id,lossPct:current.lossPct});
      destroy(conn,'simulated_loss_reset');return;
    }
    const chunks=[];
    for(let i=0;i<data.length;i+=1400)chunks.push(data.subarray(i,i+1400));
    for(const chunk of chunks){
      const delay=delayFor(chunk.length), now=Date.now();
      tail=Math.max(tail,now)+delay;
      queued+=chunk.length;
      if(queued>2*1024*1024){destroy(conn,'shaper_queue_overflow');return;}
      setTimeout(()=>{
        queued-=chunk.length;
        if(conn.dead)return;
        if(Date.now()<blockedUntil){destroy(conn,'outage');return;}
        if(!dest.writable){destroy(conn,'peer_not_writable');return;}
        dest.write(chunk);
      },Math.max(0,Math.ceil(tail-now)));
    }
  });
  source.on('error',err=>destroy(conn,direction+':'+err.code));
  source.on('close',()=>destroy(conn,direction+':closed'));
}
function onClient(client){
  const id=++connectionCounter;
  if(Date.now()<blockedUntil){client.destroy();log('connection_refused',{connection:id,reason:'outage'});return;}
  const remote=net.connect({host:upstreamHost,port:upstreamPort});
  const conn={id,client,server:remote,dead:false};
  connections.add(conn);
  log('connection_open',{connection:id});
  remote.on('error',err=>destroy(conn,'upstream:'+err.code));
  makePipe(conn,client,remote,'c2s');makePipe(conn,remote,client,'s2c');
}
const server=tlsCert?tls.createServer({cert:fs.readFileSync(tlsCert),key:fs.readFileSync(tlsKey)},onClient):net.createServer(onClient);
server.on('error',e=>{log('error',{message:e.message});process.exitCode=1;});
const api=http.createServer((req,res)=>{
  if(req.method==='GET'&&req.url==='/status'){
    res.setHeader('Content-Type','application/json');
    return res.end(JSON.stringify({instanceToken,profile:profileName,values:current,outageRemainingMs:Math.max(0,blockedUntil-Date.now()),connections:connections.size,trace:output}));
  }
  if(req.method!=='POST'|| !['/profile','/outage','/switch'].includes(req.url)){res.writeHead(404);return res.end();}
  if(instanceToken && req.headers['x-flight-instance']!==instanceToken){res.writeHead(403);return res.end('Wrong test instance');}
  let body='';req.on('data',c=>{body+=c;if(body.length>2000)req.destroy();});
  req.on('end',()=>{
    try{
      const input=JSON.parse(body||'{}');
      if(req.url==='/outage'){
        const seconds=Number(input.seconds);
        if(!Number.isFinite(seconds)||seconds<0||seconds>120)throw Error('seconds must be 0..120');
        blockedUntil=Date.now()+seconds*1000;
        for(const conn of [...connections])destroy(conn,'outage');
        log('link',{action:'outage',seconds});
      }else{
        if(!profiles[input.profile])throw Error('Unknown profile');
        if(input.profile==='httpsOnly'&&!tlsCert)throw Error('httpsOnly requires TLS listener and a trusted certificate');
        profileName=input.profile;current=profiles[profileName];
        epoch++;
        if(req.url==='/switch')for(const conn of [...connections])destroy(conn,'route_change');
        log('link',{action:req.url==='/switch'?'route_switch':'profile',values:current});
      }
      res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({ok:true,profile:profileName}));
    }catch(e){res.writeHead(400);res.end(JSON.stringify({error:e.message}));}
  });
});
api.listen(controlPort,'127.0.0.1');
server.listen(port,listen,()=>log('startup',{listen,port,controlPort,upstreamHost,upstreamPort,seed,trace:output,tls:!!tlsCert}));
process.on('SIGINT',()=>{for(const c of [...connections])destroy(c,'operator_stop');server.close();api.close();trace.end();});
