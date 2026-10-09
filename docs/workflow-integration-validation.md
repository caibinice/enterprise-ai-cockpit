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

订单、退款和月报均是样例数据；当前知识索引不是语义 embedding。

## 生产发布后核对

- 已推送功能提交 `50803b7`，已激活 release `20261009111652-50803b7`。
- 公网入口：`https://caibinice.com/smartCockpit/workflow`。
- 公网离线八场景 8/8；在线工具 / 多 Agent 2/2（42 / 92 事件，5 / 9 次模型调用）。两组验证并行进行，全部完成。
- 原聊天 Flash SSE 仍返回 `meta/token/references/done`；真实天气 MCP 返回 Open-Meteo 常州天气，原高德/时间/计算器工具目录保留。
- 原数据库健康：5 个知识库、44 篇文档、47 个向量分块，MySQL/pgvector 连接正常；未新增数据库迁移。
- 线上历史链路实际补发：after=3 / Last-Event-ID=8 → seq 9–42 共 34 条。
- 使用复用 TLS 连接验证真实增量 SSE：第一个事件约 31ms 到达，此时查询运行状态仍为 RUNNING，之后连续收到 seq 1–74，无缺口。冷连接的建立耗时与事件推送耗时分别测量。
- 生产构建浏览器控制台 0 错误 / 0 警告；已截取实际线上工具循环、三专家协作、节点图和游标 41/92 的订单专家回放。
- 座舱 MainPID 切换到 837167；Nginx 458001、量化 457945、跨境 328004 保持原 PID。未改 Nginx 配置，未重启其他服务。
- 工作流 JSON 保存在 `/opt/enterprise-ai-cockpit/shared/workflow/runs`，发布后已确认文件落盘；新 Java 进程、三个 stdio Node 进程共同受原 systemd 内存/任务上限约束。

本次本地验证日志位于 `E:\Documents\AI\codex\tmp\cockpit-workflow-20261009`。本地源码回滚快照是 `E:\Documents\AI\codex\backups\cockpit-workflow-20261009\cockpit-before.zip`；远端上一 release 是 `/opt/enterprise-ai-cockpit/releases/20261004134502-94cf09c-backend`，发布脚本在 `backups/release-20261009111652-50803b7` 保留旧环境与 systemd 单元。
