# 三维停车智能体接口

2026-10-03。该业务域直接在本项目现有 Spring Boot 进程运行，无独立后台服务。

前端：[三维停车场](https://caibinice.com/smartParking/)、[手机版](https://caibinice.com/smartParking/mobile)。
完整业务与 GitHub 调研：[停车项目设计文档](https://github.com/caibinice/3dSmartParking/blob/main/docs/parking-agent-design.md)。

## 复用项

- `ActionAuthWebFilter`：新增 POST 接口仍使用既有操作密码的 30 分钟 Bearer 令牌。
- `ModelGateway` / `ChatModelCatalog`：Flash 默认、Pro 可选、Thinking max；不在前端放密钥。
- `KnowledgeBaseService`：仅检索 `smart-parking-agent-v1` 独立知识库及 `domain=smart-parking` 文档，复用混合检索和向量索引。
- 原 MySQL、pgvector、Nginx 与 systemd 单元。新增领域类位于 `backend/src/main/java/com/example/aiagent/parking/`。

## 新接口

生产前缀：`/smartCockpit/api`。

- `GET /parking-agent/catalog`：目录，只读。
- `POST /parking-agent/knowledge/bootstrap`：幂等新增 5 篇指南，已有同标题文档保留；先调用原 `/action-auth/verify` 获取令牌。
- `POST /parking-agent/stream`：接收有边界的演示快照和近期对话，输出 SSE `meta/plan/action/report/references/answer/done`，规划期间有心跳。

请求必须明确 `source=browser-demo`，A/B/C 三个分区容量和占用、ISO 采样时间、最多 30 条出入记录、10 条告警与 8 条对话。超过 120 秒的旧快照、重复分区和越界占用量会被拒绝。当前统计不是可信生产数据；接入真实设备时应让服务读取权威停车数据适配器，替换浏览器快照来源。

程序计算报表与推荐，语言模型不生成统计数值。模型最多规划 4 个白名单动作，前端再校验并执行；未知工具、任意坐标、代码或额外参数不会进入执行器。后端规划同时最多 2 个模型请求，单次助手请求 100 秒超时。精确快捷指令不调用模型，避免增加成本。

这里的 SSE 输出结构化完整回答，未修改一般企业聊天的真实 token 流。场景动作只改变展示，不抬杆、收费或确认告警。最近对话和浏览器执行结果由前端限量传入，本版本不另建持久化停车会话表。

## 语音说明

停车前端使用真实浏览器识别与播报；本项目原 `SpeechService` 仍是模拟接口，这次未把它宣传成实时 ASR/TTS。Nginx 停车页面专门允许同源麦克风，其他站点策略保持原值。将来若接入后端 ASR 或全双工语音，可继续在同一服务增加适配器，并按实际供应商能力和资源验证。

## 发布顺序

1. Maven 全量测试与打包，照原部署脚本更新当前座舱服务。
2. 认证后调用 `knowledge/bootstrap`，再次调用验证 `imported=0`，确保幂等。
3. 停车项目生产构建与原子发布；它的 `deploy/nginx-location.conf` 增加专用无缓冲 SSE 代理，保留安全响应头。
4. 测试未授权 401、快捷报表、真实模型复合规划、知识引用与其他服务健康。

本地验证与备份记录归档于 `E:\Documents\AI\codex\tmp\parking-agent-20261003` 和 `E:\Documents\AI\codex\backups\parking-agent-20261003`，其中私有令牌和操作密码不提交。
