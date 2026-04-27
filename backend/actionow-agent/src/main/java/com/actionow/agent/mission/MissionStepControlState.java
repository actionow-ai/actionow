package com.actionow.agent.mission;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mission Step 控制工具门控状态。
 *
 * <p>用于强制约束「单步至多触发一个控制工具（complete_mission / fail_mission /
 * delegate_*）」。LLM 在一次响应中产生多个控制工具调用时（典型场景：模型不确定性导致
 * 同一 delegate 工具被重复 emit），首个调用通过、其余调用被 {@link ControlToolGuardCallback}
 * 拦截并返回错误结果，避免上游 BatchJob / Mission 状态机被同一决策反复推进。
 *
 * <p>状态以 {@code missionStepId} 为粒度隔离：
 * <ul>
 *   <li>missionStepId 为 null（Chat 模式或未注入步骤上下文）→ 直接放行，不影响普通会话；</li>
 *   <li>missionStepId 非 null → 按 step 维度独占；同 step 内仅放行首个控制工具。</li>
 * </ul>
 *
 * <p>底层使用 Caffeine 30 分钟 expireAfterWrite —— 正常路径仍由 {@link MissionExecutor}
 * 在 step 结束时调用 {@link #release(String)} 主动清理；TTL 仅作为 executor 异常退出 /
 * step 永远不结束等极端情况下的内存兜底，避免裸 Map 在退化场景下无限增长。
 *
 * @author Actionow
 */
@Slf4j
@Component
public class MissionStepControlState {

    /** Mission step 通常 1-10 分钟内结束；30 分钟 TTL 兜底覆盖极端慢路径而不至于过度占用内存。 */
    static final Duration FALLBACK_TTL = Duration.ofMinutes(30);

    private final Cache<String, AtomicReference<String>> firedByStep = Caffeine.newBuilder()
            .expireAfterWrite(FALLBACK_TTL)
            .build();

    /**
     * 尝试为指定 step 注册一个控制工具调用。
     *
     * @return true 表示当前调用是该 step 的首个控制工具，应继续执行；
     *         false 表示该 step 已有控制工具触发，本次调用应被拒绝。
     */
    public boolean tryFire(String missionStepId, String toolName) {
        if (missionStepId == null || missionStepId.isBlank()) {
            return true;
        }
        AtomicReference<String> ref = firedByStep.get(missionStepId, k -> new AtomicReference<>());
        boolean ok = ref.compareAndSet(null, toolName);
        if (!ok) {
            log.warn("Mission step 控制工具门控拒绝：missionStepId={}, requested={}, firstFired={}",
                    missionStepId, toolName, ref.get());
        }
        return ok;
    }

    /**
     * 返回该 step 已触发的控制工具名（用于错误信息提示）。
     */
    public String firedToolName(String missionStepId) {
        if (missionStepId == null) return null;
        AtomicReference<String> ref = firedByStep.getIfPresent(missionStepId);
        return ref != null ? ref.get() : null;
    }

    /**
     * 释放 step 对应的状态条目。Mission 步骤结束时由 {@link MissionExecutor} 调用。
     */
    public void release(String missionStepId) {
        if (missionStepId != null) {
            firedByStep.invalidate(missionStepId);
        }
    }

    /**
     * 仅用于诊断 / 测试。
     */
    public long activeStepCount() {
        firedByStep.cleanUp();
        return firedByStep.estimatedSize();
    }
}
