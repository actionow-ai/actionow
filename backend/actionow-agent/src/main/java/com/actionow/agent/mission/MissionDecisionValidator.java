package com.actionow.agent.mission;

import com.actionow.agent.core.agent.AgentResponse;
import com.actionow.agent.runtime.ExecutionTranscript;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Mission Step 决策解析器
 * 从 Agent 响应的工具调用列表中提取结构化的 {@link MissionDecision}。
 *
 * <p>规则：
 * <ol>
 *   <li>无工具调用 → {@link MissionDecision.Continue}</li>
 *   <li>只有非控制工具 → {@link MissionDecision.Continue}</li>
 *   <li>控制工具全部失败（无成功的控制决策） → {@link MissionDecision.Fail} (CONTROL_TOOLS_FAILED)</li>
 *   <li>仅 complete_mission 成功 → {@link MissionDecision.Complete}</li>
 *   <li>仅 fail_mission 成功 → {@link MissionDecision.Fail}</li>
 *   <li>仅 delegate_* 成功 → {@link MissionDecision.Wait}</li>
 *   <li>多种控制决策同时成功 → 按优先级 {@code FAIL > DELEGATE > COMPLETE} 兜底解析并记录 ERROR 日志，
 *       不再让整个 Mission 失败；LLM 在一步内同时调 complete + delegate 是常见的模型困惑，
 *       保守地保留 DELEGATE（任务继续推进）比接受 COMPLETE（可能漏掉委派的后续工作）更安全。</li>
 * </ol>
 *
 * @author Actionow
 */
@Slf4j
@Component
public class MissionDecisionValidator {

    private static final Set<String> DELEGATE_TOOLS = Set.of(
            "delegate_batch_generation",
            "delegate_scope_generation",
            "delegate_pipeline_generation"
    );

    /** 与 {@link ControlToolGuardCallback#CONTROL_TOOL_NAMES} 共用单一来源，新增决策类工具时只改一处。 */
    private static final Set<String> ALL_CONTROL_TOOLS = ControlToolGuardCallback.CONTROL_TOOL_NAMES;

    /**
     * 从 Agent 响应中解析决策。
     *
     * @param missionId Mission ID（仅用于日志）
     * @param response  Agent 响应
     * @return 解析出的决策
     * @throws MissionDecisionException 当一次响应中包含多种冲突控制决策时
     */
    public MissionDecision validate(String missionId, AgentResponse response) {
        List<AgentResponse.ToolCallInfo> toolCalls = response.getToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return new MissionDecision.Continue(response.getContent());
        }

        boolean hasCompletion = false;
        boolean hasFailure = false;
        boolean hasDelegation = false;
        String completeSummary = null;
        String failCode = null;
        String failReason = null;
        List<String> delegatedTools = new java.util.ArrayList<>();
        List<String> failedControlTools = new java.util.ArrayList<>();

        for (AgentResponse.ToolCallInfo toolCall : toolCalls) {
            String toolName = toolCall.getToolName();
            if (!ALL_CONTROL_TOOLS.contains(toolName)) {
                continue;
            }
            if (!toolCall.isSuccess()) {
                log.warn("控制工具调用失败，不计为有效决策: tool={}, missionId={}", toolName, missionId);
                failedControlTools.add(toolName);
                continue;
            }

            switch (toolName) {
                case "complete_mission" -> {
                    hasCompletion = true;
                    completeSummary = extractField(toolCall, "summary");
                }
                case "fail_mission" -> {
                    hasFailure = true;
                    failCode = extractField(toolCall, "code");
                    failReason = extractField(toolCall, "reason");
                }
                default -> {
                    // delegate_*
                    hasDelegation = true;
                    delegatedTools.add(toolName);
                }
            }
        }

        int decisionCount = (hasCompletion ? 1 : 0) + (hasFailure ? 1 : 0) + (hasDelegation ? 1 : 0);

        if (decisionCount > 1) {
            String conflict = "检测到多种控制决策（" +
                    (hasCompletion ? "COMPLETE " : "") +
                    (hasFailure ? "FAIL " : "") +
                    (hasDelegation ? "DELEGATE " : "") +
                    "）";
            // 优先级：FAIL > DELEGATE > COMPLETE。LLM 在一步内混用这些是常见的模型困惑，
            // 直接让 Mission 整体失败太重；按优先级取舍后继续推进，只记录 ERROR 供监控告警。
            String winner = hasFailure ? "FAIL" : (hasDelegation ? "DELEGATE" : "COMPLETE");
            log.error("Mission 决策冲突（按优先级取舍，保留 {}）: missionId={}, conflict={}",
                    winner, missionId, conflict);
        }

        // 优先级解析：FAIL > DELEGATE > COMPLETE
        if (hasFailure) {
            return new MissionDecision.Fail(failCode, failReason);
        }
        if (hasDelegation) {
            return new MissionDecision.Wait(response.getContent(), List.copyOf(delegatedTools));
        }
        if (hasCompletion) {
            return new MissionDecision.Complete(completeSummary);
        }

        // 区分"未调用控制工具"(正常 Continue) vs "调用了但全部失败"(异常 Fail)
        if (!failedControlTools.isEmpty()) {
            String failedTools = String.join(", ", failedControlTools);
            log.error("Mission {} 所有控制工具调用均失败: {}", missionId, failedTools);
            return new MissionDecision.Fail(
                    "CONTROL_TOOLS_FAILED",
                    "所有控制工具调用均失败: " + failedTools
            );
        }

        // 只有非控制工具调用 → 正常的中间推理步骤
        return new MissionDecision.Continue(response.getContent());
    }

    public MissionDecision validate(String missionId, ExecutionTranscript transcript) {
        AgentResponse response = AgentResponse.builder()
                .success(true)
                .content(transcript.getFinalText())
                .toolCalls(transcript.getToolCalls())
                .build();
        return validate(missionId, response);
    }

    @SuppressWarnings("unchecked")
    private String extractField(AgentResponse.ToolCallInfo toolCall, String field) {
        Object result = toolCall.getResult();
        if (result instanceof java.util.Map<?, ?> map) {
            Object value = map.get(field);
            return value != null ? value.toString() : null;
        }
        return null;
    }
}
