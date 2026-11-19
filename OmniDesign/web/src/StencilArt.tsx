import type {ReactNode} from 'react';
import type {Geometry,NodeElement,Style} from './types';
type Paint={fill?:string;stroke?:string;strokeWidth?:number;strokeDasharray?:string;opacity?:number};
import {palette} from './model';

/** SVG-only semantic glyphs. All geometry and labels remain native editable nodes. */
export function StencilArt({node:n,g,common}:{node:NodeElement;g:Geometry;common:Paint}):ReactNode {
 const role=n.semantic?.role||'',glyph=typeof n.semantic?.data?.['omnidesign/glyph']==='string'?n.semantic.data['omnidesign/glyph']:role.split('.').at(-1)||'';
 const border=n.style?.stroke??palette.ink;
 const stroke={stroke:border,strokeWidth:2,fill:'none'};
 const body={...common};
 const x=g.x,y=g.y,w=g.width,h=g.height;
 switch(glyph){
 case 'database': case 'resource':
  return <g><rect x={x} y={y+19} width={w} height={h-37} {...body}/><ellipse cx={x+w/2} cy={y+20} rx={w/2} ry={20} {...body}/><path d={`M ${x} ${y+h-20} A ${w/2} 20 0 0 0 ${x+w} ${y+h-20}`} {...stroke}/></g>;
 case 'queue':
  return <g><rect x={x} y={y} width={w} height={h} rx={5} {...body}/>{[0,1,2].map(i=><rect key={i} x={x+12+i*22} y={y+h-30} width={15} height={18} rx={2} fill={palette.accent} opacity={.5+i*.15}/>)}</g>;
 case 'screen':
  return <g><rect x={x} y={y} width={w} height={h} rx={8} {...body}/>
   <path d={`M${x} ${y+44} H${x+w}`} {...stroke}/><circle cx={x+w-19} cy={y+21} r={5} fill={palette.accent}/></g>;
 case 'cloud':
  return <path d={`M${x+w*.22} ${y+h*.78} C${x-w*.13} ${y+h*.77},${x+w*.02} ${y+h*.34},${x+w*.3} ${y+h*.36}
   C${x+w*.32} ${y-h*.04},${x+w*.69} ${y-h*.04},${x+w*.72} ${y+h*.29}
   C${x+w*1.08} ${y+h*.28},${x+w*1.11} ${y+h*.77},${x+w*.78} ${y+h*.78} Z`} {...body}/>;
 case 'entity':case 'weak':
  return <g><rect x={x} y={y} width={w} height={h} rx={3} {...body}/><rect x={x+1} y={y+1} width={w-2} height={40} fill={palette.accent} opacity={.23}/>
   <path d={`M${x} ${y+43} H${x+w}`} {...stroke}/>
   {n.content?.items?.slice(0,5).map((_,i)=><path key={i} d={`M${x+12} ${y+62+i*20} H${x+w-12}`} stroke={border} strokeWidth={.35} opacity={.24}/>)}
   </g>;
 case 'actor':case 'lifeline':{
  const avatar=glyph==='actor';
  return <g><rect x={x} y={y} width={w} height={h} fill="transparent" stroke="none"/>
    <rect x={x} y={y} width={w} height={75} rx={6} {...body}/>
    <path d={`M${x+w/2} ${y+76} V${y+h}`} stroke={border} strokeDasharray="7 8" strokeWidth={1.7}/>
    {avatar&&<g stroke={border} strokeWidth={2} fill="none"><circle cx={x+24} cy={y+20} r={9}/><path d={`M${x+24} ${y+29} v22 m0 -15 l-12 10 m12 -10 l12 10 m-12 5 l-12 15 m12 -15 l12 15`}/></g>}
   </g>;}
 case 'initial':
  return <circle cx={x+w/2} cy={y+h/2} r={Math.min(w,h)*.43} fill={palette.accent} stroke={border} strokeWidth={1.5}/>;
 case 'final':
  return <g><circle cx={x+w/2} cy={y+h/2} r={Math.min(w,h)*.43} {...body}/><circle cx={x+w/2} cy={y+h/2} r={Math.min(w,h)*.25} fill={palette.accent}/></g>;
 case 'router':
  return <g><ellipse cx={x+w/2} cy={y+h/2} rx={w/2} ry={h/2} {...body}/><path d={`M ${x+w*.19} ${y+h*.65} L${x+w*.32} ${y+h*.45} L${x+w*.29} ${y+h*.6}
   M${x+w*.82} ${y+h*.33} L${x+w*.67} ${y+h*.47} L${x+w*.68} ${y+h*.3}`} {...stroke}/></g>;
 case 'switch':
  return <g><rect x={x} y={y} width={w} height={h} rx={6} {...body}/>
   {Array.from({length:6},(_,i)=><rect key={i} x={x+20+i*(w-40)/6} y={y+h-32} width={Math.max(8,(w-60)/9)} height={12} fill={palette.accent}/>)}</g>;
 case 'firewall':
  return <g><rect x={x} y={y} width={w} height={h} {...body}/><path d={`M ${x} ${y+h*.6} H${x+w} M${x} ${y+h*.8} H${x+w} M${x+w*.4} ${y+h*.6} V${y+h*.8} M${x+w*.6} ${y+h*.8} V${y+h}`} {...stroke}/></g>;
 case 'document':
  return <path d={`M${x} ${y} H${x+w*.8} L${x+w} ${y+h*.25} V${y+h} Q ${x+w*.75} ${y+h-14} ${x+w*.5} ${y+h} T${x} ${y+h} Z`} {...body}/>;
 case 'room':
  return <g><rect x={x} y={y} width={w} height={h} fill="#1d2026" stroke={border} strokeWidth={7}/><path d={`M${x+12} ${y+12} H${x+w-12} V${y+h-12} H${x+12} Z`} stroke={palette.muted} strokeWidth={.7} fill="none"/></g>;
 case 'door':
  return <g><rect x={x} y={y} width={w} height={h} fill={palette.canvas} stroke="none"/><path d={`M${x+5} ${y+h-3} V${y+3} A${w-8} ${h-6} 0 0 1 ${x+w-3} ${y+h-3}`} stroke={border} strokeWidth={2} fill="none" strokeDasharray="5 4"/></g>;
 case 'window':
  return <g><rect x={x} y={y} width={w} height={h} fill={palette.canvas} stroke={border} strokeWidth={2}/><path d={`M${x} ${y+h/2} H${x+w}`} {...stroke}/></g>;
 case 'lane':
  return <g><rect x={x} y={y} width={w} height={h} {...body}/><rect x={x} y={y} width={130} height={h} fill={palette.accent} opacity={.12}/><path d={`M${x+130} ${y} V${y+h}`} {...stroke}/></g>;
 case 'event':
  return <g><rect x={x} y={y} width={w} height={h} rx={10} {...body}/><path d={`M${x+w-48} ${y+8} l-12 26 h12 l-8 25 25-35 h-14 l8-16`} fill={palette.accent} opacity={.8}/></g>;
 case 'activation':
  return <rect x={x} y={y} width={w} height={h} {...body}/>;
 default:return null;
 }
}
export const edgeStyleKinds=[
 {id:'flow',label:'Arrow'},{id:'association',label:'Association (no arrow)'},{id:'dependency',label:'Dependency'},
 {id:'return',label:'Dashed return'},{id:'message',label:'Message'},{id:'transition',label:'State transition'},
 {id:'crowfoot',label:'ERD one-to-many'},{id:'inheritance',label:'UML inheritance'},
 {id:'composition',label:'Composition'},{id:'link',label:'Network link'},{id:'event',label:'Game event'}
] as const;
export function edgeMarker(kind:string):string|undefined {
 if(kind==='association'||kind==='link')return undefined;
 if(kind==='crowfoot')return 'url(#od-crowfoot)';
 if(kind==='inheritance')return 'url(#od-hollow)';
 if(kind==='composition')return 'url(#od-diamond)';
 if(kind==='return')return 'url(#od-open)';
 return 'url(#od-arrow)';
}
export function edgeStrokeDash(kind:string,style?:Style):string|undefined {
 return (style?.strokeDash??(kind==='return'||kind==='dependency'?[6,5]:undefined))?.join(' ');
}
