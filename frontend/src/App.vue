<template>
  <el-container class="shell">
    <el-aside width="236px" class="side">
      <div class="brand">
        <span class="logo">AI</span>
        <div>
          <strong>??????</strong>
          <small>Spring AI + Vue 3</small>
        </div>
      </div>
      <el-menu :default-active="active" class="menu" @select="active = $event">
        <el-menu-item index="cockpit">??????</el-menu-item>
        <el-menu-item index="knowledge">?????</el-menu-item>
        <el-menu-item index="reports">??????</el-menu-item>
        <el-menu-item index="settings">MCP / ????</el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div>
          <h1>{{ pageTitle }}</h1>
          <p>?????????????????????????? MVP</p>
        </div>
        <el-space>
          <el-tag type="success">{{ health?.status ?? 'loading' }}</el-tag>
          <el-tag>{{ health?.mode ?? 'mock' }}</el-tag>
          <el-button @click="refreshAll">??</el-button>
        </el-space>
      </el-header>

      <el-main>
        <section v-if="active === 'cockpit'" class="grid cockpit-grid">
          <el-card class="chat-card">
            <template #header>
              <div class="card-head"><span>?? RAG ??</span><el-switch v-model="chat.enableChart" active-text="????" /></div>
            </template>
            <div class="messages">
              <div v-for="(m, i) in messages" :key="i" :class="['message', m.role]">
                <strong>{{ m.role === 'user' ? '?' : '????' }}</strong>
                <p>{{ m.content }}</p>
              </div>
            </div>
            <el-form @submit.prevent="sendChat" class="ask">
              <el-select v-model="chat.knowledgeBaseIds" multiple placeholder="?????" style="width: 280px">
                <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
              </el-select>
              <el-input v-model="chat.message" placeholder="?????????????????" clearable @keyup.enter="sendChat" />
              <el-button :loading="loading.chat" type="primary" @click="sendChat">??</el-button>
              <el-button @click="mockVoiceInput">????</el-button>
            </el-form>
          </el-card>

          <el-card>
            <template #header>???? / ????</template>
            <div ref="chartRef" class="chart"></div>
            <el-divider />
            <el-collapse v-if="references.length">
              <el-collapse-item v-for="ref in references" :key="ref.id" :title="`${ref.title} ? score ${ref.score.toFixed(1)}`">
                <p>{{ ref.content }}</p>
                <el-tag v-for="(v, k) in ref.metadata" :key="k" class="meta">{{ k }}={{ v }}</el-tag>
              </el-collapse-item>
            </el-collapse>
            <el-empty v-else description="??????" />
            <el-button :disabled="!lastAssistant" class="speak" @click="speak">????</el-button>
          </el-card>
        </section>

        <section v-else-if="active === 'knowledge'" class="grid">
          <el-card>
            <template #header>???</template>
            <el-form label-position="top">
              <el-form-item label="??"><el-input v-model="kbForm.name" /></el-form-item>
              <el-form-item label="??"><el-input v-model="kbForm.code" /></el-form-item>
              <el-form-item label="??"><el-input v-model="kbForm.description" type="textarea" /></el-form-item>
              <el-button type="primary" @click="createKnowledgeBase">?????</el-button>
            </el-form>
            <el-table :data="knowledgeBases" class="table">
              <el-table-column prop="name" label="??" />
              <el-table-column prop="code" label="??" />
              <el-table-column prop="documentCount" label="???" width="90" />
            </el-table>
          </el-card>

          <el-card>
            <template #header>????? Metadata</template>
            <el-form label-position="top">
              <el-form-item label="?????">
                <el-select v-model="upload.knowledgeBaseId" placeholder="?????">
                  <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
                </el-select>
              </el-form-item>
              <el-form-item label="??????"><el-input v-model="upload.title" placeholder="????" /><el-input v-model="upload.content" type="textarea" rows="4" placeholder="????" /></el-form-item>
              <el-form-item label="Metadata(JSON)"><el-input v-model="upload.metadata" placeholder='{"category":"sales","region":"east"}' /></el-form-item>
              <el-button type="primary" @click="importText">????</el-button>
            </el-form>
            <el-upload :auto-upload="false" multiple :on-change="onFileChange" class="uploader">
              <el-button>?? PDF/Word/Excel/Markdown/CSV</el-button>
            </el-upload>
            <el-button :disabled="!files.length" @click="uploadFiles">????</el-button>
            <el-table :data="documents" class="table">
              <el-table-column prop="title" label="??" />
              <el-table-column prop="chunks" label="??" width="80" />
              <el-table-column label="Metadata"><template #default="s"><el-tag v-for="(v,k) in s.row.metadata" :key="k" class="meta">{{ k }}={{ v }}</el-tag></template></el-table-column>
            </el-table>
          </el-card>
        </section>

        <section v-else-if="active === 'reports'" class="grid">
          <el-card>
            <template #header>???</template>
            <el-form label-position="top">
              <el-form-item label="??"><el-input v-model="dsForm.name" /></el-form-item>
              <el-form-item label="??"><el-select v-model="dsForm.type"><el-option label="HTTP API" value="HTTP" /><el-option label="JDBC ???" value="JDBC" /><el-option label="??/??" value="FILE" /></el-select></el-form-item>
              <el-form-item label="??/???"><el-input v-model="dsForm.endpoint" /></el-form-item>
              <el-form-item label="SQL/API ??"><el-input v-model="dsForm.queryText" type="textarea" /></el-form-item>
              <el-button type="primary" @click="createDataSource">?????</el-button>
            </el-form>
            <el-table :data="dataSources" class="table"><el-table-column prop="name" label="??" /><el-table-column prop="type" label="??" /><el-table-column prop="endpoint" label="??" /></el-table>
          </el-card>

          <el-card>
            <template #header>????</template>
            <el-form label-position="top">
              <el-form-item label="????"><el-input v-model="reportForm.name" /></el-form-item>
              <el-form-item label="Cron"><el-input v-model="reportForm.cron" /></el-form-item>
              <el-form-item label="??? Key"><el-input v-model="reportForm.dataSourceKey" /></el-form-item>
              <el-form-item label="?????"><el-select v-model="reportForm.knowledgeBaseId"><el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" /></el-select></el-form-item>
              <el-form-item label="???"><el-input v-model="reportForm.prompt" type="textarea" /></el-form-item>
              <el-form-item label="????"><el-input v-model="reportForm.dimensions" /></el-form-item>
              <el-button type="primary" @click="createReportTemplate">????</el-button>
            </el-form>
            <el-table :data="reportTemplates" class="table">
              <el-table-column prop="name" label="??" />
              <el-table-column prop="cron" label="Cron" />
              <el-table-column width="120" label="??"><template #default="s"><el-button size="small" @click="runReport(s.row.id)">??</el-button></template></el-table-column>
            </el-table>
            <el-divider />
            <el-table :data="reportRuns" class="table"><el-table-column prop="name" label="??" /><el-table-column prop="status" label="??" /><el-table-column prop="createdAt" label="??" /></el-table>
          </el-card>
        </section>

        <section v-else class="grid">
          <el-card>
            <template #header>MCP Tool Calling</template>
            <el-alert type="info" show-icon :closable="false" title="????? Spring AI MCP Client starter???????????????????????????????????" />
            <el-descriptions :column="1" border class="table">
              <el-descriptions-item label="????">????????????????</el-descriptions-item>
              <el-descriptions-item label="?? MCP">?? application.yml / ?????? STDIO?SSE ? Streamable HTTP MCP Server</el-descriptions-item>
            </el-descriptions>
          </el-card>
          <el-card>
            <template #header>???????</template>
            <p>?? MVP ???? mock-openai-compatible Provider????? ASR/TTS ??????? SpeechService Provider?</p>
            <el-input v-model="ttsText" type="textarea" rows="4" placeholder="????????" />
            <el-button class="table" @click="speakText(ttsText)">?????</el-button>
          </el-card>
        </section>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue';
import * as echarts from 'echarts';
import type { UploadFile } from 'element-plus';
import { ElMessage } from 'element-plus';
import { api, streamChat } from './api';

type KnowledgeBase = { id: number; name: string; description: string; code: string; documentCount: number };
type DocumentRow = { id: number; title: string; chunks: number; metadata: Record<string, string> };
type Reference = { id: number; title: string; content: string; score: number; metadata: Record<string, string> };
type Health = { status: string; mode: string; knowledgeBases: number; documents: number; chunks: number; reports: number };
type DataSource = { id: number; name: string; type: string; endpoint: string; queryText: string };
type ReportTemplate = { id: number; name: string; cron: string };
type ReportRun = { id: number; name: string; status: string; createdAt: string; chartSpec: string };

const active = ref('cockpit');
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
const ttsText = ref('??????????');
const loading = reactive({ chat: false });

const kbForm = reactive({ name: '?????', code: 'DEFAULT', description: '????????????' });
const upload = reactive({ knowledgeBaseId: 0, title: '????', content: 'East sales amount is 120 and South sales amount is 95.', metadata: '{"category":"sales"}' });
const chat = reactive({ conversationId: '', message: 'Generate a sales chart', knowledgeBaseIds: [] as number[], enableTools: true, enableChart: true });
const dsForm = reactive({ name: 'Mock Sales API', type: 'HTTP', endpoint: 'https://example.com/sales', queryText: 'GET /sales?period=today' });
const reportForm = reactive({ name: 'Sales Daily', scheduleType: 'CRON', cron: '0 0 9 * * ?', dataSourceKey: 'mock-sales', knowledgeBaseId: 0, prompt: 'Analyze sales trend', dimensions: 'region,amount', enabled: true });

const pageTitle = computed(() => ({ cockpit: '??????', knowledge: '?????', reports: '??????', settings: 'MCP / ????' }[active.value]));
const lastAssistant = computed(() => [...messages.value].reverse().find(m => m.role === 'assistant')?.content ?? '');

async function refreshAll() {
  health.value = await api<Health>('/health');
  knowledgeBases.value = await api<KnowledgeBase[]>('/admin/knowledge-bases');
  documents.value = await api<DocumentRow[]>('/admin/documents');
  dataSources.value = await api<DataSource[]>('/admin/data-sources');
  reportTemplates.value = await api<ReportTemplate[]>('/admin/report-templates');
  reportRuns.value = await api<ReportRun[]>('/admin/report-runs');
  if (!upload.knowledgeBaseId && knowledgeBases.value[0]) upload.knowledgeBaseId = knowledgeBases.value[0].id;
  if (!reportForm.knowledgeBaseId && knowledgeBases.value[0]) reportForm.knowledgeBaseId = knowledgeBases.value[0].id;
}

function parseJsonMap(text: string) {
  try { return text ? JSON.parse(text) : {}; } catch { return {}; }
}

async function createKnowledgeBase() {
  await api('/admin/knowledge-bases', { method: 'POST', body: JSON.stringify(kbForm) });
  ElMessage.success('??????');
  await refreshAll();
}

async function importText() {
  await api('/admin/documents/text?knowledgeBaseId=' + upload.knowledgeBaseId, { method: 'POST', body: JSON.stringify({ title: upload.title, content: upload.content, metadata: parseJsonMap(upload.metadata) }) });
  ElMessage.success('?????');
  await refreshAll();
}

function onFileChange(file: UploadFile) { if (file.raw) files.value.push(file.raw); }
async function uploadFiles() {
  const form = new FormData();
  form.set('knowledgeBaseId', String(upload.knowledgeBaseId));
  form.set('metadata', upload.metadata);
  files.value.forEach(f => form.append('files', f));
  await api('/admin/documents/batch-upload', { method: 'POST', body: form });
  files.value = [];
  ElMessage.success('??????');
  await refreshAll();
}

async function sendChat() {
  if (!chat.message.trim()) return;
  loading.chat = true;
  messages.value.push({ role: 'user', content: chat.message });
  const assistant = { role: 'assistant' as const, content: '' };
  messages.value.push(assistant);
  references.value = [];
  try {
    await streamChat({ ...chat, metadataFilter: {}, knowledgeBaseIds: chat.knowledgeBaseIds.length ? chat.knowledgeBaseIds : knowledgeBases.value.map(k => k.id) }, async msg => {
      if (msg.event === 'meta') chat.conversationId = JSON.parse(msg.data).conversationId;
      if (msg.event === 'token') assistant.content += msg.data;
      if (msg.event === 'references') references.value = JSON.parse(msg.data);
      if (msg.event === 'chart') renderChart(JSON.parse(msg.data));
      if (msg.event === 'error') ElMessage.error(msg.data);
    });
  } finally { loading.chat = false; }
}

function renderChart(spec: any) {
  nextTick(() => {
    if (!chartRef.value) return;
    const chartInstance = echarts.getInstanceByDom(chartRef.value) ?? echarts.init(chartRef.value);
    chartInstance.setOption(spec, true);
  });
}

async function createDataSource() { await api('/admin/data-sources', { method: 'POST', body: JSON.stringify({ ...dsForm, config: {} }) }); await refreshAll(); }
async function createReportTemplate() { await api('/admin/report-templates', { method: 'POST', body: JSON.stringify(reportForm) }); await refreshAll(); }
async function runReport(id: number) { const run = await api<ReportRun>(`/admin/report-templates/${id}/run-now`, { method: 'POST' }); renderChart(JSON.parse(run.chartSpec)); await refreshAll(); }

async function speak() { await speakText(lastAssistant.value); }
async function speakText(text: string) {
  const res = await api<{ audioUrl: string }>('/speech/synthesize', { method: 'POST', body: JSON.stringify({ text }) });
  new Audio(res.audioUrl).play().catch(() => ElMessage.info('???????????????????'));
}
async function mockVoiceInput() {
  const fd = new FormData();
  fd.set('audio', new Blob(['Generate sales chart from voice'], { type: 'audio/webm' }), 'voice.webm');
  const res = await api<{ text: string }>('/speech/transcribe', { method: 'POST', body: fd });
  chat.message = res.text;
}

onMounted(refreshAll);
</script>
