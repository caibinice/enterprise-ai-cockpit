<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">
          <span></span>
          <span></span>
          <span></span>
        </div>
        <div>
          <strong>Intelligence</strong>
          <small>Enterprise AI Cockpit</small>
        </div>
      </div>

      <nav class="primary-nav" aria-label="主导航">
        <button
          v-for="item in navigation"
          :key="item.id"
          type="button"
          :class="['nav-item', { active: active === item.id }]"
          @click="active = item.id"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
          <span v-if="item.id === 'knowledge' && health" class="nav-count">
            {{ health.knowledgeBases }}
          </span>
        </button>
      </nav>

      <div class="sidebar-spacer"></div>
      <div class="sidebar-runtime">
        <div class="runtime-line">
          <span :class="['status-dot', health?.status === 'ok' ? 'live' : 'pending']"></span>
          <div>
            <strong>{{ health?.status === 'ok' ? '系统在线' : '正在连接' }}</strong>
            <small>{{ modelStatusLabel }}</small>
          </div>
        </div>
        <div class="runtime-foot">
          <span>MySQL · pgvector</span>
          <span>v1.1</span>
        </div>
      </div>
    </aside>

    <section class="workspace">
      <header class="topbar">
        <div>
          <span class="eyebrow">{{ pageMeta.eyebrow }}</span>
          <h1>{{ pageMeta.title }}</h1>
          <p>{{ pageMeta.description }}</p>
        </div>
        <div class="topbar-actions">
          <div class="environment-pill">
            <span :class="['status-dot', health?.status === 'ok' ? 'live' : 'pending']"></span>
            Production
          </div>
          <el-tooltip content="刷新运行状态与业务数据">
            <button
              class="icon-button"
              type="button"
              :disabled="loading.refresh"
              aria-label="刷新"
              @click="refreshAll(true)"
            >
              <el-icon :class="{ spinning: loading.refresh }"><Refresh /></el-icon>
            </button>
          </el-tooltip>
        </div>
      </header>

      <main :class="['page-frame', { 'chat-frame': active === 'cockpit' }]">
        <Transition name="page" mode="out-in">
          <section
            v-if="active === 'cockpit'"
            key="cockpit"
            :class="['chat-layout', { 'has-visuals': charts.length }]"
          >
            <article class="glass-panel conversation-panel">
              <div class="panel-title-row">
                <div>
                  <span class="section-kicker">RAG WORKSPACE</span>
                  <h2>知识对话</h2>
                </div>
                <button
                  v-if="messages.length"
                  class="text-button"
                  type="button"
                  @click="clearConversation"
                >
                  清空会话
                </button>
              </div>

              <div class="runtime-toolbar">
                <div class="toolbar-field model-field">
                  <span>模型</span>
                  <el-select v-model="chat.model" popper-class="model-select-popper">
                    <el-option
                      v-for="model in chatOptions.models"
                      :key="model.id"
                      :label="model.name"
                      :value="model.id"
                    >
                      <div class="model-option">
                        <div>
                          <strong>{{ model.name }}</strong>
                          <small>{{ model.description }}</small>
                        </div>
                        <span>{{ model.badge }}</span>
                      </div>
                    </el-option>
                  </el-select>
                </div>

                <div class="toolbar-field kb-field">
                  <span>业务知识</span>
                  <el-select
                    v-model="chat.knowledgeBaseIds"
                    multiple
                    collapse-tags
                    collapse-tags-tooltip
                    placeholder="全部知识库"
                  >
                    <el-option
                      v-for="kb in knowledgeBases"
                      :key="kb.id"
                      :label="kb.name"
                      :value="kb.id"
                    >
                      <div class="select-option-line">
                        <span>{{ kb.name }}</span>
                        <small>{{ kb.businessType }}</small>
                      </div>
                    </el-option>
                  </el-select>
                </div>

                <el-popover placement="bottom-end" :width="430" trigger="click">
                  <template #reference>
                    <button
                      :class="['tool-selector', { selected: chat.mcpToolIds.length }]"
                      type="button"
                    >
                      <el-icon><Connection /></el-icon>
                      MCP
                      <span>{{ chat.mcpToolIds.length || '未选择' }}</span>
                    </button>
                  </template>
                  <div class="mcp-popover">
                    <div class="popover-heading">
                      <div>
                        <strong>MCP 工具</strong>
                        <small>仅授权给当前会话，由问题意图决定是否调用。</small>
                      </div>
                      <span :class="['status-dot', chatOptions.mcpEnabled ? 'live' : 'pending']"></span>
                    </div>
                    <el-checkbox-group v-model="chat.mcpToolIds">
                      <label
                        v-for="tool in chatOptions.mcpTools"
                        :key="tool.id"
                        class="mcp-choice"
                      >
                        <el-checkbox :value="tool.id" :disabled="!tool.available" />
                        <span class="tool-glyph">{{ toolGlyph(tool.id) }}</span>
                        <span>
                          <strong>{{ tool.name }}</strong>
                          <small>{{ tool.description }}</small>
                        </span>
                      </label>
                    </el-checkbox-group>
                    <el-empty
                      v-if="!chatOptions.mcpTools.length"
                      :image-size="48"
                      description="服务端尚未启用 MCP"
                    />
                  </div>
                </el-popover>
              </div>

              <div ref="messageScroller" class="conversation">
                <div v-if="!messages.length" class="conversation-empty">
                  <div class="ambient-orb">
                    <el-icon><MagicStick /></el-icon>
                  </div>
                  <span class="section-kicker">READY FOR CONTEXT</span>
                  <h3>从企业知识中找到答案</h3>
                  <p>
                    选择模型、业务知识库和 MCP 工具。回答会展示引用证据，
                    并保留当前会话的上下文。
                  </p>
                  <div class="suggestion-grid">
                    <button
                      v-for="suggestion in suggestions"
                      :key="suggestion"
                      type="button"
                      @click="useSuggestion(suggestion)"
                    >
                      <el-icon><ArrowRight /></el-icon>
                      {{ suggestion }}
                    </button>
                  </div>
                </div>

                <div
                  v-for="message in messages"
                  v-else
                  :key="message.id"
                  :class="['message-row', message.role]"
                >
                  <div class="message-avatar">
                    <el-icon v-if="message.role === 'assistant'"><MagicStick /></el-icon>
                    <span v-else>你</span>
                  </div>
                  <div class="message-content">
                    <div class="message-meta">
                      <strong>{{ message.role === 'assistant' ? 'Intelligence' : '你' }}</strong>
                      <span v-if="message.model">{{ modelName(message.model) }}</span>
                    </div>
                    <div
                      v-if="message.content"
                      class="message-body"
                      v-html="renderMessage(message.content)"
                    ></div>
                    <div v-else class="typing-indicator" aria-label="正在生成回答">
                      <span></span><span></span><span></span>
                    </div>
                  </div>
                </div>
              </div>

              <div v-if="agentPlanSummary" class="agent-plan-banner">
                <span><el-icon><MagicStick /></el-icon></span>
                <div>
                  <strong>智能体规划</strong>
                  <small>{{ agentPlanSummary }}</small>
                </div>
              </div>

              <div v-if="toolTraces.length" class="tool-trace-strip">
                <div
                  v-for="(trace, traceIndex) in toolTraces"
                  :key="`${trace.id}-${trace.status}-${traceIndex}`"
                  :class="['tool-trace', trace.status]"
                >
                  <span>{{ toolGlyph(trace.id) }}</span>
                  <div>
                    <strong>{{ trace.name }}</strong>
                    <small :title="trace.output">{{ toolTraceSummary(trace) }}</small>
                  </div>
                </div>
              </div>

              <div class="composer">
                <el-input
                  v-model="chat.message"
                  type="textarea"
                  resize="none"
                  :autosize="{ minRows: 2, maxRows: 5 }"
                  maxlength="12000"
                  placeholder="向企业知识库提问…"
                  @keydown="handleComposerKeydown"
                />
                <div class="composer-footer">
                  <div>
                    <el-switch v-model="chat.enableChart" size="small" />
                    <span>强制生成图表</span>
                    <span class="shortcut">Enter 发送 · Ctrl + Enter 换行</span>
                  </div>
                  <button
                    v-if="loading.chat"
                    class="send-button stop"
                    type="button"
                    aria-label="停止生成"
                    @click="stopChat"
                  >
                    <span></span>
                  </button>
                  <button
                    v-else
                    class="send-button"
                    type="button"
                    :disabled="!chat.message.trim()"
                    aria-label="发送"
                    @click="sendChat"
                  >
                    <el-icon><Top /></el-icon>
                  </button>
                </div>
              </div>
            </article>

            <aside class="context-column">
              <article class="glass-panel health-card">
                <div class="panel-title-row compact">
                  <div>
                    <span class="section-kicker">LIVE RUNTIME</span>
                    <h3>检索运行态</h3>
                  </div>
                  <span :class="['pulse-ring', health?.status === 'ok' ? 'live' : 'pending']"></span>
                </div>
                <div class="metric-grid">
                  <div>
                    <strong>{{ health?.knowledgeBases ?? '—' }}</strong>
                    <span>知识库</span>
                  </div>
                  <div>
                    <strong>{{ health?.documents ?? '—' }}</strong>
                    <span>文档</span>
                  </div>
                  <div>
                    <strong>{{ health?.chunks ?? '—' }}</strong>
                    <span>向量分块</span>
                  </div>
                </div>
                <div class="runtime-detail">
                  <span><i class="good"></i>MySQL 元数据</span>
                  <span><i :class="health?.vectorStore?.startsWith('connected') ? 'good' : 'warn'"></i>pgvector 检索</span>
                  <span><i :class="chatOptions.mcpEnabled ? 'good' : 'warn'"></i>MCP 工具链</span>
                </div>
              </article>

              <article class="glass-panel insight-card">
                <div class="insight-heading">
                  <div>
                    <span class="section-kicker">CONTEXT & VISUALS</span>
                    <h3>{{ analysisTab === 'charts' ? '动态分析' : '引用证据' }}</h3>
                  </div>
                  <div class="insight-tabs" role="tablist" aria-label="上下文面板">
                    <button
                      type="button"
                      :class="{ active: analysisTab === 'charts' }"
                      :disabled="!charts.length"
                      @click="analysisTab = 'charts'"
                    >
                      图表 <span>{{ charts.length }}</span>
                    </button>
                    <button
                      type="button"
                      :class="{ active: analysisTab === 'evidence' }"
                      @click="analysisTab = 'evidence'"
                    >
                      引用 <span>{{ references.length }}</span>
                    </button>
                  </div>
                </div>

                <div v-if="analysisTab === 'charts' && charts.length" class="chart-stack">
                  <section v-for="(chart, index) in charts" :key="chart.id" class="chart-item">
                    <div class="chart-item-heading">
                      <span>VISUAL {{ String(index + 1).padStart(2, '0') }}</span>
                      <strong>{{ chartTitle(chart.option) }}</strong>
                    </div>
                    <ChartView :option="chart.option" />
                  </section>
                </div>

                <div v-else-if="analysisTab === 'evidence' && references.length" class="reference-list">
                  <button
                    v-for="(reference, index) in references"
                    :key="reference.id"
                    type="button"
                    @click="activeReference = reference"
                  >
                    <span class="reference-index">{{ index + 1 }}</span>
                    <span>
                      <strong>{{ reference.title }}</strong>
                      <small>{{ excerpt(reference.content, 88) }}</small>
                    </span>
                    <em>{{ formatScore(reference.score) }}</em>
                  </button>
                </div>

                <div v-else class="small-empty">
                  <el-icon><Document /></el-icon>
                  <p>{{ analysisTab === 'charts' ? '提出可视化需求后，结构化图表会显示在这里。' : '回答后会在这里展示命中的知识片段。' }}</p>
                </div>
              </article>
            </aside>
          </section>

          <section v-else-if="active === 'knowledge'" key="knowledge" class="knowledge-page">
            <div class="knowledge-hero glass-panel">
              <div>
                <span class="section-kicker">KNOWLEDGE STUDIO</span>
                <h2>构建面向业务的知识空间</h2>
                <p>每个知识库拥有独立业务归属、文档与向量索引；对话可以按需组合多个知识库。</p>
              </div>
              <div class="hero-actions">
                <div class="secure-label"><el-icon><Lock /></el-icon>关键操作受保护</div>
                <el-button type="primary" :icon="Plus" @click="createKbOpen = true">
                  新建知识库
                </el-button>
              </div>
            </div>

            <div class="knowledge-layout">
              <aside class="glass-panel kb-browser">
                <div class="panel-title-row compact">
                  <h3>知识库</h3>
                  <span class="count-badge">{{ filteredKnowledgeBases.length }}</span>
                </div>
                <el-input v-model="kbSearch" :prefix-icon="Search" placeholder="搜索名称或业务" clearable />
                <div class="kb-list">
                  <button
                    v-for="kb in filteredKnowledgeBases"
                    :key="kb.id"
                    :class="['kb-card', { active: selectedKbId === kb.id }]"
                    type="button"
                    @click="selectedKbId = kb.id"
                  >
                    <span class="kb-icon">{{ businessGlyph(kb.businessType) }}</span>
                    <span>
                      <strong>{{ kb.name }}</strong>
                      <small>{{ kb.businessType }} · {{ kb.documentCount }} 篇文档</small>
                    </span>
                    <el-icon><ArrowRight /></el-icon>
                  </button>
                  <div v-if="!filteredKnowledgeBases.length" class="small-empty">
                    <el-icon><Collection /></el-icon>
                    <p>还没有知识库，先创建一个业务空间。</p>
                  </div>
                </div>
              </aside>

              <article class="glass-panel kb-detail">
                <template v-if="selectedKnowledgeBase">
                  <div class="kb-detail-head">
                    <div class="kb-title">
                      <span class="kb-icon large">{{ businessGlyph(selectedKnowledgeBase.businessType) }}</span>
                      <div>
                        <span class="business-chip">{{ selectedKnowledgeBase.businessType }}</span>
                        <h2>{{ selectedKnowledgeBase.name }}</h2>
                        <p>{{ selectedKnowledgeBase.description || '暂无描述' }}</p>
                      </div>
                    </div>
                    <div class="kb-actions">
                      <el-button :icon="Upload" @click="importOpen = true">导入文档</el-button>
                      <el-button
                        type="danger"
                        plain
                        :icon="Delete"
                        @click="deleteKnowledgeBase(selectedKnowledgeBase)"
                      >
                        删除
                      </el-button>
                    </div>
                  </div>

                  <div class="kb-stat-row">
                    <div><span>知识库代码</span><strong>{{ selectedKnowledgeBase.code }}</strong></div>
                    <div><span>文档数量</span><strong>{{ selectedKbDocuments.length }}</strong></div>
                    <div><span>分块数量</span><strong>{{ selectedKbChunkCount }}</strong></div>
                    <div><span>检索方式</span><strong>向量 + 关键词</strong></div>
                  </div>

                  <div class="document-heading">
                    <div>
                      <h3>知识文档</h3>
                      <p>上传 PDF、Word、Markdown、CSV，或直接粘贴结构化文本。</p>
                    </div>
                    <el-button text :icon="Refresh" @click="refreshAll()">刷新</el-button>
                  </div>
                  <div v-if="selectedKbDocuments.length" class="document-list">
                    <div v-for="document in selectedKbDocuments" :key="document.id" class="document-row">
                      <span class="document-icon"><el-icon><Document /></el-icon></span>
                      <button type="button" @click="openDocument(document)">
                        <strong>{{ document.title }}</strong>
                        <small>
                          {{ document.chunks }} 个分块 · {{ formatDate(document.createdAt) }}
                        </small>
                        <span class="metadata-line">
                          <em v-for="(value, key) in document.metadata" :key="key">
                            {{ key }} · {{ value }}
                          </em>
                        </span>
                      </button>
                      <el-dropdown trigger="click">
                        <button class="row-menu" type="button"><el-icon><MoreFilled /></el-icon></button>
                        <template #dropdown>
                          <el-dropdown-menu>
                            <el-dropdown-item @click="openDocument(document)">查看内容</el-dropdown-item>
                            <el-dropdown-item divided @click="deleteDocument(document)">删除文档</el-dropdown-item>
                          </el-dropdown-menu>
                        </template>
                      </el-dropdown>
                    </div>
                  </div>
                  <div v-else class="document-empty">
                    <div><el-icon><UploadFilled /></el-icon></div>
                    <h3>知识库还是空的</h3>
                    <p>导入第一份文档后，系统会自动切分并同步到 pgvector。</p>
                    <el-button type="primary" @click="importOpen = true">导入第一份文档</el-button>
                  </div>
                </template>
                <div v-else class="document-empty full">
                  <div><el-icon><Collection /></el-icon></div>
                  <h3>选择或创建知识库</h3>
                  <p>把企业知识按业务边界组织起来，再在对话中精确关联。</p>
                </div>
              </article>
            </div>
          </section>

          <section v-else-if="active === 'reports'" key="reports" class="operations-page">
            <div class="operations-grid">
              <article class="glass-panel operation-card">
                <div class="panel-title-row">
                  <div>
                    <span class="section-kicker">DATA CONNECTIONS</span>
                    <h2>业务数据源</h2>
                  </div>
                  <span class="count-badge">{{ dataSources.length }}</span>
                </div>
                <el-form label-position="top" class="clean-form">
                  <div class="form-pair">
                    <el-form-item label="名称"><el-input v-model="dsForm.name" /></el-form-item>
                    <el-form-item label="类型">
                      <el-select v-model="dsForm.type">
                        <el-option label="HTTP API" value="HTTP" />
                        <el-option label="JDBC Database" value="JDBC" />
                        <el-option label="File" value="FILE" />
                      </el-select>
                    </el-form-item>
                  </div>
                  <el-form-item label="连接地址"><el-input v-model="dsForm.endpoint" /></el-form-item>
                  <el-form-item label="只读查询 / 参数"><el-input v-model="dsForm.queryText" type="textarea" :rows="3" /></el-form-item>
                  <el-button type="primary" @click="createDataSource">保存数据源</el-button>
                </el-form>
                <div class="compact-list">
                  <div v-for="source in dataSources" :key="source.id">
                    <span class="source-icon"><el-icon><Link /></el-icon></span>
                    <span><strong>{{ source.name }}</strong><small>{{ source.type }} · {{ source.endpoint }}</small></span>
                  </div>
                  <div v-if="!dataSources.length" class="small-empty"><p>暂无数据源</p></div>
                </div>
              </article>

              <article class="glass-panel operation-card">
                <div class="panel-title-row">
                  <div>
                    <span class="section-kicker">AUTOMATED INSIGHT</span>
                    <h2>智能报告</h2>
                  </div>
                  <span class="count-badge">{{ reportTemplates.length }}</span>
                </div>
                <el-form label-position="top" class="clean-form">
                  <div class="form-pair">
                    <el-form-item label="报告名称"><el-input v-model="reportForm.name" /></el-form-item>
                    <el-form-item label="Cron"><el-input v-model="reportForm.cron" /></el-form-item>
                  </div>
                  <el-form-item label="关联知识库">
                    <el-select v-model="reportForm.knowledgeBaseId">
                      <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="分析提示词"><el-input v-model="reportForm.prompt" type="textarea" :rows="3" /></el-form-item>
                  <el-button type="primary" @click="createReportTemplate">保存模板</el-button>
                </el-form>
                <div class="compact-list report-list">
                  <div v-for="template in reportTemplates" :key="template.id">
                    <span class="source-icon violet"><el-icon><DataAnalysis /></el-icon></span>
                    <span><strong>{{ template.name }}</strong><small>{{ template.cron || '手动执行' }}</small></span>
                    <el-button size="small" @click="runReport(template.id)">立即运行</el-button>
                  </div>
                  <div v-if="!reportTemplates.length" class="small-empty"><p>暂无报告模板</p></div>
                </div>
              </article>
            </div>
          </section>

          <section v-else key="settings" class="runtime-page">
            <div class="runtime-hero glass-panel">
              <div>
                <span class="section-kicker">MODEL & TOOL CONTROL</span>
                <h2>模型与 MCP 运行中心</h2>
                <p>查看可用模型、授权工具与底层数据服务，不在浏览器保存任何 API 密钥。</p>
              </div>
              <div class="runtime-score">
                <strong>{{ runtimeScore }}%</strong>
                <span>运行就绪度</span>
              </div>
            </div>

            <div class="settings-grid">
              <article class="glass-panel settings-card">
                <div class="panel-title-row compact"><h3>DeepSeek 模型</h3><span class="status-label live">API 已配置</span></div>
                <div class="model-cards">
                  <div v-for="model in chatOptions.models" :key="model.id" :class="{ selected: chat.model === model.id }">
                    <span class="model-orb"></span>
                    <div><strong>{{ model.name }}</strong><small>{{ model.description }}</small></div>
                    <em>{{ model.badge }}</em>
                  </div>
                </div>
              </article>

              <article class="glass-panel settings-card">
                <div class="panel-title-row compact">
                  <h3>MCP 工具目录</h3>
                  <span :class="['status-label', chatOptions.mcpEnabled ? 'live' : 'warn']">
                    {{ chatOptions.mcpEnabled ? 'STDIO 在线' : '未启用' }}
                  </span>
                </div>
                <div class="tool-cards">
                  <div v-for="tool in chatOptions.mcpTools" :key="tool.id">
                    <span class="tool-glyph large">{{ toolGlyph(tool.id) }}</span>
                    <span><strong>{{ tool.name }}</strong><small>{{ tool.description }}</small></span>
                    <i :class="tool.available ? 'good' : 'warn'"></i>
                  </div>
                </div>
                <div class="weather-test">
                  <el-input v-model="weatherCity" placeholder="城市，例如常州" />
                  <el-button :loading="loading.weather" @click="testWeather">测试天气 MCP</el-button>
                </div>
                <pre v-if="weatherResult">{{ weatherResult }}</pre>
              </article>

              <article class="glass-panel settings-card infrastructure-card">
                <div class="panel-title-row compact"><h3>基础设施</h3><span class="status-label live">受控网络</span></div>
                <div class="infra-row"><span><i class="good"></i>应用数据库</span><strong>{{ health?.repository || '加载中' }}</strong></div>
                <div class="infra-row"><span><i :class="health?.vectorStore?.startsWith('connected') ? 'good' : 'warn'"></i>向量数据库</span><strong>{{ health?.vectorStore || '加载中' }}</strong></div>
                <div class="infra-row"><span><i :class="chatOptions.mcpEnabled ? 'good' : 'warn'"></i>MCP 客户端</span><strong>{{ health?.mcp || '加载中' }}</strong></div>
              </article>
            </div>
          </section>
        </Transition>
      </main>
    </section>

    <ActionAuthDialog ref="authDialog" />

    <el-dialog v-model="createKbOpen" title="新建知识库" width="520px" class="apple-dialog">
      <el-form label-position="top" class="clean-form">
        <el-form-item label="知识库名称"><el-input v-model="kbForm.name" maxlength="200" /></el-form-item>
        <div class="form-pair">
          <el-form-item label="业务类型">
            <el-select v-model="kbForm.businessType" allow-create filterable>
              <el-option v-for="type in businessTypes" :key="type" :label="type" :value="type" />
            </el-select>
          </el-form-item>
          <el-form-item label="代码"><el-input v-model="kbForm.code" maxlength="100" placeholder="可留空自动生成" /></el-form-item>
        </div>
        <el-form-item label="描述">
          <el-input v-model="kbForm.description" type="textarea" :rows="4" maxlength="1000" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createKbOpen = false">取消</el-button>
        <el-button type="primary" :loading="loading.kb" @click="createKnowledgeBase">创建知识库</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importOpen" title="导入知识文档" width="620px" class="apple-dialog">
      <el-tabs v-model="importMode" class="import-tabs">
        <el-tab-pane label="粘贴文本" name="text">
          <el-form label-position="top" class="clean-form">
            <el-form-item label="文档标题"><el-input v-model="upload.title" maxlength="500" /></el-form-item>
            <el-form-item label="文档内容"><el-input v-model="upload.content" type="textarea" :rows="9" /></el-form-item>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="上传文件" name="file">
          <el-upload
            drag
            multiple
            :auto-upload="false"
            :on-change="onFileChange"
            :on-remove="onFileRemove"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖放文件到这里，或 <em>点击选择</em></div>
            <template #tip>
              <div class="el-upload__tip">支持 PDF、Word、Excel、Markdown、CSV，单个文件不超过 15MB。</div>
            </template>
          </el-upload>
        </el-tab-pane>
      </el-tabs>
      <el-form label-position="top" class="clean-form metadata-form">
        <el-form-item label="元数据 JSON">
          <el-input v-model="upload.metadata" placeholder='{"category":"policy","region":"east"}' />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importOpen = false">取消</el-button>
        <el-button
          type="primary"
          :loading="loading.import"
          @click="importMode === 'text' ? importText() : uploadFiles()"
        >
          导入并向量化
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="referenceDialogOpen"
      :title="activeReference?.title || '引用证据'"
      width="620px"
      class="apple-dialog"
    >
      <p class="preview-content">{{ activeReference?.content }}</p>
      <div class="preview-meta">
        <span v-for="(value, key) in activeReference?.metadata" :key="key">{{ key }} · {{ value }}</span>
      </div>
    </el-dialog>

    <el-dialog
      v-model="documentDialogOpen"
      :title="previewDocument?.title || '知识文档'"
      width="720px"
      class="apple-dialog"
    >
      <p class="preview-content">{{ previewDocument?.content }}</p>
      <div class="preview-meta">
        <span v-for="(value, key) in previewDocument?.metadata" :key="key">{{ key }} · {{ value }}</span>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { UploadFile, UploadFiles } from 'element-plus';
import { BarChart, LineChart, PieChart } from 'echarts/charts';
import {
  DatasetComponent,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
  TransformComponent,
} from 'echarts/components';
import {
  use as useEcharts,
} from 'echarts/core';
import type { EChartsCoreOption } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import {
  ArrowRight,
  ChatDotRound,
  Collection,
  Connection,
  DataAnalysis,
  Delete,
  Document,
  Link,
  Lock,
  MagicStick,
  MoreFilled,
  Plus,
  Refresh,
  Search,
  Setting,
  Top,
  Upload,
  UploadFilled,
} from '@element-plus/icons-vue';
import ActionAuthDialog from './components/ActionAuthDialog.vue';
import ChartView from './components/ChartView.vue';
import { setActionTokenRequester } from './actionAuth';
import { api, streamChat } from './api';

useEcharts([
  BarChart,
  LineChart,
  PieChart,
  DatasetComponent,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
  TransformComponent,
  CanvasRenderer,
]);

type Section = 'cockpit' | 'knowledge' | 'reports' | 'settings';
type Health = {
  status: string;
  mode: string;
  repository: string;
  vectorStore: string;
  mcp: string;
  knowledgeBases: number;
  documents: number;
  chunks: number;
  reports: number;
};
type ModelOption = {
  id: string;
  name: string;
  description: string;
  badge: string;
  recommended: boolean;
};
type McpToolOption = {
  id: string;
  name: string;
  description: string;
  toolName: string;
  available: boolean;
};
type ChatOptions = {
  defaultModel: string;
  models: ModelOption[];
  mcpEnabled: boolean;
  mcpTools: McpToolOption[];
};
type KnowledgeBase = {
  id: number;
  name: string;
  description: string;
  code: string;
  businessType: string;
  documentCount: number;
  createdAt: string;
};
type DocumentRow = {
  id: number;
  knowledgeBaseId: number;
  title: string;
  content?: string;
  chunks: number;
  metadata: Record<string, string>;
  createdAt: string;
};
type Reference = {
  id: number;
  title: string;
  content: string;
  score: number;
  metadata: Record<string, string>;
};
type Message = {
  id: number;
  role: 'user' | 'assistant';
  content: string;
  model?: string;
};
type McpTrace = {
  id: string;
  name: string;
  status: 'success' | 'ready' | 'error';
  output: string;
};
type ChartItem = {
  id: number;
  option: EChartsCoreOption;
};
type DataSource = {
  id: number;
  name: string;
  type: string;
  endpoint: string;
  queryText: string;
};
type ReportTemplate = { id: number; name: string; cron: string };
type ReportRun = {
  id: number;
  name: string;
  status: string;
  createdAt: string;
  chartSpec: string;
};
type AuthDialogExpose = { requestAuthorization: () => Promise<string> };

const route = useRoute();
const router = useRouter();
const navigation = [
  { id: 'cockpit' as const, label: '智能对话', icon: ChatDotRound },
  { id: 'knowledge' as const, label: '知识库', icon: Collection },
  { id: 'reports' as const, label: '数据与报告', icon: DataAnalysis },
  { id: 'settings' as const, label: '模型与 MCP', icon: Setting },
];
const pageDetails: Record<Section, { eyebrow: string; title: string; description: string }> = {
  cockpit: { eyebrow: 'AI COCKPIT', title: '企业智能座舱', description: '基于业务知识、实时工具与 DeepSeek 的可信对话。' },
  knowledge: { eyebrow: 'KNOWLEDGE', title: '知识库配置', description: '按业务边界管理文档、元数据与向量索引。' },
  reports: { eyebrow: 'OPERATIONS', title: '数据与智能报告', description: '连接只读数据源，生成可复用的分析任务。' },
  settings: { eyebrow: 'RUNTIME', title: '模型与工具', description: '查看模型、MCP 和数据基础设施的运行状态。' },
};

const active = computed<Section>({
  get: () => {
    const segment = route.path.split('/').filter(Boolean)[0];
    return (navigation.some((item) => item.id === segment) ? segment : 'cockpit') as Section;
  },
  set: (value) => {
    void router.push(value === 'cockpit' ? '/' : `/${value}`);
  },
});
const pageMeta = computed(() => pageDetails[active.value]);

const health = ref<Health>();
const chatOptions = ref<ChatOptions>({
  defaultModel: 'deepseek-v4-flash',
  models: [],
  mcpEnabled: false,
  mcpTools: [],
});
const knowledgeBases = ref<KnowledgeBase[]>([]);
const documents = ref<DocumentRow[]>([]);
const dataSources = ref<DataSource[]>([]);
const reportTemplates = ref<ReportTemplate[]>([]);
const reportRuns = ref<ReportRun[]>([]);
const references = ref<Reference[]>([]);
const messages = ref<Message[]>([]);
const toolTraces = ref<McpTrace[]>([]);
const selectedKbId = ref(0);
const kbSearch = ref('');
const files = ref<File[]>([]);
const createKbOpen = ref(false);
const importOpen = ref(false);
const importMode = ref<'text' | 'file'>('text');
const activeReference = ref<Reference>();
const previewDocument = ref<DocumentRow>();
const charts = ref<ChartItem[]>([]);
const analysisTab = ref<'charts' | 'evidence'>('evidence');
const agentPlanSummary = ref('');
const weatherCity = ref('常州');
const weatherResult = ref('');
const authDialog = ref<AuthDialogExpose>();
const messageScroller = ref<HTMLDivElement>();
const loading = reactive({
  refresh: false,
  chat: false,
  kb: false,
  import: false,
  weather: false,
});
let messageId = 0;
let chartId = 0;
let chatAbortController: AbortController | undefined;

const storedTools = readStoredArray('cockpit-mcp-tools');
const chat = reactive({
  conversationId: '',
  message: '',
  model: localStorage.getItem('cockpit-model') || 'deepseek-v4-flash',
  knowledgeBaseIds: [] as number[],
  mcpToolIds: storedTools,
  enableChart: false,
});
const kbForm = reactive({
  name: '',
  code: '',
  businessType: '客户服务',
  description: '',
});
const upload = reactive({
  title: '',
  content: '',
  metadata: '{"category":"policy","environment":"demo"}',
});
const dsForm = reactive({
  name: '业务指标 API',
  type: 'HTTP',
  endpoint: 'https://example.com/metrics',
  queryText: 'GET /metrics?period=today',
});
const reportForm = reactive({
  name: '业务经营日报',
  scheduleType: 'CRON',
  cron: '0 0 9 * * ?',
  dataSourceKey: 'business-metrics',
  knowledgeBaseId: 0,
  prompt: '结合业务指标与知识库，分析异常并给出建议。',
  dimensions: 'region,metric,value',
  enabled: true,
});
const businessTypes = ['客户服务', '跨境电商', '量化研究', '产品运营', '人力资源', '通用业务'];
const suggestions = [
  '总结各业务知识库中的关键规则',
  '退款申请需要满足哪些条件？',
  '计算 (120 + 95) × 1.08',
  '江苏所有城市今天的天气，并展示气温对比柱状图',
];

const modelStatusLabel = computed(() =>
  health.value?.mode === 'local-rag' || health.value?.mode === 'mock'
    ? '本地 RAG 模式'
    : 'DeepSeek 已连接');
const selectedKnowledgeBase = computed(() => knowledgeBases.value.find((kb) => kb.id === selectedKbId.value));
const selectedKbDocuments = computed(() => documents.value.filter((document) => document.knowledgeBaseId === selectedKbId.value));
const selectedKbChunkCount = computed(() => selectedKbDocuments.value.reduce((sum, document) => sum + document.chunks, 0));
const filteredKnowledgeBases = computed(() => {
  const query = kbSearch.value.trim().toLowerCase();
  if (!query) return knowledgeBases.value;
  return knowledgeBases.value.filter((kb) =>
    `${kb.name} ${kb.businessType} ${kb.code}`.toLowerCase().includes(query));
});
const runtimeScore = computed(() => {
  let score = 0;
  if (health.value?.status === 'ok') score += 35;
  if (health.value?.mode !== 'mock') score += 25;
  if (health.value?.vectorStore?.startsWith('connected')) score += 25;
  if (chatOptions.value.mcpEnabled) score += 15;
  return score;
});
const referenceDialogOpen = computed({
  get: () => Boolean(activeReference.value),
  set: (value) => { if (!value) activeReference.value = undefined; },
});
const documentDialogOpen = computed({
  get: () => Boolean(previewDocument.value),
  set: (value) => { if (!value) previewDocument.value = undefined; },
});

watch(() => chat.model, (value) => localStorage.setItem('cockpit-model', value));
watch(() => [...chat.mcpToolIds], (value) => {
  localStorage.setItem('cockpit-mcp-tools', JSON.stringify(value));
}, { deep: true });
watch(selectedKbId, (id) => {
  if (id && !chat.knowledgeBaseIds.length) chat.knowledgeBaseIds = [id];
});

async function refreshAll(showSuccess = false) {
  if (loading.refresh) return;
  loading.refresh = true;
  try {
    const [
      nextHealth,
      nextOptions,
      nextKnowledgeBases,
      nextDocuments,
      nextDataSources,
      nextReportTemplates,
      nextReportRuns,
    ] = await Promise.all([
      api<Health>('/health'),
      api<ChatOptions>('/chat/options'),
      api<KnowledgeBase[]>('/admin/knowledge-bases'),
      api<DocumentRow[]>('/admin/documents'),
      api<DataSource[]>('/admin/data-sources'),
      api<ReportTemplate[]>('/admin/report-templates'),
      api<ReportRun[]>('/admin/report-runs'),
    ]);
    health.value = nextHealth;
    chatOptions.value = nextOptions;
    knowledgeBases.value = nextKnowledgeBases;
    documents.value = nextDocuments;
    dataSources.value = nextDataSources;
    reportTemplates.value = nextReportTemplates;
    reportRuns.value = nextReportRuns;

    if (!nextOptions.models.some((model) => model.id === chat.model)) {
      chat.model = nextOptions.defaultModel;
    }
    chat.mcpToolIds = chat.mcpToolIds.filter((id) =>
      nextOptions.mcpTools.some((tool) => tool.id === id && tool.available));
    if (!selectedKbId.value || !nextKnowledgeBases.some((kb) => kb.id === selectedKbId.value)) {
      selectedKbId.value = nextKnowledgeBases[0]?.id ?? 0;
    }
    chat.knowledgeBaseIds = chat.knowledgeBaseIds.filter((id) =>
      nextKnowledgeBases.some((kb) => kb.id === id));
    if (!reportForm.knowledgeBaseId || !nextKnowledgeBases.some((kb) => kb.id === reportForm.knowledgeBaseId)) {
      reportForm.knowledgeBaseId = nextKnowledgeBases[0]?.id ?? 0;
    }
    if (showSuccess) ElMessage.success('运行状态已刷新');
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    loading.refresh = false;
  }
}

async function sendChat() {
  const question = chat.message.trim();
  if (!question || loading.chat) return;
  loading.chat = true;
  references.value = [];
  toolTraces.value = [];
  charts.value = [];
  analysisTab.value = 'evidence';
  agentPlanSummary.value = '';
  messages.value.push({ id: ++messageId, role: 'user', content: question });
  const assistant: Message = {
    id: ++messageId,
    role: 'assistant',
    content: '',
    model: chat.model,
  };
  messages.value.push(assistant);
  chat.message = '';
  chatAbortController = new AbortController();
  await scrollConversation();

  try {
    await streamChat({
      conversationId: chat.conversationId,
      message: question,
      model: chat.model,
      knowledgeBaseIds: chat.knowledgeBaseIds,
      metadataFilter: {},
      mcpToolIds: chat.mcpToolIds,
      enableTools: chat.mcpToolIds.length > 0,
      enableChart: chat.enableChart,
    }, async (message) => {
      if (message.event === 'meta') {
        const metadata = parseJson<{ conversationId?: string; model?: string }>(message.data, {});
        if (metadata.conversationId) chat.conversationId = metadata.conversationId;
        if (metadata.model) assistant.model = metadata.model;
      } else if (message.event === 'plan') {
        agentPlanSummary.value = parseJson<{ summary?: string }>(message.data, {}).summary || '';
      } else if (message.event === 'token') {
        assistant.content += message.data;
      } else if (message.event === 'references') {
        references.value = parseJson<Reference[]>(message.data, []);
      } else if (message.event === 'tool') {
        toolTraces.value.push(parseJson<McpTrace>(message.data, {
          id: 'unknown',
          name: 'MCP',
          status: 'error',
          output: message.data,
        }));
      } else if (message.event === 'chart') {
        addChart(parseJson(message.data, null));
      } else if (message.event === 'error') {
        throw new Error(message.data);
      }
      await scrollConversation();
    }, chatAbortController.signal);
    if (!assistant.content) assistant.content = '模型未返回可展示的内容，请重试或切换模型。';
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      assistant.content = assistant.content
        ? `${assistant.content}\n\n（已停止生成）`
        : '已停止生成。';
    } else {
      assistant.content = `对话失败：${errorMessage(error)}`;
      ElMessage.error(assistant.content);
    }
  } finally {
    loading.chat = false;
    chatAbortController = undefined;
    await scrollConversation();
  }
}

function stopChat() {
  chatAbortController?.abort();
}

function clearConversation() {
  stopChat();
  messages.value = [];
  references.value = [];
  toolTraces.value = [];
  agentPlanSummary.value = '';
  chat.conversationId = '';
  charts.value = [];
  analysisTab.value = 'evidence';
}

function handleComposerKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.isComposing) return;
  if (event.ctrlKey || event.metaKey) {
    event.preventDefault();
    const textarea = event.target as HTMLTextAreaElement;
    const start = textarea.selectionStart ?? textarea.value.length;
    const end = textarea.selectionEnd ?? start;
    chat.message = `${textarea.value.slice(0, start)}\n${textarea.value.slice(end)}`;
    void nextTick(() => textarea.setSelectionRange(start + 1, start + 1));
    return;
  }
  if (event.shiftKey) return;
  event.preventDefault();
  void sendChat();
}

function useSuggestion(suggestion: string) {
  chat.message = suggestion;
  void sendChat();
}

async function scrollConversation() {
  await nextTick();
  if (messageScroller.value) {
    messageScroller.value.scrollTop = messageScroller.value.scrollHeight;
  }
}

function addChart(spec: unknown) {
  if (!spec || typeof spec !== 'object') return;
  charts.value.push({ id: ++chartId, option: spec as EChartsCoreOption });
  analysisTab.value = 'charts';
}

function chartTitle(option: EChartsCoreOption) {
  const title = option.title as { text?: string } | Array<{ text?: string }> | undefined;
  if (Array.isArray(title)) return String(title[0]?.text || '动态分析图');
  return String(title?.text || '动态分析图');
}

async function createKnowledgeBase() {
  if (!kbForm.name.trim()) return ElMessage.warning('请输入知识库名称');
  loading.kb = true;
  try {
    const created = await api<KnowledgeBase>('/admin/knowledge-bases', {
      method: 'POST',
      body: JSON.stringify(kbForm),
    });
    createKbOpen.value = false;
    Object.assign(kbForm, { name: '', code: '', businessType: '客户服务', description: '' });
    await refreshAll();
    selectedKbId.value = created.id;
    ElMessage.success('知识库已创建');
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    loading.kb = false;
  }
}

async function deleteKnowledgeBase(kb: KnowledgeBase) {
  try {
    await ElMessageBox.confirm(
      `将删除“${kb.name}”以及其中的全部文档和向量，操作不可撤销。`,
      '删除知识库',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
    );
    await api(`/admin/knowledge-bases/${kb.id}`, { method: 'DELETE' });
    await refreshAll();
    ElMessage.success('知识库已删除');
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error));
  }
}

async function importText() {
  if (!selectedKbId.value) return ElMessage.warning('请先选择知识库');
  if (!upload.title.trim() || !upload.content.trim()) {
    return ElMessage.warning('请输入文档标题和内容');
  }
  const metadata = parseJsonMap(upload.metadata);
  if (!metadata) return;
  loading.import = true;
  try {
    await api(`/admin/documents/text?knowledgeBaseId=${selectedKbId.value}`, {
      method: 'POST',
      body: JSON.stringify({
        title: upload.title,
        content: upload.content,
        metadata,
      }),
    });
    importOpen.value = false;
    upload.title = '';
    upload.content = '';
    await refreshAll();
    ElMessage.success('文档已切分并同步向量索引');
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    loading.import = false;
  }
}

function onFileChange(file: UploadFile) {
  if (!file.raw) return;
  if (!files.value.some((item) =>
    item.name === file.raw!.name
    && item.size === file.raw!.size
    && item.lastModified === file.raw!.lastModified)) {
    files.value.push(file.raw);
  }
}

function onFileRemove(file: UploadFile, uploadFiles: UploadFiles) {
  if (!file.raw) {
    files.value = uploadFiles.flatMap((item) => item.raw ? [item.raw] : []);
    return;
  }
  files.value = files.value.filter((item) =>
    !(item.name === file.raw!.name
      && item.size === file.raw!.size
      && item.lastModified === file.raw!.lastModified));
}

async function uploadFiles() {
  if (!selectedKbId.value) return ElMessage.warning('请先选择知识库');
  if (!files.value.length) return ElMessage.warning('请选择至少一个文件');
  const metadata = parseJsonMap(upload.metadata);
  if (!metadata) return;
  const form = new FormData();
  form.set('knowledgeBaseId', String(selectedKbId.value));
  form.set('metadata', JSON.stringify(metadata));
  files.value.forEach((file) => form.append('files', file));
  loading.import = true;
  try {
    await api('/admin/documents/batch-upload', { method: 'POST', body: form });
    files.value = [];
    importOpen.value = false;
    await refreshAll();
    ElMessage.success('文件已导入并同步向量索引');
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    loading.import = false;
  }
}

async function deleteDocument(document: DocumentRow) {
  try {
    await ElMessageBox.confirm(`确认删除“${document.title}”及其向量分块？`, '删除文档', {
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
    });
    await api(`/admin/documents/${document.id}`, { method: 'DELETE' });
    await refreshAll();
    ElMessage.success('文档已删除');
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error));
  }
}

async function openDocument(document: DocumentRow) {
  try {
    previewDocument.value = await api<DocumentRow>(
      `/admin/documents/${document.id}`,
    );
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

async function createDataSource() {
  try {
    await api('/admin/data-sources', {
      method: 'POST',
      body: JSON.stringify({ ...dsForm, config: {} }),
    });
    await refreshAll();
    ElMessage.success('数据源已保存');
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

async function createReportTemplate() {
  if (!reportForm.knowledgeBaseId) return ElMessage.warning('请先创建并选择知识库');
  try {
    await api('/admin/report-templates', {
      method: 'POST',
      body: JSON.stringify(reportForm),
    });
    await refreshAll();
    ElMessage.success('报告模板已保存');
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

async function runReport(id: number) {
  try {
    const run = await api<ReportRun>(`/admin/report-templates/${id}/run-now`, { method: 'POST' });
    addChart(parseJson(run.chartSpec, null));
    active.value = 'cockpit';
    await refreshAll();
    ElMessage.success('报告已生成，并写入关联知识库');
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

async function testWeather() {
  loading.weather = true;
  weatherResult.value = '';
  try {
    const result = await api<{ result?: string; message?: string }>(
      `/mcp/weather?city=${encodeURIComponent(weatherCity.value)}`,
    );
    const value = result.result || result.message || '无返回内容';
    weatherResult.value = prettyJson(value);
  } catch (error) {
    weatherResult.value = errorMessage(error);
  } finally {
    loading.weather = false;
  }
}

function readStoredArray(key: string): string[] {
  try {
    const value = JSON.parse(localStorage.getItem(key) || '[]');
    return Array.isArray(value) ? value.filter((item) => typeof item === 'string') : [];
  } catch {
    return [];
  }
}

function parseJson<T>(text: string, fallback: T): T {
  try { return JSON.parse(text) as T; } catch { return fallback; }
}

function parseJsonMap(text: string): Record<string, string> | null {
  if (!text.trim()) return {};
  try {
    const value: unknown = JSON.parse(text);
    if (!value || Array.isArray(value) || typeof value !== 'object') {
      throw new Error('元数据必须是 JSON 对象');
    }
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, String(item)]));
  } catch (error) {
    ElMessage.error(`元数据格式错误：${errorMessage(error)}`);
    return null;
  }
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error);
}

function modelName(id?: string) {
  return chatOptions.value.models.find((model) => model.id === id)?.name || id || '';
}

function excerpt(text: string, length: number) {
  const normalized = (text || '').replace(/\s+/g, ' ').trim();
  return normalized.length > length ? `${normalized.slice(0, length)}…` : normalized;
}

function formatScore(score: number) {
  return Number.isFinite(score) ? `${Math.max(0, score).toFixed(2)}` : '—';
}

function formatDate(value: string) {
  if (!value) return '刚刚';
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

function toolGlyph(id: string) {
  return ({ weather: '☀', time: '◷', calculator: '∑', amap: '⌖', agent: '✦' } as Record<string, string>)[id] || '⌘';
}

function toolTraceSummary(trace: McpTrace) {
  if (trace.status !== 'success' || trace.id !== 'weather') return trace.output;
  type WeatherValue = { city?: string; temperatureC?: number };
  type WeatherPayload = WeatherValue & {
    region?: string;
    count?: number;
    cities?: WeatherValue[];
    source?: string;
  };
  const payload = parseJson<WeatherPayload | null>(trace.output, null);
  if (!payload) return trace.output;
  if (Array.isArray(payload.cities) && payload.cities.length) {
    const temperatures = payload.cities
      .map((city) => city.temperatureC)
      .filter((value): value is number => Number.isFinite(value));
    const range = temperatures.length
      ? ` · ${Math.min(...temperatures).toFixed(1)}–${Math.max(...temperatures).toFixed(1)}°C`
      : '';
    return `已获取${payload.region || ''} ${payload.count || payload.cities.length} 个城市${range} · ${payload.source || '实时天气'}`;
  }
  if (payload.city && Number.isFinite(payload.temperatureC)) {
    return `${payload.city} ${Number(payload.temperatureC).toFixed(1)}°C · ${payload.source || '实时天气'}`;
  }
  return trace.output;
}

function businessGlyph(type: string) {
  if (type.includes('跨境')) return '◈';
  if (type.includes('量化')) return '⌁';
  if (type.includes('客户')) return '◎';
  if (type.includes('人力')) return '◇';
  if (type.includes('产品')) return '△';
  return '✦';
}

function prettyJson(value: string) {
  try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; }
}

function escapeHtml(value: string) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

function renderInline(value: string) {
  return escapeHtml(value)
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(
      /\[Reference\s*(\d+)\]/gi,
      '<span class="inline-citation">引用 $1</span>',
    );
}

function tableCells(line: string) {
  return line.trim().replace(/^\|/, '').replace(/\|$/, '')
    .split('|')
    .map((cell) => cell.trim());
}

function isTableSeparator(line: string) {
  const cells = tableCells(line);
  return cells.length > 0 && cells.every((cell) => /^:?-{3,}:?$/.test(cell));
}

function renderMessage(value: string) {
  const lines = value.replace(/\r/g, '').split('\n');
  const output: string[] = [];
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    if (line.includes('|') && index + 1 < lines.length && isTableSeparator(lines[index + 1])) {
      const headers = tableCells(line);
      const rows: string[][] = [];
      index += 2;
      while (index < lines.length && lines[index].includes('|') && lines[index].trim()) {
        rows.push(tableCells(lines[index]));
        index += 1;
      }
      index -= 1;
      output.push(
        '<div class="message-table-wrap"><table class="message-table"><thead><tr>',
        ...headers.map((cell) => `<th>${renderInline(cell)}</th>`),
        '</tr></thead><tbody>',
        ...rows.map((row) => `<tr>${headers.map((_, cellIndex) =>
          `<td>${renderInline(row[cellIndex] || '')}</td>`).join('')}</tr>`),
        '</tbody></table></div>',
      );
    } else if (!line.trim()) {
      output.push('<div class="message-spacer"></div>');
    } else if (/^#{1,3}\s+/.test(line)) {
      output.push(`<div class="message-heading">${renderInline(line.replace(/^#{1,3}\s+/, ''))}</div>`);
    } else if (/^\s*[-*]\s+/.test(line)) {
      output.push(`<div class="message-list-item">${renderInline(line.replace(/^\s*[-*]\s+/, ''))}</div>`);
    } else {
      output.push(`<div>${renderInline(line)}</div>`);
    }
  }
  return output.join('');
}

onMounted(() => {
  setActionTokenRequester(() => {
    if (!authDialog.value) return Promise.reject(new Error('操作验证界面尚未就绪'));
    return authDialog.value.requestAuthorization();
  });
  void refreshAll();
});

onBeforeUnmount(() => {
  setActionTokenRequester(null);
  chatAbortController?.abort();
});
</script>
