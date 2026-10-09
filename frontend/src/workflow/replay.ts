import type {TraceEvent} from './types';

/** Cursor is an array position, not an event ID: replay also supports sparse exports. */
export function stepCursor(events:TraceEvent[], cursor:number, direction:'next'|'previous'):number {
  const positions=events.flatMap((event,index)=>event.type==='node.completed'?[index+1]:[]);
  if(direction==='next')return positions.find(position=>position>cursor)??events.length;
  return positions.filter(position=>position<cursor).at(-1)??0;
}

export function completedAtCursor(events:TraceEvent[],cursor:number):TraceEvent|undefined {
  return events.slice(0,cursor).filter(event=>event.type==='node.completed').at(-1);
}

/** Input and output are emitted separately; pair them by the actual execution span. */
export function inputForEvent(events:TraceEvent[],event:TraceEvent|undefined):unknown {
  if(!event)return undefined;
  return event.input??events.find(value=>value.spanId===event.spanId&&value.path===event.path&&value.input!==undefined)?.input;
}
