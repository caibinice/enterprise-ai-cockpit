# 生产部署

前端固定发布到 `/smartCockpit/`，API 与 SSE 统一使用
`/smartCockpit/api/`，由 Nginx 代理到 `127.0.0.1:8080`。SSE location
必须关闭 buffering 和响应缓存，并把读取超时延长到 10 分钟。

本地使用 Java 17、Maven 与 Node 完成测试和构建，只把可执行 JAR 和
`frontend/dist` 上传到：

```text
/opt/enterprise-ai-cockpit/releases/<commit>/
```

服务器只安装 OpenJDK 17 headless。环境变量保存在
`/opt/enterprise-ai-cockpit/shared/app.env`，生产示例见
`deploy/application-production.env.example`。真实 MySQL、PostgreSQL、
DeepSeek/OpenAI-compatible 和 embedding 密钥不得提交。

systemd 模板限制 Java 堆为 160MB、RSS 上限为 320MB，并把数据库池和
Quartz 线程都限制为 2。Nginx 公开 `/smartCockpit/`，WebFlux
`ActionAuthWebFilter` 拦截聊天、上传、写入和删除等浏览器请求；前端通过
`/api/action-auth/verify` 换取 30 分钟签名令牌。`ACTION_PASSWORD` 和
`ACTION_TOKEN_SECRET` 只保存在 `shared/app.env`。Quartz 直接调用服务层，
不经过该过滤器。

前端关闭 source map，只混淆自有业务 chunk；Element Plus、ECharts 和 Vue
保留为独立 vendor chunk。混淆不能替代后端验证。

每次发布先创建新 release，再原子切换 `current`，最多保留五版。回滚
时切回上一软链接、重启服务，并验证：

```bash
curl -fsS http://127.0.0.1:8080/api/health
```

不要在公网安全组放开 8080。
