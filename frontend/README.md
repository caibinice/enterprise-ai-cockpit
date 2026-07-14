# Frontend

Vue 3 + TypeScript + Vite + Element Plus + ECharts 的企业 AI 驾驶舱。

## 页面

- **AI Cockpit Chat**：选择知识库，消费 WebFlux SSE 的 `meta`、`token`、`references`、`chart`、`done` 事件。
- **Knowledge Base**：创建知识库、文本导入、批量上传 PDF/Word/Excel/Markdown/CSV，查看分块和 metadata。
- **Data & Reports**：配置数据源、创建报告模板、手动运行报告并展示图表。
- **MCP / Speech**：展示实际 pgvector/MCP 状态，提供天气 MCP 测试入口说明和 Mock 语音合成。

## 启动

```powershell
Set-Location E:\codes\enterprise-ai-cockpit\frontend
npm ci
npm run dev
```

默认访问 `http://localhost:5173`，开发服务器将 `/api` 代理到 `http://127.0.0.1:8080`。后端地址不同时，在 `frontend/.env.local` 设置：

```text
VITE_API_BASE=http://localhost:8080
```

## 构建

```powershell
npm run build
```

构建包含 `vue-tsc --noEmit` 和 `vite build`。

## 目录

```text
src/App.vue       页面布局、聊天、知识库、报告和状态展示
src/api.ts        REST API 与兼容 CRLF/末尾 frame 的 SSE 解析器
src/main.ts       Vue、Router、Pinia、Element Plus 初始化
src/style.css     布局和响应式样式
```

## 后续优化

- 增加 i18n、请求取消、重试、分页、上传进度和更细粒度错误态。
- 为 SSE parser、引用渲染、图表生命周期和 API 契约补充自动化测试。
- 将健康状态、MCP 调用日志和向量检索耗时接入可观测性面板。
