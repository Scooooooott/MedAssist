# M4 Development Analysis and Task Plan

## 1. 需求边界

M4 的目标是把已有的 Agent、检索和摄取能力变成可审计、可授权、可追责的治理闭环。需求文档将 M4 拆为 13 项：

1. `M4.1` Keycloak 身份与服务鉴权。
2. `M4.2` 治理策略清单。
3. `M4.3` 策略编译器。
4. `M4.4` 治理漂移检查。
5. `M4.5` PDP/PEP 与 fail-closed 执行。
6. `M4.6` 审计服务与哈希链。
7. `M4.7` 摄取质量断言与 quarantine 联动。
8. `M4.8` 治理指标聚合。
9. `M4.9` BFF 与治理、质量、成本看板。
10. `M4.10` 反馈、受控轨迹查询与人工提升。
11. `M4.11` HIPAA-aligned 映射、权限矩阵与措辞检查。
12. `M4.12` 异步执行上下文传播与隔离。
13. `M4.13` 仅内部使用的 Django 运维控制台。

关键路径为 `M4.2 -> M4.3 -> M4.5 -> M4.6 -> M4.8 -> M4.9`。`M4.7` 可与前半段并行，`M4.10` 需要 M3 checkpoint 契约和 M4.1，`M4.13` 必须等待 Java API 和策略边界明确。

## 2. 本轮实现策略

当前仓库没有真实 Keycloak、PostgreSQL 生产实例或授权的 LLM/PHI 数据，因此本轮验收以“契约、纯函数、文件生成、fail-closed、无 PHI、可替换适配器和集成边界”为主；真实基础设施验收单独记录为外部阻塞，不用内存替身冒充生产能力。

本轮优先交付：

- 可校验的 `governance/policy-manifest.yaml` 与 JSON Schema；
- 确定性策略编译器及 golden/dry-run/rollback 产物；
- 可验证的 PDP 决策模型和 fail-closed 默认实现；
- 仅追加审计事件模型、规范化序列化、哈希链和并发安全内存适配器；
- 统一执行上下文载荷、任务装饰器和清理边界；
- 质量断言和指标聚合的安全领域模型；
- M4.11 合规文档、权限矩阵生成与词语扫描；
- 为后续 Spring/Django 接入保留明确 API，而不直接越过业务 API 写库。

暂不把以下事项伪装成已完成：真实 Keycloak 登录、PostgreSQL RLS/GRANT 执行、真实审计数据库只追加权限、生产看板数据源、Django 业务表 inspectdb 以及真实 deid provider E2E。

## 3. 任务拆分与子 agent 边界

### 批次 A：治理清单与编译器

- 文件范围：`governance/`、`scripts/governance/`、相关 Python 测试。
- 交付：manifest、schema、确定性生成、dry-run、rollback、漂移检查、golden tests。
- 禁止：修改 Java 服务中的授权判断；该整合由主 agent 完成。

### 批次 B：审计、PDP 与治理领域模型

- 文件范围：`services/identity-policy/`、`services/audit-governance/` 的新增领域包与测试。
- 交付：决策请求/响应、默认拒绝、审计规范化序列化、哈希链、并发测试、事件发布抽象。
- 禁止：宣称已连通 Keycloak/Postgres；禁止在审计 payload 放原文。

### 批次 C：上下文隔离、质量断言与指标模型

- 文件范围：`shared/common-lib/`、`services/ingestion/` 新增 M4 质量断言包及测试。
- 交付：上下文捕获/恢复/清除、TaskDecorator/执行器工厂、断言结果模型、指标口径模型。
- 禁止：改动既有检索算法和 M3 状态机。

### 主 agent 负责的整合与审查

- 合并策略清单与 Java 模型的边界。
- 审查安全默认值、PHI 约束、线程清理、并发哈希链和文件生成确定性。
- 补充 M4.11 文档/矩阵与最小可运行接口。
- 对所有真实基础设施缺口做验收标注。

## 4. 验收清单

- Manifest schema 校验通过，漏声明、重复声明、非法标签和孤儿引用可被拦截。
- 同一输入连续编译输出字节一致；生成物带有禁止手改标记；dry-run 不写入；rollback 可恢复上一版。
- PDP 在缺少主体、未知角色、未知动作或策略不可用时拒绝；允许决策携带 obligations。
- 审计链在并发写入后可验证，篡改能定位，规范化 payload 不含原文；事件发布可替换。
- 异步任务开始前能检测残留上下文，结束后 `finally` 清除；丢失身份时拒绝。
- 阻塞质量断言失败时可阻止批次，告警断言只产生告警；指标分子/分母定义明确。
- 权限矩阵从 manifest 派生，扫描能阻止 `HIPAA compliant` 等越界措辞。
- Maven/Python 静态检查与单元测试通过；真实 Keycloak/Postgres/Django/LLM E2E 明确列为未验收项。
