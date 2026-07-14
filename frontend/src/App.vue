<template>
  <el-container class="shell">
    <el-aside width="236px" class="side">
      <div class="brand">
        <span class="logo">AI</span>
        <div>
          <strong>Enterprise AI Cockpit</strong>
          <small>Spring Boot + Vue 3</small>
        </div>
      </div>
      <el-menu :default-active="active" class="menu" @select="active = $event">
        <el-menu-item index="cockpit">AI Cockpit Chat</el-menu-item>
        <el-menu-item index="knowledge">Knowledge Base</el-menu-item>
        <el-menu-item index="reports">Data & Reports</el-menu-item>
        <el-menu-item index="settings">MCP / Speech</el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div>
          <h1>{{ pageTitle }}</h1>
          <p>Enterprise knowledge-base chat, dynamic reports, tools, and speech integration MVP.</p>
        </div>
        <el-space>
          <el-tag type="success">{{ health?.status ?? 'loading' }}</el-tag>
          <el-tag>{{ health?.mode ?? 'mock' }}</el-tag>
          <el-tag type="info">{{ health?.repository ?? 'repository' }}</el-tag>
          <el-button @click="refreshAll">Refresh</el-button>
        </el-space>
      </el-header>

      <el-main>
        <section v-if="active === 'cockpit'" class="grid cockpit-grid">
          <el-card class="chat-card">
            <template #header>
              <div class="card-head"><span>Streaming RAG Chat</span><el-switch v-model="chat.enableChart" active-text="Chart" /></div>
            </template>
            <div class="messages">
              <div v-for="(m, i) in messages" :key="i" :class="['message', m.role]">
                <strong>{{ m.role === 'user' ? 'You' : 'Assistant' }}</strong>
                <p>{{ m.content }}</p>
              </div>
            </div>
            <el-form @submit.prevent="sendChat" class="ask">
              <el-select v-model="chat.knowledgeBaseIds" multiple placeholder="Select KB" style="width: 280px">
                <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
              </el-select>
              <el-input v-model="chat.message" placeholder="Ask for a sales chart from the knowledge base" clearable @keyup.enter="sendChat" />
              <el-button :loading="loading.chat" type="primary" @click="sendChat">Send</el-button>
              <el-button @click="mockVoiceInput">Voice Input</el-button>
            </el-form>
          </el-card>

          <el-card>
            <template #header>Dynamic Chart / References</template>
            <div ref="chartRef" class="chart"></div>
            <el-divider />
            <el-collapse v-if="references.length">
              <el-collapse-item v-for="ref in references" :key="ref.id" :title="`${ref.title} · score ${ref.score.toFixed(1)}`">
                <p>{{ ref.content }}</p>
                <el-tag v-for="(v, k) in ref.metadata" :key="k" class="meta">{{ k }}={{ v }}</el-tag>
              </el-collapse-item>
            </el-collapse>
            <el-empty v-else description="No references yet" />
            <el-button :disabled="!lastAssistant" class="speak" @click="speak">Speak Answer</el-button>
          </el-card>
        </section>

        <section v-else-if="active === 'knowledge'" class="grid">
          <el-card>
            <template #header>Knowledge Bases</template>
            <el-form label-position="top">
              <el-form-item label="Name"><el-input v-model="kbForm.name" /></el-form-item>
              <el-form-item label="Code"><el-input v-model="kbForm.code" /></el-form-item>
              <el-form-item label="Description"><el-input v-model="kbForm.description" type="textarea" /></el-form-item>
              <el-button type="primary" @click="createKnowledgeBase">Create KB</el-button>
            </el-form>
            <el-table :data="knowledgeBases" class="table">
              <el-table-column prop="name" label="Name" />
              <el-table-column prop="code" label="Code" />
              <el-table-column prop="documentCount" label="Docs" width="90" />
            </el-table>
          </el-card>

          <el-card>
            <template #header>Import Documents & Metadata</template>
            <el-form label-position="top">
              <el-form-item label="Target KB">
                <el-select v-model="upload.knowledgeBaseId" placeholder="Select KB">
                  <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
                </el-select>
              </el-form-item>
              <el-form-item label="Quick Text Import"><el-input v-model="upload.title" placeholder="Document title" /><el-input v-model="upload.content" type="textarea" :rows="4" placeholder="Document content" /></el-form-item>
              <el-form-item label="Metadata JSON"><el-input v-model="upload.metadata" placeholder='{"category":"sales","region":"east"}' /></el-form-item>
              <el-button type="primary" @click="importText">Import Text</el-button>
            </el-form>
            <el-upload :auto-upload="false" multiple :on-change="onFileChange" class="uploader">
              <el-button>Select PDF/Word/Excel/Markdown/CSV</el-button>
            </el-upload>
            <el-button :disabled="!files.length" @click="uploadFiles">Batch Upload</el-button>
            <el-table :data="documents" class="table">
              <el-table-column prop="title" label="Document" />
              <el-table-column prop="chunks" label="Chunks" width="80" />
              <el-table-column label="Metadata"><template #default="s"><el-tag v-for="(v,k) in s.row.metadata" :key="k" class="meta">{{ k }}={{ v }}</el-tag></template></el-table-column>
            </el-table>
          </el-card>
        </section>

        <section v-else-if="active === 'reports'" class="grid">
          <el-card>
            <template #header>Data Sources</template>
            <el-form label-position="top">
              <el-form-item label="Name"><el-input v-model="dsForm.name" /></el-form-item>
              <el-form-item label="Type"><el-select v-model="dsForm.type"><el-option label="HTTP API" value="HTTP" /><el-option label="JDBC Database" value="JDBC" /><el-option label="File" value="FILE" /></el-select></el-form-item>
              <el-form-item label="Endpoint"><el-input v-model="dsForm.endpoint" /></el-form-item>
              <el-form-item label="SQL/API Params"><el-input v-model="dsForm.queryText" type="textarea" /></el-form-item>
              <el-button type="primary" @click="createDataSource">Save Data Source</el-button>
            </el-form>
            <el-table :data="dataSources" class="table"><el-table-column prop="name" label="Name" /><el-table-column prop="type" label="Type" /><el-table-column prop="endpoint" label="Endpoint" /></el-table>
          </el-card>

          <el-card>
            <template #header>Scheduled Reports</template>
            <el-form label-position="top">
              <el-form-item label="Report Name"><el-input v-model="reportForm.name" /></el-form-item>
              <el-form-item label="Cron"><el-input v-model="reportForm.cron" /></el-form-item>
              <el-form-item label="Data Source Key"><el-input v-model="reportForm.dataSourceKey" /></el-form-item>
              <el-form-item label="Target KB"><el-select v-model="reportForm.knowledgeBaseId"><el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" /></el-select></el-form-item>
              <el-form-item label="Prompt"><el-input v-model="reportForm.prompt" type="textarea" /></el-form-item>
              <el-form-item label="Dimensions"><el-input v-model="reportForm.dimensions" /></el-form-item>
              <el-button type="primary" @click="createReportTemplate">Save Template</el-button>
            </el-form>
            <el-table :data="reportTemplates" class="table">
              <el-table-column prop="name" label="Template" />
              <el-table-column prop="cron" label="Cron" />
              <el-table-column width="120" label="Action"><template #default="s"><el-button size="small" @click="runReport(s.row.id)">Run</el-button></template></el-table-column>
            </el-table>
            <el-divider />
            <el-table :data="reportRuns" class="table"><el-table-column prop="name" label="Report" /><el-table-column prop="status" label="Status" /><el-table-column prop="createdAt" label="Time" /></el-table>
          </el-card>
        </section>

        <section v-else class="grid">
          <el-card>
            <template #header>MCP Tool Calling</template>
            <el-alert type="info" show-icon :closable="false" title="天气问题会通过 Spring AI MCP Client 的 STDIO queryWeather 工具调用示例服务；慢任务仍应预计算到知识库。" />
            <el-descriptions :column="1" border class="table">
              <el-descriptions-item label="Vector Store">{{ health?.vectorStore ?? 'loading' }}</el-descriptions-item>
              <el-descriptions-item label="MCP Status">{{ health?.mcp ?? 'loading' }}</el-descriptions-item>
              <el-descriptions-item label="Test Endpoint">GET /api/mcp/weather?city=常州</el-descriptions-item>
            </el-descriptions>
          </el-card>
          <el-card>
            <template #header>Speech Recognition & TTS</template>
            <p>The MVP uses a mock-openai-compatible provider. Replace SpeechService provider for real ASR/TTS.</p>
            <el-input v-model="ttsText" type="textarea" :rows="4" placeholder="Text to speak" />
            <el-button class="table" @click="speakText(ttsText)">Generate & Play</el-button>
          </el-card>
        </section>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import * as echarts from 'echarts';
import type { UploadFile } from 'element-plus';
import { ElMessage } from 'element-plus';
import { useRoute, useRouter } from 'vue-router';
import { api, streamChat } from './api';

type KnowledgeBase = { id: number; name: string; description: string; code: string; documentCount: number };
type DocumentRow = { id: number; title: string; chunks: number; metadata: Record<string, string> };
type Reference = { id: number; title: string; content: string; score: number; metadata: Record<string, string> };
  type Health = { status: string; mode: string; repository: string; vectorStore: string; mcp: string; knowledgeBases: number; documents: number; chunks: number; reports: number };
type DataSource = { id: number; name: string; type: string; endpoint: string; queryText: string };
type ReportTemplate = { id: number; name: string; cron: string };
type ReportRun = { id: number; name: string; status: string; createdAt: string; chartSpec: string };

const route = useRoute();
const router = useRouter();
type Section = 'cockpit' | 'knowledge' | 'reports' | 'settings';
const active = computed<Section>({
  get: () => route.path === '/' ? 'cockpit' : (route.path.slice(1) as Section),
  set: value => { void router.push(value === 'cockpit' ? '/' : `/${value}`); }
});
const health = ref<Health>();
const knowledgeBases = ref<KnowledgeBase[]>([]);
const documents = ref<DocumentRow[]>([]);
const dataSources = ref<DataSource[]>([]);
const reportTemplates = ref<ReportTemplate[]>([]);
const reportRuns = ref<ReportRun[]>([]);
const references = ref<Reference[]>([]);
const messages = ref<{ role: 'user' | 'assistant'; content: string }[]>([]);
const files = ref<File[]>([]);
const chartRef = ref<HTMLDivElement>();
const ttsText = ref('Welcome to Enterprise AI Cockpit');
const loading = reactive({ chat: false });
let chartInstance: echarts.ECharts | undefined;

const kbForm = reactive({ name: 'Enterprise KB', code: 'DEFAULT', description: 'Enterprise policies, metrics, and reports' });
const upload = reactive({ knowledgeBaseId: 0, title: 'Sales Daily', content: 'East sales amount is 120 and South sales amount is 95.', metadata: '{"category":"sales"}' });
const chat = reactive({ conversationId: '', message: 'Generate a sales chart', knowledgeBaseIds: [] as number[], enableTools: true, enableChart: true });
const dsForm = reactive({ name: 'Mock Sales API', type: 'HTTP', endpoint: 'https://example.com/sales', queryText: 'GET /sales?period=today' });
const reportForm = reactive({ name: 'Sales Daily', scheduleType: 'CRON', cron: '0 0 9 * * ?', dataSourceKey: 'mock-sales', knowledgeBaseId: 0, prompt: 'Analyze sales trend', dimensions: 'region,amount', enabled: true });

const pageTitle = computed(() => ({ cockpit: 'AI Cockpit Chat', knowledge: 'Knowledge Base', reports: 'Data & Reports', settings: 'MCP / Speech' }[active.value] ?? 'AI Cockpit Chat'));
const lastAssistant = computed(() => [...messages.value].reverse().find(m => m.role === 'assistant')?.content ?? '');

async function refreshAll() {
  try {
    const [nextHealth, nextKnowledgeBases, nextDocuments, nextDataSources, nextReportTemplates, nextReportRuns] = await Promise.all([
      api<Health>('/health'),
      api<KnowledgeBase[]>('/admin/knowledge-bases'),
      api<DocumentRow[]>('/admin/documents'),
      api<DataSource[]>('/admin/data-sources'),
      api<ReportTemplate[]>('/admin/report-templates'),
      api<ReportRun[]>('/admin/report-runs')
    ]);
    health.value = nextHealth;
    knowledgeBases.value = nextKnowledgeBases;
    documents.value = nextDocuments;
    dataSources.value = nextDataSources;
    reportTemplates.value = nextReportTemplates;
    reportRuns.value = nextReportRuns;
    if (!upload.knowledgeBaseId || !knowledgeBases.value.some(kb => kb.id === upload.knowledgeBaseId)) {
      upload.knowledgeBaseId = knowledgeBases.value[0]?.id ?? 0;
    }
    if (!reportForm.knowledgeBaseId || !knowledgeBases.value.some(kb => kb.id === reportForm.knowledgeBaseId)) {
      reportForm.knowledgeBaseId = knowledgeBases.value[0]?.id ?? 0;
    }
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error);
}

function parseJsonMap(text: string): Record<string, string> | null {
  if (!text.trim()) return {};
  try {
    const value: unknown = JSON.parse(text);
    if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error('Metadata must be a JSON object');
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, String(item)]));
  } catch (error) {
    ElMessage.error(`Metadata JSON is invalid: ${errorMessage(error)}`);
    return null;
  }
}

async function createKnowledgeBase() {
  await api('/admin/knowledge-bases', { method: 'POST', body: JSON.stringify(kbForm) });
  ElMessage.success('Knowledge base created');
  await refreshAll();
}

async function importText() {
  if (!upload.knowledgeBaseId) return ElMessage.warning('Create or select a knowledge base first');
  const metadata = parseJsonMap(upload.metadata);
  if (!metadata) return;
  await api('/admin/documents/text?knowledgeBaseId=' + upload.knowledgeBaseId, { method: 'POST', body: JSON.stringify({ title: upload.title, content: upload.content, metadata }) });
  ElMessage.success('Document imported');
  await refreshAll();
}

function onFileChange(file: UploadFile) {
  if (!file.raw || files.value.some(existing => existing.name === file.raw!.name && existing.size === file.raw!.size && existing.lastModified === file.raw!.lastModified)) return;
  files.value.push(file.raw);
}
async function uploadFiles() {
  if (!upload.knowledgeBaseId) return ElMessage.warning('Create or select a knowledge base first');
  const metadata = parseJsonMap(upload.metadata);
  if (!metadata) return;
  const form = new FormData();
  form.set('knowledgeBaseId', String(upload.knowledgeBaseId));
  form.set('metadata', JSON.stringify(metadata));
  files.value.forEach(f => form.append('files', f));
  await api('/admin/documents/batch-upload', { method: 'POST', body: form });
  files.value = [];
  ElMessage.success('Batch upload completed');
  await refreshAll();
}

async function sendChat() {
  const question = chat.message.trim();
  if (!question) return;
  loading.chat = true;
  messages.value.push({ role: 'user', content: question });
  const assistantIndex = messages.value.length;
  messages.value.push({ role: 'assistant', content: '' });
  references.value = [];
  try {
    await streamChat({ ...chat, message: question, metadataFilter: {}, knowledgeBaseIds: chat.knowledgeBaseIds.length ? chat.knowledgeBaseIds : knowledgeBases.value.map(k => k.id) }, async msg => {
      if (msg.event === 'meta') {
        const meta = parseJson(msg.data, {}) as { conversationId?: string };
        if (meta.conversationId) chat.conversationId = meta.conversationId;
      }
      if (msg.event === 'token') {
        const current = messages.value[assistantIndex];
        if (current) messages.value[assistantIndex] = { ...current, content: current.content + msg.data };
      }
      if (msg.event === 'references') references.value = parseJson(msg.data, []);
      if (msg.event === 'chart') renderChart(parseJson(msg.data, null));
      if (msg.event === 'error') ElMessage.error(msg.data);
    });
  } catch (error) {
    const message = `Chat failed: ${errorMessage(error)}`;
    const current = messages.value[assistantIndex];
    if (current) messages.value[assistantIndex] = { ...current, content: message };
    ElMessage.error(message);
  } finally {
    loading.chat = false;
  }
}

function parseJson<T>(text: string, fallback: T): T {
  try { return JSON.parse(text) as T; } catch { return fallback; }
}

function renderChart(spec: unknown) {
  nextTick(() => {
    if (!chartRef.value || !spec || typeof spec !== 'object') return;
    chartInstance ??= echarts.getInstanceByDom(chartRef.value) ?? echarts.init(chartRef.value);
    chartInstance.setOption(spec as echarts.EChartsOption, true);
  });
}

async function createDataSource() { await api('/admin/data-sources', { method: 'POST', body: JSON.stringify({ ...dsForm, config: {} }) }); await refreshAll(); }
async function createReportTemplate() { await api('/admin/report-templates', { method: 'POST', body: JSON.stringify(reportForm) }); await refreshAll(); }
async function runReport(id: number) { const run = await api<ReportRun>(`/admin/report-templates/${id}/run-now`, { method: 'POST' }); renderChart(parseJson(run.chartSpec, null)); await refreshAll(); }

async function speak() { await speakText(lastAssistant.value); }
async function speakText(text: string) {
  if (!text.trim()) return ElMessage.warning('There is no answer to speak');
  const res = await api<{ audioUrl: string }>('/speech/synthesize', { method: 'POST', body: JSON.stringify({ text }) });
  new Audio(res.audioUrl).play().catch(() => ElMessage.info('Browser blocked autoplay. Allow audio playback manually.'));
}
async function mockVoiceInput() {
  const fd = new FormData();
  fd.set('audio', new Blob(['Generate sales chart from voice'], { type: 'audio/webm' }), 'voice.webm');
  const res = await api<{ text: string }>('/speech/transcribe', { method: 'POST', body: fd });
  chat.message = res.text;
}

function resizeChart() { chartInstance?.resize(); }

onMounted(() => {
  window.addEventListener('resize', resizeChart);
  void refreshAll();
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeChart);
  chartInstance?.dispose();
  chartInstance = undefined;
});
</script>
