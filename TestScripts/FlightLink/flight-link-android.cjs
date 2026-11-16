#!/usr/bin/env node
// Instrument the REAL debug Android APK on a disposable emulator via adb reverse.
// Never run this on a physical phone; existing phone configuration is untouched.
'use strict';
const fs=require('node:fs'),path=require('node:path');
const {execFileSync}=require('node:child_process');
const argv=process.argv.slice(2);
const arg=(key,def)=>{const i=argv.indexOf('--'+key);return i<0?def:argv[i+1];};
const serial=arg('serial',''),profile=arg('profile','slow300'),seconds=Number(arg('seconds','25'));
if(!/^emulator-\d+$/.test(serial))throw Error('Explicit --serial emulator-NNNN required. Physical phones are not modified.');
if(!Number.isFinite(seconds)||seconds<5||seconds>180)throw Error('seconds must be 5..180');
const apk=arg('apk',path.resolve(__dirname,'../../OmniSync.Android/app/build/outputs/apk/debug/app-debug.apk'));
const configFile=process.env.OMNIHUB_AUTH_CONFIG||path.join(process.env.LOCALAPPDATA||'','OmniHub','config','appsettings.json');
const apiKey=JSON.parse(fs.readFileSync(configFile,'utf8')).AuthApiKey;
if(!apiKey)throw Error('Missing Hub AuthApiKey');
const adb=process.env.ADB_PATH||'adb';
function run(args,ms=45000){return execFileSync(adb,['-s',serial,...args],{encoding:'utf8',timeout:ms,windowsHide:true}).trim();}
const text=run(['get-state']);
if(text!=='device')throw Error('Emulator not online: '+text);
function xml(value){return String(value).replace(/[&<>"]/g,ch=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[ch]));}
const prefs='<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n'
 +'<string name="hub_url">http://127.0.0.1:15000/signalrhub</string>\n'
 +'<string name="wan_ip"></string>\n'
 +'<string name="api_key">'+xml(apiKey)+'</string>\n</map>\n';
async function main(){
  const response=await fetch('http://127.0.0.1:15001/status');
  if(!response.ok)throw Error('flight-link-proxy control not responding');
  const set=await fetch('http://127.0.0.1:15001/profile',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({profile})});
  if(!set.ok)throw Error('Profile rejected '+set.status+' '+await set.text());
  if(!fs.existsSync(apk))throw Error('Debug APK not found: '+apk);
  const scratch='E:/_AgentScratch/Tasks/Omni/OMN-0085/Temporary';
  fs.mkdirSync(scratch,{recursive:true});
  const tmp=path.join(scratch,'omni-flight-'+process.pid+'.xml');
  try{
    fs.writeFileSync(tmp,prefs,{mode:0o600});
    console.log('Installing debug APK on disposable emulator '+serial+' (no production changes).');
    run(['install','-r',apk],120000);
    run(['reverse','tcp:15000','tcp:15000']);
    run(['push',tmp,'/data/local/tmp/omni_flight_prefs.xml']);
    run(['shell','run-as','com.omni.sync','mkdir','-p','shared_prefs']);
    run(['shell','run-as','com.omni.sync','cp','/data/local/tmp/omni_flight_prefs.xml','shared_prefs/omni_settings.xml']);
    run(['shell','rm','/data/local/tmp/omni_flight_prefs.xml']);
    // Installed emulator may already have app_config.json. Override only its
    // connection fields, preserving all defaults and other app state.
    const remote='/sdcard/Android/data/com.omni.sync/files/app_config.json';
    let existing=null;
    try {existing=JSON.parse(run(['shell','cat',remote]));}catch{}
    if(existing){
      existing.hubUrl='http://127.0.0.1:15000/signalrhub';
      existing.wanIp='';
      existing.apiKey=apiKey;
      const staged=path.join(scratch,'omni-flight-'+process.pid+'.json');
      try{
        fs.writeFileSync(staged,JSON.stringify(existing),{mode:0o600});
        run(['push',staged,remote]);
      }finally{try{fs.unlinkSync(staged)}catch{}}
    }
  }finally{try{fs.unlinkSync(tmp);}catch{}}
  run(['shell','am','force-stop','com.omni.sync']);
  run(['shell','am','start','-n','com.omni.sync/.MainActivity']);
  console.log('Android app launched through proxy. Observe flight-link-proxy.jsonl for Authenticate and command receipts.');
  await new Promise(r=>setTimeout(r,seconds*1000));
  // Filter only infrastructure connection hints; do not log Android device/app data.
  const lines=run(['logcat','-d','-s','SignalRClient:I','SignalR:D'],15000).split(/\r?\n/)
    .filter(line=>/connect|disconnect|authenticat|websocket|timeout|error/i.test(line))
    .map(line=>line.replace(/(apiKey|token|password)\s*[:=]\s*\S+/ig,'$1=[redacted]'))
    .slice(-25);
  console.log('Android connection log (filtered):\n'+lines.join('\n'));
  console.log('App remains in disposable emulator; use adb shell am force-stop com.omni.sync to end test.');
}
main().catch(e=>{console.error('ANDROID_SMOKE_FAILED '+e.message);process.exitCode=1;});
