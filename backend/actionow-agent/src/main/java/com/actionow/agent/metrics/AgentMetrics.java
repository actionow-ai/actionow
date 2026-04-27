package com.actionow.agent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Agent 模块核心指标
 * <p>
 * 集中管理 Micrometer meter 实例，供 SaaAgentRunner、ToolExecutionAspect 等组件记录指标。
 * 所有指标以 {@code actionow.agent.} 前缀命名，与 Actuator /metrics 端点自动集成。
 *
 * @author Actionow
 */
@Slf4j
@Component
public class AgentMetrics {

    // ==================== Execution ====================

    private final Timer executionTimer;
    private final Counter executionSuccessCounter;
    private final Counter executionErrorCounter;
    private final Counter executionCancelCounter;

    // ==================== Tool ====================

    private final Timer toolTimer;
    private final Counter toolSuccessCounter;
    private final Counter toolFailureCounter;

    // ==================== Context ====================

    private final Counter contextCompactCounter;
    private final Counter workingMemoryPutCounter;
    private final Counter workingMemoryHitCounter;
    private final Counter workingMemoryMissCounter;

    // ==================== Events ====================

    private final MeterRegistry registry;
    private final Counter askUserCreatedCounter;
    private final Counter askUserAnsweredCounter;
    private final Counter askUserTimeoutCounter;
    private final Counter askUserCancelledCounter;
    private final Timer askUserRoundTripTimer;
    private final Counter structuredDataCounter;

    // ==================== Mission ====================

    private final Counter missionControlToolRejectedCounter;
    private final Counter missionReconcilerRecoveredCounter;

    // ==================== Message segment / collapse / placeholder ====================

    private final Counter segmentWrittenCounter;
    private final Counter segmentWriteDedupCounter;
    private final DistributionSummary collapseGroupSizeRead;
    private final DistributionSummary collapseGroupSizeLlm;
    private final Counter placeholderFinalizedEmptyCounter;
    private final Counter placeholderFinalizedNonEmptyCounter;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Execution
        this.executionTimer = Timer.builder("actionow.agent.execution.duration")
                .description("Agent execution duration")
                .publishPercentiles(0.5, 0.9, 0.99)
                .register(registry);
        this.executionSuccessCounter = Counter.builder("actionow.agent.execution.total")
                .tag("outcome", "success")
                .description("Successful agent executions")
                .register(registry);
        this.executionErrorCounter = Counter.builder("actionow.agent.execution.total")
                .tag("outcome", "error")
                .description("Failed agent executions")
                .register(registry);
        this.executionCancelCounter = Counter.builder("actionow.agent.execution.total")
                .tag("outcome", "cancelled")
                .description("Cancelled agent executions")
                .register(registry);

        // Tool
        this.toolTimer = Timer.builder("actionow.agent.tool.duration")
                .description("Tool execution duration")
                .publishPercentiles(0.5, 0.9, 0.99)
                .register(registry);
        this.toolSuccessCounter = Counter.builder("actionow.agent.tool.total")
                .tag("outcome", "success")
                .description("Successful tool executions")
                .register(registry);
        this.toolFailureCounter = Counter.builder("actionow.agent.tool.total")
                .tag("outcome", "failure")
                .description("Failed tool executions")
                .register(registry);

        // Context
        this.contextCompactCounter = Counter.builder("actionow.agent.context.compact.total")
                .description("Context compaction triggers")
                .register(registry);
        this.workingMemoryPutCounter = Counter.builder("actionow.agent.working_memory.put.total")
                .description("Working memory put operations")
                .register(registry);
        this.workingMemoryHitCounter = Counter.builder("actionow.agent.working_memory.get.total")
                .tag("outcome", "hit")
                .description("Working memory cache hits")
                .register(registry);
        this.workingMemoryMissCounter = Counter.builder("actionow.agent.working_memory.get.total")
                .tag("outcome", "miss")
                .description("Working memory cache misses")
                .register(registry);

        // HITL / 事件
        this.askUserCreatedCounter = Counter.builder("actionow.agent.ask_user.total")
                .tag("outcome", "created")
                .description("HITL ask_user prompts created")
                .register(registry);
        this.askUserAnsweredCounter = Counter.builder("actionow.agent.ask_user.total")
                .tag("outcome", "answered")
                .description("HITL ask_user answers received")
                .register(registry);
        this.askUserTimeoutCounter = Counter.builder("actionow.agent.ask_user.total")
                .tag("outcome", "timeout")
                .description("HITL ask_user timeouts")
                .register(registry);
        this.askUserCancelledCounter = Counter.builder("actionow.agent.ask_user.total")
                .tag("outcome", "cancelled")
                .description("HITL ask_user cancellations")
                .register(registry);
        this.askUserRoundTripTimer = Timer.builder("actionow.agent.ask_user.duration")
                .description("HITL ask_user round-trip duration (prompt → answer)")
                .publishPercentiles(0.5, 0.9, 0.99)
                .register(registry);
        this.structuredDataCounter = Counter.builder("actionow.agent.structured_data.total")
                .description("Structured data events emitted to SSE")
                .register(registry);

        // Mission - 控制工具门控（Phase 1 加的 ControlToolGuardCallback 拒绝事件）
        // tag.tool 标识被拒绝的控制工具名；异常基数受 CONTROL_TOOL_NAMES 限制（仅 5 个），不会爆炸
        this.missionControlToolRejectedCounter = Counter.builder("actionow.agent.mission.control_tool.rejected.total")
                .description("Mission step 内重复调用控制工具被 ControlToolGuardCallback 拒绝的次数")
                .register(registry);

        // 兜底：BatchJob 终态通知漏发 → MissionReconciler 补发命中。常态应为 0，>0 表示主通道有失败。
        this.missionReconcilerRecoveredCounter = Counter.builder("actionow.agent.mission.reconciler.recovered.total")
                .description("MissionReconciler 检测到孤儿 BatchJob 并补发通知的次数（常态应为 0）")
                .register(registry);

        // Message segment / collapse / placeholder
        this.segmentWrittenCounter = Counter.builder("actionow.agent.message.segment.written.total")
                .description("assistant_segment rows inserted via per-segment-write path")
                .register(registry);
        this.segmentWriteDedupCounter = Counter.builder("actionow.agent.message.segment.written.total")
                .tag("outcome", "dedup")
                .description("assistant_segment writes skipped due to (session_id, event_id) dedup")
                .register(registry);
        this.collapseGroupSizeRead = DistributionSummary.builder("actionow.agent.message.collapse.group_size")
                .tag("side", "read")
                .description("Collapsed group sizes on read side (GET /messages)")
                .publishPercentiles(0.5, 0.9, 0.99)
                .register(registry);
        this.collapseGroupSizeLlm = DistributionSummary.builder("actionow.agent.message.collapse.group_size")
                .tag("side", "llm")
                .description("Collapsed group sizes in LLM history reload (ContextWindowManager)")
                .publishPercentiles(0.5, 0.9, 0.99)
                .register(registry);
        this.placeholderFinalizedEmptyCounter = Counter.builder("actionow.agent.message.placeholder.finalized.total")
                .tag("content", "empty")
                .description("Placeholder rows finalized with empty content (per-segment-write path)")
                .register(registry);
        this.placeholderFinalizedNonEmptyCounter = Counter.builder("actionow.agent.message.placeholder.finalized.total")
                .tag("content", "nonempty")
                .description("Placeholder rows finalized with non-empty content (legacy path)")
                .register(registry);

        log.info("AgentMetrics initialized (actionow.agent.* meters registered)");
    }

    // ==================== Execution 记录 ====================

    public void recordExecutionSuccess(long durationMs) {
        executionTimer.record(durationMs, TimeUnit.MILLISECONDS);
        executionSuccessCounter.increment();
    }

    public void recordExecutionError(long durationMs) {
        executionTimer.record(durationMs, TimeUnit.MILLISECONDS);
        executionErrorCounter.increment();
    }

    public void recordExecutionCancelled() {
        executionCancelCounter.increment();
    }

    // ==================== Tool 记录 ====================

    public void recordToolExecution(String toolId, boolean success, long durationMs) {
        toolTimer.record(durationMs, TimeUnit.MILLISECONDS);
        if (success) {
            toolSuccessCounter.increment();
        } else {
            toolFailureCounter.increment();
        }
    }

    // ==================== Context 记录 ====================

    public void recordContextCompaction() {
        contextCompactCounter.increment();
    }

    public void recordWorkingMemoryPut() {
        workingMemoryPutCounter.increment();
    }

    public void recordWorkingMemoryGet(boolean hit) {
        if (hit) {
            workingMemoryHitCounter.increment();
        } else {
            workingMemoryMissCounter.increment();
        }
    }

    // ==================== 事件记录 ====================

    public void recordAskUserCreated() {
        askUserCreatedCounter.increment();
    }

    public void recordAskUserAnswered(long durationMs) {
        askUserAnsweredCounter.increment();
        askUserRoundTripTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordAskUserTimeout(long durationMs) {
        askUserTimeoutCounter.increment();
        askUserRoundTripTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordAskUserCancelled() {
        askUserCancelledCounter.increment();
    }

    public void recordStructuredData() {
        structuredDataCounter.increment();
    }

    // ==================== Message segment / collapse / placeholder ====================

    /** 每次成功插入 assistant_segment 行调用一次（per-segment-write 路径）。 */
    public void recordSegmentWritten() {
        segmentWrittenCounter.increment();
    }

    /** (session_id, event_id) 命中已有行，跳过 insert 时调用 —— 衡量跨 pod 重放率。 */
    public void recordSegmentWriteDedup() {
        segmentWriteDedupCounter.increment();
    }

    /**
     * 读侧 / LLM 侧合并触发时记录一个合并组的大小。
     *
     * @param groupSize 被合成一条的段落数；size=1 也要记，以便计算比率
     * @param side      "read" = GET /messages；"llm" = ContextWindowManager 回灌
     */
    public void recordCollapseGroupSize(int groupSize, String side) {
        if (groupSize <= 0) return;
        if ("llm".equals(side)) {
            collapseGroupSizeLlm.record(groupSize);
        } else {
            collapseGroupSizeRead.record(groupSize);
        }
    }

    /**
     * 记录一次 placeholder 收尾：empty 代表 per-segment-write 新路径（文本已分段入库），
     * nonempty 代表旧路径。比率可用来判断灰度推进到哪一步。
     */
    public void recordPlaceholderFinalized(boolean contentEmpty) {
        if (contentEmpty) {
            placeholderFinalizedEmptyCounter.increment();
        } else {
            placeholderFinalizedNonEmptyCounter.increment();
        }
    }

    // ==================== Mission ====================

    /**
     * 记录一次 mission step 内的控制工具被门控拒绝。
     * 触发自 {@link com.actionow.agent.mission.ControlToolGuardCallback}：单步只能有一个控制工具决策。
     */
    public void recordMissionControlToolRejected() {
        missionControlToolRejectedCounter.increment();
    }

    /**
     * MissionReconciler 检测到「BatchJob 终态 + mission_task PENDING」孤儿并补发通知。
     * 常态应为 0；持续 &gt;0 说明 BatchJob → Mission 的主通道（MQ）有问题，需排查。
     */
    public void recordMissionReconcilerRecovered() {
        missionReconcilerRecoveredCounter.increment();
    }

    /**
     * 记录 session-scoped HITL 等待状态（gauge），用于容量告警。
     * 由 {@link com.actionow.agent.interaction.UserInteractionService#pendingCount()} 驱动。
     */
    public void registerPendingAsksGauge(java.util.function.Supplier<Number> supplier) {
        io.micrometer.core.instrument.Gauge.builder("actionow.agent.ask_user.pending", supplier::get)
                .description("Currently pending HITL asks")
                .register(registry);
    }
}
