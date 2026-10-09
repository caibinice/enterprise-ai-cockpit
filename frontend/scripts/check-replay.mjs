import assert from 'node:assert/strict';
import {stepCursor,completedAtCursor,inputForEvent} from '../src/workflow/replay.ts';

const events=[
  {seq:2,type:'run.started',spanId:'run'},
  {seq:4,type:'node.completed',spanId:'a',nodeId:'a'},
  {seq:9,type:'node.started',spanId:'b',nodeId:'b',input:{round:2}},
  {seq:15,type:'node.completed',spanId:'b',nodeId:'b',output:{ok:true}},
  {seq:20,type:'run.completed',spanId:'run'}
];
const cursors=[0,1,2,3,4,5];
assert.deepEqual(cursors.map(cursor=>stepCursor(events,cursor,'next')),[2,2,4,4,5,5]);
assert.deepEqual(cursors.map(cursor=>stepCursor(events,cursor,'previous')),[0,0,0,2,2,4]);
assert.equal(completedAtCursor(events,0),undefined);
assert.equal(completedAtCursor(events,3).nodeId,'a');
assert.equal(completedAtCursor(events,4).nodeId,'b');
assert.equal(stepCursor([],0,'next'),0);
assert.equal(stepCursor([],0,'previous'),0);
assert.deepEqual(inputForEvent(events,events[3]),{round:2});
assert.equal(inputForEvent(events,events[1]),undefined);
assert.equal(inputForEvent(events,undefined),undefined);
const nested=[
  {type:'node.started',spanId:'shared',path:'root',input:{all:'root state'}},
  {type:'agent.started',spanId:'shared',path:'root/toolAgent[3]',input:{task:'order'}},
  {type:'agent.completed',spanId:'shared',path:'root/toolAgent[3]',output:{draft:'quote'}}
];
assert.deepEqual(inputForEvent(nested,nested[2]),{task:'order'});
assert.deepEqual(inputForEvent(nested,nested[0]),{all:'root state'});
assert.equal(inputForEvent(nested,{spanId:'other',path:'root'}),undefined);
console.log('Replay helpers: 23 boundary assertions passed.');
