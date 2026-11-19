import type {Diagram} from './types';
import {validateDocument} from './model';
const DB='OmniDesign',VERSION=1,STORE='diagrams';
let opening:Promise<IDBDatabase>|undefined;
function database():Promise<IDBDatabase>{
 return opening??=new Promise((resolve,reject)=>{
 const request=indexedDB.open(DB,VERSION);
 request.onupgradeneeded=()=>{if(!request.result.objectStoreNames.contains(STORE))request.result.createObjectStore(STORE,{keyPath:'id'});};
 request.onsuccess=()=>resolve(request.result);request.onerror=()=>reject(request.error);
 });
}
async function transact<T>(mode:IDBTransactionMode,run:(store:IDBObjectStore,resolve:(v:T)=>void,reject:(e:unknown)=>void)=>void):Promise<T>{
 const db=await database();return new Promise((resolve,reject)=>{const tx=db.transaction(STORE,mode);let result:T,failed:unknown;run(tx.objectStore(STORE),v=>{result=v;},e=>{failed=e;tx.abort();});tx.oncomplete=()=>resolve(result);tx.onerror=()=>reject(failed??tx.error);tx.onabort=()=>reject(failed??tx.error);});
}
export async function putDocument(doc:Diagram):Promise<void>{validateDocument(doc);await transact<void>('readwrite',(store,resolve,reject)=>{const r=store.put(doc);r.onsuccess=()=>resolve();r.onerror=()=>reject(r.error);});}
export async function getDocuments():Promise<Diagram[]>{return transact<Diagram[]>('readonly',(store,resolve,reject)=>{const r=store.getAll();r.onsuccess=()=>resolve(r.result as Diagram[]);r.onerror=()=>reject(r.error);});}
export async function removeDocument(id:string):Promise<void>{await transact<void>('readwrite',(store,resolve,reject)=>{const r=store.delete(id);r.onsuccess=()=>resolve();r.onerror=()=>reject(r.error);});}
export function download(data:Blob,filename:string){const url=URL.createObjectURL(data),a=document.createElement('a');a.href=url;a.download=filename;a.click();setTimeout(()=>URL.revokeObjectURL(url),10000);}
export const filename=(title:string,ext:string)=>(title.replace(/[\\/:*?"<>|]/g,'_').slice(0,80)||'Untitled')+ext;
