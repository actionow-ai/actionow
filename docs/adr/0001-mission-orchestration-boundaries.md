# ADR-0001: Mission 三层编排边界 + 重复决策防护

- **Status**: Accepted
- **Date**: 2026-04-27
- **Deciders**: Mission / Agent / Task 团队
- **Trigger Incident**: Mission `019dc989-6b71-750c-9b8f-540ea310d066`
  在单个 LLM step 内 emit 5 次 `delegate_batch_generation`，
  生成 5 个 BatchJob、25 条冗余图像生成（用户为同一目标支付了 5 倍预算）。

## 背景

Mission 模式让 LLM 通过控制工具（`delegate_*` / `complete_mission` / `fail_mission`）
驱动一个长时间运行的状态机。LLM 是非确定性的——同一次响应可能 emit 多个相互重复甚至
互相矛盾的控制工具调用。在没有任何系统层面拦截时，每一个调用都会真实推进状态、创建副作用，
事故就是这种不变量缺失的直接后果。

我们需要明确的是：

1. 谁在哪一层负责保证「单步至多一个控制决策」？
2. 即便防线被绕过，下游（BatchJob、Task）有没有兜底？
3. 当 provider 实际失败时，由谁负责切换 provider，以什么粒度？

## 决策

采纳「**Prompt 引导 → 工具门控 → 数据库幂等 → 任务级 fallback**」的四层防护，
其中前三层防护重复决策，第四层处理实际故障。每一层都假定上游可能失守。

### Layer 1 — Prompt 引导（位于 `mission_expert.md`）
- 在 skill prompt 显式声明"单步只能调一个控制工具"和反模式样例；
- 目标：把 LLM 的错误率压到最低，是最便宜的拦截层。

### Layer 2 — 工具门控（`ControlToolGuardCallback` + `MissionStepControlState`）
- 由 `SaaAgentFactory.wrapIfControlTool` 在工具注册时按工具名包装；
- 同 `missionStepId` 内首个控制工具 `compareAndSet` 抢占放行，其余调用返回
  rejection JSON 并打 metric `actionow.agent.mission.control_tool.rejected.total`；
- 仅在 Mission 模式启用（`missionStepId` 为空时透明放行 Chat 模式）；
- step 结束时 `MissionExecutor` 调 `release()` 清理状态条目，避免 Map 长期堆积。

### Layer 3 — 数据库幂等（`t_batch_job (mission_id, idempotency_key)` 唯一索引）
- `idempotency_key = SHA-256(missionId + stepId + type + 稳定输入摘要)`；
- 即便上面两层都失守，相同 key 在 mission 内只能创建一条 BatchJob；
- 索引覆盖软删行（`WHERE mission_id IS NOT NULL AND idempotency_key IS NOT NULL`），
  防止"删除-再创建"绕过。

### Layer 4 — Task 级 Provider Auto-Fallback（`AiGenerationOrchestrator.tryProviderFallback`）
处理的是另一个问题：单 provider 故障导致整个 BatchJob 卡住。
- **触发条件**：runtime flag `provider_fallback_enabled` = true、Task 属于 BatchJobItem、
  errorCode 非用户/参数侧（白名单 PARAM\* / VALIDATION\* / INSUFFICIENT_CREDIT\* /
  UNAUTHORIZED / FORBIDDEN / NOT_FOUND 不 fallback）；
- **粒度**：改写**老 Task**的 providerId 而不是新建 Task。理由：
  - 保留原始的 wallet 冻结金额和 BatchJobItem.task_id 关联；
  - 单次 MQ 重投，不引入新的事务边界；
  - 失败历史持久化在 `t_batch_job_item.failed_provider_ids`（JSONB）和 `retry_count`，
    跨实例/重启都不丢；
- **预算控制**：`provider_fallback_max_attempts`（默认 2），耗尽则正常进入 onFailure；
- **可观测**：`actionow.task.provider_fallback.total{outcome=attempt|success|exhausted}`。

## 关键不变量

| 不变量 | 由哪一层保证 |
|---|---|
| 单 mission step 内 ≤ 1 个控制工具决策 | Layer 1（软）+ Layer 2（硬） |
| 同 (missionId, idempotency_key) 仅 1 条 BatchJob | Layer 3 |
| 故障 provider 不会原地死循环 | Layer 4 + max_attempts |
| Chat 模式不受 Mission 控制工具门控影响 | Layer 2（missionStepId 空时透明放行） |
| Mission 决策优先级：FAIL > DELEGATE > COMPLETE | `MissionDecisionValidator`（已有） |
| BatchJob 终态 ⇒ mission_task 终态（不变量 5） | 主路径：`BatchJobMissionNotifier` → MQ；兜底：`MissionReconciler` 定时扫描孤儿 |

### 不变量 5 的事故背景
事故 mission `019dcfa8-…71a`：BatchJob `019dcfa8-…dbf` 4/4 COMPLETED，但 `t_agent_mission_task.status` 永久 PENDING、Mission 永久 WAITING。
**根因**：`BatchJobMissionNotifier` 误用 routing key `batch.job.completed`，而 `MissionTaskListener` 队列只绑定 `mission.task.callback`。
direct exchange 直接丢弃消息，单点通知链失败 → 永久卡死。
**修复**：发到正确 routing key + 增加 `MissionReconciler` 兜底（不再相信单一通知通道）+ 集成测试校验「published key 必有 binding」。

## 拒绝的备选方案

- **「只在 Prompt 里讲清楚」**：依赖 LLM 守规矩。已被事故证伪。
- **「让 BatchJob 创建直接抛重复异常」**：会让 Mission step 失败，但 LLM 拿到错误后
  可能继续重试同样的工具，需要在 callback 层吃掉错误。门控在 callback 层做更干净。
- **「fallback 时新建一个 Task」**：违反 BatchJobItem.task_id 的稳定性，
  钱包冻结需要重新走一遍，事务边界更复杂；测试一致性变差。
- **「fallback 时遇到所有 5xx 都重试，不区分错误码」**：会把用户参数错误也无谓打到
  其他 provider，浪费配额且用户感知到的失败延迟变长。

## 后果

### 正面
- 单 LLM step 重复决策事件被压制为 metric 计数 + warn 日志；
- BatchJob 表上不会再出现重复行，未来 audit/billing 数据干净；
- Provider 局部故障不影响整个 BatchJob 的吞吐；
- 每一层都可独立验证（`ControlToolGuardCallbackTest` /
  `AiGenerationOrchestratorClassifierTest`）。

### 代价
- 多了一个 ToolCallback 装饰层和一个 ConcurrentHashMap，少量内存/调度开销；
- `MissionStepControlState.release()` 由 `MissionExecutor` 在 step 结束时主动调用；
  Caffeine 30 分钟 `expireAfterWrite` 作为 executor 异常退出场景下的内存兜底，
  避免裸 Map 在退化场景无限增长（`MissionStepControlState.FALLBACK_TTL`）；
- `idempotency_key` 计算口径需要稳定，调用方修改输入字段顺序/序列化会破坏命中率；
- Provider fallback 默认 OFF，需要运维显式开启并监控
  `provider_fallback.total{outcome=success/exhausted}` 比例。

## 相关变更

- `backend/actionow-agent/src/main/java/com/actionow/agent/mission/ControlToolGuardCallback.java`
- `backend/actionow-agent/src/main/java/com/actionow/agent/mission/MissionStepControlState.java`
- `backend/actionow-agent/src/main/java/com/actionow/agent/saa/factory/SaaAgentFactory.java`
- `backend/actionow-task/src/main/java/com/actionow/task/service/impl/AiGenerationOrchestrator.java`
- `backend/actionow-task/src/main/java/com/actionow/task/service/ProviderRouter.java`
- `docker/init-db/06-batch-job-idempotency.sql`
- `docker/init-db/07-batch-job-item-retry.sql`
- `docker/repair-scripts/01-batch-job-cleanup.sql`
- `backend/scripts/skill-package/mission_expert.md`
