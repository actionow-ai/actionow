package com.actionow.agent.mission;

import com.actionow.agent.core.scope.AgentContext;
import com.actionow.agent.core.scope.AgentContextHolder;
import com.actionow.agent.metrics.AgentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ControlToolGuardCallback 回归测试
 *
 * <p>历史背景：
 * Mission 019dc989-6b71-750c-9b8f-540ea310d066 单 LLM step 内 5x
 * delegate_batch_generation 被全部下发，造成 5 个 BatchJob、25 条冗余图像生成。
 * 本测试绑死「单步至多一个控制工具」语义，防止回归。
 *
 * <p>覆盖：
 * <ul>
 *   <li>同 step 内首个调用放行，后续调用返回 rejection JSON 并触发 metric；</li>
 *   <li>同 step 内不同控制工具混调，仍只放行第一个；</li>
 *   <li>跨 step 之间互不干扰；</li>
 *   <li>missionStepId 为空（Chat 模式）始终放行，不影响普通会话。</li>
 * </ul>
 */
class ControlToolGuardCallbackTest {

    private MissionStepControlState state;
    private AgentMetrics metrics;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        state = new MissionStepControlState();
        registry = new SimpleMeterRegistry();
        metrics = new AgentMetrics(registry);
    }

    @AfterEach
    void tearDown() {
        AgentContextHolder.clearContext();
    }

    @Test
    void firstCallPassesSubsequentCallsRejected() {
        AgentContextHolder.setContext(contextWithStep("step-1"));
        CountingDelegate delegate = new CountingDelegate("delegate_batch_generation", "{\"ok\":true}");
        ControlToolGuardCallback guard = new ControlToolGuardCallback(delegate, state, metrics);

        String first = guard.call("{}");
        String second = guard.call("{}");
        String third = guard.call("{}");

        assertEquals("{\"ok\":true}", first, "首次调用应直达 delegate");
        assertEquals(1, delegate.callCount.get(), "delegate 仅应被触发一次");
        assertTrue(second.contains("\"skipped\":true"), "重复调用应返回 rejection JSON");
        assertTrue(second.contains("controlToolFired"));
        assertTrue(third.contains("\"skipped\":true"));
        assertEquals(2.0, rejectedCount(), "两次重复调用应各计一次 metric");
    }

    @Test
    void differentControlToolsInSameStepStillBlocked() {
        AgentContextHolder.setContext(contextWithStep("step-2"));
        CountingDelegate delegateA = new CountingDelegate("delegate_batch_generation", "A-result");
        CountingDelegate delegateB = new CountingDelegate("complete_mission", "B-result");
        ControlToolGuardCallback guardA = new ControlToolGuardCallback(delegateA, state, metrics);
        ControlToolGuardCallback guardB = new ControlToolGuardCallback(delegateB, state, metrics);

        assertEquals("A-result", guardA.call("{}"));
        String blocked = guardB.call("{}");

        assertTrue(blocked.contains("\"skipped\":true"));
        assertTrue(blocked.contains("delegate_batch_generation"),
                "rejection 应指出已触发的工具名");
        assertEquals(0, delegateB.callCount.get(), "第二个控制工具不应抵达 delegate");
    }

    @Test
    void differentStepsIsolated() {
        CountingDelegate delegate = new CountingDelegate("delegate_batch_generation", "ok");
        ControlToolGuardCallback guard = new ControlToolGuardCallback(delegate, state, metrics);

        AgentContextHolder.setContext(contextWithStep("step-A"));
        guard.call("{}");
        AgentContextHolder.setContext(contextWithStep("step-B"));
        guard.call("{}");

        assertEquals(2, delegate.callCount.get(), "不同 step 应彼此独立放行");
        assertEquals(0.0, rejectedCount());
    }

    @Test
    void chatModeWithoutMissionStepAlwaysPasses() {
        AgentContextHolder.setContext(contextWithStep(null));
        CountingDelegate delegate = new CountingDelegate("delegate_batch_generation", "ok");
        ControlToolGuardCallback guard = new ControlToolGuardCallback(delegate, state, metrics);

        guard.call("{}");
        guard.call("{}");
        guard.call("{}");

        assertEquals(3, delegate.callCount.get(), "Chat 模式下门控不生效");
        assertEquals(0.0, rejectedCount());
    }

    @Test
    void releaseAllowsRefireAfterStepFinishes() {
        AgentContextHolder.setContext(contextWithStep("step-recycled"));
        CountingDelegate delegate = new CountingDelegate("delegate_batch_generation", "ok");
        ControlToolGuardCallback guard = new ControlToolGuardCallback(delegate, state, metrics);

        guard.call("{}");
        assertFalse(guard.call("{}").startsWith("ok"), "release 之前应被拒绝");

        state.release("step-recycled");
        // 同一 step 在新阶段不会复用；这里只是验证 release 之后 state 内部已清除
        assertEquals(0L, state.activeStepCount());
    }

    private AgentContext contextWithStep(String missionStepId) {
        return AgentContext.builder().missionStepId(missionStepId).build();
    }

    private double rejectedCount() {
        return registry.find("actionow.agent.mission.control_tool.rejected.total")
                .counter()
                .count();
    }

    /** 简单 ToolCallback fake：记录调用次数并返回固定结果，无外部依赖。 */
    private static final class CountingDelegate implements ToolCallback {
        private final String name;
        private final String result;
        final AtomicInteger callCount = new AtomicInteger();

        CountingDelegate(String name, String result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description("test delegate")
                    .inputSchema("{}")
                    .build();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().build();
        }

        @Override
        public String call(String arguments) {
            callCount.incrementAndGet();
            return result;
        }
    }
}
