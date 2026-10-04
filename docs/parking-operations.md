# 停车智能体业务服务与数据运维

2026-10-04。前端：[桌面](https://caibinice.com/smartParking/)、[手机](https://caibinice.com/smartParking/mobile)。后端仍是原 `enterprise-ai-cockpit` Java 进程；MySQL 与 pgvector 均复用，旧座舱和一期停车接口不变。

## 数据来源与可重复生成

源数据为 [UCI Parking Birmingham](https://archive.ics.uci.edu/dataset/482/parking%2Bbirmingham)，作者 Daniel Stolfi，观测来自 Birmingham City Council / NCP。原观测只覆盖 2016 年部分日间；本项目清除 385 条越界记录，提取工作日/周末的 15 分钟规律，生成缺失夜间与季节变化，并缩放到 A/B/C 的 120/100/80 个模拟泊位。UCI 标注 CC BY 4.0，原来源说明 UK OGL，署名与修改说明见 `scripts/data/sources/README.md`。

固定种子 20261003，数据集 `parking-year-v1`，范围 **2025-10-03—2026-10-02**。三类输出：105,120 条快照、167,906 次停车、365 条告警。65 次停车尚未离场，与最后三分区在场量 19/28/18 对应。所有车辆别名、日期、收费、告警与医院设施规则均是合成数据，标记 `database-synthetic`。大批派生文件输出到指定目录，不纳入 Git。

守恒式：`occupied[t] = occupied[t-1] + arrivals[t] - departures[t]`。费用逐次计算：30 分钟免费，其后每开始 30 分钟 2 元，每开始 24 小时上限 25 元。全年 167,841 次结算，模拟实收 352,094,400 分；日表、分区表与账本总额一致。该费率是演示规则。

```powershell
# 在仓库根目录；$Python 为安装 paramiko 的 Python
& $Python scripts/data/generate_parking_year.py --output E:/Documents/AI/codex/tmp/parking-year
& $Python scripts/data/test_parking_year.py
# 先备份，再仅新增表；不重启原服务
& $Python scripts/remote/prepare_parking_database.py
& $Python scripts/remote/import_parking_year.py E:/Documents/AI/codex/tmp/parking-year
```

Flyway V3 使用增量 `CREATE TABLE IF NOT EXISTS`。生成脚本保留源哈希、种子、转换和收费口径；导入按唯一键重试，数据集完成标记最后写入，核验三类行数。源哈希/种子与同名数据集冲突时终止。

## 权限与接口

生产前缀 `https://caibinice.com/smartCockpit/api/parking`。除登录外，**GET 也验证服务端签名令牌**。30 分钟令牌不信任浏览器自报角色；业务密码 BCrypt 保存，登录限流，账号禁用即时生效。管理员沿用原操作密码；访客无密码，仅具备公开导览权限。命名业务账号由管理员创建，不把密码写入源码。

| 角色 | 可用能力 |
| --- | --- |
| visitor | 脱敏空位快照、可达路线、联合推荐、访客导览、知识指南；无收费/记录/告警明细 |
| security | 以上加运营/夜间巡检、出入与告警、工单提交领取核验、自己的审计、模型/视觉问答 |
| operator | 以上加账本与历史分析、报表任务、工单批准复核、全局审计 |
| admin | 全部，加账号创建、版本化路径图维护、幂等 setup |

主要合同：

- `POST /login`：`username/password`；访客为 `visitor` 和空密码。
- `GET /catalog`、`/snapshot`：按角色返回工具目录、图、数据集和快照。
- `GET /route?from=entrance&to=emergency`、`/recommendation?destination=outpatient&preference=standard`。
- `GET /analytics?from=2025-10-03&to=2026-10-02&zone=A`；最多一年，结束日期包含当天。
- `GET /stays?page=1&size=20`、`/workorders`、`/audit`、`/report-jobs`。
- `POST /workorders`、`/workorders/{id}/transition`：确认、非空核验记录、请求幂等键/版本号与后端角色检查。
- `PUT /graph`：管理员提交当前版本及节点/边；乐观锁、坐标边界、图完整性检查。
- `POST /setup`：管理员幂等初始化图、独立知识库与四类汇总。
- `POST /ag-ui`：AG-UI RunAgentInput / SSE；`POST /feedback` 记录有限客户端工具结果。
- `POST /vision`：确认后发送 JPEG 场景截图与问题。

安保提交的工单依次经历 `pending_review → approved → assigned → resolved → closed`。运营负责批准和复核关闭；版本冲突返回 409，重复原幂等键返回原工单，已关闭告警的新草稿返回 409。业务账号密码至少 12 字符、UTF-8 最多 72 字节。完成的是模拟业务流程，不发送收费、抬杆或现场设备指令。

## 图、推荐与协议

图包含 17 个稳定节点，Dijkstra 排除关闭节点/边。距离按 20 米/模型单位估算。推荐过滤无空位、关闭与不连通分区，再考虑步行距离、充电能力、无障碍条件与急诊 C 区预留，返回解释与路线。配置不是实地测绘。

版本 2 坐标与三维前端的 `campus-layout.json` 同源：B 区 `(-8,-2)` 为俯视图右下停车区，C 区主点 `(10.8,5.2)` 在左上，辅助点 `(10.2,-1.9)` 在左侧。辅助点 ID 为 `parking-c-side`、类型 `service`，与主点共用 C 区容量，不额外累计 80 个泊位。老节点 ID 保持稳定，新增左侧道路连接点。新库首次初始化读取资源文件；已有图由管理员备份后带当前版本显式 `PUT /graph`，应用启动不自动覆盖人工维护的图。

AG-UI 按 [1.0 schema](https://github.com/ag-ui-protocol/ag-ui/blob/main/docs/spec/1.0/schema.mdx) 输出运行、消息、工具、状态事件。`CUSTOM parking.*` 承载业务报告、路线、草稿和引用。前端使用 `@ag-ui/core` 官方 schema，再检查白名单、目标、调用顺序、最大四个动作与重复 ID。适配器覆盖本服务产生的事件子集，并非宣称渲染全部协议事件。

快捷请求直接使用确定性业务计算。员工自由表达最多两个并发规划，默认 Flash + thinking max，Pro 可选；访客只使用本地知识指南。统计源只取数据库，报表和路径不由模型编造。先校验完整计划与全部结果再发出执行事件；请求停止后前端停止后续动作。消息事件承载完整结构化回答，不伪装逐 token 生成。每 10 秒心跳，总规划超时 100 秒；审计包含 runId、耗时和终止状态。

## 知识、语音、视觉与谷时任务

二期知识域 `smart-parking-agent-v2`，14 篇指南为原 5 篇适配版加 9 篇新指南：公开数据、角色、POI/路径、联合推荐、工单、账本、语音视觉、协议评测，以及区域空间定位与镜头操作。按标题幂等导入，不覆盖人工编辑，不退回全库检索；旧 v1 留作回滚。

语音在前端使用浏览器识别/播报，连续会话支持显式唤醒打断与回声过滤、旧规划取消；没有新增 LiveKit/ASR 常驻服务。实际识别率由浏览器、麦克风和网络决定，自动测试覆盖状态机，不把虚拟输入当真人声学测评。

视觉使用官方支持图像的 `deepseek-flash`，thinking max。截图仅三维画布，必须预览确认；上限 1 MB JPEG、1920 单边/200 万像素，一个并发、85 秒超时。服务不存图像，失败原样提示。Spring JSON 读取上限 2 MB，Nginx 精确视觉路由上限 2 MB；AG-UI 精确路由关闭代理缓冲。只有停车静态页开放 `microphone=(self)`。

现有 Spring 调度每天 **02:15 Asia/Shanghai** 刷新日/周/月/年四份 DB 汇总缓存，不调用模型、不启动新进程。`/report-jobs` 返回生成时刻，业务台可查询。固定数据集采样截止日不随着调度伪造增长。

## 发布、初始化与回归

开启 `PARKING_OPERATIONS_ENABLED=true`，图像模型 `PARKING_VISION_MODEL=deepseek-flash`，其余使用原数据库/模型配置。部署脚本已生成这两个环境项。顺序：后端完整测试/打包与 Vue 构建 → 原座舱发布 → 停车 Angular 发布/Nginx 更新 → 博客发布。

纯后端增量可使用 `scripts/remote/deploy_backend.py`：上传新 jar、校验 SHA-256、复制当前 release 的 Vue/MCP 内容、原子切换后只重启座舱。保留并校验现有 app.env/systemd 单元及其他业务服务 PID；旧 release 和服务器私有配置备份可回退。此入口不生成或修改环境项，首次部署仍用完整发布流程。

区域校准使用 `scripts/remote/sync_parking_layout.py --backup E:/Documents/AI/codex/backups/TASK/campus-graph.json` 只读预览；审阅后加 `--apply --expected-version CURRENT_VERSION --setup`，备份原图、保留稳定节点的关闭/服务属性、带版本更新并幂等补齐知识。额外自定义节点或被移除的关闭道路需要先人工合并，脚本不直接覆盖。

```powershell
& $Python scripts/remote/provision_parking_operations.py --accounts-file E:/Documents/AI/codex/backups/parking-operations-20261003/parking-accounts.json
& $Python scripts/remote/evaluate_parking_operations.py --accounts-file E:/Documents/AI/codex/backups/parking-operations-20261003/parking-accounts.json --llm --workflow --output E:/Documents/AI/codex/tmp/parking-operations-20261003/online-evaluation.json
```

初始化脚本沿用私有账户文件；密码不进日志和报告。回归覆盖拒绝越权、角色签名、脱敏、金额一致、路径/推荐、三线路导览、四类报表、两个真实 Flash 复合计划与完整工单闭环。`--workflow` 只操作合成告警，会新增一条已关闭测试工单与审计。

发布前备份数据库、app.env、systemd、Nginx conf.d/snippets 与三个旧 release 指针。座舱发布失败自动恢复旧 env/unit/release；停车脚本原子切换并回滚 Nginx。数据库新表和 v2 知识库为增量，切回旧版本后保留即可；需要全量恢复时用发布前 SQL 备份，并同步检查 pgvector 文档状态。验证详见 [停车项目二期记录](https://github.com/caibinice/3dSmartParking/blob/main/docs/parking-operations-validation.md)。
