/** A diagram link addresses an ID, never a screenshot or a machine-specific file.
 * The private owner-login Gateway opens the latest synchronized revision. */
export const isDiagramId=(id:string)=>/^dgm_[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(id);
const PRIVATE_GATEWAY='https://hub.rancorous.online/omnidesign/';

export function diagramLink(id:string,here:Location=window.location):string{
 if(!isDiagramId(id))throw Error('Invalid diagram ID');
 const path=here.pathname;
 const remote=here.protocol==='https:'&&
  (path.includes('/gateway/tools/omnidesign/')||path.includes('/omnidesign/'));
 const base=remote?new URL(path,here.origin).href:PRIVATE_GATEWAY;
 const url=new URL(base);
 url.searchParams.set('diagram',id);
 return url.href;
}

/** Clipboard API is unavailable in some embedded/browser configurations. */
export async function copyDiagramLink(id:string):Promise<string>{
 if(window.OmniDesignNative?.copyLink)return window.OmniDesignNative.copyLink(id);
 const value=diagramLink(id);
 if(navigator.clipboard?.writeText)await navigator.clipboard.writeText(value);
 else{
  const field=document.createElement('textarea');field.value=value;
  field.style.position='fixed';field.style.opacity='0';document.body.appendChild(field);
  field.focus();field.select();
  const copied=document.execCommand('copy');field.remove();
  if(!copied)throw Error('Browser denied clipboard access');
 }
 return value;
}
