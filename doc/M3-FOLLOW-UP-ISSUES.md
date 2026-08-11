# M3 Follow-up Issues

本文档是 M3 收尾后的可执行问题清单。它与
`doc/M3-CLOSEOUT-IMPLEMENTATION-AND-REMAINDER.md` 的区别是：收尾报告记录阶段结果，本文件记录后续修复入口、优先级和验收条件。

## 1. 已在 M3 收尾中修复

| 编号 | 问题 | 处理结果 |
|---|---|---|
| M3-FIX-01 | SQL 禁止函数检测允许函数名与左括号之间出现空白 | 已改为按 `function\\s*\\(` 检查，并补充边界测试 |
| M3-FIX-02 | 聚合查询可能用 `aggregate_count` 覆盖更可信的 `patient_count` | 已优先读取 `patient_count`，并补充安全测试 |
| M3-FIX-03 | 召回证据进入 LLM 前未统一执行注入检测 | 已在草稿生成入口接入 `PromptInjectionDetector`，命中时阻断并记录失败路径 |
| M3-FIX-04 | Agent 会话标识与运行时对话历史缺少最小边界 | 已增加可选 `conversationId`、有界内存以及不进入持久化投影的运行时证据 |

## 2. 仍需处理的代码与架构问题

### 高优先级

| 编号 | 问题与位置 | 建议处理方向 | 状态 |
|---|---|---|---|
| M3-OPEN-01 | `services/retrieval/.../RetrievalController.java` 的传统回答接口仍可绕过 Agent 的入口脱敏、工具授权和出站闸门；回答缓存也可能保存原始查询 | 将传统回答路径收敛到受控 Agent/BFF，或明确关闭旧接口；缓存只允许保存脱敏查询哈希和合规回答投影 | 未处理，M4/M5 前必须关闭旁路 |
| M3-OPEN-02 | `AgentRequest.role` 由客户端直接提交，`conversationId` 尚未与认证主体绑定 | M4.1 接入 JWT 后由服务端从 token 解析角色和主体，忽略客户端角色；会话存储键必须包含 `subject`，跨主体访问 fail-closed | 待 M4.1 |
| M3-OPEN-03 | 默认配置仍不能完成真实 LLM + retrieval 的 Agent 端到端链路，且未使用真正的 Spring AI Advisor chain | 接入可配置真实 provider/retrieval client，保留无外部依赖的测试替身；增加 profile 级 E2E 和启动诊断 | 外部依赖+后续实现 |
| M3-OPEN-04 | Agent 的 `structuredQueryToolBackend` 尚未接入 clinical-data 的真实适配器 | 通过内部 client credentials 调用受控 clinical-data API；PDP 决策和 obligations 必须在执行点生效 | 待 M4.5 |
| M3-OPEN-05 | `MIXED` 路由当前对任一分支异常都可能整体拒答，不能区分部分成功 | 定义向量/词法/结构化分支的失败语义；允许安全的部分结果继续，并在状态、审计和响应中记录降级码 | 待 M4/M5 联调 |
| M3-OPEN-06 | 检查点恢复只恢复持久化投影，不能从 `GENERATE` / `VERIFY` 等中间状态重建运行时证据、对话历史和当前草稿 | 只从授权主存储按 ID 重载脱敏文本；重新执行撤回、领域和角色过滤；禁止把完整候选文本写入 checkpoint | 待 M4.10，保留期需与反馈评审窗口一致 |
| M3-OPEN-07 | 全局超时目前主要依赖节点检查，provider 调用本身可能超过 deadline | 为外部调用传递剩余 deadline，超时后取消 future/连接并返回明确 `terminationReason`；补 provider 超时测试 | 待 M5 韧性阶段前收口 |

### 中优先级

| 编号 | 问题与位置 | 建议处理方向 | 状态 |
|---|---|---|---|
| M3-OPEN-08 | 引用校验仍主要依赖简单字符串包含，无法处理规范化、跨段证据和覆盖率细节 | 增加 Unicode/空白规范化、字符区间覆盖计算、陈旧文档状态检查；重试时改变检索策略而非重复原请求 | M2/M4 质量闭环 |
| M3-OPEN-09 | FHIR JDBC 持久化尚未与 Spring Batch clinical import job 完整连接 | 将 FHIR 映射、Safe Harbor、持久化和 quarantine 接入同一个可恢复 job，并记录断言结果 | M4.7 |
| M3-OPEN-10 | 临床数据写入只按 source/resource 去重，source checksum、版本更新和撤回语义不完整 | 引入 source version/checksum 与幂等更新规则，明确 ACTIVE/SUPERSEDED/WITHDRAWN 状态迁移 | M2.6/M4.7 |
| M3-OPEN-11 | trajectory/checkpoint 默认是内存实现，审计和反馈无法依赖可重建的持久化轨迹 | 保持安全投影，补持久化仓储和受控 trace 查询 API；轨迹事件不得携带完整文本 | M4.6/M4.10 |
| M3-OPEN-12 | 工具过滤条件、来源元数据和权限义务尚未由单一策略清单驱动 | M4.2/M4.3 生成检索过滤、工具映射、出站规则和应用动作授权，删除 M3 配置中的重复定义 | 待 M4.2/M4.3 |
| M3-OPEN-13 | Prompt injection 检测结果、分类置信度、轨迹评估和 MCP 连接尚未形成统一指标 | 先落审计事件契约和指标口径，再由 M4.6/M4.8 聚合；MCP 保持可选且默认关闭 | 待 M4.6/M4.8 |

## 3. 不能仅靠当前本地代码完成的事项

| 编号 | 事项 | 需要的外部条件 | 当前动作 |
|---|---|---|---|
| M3-EXT-01 | 真实 PostgreSQL + pgvector + RLS 并发验证 | Docker/Podman、数据库镜像和可写权限 | 保留 Testcontainers/集成测试入口，暂不宣称生产验证通过 |
| M3-EXT-02 | 真实 LLM、embedding、reranker、deid provider E2E | provider 地址、模型文件、凭据及可接受成本 | 仅使用契约替身完成单测；凭据不写入仓库 |
| M3-EXT-03 | 真实数据规模和脱敏泄漏率基线 | 获得授权的数据集、许可证和人工评审窗口 | 当前只处理合成数据与数据获取脚本，不导入真实 PHI |

## 4. 处理顺序

1. M4.1/M4.2/M4.3 先建立身份和策略单一真相源，关闭角色伪造与策略重复定义。
2. M4.5/M4.12 收口 PDP、PEP、数据库事务级身份和异步上下文传播。
3. M4.6/M4.8/M4.10 建立审计、指标和受控轨迹/反馈闭环。
4. M4.7/M4.13 接入质量断言、quarantine 和内部评审控制台。
5. M5 阶段再处理 Redpanda、熔断、真实 OTel、流式会话恢复和硬超时的生产级联调。

本清单不表示当前 M3 已达到生产部署条件；它表示已知问题已经被分类、定位并绑定到后续交付阶段。

## 5. M4 本轮处理映射

| M3 问题 | M4 本轮处理 | 当前结论 |
|---|---|---|
| M3-OPEN-02 角色与主体绑定 | M4.1 提供 Keycloak realm 与 M4.5 PDP 模型 | Java Resource Server 和会话归属仍待联调 |
| M3-OPEN-04 structured query adapter | M4.2/M4.3 生成工具映射与应用权限产物 | clinical-data 真实 client adapter 未完成 |
| M3-OPEN-06 checkpoint 恢复 | M4.10 提供受控轨迹投影接口 | 持久化 checkpoint 与主存储重载仍未完成 |
| M3-OPEN-09 FHIR/Batch 接入 | M4.7 提供质量断言模型 | Spring Batch 业务接线仍未完成 |
| M3-OPEN-11 trajectory 持久化 | M4.6/M4.10 提供安全投影和审计抽象 | 真实仓储和保留策略仍未完成 |
| M3-OPEN-12 工具/过滤硬编码 | M4.2/M4.3 提供单一 manifest 和生成物 | Agent 运行时消费生成物仍待接入 |

旧 retrieval 直答旁路、真实认证链路、硬超时、MIXED 部分失败和真实模型 E2E 未由本轮伪造完成，继续保留在本清单的未处理状态。
