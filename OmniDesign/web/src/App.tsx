import React,{useCallback,useEffect,useRef,useState} from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
import {Canvas,type CanvasApi} from './Canvas';
import {GraphicScene} from './GraphicScene';
import {clone,edge,exampleDiagram,groupElements,itemById,newDiagram,node,origin,palette,removeElements,snap,stamp,translate,ungroupElements,uuid,validateDocument} from './model';
import {download,filename,getDocuments,getSynced,needsSync,putDocument,removeDocument,setSynced,type SyncedBaseline} from './storage';
import {hubHealth,hubJson,HubHttpError,inAndroid} from './androidBridge';
import type {Diagram,Element,NodeElement,Point,Style,Tool} from './types';
import {allStencils,allTemplates,attachPack,builtInPacks,documentPacks,findStencil,instantiateTemplate,loadPersonalPacks,parsePack,savePersonalPacks,stencilNode,type Pack,type Stencil} from './packs';
import {edgeStyleKinds} from './StencilArt';
const families:Diagram['family'][]=['architecture','flowchart','sequence','state','erd','network','roadmap','floorplan','swimlane','mindmap','game-system','custom'];
const shapes:{tool:Tool;label:string;icon:string}[]=[
 {tool:'select',label:'Select',icon:'↖'},{tool:'hand',label:'Pan',icon:'✋'},{tool:'connect',label:'Connect',icon:'↗'},
 {tool:'rect',label:'Rectangle',icon:'▣'},{tool:'roundRect',label:'Panel',icon:'▢'},
 {tool:'ellipse',label:'Ellipse',icon:'◯'},{tool:'diamond',label:'Decision',icon:'◇'},
 {tool:'text',label:'Text',icon:'T'},{tool:'pen',label:'Draw',icon:'✎'}];
const filePicker=()=>document.getElementById('omd-import') as HTMLInputElement;
const isMobile=()=>window.matchMedia('(max-width:760px)').matches;
function Button({children,onClick,title,disabled,active=false,className=''}:{children:React.ReactNode;onClick:()=>void;title?:string;disabled?:boolean;active?:boolean;className?:string}){
 return <button type="button" className={`button ${active?'active':''} ${className}`} onClick={onClick} disabled={disabled} title={title}>{children}</button>;
}
function Numeric({value,change,min=-1e6,max=1e6}:{value:number;change:(v:number)=>void;min?:number;max?:number}){
 const [draft,setDraft]=useState(String(Math.round(value*100)/100));useEffect(()=>setDraft(String(Math.round(value*100)/100)),[value]);
 return <input type="number" value={draft} onChange={e=>setDraft(e.target.value)} onBlur={()=>{const n=Number(draft);if(draft.trim()!==''&&Number.isFinite(n))change(Math.min(max,Math.max(min,n)));else setDraft(String(value));}} onKeyDown={e=>{if(e.key==='Enter')e.currentTarget.blur();}}/>;
}
function App(){
 const [doc,setDoc]=useState<Diagram>(()=>exampleDiagram()),docRef=useRef(doc);
 const history=useRef<{past:Diagram[];future:Diagram[]}>({past:[],future:[]});
 const [revision,setRevision]=useState(0),[selected,setSelected]=useState<string[]>([]),[tool,setTool]=useState<Tool>('select');
 const [library,setLibrary]=useState(false),[saved,setSaved]=useState<Diagram[]>([]);
 const [mobileActions,setMobileActions]=useState(false);
 const [panel,setPanel]=useState<'inspector'|'layers'|'outline'|'shapes'|null>('inspector');
 const [mobileSheet,setMobileSheet]=useState(false),[activeLayer,setActiveLayer]=useState<string>('');
 const [message,setMessage]=useState('Saved locally'),[ready,setReady]=useState(false);
 const [synced,setSyncedMap]=useState<Record<string,SyncedBaseline>>({});
 const [hubOnline,setHubOnline]=useState<boolean|null>(null),[syncBusy,setSyncBusy]=useState(false);
 const [conflicts,setConflicts]=useState<Record<string,string>>({});
 const syncLock=useRef(false);
 const [hubDocs,setHubDocs]=useState<{id:string;title:string;family:string;revision:number}[]>([]),[hubOpen,setHubOpen]=useState(false);
 const [personalPacks,setPersonalPacks]=useState<Pack[]>(loadPersonalPacks);
 const [templateGallery,setTemplateGallery]=useState(false),[shapeSearch,setShapeSearch]=useState(''),[packFilter,setPackFilter]=useState('all');
 const [stencilChoice,setStencilChoice]=useState<{packId:string;name:string}|null>(null),[edgeKind,setEdgeKind]=useState('flow');
 const packs=[...builtInPacks,...personalPacks,...documentPacks(doc).filter(p=>!personalPacks.some(q=>q.id===p.id&&q.version===p.version))];
 const canvas=useRef<CanvasApi>(null);
 const replace=(next:Diagram)=>{docRef.current=next;setDoc(next);};
 const updateLibrary=async()=>{try{
  const [documents,baselines]=await Promise.all([getDocuments(),getSynced()]);
  setSaved(documents.sort((a,b)=>b.metadata.updatedAt.localeCompare(a.metadata.updatedAt)));
  setSyncedMap(baselines);
 }catch(e){setMessage('Local storage unavailable: '+String(e));}};
 const openDoc=(next:Diagram)=>{replace(clone(next));setSelected([]);history.current={past:[],future:[]};setRevision(v=>v+1);setActiveLayer(next.layers[0].id);localStorage.setItem('omnidesign.current',next.id);setLibrary(false);setMobileSheet(false);setTool('select');};
 useEffect(()=>{let live=true;(async()=>{try{
  const all=await getDocuments();if(!live)return;
  const id=localStorage.getItem('omnidesign.current');let prior:Diagram|undefined=all.find(d=>d.id===id)||all[0];
  const deepId=new URLSearchParams(window.location.search).get('diagram');
  if(deepId){prior=all.find(d=>d.id===deepId);
   if(!prior)try{prior=validateDocument(await hubJson('diagrams/'+encodeURIComponent(deepId)));await putDocument(prior);await setSynced(prior);}catch{/* Offline: never replace the locally cached diagram. */}}
  if(prior)openDoc(validateDocument(prior));else{const example=exampleDiagram();openDoc(example);await putDocument(example);}
  await updateLibrary();
 }catch(e){setMessage('Storage issue: '+String(e));}finally{if(live)setReady(true);}})();return()=>{live=false;};},[]);
 useEffect(()=>{if(!ready)return;setMessage('Saving locally…');
  const persist=async()=>{try{await putDocument(doc);setMessage('Saved locally');await updateLibrary();}catch(e){setMessage('Save failed: '+String(e));}};
  // Android cannot afford to lose the final edit on a fast app switch.
  if(inAndroid){void persist();return;}
  const timer=setTimeout(persist,420);return()=>clearTimeout(timer);
 },[doc,ready]);
 useEffect(()=>{if(!inAndroid)return;
  // Native Back waits for the last IndexedDB write to finish before tearing down the WebView.
  window.omniDesignBeforeExit=()=>{void putDocument(docRef.current).finally(()=>window.OmniDesignNative?.flushed());};
  return()=>{delete window.omniDesignBeforeExit;};
 },[]);
 useEffect(()=>{if(!ready)return;
  let live=true;const check=async()=>{const online=await hubHealth();if(live)setHubOnline(online);};
  void check();const interval=setInterval(check,25000);window.addEventListener('online',check);
  return()=>{live=false;clearInterval(interval);window.removeEventListener('online',check);};
 },[ready]);
 // Hub writes are explicit; local edits keep their full native document in IndexedDB.
 // Conditional revisions prevent another device or agent from being overwritten.
 const hubRequest=async(path:string,options?:RequestInit):Promise<{data:any}>=>{
  try{const data=await hubJson(path,options);setHubOnline(true);return {data};}
  catch(error){if(!(error instanceof HubHttpError)||![409,412,422].includes(error.status))setHubOnline(false);throw error;}
 };
 const uploadDocument=async(snapshot:Diagram)=>{
  validateDocument(snapshot);
  const creating=snapshot.revision===0,payload=JSON.stringify(snapshot);
  // Replaying an uncertain write uses the same idempotency key.
  const digest=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(payload));
  const key=Array.from(new Uint8Array(digest),b=>b.toString(16).padStart(2,'0')).join('');
  try{
   const {data}=await hubRequest('diagrams'+(creating?'':'/'+snapshot.id),{
    method:creating?'POST':'PUT',
    headers:creating?{'Content-Type':'application/json'}:{'Content-Type':'application/json','If-Match':'"rev-'+snapshot.revision+'"','Idempotency-Key':key},
    body:payload
   });
   const accepted=validateDocument(data);
   // Record the confirmed revision BEFORE changing the local document.
   await setSynced(accepted);
   if(docRef.current.id===snapshot.id){
    const unchanged=JSON.stringify(docRef.current)===JSON.stringify(snapshot);
    const next=unchanged?accepted:{...docRef.current,revision:accepted.revision};
    replace(next);await putDocument(next);
   }else{
    // A different diagram is open; never swap its canvas.
    await putDocument(accepted);
   }
   setConflicts(c=>{const n={...c};delete n[snapshot.id];return n;});
   await updateLibrary();
   return accepted;
  }catch(error){
   if(error instanceof HubHttpError&&[409,412].includes(error.status))
    setConflicts(c=>({...c,[snapshot.id]:'Hub has a different revision. Local edits are safe. Keep both or review on PC.'}));
   throw error;
  }
 };
 const saveToHub=async()=>{
  if(syncLock.current)return;
  syncLock.current=true;setSyncBusy(true);
  try{await putDocument(docRef.current);const accepted=await uploadDocument(clone(docRef.current));setMessage('Hub saved · revision '+accepted.revision);}
  catch(e){setMessage('Hub save failed; saved locally: '+String(e));}
  finally{syncLock.current=false;setSyncBusy(false);}
 };
 const syncPending=async()=>{
  if(syncLock.current)return;
  syncLock.current=true;setSyncBusy(true);
  let uploaded=0,failed=0;
  try{
   await putDocument(docRef.current);
   const [documents,baselines]=await Promise.all([getDocuments(),getSynced()]);
   for(const item of documents){
    const snapshot=item.id===docRef.current.id?clone(docRef.current):item;
    if(!needsSync(snapshot,baselines[item.id]))continue;
    try{await uploadDocument(snapshot);uploaded++;}catch{failed++;}
   }
   setMessage(failed?uploaded+' synced; '+failed+' kept locally (see pending/conflict)':'All pending diagrams synced · '+uploaded+' uploaded');
  }catch(error){setMessage('Sync unavailable; diagrams retained locally: '+String(error));}
  finally{await updateLibrary();syncLock.current=false;setSyncBusy(false);}
 };
 const keepBoth=async()=>{
  const original=clone(docRef.current);
  const copy={...original,id:uuid('dgm_'),revision:0,title:original.title+' · local copy',
   metadata:{...original.metadata,updatedAt:stamp(),forkedFrom:{id:original.id,revision:original.revision}}};
  // First make the conflicting local version durable under a fresh ID.
  await putDocument(copy);
  // Then restore the unmodified Hub version to its original stable ID.
  // If connectivity fails, both local copies remain intact for later reconciliation.
  try{
   const {data}=await hubRequest('diagrams/'+original.id),remote=validateDocument(data);
   await putDocument(remote);await setSynced(remote);
   setConflicts(c=>{const next={...c};delete next[original.id];return next;});
  }catch{/* Preserve the original pending local source when Hub is unreachable. */}
  openDoc(copy);await updateLibrary();
  setMessage('Local version saved as a separate ID. The Hub original was not overwritten.');
 };
 const openHubLibrary=async()=>{try{
  const {data}=await hubRequest('diagrams?limit=100');
  setHubDocs(data.items??[]);setHubOpen(true);
 }catch(e){setMessage('Hub unavailable: '+String(e));}};
 const openFromHub=async(id:string)=>{try{
  await putDocument(docRef.current); // Keep the current local working state before changing documents.
  const {data}=await hubRequest('diagrams/'+id);
  const native=validateDocument(data);
  const locallySaved=(await getDocuments()).find(d=>d.id===id),baseline=(await getSynced())[id];
  if(locallySaved&&needsSync(locallySaved,baseline)){
   openDoc(locallySaved);setHubOpen(false);setMessage('Opened local pending edits, not the newer Hub version.');return;
  }
  await putDocument(native);await setSynced(native);openDoc(native);await updateLibrary();setHubOpen(false);
  setMessage('Opened Hub revision '+native.revision);
 }catch(e){setMessage('Hub open failed: '+String(e));}};
 const copyHubLink=async()=>{if(inAndroid){setMessage('Use the PC editor to copy its private Hub link.');return;}try{const prefix=location.pathname.includes('/gateway/tools/omnidesign/')?location.origin+'/gateway/tools/omnidesign/':location.origin+'/';await navigator.clipboard.writeText(prefix+'?diagram='+encodeURIComponent(docRef.current.id));setMessage(docRef.current.revision?'Link copied':'Link copied · this ID is local only until first Hub save');}catch(e){setMessage('Unable to copy link: '+String(e));}};
 const commit=(edit:(d:Diagram)=>Diagram)=>{const before=docRef.current,next=edit(clone(before));if(JSON.stringify(before)===JSON.stringify(next))return;history.current.past.push(clone(before));if(history.current.past.length>100)history.current.past.shift();history.current.future=[];next.metadata={...next.metadata,updatedAt:stamp()};replace(next);setRevision(v=>v+1);};
 const preview=(d:Diagram)=>replace(d);
 const finish=(before:Diagram)=>{if(JSON.stringify(before)===JSON.stringify(docRef.current))return;history.current.past.push(before);if(history.current.past.length>100)history.current.past.shift();history.current.future=[];replace({...docRef.current,metadata:{...docRef.current.metadata,updatedAt:stamp()}});setRevision(v=>v+1);};
 const undo=()=>{const prior=history.current.past.pop();if(!prior)return;history.current.future.push(clone(docRef.current));replace(prior);setSelected([]);setRevision(v=>v+1);};
 const redo=()=>{const next=history.current.future.pop();if(!next)return;history.current.past.push(clone(docRef.current));replace(next);setSelected([]);setRevision(v=>v+1);};
 const create=(kind:Tool,p:Point)=>{let made='';commit(d=>{
  const layer=d.layers.find(l=>l.id===activeLayer&&!l.locked&&l.visible)||d.layers.find(l=>l.visible&&!l.locked);if(!layer)return d;
  const pack=kind==='stencil'?packs.find(q=>q.id===stencilChoice?.packId):undefined;
  const definition=pack?.shapes.find(s=>s.name===stencilChoice?.name);
  if(kind==='stencil'&&(!pack||!definition))return d;
  const shape=kind==='stencil'&&pack&&definition?stencilNode(pack,definition,p.x,p.y,layer.id):kind==='text'?{id:uuid('el_'),type:'text' as const,layerId:layer.id,parentId:null,order:d.elements.length,geometry:{x:p.x,y:p.y,width:280,height:60,rotation:0},text:'Double-click to edit',style:{textColor:palette.ink,fontSize:23}}:node(p.x,p.y,kind as NodeElement['shape']['fallback'],layer.id,kind==='diamond'?'Decision':kind==='ellipse'?'State':'New shape');
  if(kind==='stencil'&&pack)attachPack(d,pack);
  shape.order=d.elements.length;made=shape.id;d.elements.push(shape);return d;
 });if(made){setSelected([made]);if(isMobile())setTool('select');}};
 const draw=(points:Point[])=>{commit(d=>{const layer=d.layers.find(l=>l.id===activeLayer&&!l.locked&&l.visible)||d.layers[0];d.elements.push({id:uuid('el_'),type:'path',layerId:layer.id,parentId:null,order:d.elements.length,commands:points.map((p,i)=>({op:i?'L':'M',to:p})),style:{stroke:palette.accent,strokeWidth:3},semantic:{role:'freehand',data:{}}});return d;});};
 const connect=(from:{nodeId:string;portId:string},to:{nodeId:string;portId:string})=>{
  const a0=itemById(docRef.current,from.nodeId),b0=itemById(docRef.current,to.nodeId);
  if(!a0||a0.type!=='node'||!b0||b0.type!=='node')return;
  const p0=a0.ports.find(p=>p.id===from.portId),q0=b0.ports.find(p=>p.id===to.portId);
  if(!p0||!q0||p0.direction==='in'||q0.direction==='out'||(p0.dataType&&q0.dataType&&p0.dataType!==q0.dataType)){setMessage('Connector ports have incompatible directions or data types.');return;}
  commit(d=>{
  const a=itemById(d,from.nodeId),b=itemById(d,to.nodeId);if(!a||a.type!=='node'||!b||b.type!=='node')return d;
  if(d.layers.find(l=>l.id===a.layerId)?.locked||d.layers.find(l=>l.id===b.layerId)?.locked)return d;
  const el=edge(a,a.ports.find(p=>p.id===from.portId)?.anchor.side??'right',b,b.ports.find(p=>p.id===to.portId)?.anchor.side??'left',a.layerId);
  if(el.type==='edge'){el.source={type:'port',nodeId:a.id,portId:from.portId};el.target={type:'port',nodeId:b.id,portId:to.portId};el.order=d.elements.length;
   el.semantic={role:edgeKind,data:{kind:edgeKind}};el.route.mode=['message','return','link'].includes(edgeKind)?'straight':'orthogonal';
   if(edgeKind==='return'||edgeKind==='dependency')el.style={...el.style,strokeDash:[6,5]};
   if(edgeKind==='association'||edgeKind==='link')el.style={...el.style,stroke:palette.muted};}
  d.elements.push(el);return d;
 });};
 const changeElement=(id:string,patch:(e:Element)=>Element)=>commit(d=>({...d,elements:d.elements.map(e=>e.id===id?patch(e):e)}));
 const remove=()=>{if(!selected.length)return;commit(d=>removeElements(d,selected));setSelected([]);};
 const group=()=>{let groupId:string|null=null;commit(d=>{const r=groupElements(d,selected);groupId=r.id;return r.document;});if(groupId)setSelected([groupId]);else setMessage('Select 2+ shapes on the same layer and parent.');};
 const ungroup=()=>{const el=selected.length===1?itemById(doc,selected[0]):undefined;if(el?.type==='group'){commit(d=>ungroupElements(d,el.id));setSelected([]);}};
 const select=(ids:string[])=>setSelected(ids.filter(id=>{const e=itemById(docRef.current,id);return e&&!e.locked&&!docRef.current.layers.find(l=>l.id===e.layerId)?.locked;}));
 const edit=(id:string)=>{setSelected([id]);setPanel('inspector');setMobileSheet(true);};
 useEffect(()=>{const fn=(e:KeyboardEvent)=>{const tag=(e.target as HTMLElement)?.tagName;if(['INPUT','TEXTAREA','SELECT'].includes(tag)||(e.target as HTMLElement)?.isContentEditable)return;
  const cmd=e.ctrlKey||e.metaKey,k=e.key.toLowerCase();
  if(cmd&&k==='z'){e.preventDefault();e.shiftKey?redo():undo();}
  else if(cmd&&k==='y'){e.preventDefault();redo();}
  else if(cmd&&k==='g'){e.preventDefault();group();}
  else if(cmd&&k==='s'){e.preventDefault();exportNative();}
  else if(['delete','backspace'].includes(k)){e.preventDefault();remove();}
  else if(k==='escape'){setTool('select');setMobileSheet(false);setSelected([]);}
  else if(k==='v')setTool('select');
  else if(k==='c'&&!cmd)setTool('connect');
  else if(k==='p')setTool('pen');
 };window.addEventListener('keydown',fn);return()=>window.removeEventListener('keydown',fn);});
 const exportNative=()=>{try{const valid=validateDocument(docRef.current);download(new Blob([JSON.stringify(valid,null,2)],{type:'application/json'}),filename(valid.title,'.omd.json'));}catch(e){setMessage('Export failed: '+String(e));}};
 const svgMarkup=()=>renderToStaticMarkup(<svg xmlns="http://www.w3.org/2000/svg" width={docRef.current.page.width} height={docRef.current.page.height} viewBox={`0 0 ${docRef.current.page.width} ${docRef.current.page.height}`}><GraphicScene document={docRef.current}/></svg>);
 const exportSvg=()=>{download(new Blob([svgMarkup()],{type:'image/svg+xml;charset=utf-8'}),filename(docRef.current.title,'.svg'));};
 const exportPng=async()=>{try{const source=new Blob([svgMarkup()],{type:'image/svg+xml'}),url=URL.createObjectURL(source),img=new Image();await new Promise<void>((resolve,reject)=>{img.onload=()=>resolve();img.onerror=reject;img.src=url;});const c=document.createElement('canvas');c.width=docRef.current.page.width*2;c.height=docRef.current.page.height*2;const ctx=c.getContext('2d');if(!ctx)throw Error('2D canvas unavailable');ctx.drawImage(img,0,0,c.width,c.height);URL.revokeObjectURL(url);c.toBlob(blob=>blob?download(blob,filename(docRef.current.title,'.png')):setMessage('PNG encoder unavailable.'),'image/png');}catch(e){setMessage('PNG failed: '+String(e));}};
 const importPack=async(file:File)=>{try{const imported=parsePack(JSON.parse(await file.text()));
  const updated=[...personalPacks.filter(p=>p.id!==imported.id),imported];savePersonalPacks(updated);setPersonalPacks(updated);
  setPackFilter(imported.id);setPanel('shapes');setMobileSheet(true);setMessage('Library added: '+imported.name+' · '+imported.shapes.length+' shapes');
 }catch(error){setMessage('Pack import rejected: '+String(error));}};
 const fromTemplate=(pack:Pack,template:Pack['templates'][number])=>{openDoc(instantiateTemplate(pack,template,packs));setTemplateGallery(false);setMessage('Editable '+template.title+' template created');};
 const importFile=async(file:File)=>{try{const text=await file.text(),parsed=validateDocument(JSON.parse(text));const existing=(await getDocuments()).find(d=>d.id===parsed.id);if(existing&&!window.confirm('Replace the locally saved diagram with the same document ID? Cancel to keep both as a copy.')){parsed.id=uuid('dgm_');parsed.revision=0;parsed.metadata.forkedFrom={id:existing.id,revision:existing.revision};parsed.title+=' · copy';}
  await putDocument(parsed);openDoc(parsed);updateLibrary();setMessage('Native document imported.');}catch(e){setMessage('Import rejected: '+String(e));}};
 const addDocument=()=>{const d=newDiagram('Untitled diagram');openDoc(d);setLibrary(false);setTemplateGallery(false);};
 const removeCurrent=async()=>{if(!window.confirm('Delete this locally saved diagram? Export it first if you need a backup.'))return;await removeDocument(doc.id);const others=(await getDocuments()).filter(d=>d.id!==doc.id);openDoc(others[0]??newDiagram());updateLibrary();};
 const moveToLayer=(id:string,layerId:string)=>commit(d=>{const target=itemById(d,id);if(!target)return d;const previousOrigin=origin(d,target);const isChild=(e:Element)=>{let p=e.parentId;while(p){if(p===id)return true;p=itemById(d,p)?.parentId??null;}return false;};return {...d,elements:d.elements.map(e=>e.id===id?{...translate(e,previousOrigin.x,previousOrigin.y),layerId,parentId:null}:target.type==='group'&&isChild(e)?{...e,layerId}:e)};});
 const selectedEl=selected.length===1?itemById(doc,selected[0]):undefined;
 const editable=selectedEl&&!selectedEl.locked&&!doc.layers.find(l=>l.id===selectedEl.layerId)?.locked;
 const keyRow=(id:string)=>{const e=itemById(doc,id);if(!e)return '';return e.type==='node'?e.content?.title||e.label?.text||'Shape':e.type==='text'?e.text.slice(0,24):e.type==='group'?e.label?.text||'Group':e.type==='edge'?e.label?.text||'Connection':'Path';};
 const layer=doc.layers.find(l=>l.id===selectedEl?.layerId);
 const activeStencil=selectedEl?.type==='node'?findStencil(packs,selectedEl.shape.libraryId,selectedEl.shape.name):undefined;
 const matches=allStencils(packs).filter(s=>(packFilter==='all'||s.packId===packFilter)&&((s.title+' '+s.packName+' '+s.role).toLowerCase().includes(shapeSearch.toLowerCase())));
 const staged=saved.some(d=>d.id===doc.id)?saved.map(d=>d.id===doc.id?doc:d):[...saved,doc];
 const pendingCount=staged.filter(d=>needsSync(d,synced[d.id])).length;
 const documentState=(d:Diagram)=>conflicts[d.id]?'Conflict':!needsSync(d,synced[d.id])?'Synced':d.revision===0?'Local only':'Pending';
 const toolsPanel=<div className="tool-stack">{shapes.map(s=><Button key={s.tool} title={s.label} active={tool===s.tool} onClick={()=>{setTool(s.tool);setMobileSheet(false);}}><b>{s.icon}</b><span>{s.label}</span></Button>)}<Button title="Shape libraries" active={tool==='stencil'} onClick={()=>{setPanel('shapes');setMobileSheet(true);}}><b>▦</b><span>Kits</span></Button></div>;
 const field=(label:string,children:React.ReactNode)=><label className="field"><span>{label}</span>{children}</label>;
 const updateText=(text:string)=>{if(!selectedEl)return;changeElement(selectedEl.id,e=>{
  if(e.type==='node')return {...e,label:{...e.label,text},...(e.content?{content:{...e.content,title:text}}:{})};
  if(e.type==='group')return {...e,label:{text}};
  if(e.type==='text')return {...e,text};
  if(e.type==='edge')return {...e,label:{text}};
  return e;
 });};
 const shapeInspector=<div className="inspector-body">
  {selected.length>1?<><p className="subheading">{selected.length} elements selected</p><Button onClick={group}>Group selection</Button><Button onClick={()=>{const d=docRef.current;const bases=selected.map(id=>itemById(d,id)).filter(e=>e&&'geometry'in e) as (Element&{geometry:{x:number;y:number}})[];if(bases.length<2)return;const x=Math.min(...bases.map(e=>e.geometry.x));commit(d=>({...d,elements:d.elements.map(e=>selected.includes(e.id)&&'geometry'in e?{...e,geometry:{...e.geometry,x}}:e)}));}}>Align left</Button><Button onClick={remove}>Delete selected</Button></>:
  editable&&selectedEl?<><div className="inspector-header"><span>{selectedEl.type.toUpperCase()}</span><Button onClick={remove}>Delete</Button></div>
   {field('Name / text',<textarea key={selectedEl.id+'label'} defaultValue={keyRow(selectedEl.id)} rows={2} onBlur={e=>updateText(e.target.value)}/>)}
   {'geometry'in selectedEl&&<><div className="section-head">Geometry</div><div className="number-grid">{(['x','y','width','height'] as const).map(k=>field(k.toUpperCase(),<Numeric key={selectedEl.id+k} value={selectedEl.geometry[k]} min={k==='width'||k==='height'?1:-1e6} change={n=>changeElement(selectedEl.id,e=>'geometry'in e?{...e,geometry:{...e.geometry,[k]:n}}:e)}/>))}</div></>}
   {selectedEl.type==='node'&&<>{field('Shape',<select value={selectedEl.shape.fallback} onChange={e=>changeElement(selectedEl.id,n=>n.type==='node'?{...n,shape:{...n.shape,fallback:e.target.value as NodeElement['shape']['fallback']}}:n)}>{['rect','roundRect','ellipse','diamond','polygon','line','icon'].map(v=><option key={v} value={v}>{v}</option>)}</select>)}
   {selectedEl.content&&<>{field('Subtitle',<textarea key={selectedEl.id+'sub'} defaultValue={selectedEl.content.subtitle||''} onBlur={ev=>changeElement(selectedEl.id,n=>n.type==='node'&&n.content?{...n,content:{...n.content,subtitle:ev.target.value}}:n)}/>)}
   {field('List items (one per line)',<textarea key={selectedEl.id+'items'} rows={4} defaultValue={selectedEl.content.items.map(i=>i.text).join('\n')} onBlur={ev=>changeElement(selectedEl.id,n=>{if(n.type!=='node'||!n.content)return n;return {...n,content:{...n.content,items:ev.target.value.split('\n').filter(Boolean).map((text,i)=>({...n.content!.items[i],id:n.content!.items[i]?.id||uuid('itm_'),text}))}};})}/>)}
   {field('Chips (one per line)',<textarea key={selectedEl.id+'chips'} defaultValue={selectedEl.content.chips.map(i=>i.text).join('\n')} onBlur={ev=>changeElement(selectedEl.id,n=>{if(n.type!=='node'||!n.content)return n;return {...n,content:{...n.content,chips:ev.target.value.split('\n').filter(Boolean).map((text,i)=>({...n.content!.chips[i],id:n.content!.chips[i]?.id||uuid('itm_'),text}))}};})}/>)}</>}
   </>}
   {selectedEl.type==='node'&&activeStencil&&<><div className="section-head">{activeStencil.title} · {selectedEl.shape.libraryId}</div>
    {activeStencil.fields?.map(spec=>{const value=selectedEl.semantic?.data?.[spec.key]??activeStencil.defaults?.[spec.key]??'';
     const change=(next:string|number|boolean)=>changeElement(selectedEl.id,e=>e.type==='node'?{...e,semantic:{role:e.semantic?.role??activeStencil.role,data:{...e.semantic?.data,[spec.key]:next}}}:e);
     return field(spec.label,spec.type==='boolean'?<input key={selectedEl.id+spec.key} type="checkbox" checked={Boolean(value)} onChange={ev=>change(ev.target.checked)}/>:spec.type==='select'?<select key={selectedEl.id+spec.key} value={String(value)} onChange={ev=>change(ev.target.value)}>{spec.options?.map(o=><option key={o} value={o}>{o}</option>)}</select>:spec.type==='number'?<Numeric key={selectedEl.id+spec.key} value={Number(value)||0} change={change}/>:<input key={selectedEl.id+spec.key} defaultValue={String(value)} onBlur={ev=>change(ev.target.value)}/>);
    })}</>}
   {selectedEl.type==='edge'&&<>{field('Connector semantics',<select value={String(selectedEl.semantic?.data?.kind??selectedEl.semantic?.role??'flow')} onChange={ev=>changeElement(selectedEl.id,e=>e.type==='edge'?{...e,semantic:{role:ev.target.value,data:{...e.semantic?.data,kind:ev.target.value}}}:e)}>{edgeStyleKinds.map(k=><option key={k.id} value={k.id}>{k.label}</option>)}</select>)}
    {field('Route',<select value={selectedEl.route.mode} onChange={ev=>changeElement(selectedEl.id,e=>e.type==='edge'?{...e,route:{...e.route,mode:ev.target.value as 'straight'|'orthogonal'|'bezier',manual:false}}:e)}><option value="orthogonal">Orthogonal</option><option value="straight">Straight</option><option value="bezier">Curved</option></select>)}
    {['sourceCardinality','targetCardinality'].map(key=>field(key==='sourceCardinality'?'Source multiplicity':'Target multiplicity',<input key={selectedEl.id+key} defaultValue={String(selectedEl.semantic?.data?.[key]??'')} placeholder="0..*, 1, etc." onBlur={ev=>changeElement(selectedEl.id,e=>e.type==='edge'?{...e,semantic:{role:e.semantic?.role??'relationship',data:{...e.semantic?.data,[key]:ev.target.value}}}:e)}/>))}
    </>}
   <div className="section-head">Appearance</div>
   <div className="style-presets">{[{name:'Ivory',stroke:'#e6e2d8',fill:'#1d2026'},{name:'Amber',stroke:'#e0a458',fill:'#2c261d'},{name:'Teal',stroke:'#79c5da',fill:'#193039'},{name:'Green',stroke:'#8ec6a3',fill:'#1d3028'},{name:'Alert',stroke:'#e58578',fill:'#352324'}].map(p=><Button key={p.name} title={p.name+' style'} onClick={()=>changeElement(selectedEl.id,e=>({...e,style:{...e.style,fill:p.fill,stroke:p.stroke}}))}><i style={{background:p.fill,borderColor:p.stroke}}/>{p.name}</Button>)}</div>
   <div className="color-grid">{(['fill','stroke','textColor'] as const).map(k=>field(k,<input type="color" value={(selectedEl.style?.[k]??(k==='fill'?palette.panel:k==='stroke'?palette.ink:palette.ink)).match(/^#[0-9a-fA-F]{6}$/)?selectedEl.style?.[k]??palette.ink:palette.ink} onChange={ev=>changeElement(selectedEl.id,e=>k==='textColor'&&e.type==='node'?{...e,label:{text:e.label?.text??'',style:{...e.label?.style,textColor:ev.target.value}}}:{...e,style:{...e.style,[k]:ev.target.value}})}/>))}</div>
   {field('Stroke width',<Numeric value={selectedEl.style?.strokeWidth??2} min={0} max={30} change={v=>changeElement(selectedEl.id,e=>({...e,style:{...e.style,strokeWidth:v}}))}/>)}
   {field('Font size',<Numeric value={selectedEl.type==='node'?(selectedEl.label?.style?.fontSize??selectedEl.style?.fontSize??18):(selectedEl.style?.fontSize??20)} min={6} max={100} change={v=>changeElement(selectedEl.id,e=>e.type==='node'?{...e,label:{text:e.label?.text??'',style:{...e.label?.style,fontSize:v}}}:{...e,style:{...e.style,fontSize:v}})}/>)}
   {field('Layer',<select value={selectedEl.layerId} onChange={ev=>moveToLayer(selectedEl.id,ev.target.value)}>{doc.layers.map(l=><option key={l.id} value={l.id}>{l.name}</option>)}</select>)}
   {selectedEl.type==='group'&&<Button onClick={ungroup}>Ungroup</Button>}
   </>:<div className="empty-pane"><strong>No shape selected</strong><p>Select a shape to edit its contents, size, color and layer. Double-click to open this panel.</p><Button onClick={()=>setPanel('layers')}>Manage layers →</Button><div className="section-head">Artboard / export bounds</div><div className="number-grid">{field('Width',<Numeric value={doc.page.width} min={200} max={200000} change={v=>commit(d=>({...d,page:{...d.page,width:v}}))}/>)}{field('Height',<Numeric value={doc.page.height} min={200} max={200000} change={v=>commit(d=>({...d,page:{...d.page,height:v}}))}/>)}</div>{field('Background',<input type="color" value={doc.page.background} onChange={e=>commit(d=>({...d,page:{...d.page,background:e.target.value}}))}/>)}</div>}
 </div>;
 const layersPane=<div className="inspector-body"><div className="inspector-header"><b>Layers</b><Button onClick={()=>commit(d=>({...d,layers:[...d.layers,{id:uuid('lyr_'),name:'Layer '+(d.layers.length+1),visible:true,locked:false}]}))}>+ Add</Button></div>{doc.layers.map((l,i)=><div key={l.id} className="layer-row">
  <Button active={activeLayer===l.id} onClick={()=>setActiveLayer(l.id)}>{i+1}</Button>
  <input aria-label="Layer name" value={l.name} onChange={ev=>commit(d=>({...d,layers:d.layers.map(x=>x.id===l.id?{...x,name:ev.target.value}:x)}))}/>
  <Button title="Toggle visibility" onClick={()=>commit(d=>({...d,layers:d.layers.map(x=>x.id===l.id?{...x,visible:!x.visible}:x)}))}>{l.visible?'◉':'◌'}</Button>
  <Button title="Lock layer" onClick={()=>commit(d=>({...d,layers:d.layers.map(x=>x.id===l.id?{...x,locked:!x.locked}:x)}))}>{l.locked?'🔒':'♧'}</Button>
  <Button title="Raise layer" disabled={i===doc.layers.length-1} onClick={()=>commit(d=>{const j=d.layers.findIndex(x=>x.id===l.id);[d.layers[j],d.layers[j+1]]=[d.layers[j+1],d.layers[j]];return d;})}>↑</Button>
 </div>)}</div>;
 const outlinePane=<div className="inspector-body">{doc.layers.map(l=><section key={l.id}><h4>{l.name}</h4>{doc.elements.filter(e=>e.layerId===l.id).map(e=><button className={`outline-item ${selected.includes(e.id)?'active':''}`} style={{paddingLeft:12+(e.parentId?16:0)}} key={e.id} onClick={()=>{select([e.id]);setPanel('inspector');if(isMobile())setMobileSheet(true);}}>{e.type==='edge'?'↗':e.type==='group'?'▧':e.type==='text'?'T':e.type==='path'?'〰':'□'} {keyRow(e.id)}</button>)}</section>)}</div>;
 const shapesPane=<div className="inspector-body"><div className="inspector-header"><b>Stencil library</b><Button onClick={()=>setTemplateGallery(true)}>Templates →</Button></div>
  <p className="catalog-intro">Pick any stencil, then tap the canvas to place it. Kits freely mix in one document.</p>
  {field('Find shape',<input type="search" placeholder="Search symbols or families" value={shapeSearch} onChange={e=>setShapeSearch(e.target.value)}/>)}
  {field('Library',<select value={packFilter} onChange={e=>setPackFilter(e.target.value)}><option value="all">All packs · {allStencils(packs).length} shapes</option>{packs.map(p=><option key={p.id} value={p.id}>{p.name}</option>)}</select>)}
  <div className="shape-catalog">{matches.map(s=><Button key={s.packId+':'+s.name} active={tool==='stencil'&&stencilChoice?.packId===s.packId&&stencilChoice?.name===s.name} onClick={()=>{setStencilChoice({packId:s.packId,name:s.name});setTool('stencil');setMobileSheet(false);}}><b>{s.icon}</b><span>{s.title}</span><small>{s.packName}</small></Button>)}</div>
  {!matches.length&&<p>No matching shapes.</p>}
  <div className="section-head">Connector library</div>
  {field('New connectors use',<select value={edgeKind} onChange={e=>{setEdgeKind(e.target.value);setTool('connect');setMobileSheet(false);}}>{edgeStyleKinds.map(k=><option key={k.id} value={k.id}>{k.label}</option>)}</select>)}
  <p className="catalog-intro">After choosing a connector, tap its source and destination ports.</p>
  <div className="section-head">Extend the library</div><Button onClick={()=>document.getElementById('omd-pack-import')?.click()}>⇧ Import .omdpack.json</Button>
  <a className="sample-pack-link" href="./sample.quest-pack.omdpack.json" download="sample.quest-pack.omdpack.json">↓ Example: quest & dialogue pack</a>
  <p className="catalog-intro">Imported JSON packs add stencils and templates without changing the editor. Their definitions are embedded into diagrams that use them.</p>
  </div>;
 const pane=panel==='layers'?layersPane:panel==='outline'?outlinePane:panel==='shapes'?shapesPane:shapeInspector;
 return <div className="app">
 <input id="omd-import" style={{display:'none'}} type="file" accept=".json,.omd.json,application/json" onChange={e=>{const file=e.target.files?.[0];if(file)importFile(file);e.target.value='';}}/>
 <input id="omd-pack-import" style={{display:'none'}} type="file" accept=".json,.omdpack.json,application/json" onChange={e=>{const file=e.target.files?.[0];if(file)importPack(file);e.target.value='';}}/>
 <header className="topbar">
 <Button className="brand" onClick={()=>setLibrary(v=>!v)}>◈ <span>OmniDesign</span></Button>
 <span className="divider"/>
 <input aria-label="Diagram title" className="title-input" value={doc.title} onChange={e=>commit(d=>({...d,title:e.target.value||'Untitled diagram'}))}/>
 <span className="desktop-only small-status">{message}</span>
 <div className="top-actions">
 <Button className="mobile-only mobile-outline-header" title="Canvas / Outline" onClick={()=>{setPanel('outline');setMobileSheet(true);}}>☷</Button>
 <Button className="mobile-primary-library" title="Open local diagram library" onClick={()=>setLibrary(v=>!v)}>☷ <span className="desktop-only">Library</span></Button>
 <Button title="Browse editable diagram templates" onClick={()=>{setTemplateGallery(true);setLibrary(false);}}>▦ <span className="desktop-only">Templates</span></Button>
  <Button title="Save this editable diagram to PC OmniHub with revision checks" onClick={saveToHub}>↑ <span className="desktop-only">Hub save</span></Button>
  <Button title="Open the PC OmniHub diagram library" onClick={openHubLibrary}>☁ <span className="desktop-only">Hub library</span></Button>
  <Button title="Copy private Hub link to this diagram" onClick={copyHubLink}>↗ <span className="desktop-only">Link</span></Button>
 <Button title="Create a blank diagram" onClick={addDocument}>＋ <span className="desktop-only">Blank</span></Button>
 <Button title="Import editable .omd.json" onClick={()=>filePicker().click()}>⇧ <span className="desktop-only">Open</span></Button>
 <div className="export-menu"><Button onClick={exportNative}>Save ▾</Button><div className="export-dropdown"><button onClick={exportNative}>Native .omd.json</button><button onClick={exportSvg}>Export SVG</button><button onClick={exportPng}>Export PNG</button></div></div>
 <Button className="mobile-only mobile-menu-toggle" title="More diagram actions" onClick={()=>setMobileActions(true)}>⋯</Button>
 </div></header>
 <div className="sync-strip" role="status" aria-live="polite">
  <span className={'sync-light '+(hubOnline?'online':'offline')}/>
  <strong>{hubOnline?'Hub connected':hubOnline===false?'Hub unreachable · offline':'Checking Hub'}</strong>
  <span className="sync-description">{documentState(doc)} · {pendingCount} pending · editable on device</span>
  {conflicts[doc.id]&&<Button className="conflict-action" onClick={keepBoth}>Conflict · Keep both</Button>}
  <Button className="sync-action" title="Sync revision-checked local documents to PC (offline copies are always retained)" disabled={syncBusy||!ready} onClick={syncPending}>{syncBusy?'Syncing…':'Sync now'}</Button>
 </div>
 <div className="workspace">
 <aside className="leftpane">{toolsPanel}<div className="tool-bottom"><Button onClick={()=>{setPanel('layers');setMobileSheet(true);}} title="Layers">▤</Button><Button onClick={()=>{setPanel('outline');setMobileSheet(true);}} title="Outline">☷</Button></div></aside>
 <main className="mainarea">
 <div className="canvas-actions">
 <div className="action-left">
 <Button title="Undo (Ctrl+Z)" disabled={!history.current.past.length} onClick={undo}>↶</Button><Button title="Redo (Ctrl+Y)" disabled={!history.current.future.length} onClick={redo}>↷</Button>
 <span className="divider"/><Button active={doc.page.grid.enabled} title="Grid" onClick={()=>commit(d=>({...d,page:{...d.page,grid:{...d.page.grid,enabled:!d.page.grid.enabled}}}))}>Grid</Button>
 <Button active={doc.page.grid.snap} title="Snap to grid" onClick={()=>commit(d=>({...d,page:{...d.page,grid:{...d.page.grid,snap:!d.page.grid.snap}}}))}>Snap</Button>
 <Button title="Group selection (Ctrl+G)" disabled={selected.length<2} onClick={group}>Group</Button>
 {selectedEl?.type==='group'&&<Button onClick={ungroup}>Ungroup</Button>}
 </div><div className="action-right">
 <Button title="Zoom out" onClick={()=>canvas.current?.zoom(.8)}>−</Button><Button title="Fit page" onClick={()=>canvas.current?.fit()}>Fit</Button><Button title="Zoom in" onClick={()=>canvas.current?.zoom(1.25)}>＋</Button>
 </div></div>
 <Canvas ref={canvas} document={doc} tool={tool} selected={selected} select={select} preview={preview} finish={finish} create={create} draw={draw} connect={connect} edit={edit}/>
 <div className="mobile-context">{selected.length>0&&<Button onClick={()=>{setPanel('inspector');setMobileSheet(true);}}>Edit {selected.length} selected</Button>}</div>
 </main>
 <aside className={`rightpane ${mobileSheet?'mobile-open':''}`}><div className="panel-tabs"><Button active={panel==='inspector'} onClick={()=>setPanel('inspector')}>Inspector</Button><Button active={panel==='shapes'} onClick={()=>setPanel('shapes')}>Kits</Button><Button active={panel==='layers'} onClick={()=>setPanel('layers')}>Layers</Button><Button active={panel==='outline'} onClick={()=>setPanel('outline')}>Outline</Button><Button className="close-sheet" onClick={()=>setMobileSheet(false)}>×</Button></div>{pane}</aside>
 </div>
 <nav className="mobile-nav"><Button active={tool==='select'} onClick={()=>{setTool('select');setMobileSheet(false);}}>↖<small>Select</small></Button><Button active={tool==='connect'} onClick={()=>{setTool('connect');setMobileSheet(false);}}>↗<small>Connect</small></Button><Button onClick={()=>{setPanel('shapes');setMobileSheet(true);}}>＋<small>Add</small></Button><Button disabled={!history.current.past.length} onClick={undo}>↶<small>Undo</small></Button><Button disabled={!history.current.future.length} onClick={redo}>↷<small>Redo</small></Button></nav>
 {mobileActions&&<div className="scrim mobile-actions-scrim" onPointerDown={e=>{if(e.target===e.currentTarget)setMobileActions(false);}}><div className="mobile-actions-dialog" role="dialog" aria-label="More diagram actions"><header><h2>Diagram actions</h2><Button onClick={()=>setMobileActions(false)}>×</Button></header>
  <div className="mobile-actions-grid">
   <Button onClick={()=>{setMobileActions(false);setLibrary(true);}}>Library</Button>
   <Button onClick={()=>{setMobileActions(false);setTemplateGallery(true);}}>Templates</Button>
   <Button onClick={()=>{setMobileActions(false);addDocument();}}>New blank</Button>
   <Button onClick={()=>{setMobileActions(false);filePicker().click();}}>Import native</Button>
   <Button onClick={()=>{setMobileActions(false);setPanel('outline');setMobileSheet(true);}}>Canvas / Outline</Button>
   <Button onClick={()=>{setMobileActions(false);setPanel('inspector');setMobileSheet(true);}}>Inspector</Button>
   <Button onClick={()=>{setMobileActions(false);void saveToHub();}}>Save to Hub</Button>
   <Button onClick={()=>{setMobileActions(false);void openHubLibrary();}}>Hub library</Button>
   <Button onClick={()=>{setMobileActions(false);void copyHubLink();}}>Copy link</Button>
   <Button onClick={()=>{setMobileActions(false);exportNative();}}>Export editable</Button>
   <Button onClick={()=>{setMobileActions(false);exportSvg();}}>Export SVG</Button>
   <Button onClick={()=>{setMobileActions(false);void exportPng();}}>Export PNG</Button>
  </div></div></div>}
 {library&&<div className="scrim" onPointerDown={e=>{if(e.target===e.currentTarget)setLibrary(false);}}><div className="library"><header><div><h2>Diagram library</h2><p>Editable documents stored on this device</p></div><Button onClick={()=>setLibrary(false)}>×</Button></header>
 <div className="library-cta"><Button onClick={addDocument}>＋ Blank diagram</Button><Button onClick={()=>{setLibrary(false);setTemplateGallery(true);}}>▦ Browse templates</Button><Button onClick={()=>filePicker().click()}>⇧ Import native</Button></div>
 <div className="library-grid">{saved.map(d=><button key={d.id} className={`doc-card ${d.id===doc.id?'current':''}`} onClick={()=>openDoc(d)}><div className="doc-preview"><svg viewBox={`0 0 ${d.page.width} ${d.page.height}`} preserveAspectRatio="xMidYMid meet" role="img" aria-label={d.title+' preview'}><GraphicScene document={d}/></svg></div><strong>{d.title}</strong><small>{d.family} · {new Date(d.metadata.updatedAt).toLocaleString()}</small><small className={'document-sync '+(needsSync(d,synced[d.id])?'waiting':'synced')}>{documentState(d)}</small></button>)}</div>
 <div className="library-bottom"><Button onClick={removeCurrent}>Delete current local diagram</Button></div></div></div>}
 {hubOpen&&<div className="scrim" onPointerDown={e=>{if(e.target===e.currentTarget)setHubOpen(false);}}><div className="library"><header><div><h2>OmniHub diagrams</h2><p>PC-persisted native diagrams · revisions shared with agents</p></div><Button onClick={()=>setHubOpen(false)}>×</Button></header><div className="library-cta"><Button onClick={openHubLibrary}>Refresh Hub list</Button></div><div className="library-grid">{hubDocs.map(d=><button key={d.id} className="doc-card" onClick={()=>openFromHub(d.id)}><strong>{d.title}</strong><small>{d.family} · Hub revision {d.revision}</small></button>)}</div>{hubDocs.length===0&&<p>No Hub diagrams yet. Use Hub save on a local diagram.</p>}</div></div>}
 {templateGallery&&<div className="scrim" onPointerDown={e=>{if(e.target===e.currentTarget)setTemplateGallery(false);}}><div className="library template-library"><header><div><h2>New from template</h2><p>Editable shapes, relationships and properties · one freeform canvas</p></div><Button onClick={()=>setTemplateGallery(false)}>×</Button></header>
   <div className="library-cta"><Button onClick={addDocument}>＋ Blank diagram</Button><Button onClick={()=>document.getElementById('omd-pack-import')?.click()}>⇧ Import stencil pack</Button><a className="sample-pack-link" href="./sample.quest-pack.omdpack.json" download>↓ Sample pack</a></div>
   <div className="template-grid">{allTemplates(packs).map(t=>{const pack=packs.find(p=>p.id===t.packId)!;return <button key={t.packId+':'+t.id} className="template-card" onClick={()=>fromTemplate(pack,t)}>
     <div className="template-preview"><svg viewBox="0 0 1200 700" preserveAspectRatio="xMidYMid meet" aria-label={t.title+' template'}><GraphicScene document={instantiateTemplate(pack,t,packs)}/></svg></div>
     <div className="template-info"><small>{t.packName}</small><strong>{t.title}</strong><p>{t.description}</p></div></button>;})}</div>
   </div></div>}
 {!ready&&<div className="loading">Opening local diagrams…</div>}
 </div>;
}
export default App;
