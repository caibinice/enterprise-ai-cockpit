# 参考项目与资料

## 本次实现参考

1. 本机参考项目 `E:\codes\demo1\backend-spring-ai-alibaba`：其中的 `ChatClient.stream().content()`、WebFlux SSE、MCP STDIO 客户端写法和天气 MCP 示例已被核对并移植。
2. [Spring AI 官方文档](https://docs.spring.io/spring-ai/reference/)：`ChatClient`、OpenAI-compatible 模型、Embedding、VectorStore 和 MCP 抽象。
3. [Spring AI ChatClient 流式 API](https://docs.spring.io/spring-ai/reference/api/chatclient.html)：本项目用 `Flux` 接收真实模型流，不再对完整回答做本地切片。
4. [Spring AI PGVector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)：pgvector 扩展、向量维度和相似度检索约束。
5. [Spring AI MCP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)：MCP Client 与 `ToolCallback` 适配方式。
6. [DeepSeek API 中文文档](https://api-docs.deepseek.com/zh-cn/)：OpenAI-compatible base URL、`/chat/completions` 和 `stream=true`。
7. [Spring AI Alibaba](https://github.com/alibaba/spring-ai-alibaba)：智能体、RAG、模型接入和示例。
8. [Spring AI Alibaba Examples](https://github.com/alibaba/spring-ai-alibaba-examples)：聊天、RAG、工具调用等案例。

## 版本说明

- 当前后端使用 Spring Boot 3.5.3、Spring AI 1.0.0、Java 17。
- Spring AI 2.0.0 与当前 Spring Boot 3.x 依赖线不兼容，因此没有继续使用原来 profile 中的 2.0.0；这也是本次构建从 profile 切换到实际依赖时修正的一项。
- 远端 Alibaba Linux 3 的系统 PostgreSQL 为 11.15，无法直接使用 PGDG PostgreSQL 16；最终安装 pgvector 0.4.4，并使用 IVFFlat 索引。生产环境建议升级 PostgreSQL，再评估新版 pgvector/HNSW。

## 本地敏感配置

真实 SSH、MySQL、PostgreSQL 和 DeepSeek 配置仅保存在 `credentials/credentials.txt`，已被 `.gitignore` 排除；脱敏模板为根目录 `credentials.example.txt`。不要把 `crossborder-trend-report/credentials.txt` 或 demo 的真实 token 提交到仓库。
