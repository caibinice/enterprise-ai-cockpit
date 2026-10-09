<script setup lang="ts">
import {computed} from 'vue';
import {ArrowRight,CornerDownLeft,Check,Clock,ChevronRight} from 'lucide-vue-next';
import type {TraceEvent} from './types';
const props=defineProps<{route:'tool'|'multi';events:TraceEvent[];budget:number;focusedPath:string}>();
const emit=defineEmits<{select:[event:TraceEvent];scope:[path:string]}>();
const obj=(value:unknown):Record<string,any>=>value&&typeof value==='object'?value as Record<string,any>:{};
const executions=computed(()=>props.events.filter(event=>event.type==='node.completed'&&event.nodeId==='tool_execute'&&event.path==='root'));
const decision=computed(()=>obj([...props.events].reverse().find(event=>event.type==='node.completed'&&event.nodeId==='tool_plan'&&event.path==='root')?.output));
const decisionText=computed(()=>decision.value.toolNext==='done'?'结果齐全，退出循环':decision.value.toolNext==='human'?'停止循环，转交人工':decision.value.toolNext==='execute'?'已选择下一项工具':'等待模型选择工具');
const toolName=(event:TraceEvent)=>String(obj(obj(event.output).toolOutcome).name??'tool');
const outcome=(event:TraceEvent)=>obj(obj(event.output).toolOutcome);
function summary(event:TraceEvent){
  const value=outcome(event),result=obj(value.result);
  if(!value.ok)return String(result.error??'工具返回失败');
  if(value.name==='lookup_order')return `${result.product??'订单'} · ${result.status??''} · ¥${result.amount??'—'}`;
  return `可退商品金额 ¥${result.refundableAmount??'—'} · ${result.eligible?'符合条件':'交人工评估'}`;
}
const agentNames:Record<string,string>={rag:'知识专家',report:'报表专家',tool:'订单专家'};
const nodeNames:Record<string,string>={retrieve:'知识检索',report_mcp:'MCP 报表',tool_plan:'工具决策',tool_execute:'工具执行',agent_answer:'专家回答'};
const jobs=computed(()=>{
  const planning=[...props.events].reverse().find(event=>event.type==='node.completed'&&event.nodeId==='multi_plan');
  const tasks=obj(planning?.output).tasks;
  if(!Array.isArray(tasks))return [];
  return tasks.map((task,index)=>{
    const plannedPath=`root/${task.kind}Agent[${index+1}]`;
    const started=props.events.find(event=>event.type==='agent.started'&&event.path===plannedPath);
    const completed=props.events.find(event=>event.type==='agent.completed'&&event.path===plannedPath);
    const path=started?.path??plannedPath;
    return {task,index,path,started,completed,steps:props.events.filter(event=>event.type==='node.completed'&&event.path===path)};
  });
});
function selectJob(job:typeof jobs.value[number]){
  const event=job.completed??job.started;if(event){emit('scope',job.path);emit('select',event);}
}
</script>
<template>
  <section class="execution-focus panel" :aria-label="route==='tool'?'工具循环讲解':'多 Agent 协作讲解'">
    <div class="focus-heading"><div><span class="eyebrow">{{route==='tool'?'DEMO 01 · TOOL LOOP':'DEMO 02 · SPECIALIST GRAPHS'}}</span><h2>{{route==='tool'?'上一轮的结果，决定下一轮做什么':'主图分配任务，专家子图完成各自的工作'}}</h2></div><span class="focus-count">{{route==='tool'?`${executions.length} / ${budget} 轮执行`:`${jobs.filter(job=>job.completed).length} / ${jobs.length} 个专家返回`}}</span></div>
    <template v-if="route==='tool'">
      <div class="loop-explainer"><div><b>决定下一步</b><code>tool_plan</code><small>模型读取已取得的结果</small></div><ArrowRight :size="17"/><div><b>执行实际工具</b><code>tool_execute</code><small>程序校验参数并调用 HTTP</small></div><ArrowRight :size="17"/><div><b>结果回到上下文</b><code>observations / toolMessages</code><small>下一轮获得订单或试算结果</small></div></div>
      <div class="loop-feedback"><CornerDownLeft :size="15"/><span>{{decisionText}}</span><span>达到预算或缺少信息时交给人工</span></div>
      <div v-if="executions.length" class="execution-rounds"><button v-for="(event,index) in executions" :key="event.seq" :class="['round-card',{failed:!outcome(event).ok}]" @click="emit('select',event)"><div><span>第 {{index+1}} 轮</span><span>{{event.durationMs}} ms <ChevronRight :size="12"/></span></div><strong>{{toolName(event)}}</strong><p>{{summary(event)}}</p><small>EVENT #{{event.seq}} · 点击查看完整返回</small></button></div>
      <p v-else class="focus-empty">运行订单退款案例后，这里会按真实发生的顺序列出每次工具执行。结果是查询和试算，不是已执行退款。</p>
    </template>
    <template v-else>
      <p class="focus-description">先拆分“政策、订单金额、报表”三个问题，再依次调用独立的 CompiledGraph。点击专家或其中一步，查看该子图的输入输出。</p>
      <div v-if="jobs.length" class="specialist-grid"><article v-for="job in jobs" :key="job.path" :class="['specialist-card',{focused:focusedPath===job.path}]"><button class="specialist-heading" :disabled="!job.started" @click="selectJob(job)"><span class="specialist-number">0{{job.index+1}}</span><div><b>{{agentNames[job.task.kind]??job.task.kind}}</b><code>{{job.task.kind}}SpecialistGraph</code></div><Check v-if="job.completed" :size="15"/><Clock v-else :size="15"/></button><p>{{job.task.summary||job.task.question}}</p><span class="specialist-status">{{job.completed?(obj(job.completed.output).specialistStatus==='needs_human'?'需人工补充':'已返回'):job.started?'正在执行':'等待执行'}} · {{job.steps.length}} 个已完成节点</span><div v-if="job.steps.length" class="child-steps"><template v-for="(event,index) in job.steps" :key="event.seq"><ChevronRight v-if="index" :size="11"/><button @click="emit('scope',job.path);emit('select',event)" :aria-label="`查看${agentNames[job.task.kind]}的${nodeNames[event.nodeId]??event.nodeId}第${index+1}步`">{{nodeNames[event.nodeId]??event.nodeId}}</button></template></div></article></div>
      <p v-else class="focus-empty">运行组合请求后，这里会显示拆分出来的任务及每个专家的执行路径。</p>
    </template>
  </section>
</template>
