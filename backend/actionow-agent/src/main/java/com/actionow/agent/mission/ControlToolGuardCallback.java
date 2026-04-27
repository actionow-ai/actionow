package com.actionow.agent.mission;

import com.actionow.agent.core.scope.AgentContext;
import com.actionow.agent.core.scope.AgentContextHolder;
import com.actionow.agent.metrics.AgentMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Mission 控制工具门控装饰器。
 *
 * <p>控制工具（complete_mission / fail_mission / delegate_*）每个 Mission Step
 * 仅允许触发一次。本装饰器查询 {@link MissionStepControlState}：
 * <ul>
 *   <li>首个调用：放行底层 {@link ToolCallback#call(String)}；</li>
 *   <li>同 step 后续调用：直接返回错误 JSON，避免 BatchJob 重复创建 / Mission
 *       状态被同决策反复推进。</li>
 * </ul>
 *
 * <p>仅 Mission 模式（AgentContext.missionStepId 非空）生效；Chat 模式默认放行。
 *
 * @author Actionow
 */
@Slf4j
public class ControlToolGuardCallback implements ToolCallback {

    /**
     * 必须以单步唯一决策语义被门控的工具集合。新增决策类工具时同步追加。
     */
    public static final Set<String> CONTROL_TOOL_NAMES = Set.of(
            "complete_mission",
            "fail_mission",
            "delegate_batch_generation",
            "delegate_scope_generation",
            "delegate_pipeline_generation"
    );

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolCallback delegate;
    private final MissionStepControlState controlState;
    private final AgentMetrics agentMetrics;
    private final String toolName;

    public ControlToolGuardCallback(ToolCallback delegate, MissionStepControlState controlState) {
        this(delegate, controlState, null);
    }

    public ControlToolGuardCallback(ToolCallback delegate, MissionStepControlState controlState,
                                    AgentMetrics agentMetrics) {
        this.delegate = delegate;
        this.controlState = controlState;
        this.agentMetrics = agentMetrics;
        this.toolName = delegate.getToolDefinition().name();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String arguments) {
        return guard(() -> delegate.call(arguments));
    }

    @Override
    public String call(String arguments, ToolContext toolContext) {
        return guard(() -> delegate.call(arguments, toolContext));
    }

    private String guard(Supplier<String> action) {
        String missionStepId = currentMissionStepId();
        if (controlState.tryFire(missionStepId, toolName)) {
            return action.get();
        }
        String first = controlState.firedToolName(missionStepId);
        log.warn("[ControlToolGuard] 拒绝重复控制工具调用: tool={}, missionStepId={}, firstFired={}",
                toolName, missionStepId, first);
        if (agentMetrics != null) {
            agentMetrics.recordMissionControlToolRejected();
        }
        return rejectionPayload(first);
    }

    private String rejectionPayload(String firstFired) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("skipped", true);
        body.put("error", "本步骤已触发控制工具 '" + firstFired + "'，禁止再次调用 '" + toolName
                + "'。Mission 单步仅允许一个控制工具决策（complete_mission / fail_mission / delegate_*）。"
                + "请直接结束本步骤；如需追加任务，会在下一步由系统继续调度。");
        body.put("controlToolFired", firstFired);
        body.put("rejected", toolName);
        try {
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            return "{\"success\":false,\"skipped\":true,\"error\":\"control tool already fired in this step\"}";
        }
    }

    private static String currentMissionStepId() {
        AgentContext ctx = AgentContextHolder.getContext();
        return ctx != null ? ctx.getMissionStepId() : null;
    }
}
