import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import schema from '../../../docs/OmniDesign/omnidesign.document.v1.schema.json';
import type {Diagram,Element,NodeElement,Point,Port,Endpoint,PathCommand,GroupElement} from './types';

export const palette={canvas:'#14161a',panel:'#1d2026',ink:'#e6e2d8',muted:'#a09a8c',accent:'#e0a458'};
export const clone=<T,>(v:T):T=>structuredClone(v);
export const uuid=(prefix:string)=>prefix+crypto.randomUUID();
export const stamp=()=>new Date().toISOString();
const ajv=new Ajv2020({allErrors:true,strict:false}); addFormats(ajv);
const validateSchema=ajv.compile(schema);
const SIDES:Port['anchor']['side'][]=['top','right','bottom','left'];
export const initialPorts=():Port[]=>SIDES.map(side=>({id:uuid('prt_'),name:side,direction:'both',anchor:{side,t:0.5}}));
export function node(x:number,y:number,kind:NodeElement['shape']['fallback']='roundRect',layerId:string,title='New shape'):NodeElement {
 return {id:uuid('el_'),type:'node',layerId,parentId:null,order:0,geometry:{x,y,width:kind==='ellipse'?150:210,height:kind==='ellipse'?100:116,rotation:0},shape:{libraryId:'core',name:kind,version:'1',fallback:kind},ports:initialPorts(),label:{text:title,style:{fontSize:18,textColor:palette.ink}},style:{fill:palette.panel,stroke:palette.ink,strokeWidth:2,cornerRadius:12},semantic:{role:'shape',data:{}}};
}
export function newDiagram(title='Untitled diagram',family:Diagram['family']='architecture'):Diagram {
 const now=stamp(),id=uuid('lyr_');
 return {format:'omnidesign',schemaVersion:1,id:uuid('dgm_'),revision:0,title,family,
 metadata:{createdAt:now,updatedAt:now,originDeviceId:deviceId(),forkedFrom:null,tags:[]},
 page:{width:1200,height:700,background:palette.canvas,grid:{enabled:false,size:20,snap:true},units:'px'},
 theme:{name:'R3 · diagram skill',tokens:palette},layers:[{id,name:'Systems and connections',visible:true,locked:false}],
 elements:[],libraries:{references:[{id:'core',version:'1'}],embedded:[]},assets:[]};
}
export function exampleDiagram():Diagram {
 const d=newDiagram('Systems architecture');
 const a=node(110,210,'roundRect',d.layers[0].id,'Input & events');
 const b=node(475,205,'roundRect',d.layers[0].id,'Simulation');
 const c=node(835,210,'roundRect',d.layers[0].id,'State & memory');
 b.style={...b.style,stroke:palette.accent,strokeDash:[7,5]};
 d.elements=[a,b,c,edge(a,'right',b,'left',d.layers[0].id),edge(b,'right',c,'left',d.layers[0].id)];
 return d;
}
export function deviceId():string {
 const k='omnidesign.deviceId';try{let v=localStorage.getItem(k);if(!v){v=uuid('dev_');localStorage.setItem(k,v);}return v;}catch{return uuid('dev_');}
}
export function edge(a:NodeElement,from:Port['anchor']['side'],b:NodeElement,to:Port['anchor']['side'],layerId:string):Element {
 return {id:uuid('el_'),type:'edge',layerId,parentId:null,order:0,source:{type:'port',nodeId:a.id,portId:a.ports.find(p=>p.anchor.side===from)!.id},target:{type:'port',nodeId:b.id,portId:b.ports.find(p=>p.anchor.side===to)!.id},route:{mode:'orthogonal',waypoints:[],manual:false},label:{text:''},style:{stroke:palette.accent,strokeWidth:2},semantic:{role:'flow',data:{}}};
}
export function validateDocument(input:unknown):Diagram {
 if(typeof input!=='object'||!input)throw Error('Not an OmniDesign document.');
 const raw=input as Record<string,unknown>;
 if(raw.format!=='omnidesign')throw Error('This file is not an OmniDesign document.');
 if(raw.schemaVersion!==1)throw Error('Unsupported document version '+String(raw.schemaVersion)+'. The original file has not been altered.');
 if(JSON.stringify(input).length>8*1024*1024)throw Error('Document exceeds 8 MB limit.');
 if(!validateSchema(input))throw Error('Invalid native document: '+(ajv.errorsText(validateSchema.errors,{separator:'; '}).slice(0,320)));
 const d=input as Diagram;
 const layerIds=new Set<string>(),ids=new Set<string>(),byId=new Map<string,Element>();
 for(const l of d.layers){if(layerIds.has(l.id))throw Error('Duplicate layer ID.');layerIds.add(l.id);}
 for(const e of d.elements){if(ids.has(e.id))throw Error('Duplicate element ID.');ids.add(e.id);byId.set(e.id,e);}
 for(const e of d.elements){
  if(!layerIds.has(e.layerId))throw Error('Missing layer referenced by '+e.id);
  if(e.parentId){
   const p=byId.get(e.parentId);
   if(!p||p.type!=='group'||p.layerId!==e.layerId)throw Error('Invalid group parent for '+e.id);
   const seen=new Set([e.id]);let parent:Element|undefined=p;
   while(parent){if(seen.has(parent.id))throw Error('Group cycle.');seen.add(parent.id);parent=parent.parentId?byId.get(parent.parentId):undefined;}
  }
  if('geometry' in e && (!Object.values(e.geometry).every(v=>typeof v==='number'&&Number.isFinite(v)&&Math.abs(v)<1e8)))throw Error('Invalid geometry.');
  if(e.type==='path'){
   for(const c of e.commands){if('to' in c && !finite(c.to))throw Error('Invalid path coordinate.');if(c.op==='C'&&(!finite(c.control1)||!finite(c.control2)))throw Error('Invalid Bezier coordinate.');}
  }
  if(e.type==='edge'){
   for(const endpoint of [e.source,e.target]){
    if(endpoint.type==='free'){if(!finite(endpoint.point))throw Error('Invalid free endpoint.');continue;}
    const n=byId.get(endpoint.nodeId);
    if(!n||n.type!=='node')throw Error('Dangling connector node.');
    if(endpoint.type==='port'&&!n.ports.some(p=>p.id===endpoint.portId))throw Error('Dangling connector port.');
   }
   if(e.route.waypoints.some(p=>!finite(p)))throw Error('Invalid route point.');
  }
  if(e.type==='node'&&new Set(e.ports.map(p=>p.id)).size!==e.ports.length)throw Error('Duplicate node ports.');
 }
 return d;
}
const finite=(p:Point)=>Number.isFinite(p.x)&&Number.isFinite(p.y)&&Math.max(Math.abs(p.x),Math.abs(p.y))<1e8;
export const itemById=(d:Diagram,id:string)=>d.elements.find(e=>e.id===id);
export const origin=(d:Diagram,e:Element):Point=>{
 let x=0,y=0,p=e.parentId,depth=0;
 while(p&&depth++<64){const parent=itemById(d,p);if(!parent||parent.type!=='group')break;x+=parent.geometry.x;y+=parent.geometry.y;p=parent.parentId;}
 return {x,y};
};
export const absGeometry=(d:Diagram,e:NodeElement|GroupElement|Extract<Element,{type:'text'}>)=>{
 const p=origin(d,e);return {...e.geometry,x:e.geometry.x+p.x,y:e.geometry.y+p.y};
};
export function anchorPoint(d:Diagram,n:NodeElement,side:Port['anchor']['side'],t:number):Point {
 const g=absGeometry(d,n);let x=g.x+g.width/2,y=g.y+g.height/2;
 if(side==='left'){x=g.x;y=g.y+g.height*t;}if(side==='right'){x=g.x+g.width;y=g.y+g.height*t;}
 if(side==='top'){x=g.x+g.width*t;y=g.y;}if(side==='bottom'){x=g.x+g.width*t;y=g.y+g.height;}
 if(g.rotation){const a=g.rotation*Math.PI/180,dx=x-(g.x+g.width/2),dy=y-(g.y+g.height/2);x=g.x+g.width/2+dx*Math.cos(a)-dy*Math.sin(a);y=g.y+g.height/2+dx*Math.sin(a)+dy*Math.cos(a);}
 return {x,y};
}
export function endpointPoint(d:Diagram,end:Endpoint,edgeParent:Element):Point {
 const edgeOffset=origin(d,edgeParent);
 if(end.type==='free')return {x:end.point.x+edgeOffset.x,y:end.point.y+edgeOffset.y};
 const n=itemById(d,end.nodeId);if(!n||n.type!=='node')return {x:0,y:0};
 const port=end.type==='port'?n.ports.find(p=>p.id===end.portId):null;
 const anchor=port?.anchor??(end.type==='anchor'?end.anchor:{side:'center' as const,t:0.5});
 return anchorPoint(d,n,anchor.side,anchor.t);
}
export const pathD=(commands:PathCommand[],offset:Point={x:0,y:0})=>commands.map(c=>{
 if(c.op==='Z')return 'Z';
 const p=(v:Point)=>`${+(v.x+offset.x).toFixed(2)} ${+(v.y+offset.y).toFixed(2)}`;
 return c.op==='C'?`C ${p(c.control1)} ${p(c.control2)} ${p(c.to)}`:`${c.op} ${p(c.to)}`;
}).join(' ');
export function edgeD(d:Diagram,e:Extract<Element,{type:'edge'}>):string {
 const a=endpointPoint(d,e.source,e),b=endpointPoint(d,e.target,e),delta=origin(d,e),pts=e.route.manual?e.route.waypoints.map(p=>({x:p.x+delta.x,y:p.y+delta.y})):[];
 if(e.route.mode==='bezier'&&!pts.length){const dx=Math.max(65,Math.abs(b.x-a.x)*.48);return `M ${a.x} ${a.y} C ${a.x+dx} ${a.y} ${b.x-dx} ${b.y} ${b.x} ${b.y}`;}
 const points=pts.length?pts:e.route.mode==='orthogonal'?[{x:(a.x+b.x)/2,y:a.y},{x:(a.x+b.x)/2,y:b.y}]:[];
 return pathD([{op:'M',to:a},...points.map(to=>({op:'L' as const,to})),{op:'L',to:b}]);
}
export const snap=(n:number,d:Diagram)=>d.page.grid.snap?Math.round(n/d.page.grid.size)*d.page.grid.size:n;
function movePoint(p:Point,dx:number,dy:number):Point{return {x:p.x+dx,y:p.y+dy};}
export function translate(e:Element,dx:number,dy:number):Element {
 const next=clone(e);
 if('geometry' in next){next.geometry.x+=dx;next.geometry.y+=dy;}
 if(next.type==='path')next.commands=next.commands.map(c=>c.op==='Z'?c:c.op==='C'?{...c,to:movePoint(c.to,dx,dy),control1:movePoint(c.control1,dx,dy),control2:movePoint(c.control2,dx,dy)}:{...c,to:movePoint(c.to,dx,dy)});
 if(next.type==='edge'){for(const end of [next.source,next.target])if(end.type==='free')end.point=movePoint(end.point,dx,dy);next.route.waypoints=next.route.waypoints.map(p=>movePoint(p,dx,dy));}
 return next;
}
export function moveElements(d:Diagram,ids:string[],dx:number,dy:number):Diagram {
 const set=new Set(ids),canMove=(e:Element)=>!e.locked&&!d.layers.find(l=>l.id===e.layerId)?.locked&&!ancestorSelected(d,e,set);
 return {...d,elements:d.elements.map(e=>set.has(e.id)&&canMove(e)?translate(e,dx,dy):e)};
}
const ancestorSelected=(d:Diagram,e:Element,ids:Set<string>)=>{let p=e.parentId;while(p){if(ids.has(p))return true;p=itemById(d,p)?.parentId??null;}return false;};
export function removeElements(d:Diagram,ids:string[]):Diagram{
 const doomed=new Set(ids),all=(e:Element)=>{let p=e.parentId;while(p){if(doomed.has(p))return true;p=itemById(d,p)?.parentId??null;}return doomed.has(e.id);};
 const gone=new Set(d.elements.filter(all).map(e=>e.id));
 return {...d,elements:d.elements.filter(e=>!gone.has(e.id)&&(e.type!=='edge'||![e.source,e.target].some(p=>p.type!=='free'&&gone.has(p.nodeId))))};
}
export function groupElements(d:Diagram,ids:string[]):{document:Diagram;id:string|null}{
 const entries=d.elements.filter(e=>ids.includes(e.id)&&['node','text','group','path'].includes(e.type)) as Exclude<Element,{type:'edge'}>[];
 if(entries.length<2||new Set(entries.map(e=>e.layerId)).size!==1||new Set(entries.map(e=>e.parentId)).size!==1)return {document:d,id:null};
 const boxes=entries.map(e=>{if(e.type!=='path')return absGeometry(d,e);const o=origin(d,e),points=e.commands.flatMap(c=>c.op==='Z'?[]:c.op==='C'?[c.control1,c.control2,c.to]:[c.to]);const xs=points.map(p=>p.x+o.x),ys=points.map(p=>p.y+o.y);return {x:Math.min(...xs),y:Math.min(...ys),width:Math.max(...xs)-Math.min(...xs),height:Math.max(...ys)-Math.min(...ys)};});const x=Math.min(...boxes.map(b=>b.x))-24,y=Math.min(...boxes.map(b=>b.y))-38;
 const w=Math.max(...boxes.map(b=>b.x+b.width))-x+24,h=Math.max(...boxes.map(b=>b.y+b.height))-y+24;
 const offset=entries[0].parentId?origin(d,entries[0]):{x:0,y:0};
 const group:GroupElement={id:uuid('el_'),type:'group',layerId:entries[0].layerId,parentId:entries[0].parentId,order:0,geometry:{x:x-offset.x,y:y-offset.y,width:w,height:h,rotation:0},label:{text:'Group'},style:{fill:'#1d202655',stroke:palette.muted,strokeDash:[6,5]}};
 return {id:group.id,document:{...d,elements:[...d.elements.map(e=>entries.some(a=>a.id===e.id)?{...translate(e,-group.geometry.x,-group.geometry.y),parentId:group.id} as Element:e),group]}};
}
export function ungroupElements(d:Diagram,id:string):Diagram {
 const g=itemById(d,id);if(!g||g.type!=='group')return d;
 return {...d,elements:d.elements.filter(e=>e.id!==id).map(e=>e.parentId===id?{...translate(e,g.geometry.x,g.geometry.y),parentId:g.parentId}:e)};
}
