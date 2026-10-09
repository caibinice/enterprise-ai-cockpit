<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref} from 'vue';
import {Layers,Workflow,BookOpen,Presentation,Play,ArrowUp,ImagePlus,X,Check,ArrowRight,Square,Download,RotateCcw,ChevronRight,Clock,Radio,Search,FileJson,AlertCircle,Plus,Pause,Activity,ExternalLink} from 'lucide-vue-next';
import { api as cockpitApi, API_BASE } from '../api';
import WorkflowGraph from './WorkflowGraph.vue';
import ExecutionFocus from './ExecutionFocus.vue';
import AnswerContent from './AnswerContent.vue';
import {stepCursor,completedAtCursor,inputForEvent} from './replay';
import type {Run,History,TraceEvent,GraphNode,GraphEdge} from './types';
const mode=ref('offline'),page=ref('studio'),message=ref('Aero 耳机的退货政策是什么？签收三天未使用可以退吗？'),knowledgeBase=ref('support'),maxIterations=ref(3),fault=ref('none');
const graph=ref<{nodes:GraphNode[];edges:GraphEdge[]}>({nodes:[],edges:[]}),health=ref<any>(null),run=ref<Run|null>(null),history=ref<History[]>([]),events=ref<TraceEvent[]>([]),submitting=ref(false),error=ref(''),image=ref(''),imageName=ref('');
const selected=ref<TraceEvent|null>(null),selectedNode=ref('classify'),detailTab=ref('output'),trackLive=ref(true),cursor=ref(0),playing=ref(false),humanReply=ref(''),docs=ref<any[]>([]),kbSearch=ref(''),newTitle=ref(''),newContent=ref(''),showAdd=ref(false),sseConnected=ref(false);
const caseView=ref<'overview'|'tool'|'multi'>('overview'),presentationMode=ref(false),tracePath=ref('all'),eventFilter=ref('steps');
let source:EventSource|null=null,timer:ReturnType<typeof setInterval>|null=null;
const scenarios=[
  {id:'rag',n:'01',title:'产品知识 RAG',desc:'检索知识库 · 基于证据回答',badge:'RAG',text:'Aero 耳机的退货政策是什么？签收三天未使用可以退吗？',fault:'none'},
  {id:'vision',n:'02',title:'图片与文字',desc:'提取截图证据 · 再检索',badge:'VISION',text:'我上传的耳机故障截图里是什么问题？应该怎么处理？',fault:'none'},
  {id:'report',n:'03',title:'企业报表 MCP',desc:'发现工具 · 查询销售报表',badge:'MCP',text:'查询2026-09月的销售报表，告诉我营收、订单量及营收环比。',fault:'none'},
  {id:'tool',n:'04',title:'工具调用循环',desc:'查订单 · 观察结果 · 退款试算',badge:'LOOP',text:'查询订单SO20261001，然后根据实际状态试算可退款金额。',fault:'none'},
  {id:'multi',n:'05',title:'多 Agent 协作',desc:'拆解任务 · 执行专家子图 · 汇总',badge:'AGENTS',text:'请分别说明Aero耳机退货政策、SO20261001可退款金额，以及2026-09月销售报表。',fault:'none'},
  {id:'human',n:'06',title:'人工接管',desc:'保存交接上下文 · 人工反馈恢复',badge:'HITL',text:'我要人工客服处理这次赔偿争议，请转交人工。',fault:'none'},
  {id:'rework',n:'07',title:'反馈驱动重检索',desc:'首次证据缺失 · 修订查询 · 再答',badge:'RETRY',text:'Aero耳机退货政策是什么？',fault:'retrieval_gap'},
  {id:'timeout',n:'08',title:'工具异常与预算',desc:'注入一次超时 · 有界重试',badge:'BUDGET',text:'查询SO20261001并试算退款金额。',fault:'tool_timeout'}
];
const scenario=ref('rag');
const visibleEvents=computed(()=>trackLive.value?events.value:events.value.slice(0,cursor.value));
const steps=computed(()=>visibleEvents.value.filter(e=>e.type==='node.completed'));
const scopedEvents=computed(()=>visibleEvents.value.filter(e=>tracePath.value==='all'||e.path===tracePath.value));
const timeline=computed(()=>scopedEvents.value.filter(e=>eventFilter.value==='all'||e.type==='node.completed'||e.type==='agent.completed'||e.type.startsWith('run.')||e.type.endsWith('failed')));
const tracePaths=computed(()=>[...new Set(events.value.map(e=>e.path))]);
const runRoute=computed(()=>String(run.value?.state.route??(events.value.find(e=>e.type==='node.completed'&&e.nodeId==='classify')?.output as Record<string,unknown>|undefined)?.route??scenario.value));
const focusEvents=computed(()=>runRoute.value===caseView.value?visibleEvents.value:[]);
const scopeName=(path:string)=>path==='root'?'主图':path.replace('root/','').replace('ragAgent','知识专家').replace('reportAgent','报表专家').replace('toolAgent','订单专家');
const busy=computed(()=>submitting.value||run.value?.status==='RUNNING');
const canStop=computed(()=>busy.value||run.value?.status==='WAITING_HUMAN');
const duration=computed(()=>{const es=visibleEvents.value;if(es.length<2)return '—';return ((Date.parse(es.at(-1)!.timestamp)-Date.parse(es[0]!.timestamp))/1000).toFixed(1)+'s';});
const tokens=computed(()=>visibleEvents.value.reduce((n,e)=>n+(e.type==='model.completed'?(e.usage?.totalTokens??e.usage?.total_tokens??0):0),0));
const statusText:Record<string,string>={RUNNING:'正在执行',COMPLETED:'执行完成',WAITING_HUMAN:'等待人工',FAILED:'执行失败',CANCELLED:'已停止',INTERRUPTED:'服务重启中断'};
const pretty=(v:unknown)=>typeof v==='string'?v:JSON.stringify(v??{},null,2);
const title=(id:string)=>graph.value.nodes.find(n=>n.id===id)?.title??({agent_answer:'专家回答',runtime:'运行时'} as Record<string,string>)[id]??id;
const activeDetail=computed(()=>selected.value??[...scopedEvents.value].reverse().find(e=>e.nodeId===selectedNode.value&&e.type==='node.completed')??[...scopedEvents.value].reverse().find(e=>e.nodeId===selectedNode.value&&e.type==='node.started'));
const detailInput=computed(()=>inputForEvent(visibleEvents.value,activeDetail.value));
const filteredDocs=computed(()=>docs.value.filter(d=>JSON.stringify(d).toLowerCase().includes(kbSearch.value.toLowerCase())));
async function api(path:string,body?:unknown):Promise<any>{return cockpitApi('/workflow'+path,body===undefined?{}:{method:'POST',body:JSON.stringify(body)});}
async function refreshHistory(){history.value=await api('/runs');}
function connect(id:string){
  source?.close();const stream=new EventSource(`${API_BASE}/api/workflow/runs/${id}/events?after=${events.value.at(-1)?.seq??0}`);source=stream;
  stream.onopen=()=>{if(run.value?.id===id)sseConnected.value=true;};
  function syncRun(){api('/runs/'+id).then(value=>{if(run.value?.id===id)run.value=value;}).catch(err=>{if(run.value?.id===id)error.value=(err as Error).message;});refreshHistory().catch(()=>{});}
  stream.addEventListener('trace',(raw)=>{
    if(run.value?.id!==id)return;
    const event=JSON.parse((raw as MessageEvent).data) as TraceEvent;
    if(!events.value.some(value=>value.seq===event.seq))events.value.push(event);
    if(trackLive.value)cursor.value=events.value.length;
    if(event.type==='run.waiting'){run.value.status='WAITING_HUMAN';syncRun();}
    if(event.type==='run.resumed')run.value.status='RUNNING';
    if(['run.completed','run.failed','run.cancelled'].includes(event.type)){stream.close();sseConnected.value=false;syncRun();}
  });
  stream.onerror=()=>{if(run.value?.id!==id)return;sseConnected.value=false;if(!['RUNNING','WAITING_HUMAN'].includes(run.value.status))stream.close();};
}
async function submit(){error.value='';if(!message.value.trim()||busy.value)return;submitting.value=true;stopPlayback();try{const result=await api('/runs',{message:message.value,mode:mode.value,knowledgeBase:knowledgeBase.value,maxIterations:maxIterations.value,fault:fault.value,image:image.value||null,imageName:imageName.value||null});run.value=await api('/runs/'+result.id);events.value=[...run.value!.events];trackLive.value=true;cursor.value=events.value.length;tracePath.value='all';selected.value=null;selectedNode.value='classify';page.value='studio';connect(result.id);window.history.replaceState(window.history.state,'',`${location.pathname}?run=${result.id}`);await refreshHistory();}catch(e){error.value=(e as Error).message;}finally{submitting.value=false;}}
async function loadRun(id:string){try{source?.close();stopPlayback();run.value=await api('/runs/'+id);events.value=[...run.value!.events];trackLive.value=true;cursor.value=events.value.length;selected.value=null;message.value=run.value!.message;knowledgeBase.value=run.value!.knowledgeBase;mode.value=run.value!.mode;fault.value=run.value!.fault;maxIterations.value=run.value!.maxIterations;tracePath.value='all';scenario.value=run.value!.fault==='retrieval_gap'?'rework':run.value!.fault==='tool_timeout'?'timeout':run.value!.imageAttached?'vision':String(run.value!.state.route??'rag');caseView.value=['tool','multi'].includes(String(run.value!.state.route))?String(run.value!.state.route) as 'tool'|'multi':'overview';image.value='';imageName.value='';page.value='studio';if(['RUNNING','WAITING_HUMAN'].includes(run.value!.status))connect(id);window.history.replaceState(window.history.state,'',`${location.pathname}?run=${id}`);}catch(e){error.value=(e as Error).message;}}
async function choose(s:typeof scenarios[number]){scenario.value=s.id;caseView.value=s.id==='tool'||s.id==='multi'?s.id:s.id==='timeout'?'tool':'overview';tracePath.value='all';selected.value=null;message.value=s.text;fault.value=s.fault;knowledgeBase.value='support';image.value='';imageName.value='';page.value='studio';if(s.id==='vision')try{const r=await fetch(`${API_BASE}/api/workflow/demo-image`);if(!r.ok)throw new Error('图片样例加载失败');const blob=await r.blob();image.value=await dataUrl(blob);imageName.value='customer-e02.png';}catch(e){error.value=(e as Error).message;}}
function dataUrl(file:Blob):Promise<string>{return new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result));reader.onerror=reject;reader.readAsDataURL(file);});}
async function upload(e:Event){const f=(e.target as HTMLInputElement).files?.[0];if(!f)return;if(!['image/png','image/jpeg'].includes(f.type)||f.size>4_000_000){error.value='请选择4MB内的 PNG 或 JPEG 图片';return;}image.value=await dataUrl(f);imageName.value=f.name;}
function setScope(path:string){tracePath.value=path;selected.value=null;const last=[...scopedEvents.value].reverse().find(e=>e.type==='node.completed');if(last)selectedNode.value=last.nodeId;}
function step(direction:'next'|'previous'){stopPlayback();cursor.value=stepCursor(events.value,trackLive.value?events.value.length:cursor.value,direction);trackLive.value=false;const event=completedAtCursor(events.value,cursor.value);tracePath.value=event?.path??'all';selected.value=event??null;selectedNode.value=event?.nodeId??'ingest';detailTab.value='output';}
function selectNode(id:string){selectedNode.value=id;selected.value=null;detailTab.value='output';}
function selectEvent(e:TraceEvent){selected.value=e;selectedNode.value=e.nodeId;detailTab.value=e.output===undefined?'input':'output';}
async function cancel(){
  if(!run.value||submitting.value)return;
  const id=run.value.id;
  try{
    await api('/runs/'+id+'/cancel',{});
    const value=await api('/runs/'+id);
    if(run.value?.id!==id)return;
    run.value=value;events.value=[...value.events];
    if(trackLive.value)cursor.value=events.value.length;
    source?.close();sseConnected.value=false;
    await refreshHistory();
  }catch(e){error.value=(e as Error).message;}
}
async function resume(){if(!run.value||!humanReply.value.trim())return;try{const id=run.value.id;await api('/runs/'+id+'/human',{reply:humanReply.value});run.value.status='RUNNING';trackLive.value=true;tracePath.value='all';selected.value=null;connect(id);humanReply.value='';}catch(e){error.value=(e as Error).message;}}
function stopPlayback(){playing.value=false;if(timer)clearInterval(timer);timer=null;}
function replay(){if(playing.value){stopPlayback();return;}trackLive.value=false;selected.value=null;tracePath.value='all';if(cursor.value>=events.value.length)cursor.value=0;playing.value=true;timer=setInterval(()=>{if(cursor.value>=events.value.length)stopPlayback();else cursor.value++;},600);}
function seek(e:Event){stopPlayback();trackLive.value=false;cursor.value=Number((e.target as HTMLInputElement).value);selected.value=null;}
async function addKnowledge(){try{await api('/knowledge',{title:newTitle.value,content:newContent.value,knowledgeBase:knowledgeBase.value==='all'?'support':knowledgeBase.value});docs.value=await api('/knowledge');showAdd.value=false;newTitle.value='';newContent.value='';}catch(e){error.value=(e as Error).message;}}
async function uploadText(e:Event){const f=(e.target as HTMLInputElement).files?.[0];if(f){newTitle.value=f.name.replace(/\.[^.]+$/,'');newContent.value=await f.text();showAdd.value=true;}}
onMounted(async()=>{try{[health.value,graph.value,docs.value]=await Promise.all([api('/health'),api('/graph'),api('/knowledge')]);await refreshHistory();const id=new URLSearchParams(location.search).get('run');if(id)await loadRun(id);}catch(e){error.value='后端连接异常：'+(e as Error).message;}});
onUnmounted(()=>{source?.close();stopPlayback();});
</script>
<template>
<div class="workflow-studio">
<div :class="['studio-shell',{'presentation-mode':presentationMode}]">
  <aside class="studio-sidebar">
    <div class="brand"><div class="brand-mark"><Layers :size="22"/></div><div><strong>座舱 Agent 工作台</strong><span>工作流编排演示</span></div></div>
    <nav class="nav"><button :class="{active:page==='studio'}" @click="page='studio'"><Workflow :size="17"/> 编排工作台</button><button :class="{active:page==='knowledge'}" @click="page='knowledge'"><BookOpen :size="17"/> 演示知识</button><button :class="{active:page==='guide'}" @click="page='guide'"><Presentation :size="17"/> 分享导览</button></nav>
    <div class="studio-sidebar-caption">演示场景 <span>8 workflows</span></div>
    <div class="scenarios"><button v-for="s in scenarios" :key="s.id" :aria-label="`${s.n} ${s.title}`" :class="['scenario',{active:scenario===s.id}]" @click="choose(s)"><span class="scenario-number">{{s.n}}</span><div><strong>{{s.title}}</strong><span>{{s.desc}}</span></div><ChevronRight :size="14"/></button></div>
    <div class="studio-sidebar-caption history-label">最近运行 <button @click="refreshHistory" aria-label="刷新运行历史"><RotateCcw :size="12"/></button></div>
    <div class="history-list"><button v-for="h in history.slice(0,7)" :key="h.id" :class="{selected:run?.id===h.id}" @click="loadRun(h.id)"><i :class="h.status.toLowerCase()"></i><span>{{h.message}}</span><Clock :size="12"/></button><p v-if="!history.length">运行场景后将在这里保留记录</p></div>
    <div class="studio-sidebar-footer"><span class="online-dot" :class="{off:!health}"></span><span>{{health?'Graph Runtime 已连接':'等待后端连接'}}</span><small>COCKPIT MODULE</small></div>
  </aside>
  <main>
    <header class="topbar"><div class="breadcrumbs">座舱 Agent 工作台 <ChevronRight :size="13"/><b>{{page==='studio'?'编排工作台':page==='knowledge'?'知识库':'分享导览'}}</b></div><div class="topbar-actions"><button class="presentation-toggle" :aria-pressed="presentationMode" @click="presentationMode=!presentationMode"><Presentation :size="14"/>{{presentationMode?'退出演示':'演示模式'}}</button><span class="pill"><i></i> Spring AI Alibaba Graph</span><div class="mode-control" aria-label="模型调用模式"><button :class="{active:mode==='offline'}" @click="mode='offline'">离线讲解</button><button :class="{active:mode==='live'}" @click="mode='live'">DeepSeek 在线</button></div></div></header>
    <div v-if="error" class="error-banner" role="alert"><AlertCircle :size="17"/>{{error}}<button @click="error=''"><X :size="16"/></button></div>
    <template v-if="page==='studio'">
      <section class="page-heading"><div><div class="eyebrow">WORKFLOW ORCHESTRATION</div><h1>智能客服工作流</h1><p>八个场景共享客服主图：观察意图、检索、工具与人工分支，并回放每一步的输入输出。</p></div><div class="run-controls"><button v-if="canStop" class="button secondary" :disabled="submitting" @click="cancel"><Square :size="14"/>停止当前工作流</button><button v-if="!busy" class="button primary" :disabled="!message.trim()" @click="submit">运行当前输入 <Play :size="14"/></button><a v-if="run" :href="`${API_BASE}/api/workflow/runs/${run.id}/export`" class="button secondary" download><Download :size="15"/> 导出链路</a><span class="status-tag" :class="submitting?'running':run?.status.toLowerCase()??'idle'"><Radio :size="13"/>{{submitting?'正在创建运行':run?statusText[run.status]:'等待运行'}}</span></div></section>
      <div class="demo-switcher" aria-label="重点演示选择"><button :class="{active:caseView==='overview'}" @click="caseView='overview';setScope('all')">工作流总览</button><button :class="{active:caseView==='tool'}" :disabled="busy" @click="choose(scenarios[3]!)"><span>重点一</span> 工具调用循环 <small>先查订单 再试算退款</small></button><button :class="{active:caseView==='multi'}" :disabled="busy" @click="choose(scenarios[4]!)"><span>重点二</span> 多 Agent 协作 <small>拆解问题 调用专家 汇总</small></button></div>
      <ExecutionFocus v-if="caseView!=='overview'" :route="caseView" :events="focusEvents" :budget="run?.maxIterations??maxIterations" :focused-path="tracePath" @select="selectEvent" @scope="setScope"/>
      <div class="workspace">
        <section class="workflow-panel panel"><div class="panel-heading"><div><Workflow :size="17"/><strong>客服工作流</strong><span class="subtle">CustomerServiceGraph</span></div><span class="sse-state"><i :class="{connected:sseConnected}"></i>{{sseConnected?'实时事件流':'事件可回放'}}</span></div>
          <div class="metrics"><div><span>执行节点</span><strong>{{steps.length}}<small>steps</small></strong></div><div><span>端到端耗时</span><strong>{{duration}}</strong></div><div><span>模型 Token</span><strong>{{tokens.toLocaleString()}}</strong></div><div><span>运行模式</span><strong class="mode-metric">{{run?.mode==='live'?'DeepSeek':'离线讲解'}}<small>{{run?.mode==='live'?'REAL API':'REAL GRAPH'}}</small></strong></div></div>
          <WorkflowGraph :nodes="graph.nodes" :edges="graph.edges" :events="visibleEvents" :selected="selectedNode" @select="selectNode"/>
          <div class="replay-bar"><button aria-label="上一步节点" title="上一步已完成节点" :disabled="!events.length" @click="step('previous')">‹</button><button @click="replay" :disabled="!events.length" :aria-label="playing?'暂停回放':'回放执行链路'"><Pause v-if="playing" :size="15"/><Play v-else :size="15"/></button><button aria-label="下一步节点" title="下一步已完成节点" :disabled="!events.length" @click="step('next')">›</button><span>{{trackLive?'实时':'回放'}} {{cursor}} / {{events.length}}</span><input type="range" aria-label="链路回放进度" min="0" :max="events.length" :value="trackLive?events.length:cursor" @input="seek"><button :class="{active:trackLive}" @click="stopPlayback();trackLive=true;cursor=events.length;selected=null">Live</button></div>
        </section>
        <aside class="inspector panel"><div class="panel-heading"><div><Activity :size="17"/><strong>执行详情</strong></div><span class="subtle">Inspector</span></div><div class="inspector-title"><span class="node-label">{{activeDetail?.path??'root'}}</span><h2>{{title(selectedNode)}}</h2><p>{{selectedNode}} <span v-if="activeDetail?.durationMs!==undefined">· {{activeDetail.durationMs}} ms</span></p></div><div class="detail-tabs"><button :class="{active:detailTab==='output'}" @click="detailTab='output'">输出结果</button><button :class="{active:detailTab==='input'}" @click="detailTab='input'">输入上下文</button><button :class="{active:detailTab==='state'}" @click="detailTab='state'">当前状态</button></div><p v-if="detailTab==='state'" class="state-note">这里是运行的最新状态。单步回放请查看节点的输入和输出。</p>
          <pre v-if="activeDetail||detailTab==='state'" class="json-view">{{pretty(detailTab==='state'?run?.state:detailTab==='input'?detailInput:activeDetail?.output??activeDetail?.error)}}</pre><div v-else class="empty-inspector"><FileJson :size="32"/><strong>从一个节点开始</strong><p>运行任意场景，然后点击工作流节点或事件，查看真实输入与输出。</p></div>
          <div v-if="activeDetail" class="span-meta"><span>SPAN {{activeDetail.spanId.slice(0,8)}}</span><span>EVENT #{{activeDetail.seq}}</span><span v-if="activeDetail.parentSpanId">PARENT {{activeDetail.parentSpanId.slice(0,8)}}</span></div>
        </aside>
      </div>
      <section class="composer panel"><div class="composer-top"><span><i></i> 客户输入</span><div class="composer-config"><label>知识库 <select v-model="knowledgeBase"><option value="support">客服产品库</option><option value="engineering">工程知识库</option><option value="all">全部知识库</option></select></label><label>循环预算 <select v-model.number="maxIterations"><option v-for="i in [2,3,4,5,6]" :key="i" :value="i">{{i}} 轮</option></select></label><label>演示故障 <select v-model="fault"><option value="none">无</option><option value="retrieval_gap">首次检索缺失</option><option value="tool_timeout">首次工具超时</option><option value="review_rework">首轮评审返工</option></select></label></div></div>
        <textarea v-model="message" aria-label="客户输入" placeholder="输入产品问题、订单号、报表需求，或者一次组合任务…" rows="2" maxlength="4000" @keydown.ctrl.enter.prevent="submit"/>
        <div v-if="image" class="attachment"><img :src="image" alt="待分析的客户图片"><div><b>{{imageName}}</b><small>{{mode==='live'?'发送真实图片到视觉模型':'离线核对内置图片样例注释'}}</small></div><button aria-label="移除图片" @click="image='';imageName=''"><X :size="14"/></button></div>
        <div class="composer-bottom"><div><label class="upload-button"><ImagePlus :size="17"/><span>附加图片</span><input type="file" accept="image/png,image/jpeg" @change="upload"></label><span class="composer-note">{{mode==='live'?`真实 DeepSeek API · ${health?.model?.model??'deepseek-flash'}`:'模型为显式离线模板 · Graph / RAG / MCP / HTTP 工具仍实际执行'}}</span></div><button v-if="canStop" class="button secondary" :disabled="submitting" @click="cancel"><Square :size="14"/>停止</button><button v-if="!busy" class="button primary" :disabled="!message.trim()" @click="submit">运行工作流 <ArrowUp :size="16"/></button></div>
      </section>
      <section v-if="run?.status==='WAITING_HUMAN'" class="human-panel panel"><div><span class="pill red">HUMAN IN THE LOOP</span><h2>等待人工处理意见</h2><p>交接材料已保存。补充处理意见后，从恢复子图继续，不重复前序工具调用。</p></div><textarea v-model="humanReply" aria-label="人工处理意见" placeholder="例如：已核实订单，符合7天未使用退货条件。告知顾客提交退货申请，不承诺退款已完成。" rows="3"/><button class="button primary" :disabled="!humanReply.trim()" @click="resume">提交并恢复 <ArrowRight :size="15"/></button></section>
      <div class="results-grid"><section class="answer-panel panel"><div class="panel-heading"><div><Check :size="17"/><strong>客户答复</strong></div><span class="subtle">Final response</span></div><div class="answer-content"><AnswerContent v-if="run?.answer" :text="run.answer"/><div v-else class="answer-placeholder"><p>{{busy?'工作流正在收集证据与执行工具…':'最终回答将在这里出现。'}}</p><span>查看旁边的事件流，理解答案是怎样被构建出来的。</span></div></div></section><section class="timeline-panel panel"><div class="panel-heading"><div><Layers :size="17"/><strong>调用链与事件</strong></div><span class="subtle">{{timeline.length}} 条</span></div><div class="trace-filters"><select v-model="tracePath" aria-label="调用路径" @change="setScope(tracePath)"><option value="all">全部调用路径</option><option v-for="path in tracePaths" :key="path" :value="path">{{scopeName(path)}}</option></select><select v-model="eventFilter" aria-label="事件筛选"><option value="steps">关键步骤</option><option value="all">全部事件</option></select></div><div class="timeline"><button v-for="e in timeline" :key="e.seq" :class="['timeline-event',{selected:selected?.seq===e.seq}]" @click="selectEvent(e)"><i :class="e.type.includes('failed')?'failed':e.type.includes('completed')?'completed':''"></i><span class="event-number">{{String(e.seq).padStart(2,'0')}}</span><div><strong>{{title(e.nodeId)}} <span>{{e.type}}</span></strong><small>{{e.path}}<span v-if="e.purpose"> · {{e.purpose}}</span></small></div><span>{{e.durationMs!==undefined?e.durationMs+'ms':new Date(e.timestamp).toLocaleTimeString('zh-CN',{hour12:false})}}</span></button><div v-if="!timeline.length" class="timeline-empty">后端事件将按真实发生顺序显示于此</div></div></section></div>
    </template>
    <template v-else-if="page==='knowledge'"><section class="page-heading"><div><div class="eyebrow">KNOWLEDGE & EVIDENCE</div><h1>客服知识库</h1><p>本地持久化向量索引 · 2048 维 hashed n-gram · 余弦检索 · 每次命中保留来源与分数。</p></div><button class="button primary" @click="showAdd=!showAdd"><Plus :size="16"/> 添加知识</button></section><div class="knowledge-toolbar"><Search :size="18"/><input v-model="kbSearch" placeholder="搜索标题、正文或知识库…" aria-label="搜索知识"><span>{{filteredDocs.length}} 条知识</span><label class="button secondary">导入 TXT / MD<input hidden type="file" accept=".txt,.md" @change="uploadText"></label></div><section v-if="showAdd" class="knowledge-form panel"><h2>新增检索文档</h2><input v-model="newTitle" placeholder="文档标题" aria-label="知识标题" maxlength="100"><select v-model="knowledgeBase"><option value="support">客服产品库</option><option value="engineering">工程知识库</option></select><textarea v-model="newContent" rows="6" placeholder="知识正文（保存后自动向量化）" aria-label="知识正文" maxlength="20000"/><button class="button primary" :disabled="!newTitle.trim()||!newContent.trim()" @click="addKnowledge">保存并建立向量</button></section><div class="knowledge-grid"><article v-for="d in filteredDocs" :key="d.id" class="knowledge-card panel"><div><span class="pill">{{d.id}}</span><small>{{d.knowledgeBase==='support'?'客服产品库':'工程知识库'}}</small></div><h2>{{d.title}}</h2><p>{{d.content}}</p><footer><span>{{d.version}}</span><span v-for="tag in d.tags" :key="tag">#{{tag}}</span></footer></article></div><div class="info-note">这是可运行的轻量向量检索演示，不等同于生产语义 Embedding。替换 KnowledgeIndex 的 embed 与 search 适配器即可接入语义模型及向量数据库。</div></template>
    <template v-else>
      <section class="page-heading"><div><div class="eyebrow">20 MINUTES · SHARING GUIDE</div><h1>从智能体到工作流编排</h1><p>概念讲清楚，再看两次真实执行。正文约18分钟，留2分钟余量。</p></div></section>
      <div class="talk-timing"><span>智能体 2′</span><ArrowRight :size="13"/><span>Graph 3′</span><ArrowRight :size="13"/><span>客服设计 2′</span><ArrowRight :size="13"/><b>工具循环 5′</b><ArrowRight :size="13"/><b>多 Agent 4′</b><ArrowRight :size="13"/><span>Coding agent 2′</span></div>
      <div class="guide-grid">
        <article class="panel"><span class="guide-number">01</span><h2>智能体和单次问答差在哪里</h2><p>模型可以给出查询订单的计划，但实际订单状态来自业务系统。智能体需要执行动作，读回结果，再决定下一步。提示词仍然重要，只是它负责的是其中一次判断。</p><pre>目标 → 选择动作 → 实际执行
→ 读取结果 → 继续或结束</pre></article>
        <article class="panel"><span class="guide-number">02</span><h2>Graph 把执行过程写出来</h2><p>节点做事，状态保存已知信息，边决定接下来去哪。返回上一节点就形成循环。LangGraph和Spring AI Alibaba Graph都采用这种编排方式，框架提供运行机制，业务逻辑仍由我们设计。</p><pre>Node：一次检索或模型判断
State：订单 证据 工具返回
Edge：继续调用 或结束</pre></article>
        <article class="panel"><span class="guide-number">03</span><h2>先按企业业务划分职责</h2><p>问政策查知识库，问订单查业务接口，问报表用MCP，组合问题拆给专家。赔偿争议交人工。每条路都需要定义数据来源和完成条件，而不是只指定一个角色提示词。</p><pre>意图识别 → 对应业务分支
业务结果 → 回答 → 检查</pre></article>
        <article class="panel"><span class="guide-number">04</span><h2>演示订单查询与退款试算</h2><p>先查SO20261001。第一轮获得签收状态，第二轮才试算可退金额。回放时停在tool_execute，再看下一轮tool_plan的输入，说明工具结果确实改变了上下文。</p><button class="button primary" @click="choose(scenarios[3]!)">加载工具循环案例 <ArrowRight :size="15"/></button></article>
        <article class="panel"><span class="guide-number">05</span><h2>演示三个专家子图</h2><p>把政策、退款金额和销售报表拆成三个任务。查看专家卡片，对比知识专家的两步检索回答和订单专家内部的工具循环。本例顺序调度，多Agent不等于必须并行。</p><button class="button primary" @click="choose(scenarios[4]!)">加载多 Agent 案例 <ArrowRight :size="15"/></button></article>
        <article class="panel"><span class="guide-number">06</span><h2>Coding agent 用的是同类循环</h2><p>读代码、修改、运行测试、根据失败继续修正，也是在观察和行动之间循环。Harness是包围模型的运行支撑，包括工具、上下文管理和检查机制。可以用反馈控制的视角理解它，但这不是对技术起源的断言。</p><pre>修改代码 → 测试 → 失败信息
→ 下一轮修改 → 再验证</pre></article>
      </div>
      <div class="reference-panel panel"><h2>参考资料</h2><a href="https://docs.langchain.com/oss/python/langgraph/graph-api" target="_blank" rel="noreferrer">LangGraph Graph API <ExternalLink :size="14"/></a><a href="https://java2ai.com/docs/frameworks/graph-core/quick-start/" target="_blank" rel="noreferrer">Spring AI Alibaba Graph <ExternalLink :size="14"/></a><a href="https://developers.openai.com/cookbook/examples/codex/build_iterative_repair_loops_with_codex" target="_blank" rel="noreferrer">Codex 闭环修复示例 <ExternalLink :size="14"/></a><a href="https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents" target="_blank" rel="noreferrer">Anthropic 长任务 Harness <ExternalLink :size="14"/></a><p>本项目使用本地订单和报表样例，MCP与HTTP调用实际执行。节点详情显示请求和结果，不显示模型内部思维过程。</p></div>
    </template>
    <footer class="page-footer"><span>座舱 Agent 工作台 · 技术分享演示</span><span>Vue 3 · Spring AI Alibaba Graph · DeepSeek · MCP</span></footer>
  </main>
</div>
</div>
</template>

<style src="./workflow.css"></style>
