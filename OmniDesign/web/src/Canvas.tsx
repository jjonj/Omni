import React,{forwardRef,useEffect,useImperativeHandle,useRef,useState} from 'react';
import {GraphicScene} from './GraphicScene';
import {clone,moveElements,snap,palette,anchorPoint} from './model';
import type {Diagram,Point,Tool} from './types';

export type CanvasApi={fit:()=>void;zoom:(factor:number)=>void};
type Props={document:Diagram;tool:Tool;selected:string[];select:(ids:string[])=>void;preview:(doc:Diagram)=>void;finish:(before:Diagram)=>void;create:(tool:Tool,p:Point)=>void;draw:(points:Point[])=>void;connect:(from:{nodeId:string;portId:string},to:{nodeId:string;portId:string})=>void;edit:(id:string)=>void;};
type View={x:number;y:number;z:number};
type Drag={kind:'pan'|'move'|'resize'|'draw';pointer:number;start:Point;view:View;before:Diagram;ids:string[];moved:boolean;points:Point[]};
const clamp=(v:number,min:number,max:number)=>Math.min(max,Math.max(min,v));
export const Canvas=forwardRef<CanvasApi,Props>(function Canvas({document:d,tool,selected,select,preview,finish,create,draw,connect,edit},ref){
 const svg=useRef<SVGSVGElement>(null),box=useRef<HTMLDivElement>(null),drag=useRef<Drag|null>(null);
 const [view,setView]=useState<View>({x:70,y:75,z:0.8}),viewRef=useRef(view);
 const [connectFrom,setConnectFrom]=useState<{nodeId:string;portId:string}|null>(null);
 const [cursor,setCursor]=useState<Point|null>(null),[stroke,setStroke]=useState<Point[]>([]);
 const pointers=useRef(new Map<number,Point>());
 const pinch=useRef<{distance:number;world:Point;view:View}|null>(null);
 const setCamera=(v:View)=>{viewRef.current=v;setView(v);};
 const size=()=>box.current?.getBoundingClientRect()??{left:0,top:0,width:1000,height:700};
 const local=(e:Pick<PointerEvent,'clientX'|'clientY'>):Point=>{const r=size();return {x:e.clientX-r.left,y:e.clientY-r.top};};
 const toWorld=(p:Point,v=viewRef.current):Point=>({x:(p.x-v.x)/v.z,y:(p.y-v.y)/v.z});
 const fit=()=>{const r=size();const z=clamp(Math.min((r.width-90)/d.page.width,(r.height-80)/d.page.height),.12,2);setCamera({x:(r.width-d.page.width*z)/2,y:(r.height-d.page.height*z)/2,z});};
 useEffect(()=>{fit();setConnectFrom(null);},[d.id]); // Only a document switch resets the camera.
 const zoom=(factor:number,client?:Point)=>{const r=size(),p=client??{x:r.width/2,y:r.height/2},w=toWorld(p),z=clamp(viewRef.current.z*factor,.12,5);setCamera({z,x:p.x-w.x*z,y:p.y-w.y*z});};
 useImperativeHandle(ref,()=>({fit,zoom}),[d.id]);
 const beginPinch=()=>{const [a,b]=Array.from(pointers.current.values()),center={x:(a.x+b.x)/2,y:(a.y+b.y)/2};if(drag.current?.kind==='move'||drag.current?.kind==='resize')preview(drag.current.before);drag.current=null;pinch.current={distance:Math.hypot(a.x-b.x,a.y-b.y)||1,world:toWorld(center),view:viewRef.current};};
 // Native capture sees every simultaneous touch pointer, including events Chromium may not bubble through React's delegated handler.
 useEffect(()=>{const el=svg.current;if(!el)return;
  const down=(e:PointerEvent)=>{if(e.pointerType!=='touch')return;pointers.current.set(e.pointerId,local(e));if(pointers.current.size===2)beginPinch();};
  const move=(e:PointerEvent)=>{if(e.pointerType!=='touch'||!pointers.current.has(e.pointerId))return;pointers.current.set(e.pointerId,local(e));if(!pinch.current||pointers.current.size<2)return;const [a,b]=Array.from(pointers.current.values()),center={x:(a.x+b.x)/2,y:(a.y+b.y)/2},z=clamp(pinch.current.view.z*Math.hypot(a.x-b.x,a.y-b.y)/pinch.current.distance,.12,5);setCamera({x:center.x-pinch.current.world.x*z,y:center.y-pinch.current.world.y*z,z});};
  const up=(e:PointerEvent)=>{if(e.pointerType!=='touch')return;pointers.current.delete(e.pointerId);if(pointers.current.size<2)pinch.current=null;};
  el.addEventListener('pointerdown',down,true);el.addEventListener('pointermove',move,true);el.addEventListener('pointerup',up,true);el.addEventListener('pointercancel',up,true);
  return()=>{el.removeEventListener('pointerdown',down,true);el.removeEventListener('pointermove',move,true);el.removeEventListener('pointerup',up,true);el.removeEventListener('pointercancel',up,true);};
 },[]);
 const pointerDown=(e:React.PointerEvent<SVGSVGElement>)=>{
  if(e.button!==0&&e.button!==1)return;
  const p=local(e.nativeEvent);pointers.current.set(e.pointerId,p);svg.current?.setPointerCapture(e.pointerId);
  if(pointers.current.size===2){beginPinch();return;}
  const w=toWorld(p);
  if(tool==='pen'){
   drag.current={kind:'draw',pointer:e.pointerId,start:w,view:viewRef.current,before:d,ids:[],moved:false,points:[w]};setStroke([w]);return;
  }
  if(tool==='rect'||tool==='roundRect'||tool==='ellipse'||tool==='diamond'||tool==='text'){
   create(tool,{x:snap(w.x,d),y:snap(w.y,d)});return;
  }
  if(tool==='connect'){if(!connectFrom)select([]);setCursor(w);return;}
  if(tool==='select')select([]);
  drag.current={kind:'pan',pointer:e.pointerId,start:p,view:viewRef.current,before:d,ids:[],moved:false,points:[]};
 };
 const elementDown=(e:React.PointerEvent<SVGGElement>,id:string)=>{
  if(e.button!==0)return;
  e.stopPropagation();
  const p=local(e.nativeEvent),w=toWorld(p);
  pointers.current.set(e.pointerId,p);svg.current?.setPointerCapture(e.pointerId);
  if(pointers.current.size===2){beginPinch();return;}
  if(tool==='hand'){drag.current={kind:'pan',pointer:e.pointerId,start:p,view:viewRef.current,before:d,ids:[],moved:false,points:[]};return;}
  const multi=e.shiftKey||e.ctrlKey||e.metaKey;
  const ids=multi?(selected.includes(id)?selected.filter(i=>i!==id):[...selected,id]):selected.includes(id)?selected:[id];
  select(ids);
  if(tool==='connect')return;
  drag.current={kind:'move',pointer:e.pointerId,start:w,view:viewRef.current,before:clone(d),ids,moved:false,points:[]};
 };
 const resizeDown=(e:React.PointerEvent<SVGGElement>,id:string)=>{
  e.stopPropagation();const p=local(e.nativeEvent);pointers.current.set(e.pointerId,p);svg.current?.setPointerCapture(e.pointerId);
  drag.current={kind:'resize',pointer:e.pointerId,start:toWorld(p),view:viewRef.current,before:clone(d),ids:[id],moved:false,points:[]};
 };
 const portDown=(e:React.PointerEvent<SVGGElement>,nodeId:string,portId:string)=>{
  if(tool!=='connect')return;
  e.stopPropagation();if(connectFrom){if(connectFrom.nodeId!==nodeId||connectFrom.portId!==portId)connect(connectFrom,{nodeId,portId});setConnectFrom(null);}
  else setConnectFrom({nodeId,portId});
 };
 const pointerMove=(e:React.PointerEvent<SVGSVGElement>)=>{
  const p=local(e.nativeEvent),w=toWorld(p);
  if(tool==='connect')setCursor(w);
  if(pointers.current.has(e.pointerId))pointers.current.set(e.pointerId,p);
  if(pinch.current&&pointers.current.size>=2){
   const [a,b]=Array.from(pointers.current.values()),center={x:(a.x+b.x)/2,y:(a.y+b.y)/2};
   const z=clamp(pinch.current.view.z*Math.hypot(a.x-b.x,a.y-b.y)/pinch.current.distance,.12,5);
   setCamera({x:center.x-pinch.current.world.x*z,y:center.y-pinch.current.world.y*z,z});return;
  }
  const g=drag.current;if(!g||g.pointer!==e.pointerId)return;
  if(g.kind==='pan'){const dx=p.x-g.start.x,dy=p.y-g.start.y;setCamera({...g.view,x:g.view.x+dx,y:g.view.y+dy});return;}
  if(g.kind==='draw'){
   if(Math.hypot(w.x-g.points[g.points.length-1].x,w.y-g.points[g.points.length-1].y)>2/viewRef.current.z){g.points.push(w);setStroke([...g.points]);}return;
  }
  const dx=w.x-g.start.x,dy=w.y-g.start.y;if(Math.hypot(dx,dy)<1.5&&!g.moved)return;
  g.moved=true;
  if(g.kind==='resize'){
   preview({...g.before,elements:g.before.elements.map(el=>el.id===g.ids[0]&&'geometry' in el?{...el,geometry:{...el.geometry,width:Math.max(32,snap(el.geometry.width+dx,g.before)),height:Math.max(32,snap(el.geometry.height+dy,g.before))}}:el)});
  }else{preview(moveElements(g.before,g.ids,snap(dx,g.before),snap(dy,g.before)));}
 };
 const pointerUp=(e:React.PointerEvent<SVGSVGElement>)=>{
  pointers.current.delete(e.pointerId);if(pointers.current.size<2)pinch.current=null;
  const g=drag.current;if(!g||g.pointer!==e.pointerId)return;
  drag.current=null;
  if((g.kind==='move'||g.kind==='resize')&&g.moved)finish(g.before);
  if(g.kind==='draw'&&g.points.length>1)draw(g.points);
  setStroke([]);
 };
 useEffect(()=>{const release=()=>{if(drag.current?.kind==='move'||drag.current?.kind==='resize')finish(drag.current.before);drag.current=null;pointers.current.clear();pinch.current=null;setStroke([]);};window.addEventListener('blur',release);return ()=>window.removeEventListener('blur',release);},[finish]);
 const cursorStyle=tool==='hand'?'grab':tool==='pen'?'crosshair':tool==='connect'?'crosshair':tool==='select'?'default':'crosshair';
 return <div className="canvas-surface" ref={box}>
 <svg ref={svg} width="100%" height="100%" style={{touchAction:'none',cursor:cursorStyle}} onPointerDown={pointerDown} onPointerMove={pointerMove} onPointerUp={pointerUp} onPointerCancel={pointerUp} onWheel={e=>{e.preventDefault();zoom(Math.exp(-e.deltaY*.0015),local(e.nativeEvent as unknown as PointerEvent));}} onDoubleClick={e=>{const t=(e.target as Element).closest?.('[data-element]');if(t)edit(t.getAttribute('data-element')!);}}>
 <rect width="100%" height="100%" fill="#101216"/>
 <g transform={`translate(${view.x} ${view.y}) scale(${view.z})`}>
 <rect x={-250000} y={-250000} width={500000} height={500000} fill="transparent"/>
 <GraphicScene document={d} selection={selected} tool={tool} zoom={view.z} interactive onElementDown={elementDown} onResizeDown={resizeDown} onPortDown={portDown} connectFrom={connectFrom}/>
 {d.page.grid.enabled&&<g pointerEvents="none" opacity=".27"><defs><pattern id="od-grid" x="0" y="0" width={d.page.grid.size} height={d.page.grid.size} patternUnits="userSpaceOnUse"><circle cx="0" cy="0" r="1" fill="#e6e2d8"/></pattern></defs><rect width={d.page.width} height={d.page.height} fill="url(#od-grid)"/></g>}
 {stroke.length>0&&<polyline points={stroke.map(p=>p.x+','+p.y).join(' ')} stroke={palette.accent} strokeWidth="3" fill="none" pointerEvents="none"/>}
 {connectFrom&&cursor&&(()=>{const n=d.elements.find(e=>e.id===connectFrom.nodeId);if(!n||n.type!=='node')return null;const port=n.ports.find(p=>p.id===connectFrom.portId);if(!port)return null;const source=anchorPoint(d,n,port.anchor.side,port.anchor.t);return <path d={`M ${source.x} ${source.y} L ${cursor.x} ${cursor.y}`} stroke={palette.accent} strokeWidth="2" strokeDasharray="7 5" pointerEvents="none"/>;})()}
 </g></svg>
 <span className="zoom-indicator">{Math.round(view.z*100)}% · {tool==='connect'?(connectFrom?'Tap destination port':'Tap source port'):'Scroll to zoom · drag canvas to pan'}</span>
 </div>;
});
