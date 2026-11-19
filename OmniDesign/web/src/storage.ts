import type {Diagram} from './types';
import {validateDocument} from './model';

const DB='OmniDesign',VERSION=3,STORE='diagrams',SYNC='sync',OUTBOX='outbox',CONFLICTS='conflicts';
export type SyncedBaseline={id:string;revision:number;snapshot:string};
export type PendingOperation={id:string;key:string;baseRevision:number;method:'POST'|'PUT';snapshot:string;createdAt:string};
export type SyncConflict={id:string;remote:Diagram;forkId:string;detectedAt:string};
let opening:Promise<IDBDatabase>|undefined;

function database():Promise<IDBDatabase>{
 return opening??=new Promise((resolve,reject)=>{
  const request=indexedDB.open(DB,VERSION);
  request.onupgradeneeded=()=>{
   for(const name of [STORE,SYNC,OUTBOX,CONFLICTS])
    if(!request.result.objectStoreNames.contains(name))request.result.createObjectStore(name,{keyPath:'id'});
  };
  request.onsuccess=()=>resolve(request.result);
  request.onerror=()=>{opening=undefined;reject(request.error);};
  request.onblocked=()=>{opening=undefined;reject(Error('OmniDesign storage upgrade blocked by another open tab'));};
 });
}

async function transact<T>(mode:IDBTransactionMode,stores:string[],run:(tx:IDBTransaction,finish:(v:T)=>void,fail:(e:unknown)=>void)=>void):Promise<T>{
 const db=await database();return new Promise((resolve,reject)=>{
  const tx=db.transaction(stores,mode);let result:T,failed:unknown;
  const finish=(value:T)=>{result=value;};
  const fail=(error:unknown)=>{failed=error;tx.abort();};
  try{run(tx,finish,fail);}catch(e){fail(e);}
  tx.oncomplete=()=>resolve(result);tx.onerror=()=>reject(failed??tx.error);tx.onabort=()=>reject(failed??tx.error);
 });
}

async function rows<T>(storeName:string):Promise<T[]>{
 return transact('readonly',[storeName],(tx,finish,fail)=>{
  const r=tx.objectStore(storeName).getAll();r.onsuccess=()=>finish(r.result as T[]);r.onerror=()=>fail(r.error);
 });
}
const byId=<T extends {id:string}>(items:T[]):Record<string,T>=>Object.fromEntries(items.map(item=>[item.id,item]));

// Revision metadata belongs to the Hub; local edits do not increment it.
// Never let a late autosave restore a stale pre-acknowledgement revision.
export async function putDocument(doc:Diagram):Promise<void>{
 validateDocument(doc);
 await transact<void>('readwrite',[STORE,SYNC],(tx,finish,fail)=>{
  const docs=tx.objectStore(STORE),r=tx.objectStore(SYNC).get(doc.id);
  r.onsuccess=()=>{
   const baseline=r.result as SyncedBaseline|undefined;
   const local=baseline&&doc.revision<baseline.revision?{...doc,revision:baseline.revision}:doc;
   const write=docs.put(local);write.onsuccess=()=>finish();write.onerror=()=>fail(write.error);
  };r.onerror=()=>fail(r.error);
 });
}
export async function getDocuments():Promise<Diagram[]>{return rows<Diagram>(STORE);}
export async function removeDocument(id:string):Promise<void>{
 await transact<void>('readwrite',[STORE,SYNC,OUTBOX,CONFLICTS],(tx,finish)=>{
  for(const name of [STORE,SYNC,OUTBOX,CONFLICTS])tx.objectStore(name).delete(id);
  finish();
 });
}
export async function setSynced(doc:Diagram):Promise<void>{
 const baseline:SyncedBaseline={id:doc.id,revision:doc.revision,snapshot:JSON.stringify(validateDocument(doc))};
 await transact<void>('readwrite',[SYNC],(tx,finish)=>{tx.objectStore(SYNC).put(baseline);finish();});
}
export async function getSynced():Promise<Record<string,SyncedBaseline>>{return byId(await rows<SyncedBaseline>(SYNC));}
export async function getOutbox():Promise<Record<string,PendingOperation>>{return byId(await rows<PendingOperation>(OUTBOX));}
export async function getConflicts():Promise<Record<string,SyncConflict>>{return byId(await rows<SyncConflict>(CONFLICTS));}
// Hub JSON is serialized with sorted keys; a JS object may have a different
// property order after round-tripping through Python/SQLite. Compare native
// content, not insertion order, or clean diagrams become phantom conflicts.
function canonical(value:unknown):string{
 return JSON.stringify(value,(_key,item)=>{
  if(item&&typeof item==='object'&&!Array.isArray(item))
   return Object.fromEntries(Object.keys(item).sort().map(key=>[key,item[key]]));
  return item;
 });
}
export function sameDocument(a:Diagram,b:Diagram):boolean{return canonical(a)===canonical(b);}
export function needsSync(doc:Diagram,baseline?:SyncedBaseline):boolean{
 return !baseline||canonical(JSON.parse(baseline.snapshot))!==canonical(doc);
}
export async function enqueue(doc:Diagram,baseRevision:number):Promise<PendingOperation>{
 const method=baseRevision===0?'POST':'PUT';
 const snapshot=JSON.stringify(validateDocument({...doc,revision:baseRevision}));
 const next:PendingOperation={id:doc.id,key:crypto.randomUUID(),baseRevision,method,snapshot,createdAt:new Date().toISOString()};
 return transact('readwrite',[OUTBOX],(tx,finish,fail)=>{
  const store=tx.objectStore(OUTBOX),get=store.get(doc.id);
  get.onsuccess=()=>{
   if(get.result){finish(get.result as PendingOperation);return;}
   const write=store.put(next);write.onsuccess=()=>finish(next);write.onerror=()=>fail(write.error);
  };get.onerror=()=>fail(get.error);
 });
}

/** One IDB commit: clear the durable request only after its Hub receipt,
 * advance the confirmed baseline, and retain edits made during the request. */
export async function acknowledge(op:PendingOperation,accepted:Diagram):Promise<Diagram>{
 validateDocument(accepted);
 if(accepted.id!==op.id||accepted.revision<=op.baseRevision)throw Error('Unexpected Hub receipt');
 return transact('readwrite',[STORE,SYNC,OUTBOX],(tx,finish,fail)=>{
  const docs=tx.objectStore(STORE),queue=tx.objectStore(OUTBOX),sync=tx.objectStore(SYNC);
  const r=queue.get(op.id);
  r.onsuccess=()=>{
   if(!r.result||r.result.key!==op.key){fail(Error('Sync operation replaced during upload'));return;}
   const localReq=docs.get(op.id);
   localReq.onsuccess=()=>{
    const local=localReq.result as Diagram|undefined;
    // A newer local edit keeps all its native element/link IDs and content.
    const same=local&&canonical({...local,revision:op.baseRevision})===canonical(JSON.parse(op.snapshot));
    const result=local&&!same?{...local,revision:accepted.revision}:accepted;
    sync.put({id:op.id,revision:accepted.revision,snapshot:JSON.stringify(accepted)} satisfies SyncedBaseline);
    if(local)docs.put(result);
    queue.delete(op.id);
    finish(result);
   };localReq.onerror=()=>fail(localReq.error);
  };r.onerror=()=>fail(r.error);
 });
}

/** Import only while local state is clean; a concurrent in-editor change wins.
 * Caller records a conflict if the document was dirty. */
export async function adoptRemote(remote:Diagram):Promise<'adopted'|'dirty'>{
 validateDocument(remote);
 return transact('readwrite',[STORE,SYNC,OUTBOX],(tx,finish,fail)=>{
  const docs=tx.objectStore(STORE),sync=tx.objectStore(SYNC),queue=tx.objectStore(OUTBOX);
  const get=docs.get(remote.id);
  get.onsuccess=()=>{
   const current=get.result as Diagram|undefined;
   const baseRequest=sync.get(remote.id);
   baseRequest.onsuccess=()=>{
    const baseline=baseRequest.result as SyncedBaseline|undefined;
    const out=queue.get(remote.id);
    out.onsuccess=()=>{
     if(out.result||(current&&needsSync(current,baseline))){finish('dirty');return;}
     docs.put(remote);
     sync.put({id:remote.id,revision:remote.revision,snapshot:JSON.stringify(remote)} satisfies SyncedBaseline);
     finish('adopted');
    };out.onerror=()=>fail(out.error);
   };baseRequest.onerror=()=>fail(baseRequest.error);
  };get.onerror=()=>fail(get.error);
 });
}
/** Recover an acknowledged create when its HTTP response was lost and the outbox
 * was unavailable (e.g. a pre-v3 offline installation). Never adopt different content. */
export async function confirmEquivalent(remote:Diagram):Promise<boolean>{
 validateDocument(remote);
 return transact('readwrite',[STORE,SYNC,OUTBOX],(tx,finish,fail)=>{
  const docs=tx.objectStore(STORE),sync=tx.objectStore(SYNC),outbox=tx.objectStore(OUTBOX);
  const r=docs.get(remote.id);
  r.onsuccess=()=>{
   const local=r.result as Diagram|undefined;
   if(!local||!sameDocument({...local,revision:remote.revision},remote)){finish(false);return;}
   const b=sync.get(remote.id);
   b.onsuccess=()=>{
    const q=outbox.get(remote.id);
    q.onsuccess=()=>{
     if(b.result||q.result){finish(false);return;}
     docs.put(remote);sync.put({id:remote.id,revision:remote.revision,snapshot:JSON.stringify(remote)} satisfies SyncedBaseline);
     finish(true);
    };q.onerror=()=>fail(q.error);
   };b.onerror=()=>fail(b.error);
  };r.onerror=()=>fail(r.error);
 });
}
export async function noteConflict(remote:Diagram):Promise<SyncConflict>{
 validateDocument(remote);
 return transact('readwrite',[CONFLICTS],(tx,finish,fail)=>{
  const store=tx.objectStore(CONFLICTS),r=store.get(remote.id);
  r.onsuccess=()=>{
   const existing=r.result as SyncConflict|undefined;
   const conflict:SyncConflict={id:remote.id,remote,forkId:existing?.forkId??'dgm_'+crypto.randomUUID(),detectedAt:existing?.detectedAt??new Date().toISOString()};
   store.put(conflict);finish(conflict);
  };r.onerror=()=>fail(r.error);
 });
}
export async function clearConflict(id:string):Promise<void>{
 await transact<void>('readwrite',[CONFLICTS],(tx,finish)=>{tx.objectStore(CONFLICTS).delete(id);finish();});
}
/** Local fork is durable before the Hub original can be restored. The fork ID
 * belongs to the conflict record, so retries never spawn duplicate copies. */
export async function finishKeepBoth(originalId:string,fork:Diagram,remote:Diagram):Promise<boolean>{
 validateDocument(fork);validateDocument(remote);
 return transact('readwrite',[STORE,SYNC,OUTBOX,CONFLICTS],(tx,finish,fail)=>{
  const docs=tx.objectStore(STORE),sync=tx.objectStore(SYNC),conflicts=tx.objectStore(CONFLICTS);
  const r=docs.get(originalId);
  r.onsuccess=()=>{
   const current=r.result as Diagram|undefined;
   const matches=current&&sameDocument({...current,id:fork.id,revision:0,metadata:fork.metadata,title:fork.title},fork);
   if(!matches){finish(false);return;}
   docs.put(remote);
   sync.put({id:remote.id,revision:remote.revision,snapshot:JSON.stringify(remote)} satisfies SyncedBaseline);
   tx.objectStore(OUTBOX).delete(originalId);
   conflicts.delete(originalId);
   finish(true);
  };r.onerror=()=>fail(r.error);
 });
}
export function download(data:Blob,filename:string){const url=URL.createObjectURL(data),a=document.createElement('a');a.href=url;a.download=filename;a.click();setTimeout(()=>URL.revokeObjectURL(url),10000);}
export const filename=(title:string,ext:string)=>(title.replace(/[\\/:*?"<>|]/g,'_').slice(0,80)||'Untitled')+ext;
