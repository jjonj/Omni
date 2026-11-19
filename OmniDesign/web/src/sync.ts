import type {Diagram} from './types';
import {clone,validateDocument} from './model';
import {hubJson,HubHttpError} from './androidBridge';
import {acknowledge,adoptRemote,clearConflict,confirmEquivalent,enqueue,finishKeepBoth,getConflicts,getDocuments,getOutbox,getSynced,needsSync,noteConflict,putDocument,sameDocument,type PendingOperation,type SyncedBaseline,type SyncConflict} from './storage';

export type SyncResult={uploaded:number;downloaded:number;conflicted:number;failed:number;errors:string[]};
export type SyncCallbacks={
 // Called only on durable local storage changes. UI must not override newer edits.
 onAccepted?:(doc:Diagram)=>void;
 onRemote?:(doc:Diagram,previous?:Diagram)=>void;
 onConflict?:(conflict:SyncConflict)=>void;
};
type RemoteSummary={id:string;revision:number;archived?:boolean};

async function remoteLibrary():Promise<Map<string,RemoteSummary>>{
 const result=new Map<string,RemoteSummary>();
 let cursor:string|null=null;
 const seen=new Set<string>();
 do{
  const page=await hubJson('diagrams?limit=100&includeArchived=true'+(cursor?'&cursor='+encodeURIComponent(cursor):''));
  if(!Array.isArray(page.items))throw Error('Hub sent an invalid diagram listing');
  for(const item of page.items as RemoteSummary[])if(typeof item.id==='string')result.set(item.id,item);
  const next=typeof page.nextCursor==='string'?page.nextCursor:null;
  if(next&&seen.has(next))throw Error('Hub diagram pagination repeated a cursor');
  if(next)seen.add(next);
  cursor=next;
 }while(cursor);
 return result;
}
const loadRemote=async(id:string)=>validateDocument(await hubJson('diagrams/'+encodeURIComponent(id)));
const equivalent=(a:Diagram,b:Diagram)=>sameDocument({...a,revision:b.revision},b);

/** Replay the exact durable payload and conditional revision. It is important
 * not to regenerate a PUT key or POST body after a timed-out response. */
async function upload(op:PendingOperation):Promise<Diagram>{
 const payload=JSON.parse(op.snapshot) as Diagram;
 try{
  return validateDocument(await hubJson('diagrams'+(op.method==='POST'?'':'/'+encodeURIComponent(op.id)),{
   method:op.method,headers:{
    'Content-Type':'application/json',
    'Idempotency-Key':op.key,
    ...(op.method==='PUT'?{'If-Match':'"rev-'+op.baseRevision+'"'}:{})
   },body:op.snapshot
  }));
 }catch(error){
  if(op.method==='POST'&&error instanceof HubHttpError&&error.status===409){
   // Create succeeded but response was lost; an agent may already have edited
   // that remote ID, so compare its immutable first revision, not its latest.
   const remote=await loadRemote(op.id);
   const first=remote.revision===1?remote:validateDocument(await hubJson('diagrams/'+encodeURIComponent(op.id)+'/revisions/1'));
   if(equivalent(payload,first))return first;
  }
  throw error;
 }
}

export async function synchronize(callbacks:SyncCallbacks={}):Promise<SyncResult>{
 const result:SyncResult={uploaded:0,downloaded:0,conflicted:0,failed:0,errors:[]};
 // A successful listing is required before uploading: it detects remote/agent
 // changes while the phone was offline and avoids needless stale PUTs.
 const library=await remoteLibrary();
 const local=await getDocuments(),baselines=await getSynced(),outbox=await getOutbox(),conflicts=await getConflicts();
 const reported=new Set<string>();
 const conflict=async(remote:Diagram)=>{
  const item=await noteConflict(remote);callbacks.onConflict?.(item);
  if(!reported.has(remote.id)){result.conflicted++;reported.add(remote.id);}
 };
 const adopt=async(remote:Diagram,previous?:Diagram)=>{
  const adopted=await adoptRemote(remote);
  if(adopted==='adopted'){
   result.downloaded++;callbacks.onRemote?.(remote,previous);
   return;
  }
  await conflict(remote);
 };
 for(const previous of local){
  const remoteMeta=library.get(previous.id);
  const baseline=baselines[previous.id];
  const operation=outbox[previous.id];
  try{
   // A prior explicit conflict is a durable hold, never an implicit "retry overwrite".
   if(conflicts[previous.id]){
    if(remoteMeta&&remoteMeta.revision>conflicts[previous.id].remote.revision)
     await conflict(await loadRemote(previous.id));
    else{if(!reported.has(previous.id)){result.conflicted++;reported.add(previous.id);}}
    continue;
   }

   let current=previous,currentBaseline=baseline,acceptedThisPass=false;
   if(operation){
    try{
     const accepted=await upload(operation);
     current=await acknowledge(operation,accepted);
     currentBaseline={id:accepted.id,revision:accepted.revision,snapshot:JSON.stringify(accepted)};
     callbacks.onAccepted?.(current);
     acceptedThisPass=true;
     result.uploaded++;
    }catch(error){
     if(error instanceof HubHttpError&&[409,412].includes(error.status)){
      await conflict(await loadRemote(previous.id));
     }else{result.failed++;result.errors.push(previous.title+': '+String(error));}
     continue;
    }
   }

   // A remote revision ahead of our last confirmed snapshot is either a
   // clean download or a two-writer conflict. Neither branch overwrites edits.
   if(remoteMeta){
    if(!currentBaseline){
     const remote=await loadRemote(previous.id);
     if(await confirmEquivalent(remote)){result.downloaded++;callbacks.onRemote?.(remote,previous);}
     else await conflict(remote);
     continue;
    }
    // The list was fetched before the replay. A just-accepted PUT may have
    // advanced the Hub beyond that list entry, which is not a conflict.
    if(remoteMeta.revision!==currentBaseline.revision&&
       (!acceptedThisPass||remoteMeta.revision>currentBaseline.revision)){
     const remote=await loadRemote(previous.id);
     if(remote.revision<currentBaseline.revision){await conflict(remote);continue;}
     if(needsSync(current,currentBaseline))await conflict(remote);
     else await adopt(remote,current);
     continue;
    }
   }else if(currentBaseline){
    // An archived document remains in the includeArchived listing. A missing
    // ID means a different Hub/database; never recreate it as a new diagram.
    result.failed++;result.errors.push(previous.title+': Hub no longer has the confirmed document ID');
    continue;
   }

   if(!needsSync(current,currentBaseline))continue;
   const queued=await enqueue(current,currentBaseline?.revision??0);
   try{
    const accepted=await upload(queued);
    const updated=await acknowledge(queued,accepted);
    callbacks.onAccepted?.(updated);
    result.uploaded++;
   }catch(error){
    if(error instanceof HubHttpError&&[409,412].includes(error.status)){
     await conflict(await loadRemote(previous.id));
    }else{result.failed++;result.errors.push(previous.title+': '+String(error));}
   }
  }catch(error){
   result.failed++;result.errors.push(previous.title+': '+String(error));
  }
 }
 // Full pagination matters: a diagram changed by an agent while Android was
 // offline should appear locally even when it is beyond the first 100 rows.
 const present=new Set(local.map(doc=>doc.id));
 for(const remote of library.values()){
  if(remote.archived||present.has(remote.id))continue;
  try{await adopt(await loadRemote(remote.id));}
  catch(error){result.failed++;result.errors.push(remote.id+': '+String(error));}
 }
 return result;
}

/** Default explicit conflict choice. The user keeps the entire local graph in
 * a stable fork and the remote graph at its original ID; links still point at
 * the original and the fork's element/port IDs are preserved. */
export async function keepBoth(local:Diagram,callbacks:SyncCallbacks={}):Promise<{fork:Diagram;restored:boolean}>{
 const existing=(await getConflicts())[local.id];
 if(!existing)throw Error('This diagram has no recorded conflict');
 const fork:Diagram={...clone(local),id:existing.forkId,revision:0,title:local.title+' · offline copy',
  metadata:{...local.metadata,forkedFrom:{id:local.id,revision:local.revision}}};
 // First durable write: interrupted resolution never loses the local graph.
 await putDocument(fork);
 let restored=false;
 try{
  const remote=await loadRemote(local.id);
  restored=await finishKeepBoth(local.id,fork,remote);
  if(restored){await clearConflict(local.id);callbacks.onRemote?.(remote,local);}
 }catch{/* A disconnected Hub cannot finish resolution; conflict remains visible. */}
 return {fork,restored};
}
