export interface TraceEvent { seq:number; type:string; timestamp:string; traceId:string; spanId:string; parentSpanId?:string; nodeId:string; path:string; durationMs?:number; input?:unknown; output?:unknown; source?:string; target?:string; error?:string; model?:string; purpose?:string; usage?:Record<string,number>; }
export interface GraphNode {id:string;title:string;kind:string;x:number;y:number}
export interface GraphEdge {source:string;target:string;label:string}
export interface Run {id:string;status:string;mode:string;message:string;answer:string;createdAt:string;events:TraceEvent[];state:Record<string,unknown>;knowledgeBase:string;fault:string;maxIterations:number;imageAttached:boolean}
export interface History {id:string;status:string;mode:string;message:string;createdAt:string}
