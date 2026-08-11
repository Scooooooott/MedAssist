# M4 Implementation and Acceptance Record

## 1. 执行流程

本阶段按以下顺序推进：

1. 对照 `REQUIREMENTS-FULL.md` 分析 M4.1-M4.13 和关键路径。
2. 将实现拆成不重叠写集，分别交给治理编译器、PDP/审计、上下文/质量、Keycloak/ops-console 子 agent。
3. 主 agent 负责 M4.9 前端看板、M4.10 反馈闭环、M4.11 合规文档、CI 与跨模块整合。
4. 对子 agent 代码进行编译、测试、静态检查和安全边界复审；发现上下文测试编译/行为问题后修正测试边界，发现反馈优先级和审计角色问题后修正业务实现。

## 2. 已实现内容

### M4.1 身份基础

- `deploy/keycloak/realm-medassist.json`：三角色、demo 用户占位、公开前端客户端和服务机密客户端。
- `deploy/keycloak/init-demo-credentials.sh`：密码和 client secret 只从环境变量注入。
- `deploy/compose/compose.governance.yml`：Keycloak 自动导入、健康检查和初始化容器。
- `justfile` 新增 `up-governance`。

当前完成的是可复现配置与启动边界；真实登录、JWT 签名轮换和所有 Java 服务 Resource Server 接入需在有 Keycloak 容器的环境验收。

### M4.2-M4.4 治理清单、编译与漂移

- `governance/policy-manifest.yaml` 分离 `column_classifications` 与 `content_domains`，声明默认拒绝、三角色、工具、看板、ops-console 和出站策略。
- `governance/policy-manifest.schema.json` 做结构约束。
- `scripts/governance/policy_compiler.py` 支持 `validate`、`compile`、`dry-run`、`rollback`、`drift`，输出 SQL、检索过滤、出站策略、工具映射、ops 权限和应用权限。
- 编译输出确定性、生成标记、fixture 双向漂移检查、golden/determinism、rollback 和失败路径测试已覆盖。
- `.github/workflows/ci.yml` 增加 governance job；`scripts/check_governance_wording.py` 执行措辞纪律检查。

### M4.5-M4.6 PDP 与审计链

- `services/identity-policy/`：不可变 PDP 请求/响应、编译产物模型、obligations、默认拒绝和策略不可用时 fail-closed。
- `services/audit-governance/`：安全白名单 payload、确定性 canonical serializer、SHA-256 哈希链、100 并发追加、完整性定位、外部 anchor 接口和可替换 publisher。
- 审计 payload 不接收原文；M4 使用内存 publisher，后续可由 M5 替换事件传输。

当前尚未把 GRANT/RLS、审计表只追加权限和 PDP 决策缓存接入真实 PostgreSQL。

### M4.7-M4.8 质量与指标

- `services/ingestion/.../quality/`：阻塞/告警断言、配置化阈值、批次拒绝、质量报告和趋势模型。
- `services/audit-governance/.../dashboard/`：BFF 指标契约、空数据实现和服务端角色门控；未认证返回 401，越权返回 403。
- `docs/compliance/governance-metrics.md` 定义漏斗、拒绝、质量、脱敏、成本和重试指标口径。

当前指标服务为安全空数据适配器，尚未读取真实聚合表或审计投影，质量断言也尚未接入完整 Spring Batch 业务链。

### M4.9-M4.10 看板、反馈与受控轨迹

- `frontend/src/features/governance/`：治理、质量、成本三类看板，角色可见性、空态和异常态。
- `services/audit-governance/.../feedback/`：整体反馈、引用级反馈、问题分类、严重度、结构化反馈队列、ADMIN 显式评审和候选创建。
- 用户反馈不含自由文本；人工候选答案需经过 `DeidentifiedTextGuard`。
- `ControlledTrajectoryQueryService` 只返回候选 ID、节点、模型/策略版本和降级码等安全投影，不暴露 checkpoint 或原文。
- `docs/adr/ADR-017-feedback-no-free-text.md` 明确“不自动回流”红线。

当前反馈和轨迹实现是内存可替换边界，尚未接入持久化 checkpoint、真实 trace 重建和 Java API 数据库仓储。

### M4.11-M4.13 合规与内部控制台

- `docs/compliance/hipaa-mapping.md`：逐项标注机制、代码位置、测试和未覆盖组织事项。
- `docs/compliance/permission-matrix.md`：权限矩阵审阅快照，源头仍为 manifest。
- `ops-console/`：Django 内部只读队列模型、Java API 状态变更适配器、英文界面、迁移边界检查；业务模型均 `managed = False`，默认拒绝直接状态变更。
- `docs/adr/ADR-016-governance-policy-compiler.md` 与 `ADR-017` 记录关键决策。

当前 Django 依赖未安装，未执行 `manage.py check`；真实专属只读数据库角色、RLS 和 Java API 状态变更仍需环境验收。

### M4.12 上下文传播

- `shared/common-lib/.../context/`：不可变执行上下文、捕获/恢复/清除、统一执行器、任务装饰器、入口残留检测和缺失身份 fail-closed。
- 覆盖线程复用、异常 finally 清除、残留检测和缺少身份测试。

## 3. 验收结果

| 检查 | 结果 |
|---|---:|
| Governance Python tests | 7 passed |
| ops-console boundary tests | 5 passed |
| common-lib + context | 9 passed |
| ingestion full reactor | 153 passed |
| identity-policy | 4 passed |
| audit-governance | 9 passed |
| frontend | 30 passed |
| frontend build/lint/format | passed |
| Java Spotless/Checkstyle (M4 touched modules) | 0 violations |
| governance manifest/fixture drift | passed |
| governance wording scan | passed |

## 4. 仍未完成的生产级闭环

1. Keycloak 真实登录、JWT/JWK 轮换以及全部 Java 服务的 Resource Server/PEP 接入。
2. PostgreSQL 真实 RLS、事务级会话变量、列级 GRANT、审计表 INSERT/SELECT-only 权限和并发串用测试。
3. 审计链外部锚定的定时任务、真实持久化和异步缓冲丢失检测。
4. M4.7 断言接入 Spring Batch、quarantine 结果落库和真实脱敏残留抽检。
5. M4.8 聚合表/物化视图、真实指标投影和成本数据源。
6. M4.10 checkpoint 持久化、反馈评审窗口对齐、受控轨迹从主存储重新授权装载。
7. M4.11 权限矩阵从 manifest 自动生成并写回，而不是保留静态审阅快照。
8. M4.13 Django `manage.py check`、真实 inspectdb、专属只读连接和 Java API 联调。
9. M3 遗留的旧 retrieval 直答旁路、Agent 客户端角色绑定、结构化查询真实 adapter、MIXED 部分失败、硬超时和真实 LLM/deid E2E。

因此本记录的结论是：M4 的可替换领域实现、契约、测试和展示面已完成并通过本地验收；M4 尚未达到真实 Keycloak/PostgreSQL/Django/模型基础设施上的生产验收条件。上述缺口已与 `doc/M3-FOLLOW-UP-ISSUES.md` 互相引用，后续不得被误记为已完成。
