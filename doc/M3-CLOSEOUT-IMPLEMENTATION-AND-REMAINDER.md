# M3 收尾实现与剩余工作记录

更新时间：2026-08-10

## 1. 结论

本轮完成了 M3 中可以在当前仓库内独立完成的代码、边界校验和测试补全。M3 的核心安全路径已经从“默认拒绝/内存桩”推进到具备真实适配器、状态投影隔离、结构化生成校验、临床数据 Safe Harbor 持久化和受限结构化查询的实现。

但是，M3 暂不应标记为生产验收完成。仍有若干任务需要真实 PostgreSQL 权限、Spring AI/MCP 运行时接入、真实模型和数据授权，或需要跨服务 Testcontainers/E2E 证据。这些不能由当前本地代码测试替代，已在第 5 节单独列出。

## 2. 本轮实现

### 2.1 Agent 生成、引用和会话上下文

- `LlmDraftGenerator` 通过 `LlmGateway` 生成严格 JSON；`StructuredDraftParser` 只接受 `answer` 和带 `chunkId`/`quotedSpan` 的引用结构。
- `StructuredDraftVerifier` 要求引用 chunk 来自当前执行轮次的 transient runtime evidence，并要求 quoted span 原文包含于对应 chunk；无证据、伪造 chunk、无效 span 均 fail-closed。
- 检索 evidence 在进入 LLM provider 前经过 `PromptInjectionDetector`；检测到指令劫持、边界伪造、工具滥用等模式时阻断 provider 调用。当前仍缺少分类结果的持久化审计和红队评估闭环。
- runtime evidence 经过 `ToolResultProjector`、`ToolExecutionResult`、`AgentState` 和 `AgentGenerationContext` 传递，但不进入 `AgentStateProjection` 和 checkpoint 序列化。
- 新增有界 `ChatMemory`/`InMemoryChatMemory`。会话 ID 只接受受限安全字符；消息数和字符数均有上限；仅保存已去标识化的用户问题和成功回答。历史会话作为独立的非证据段进入 LLM prompt，不参与 citation 验证。
- Agent 工具执行改用固定大小 executor，并通过 `AgentThreadContext` 传递 trace/request/role；生产配置不再依赖 `ForkJoinPool.commonPool()`。
- 保留原有的 execution step/retry/timeout 状态机；重复工具执行不会重复加入同一 chunk 的 runtime evidence。

### 2.2 临床数据 Safe Harbor 持久化和查询

- 新增 `ClinicalImportPersistencePort`、`ClinicalBundleImportService` 和 JDBC 适配器。持久化只接受 Safe Harbor 投影字段，不落 raw FHIR payload、完整出生日期或完整 ZIP。
- 新增 `V6__m3_clinical_safe_harbor_import.sql`：临床资源表、quarantine 表、导入运行表以及三个研究聚合视图均具备主键/约束；`(source_id, resource_id)` 用于重跑幂等。
- JDBC 临床持久化在事务内写入导入运行、资源和 quarantine 记录；无 JDBC/事务基础设施时不强行启动该适配器。
- 结构化查询在 JDBC 可用时接入真实 aggregate view repository；视图名、维度、过滤值、LIMIT 均受 allow-list 和参数化约束。
- 收紧默认结构化查询边界：拒绝多语句、注释、危险关键字/函数、子查询、`UNION`、`JOIN` 和多关系 `FROM`，保留角色、k-anonymity、超时和行数上限。
- 危险 SQL 函数检测覆盖函数名和左括号之间的空白；结果映射优先使用 `patient_count`，避免 `aggregate_count` 覆盖患者数门槛。

### 2.3 数据获取和本地预处理

- `scripts/data/fetch_data.py` 现在可以把 MTSamples CSV、PMC JSON/JSONL 等受控输入规范化为统一 JSONL，并保留来源、行号/记录号、PMID/UID 等审计元数据。
- malformed structured record 会 fail-closed，不替换已有输出；manifest、hash、路径安全、许可证和第三方模型门禁保持有效。
- 增加 Synthea 输出目录、FHIR Bundle、人口数、患者 ID 重复等本地验证；外部 FHIR R4 schema 验证仍明确标记为未验证。

## 3. 验收证据

| 范围 | 结果 |
| --- | --- |
| Agent reactor tests | 67/67 通过 |
| Clinical-data reactor tests | 34/34 通过 |
| Ingestion reactor tests | 144/144 通过；使用仓库内可写临时目录运行 |
| Data script tests | 20/20 通过 |
| Spotless / Checkstyle | agent、clinical-data、ingestion 均通过，0 violations |
| Migration contract | 6/6 通过，包含 V6 迁移契约 |
| Root Maven reactor | 12 个 Java 模块 `BUILD SUCCESS`；包含 retrieval 83 项、架构测试 5 项 |

首次使用当前 Windows 默认临时目录运行 ingestion 时，3 个带 `@TempDir` 的 contextual retrieval 测试在测试结束清理目录时收到 `AccessDeniedException`，没有业务断言失败。将 `TEMP`、`TMP` 和 `java.io.tmpdir` 指向仓库内可写临时目录后，整套 144 个测试通过。因此该现象记录为本机测试环境权限问题，而不是代码回归。

## 4. M0-M3 目前的验收边界

当前仓库具备可审查的 M0-M3 代码骨架、单元测试、迁移契约和安全边界测试，但仍缺少真实依赖条件下的端到端证据。特别是“测试中可以注入一个 fake gateway/repository”不等于生产 provider、数据库权限或跨服务调用已经就绪。

M4 依赖 M3.4、M3.5 和 M3.11 的生产级闭环；在下节剩余项未处理前，不建议把 M4 标记为正式可进入状态。

## 5. 仍未完成或不能由本轮本地直接完成的事项

### 5.1 Agent / Advisor / MCP

1. 当前 ChatMemory 是进程内有界实现，尚未接入 PostgreSQL/Redis 等跨实例持久化，也没有过期、租户隔离、加密存储和水平扩展验证。
2. Spring AI Advisor 链、工具调用循环和 Advisor ordering 尚未作为生产运行时链路接通；当前实现是仓内 provider-neutral generation path。
3. M3.11 要求的 Spring AI MCP server/tool adapter、MCP 会话边界、trajectory 数据集和 holdout-v3 CI gate 尚未完成。
4. 当前 citation verifier 的 span 判断是精确字符串包含，尚未实现规范化文本/断句映射和回答级 citation coverage 指标；外层 provider retry、退避和 coverage gate 仍需补齐。
5. MIXED 查询当前只要任一并行工具失败就整体进入 abstain，尚未实现“保留成功分支继续生成”的产品策略；重试也尚未根据失败类型改变 query/filter/topK。
6. checkpoint 只保存 metadata projection；runtime evidence、当前 draft 和 chat history 不恢复，无法保证从 GENERATE/VERIFY 节点可靠继续。

### 5.2 临床数据生产链路

1. JDBC persistence adapter 已实现，但尚未接入一个完整的 Spring Batch FHIR import job；因此仍缺少批处理 restart、job instance 幂等、失败恢复、重跑和大批量性能证据。
2. V6 迁移已定义表和聚合视图，但没有在真实 PostgreSQL/Testcontainers 中执行 upload -> parse -> map -> quarantine -> persist -> query 的跨服务验证。
3. 只读数据库角色、表权限、视图权限、禁止访问 raw/staging 表的权限测试尚未在部署环境配置和验证。
4. `StructuredQueryService` 已有 clinical-data JDBC 实现，但尚未提供 clinical-data 到 Agent `structuredQueryToolBackend` 的跨模块适配器；Agent 侧因此仍会在该 backend 未注入时 fail-closed。
5. 临床资源表使用 `(source_id, resource_id)` 的 `DO NOTHING` 幂等策略；同一资源的新版本不会更新旧投影，source/checksum 级输入幂等和版本更新策略仍需明确。

### 5.3 真实数据和模型资产

1. 本轮没有下载任何真实临床数据。真实下载仍受来源许可、账户/凭据、robots/rate limit 和人工 PHI 复核约束。
2. Synthea 只提供了显式命令和输出校验入口，尚未在本机固定版本、运行人口规模并生成 manifest。
3. PMC/MTSamples/CDC/USPSTF/AHRQ/DailyMed 等来源尚未完成生产 revision、许可证记录、下载快照和人工采样验收。
4. 真实 embedding、reranker、de-identification、generation 模型包及 provider credentials 未安装或配置；当前不能宣称有真实模型效果指标。
5. M1/M2 的真实 Recall@k、MRR、nDCG、citation coverage、PHI residual 和成本指标仍是未测量状态，现有指标测试不替代真实 holdout 评估。

### 5.4 集成和交付证据

1. 尚无完整 Docker/Testcontainers 三次稳定运行证据，也没有真实服务间 upload -> ingestion -> retrieval -> agent answer 的 E2E trace。
2. 尚未完成生产 observability backend、trace/metric/log retention、敏感字段脱敏验证和告警演练。
3. 远程 CI、真实 secrets、数据库备份/恢复和部署环境的安全审查不在当前本地实现范围内。

### 5.5 现有服务旁路和身份边界

1. retrieval 服务仍保留旧的直接 answer/generation API；若打开旧 LLM 配置，该路径可能绕过 Agent 入口脱敏、统一出站闸门和 Agent citation chain。需要做 API 收敛、明确兼容策略，或在部署层禁用旁路。
2. Agent 当前从请求 DTO 读取 role，尚未接入认证身份到角色的服务端绑定；`conversationId` 也未绑定租户/用户。生产部署前必须完成认证授权、会话归属和审计主体接入。
3. Agent 的单次 LLM provider 调用可能超过全局 execution deadline；当前是节点前后检查，不是可中断的硬超时。需要 provider/client 级 deadline 传播和取消验证。
4. 工具结果中的 publisher、effectiveDate、stale 等来源元数据和完整 retrieval trajectory 尚未进入 Agent 的可验证状态；MCP/trajectory/holdout gate 仍需独立实现。

## 6. 下一步建议

建议先把真实 PostgreSQL/Testcontainers 验证和临床 Spring Batch job 接通，再完成 MCP/Advisor 运行时链路与 trajectory/holdout gate；同时由项目负责人确认数据许可证、下载凭据、人工 PHI 审核和模型 provider 预算。完成这些外部条件后，再重新运行 M0-M3 的跨服务验收并决定是否进入 M4。
