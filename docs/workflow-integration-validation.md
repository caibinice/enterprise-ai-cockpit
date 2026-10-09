# 工作流融合验证记录

验证日期：2026-10-09。验证对象是合入 enterprise-ai-cockpit 的原生模块，不是独立 agentDemo。

## 本地已执行

| 检查 | 结果 |
|---|---|
| Maven package（含既有测试与新增测试） | 86 项，0 failure / 0 error；包括停车业务、聊天编排、MCP、知识分块与鉴权 |
| WorkflowEngineTest | 16 项：RAG、图片校验、独立 MCP、HTTP 循环、三个子图、反馈重检索、预算转人工、持久化恢复、协议多调用配对等 |
| RunStoreTest | 3 项：游标补发、订阅不断档/断开不取消、公开字段过滤 |
| Vue 类型检查与生产构建 | 通过；工作台异步 chunk，关闭 source map 并沿用自有代码混淆 |
| 回放辅助函数 | 23 个边界断言，包括专家生命周期共享 Span 时的 Path 配对 |
| API 离线八场景 | 8/8；实际 Graph、向量、MCP、HTTP 执行，导出事件数量一致，seq 不重复 |
| API DeepSeek 在线重点场景 | 2/2；工具链 42 事件 / 5 次模型请求，多 Agent 链 92 事件 / 9 次模型请求 |
| 浏览器场景 | 八个场景通过，包括人工填写反馈并恢复 |
| 浏览器回放 | 拖动到 0、下一节点、前一节点、播放/暂停、Live、订单专家路径与输入/输出配对通过 |
| 原页面导航 | 智能对话、知识库、数据与报告、模型与 MCP 均正常 |
| 响应式 | 1600、1280、1024、390 像素无文档横向溢出 |
| 浏览器控制台 | 应用错误 0，警告 0 |
| HTTP SSE 补发 | after=3 / Last-Event-ID=8 → first=9 / last=42 / count=34，no-cache / X-Accel-Buffering=no |
| 新建运行鉴权 | 无操作令牌返回 401，既有验证弹窗能签发令牌并继续运行 |
| 本地配置映射 | run-dev.ps1 -ValidateConfigOnly 通过，不输出配置值 |

## 重跑

```powershell
mvn -f backend/pom.xml package
npm run test:replay --prefix frontend
npm run build --prefix frontend
# ACTION_PASSWORD 由本地配置注入
pwsh -File scripts/verify-workflow.ps1 -OutputPath .runtime/verification-offline.json
pwsh -File scripts/verify-workflow.ps1 -Mode live -FocusOnly -OutputPath .runtime/verification-live.json
```

线上部署和运行核对在实际发布后记录，不能用本地通过替代生产检查。订单、退款和月报均是样例数据；当前知识索引不是语义 embedding。
