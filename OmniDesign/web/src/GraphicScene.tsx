import React from 'react';
import type {Diagram,Element,NodeElement,Point,Style,Tool} from './types';
import {absGeometry,anchorPoint,edgeD,endpointPoint,itemById,origin,palette,pathD} from './model';
type Props={document:Diagram;selection?:string[];tool?:Tool;interactive?:boolean;zoom?:number;onElementDown?:(e:React.PointerEvent<SVGGElement>,id:string)=>void;onResizeDown?:(e:React.PointerEvent<SVGGElement>,id:string)=>void;onPortDown?:(e:React.PointerEvent<SVGGElement>,nodeId:string,portId:string)=>void;connectFrom?:{nodeId:string;portId:string}|null;};
const color=(s:Style|undefined,k:keyof Style,fallback:string)=>typeof s?.[k]==='string'?s[k] as string:fallback;
const lines=(text:string,width:number,size:number):string[]=>{
 const result:string[]=[];const max=Math.max(5,Math.floor(width/(size*.56)));
 for(const line of text.split('\n')){let row='';for(const word of line.split(' ')){if((row+' '+word).length>max&&row){result.push(row);row=word;}else row+=(row?' ':'')+word;}result.push(row);}return result.slice(0,18);
};
export function GraphicScene({document:d,selection=[],tool='select',interactive=false,zoom=1,onElementDown,onResizeDown,onPortDown,connectFrom}:Props) {
 const set=new Set(selection),layerMap=new Map(d.layers.map(l=>[l.id,l]));
 const visible=d.elements.filter(e=>!e.hidden&&layerMap.get(e.layerId)?.visible);
 const ordered=visible.sort((a,b)=>d.layers.findIndex(l=>l.id===a.layerId)-d.layers.findIndex(l=>l.id===b.layerId)||a.order-b.order);
 const groups=ordered.filter(e=>e.type==='group'),edges=ordered.filter(e=>e.type==='edge'),shapes=ordered.filter(e=>e.type!=='edge'&&e.type!=='group');
 const selectStroke='#79c5da';
 const handle=(e:React.PointerEvent<SVGGElement>,id:string)=>{if(interactive)onElementDown?.(e,id);};
 const shape=(e:Element)=>{
 if(e.type==='edge'){
  const dpath=edgeD(d,e),label=e.label?.text||'',a=endpointPoint(d,e.source,e),b=endpointPoint(d,e.target,e);
  const mid={x:(a.x+b.x)/2,y:(a.y+b.y)/2};
  return <g key={e.id} data-element={e.id} onPointerDown={evt=>handle(evt,e.id)} style={{cursor:interactive?'pointer':'default'}}>
   <path d={dpath} fill="none" stroke={e.style?.stroke||palette.accent} strokeWidth={e.style?.strokeWidth??2} strokeDasharray={e.style?.strokeDash?.join(' ')} markerEnd="url(#od-arrow)" opacity={e.style?.opacity??1}/>
   {interactive&&<path d={dpath} fill="none" stroke="transparent" strokeWidth={Math.max(15,8)} />}
   {label&&<g><rect x={mid.x-label.length*3.6-9} y={mid.y-22} width={label.length*7.2+18} height={23} rx={5} fill={palette.canvas}/><text x={mid.x} y={mid.y-6} fill={color(e.label?.style,'textColor',palette.accent)} fontSize={e.label?.style?.fontSize??13} textAnchor="middle">{label}</text></g>}
   {set.has(e.id)&&interactive&&<path d={dpath} fill="none" stroke={selectStroke} strokeWidth="2" strokeDasharray="3 5" pointerEvents="none"/>}
  </g>;
 }
 if(e.type==='path'){
  const offset=origin(d,e);return <g key={e.id} data-element={e.id} onPointerDown={evt=>handle(evt,e.id)} style={{cursor:interactive?'move':'default'}}>
   <path d={pathD(e.commands,offset)} fill={e.style?.fill??'none'} stroke={e.style?.stroke??palette.accent} strokeWidth={e.style?.strokeWidth??3} strokeLinecap="round" strokeLinejoin="round" strokeDasharray={e.style?.strokeDash?.join(' ')} opacity={e.style?.opacity??1}/>
   {interactive&&<path d={pathD(e.commands,offset)} fill="none" stroke="transparent" strokeWidth={16}/>}
  </g>;
 }
 const g=absGeometry(d,e),style=e.style||{},fill=style.fill??(e.type==='group'?'#1d202644':palette.panel),stroke=style.stroke??(e.type==='group'?palette.muted:palette.ink),sw=style.strokeWidth??(e.type==='group'?1.4:2);
 const common={fill,stroke,strokeWidth:sw,strokeDasharray:style.strokeDash?.join(' '),opacity:style.opacity??1};
 const rotate=g.rotation?`rotate(${g.rotation} ${g.x+g.width/2} ${g.y+g.height/2})`:undefined;
 if(e.type==='group')return <g key={e.id} data-element={e.id} transform={rotate} onPointerDown={evt=>handle(evt,e.id)} style={{cursor:interactive?'move':'default'}}>
  <rect x={g.x} y={g.y} width={g.width} height={g.height} rx={8} {...common}/>
  <text x={g.x+15} y={g.y+23} fill={palette.muted} fontSize={14}>{e.label?.text??'Group'}</text>
  {set.has(e.id)&&interactive&&<rect x={g.x-3} y={g.y-3} width={g.width+6} height={g.height+6} fill="none" stroke={selectStroke} strokeDasharray="6 3" strokeWidth={2} pointerEvents="none"/>}{set.has(e.id)&&interactive&&<g onPointerDown={ev=>{ev.stopPropagation();onResizeDown?.(ev,e.id);}} className="resize-handle"><circle cx={g.x+g.width} cy={g.y+g.height} r={23/zoom} fill="transparent"/><circle cx={g.x+g.width} cy={g.y+g.height} r={9/zoom} fill={selectStroke} stroke="#14242e" strokeWidth={2} pointerEvents="none"/></g>}
 </g>;
 if(e.type==='text')return <g key={e.id} data-element={e.id} transform={rotate} onPointerDown={evt=>handle(evt,e.id)} style={{cursor:interactive?'move':'default'}}>
  <text x={g.x} y={g.y+(style.fontSize??23)} fontSize={style.fontSize??23} fontFamily={style.fontFamily??'Segoe UI'} fontWeight={style.fontWeight??400} fill={style.textColor??palette.ink}>
   {lines(e.text,g.width,style.fontSize??23).map((line,i)=><tspan x={g.x} dy={i?1.25*(style.fontSize??23):0} key={i}>{line}</tspan>)}
  </text>
  {set.has(e.id)&&interactive&&<rect x={g.x-5} y={g.y-3} width={g.width+10} height={g.height+6} fill="none" stroke={selectStroke} strokeDasharray="5 3" pointerEvents="none"/>}{set.has(e.id)&&interactive&&<g onPointerDown={ev=>{ev.stopPropagation();onResizeDown?.(ev,e.id);}} className="resize-handle"><circle cx={g.x+g.width} cy={g.y+g.height} r={23/zoom} fill="transparent"/><circle cx={g.x+g.width} cy={g.y+g.height} r={9/zoom} fill={selectStroke} pointerEvents="none"/></g>}
 </g>;
 const n=e as NodeElement,primitive=n.shape.fallback;
 const outline=primitive==='ellipse'?<ellipse cx={g.x+g.width/2} cy={g.y+g.height/2} rx={g.width/2} ry={g.height/2} {...common}/>:
 primitive==='diamond'?<path d={`M ${g.x+g.width/2} ${g.y} L ${g.x+g.width} ${g.y+g.height/2} L ${g.x+g.width/2} ${g.y+g.height} L ${g.x} ${g.y+g.height/2} Z`} {...common}/>:
 primitive==='polygon'?<polygon points={`${g.x+g.width/2},${g.y} ${g.x+g.width},${g.y+g.height*.3} ${g.x+g.width*.85},${g.y+g.height} ${g.x+g.width*.15},${g.y+g.height} ${g.x},${g.y+g.height*.3}`} {...common}/>:
 primitive==='line'?<line x1={g.x} y1={g.y} x2={g.x+g.width} y2={g.y+g.height} {...common}/>:
 <rect x={g.x} y={g.y} width={g.width} height={g.height} rx={primitive==='rect'?0:style.cornerRadius??12} {...common}/>;
 const font=n.label?.style?.fontSize??style.fontSize??18,fg=n.label?.style?.textColor??style.textColor??palette.ink;
 const title=n.content?.title??n.label?.text??'Shape',subtitle=n.content?.subtitle,items=n.content?.items??[],chips=n.content?.chips??[],footer=n.content?.footer;
 const center=primitive==='ellipse'||primitive==='diamond';
 const left=g.x+(center?g.width/2:18),y=g.y+(center?g.height/2:28);
 const align=center?'middle':'start';
 return <g key={e.id} data-element={e.id} onPointerDown={evt=>handle(evt,e.id)} style={{cursor:interactive?'move':'default'}} >
 <g transform={rotate}>{outline}
 <text x={left} y={y} textAnchor={align} fontSize={font} fontWeight={600} fontFamily="Segoe UI" fill={fg}>
 {lines(title,center?g.width-42:g.width-35,font).slice(0,3).map((line,i)=><tspan key={i} x={left} dy={i?font*1.15:0}>{line}</tspan>)}
 </text>
 {subtitle&&<text x={left} y={y+29} textAnchor={align} fontSize={13} fill={palette.muted}>{subtitle.slice(0,75)}</text>}
 {items.map((it,i)=><text key={it.id} x={g.x+18} y={g.y+(subtitle?69:50)+i*20} fontSize={13} fill={it.style?.textColor??palette.ink}>• {it.text.slice(0,70)}</text>)}
 {chips.slice(0,4).map((chip,i)=><g key={chip.id}><rect x={g.x+15+i*96} y={g.y+g.height-(footer?52:34)} rx={4} width={89} height={21} fill={chip.style?.fill??palette.accent}/><text x={g.x+22+i*96} y={g.y+g.height-(footer?37:19)} fontSize={11} fill={chip.style?.textColor??palette.canvas}>{chip.text.slice(0,12)}</text></g>)}
 {footer&&<text x={g.x+18} y={g.y+g.height-9} fill={palette.muted} fontSize={11}>{footer.slice(0,70)}</text>}
 {set.has(e.id)&&interactive&&<rect x={g.x-4} y={g.y-4} width={g.width+8} height={g.height+8} fill="none" stroke={selectStroke} strokeWidth={2} strokeDasharray="5 3" pointerEvents="none"/>}{set.has(e.id)&&interactive&&<g onPointerDown={ev=>{ev.stopPropagation();onResizeDown?.(ev,e.id);}} className="resize-handle"><circle cx={g.x+g.width} cy={g.y+g.height} r={23/zoom} fill="transparent"/><circle cx={g.x+g.width} cy={g.y+g.height} r={9/zoom} fill={selectStroke} stroke="#14242e" strokeWidth={2} pointerEvents="none"/></g>}
 </g>
 {interactive&&(tool==='connect'||set.has(e.id))&&n.ports.map(port=>{const pos=anchorPoint(d,n,port.anchor.side,port.anchor.t),active=connectFrom?.nodeId===n.id&&connectFrom.portId===port.id;return <g key={port.id} className="port" onPointerDown={evt=>{evt.stopPropagation();onPortDown?.(evt,n.id,port.id);}}><circle cx={pos.x} cy={pos.y} r={23/zoom} fill="transparent"/><circle cx={pos.x} cy={pos.y} r={(tool==='connect'?8:6)/zoom} fill={active?palette.accent:'#111d24'} stroke={active?palette.ink:selectStroke} strokeWidth={2/zoom} pointerEvents="none"/></g>;})}
 </g>;
 };
 return <><defs><marker id="od-arrow" markerWidth="7" markerHeight="7" refX="6" refY="3.5" orient="auto"><path d="M0 0 L7 3.5 L0 7 Z" fill={palette.accent}/></marker></defs>
 <rect x={0} y={0} width={d.page.width} height={d.page.height} fill={d.page.background} />
 {groups.map(shape)}{edges.map(shape)}{shapes.map(shape)}
 </>;
}
