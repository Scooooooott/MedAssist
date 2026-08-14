# M0-M5 进入 M6 前收尾审查记录

## 1. 审查范围

本记录基于以下材料和当前工作区实现形成：

- `REQUIREMENTS-FULL.md` 及其 M0-M5 需求条目；
- `doc/` 下 M0-M5 阶段分析、实现、验收和问题记录；
- Java、Python、前端、协议、部署编排、治理脚本和测试代码；
- 当前代码质量、架构规则、治理扫描和单元测试结果。

本轮目标是把 M0-M5 中可以在本地代码范围内闭环的问题先修复，并把依赖真实基础设施、真实数据、外部模型或生产操作的问题单独记录，不把本地单元测试误认为端到端验收。

## 2. 结论摘要

M0-M5 的核心代码路径目前可以作为进入 M6 的开发基线：Java 全量 Maven 验证、架构规则、治理脚本、策略编译测试、迁移边界检查和格式检查均通过。本轮没有发现需要阻断 M6 的已确认本地编译或单元测试缺陷。

同时，以下事项仍然不能宣称已经完成：

1. 真实 MinIO、Postgres、Redis、Redpanda、Keycloak、OTel、Toxiproxy 和真实模型服务的联合验收；
2. 解析器读取对象时的不可变版本绑定；
3. Redis 在终态写入失败时的远端会话收敛；
4. Gateway 入口到下游 OTel trace context 的真实链路验证；
5. 真实医学语料、模型、评测集和大规模性能基线；
6. 生产密钥、证书、issuer/JWK、topic、容量和数据保留策略。

这些是下一阶段的集成、数据或生产准备事项，不应通过继续堆叠本地 mock 来伪装成已经完成。

## 3. 本轮已修复

| 阶段/需求关注点 | 问题 | 修复结果 |
| --- | --- | --- |
| M0 治理扫描 | 路径排除规则可能误排 `services/clinical-data`，且只按简单前缀判断 | `scripts/scan_language.py` 改为按完整路径组件和明确前缀排除；公共 `docs/` 仍扫描，内部 `doc/` 可按治理规则排除；补充 4 个回归测试 |
| M0 治理扫描 | 扫描脚本和治理词汇检查中存在编码/乱码信号 | 统一脚本和 Java 路由分类器中的字符串表达，保留已有治理信号；语言扫描、治理词汇检查通过 |
| M0 架构规则 | 集合字段规则只覆盖原始 `Collection`，对 `List`、`Set`、`Map` 的判定不完整 | 扩展 ArchUnit 规则并修正谓词组合；架构模块 6 个测试通过 |
| M1 检索/生成 | 旧 Retrieval `/internal/api/answer` 和 `/answer/stream` 可以绕过 Agent 安全路径 | 旧接口增加显式配置开关，默认关闭并返回 HTTP 410；保留显式兼容开关以便迁移，不再默认形成旁路 |
| M1 检索 | 引用 quoted span 只做过于严格的原始字符串包含判断 | 在不放宽语义的前提下规范化大小写、空白和常见 Unicode 标点；补充正例和标点缺失反例 |
| M1 检索 | 重试时 `topK` 增长没有统一上限 | 在请求模型和执行器中统一设置上限 50，并为多次重试补充测试 |
| M1/M2 版本管理 | `DocumentVersion` 的 effective date 被强制非空且 stale 判断使用 retrievedAt，无法表示 Unknown | effective date 改为可空；领域判断对未知日期 fail closed；Retrieval 服务向视图暴露 Unknown 语义；补充 Clock 固定的领域和服务测试 |
| M2 版本管理 | Controller 直接访问 repository，跨层边界不清晰 | 新增 `DocumentVersionService` 作为服务边界，Controller 只依赖服务；补充服务和 Controller 测试 |
| M2 数据一致性 | discovery 后对象内容可能变化，解析前仍可能处理不同内容 | 解析前重新计算 SHA-256，与 discovery 指纹比较；不一致或读取失败进入安全隔离路径，错误信息不包含原文；补充变更对象测试 |
| M3 Agent | 策略编译器生成的 tool map 没有被运行时真正消费，运行时仍有硬编码分类 | 编译产物增加按角色的聚合域信息；Agent 启动时加载 `governance/tool-map.json`，校验 hash、scope 和 JSON 结构，缺失或非法时 fail closed；补充治理脚本测试 |
| M3 Agent | LLM Gateway 通过原始线程池创建执行器，绕过统一上下文传播 | 改为由 `ExecutorFactory` 注入显式虚拟线程执行器 |
| M3 Agent | Generation scheduler 直接使用 JDK 原始 scheduler，缺少统一上下文装饰 | 新增 `ContextAwareScheduledExecutorService` 并通过 `ExecutorFactory` 创建 generation scheduler |
| M3 Agent | `Last-Event-ID` 超长数字可能在 replay 比较时触发 5xx | 在访问 store 前校验两段数字、长度和 `long` 范围；补充溢出输入测试 |
| M4 安全 | Servlet 资源服务器只要求 authenticated，未强制 issuer、audience、subject 和唯一已知 MedAssist role | 增加 issuer/audience validator；拒绝缺失 subject、未知角色和多角色 token；Gateway 同步增加 issuer/audience 校验和缺失 JWK 的 fail closed 行为 |
| M5 可观测性 | DLQ pending 指标被用于表示路由次数，无法表达 broker 深度 | 分离 routed counter 与 broker-reported pending gauge；增加告警和 runbook 说明，避免把本地动作计数误报成队列深度 |

## 4. 当前已验证的验收结果

### Java/Maven

以下命令通过：

```text
mvn -Djacoco.skip=true verify
mvn checkstyle:check
mvn spotless:check
```

Maven reactor 的 13 个模块全部 `SUCCESS`，包含 Contracts、Domain Model、Common Library、Audit Client、Gateway、Identity Policy、Ingestion、Clinical Data、Retrieval、Agent、Audit Governance 和 Architecture Tests。

本次关键测试数量包括：

- Ingestion：154 个测试通过；
- Clinical Data：34 个测试通过；
- Gateway：11 个测试通过；
- Retrieval：包含旧接口默认 410、版本服务和检索回归测试；
- Architecture Tests：6 个测试通过；
- 其余模块均在全量 reactor 中通过。

### Python/治理

以下检查通过：

```text
python scripts/scan_language.py
python scripts/check_governance_wording.py
python scripts/check_forbidden_data.py
python -m unittest discover -s scripts/tests -v
python -m pytest scripts/governance -q -p no:cacheprovider
python ops-console/scripts/check_migrations.py
git diff --check
```

结果为：治理检查通过、敏感数据检查通过、脚本测试 4/4 通过、策略测试 8/8 通过、迁移边界检查通过。Git 检查只产生 Windows 换行提示，没有 whitespace error。

此前阶段记录中已通过的前端、Python 服务、integration-smoke 和 compose profile 检查，本轮未重复执行；它们仍应在合并前按对应阶段记录复核。

## 5. M6 前仍需处理的事项

### P1：解析对象必须绑定不可变版本

当前修复只是在解析前增加 hash re-check。解析客户端和 ParserRequest 仍以 URI 为主要读取依据，discovery、hash 校验和 parser 实际读取之间仍存在最后一个竞态窗口。

建议路径：

1. 在 discovery 结果中保存 object version/ETag；
2. 扩展 parser contract，使 ParserRequest 携带 version/ETag；
3. Parser 对象读取必须使用 conditional read 或 immutable version read；
4. 对象版本不匹配时返回可重试的稳定错误，不进入普通解析结果；
5. 增加 MinIO/Testcontainer 级别的替换对象测试。

这是协议和集成测试任务，不宜只在当前 Processor 中继续增加字符串校验。

### P1：Redis 终态写失败后的远端会话收敛

`GenerationSessionService` 在 store 终态写失败时会停止本地 runtime，但 Redis 中的 RUNNING 状态可能暂时保留到 TTL。当前行为能够避免本地继续生成，却不能保证远端状态立即转为 FAILED。

建议路径：

1. 明确 Redis 不可用时的客户端可见错误和重试语义；
2. 采用幂等终态记录、补偿任务或独立 reconciliation marker；
3. 保证补偿不会覆盖 CANCELLED、EXPIRED 或已经成功的终态；
4. 用 Toxiproxy/真实 Redis 验证终态写入失败、恢复和重复补偿。

### P2：Gateway traceparent 的端到端绑定

Gateway 会校验或生成 `traceparent` 并回写请求/响应头，共享 tracing interceptor 也已覆盖 gRPC client/server。但当前缺少 Gateway WebFlux 入口、下游调用和 OTel exporter 的完整链路断言，不能仅凭 header 存在就证明 trace parent 正确绑定。

建议增加一个真实 WebTestClient + OTel test exporter + 下游 stub 的测试，确认：入口 traceparent、Gateway span、下游 gRPC span、审计事件中的 traceparent 属于同一 trace，并且响应头不泄露额外敏感上下文。

### P2：DLQ broker 深度和生产 transport

当前代码已经把 routed counter 与 pending gauge 分离，但 pending gauge 的真实值仍依赖 Redpanda/Kafka consumer 或运维采集器上报。审计 transport 和 DLQ topic 的生产默认值、权限、保留策略及 broker depth 采集需要真实环境确认。

### P2：M0 bootstrap 的基础设施语义

`just bootstrap` 目前主要完成本地检查、依赖和配置准备，不等价于启动所有基础设施。需求中若把 bootstrap 理解为“一键启动完整环境”，需要在 M6 前统一命名或补充明确的 `just infra-up`/profile 工作流；这属于开发体验和环境编排事项。

### P2：真实数据、模型和质量基线

需要单独完成真实医学来源的授权、下载、原始文件隔离、hash/版本登记、解析适配、去标识化、人工抽样、索引构建、评测集和质量阈值。当前代码只证明了数据管道和边界逻辑，不代表已经具备可用于临床研究的真实语料或质量结论。

### P2：生产配置和运维操作

以下内容暂不在本轮本地修复范围内，进入 M6/M7 的环境清单：Keycloak issuer/JWK 与 audience、TLS、数据库迁移执行、Redis/Redpanda ACL、密钥轮换、OTel collector、MinIO bucket policy、备份恢复、数据保留和容量告警。

## 6. 进入 M6 的建议门槛

可以开始 M6 的业务开发，但建议把以下三项作为 M6 前置验收而不是事后补充：

1. 先冻结并升级 parser contract 的 object version/ETag 语义；
2. 用真实 Redis/MinIO/Redpanda/OTel 运行一次故障和链路集成测试；
3. 为真实数据集建立来源、许可、hash、版本和质量审计记录。

如果 M6 直接依赖这些能力，则应先把对应集成任务拆成 M6 的最早子任务；否则当前 M0-M5 本地代码基线已经足够支撑继续实现 M6 业务功能。
