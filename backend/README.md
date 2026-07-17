# Backend

Spring Boot 3.5.3 + Java 17 的 WebFlux API。后端现在默认按真实持久化链路配置：MySQL/MariaDB 保存业务数据，PostgreSQL + pgvector 保存向量；无数据库演示可切换 `memory`。

## 关键实现

- `JdbcEnterpriseRepository`：实现知识库、文档、分块、metadata、数据源、报告和聊天消息的 JDBC CRUD。
- `InMemoryEnterpriseRepository`：仅在 `APP_REPOSITORY_MODE=memory` 时生效。
- `EmbeddingService`：默认确定性 local embedding，维度 1536；可切换 OpenAI-compatible `/embeddings`。
- `PostgresVectorIndexService`：写入 `enterprise_ai_vectors`，使用 cosine distance 检索；异常时回退 MySQL 关键词检索。
- `SpringAiModelGateway`：`ChatClient.stream().content()` 输出真实 Reactor 流。
- `OpenAiCompatibleModelGateway`：可选的直接 WebClient SSE 网关，使用 `LLM_PROVIDER=openai-compatible`。
- `McpWeatherService`：懒初始化 Spring AI MCP STDIO 客户端，连接 `mcp-servers/weather-mcp-server.js`。
- WebFlux 控制器将 JDBC、Tika 和语音阻塞操作调度到 `boundedElastic`；批量文档上传使用临时文件串行解析，避免把整批文件同时聚合到堆内存。

## 配置

| 变量 | 默认值 | 作用 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP 端口 |
| `APP_REPOSITORY_MODE` | `mysql` | `mysql` 或 `memory` |
| `MYSQL_URL` | localhost MySQL URL | 业务数据库 JDBC URL |
| `MYSQL_USER` / `MYSQL_PASSWORD` | `enterprise_ai_cockpit` / `change-me` | MySQL 账号 |
| `VECTOR_ENABLED` | `false` | 是否启用独立 pgvector 连接 |
| `VECTOR_DATABASE_URL` | localhost PG URL | 向量数据库 JDBC URL |
| `VECTOR_DATABASE_USER` / `VECTOR_DATABASE_PASSWORD` | `enterprise_ai_cockpit` / `change-me` | PG 账号 |
| `EMBEDDING_MODE` | `local` | `local` 或 `openai-compatible` |
| `EMBEDDING_DIMENSIONS` | `1536` | 必须与 PG vector 列一致 |
| `LLM_ENABLED` | `false` | 是否启用真实模型 |
| `LLM_PROVIDER` | `spring-ai` | `spring-ai` 或 `openai-compatible` |
| `OPENAI_BASE_URL` | `https://api.deepseek.com` | OpenAI-compatible 服务地址 |
| `OPENAI_API_KEY` | `demo-key` | API token |
| `LLM_MODEL` | `deepseek-v4-flash` | 模型名 |
| `MCP_ENABLED` | `false` | 是否启用天气 MCP |
| `MCP_WEATHER_SERVER` | `mcp-servers/weather-mcp-server.js` | STDIO MCP server 路径 |

不要把真实值写进 `application.yml`。本机凭据放在项目外的 `credentials/credentials.txt`，该目录已被 Git 忽略。

## 数据库初始化

```powershell
# 在远端或目标 MySQL 执行
mysql --host <host> --port 3306 --user enterprise_ai_cockpit --password enterprise_ai_cockpit < ..\database\mysql\enterprise_ai_cockpit.sql

# 在远端 PostgreSQL 执行
psql --host <host> --port 5432 --username enterprise_ai_cockpit --dbname enterprise_ai_cockpit_vector -f ..\database\postgresql\enterprise_ai_cockpit_vector.sql
```

同内容的 Flyway MySQL 迁移位于 `src/main/resources/db/migration/mysql/`，运行时已包含 Flyway 11 所需的 `flyway-mysql` 模块；pgvector 迁移位于 `src/main/resources/db/migration/postgresql/`。默认关闭 Flyway，避免未提供密码时启动失败。

## API

- `GET /api/health`：Repository、pgvector、MCP 连接状态和数量统计。
- `POST /api/chat`：聚合聊天结果。
- `POST /api/chat/stream`：WebFlux SSE；真实模型时 token 来自上游流。
- `GET /api/mcp/status`：MCP 客户端配置/工具状态。
- `GET /api/mcp/weather?city=常州`：真实 MCP 工具调用。
- `/api/admin/knowledge-bases`、`/api/admin/documents`：知识库和文档管理。
- `/api/admin/data-sources`、`/api/admin/report-templates`、`/api/admin/report-runs`：数据源与报告管理。

## 启动示例

内存模式：

```powershell
$env:APP_REPOSITORY_MODE = 'memory'
$env:VECTOR_ENABLED = 'false'
$env:LLM_ENABLED = 'false'
mvn spring-boot:run
```

真实远端模式：

```powershell
$env:APP_REPOSITORY_MODE = 'mysql'
$env:MYSQL_URL = 'jdbc:mysql://<host>:3306/enterprise_ai_cockpit?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false'
$env:MYSQL_USER = 'enterprise_ai_cockpit'
$env:MYSQL_PASSWORD = '<mysql-password>'
$env:VECTOR_ENABLED = 'true'
$env:VECTOR_DATABASE_URL = 'jdbc:postgresql://<host>:5432/enterprise_ai_cockpit_vector'
$env:VECTOR_DATABASE_USER = 'enterprise_ai_cockpit'
$env:VECTOR_DATABASE_PASSWORD = '<postgresql-password>'
mvn spring-boot:run
```

## 测试

```powershell
mvn test
```

已用真实远端 MySQL 和公开地址直连远端 PostgreSQL 做过链路测试：写入文档后 `/api/health` 能显示 `JdbcEnterpriseRepository`、`pgvector=0.4.4` 和向量行数增长；聊天 SSE 返回 `meta/token/references/done`；DeepSeek 上游流没有触发 fallback；MCP 实测发现并调用 `queryWeather`。另在远端临时空库验证 Flyway 自动创建 7 张业务表和 `flyway_schema_history`，测试后已删除临时库。

## 已知边界

1. 默认 embedding 只是可复现的本地实现，不等于生产语义 embedding。
2. 报告数据源抽取、cron 任务和语音仍为 Mock/MVP 实现。
3. 安全配置仍允许匿名访问，仅适合演示；生产需要认证、授权、密钥托管和最小权限。
4. PostgreSQL 5432 已能远端直连；云安全组需要限制来源地址，生产优先使用内网或 TLS，避免把数据库长期暴露给公网。
