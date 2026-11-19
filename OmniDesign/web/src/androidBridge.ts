/**
 * The Android APK serves this exact editor from WebViewAssetLoader. Browser fetch()
 * cannot reach the PC API from that origin, so only REST transport crosses the
 * native bridge. Diagrams and the offline queue stay in the editor's IndexedDB.
 */
declare global {
 interface Window {
  OmniDesignNative?: {request:(id:string,method:string,route:string,body:string,revision:string,key:string)=>void;flushed:()=>void;copyLink?:(id:string)=>string};
  omniDesignBeforeExit?:()=>void;
  omniDesignNativeReply?: (id:string,payload:string)=>void;
 }
}
type NativeReply={ok:boolean;status:number;body:string;error?:string;etag?:string};
type Resolve=(value:NativeReply)=>void;
const pending=new Map<string,{resolve:Resolve;reject:(error:Error)=>void;timer:number}>();
window.omniDesignNativeReply=(id,payload)=>{
 const request=pending.get(id);if(!request)return;
 clearTimeout(request.timer);pending.delete(id);
 try{request.resolve(JSON.parse(payload) as NativeReply);}catch(error){request.reject(Error(String(error)));}
};
export const inAndroid=Boolean(window.OmniDesignNative);

export class HubHttpError extends Error {
 constructor(message:string,public status:number){super(message);}
}

export async function hubJson(path:string,options:RequestInit={}):Promise<any>{
 if(!window.OmniDesignNative){
  const res=await fetch(new URL('./api/v1/'+path,location.href),options);
  const data=await res.json().catch(()=>null);
  if(!res.ok)throw new HubHttpError(String(data?.detail?.error??data?.detail??data?.error??res.statusText)+' (HTTP '+res.status+')',res.status);
  return data;
 }
 const id=crypto.randomUUID(),method=(options.method||'GET').toUpperCase();
 const headers=new Headers(options.headers);
 const reply=await new Promise<NativeReply>((resolve,reject)=>{
  const timer=window.setTimeout(()=>{pending.delete(id);reject(new HubHttpError('Hub request timed out; local copy retained',0));},22000);
  pending.set(id,{resolve,reject,timer});
  try{
   window.OmniDesignNative!.request(id,method,'api/v1/'+path,
    String(options.body??''),headers.get('If-Match')??'',headers.get('Idempotency-Key')??'');
  }catch(error){clearTimeout(timer);pending.delete(id);reject(error);}
 });
 let data:any=null;try{if(reply.body)data=JSON.parse(reply.body);}catch{throw new HubHttpError('Hub returned a non-JSON response (login may be required)',reply.status);}
 if(!reply.ok)throw new HubHttpError(String(reply.error??data?.detail?.error??data?.detail??data?.error??'Hub unavailable')+' (HTTP '+reply.status+')',reply.status);
 return data;
}

export async function hubHealth():Promise<boolean>{
 try{
  if(window.OmniDesignNative){
   const id=crypto.randomUUID();
   const reply=await new Promise<NativeReply>((resolve,reject)=>{
    const timer=window.setTimeout(()=>{pending.delete(id);reject(Error('Offline'));},12000);
    pending.set(id,{resolve,reject,timer});
    window.OmniDesignNative!.request(id,'GET','api/health','','','');
   });
   return reply.ok&&JSON.parse(reply.body)?.service==='omnidesign';
  }
  const response=await fetch(new URL('./api/health',location.href),{cache:'no-store'});
  return response.ok&&(await response.json()).service==='omnidesign';
 }catch{return false;}
}
