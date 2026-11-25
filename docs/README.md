# docs 目录索引

`docs/` 下的文档用于承载专题说明、设计方案、调研记录、版本记录与流程模板；**当前已实现行为与架构边界以仓库根 [SPEC.md](../SPEC.md) 和 [ARCHITECTURE.md](../ARCHITECTURE.md) 为准**。

专题方案与计划保留生成时的上下文与结论，不等于代码已实现或已通过真机验收；引用前请对照当前源码及上述两份根文档。

## 核心说明与适配

- [连接能力与系统适配](adapters.md) — 直连决策、桥接精确匹配、实验性 prologue 扫描与临时 Root payload 目录
- [多代型号说明](airpods-models.md) — AACP 型号注册表与能力门控规则
- [Payload 导入说明](payload-import.md) — 本地 payload 移植步骤、验证清单与导入设计

## 版本发布与问题台账

- [v1.1 版本说明](release/v1.1.md) — 当前预发布版本（v1.1 / build102）说明与已知限制
- [v1.0 版本说明](release/v1.0.md) — 历史版本说明
- [已知问题与验收矩阵](issues/README.md) — 待验证场景与 `SM-X810` 真机验收矩阵

## 设计方案与实施计划（过程文档）

- [多代 AirPods 设计方案](superpowers/specs/2026-11-17-multi-generation-airpods-design.md)
- [多代 AirPods 实施计划](superpowers/plans/2026-11-17-multi-generation-airpods.md)
- [v1.1 发布实施计划](superpowers/plans/2026-11-17-release-v1.1.md)

## 调研记录

- [开源 AirPods 智能路由调研](research/open-source-airpods-smart-routing.md)
- [AirPods 跨设备交接市场调研](research/market-airpods-handoff.md)

## 架构决策记录（ADR）

- [0001 Galaxy 发起的音频交接](adr/0001-galaxy-initiated-audio-handoff.md)
- [0002 双向交接与让出链路](adr/0002-bidirectional-handoff-and-yielded-link.md)

## Agent 协作配置

- [领域文档约定](agents/domain.md)
- [Issue Tracker 约定](agents/issue-tracker.md)
- [Triage 标签映射](agents/triage-labels.md)

## 审核模板

- [审核模板目录说明](reviews/README.md)
- [PR 审核回复模板](reviews/PR-REVIEW-TEMPLATE.md)
