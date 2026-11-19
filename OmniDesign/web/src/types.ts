/** Native OmniDesign v1 types. Unrecognized semantic/extension fields are kept intact on edits. */
export type Point={x:number;y:number};
export type Geometry=Point&{width:number;height:number;rotation:number};
export type Style={fill?:string;stroke?:string;strokeWidth?:number;strokeDash?:number[];opacity?:number;cornerRadius?:number;textColor?:string;fontFamily?:string;fontSize?:number;fontWeight?:number;textAlign?:'left'|'center'|'right';lineHeight?:number;tokenRefs?:Record<string,string>};
export type Anchor={side:'top'|'right'|'bottom'|'left'|'center';t:number};
export type Port={id:string;name:string;direction:'in'|'out'|'both';anchor:Anchor;dataType?:string};
export type Endpoint={type:'port';nodeId:string;portId:string}|{type:'anchor';nodeId:string;anchor:Anchor}|{type:'free';point:Point};
export type Base={id:string;layerId:string;parentId:string|null;order:number;locked?:boolean;hidden?:boolean;extensions?:Record<string,unknown>;style?:Style;semantic?:{role:string;data:Record<string,unknown>}};
export type Item={id:string;text:string;style?:Style};
export type NodeElement=Base&{type:'node';geometry:Geometry;shape:{libraryId:string;name:string;version:string;fallback:'rect'|'roundRect'|'ellipse'|'diamond'|'line'|'polygon'|'icon'};ports:Port[];label?:{text:string;style?:Style};content?:{title?:string;subtitle?:string;items:Item[];chips:Item[];footer?:string}};
export type GroupElement=Base&{type:'group';geometry:Geometry;label?:{text:string;style?:Style}};
export type TextElement=Base&{type:'text';geometry:Geometry;text:string};
export type PathCommand={op:'M'|'L';to:Point}|{op:'C';control1:Point;control2:Point;to:Point}|{op:'Z'};
export type PathElement=Base&{type:'path';commands:PathCommand[]};
export type EdgeElement=Base&{type:'edge';source:Endpoint;target:Endpoint;route:{mode:'straight'|'orthogonal'|'bezier';waypoints:Point[];manual:boolean};label?:{text:string;style?:Style}};
export type Element=NodeElement|GroupElement|TextElement|PathElement|EdgeElement;
export type Diagram={
 format:'omnidesign';schemaVersion:1;id:string;revision:number;title:string;family:'architecture'|'flowchart'|'sequence'|'state'|'erd'|'network'|'roadmap'|'floorplan'|'swimlane'|'mindmap'|'game-system'|'custom';
 metadata:{createdAt:string;updatedAt:string;originDeviceId:string;forkedFrom:{id:string;revision:number}|null;description?:string;tags?:string[]};
 page:{width:number;height:number;background:string;grid:{enabled:boolean;size:number;snap:boolean};units:'px'|'mm'};
 theme:{name:string;tokens:Record<string,string>};
 layers:{id:string;name:string;visible:boolean;locked:boolean}[];
 elements:Element[];
 libraries:{references:{id:string;version:string}[];embedded:unknown[]};
 assets:unknown[];
 extensions?:Record<string,unknown>;
};
export type Tool='select'|'hand'|'connect'|'rect'|'roundRect'|'ellipse'|'diamond'|'text'|'pen'|'stencil';
