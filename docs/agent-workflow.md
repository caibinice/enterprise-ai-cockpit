# 座舱智能体工作流模块

## 如何进入与演示

线上入口：`https://caibinice.com/smartCockpit/workflow`。左侧主导航选择“智能体工作流”，不需要打开独立 Agent Demo，也没有 iframe 或第二个 Java 后端。

建议先演示“工具调用循环”，再演示“多 Agent 协作”：

1. 选中场景，点击“运行当前输入”；创建运行沿用座舱操作密码验证。
2. 离线讲解只替换模型决策为明确的规则/证据模板；Graph、向量检索、stdio MCP 和 HTTP 工具仍真实执行。
3. “DeepSeek 在线”会调用 `deepseek-flash`，默认沿用本机 `credentials.txt` 的 `[deepseek.api]`；密钥只进入后端环境。工作流模型与原聊天 Flash/Pro 模型设置分开，避免互相改变。
4. 观察每轮工具返回、专家路径与节点输入输出。“演示模式”收起场景栏以腾出图的空间。
5. 点击回放或拖动游标，可逐节点前后查看。**回放不重跑模型或工具**；人工提交反馈则会执行恢复图。

| 场景 | 运行链路 | 核对点 |
|---|---|---|
| 产品知识 RAG | 分类 → 检索 → 回答 → 评审 | 引用 KB001，保留分数与来源 |
| 图片与文字 | 图片证据 → 分类 → RAG | 内置 E02 图片；在线实际视觉调用，离线核对图片 SHA-256 后使用样例注释 |
| 企业报表 MCP | 发现报表工具 → tools/call → 回答 | 2026-09 营收 1,286,000，环比 12.81% |
| 工具调用循环 | 决策 → 查订单 → 决策 → 退款试算 → 退出 | 两次实际 HTTP；商品金额 599 元；只是试算 |
| 多 Agent 协作 | 拆任务 → 专家图 → 检查 → 下一专家 → 汇总 | 三个 CompiledGraph 顺序执行，订单子图内部另有循环 |
| 人工接管 | 交接资料 → 等待 → 人工反馈恢复图 | 前序工具不重复执行 |
| 反馈重检索 | 首次证据空 → 反馈改写 → 再检索 | 两次 retrieve，query 确实变化 |
| 工具异常与预算 | 注入一次超时 → 带失败观察重试 | 3 轮可恢复；预算 2 轮时转人工 |

## 合入现有工程时改了什么

```mermaid
flowchart LR
  UI[座舱 Vue 主导航 /workflow] --> API[/api/workflow]
  API --> AUTH[既有 ActionAuthWebFilter]
  AUTH --> G[CustomerServiceGraph]
  G --> S[rag / report / tool 专家图]
  G --> DS[后端 DeepSeek Gateway]
  G --> MCP[stdio 企业报表样例服务]
  G --> HTTP[127.0.0.1:18083 只读订单 / 退款样例]
  G --> E[RunStore 有序事件]
  E --> SSE[WebFlux SSE]
  SSE --> UI
  E --> D[shared/workflow/runs/*.json]
```

- 后端包：`com.example.aiagent.workflow`；新增 API 命名空间 `/api/workflow`，既有 `/api/chat`、管理 API 和停车 API 不变。
- 采用 Spring AI Alibaba Graph `1.1.2.4-security-fix`，Spring AI BOM 从 1.0.0 对齐到 1.1.2；原 MCP 单元测试改用 SDK Tool builder。
- 原演示的 MVC SseEmitter 转为 Reactor Flux，订阅/补发与事件追加仍共用 Run 锁；15 秒无 ID 心跳、5 分钟重连窗口。响应设置 `X-Accel-Buffering: no`，适配座舱既有通用 Nginx 代理，不改其他应用路由。
- 可能阻塞的创建、磁盘写入与恢复请求在 boundedElastic 执行；Graph 工作池 2 线程、12 个排队位置，另有每图递归限制和业务循环预算。
- 知识、图片、校验注释放进 JAR classpath，避免依赖兄弟项目绝对路径；Node 样例服务随现有 `mcp-servers` 一起打包，采用与座舱已有服务一致的无 npm 依赖 JSON-RPC 适配层。
- Vue 工作台作为异步组件加载，样式限定于 `.workflow-studio`，保留一个座舱主导航。创建/取消/人工恢复/演示知识写入都走既有操作令牌。
- 工作流演示知识使用 2048 维 hashed n-gram cosine，不是语义 embedding；座舱原企业知识库继续使用 MySQL + pgvector 检索。

## 节点监控和回放的边界

节点包装器产生 `edge.traversed`、`node.started`、`node.completed` 或 `node.failed`。事件带有 Run 内递增的 `seq`、`traceId`、`spanId`、`parentSpanId`、`nodeId`、`path`、时间及公开输入输出。完成事件的 output 是**状态增量**，不是完整状态。

同一 nodeId 可能循环多次，因此输入输出按 **spanId + path** 配对。专家生命周期事件复用外层 specialist Span，path 区分外层节点与专家标记。调用详情记录请求和结果，不记录模型内部思维。

前端回放是 `events.slice(0, cursor)`；600ms 推进一个事件，不按原始墙钟时间等速播放。路径筛选针对时间线/详情，主图仍按 nodeId 聚合。父节点耗时包含子节点时间，不要把父子耗时相加。最终答复与“当前状态”保留最新值，不伪装成历史状态 time-travel。

根节点边界和运行结束保存 JSON，经临时文件 + 原子替换落盘。不是每条事件立即写盘，进程突然退出时最后边界后的事件可能丢失。重启后 RUNNING 记为 INTERRUPTED，WAITING_HUMAN 保留；人工恢复用应用保存状态进入 HumanResumeGraph，不是 Graph 原生 interrupt/checkpointer 任意节点恢复。

记录最多保留 100 条，历史 API 返回最近 40 条。事件去掉 `__*` 内部字段、原始 image 和 api-key；这不是通用个人信息脱敏系统，演示使用样例资料。

## 本地与生产运行

本地仍使用根目录 `run-dev.ps1`，凭据仍放被 Git 忽略的 `credentials.txt`。操作验证签名沿用忽略的 `.deploy/action-auth.json`，或由进程注入 `ACTION_PASSWORD` + `ACTION_TOKEN_SECRET`。

```powershell
pwsh -File run-dev.ps1 -SkipInstall -SkipPostgres
# 浏览器 http://localhost:5173/smartCockpit/workflow

mvn -f backend/pom.xml test
npm run test:replay --prefix frontend
npm run build --prefix frontend

# ACTION_PASSWORD 从本地配置加载到进程，不放命令历史或仓库
pwsh -File scripts/verify-workflow.ps1 -BaseUrl http://127.0.0.1:8080
pwsh -File scripts/verify-workflow.ps1 -Mode live -FocusOnly
```

生产发布继续用 `scripts/deploy.ps1`：归档 JAR、前端 dist 和 MCP 脚本，切换 release，只重启座舱。工作流持久目录为 `/opt/enterprise-ai-cockpit/shared/workflow`，不放在会被清理的 release 下。Node HTTP 仅绑定 loopback 18083；外部仍只走现有 HTTPS 入口。

新增配置见 `deploy/application-production.env.example`。环境目录权限、160MB Java 堆限制和其他服务不变；健康失败仍回滚前版。

## 主要源码

| 文件 | 职责 |
|---|---|
| `backend/.../workflow/WorkflowEngine.java` | 图、路由、预算、节点包装、专家与人工恢复 |
| `backend/.../workflow/RunStore.java` | 事件、补发、Flux 订阅、JSON 检查点 |
| `backend/.../workflow/WorkflowController.java` | 原生 WebFlux API、心跳、流式响应头 |
| `backend/.../workflow/WorkflowModelGateway.java` | DeepSeek 普通、视觉、原生 tool_calls 与模型事件 |
| `backend/.../workflow/EnterpriseTools.java` | 真正的 MCP/HTTP 执行、工具白名单、参数校验 |
| `frontend/src/workflow/WorkflowStudio.vue` | 场景、事件流、历史、暂停/单步/实时 |
| `frontend/src/workflow/WorkflowGraph.vue` | SVG 节点和边的事件派生状态 |
| `frontend/src/workflow/ExecutionFocus.vue` | 循环轮次、专家卡片与路径 |
| `frontend/src/workflow/replay.ts` | 游标定位与 Span/Path 输入配对 |
