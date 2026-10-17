// Dependency-free synthetic DOM smoke test for the merged Gateway page
// (tools, OmniSweep setups/scenes/editor, Tailscale). Run: node OmniSync.Web/tests/gateway_ui_smoke.cjs
const fs = require('fs');
const vm = require('vm');
const path = require('path');
const assert = require('assert');
function element(id) {
  return {
    id,value:'',innerHTML:'',textContent:'',hidden:false,disabled:false,open:false,dataset:{},className:'',
    classList:{add(){},remove(){},toggle(){}},
    listeners:{},addEventListener(name,cb){this.listeners[name]=cb;},setAttribute(){},
    querySelector(){return null;},querySelectorAll(){return [];},closest(){return null;},
    focus(){},select(){},blur(){},click(){},scrollIntoView(){},getClientRects(){return [];},
    showModal(){this.open=true;},close(){this.open=false;},parentElement:{hidden:false}
  };
}
const nodes = new Map();
const get = id => nodes.get(id) || (nodes.set(id,element(id)),nodes.get(id));
const document = {
  hidden:true, activeElement:null, getElementById:get,
  querySelectorAll:() => [], querySelector:() => null, addEventListener(){}
};
const context = vm.createContext({
  document, location:{origin:'http://localhost:3333'},
  URL,Date,JSON,Array,Math,String,Number,Promise,console,
  localStorage:{getItem:()=>null,setItem(){}},
  setTimeout:()=>1,clearTimeout(){},fetch(){throw Error('no network expected')}
});
const script = fs.readFileSync(path.join(__dirname,'..','www','gateway','gateway.js'),'utf8');
vm.runInContext(script,context,{filename:'gateway.js'});

// Tools
vm.runInContext(`state.initialized=true; state.tools=[
 {id:'safe-tool',name:'My Test Tool',description:'A running test',url:'http://127.0.0.1:9999/',status:'running',error:null},
 {id:'offline-tool',name:'Other Tool',description:'An offline test',url:'http://127.0.0.1:8080/',status:'stopped',error:null},
 {id:'malicious',name:'<img src=x onerror=alert(1)>',description:'Script <b>escape</b>',url:'http://127.0.0.1:7000/',status:'error',error:'bad <script>'}
]; render()`,context);
assert.match(get('tools').innerHTML,/My Test Tool/);
assert.match(get('tools').innerHTML,/&lt;img src=x onerror=alert\(1\)&gt;/);
assert.doesNotMatch(get('tools').innerHTML,/<img src=x onerror=/);
assert.match(get('tools').innerHTML,/bad &lt;script&gt;/);
assert.match(get('tools').innerHTML,/Offline · starts on open/);
assert.strictEqual(get('tools-count').textContent,'3');
vm.runInContext("pin('offline-tool')",context);
assert.ok(get('tools').innerHTML.indexOf('Other Tool') < get('tools').innerHTML.indexOf('My Test Tool'),'pinned tool sorts first');
get('search').value='other';
vm.runInContext('render()',context);
assert.match(get('tools').innerHTML,/Other Tool/);
assert.doesNotMatch(get('tools').innerHTML,/My Test Tool/);
get('search').value='zzz';
vm.runInContext('render()',context);
assert.match(get('tools').innerHTML,/No matching tools/);
assert.strictEqual(get('no-results').hidden,false);
get('search').value='';

// OmniSweep setups, scenes and macros
vm.runInContext(`state.snapshot={revision:5,setups:[
 {id:'one',name:'Test <b>safe</b>',shortcut:'F1',actions:[{id:'a',type:0,path:'D:\\Wartribes',arguments:'',command:''}]},
 {id:'two',name:'Other setup',shortcut:'',actions:[]}],
 macros:[{id:'x',name:'Morning',isScene:true,category:'Voice'},{id:'y',name:'Lock PC',category:'General',isScene:false}],
 calendar:{ongoing:[],next:null}}; render()`,context);
assert.match(get('setups-grid').innerHTML,/Test &lt;b&gt;safe&lt;\/b&gt;/);
assert.doesNotMatch(get('setups-grid').innerHTML,/<b>safe<\/b>/);
assert.strictEqual(get('setups-count').textContent,'2');
assert.match(get('scenes-grid').innerHTML,/Morning/);
assert.match(get('macros-grid').innerHTML,/Lock PC/);
assert.doesNotMatch(get('scenes-grid').innerHTML,/Lock PC/);
get('search').value='Other';
vm.runInContext('render()',context);
assert.doesNotMatch(get('setups-grid').innerHTML,/Test &lt;b/);
assert.match(get('setups-grid').innerHTML,/Other setup/);
vm.runInContext("openEditor('two')",context);
assert.strictEqual(get('setup-editor').open,true);
assert.strictEqual(get('edit-name').value,'Other setup');
assert.strictEqual(get('step-count').textContent,0);
vm.runInContext('state.snapshot.revision=6; render()',context);
assert.strictEqual(get('edit-warning').hidden,false);
assert.strictEqual(get('save-setup').disabled,true);
get('search').value='';

// Tailscale section
vm.runInContext(`state.network={available:true,backend:'Running',
 self:{name:'DESKTOP2024',dns:'desktop2024.tail8699ce.ts.net',ip:'100.115.212.38',online:true},
 urls:[{url:'https://desktop2024.tail8699ce.ts.net',funnel:true,routes:[{path:'/',target:'http://127.0.0.1:5055'}]},
       {url:'https://desktop2024.tail8699ce.ts.net:8443',funnel:false,routes:[{path:'/',target:'http://127.0.0.1:8787'}]}],
 devices:[{name:'phone<x>',dns:'phone.tail.ts.net',ip:'100.1.1.2',os:'android',online:true}]}; renderNetwork()`,context);
assert.match(get('network-body').innerHTML,/href="https:\/\/desktop2024\.tail8699ce\.ts\.net"/);
assert.match(get('network-body').innerHTML,/badge public/);
assert.match(get('network-body').innerHTML,/badge tailnet/);
assert.match(get('network-body').innerHTML,/phone&lt;x&gt;/);
vm.runInContext("state.network={unavailable:'Tailscale is not running on this PC.'}; renderNetwork()",context);
assert.match(get('network-body').innerHTML,/Tailscale is not running/);

console.log('PASS: tools, setups, scenes, macros, editor, stale guard, Tailscale, escaping and search');
