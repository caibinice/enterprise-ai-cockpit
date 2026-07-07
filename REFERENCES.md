# 参考项目与资料

建议优先看这些项目/资料：

1. Spring AI Alibaba 主仓库：智能体、RAG、模型接入与示例的核心入口。
   - https://github.com/alibaba/spring-ai-alibaba
2. Spring AI Alibaba Examples：官方示例集合，适合对照学习聊天、RAG、工具调用等模块。
   - https://github.com/spring-ai-alibaba/spring-ai-alibaba-examples
3. Spring AI Alibaba 官方文档站：查看版本、启动器、DashScope 配置与示例说明。
   - https://java2ai.com/docs/1.0.0.2/overview/
4. Spring AI 官方文档：学习 ChatClient、Embedding、VectorStore、Advisor 等通用抽象。
   - https://docs.spring.io/spring-ai/reference/
5. DashScope OpenAI-compatible API 文档：本 Demo 的真实 LLM 调用兼容这个接口。
   - https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope

本 Demo 为了保证“无 Key 也能跑起来”，没有强绑定 Spring AI Alibaba 依赖，而是先用 Spring Boot + MySQL + 兼容接口实现最小可运行闭环。后续可按 `README.md` 的升级方向迁移到 Spring AI Alibaba ChatModel / EmbeddingModel / VectorStore。
