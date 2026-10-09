# Agent Studio 完整迁移复核

复核日期：2026-10-09。来源是 `E:\codes\agentDemo`，目标是本仓库原生 Vue/WebFlux 模块 `/smartCockpit/workflow`。

## 迁移范围与逐项验收

迁移的是 `CustomerServiceGraph`、三个专家图、人工恢复图和整套工作台；工具循环与多 Agent 是分享重点，不是功能裁剪范围。主图定义包含 16 个展示节点、28 条候选边和三个独立专家图。

| 原 Agent Studio 栏目或能力 | 座舱入口 | 本次实际验收 |
|---|---|---|
| 编排工作台 | 智能体工作流 → 编排工作台 | 真实 Graph 调度、模式/知识范围/预算/故障配置 |
| 产品知识 RAG | 场景 01 | 知识来源 KB001、检索结果、回答与评审 |
| 图片与文字 | 场景 02 | 内置图片校验、E02 观察进入状态、后续 RAG |
| 企业报表 MCP | 场景 03 | 独立 stdio 工具发现与调用、实际样例营收 |
| 工具调用循环 | 场景 04 | 两轮 HTTP 执行、599 元只读退款试算 |
| 多 Agent 协作 | 场景 05 | 三个 CompiledGraph 顺序执行、专家路径及内部循环 |
| 人工接管 | 场景 06 | 等待 → 人工填写反馈 → 恢复图完成 |
| 反馈驱动重检索 | 场景 07 | 两次 retrieve，查询文本确实改变 |
| 工具异常与预算 | 场景 08 | 3 轮恢复；2 轮转人工并可停止 |
| 演示知识 | 演示知识页 | 搜索、新增、TXT/MD 导入并建立向量；写入只在本地验收目录进行 |
| 分享导览 | 分享导览页 | 六节内容、两个重点案例加载按钮 |
| 最近运行 | 场景栏下方历史条 | 刷新、重新加载、保留 Run URL |
| 节点监控与回放 | 所有八个运行共用 | 逐项检查事件序号、节点输入输出、退到 0、下一节点、Live、JSON 导出 |
| 演示模式与响应式 | 工作台工具栏 | 收起/恢复场景栏；1600/1280/1024/390 无页面横向溢出 |

前端逐项验收 8/8，通过预算与评审返工额外用例；浏览器 pageerror 为 0。回放辅助函数 23 个边界断言与前端生产构建通过。

## 本次补齐

- `WAITING_HUMAN` 原来只有新建运行按钮，没有停止按钮。本次让运行中与等待人工均可停止，同时保留等待时创建新运行的能力；在两轮预算耗尽后实际点击停止并核对 CANCELLED。
- 将工作台说明改为八场景共享主图，避免把两个重点面板误读为全部功能。
- 完整在线回归中发现既有 `TasksMax=90` 会触发 native thread 创建失败。只将座舱服务额度改为 128，原 CPUQuota、堆、MemoryHigh/MemoryMax 保留；不调整其他应用的资源限制。

## 验证材料与复现

本次日志位于 `E:\Documents\AI\codex\tmp\blog-agent-publish-20261009`，包括 `audit-full-ui.log`、`production-all.json` 和生产构建日志。

```powershell
# 从本地凭据向进程注入 ACTION_PASSWORD，不写入仓库
pwsh -File scripts/verify-workflow.ps1 -BaseUrl https://caibinice.com/smartCockpit
pwsh -File scripts/verify-workflow.ps1 -BaseUrl https://caibinice.com/smartCockpit -Mode live
npm run test:replay --prefix frontend
npm run build --prefix frontend
```

离线模式仍实际运行 Graph、向量检索、MCP 和 HTTP；在线模式增加真实模型和视觉请求。订单、报表、知识仍是隔离样例，退款仍为试算；回放不重执行业务，人工恢复不是任意节点的原生检查点续跑。
