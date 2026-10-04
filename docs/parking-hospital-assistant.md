# 停车院内服务工作流

更新日期：2026-10-04

## 查询分层

`ParkingWorkflowService` 优先处理明确的业务意图：常州天气 MCP、数据库停车推荐、快捷工具指令、脱敏医院 FAQ、点位/道路导航。只有业务账号的复杂组合问题进入模型规划。访客角色仍不调用付费模型，权限由后端签名角色控制。

自然语言先解析目的地和泊位偏好，并通过 `parking.preferences` 事件同步前端。历史用于“那住院楼呢”等追问，不作为事实来源。模型提示词要求先结论、后必要步骤、只引用相关证据，不虚构余号、天气、已预约/已派单或已到达；工具数量和目标白名单继续在前后端核对。

停车模型 JSON 请求预算为 60 秒，工作流最多 100 秒，客户端最多 110 秒。JSON 兼容回退只针对 HTTP 400/422，并共享原请求的剩余时间；超时、429、5xx 和解析失败不额外等待一轮。失败使用友好回退，不执行未验证动作。其他业务保留默认 120 秒总预算。

## 单一脱敏数据源

`backend/src/main/resources/parking/hospital-guide.json` 提供结构化科室/病区及 8 篇知识文档。`ParkingHospitalService` 用它作直接 FAQ，`ParkingKnowledgeService.bootstrap` 用同一内容导入独立 `smart-parking-agent-v2` 知识库。新增文档 metadata：

```json
{"domain":"smart-parking","topic":"hospital","sourceType":"anonymized-demo","status":"active","version":"2026-10-04"}
```

总文档数由 14 增至 22，按标题幂等新增，不覆盖已有手工文档、v1 库或其他企业库。FAQ 使用结构化资源，知识库提供证据引用；调整直接 FAQ 时同时修改该资源并发布，避免只编辑数据库文档却认为直接答案已同步。

内容覆盖：科室楼层、预约报到、虚构医师排班、变更退号、病区病房探视、入出院、缴费检查取药、急诊与无障碍。名称、医师、地址均脱敏，科室位置和房间为虚构演示，不含真实患者数据。实际参考的是公开流程，不复制真实院区的医生列表或地址。

## 天气服务

`ParkingWeatherService` 复用已有 `McpToolService.queryWeather("常州")`，没有新增服务进程或数据库天气假数据。日期始终按 `Asia/Shanghai`，校验来源、城市、采样时间和气象数值，只有今天且 45 分钟内的数据可回答。两分钟进程缓存、单查询并发门槛及 18 秒查询预算避免挤占模型规划时间。近期缓存回退明确标注更新时间；没有有效结果时不编造温度。

## 验证命令

```powershell
# 后端单元测试及可执行 JAR
mvn test package

# 只使用公开访客登录，无需私有口令
python scripts/remote/evaluate_parking_hospital.py --weather --output RESULT_PATH.json

# 原有权限、账本、AG-UI 和真实模型规划回归
python scripts/remote/evaluate_parking_operations.py --accounts-file PRIVATE_ACCOUNTS.json --llm --output RESULT_PATH.json
```

本轮 67 项后端测试、31 项既有线上 API 用例、10 项新增院内查询用例通过。新增用例覆盖常州当前天气、引用作用域、虚构医师、病房、推荐追问、起终点及结束动作。前端故障注入另覆盖结束事件后的网络断开和提前 EOF。

## 资料与回滚

参考：[公开就医服务](https://www.czzyy.com/)、[门诊指南](https://www.czzyy.com/jkapi/api/home/category/detail?id=15)、[住院指南](https://www.czzyy.com/jkapi/api/home/category/detail?id=17)、[挂号指南](https://www.czzyy.com/jkapi/api/home/category/detail?id=19)、[Open-Meteo](https://open-meteo.com/en/docs)。

发布脚本保留旧 release 并在健康检查失败时自动回滚，配置、MCP 目录和其他服务不变。知识新增为文档 ID 45–52；本地私有备份记录新增 ID 和导入前内容，若需要数据回滚仅删除该 8 篇本轮新增文档，不删除整个知识库。
