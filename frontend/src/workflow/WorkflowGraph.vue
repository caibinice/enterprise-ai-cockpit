<script setup lang="ts">
import {computed,ref} from 'vue';
import type {GraphNode,GraphEdge,TraceEvent} from './types';
const props=defineProps<{nodes:GraphNode[];edges:GraphEdge[];events:TraceEvent[];selected?:string}>();
const emit=defineEmits<{select:[string]}>();
const zoom=ref(1);
const layoutNodes=computed(()=>props.nodes.map(n=>({...n,y:n.y*.68})));
const colors:Record<string,string>={system:'#8e8e93',model:'#007aff',retrieval:'#30a46c',tool:'#b070e0',agent:'#ed8c26',review:'#d49414',human:'#eb5d68'};
const stats=computed(()=>{const m:Record<string,{state:string;count:number;ms:number}>={};for(const e of props.events){if(!e.type.startsWith('node.'))continue;const p=m[e.nodeId]??{state:'idle',count:0,ms:0};p.state=e.type.split('.')[1]!;if(e.type==='node.completed'){p.count++;p.ms+=e.durationMs??0;}m[e.nodeId]=p;}return m;});
function path(e:GraphEdge){const a=layoutNodes.value.find(n=>n.id===e.source),b=layoutNodes.value.find(n=>n.id===e.target);if(!a||!b)return '';const x=a.x+88,y=a.y+66,ex=b.x+88,ey=b.y;if(ey>y)return `M${x},${y} C${x},${y+(ey-y)/2} ${ex},${y+(ey-y)/2} ${ex},${ey}`;const offset=e.source==='requery'?38:e.source==='tool_execute'?70:110;return `M${a.x},${a.y+33} C${a.x-offset},${a.y+33} ${b.x-offset},${b.y+33} ${b.x},${b.y+33}`;}
function used(e:GraphEdge){return props.events.some(v=>v.type==='edge.traversed'&&v.source===e.source&&v.target===e.target);}
function state(n:GraphNode){return stats.value[n.id]?.state??'idle';}
</script>
<template>
  <div class="graph-shell">
    <div class="graph-legend"><span v-for="(color,kind) in colors" :key="kind"><i :style="{background:color}"></i>{{({system:'状态',model:'模型',retrieval:'RAG',tool:'工具',agent:'子图',review:'评审',human:'人工'} as Record<string,string>)[kind]}}</span></div>
    <div class="graph-scroll">
      <svg class="flow-svg" :style="{width:`${zoom*100}%`}" viewBox="-20 0 1280 690" role="img" aria-label="智能客服工作流图 点击节点查看输入输出">
        <defs><pattern id="dots" width="22" height="22" patternUnits="userSpaceOnUse"><circle cx="1" cy="1" r=".85" fill="#cfd3d9"/></pattern><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="#bfc5cd"/></marker><marker id="active-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="5" markerHeight="5" orient="auto"><path d="M 0 0 L 10 5 L 0 10 z" fill="#007aff"/></marker><filter id="shadow" x="-20%" y="-30%" width="140%" height="180%"><feDropShadow dx="0" dy="2" stdDeviation="3" flood-opacity=".06"/></filter></defs>
        <rect x="-20" y="0" width="1280" height="690" fill="url(#dots)" opacity=".65"/>
        <rect x="28" y="206" width="185" height="235" rx="20" fill="#30a46c" opacity=".045"/>
        <rect x="510" y="206" width="230" height="200" rx="20" fill="#b070e0" opacity=".035"/>
        <rect x="775" y="206" width="225" height="270" rx="20" fill="#ed8c26" opacity=".045"/>
        <text x="53" y="224" class="lane-label">知识检索循环</text><text x="530" y="224" class="lane-label">PLAN · ACT · OBSERVE</text><text x="790" y="224" class="lane-label">SPECIALIST SUBGRAPHS</text>
        <g v-for="(edge,i) in edges" :key="i"><path :d="path(edge)" fill="none" :class="['edge',{used:used(edge),feedback:edge.target==='requery'||edge.source==='tool_execute'||edge.source==='multi_review'}]" :marker-end="used(edge)?'url(#active-arrow)':'url(#arrow)'"/></g>
        <g v-for="node in layoutNodes" :key="node.id" :transform="`translate(${node.x},${node.y})`" :class="['graph-node',state(node),{selected:selected===node.id}]" tabindex="0" role="button" :aria-label="node.title" @click="emit('select',node.id)" @keydown.enter="emit('select',node.id)">
          <rect class="node-bg" width="176" height="66" rx="13" filter="url(#shadow)"/><rect x="12" y="14" width="27" height="27" rx="8" :fill="colors[node.kind]" opacity=".1"/>
          <text x="25.5" y="33" text-anchor="middle" :fill="colors[node.kind]" class="node-icon">{{({system:'S',model:'AI',retrieval:'R',tool:'T',agent:'G',review:'✓',human:'H'} as Record<string,string>)[node.kind]}}</text>
          <text x="48" y="28" class="node-title">{{node.title}}</text><text x="48" y="47" class="node-id">{{node.id}}</text>
          <circle v-if="state(node)==='started'" class="running-dot" cx="162" cy="17" r="4" fill="#007aff"/>
          <text v-if="stats[node.id]?.count" x="162" y="18" text-anchor="middle" class="node-count">{{stats[node.id]?.count}}</text>
          <text v-if="stats[node.id]?.count" x="88" y="60" text-anchor="middle" class="node-time">{{stats[node.id]?.ms}} ms · {{stats[node.id]?.count}} 次</text>
        </g>
      </svg>
    </div>
    <div class="graph-bottom"><span>条件边由状态驱动 · 高亮来自后端真实执行事件</span><div class="zoom"><button aria-label="缩小图" @click="zoom=Math.max(.7,zoom-.15)">−</button><button @click="zoom=1">{{Math.round(zoom*100)}}%</button><button aria-label="放大图" @click="zoom=Math.min(2,zoom+.15)">＋</button></div></div>
  </div>
</template>
