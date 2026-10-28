const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const file=fs.readFileSync(__dirname+'/background.js','utf8');
const coreSource=file.match(/^function getCoreUrlPattern\(url\) \{[\s\S]*?^\}/m)?.[0];
assert(coreSource, 'Quick-add core URL handler exists');
const core=vm.runInNewContext(coreSource+';getCoreUrlPattern;', {URL});
assert.equal(core('https://www.youtube.com/watch?v=abc'), 'https://www.youtube.com/*');
assert.equal(core('https://www.twitch.tv/directory/following'), 'https://www.twitch.tv/*');
assert.equal(core('file:///C:/example.html'), 'file:///*');
assert.equal(core('chrome://extensions'), 'chrome:*');
assert.equal(core('vivaldi:extensions'), 'vivaldi:*');
assert.equal(core('vivaldi://startpage'), 'vivaldi:*');
const matcherSource=file.match(/^function urlMatchesPattern\(url, pattern\) \{[\s\S]*?^\}/m)?.[0];
assert(matcherSource, 'Wildcard matcher exists');
const matches=vm.runInNewContext(matcherSource+';urlMatchesPattern;');
assert(matches('vivaldi:extensions','vivaldi:*'));
assert(matches('chrome://extensions','chrome:*'));
assert(!matches('https://youtube.com','chrome:*'));
const popup=fs.readFileSync(__dirname+'/browser-popup.js','utf8');
const titleSource=popup.match(/^function displayArchiveTitle\(title, url\) \{[\s\S]*?^\}/m)?.[0];
assert(titleSource, 'Archived title formatter exists');
const displayTitle=vm.runInNewContext(titleSource+';displayArchiveTitle;');
assert.equal(displayTitle('(100) How My AI Civilization Works', 'https://youtube.com'), 'How My AI Civilization Works');
assert.equal(displayTitle('(100) [25] Inbox', 'https://gmail.com'), 'Inbox');
assert.equal(displayTitle('Normal title', 'https://example.com'), 'Normal title');
assert(popup.includes('open.title = item.url'), 'Full URL remains available on hover');
assert(!popup.includes("className = 'archive-url'"), 'URL subtitle is removed');
const begin=file.indexOf('// OmniHub is the authoritative owner of cleanup patterns.');
const end=file.indexOf('// Handle Commands from Android',begin);
assert(begin>0&&end>begin,'Hub-backed cleanup pattern handler must exist');
let authoritative=['*from-omnihub*','title:existing'];
let cached=['*old-cache*'];
let connected=true;
let failWrite=false;
const handlers=[];
const calls=[];
const chrome={
 runtime:{id:'test',onMessage:{addListener:fn=>handlers.push(fn)}},
 storage:{local:{async set(obj){cached=obj.customCleanupPatterns}}}
};
const signalR={HubConnectionState:{Connected:'Connected',Disconnected:'Disconnected'}};
const connection={
 get state(){return connected?'Connected':'Disconnected';},
 async invoke(method,arg){
  calls.push(method);
  if(method==='GetSettings')return {browserCleanupPatterns:[...authoritative]};
  if(method==='SendCleanupPatterns'){
   if(failWrite)throw Error('Hub rejected write');
   authoritative=[...arg];
   return null;
  }
  throw Error('Unexpected RPC: '+method);
 }
};
const sandbox={chrome,signalR,connection,console,start:async()=>{}};
vm.runInNewContext(file.slice(begin,end),sandbox);
function message(command,pattern){
 return new Promise((resolve,reject)=>{
  const msg={command,pattern};
  const used=handlers[0](msg,{id:chrome.runtime.id},value=>
    value.ok?resolve(JSON.parse(JSON.stringify(value.data))):reject(Error(value.error)));
  if(!used)reject(Error('Handler ignored command'));
 });
}
(async()=>{
 assert.deepEqual(await message('patterns:list'),authoritative,'Hub returned data over stale cache');
 assert.deepEqual(JSON.parse(JSON.stringify(cached)),authoritative,'Hub snapshot updates the extension cache');
 await message('patterns:add','*third*');
 assert.deepEqual(authoritative,['*from-omnihub*','title:existing','*third*']);
 await message('patterns:remove','title:existing');
 assert.deepEqual(authoritative,['*from-omnihub*','*third*']);
 assert.equal(calls.filter(x=>x==='GetSettings').length,3,'Every edit refetches from Hub');
 const previous=[...authoritative];
 failWrite=true;
 await assert.rejects(()=>message('patterns:add','*not-saved*'),/Hub rejected write/);
 assert.deepEqual(JSON.parse(JSON.stringify(cached)),previous,'Failed Hub save does not alter cache');
 failWrite=false;connected=false;
 await assert.rejects(()=>message('patterns:list'),/disconnected/);
 console.log('Cleanup patterns: authoritative Hub read, add/remove, failed save, offline behavior PASS');
})().catch(error=>{console.error(error);process.exitCode=1});
