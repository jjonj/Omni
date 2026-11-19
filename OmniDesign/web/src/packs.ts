import type {Diagram, NodeElement, Port, Style} from './types';
import {edge, initialPorts, newDiagram, node, palette, uuid} from './model';

/** Declarative stencil packs: no separate canvas, HTML or SVG renderer per diagram family. */
export type Field = {key:string;label:string;type:'text'|'number'|'boolean'|'select';options?:string[]};
export type Stencil = {
 name:string; title:string; icon:string; fallback:NodeElement['shape']['fallback'];
 role:string; width?:number; height?:number; glyph?:string;
 style?:Style; defaults?:Record<string,string|number|boolean>; fields?:Field[];
 ports?:{name:string;side:Port['anchor']['side'];direction?:Port['direction'];dataType?:string}[];
};
export type TemplateNode={key:string;stencil:string;x:number;y:number;width?:number;height?:number;text?:string;subtitle?:string;items?:string[];props?:Record<string,string|number|boolean>};
export type TemplateLink={from:string;to:string;label?:string;kind?:string;fromSide?:Port['anchor']['side'];toSide?:Port['anchor']['side'];route?:'straight'|'orthogonal'|'bezier'};
export type Template={id:string;title:string;family:Diagram['family'];description:string;nodes:TemplateNode[];links:TemplateLink[]};
export type Pack={format:'omnidesign-pack';schemaVersion:1;id:string;version:string;name:string;family:Diagram['family'];shapes:Stencil[];templates:Template[]};
const S=(name:string,title:string,icon:string,fallback:Stencil['fallback'],role:string,glyph?:string,fields:Field[]=[],defaults:Stencil['defaults']={},width=210,height=112):Stencil=>({name,title,icon,fallback,role,glyph,fields,defaults,width,height});
const T=(id:string,title:string,family:Diagram['family'],description:string,nodes:TemplateNode[],links:TemplateLink[]=[]):Template=>({id,title,family,description,nodes,links});
const N=(key:string,stencil:string,x:number,y:number,text:string,extras:Partial<TemplateNode>={}):TemplateNode=>({key,stencil,x,y,text,...extras});
const L=(from:string,to:string,label='',kind='flow',extras:Partial<TemplateLink>={}):TemplateLink=>({from,to,label,kind,...extras});
const P=(id:string,name:string,family:Diagram['family'],shapes:Stencil[],templates:Template[]):Pack=>({format:'omnidesign-pack',schemaVersion:1,id,version:'1',name,family,shapes,templates});
const status:Field={key:'status',label:'Status',type:'select',options:['planned','active','done','blocked']};
const priority:Field={key:'priority',label:'Priority',type:'select',options:['low','medium','high','critical']};

export const builtInPacks:Pack[]=[
 P('architecture','Architecture','architecture',[
 S('service','Service','▣','roundRect','architecture.service','service',[{key:'technology',label:'Technology',type:'text'},status],{technology:'API',status:'active'}),
 S('database','Database','▤','roundRect','architecture.database','database',[{key:'engine',label:'Database engine',type:'text'}],{engine:'SQLite'}),
 S('queue','Queue','▥','rect','architecture.queue','queue',[{key:'capacity',label:'Capacity',type:'number'}],{capacity:100}),
 S('client','Client','▣','roundRect','architecture.client','screen'),
 S('cloud','Cloud','☁','ellipse','architecture.cloud','cloud')
 ],[T('service-map','Service architecture','architecture','Client → gateway → services → data store',[
 N('client','client',90,230,'Android / Browser'),N('gateway','service',360,230,'OmniHub Gateway',{props:{technology:'Rust'}}),
 N('worker','service',645,120,'Diagram service',{props:{technology:'HTTP'}}),N('queue','queue',645,345,'Event queue'),
 N('data','database',930,120,'Local storage',{props:{engine:'SQLite'}})
 ],[L('client','gateway','HTTPS'),L('gateway','worker','REST'),L('gateway','queue','events'),L('worker','data','SQL')])]),
 P('flowchart','Flowchart','flowchart',[
 S('terminator','Start / End','◉','ellipse','flow.terminator'),
 S('process','Process','▣','roundRect','flow.process',undefined,[{key:'owner',label:'Owner',type:'text'}]),
 S('decision','Decision','◇','diamond','flow.decision',undefined,[{key:'expression',label:'Condition',type:'text'}]),
 S('document','Document','▤','rect','flow.document','document'),
 S('io','Input / Output','▱','polygon','flow.io','io')
 ],[T('decision-flow','Decision and retry','flowchart','A decision with yes/no branches and retry loop',[
 N('start','terminator',70,250,'Request'),N('check','decision',360,225,'Is valid?',{width:185,height:135}),
 N('process','process',670,110,'Process request'),N('retry','process',670,360,'Request correction'),N('end','terminator',970,110,'Complete')
 ],[L('start','check'),L('check','process','Yes'),L('check','retry','No'),L('process','end'),L('retry','check','Retry','flow',{fromSide:'left',toSide:'bottom'})])]),
 P('uml-sequence','UML · Sequence','sequence',[
 S('actor','Actor','♙','roundRect','uml.actor','actor',[{key:'stereotype',label:'Stereotype',type:'text'}],{},145,400),
 S('lifeline','Lifeline','┊','roundRect','uml.lifeline','lifeline',[{key:'participantType',label:'Participant',type:'select',options:['object','service','boundary','control','entity']}],{participantType:'object'},185,420),
 S('activation','Activation','▯','rect','uml.activation','activation',[],{},60,170)
 ],[T('request-sequence','Request sequence','sequence','Participants and message order with editable arrow labels',[
 N('user','actor',100,100,'User',{height:470}),N('app','lifeline',390,100,'Android app',{height:470}),
 N('hub','lifeline',700,100,'OmniHub',{height:470}),N('store','lifeline',980,100,'Store',{width:165,height:470})
 ],[
 L('user','app','1 · Open screen','message',{route:'straight'}),L('app','hub','2 · GET data','message',{route:'straight'}),
 L('hub','store','3 · Query','message',{route:'straight'}),L('store','hub','4 · Result','return',{fromSide:'left',toSide:'right',route:'straight'}),
 L('hub','app','5 · Response','return',{fromSide:'left',toSide:'right',route:'straight'})
 ])]),
 P('uml-state','UML · State','state',[
 S('initial','Initial','●','ellipse','state.initial','initial',[],{},66,66),
 S('state','State','▢','roundRect','state.normal',undefined,[{key:'entry',label:'Entry action',type:'text'},{key:'exit',label:'Exit action',type:'text'}]),
 S('final','Final','◎','ellipse','state.final','final',[],{},70,70),
 S('choice','Choice','◇','diamond','state.choice')
 ],[T('lifecycle','State machine','state','Initial, transitions, guards and terminal state',[
 N('start','initial',70,315,''),N('idle','state',220,280,'Idle',{props:{entry:'reset'}}),
 N('action','state',535,130,'Working',{props:{entry:'begin work'}}),N('error','state',540,385,'Failed'),
 N('end','final',940,150,'')
 ],[L('start','idle'),L('idle','action','start [ready]','transition'),L('action','idle','cancel','transition',{fromSide:'bottom',toSide:'top'}),L('action','error','error','transition',{fromSide:'bottom',toSide:'top'}),L('action','end','finish','transition')])]),
 P('erd','Database · ERD','erd',[
 S('entity','Entity / Table','▤','rect','erd.entity','entity',[{key:'schema',label:'Schema',type:'text'},{key:'primaryKey',label:'Primary key',type:'text'}],{schema:'public',primaryKey:'id'},245,185),
 S('weak','Weak Entity','▣','rect','erd.weak','entity'),
 S('relationship','Relationship','◇','diamond','erd.relationship'),
 S('attribute','Attribute','◯','ellipse','erd.attribute')
 ],[T('data-model','Entity relationship model','erd','Tables, fields, primary keys and cardinalities',[
 N('users','entity',85,155,'Users',{items:['PK id : uuid','name : text','email : text'],props:{schema:'public',primaryKey:'id'}}),
 N('orders','entity',470,155,'Orders',{items:['PK id : uuid','FK user_id : uuid','created_at : date'],props:{primaryKey:'id'}}),
 N('lines','entity',870,155,'Order lines',{items:['PK id : uuid','FK order_id : uuid','quantity : int'],props:{primaryKey:'id'}})
 ],[L('users','orders','1 : N','crowfoot'),L('orders','lines','1 : N','crowfoot')])]),
 P('mindmap','Mind Map','mindmap',[
 S('topic','Main Topic','◉','ellipse','mindmap.root'),
 S('branch','Branch','⬭','roundRect','mindmap.branch'),
 S('detail','Detail','▫','roundRect','mindmap.detail')
 ],[T('idea-map','Idea map','mindmap','Central idea with three branches and editable details',[
 N('root','topic',465,255,'Project vision',{width:235,height:135}),
 N('design','branch',130,85,'Design'),N('tech','branch',130,420,'Engineering'),
 N('release','branch',835,260,'Release'),N('ref','detail',130,260,'References'),
 N('demo','detail',855,90,'Playtest')
 ],[L('root','design','','association',{route:'bezier',fromSide:'left',toSide:'right'}),L('root','tech','','association',{route:'bezier',fromSide:'left',toSide:'right'}),L('root','release','','association',{route:'bezier'}),L('design','ref','','association',{route:'bezier'}),L('release','demo','','association',{route:'bezier'})])]),
 P('roadmap','Timeline & Roadmap','roadmap',[
 S('milestone','Milestone','◆','diamond','timeline.milestone',undefined,[{key:'date',label:'Target date',type:'text'},status],{date:'2026-12-01',status:'planned'},135,100),
 S('phase','Phase','▰','roundRect','timeline.phase',undefined,[{key:'start',label:'Start',type:'text'},{key:'end',label:'End',type:'text'},status]),
 S('deliverable','Deliverable','▤','rect','timeline.deliverable')
 ],[T('delivery-roadmap','Milestone timeline','roadmap','Project phases and dated deliverables',[
 N('research','phase',75,215,'Discovery',{width:235,props:{start:'Week 1',end:'Week 2'}}),
 N('m1','milestone',390,230,'Spec approved',{props:{date:'Week 2'}}),
 N('build','phase',605,215,'Implementation',{width:240,props:{start:'Week 3',end:'Week 5'}}),
 N('m2','milestone',955,230,'Release',{props:{date:'Week 6'}}),
 N('deliverable','deliverable',605,425,'Playable prototype')
 ],[L('research','m1','','dependency'),L('m1','build','','dependency'),L('build','m2','','dependency'),L('build','deliverable','produces','association')])]),
 P('network','Network Topology','network',[
 S('router','Router','⌘','ellipse','network.router','router',[{key:'ip',label:'IP address',type:'text'},{key:'vlan',label:'VLAN',type:'number'}],{ip:'10.0.0.1',vlan:1},175,140),
 S('switch','Switch','▦','rect','network.switch','switch',[{key:'ports',label:'Physical ports',type:'number'}],{ports:8}),
 S('host','Host','▣','roundRect','network.host','screen',[{key:'ip',label:'IP address',type:'text'},{key:'os',label:'Operating system',type:'text'}],{ip:'10.0.0.2',os:'Windows'}),
 S('firewall','Firewall','▥','polygon','network.firewall','firewall',[{key:'policy',label:'Policy',type:'text'}],{policy:'deny-by-default'}),
 S('subnet','Subnet','☁','ellipse','network.subnet','cloud',[{key:'cidr',label:'CIDR',type:'text'}],{cidr:'10.0.0.0/24'})
 ],[T('home-lan','LAN topology','network','Internet, firewall, switch and endpoint connections',[
 N('net','subnet',80,215,'Internet',{props:{cidr:'WAN'}}),N('router','router',360,210,'Gateway',{props:{ip:'192.168.1.1'}}),
 N('switch','switch',640,215,'LAN Switch',{props:{ports:8}}),
 N('pc','host',940,85,'Desktop',{props:{ip:'192.168.1.10',os:'Windows'}}),
 N('phone','host',940,370,'Android',{props:{ip:'192.168.1.11',os:'Android'}})
 ],[L('net','router','','link'),L('router','switch','','link'),L('switch','pc','Ethernet','link'),L('switch','phone','Wi-Fi','link')])]),
 P('game-system','Game Design','game-system',[
 S('event','Event','⚡','roundRect','game.event','event',[{key:'trigger',label:'Trigger',type:'text'}],{trigger:'OnInteract'}),
 S('condition','Condition','◇','diamond','game.condition',undefined,[{key:'predicate',label:'Predicate',type:'text'}],{predicate:'hasResource'}),
 S('action','Action','▣','roundRect','game.action',undefined,[{key:'effect',label:'Effect',type:'text'}],{effect:'modify state'}),
 S('resource','Resource','▤','rect','game.resource','database',[{key:'amount',label:'Quantity',type:'number'}],{amount:5}),
 S('npc','NPC / Actor','♟','roundRect','game.actor','actor',[{key:'faction',label:'Faction',type:'text'}],{faction:'neutral'})
 ],[T('survival-loop','Survival gameplay loop','game-system','Event → condition → action with resources and feedback',[
 N('event','event',65,180,'Player forages'),N('gate','condition',360,190,'Resource available?',{width:200,height:135}),
 N('award','action',670,115,'Collect food',{props:{effect:'inventory +1'}}),
 N('res','resource',940,115,'Food inventory',{props:{amount:1}}),
 N('fail','action',670,390,'Search elsewhere',{props:{effect:'set goal'}}),
 N('npc','npc',65,445,'Foraging NPC',{props:{faction:'tribe'}})
 ],[L('event','gate','OnUse','event'),L('gate','award','Yes','flow'),L('gate','fail','No','flow'),L('award','res','Grants','resource'),L('npc','event','Motivation','event')])]),
 P('floorplan','Floorplan','floorplan',[
 S('room','Room','▣','rect','floor.room','room',[{key:'widthMeters',label:'Width (m)',type:'number'},{key:'lengthMeters',label:'Length (m)',type:'number'}],{widthMeters:4,lengthMeters:3},300,220),
 S('door','Door','◫','rect','floor.door','door',[{key:'swing',label:'Swing',type:'select',options:['left','right','sliding']}],{swing:'left'},90,30),
 S('window','Window','═','rect','floor.window','window',[],{},140,27),
 S('furniture','Furniture','▣','roundRect','floor.furniture')
 ],[T('rooms','Room sketch','floorplan','Approximate adjacency sketch, not construction CAD',[
 N('living','room',100,160,'Living',{width:440,height:375,props:{widthMeters:5,lengthMeters:4}}),
 N('kitchen','room',540,160,'Kitchen',{width:400,height:225,props:{widthMeters:4,lengthMeters:3}}),
 N('bed','room',540,385,'Bedroom',{width:400,height:255}),
 N('door1','door',510,280,'',{width:65,height:30}),N('window1','window',210,148,'',{width:160,height:25})
 ],[]) ]),
 P('swimlane','Process / Swimlane','swimlane',[
 S('lane','Swimlane','▰','rect','process.lane','lane',[{key:'owner',label:'Owner',type:'text'}],{owner:'Team'},1040,150),
 S('task','Task','▣','roundRect','process.task'),
 S('decision','Decision','◇','diamond','process.decision')
 ],[T('handoff-process','Team handoff process','swimlane','Editable lanes with cross-lane ownership and flows',[
 N('lane1','lane',70,60,'Design',{width:1060,height:165}),N('lane2','lane',70,245,'Engineering',{width:1060,height:165}),
 N('lane3','lane',70,430,'QA',{width:1060,height:165}),
 N('spec','task',285,85,'Draft spec',{width:190,height:100}),N('impl','task',540,280,'Implement',{width:190,height:100}),
 N('verify','task',845,455,'Verify',{width:190,height:100})
 ],[L('spec','impl','handoff'),L('impl','verify','build ready')])]),
 P('wireframe','Wireframe / UI','custom',[
 S('screen','Screen','▣','rect','ui.screen','screen',[{key:'platform',label:'Platform',type:'select',options:['desktop','mobile','tablet']}],{platform:'mobile'},300,490),
 S('button','Button','▣','roundRect','ui.button'),
 S('input','Input','▱','rect','ui.input'),
 S('card','Card','▤','roundRect','ui.card')
 ],[T('mobile-screen','Mobile wireframe','custom','Screen layout as native editable boxes',[
 N('screen','screen',365,65,'App screen',{width:425,height:575,props:{platform:'mobile'}}),
 N('header','card',425,160,'Header',{width:300,height:65}),
 N('input','input',425,260,'Search',{width:300,height:62}),
 N('card1','card',425,365,'Result card',{width:300,height:100}),
 N('action','button',425,510,'Continue',{width:300,height:68})
 ],[])])
];
export const allTemplates=(packs:Pack[])=>packs.flatMap(p=>p.templates.map(t=>({...t,packId:p.id,packName:p.name})));
export const allStencils=(packs:Pack[])=>packs.flatMap(p=>p.shapes.map(s=>({...s,packId:p.id,packName:p.name})));
export const findStencil=(packs:Pack[],id:string,name:string)=>packs.find(p=>p.id===id)?.shapes.find(s=>s.name===name);

const ownedPorts=(stencil:Stencil):Port[]=>stencil.ports?.length
 ?stencil.ports.map(p=>({id:uuid('prt_'),name:p.name,direction:p.direction??'both',dataType:p.dataType,anchor:{side:p.side,t:.5}}))
 :initialPorts();
export function stencilNode(p:Pack,s:Stencil,x:number,y:number,layerId:string,title=s.title):NodeElement {
 const n=node(x,y,s.fallback,layerId,title);
 n.geometry.width=s.width??n.geometry.width;n.geometry.height=s.height??n.geometry.height;
 n.shape={libraryId:p.id,name:s.name,version:p.version,fallback:s.fallback};
 n.semantic={role:s.role,data:{...s.defaults,...(s.glyph?{'omnidesign/glyph':s.glyph}:{})}};
 n.ports=ownedPorts(s);
 n.style={...n.style,...s.style};
 n.label={text:title,style:{...n.label?.style}};
 return n;
}
const embeddedPack=(p:Pack)=>({id:p.id,version:p.version,name:p.name,shapes:p.shapes.map(s=>({
 name:s.name,fallback:s.fallback,
 ...(s.style?{defaultStyle:s.style}:{}),
 ...(s.ports?{defaultPorts:s.ports.map(port=>({name:port.name,direction:port.direction??'both',anchor:{side:port.side,t:.5},...(port.dataType?{dataType:port.dataType}:{})}))}:{})
}))});
export function attachPack(d:Diagram,p:Pack):void {
 if(!d.libraries.references.some(r=>r.id===p.id&&r.version===p.version))d.libraries.references.push({id:p.id,version:p.version});
 if(!builtInPacks.some(b=>b.id===p.id&&b.version===p.version)){
  d.libraries.embedded=d.libraries.embedded.filter(v=>!(v&&typeof v==='object'&&'id' in v&&v.id===p.id));
  d.libraries.embedded.push(embeddedPack(p));
  d.extensions={...d.extensions,'omnidesign/packDefinitions':{
   ...(d.extensions?.['omnidesign/packDefinitions'] as Record<string,Pack>|undefined),
   [p.id]:p
  }};
 }
}
export function instantiateTemplate(p:Pack,t:Template,packs:Pack[]):Diagram {
 const d=newDiagram(t.title,t.family),layer=d.layers[0].id,refs=new Map<string,NodeElement>();
 d.metadata.description=t.description;
 d.page.width=1200;d.page.height=700;
 for(const spec of t.nodes){
  const stencil=p.shapes.find(s=>s.name===spec.stencil)??allStencils(packs).find(s=>s.name===spec.stencil);
  if(!stencil)continue;
  const source=p.shapes.includes(stencil)?p:packs.find(pack=>pack.shapes.includes(stencil))??p;
  const n=stencilNode(source,stencil,spec.x,spec.y,layer,spec.text??stencil.title);
  n.geometry.width=spec.width??n.geometry.width;n.geometry.height=spec.height??n.geometry.height;
  n.semantic={role:stencil.role,data:{...stencil.defaults,...(stencil.glyph?{'omnidesign/glyph':stencil.glyph}:{}),...spec.props}};
  if(spec.subtitle||spec.items?.length)n.content={title:spec.text??stencil.title,subtitle:spec.subtitle,items:(spec.items??[]).map(text=>({id:uuid('itm_'),text})),chips:[]};
  n.order=d.elements.length;d.elements.push(n);refs.set(spec.key,n);attachPack(d,source);
 }
 for(const link of t.links){
  const a=refs.get(link.from),b=refs.get(link.to);if(!a||!b)continue;
  const e=edge(a,link.fromSide??'right',b,link.toSide??'left',layer);
  if(e.type!=='edge')continue;
  e.order=d.elements.length;e.route.mode=link.route??(link.kind==='message'||link.kind==='return'?'straight':'orthogonal');
  if(t.family==='sequence'){
   // Sequence messages bind to timed anchors rather than the middle of every lifeline.
   const time=.23+.115*t.links.indexOf(link);
   e.source={type:'anchor',nodeId:a.id,anchor:{side:link.fromSide??'right',t:time}};
   e.target={type:'anchor',nodeId:b.id,anchor:{side:link.toSide??'left',t:time}};
  }
  e.label={text:link.label??''};e.semantic={role:link.kind??'flow',data:{kind:link.kind??'flow'}};
  if(link.kind==='return')e.style={...e.style,strokeDash:[6,5]};
  if(link.kind==='association'||link.kind==='link')e.style={...e.style,stroke:palette.muted};
  d.elements.push(e);
 }
 return d;
}

/** Only declarative shapes/templates are accepted; no executable code, SVG/HTML injection or new canvas types. */
export function parsePack(raw:unknown):Pack {
 if(!raw||typeof raw!=='object')throw Error('Pack must be a JSON object.');
 const p=raw as Pack;
 if(p.format!=='omnidesign-pack'||p.schemaVersion!==1||typeof p.id!=='string'||!/^[a-z][a-z0-9-]{1,50}$/.test(p.id)||
 !p.version||typeof p.version!=='string'||!p.name||typeof p.name!=='string'||!Array.isArray(p.shapes)||p.shapes.length===0||p.shapes.length>120||
 !Array.isArray(p.templates)||p.templates.length>50||JSON.stringify(raw).length>150000)throw Error('Unsupported pack format or limits.');
 if(builtInPacks.some(b=>b.id===p.id))throw Error('Built-in packs cannot be overwritten.');
 const fallbacks=['rect','roundRect','ellipse','diamond','line','polygon','icon'],types=['text','number','boolean','select'];
 const families=['architecture','flowchart','sequence','state','erd','network','roadmap','floorplan','swimlane','mindmap','game-system','custom'];
 if(!families.includes(p.family)||p.templates.some(t=>!families.includes(t.family)))throw Error('Unsupported diagram family.');
 const names=new Set<string>();
 for(const s of p.shapes){
  if(!s||typeof s.name!=='string'||!s.name||names.has(s.name)||typeof s.title!=='string'||typeof s.role!=='string'||!fallbacks.includes(s.fallback))throw Error('Invalid or duplicate stencil name.');
  names.add(s.name);
  if(s.fields&&(!Array.isArray(s.fields)||s.fields.length>30||s.fields.some(f=>!f.key||!f.label||!types.includes(f.type))))throw Error('Invalid property fields.');
  if(s.ports&&(!Array.isArray(s.ports)||s.ports.length>24||s.ports.some(port=>!port.name||!['top','bottom','left','right','center'].includes(port.side))))throw Error('Invalid ports.');
  if((s.width!==undefined&&(!Number.isFinite(s.width)||s.width<=0))||(s.height!==undefined&&(!Number.isFinite(s.height)||s.height<=0)))throw Error('Invalid stencil dimensions.');
 }
 for(const t of p.templates){
  if(!t||!t.id||!t.title||!Array.isArray(t.nodes)||t.nodes.length>300||!Array.isArray(t.links)||t.links.length>600)throw Error('Invalid template.');
  if(t.nodes.some(n=>!names.has(n.stencil)||!n.key||!Number.isFinite(n.x)||!Number.isFinite(n.y)))throw Error('Template references a missing stencil or coordinate.');
  const keys=new Set(t.nodes.map(n=>n.key));
  if(keys.size!==t.nodes.length||t.links.some(l=>!keys.has(l.from)||!keys.has(l.to)))throw Error('Template has duplicate/missing node references.');
 }
 return structuredClone(p);
}
export const loadPersonalPacks=():Pack[]=>{
 try{const saved=JSON.parse(localStorage.getItem('omnidesign.shapePacks')||'[]');return Array.isArray(saved)?saved.flatMap(p=>{try{return [parsePack(p)];}catch{return [];}}):[];}catch{return [];}
};
export const savePersonalPacks=(packs:Pack[])=>localStorage.setItem('omnidesign.shapePacks',JSON.stringify(packs));
export const documentPacks=(d:Diagram):Pack[]=>{
 const dict=d.extensions?.['omnidesign/packDefinitions'];
 return dict&&typeof dict==='object'?Object.values(dict).flatMap(p=>{try{return [parsePack(p)];}catch{return [];}}):[];
};
