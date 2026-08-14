# M5 开发前置审查与实现记录

日期：2026-08-11

## 结论

M5 不是完全没有前置工作。生产配置、真实 Keycloak/Redis/Redpanda/OTel 基础设施、阈值
调优和故障演练可以延期；但进入 M5 代码开发前，必须先固定身份上下文边界、流会话契约、
降级语义、客户端输出安全边界和关键架构决策。本轮完成了可在当前仓库内完成的前置项，
没有实现 M5 网关、事件总线、韧性、追踪或流会话功能。

## 本轮实现

- 新增共享 `AuthenticatedRequestContext`，缺失、未知或多角色上下文均 fail-closed。
- Agent HTTP 请求移除客户端角色；HTTP 控制器只从 `ExecutionContext` 获取身份与请求标识。
- Retrieval HTTP 控制器覆盖请求体角色，避免请求体直接改变访问角色。
- 新增 M5.1、M5.2、M5.4、M5.7、M5.12 的 ADR 和并发基线文档。
- 记录流式认证方式、客户端输出审批边界、30 天检查点/反馈评审基线、审计单分区语义、
  OTel/Micrometer 方案和共享契约样例集方案。

## 仍属于 M5 本身的工作

- 网关路由、JWT Resource Server、服务侧令牌校验、Redis 分布式限流和 RFC 9457 错误处理。
- Redpanda topic、schema registry、幂等消费、DLQ 和本地缓冲。
- Resilience4j 的逐组件实现、降级 code 的跨响应/trace/checkpoint/audit/metrics 贯通。
- OTel Collector、Prometheus/Grafana、Java/Python instrumentation 和 span allowlist。
- 多供应商 LLM 网关、预算控制、目的地切换后的出站闸门重检。
- 生成会话、Redis Stream、所有权恢复、取消、终态重放和客户端输出审批实现。
- 旧 Retrieval 直答旁路最终应由 M5.1 BFF/Agent 边界收口；当前 HTTP 入口已要求认证上下文，
  但在 Agent/BFF 完成前不应作为公开生产路由暴露。

## 可延期事项

- 真实凭据、JWK 轮换、TLS/CORS 域名、生产 topic 分区/保留容量和限流数值。
- Docker/Testcontainers、Redpanda、Redis、OTel Collector、Tempo/Jaeger、Prometheus/Grafana。
- 虚拟线程和 Python worker 的最终 P95、内存、pinning 与吞吐实测。
- GraalVM 原生镜像试编译及收益报告。

## M5 进入条件

1. M5 代码必须沿用 `ExecutionContext`，不得重新引入请求体角色信任。
2. `contracts/` 中先加入生成会话、事件 envelope、降级语义和共享 conformance fixtures。
3. M5.1 开发前关闭或明确隔离 Retrieval 直答旁路。
4. M5.3/M5.5/M5.9 共同使用统一降级标识，不允许只在日志中记录降级。
5. M5.12 开发前确定真实 checkpoint/chat memory/trajectory 的权威存储接口；当前内存实现只
   能作为测试替身。
