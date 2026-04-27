package com.actionow.agent.mission;

import com.actionow.agent.config.AgentRuntimeConfigService;
import com.actionow.agent.entity.AgentMissionStep;
import com.actionow.agent.service.MissionExecutionRecordService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Mission Prompt 构建器
 * 将 Mission 上下文组装为结构化 Prompt，替代 MissionExecutor 中的内联拼接。
 * 每次 Mission Step 的 Prompt 固定结构，便于测试和维护。
 *
 * @author Actionow
 */
@Component
@RequiredArgsConstructor
public class MissionPromptBuilder {

    private final AgentRuntimeConfigService runtimeConfig;
    private final ObjectMapper objectMapper;

    /**
     * 构建 Mission Step 的 Prompt
     */
    public String build(MissionPromptContext ctx) {
        StringBuilder sb = new StringBuilder();

        appendMissionContext(sb, ctx);
        appendExecutionHistory(sb, ctx);
        appendTaskStatus(sb, ctx);
        appendWarnings(sb, ctx);
        appendRules(sb, ctx);

        return sb.toString();
    }

    private void appendMissionContext(StringBuilder sb, MissionPromptContext ctx) {
        sb.append("[MISSION CONTEXT]\n");
        sb.append("Mission ID: ").append(ctx.missionId()).append("\n");
        sb.append("目标: ").append(ctx.goal()).append("\n");

        if (ctx.plan() != null && !ctx.plan().isEmpty()) {
            sb.append("当前计划: ").append(ctx.plan()).append("\n");
        }

        sb.append("当前步骤: 第 ").append(ctx.stepNo()).append(" 步\n");
    }

    private void appendExecutionHistory(StringBuilder sb, MissionPromptContext ctx) {
        List<AgentMissionStep> previousSteps = ctx.recentSteps();
        if (previousSteps == null || previousSteps.isEmpty()) {
            return;
        }

        sb.append("\n[EXECUTION HISTORY]\n");
        sb.append("已完成步骤:\n");

        List<AgentMissionStep> displaySteps = previousSteps;
        if (displaySteps.size() > runtimeConfig.getMissionMaxContextSteps()) {
            int olderCount = displaySteps.size() - runtimeConfig.getMissionMaxContextSteps();
            sb.append("  ... (已完成 ").append(olderCount).append(" 个更早的步骤)\n");
            displaySteps = displaySteps.subList(olderCount, displaySteps.size());
        }

        for (AgentMissionStep prevStep : displaySteps) {
            String summary = prevStep.getOutputSummary() != null ? prevStep.getOutputSummary() : "无摘要";
            if (summary.length() > runtimeConfig.getMissionMaxStepSummaryChars()) {
                summary = summary.substring(0, runtimeConfig.getMissionMaxStepSummaryChars()) + "...";
            }
            sb.append("  - 第 ").append(prevStep.getStepNumber()).append(" 步 (")
                    .append(prevStep.getStepType()).append("): ")
                    .append(summary)
                    .append("\n");

            // 追加失败 / 被门控拒绝的工具调用，让 LLM 看到错误上下文，避免重复同样的决策
            appendToolFailures(sb, prevStep.getToolCalls());

            // 追加结构化 artifacts（如有），供后续步骤精确引用
            Map<String, Object> artifacts = prevStep.getArtifacts();
            if (artifacts != null && !artifacts.isEmpty()) {
                try {
                    sb.append("    artifacts: ").append(objectMapper.writeValueAsString(artifacts)).append("\n");
                } catch (JsonProcessingException ignored) {
                    // 序列化失败时跳过 artifacts，不影响主流程
                }
            }
        }
    }

    /**
     * 渲染上一步的失败 / 被门控拒绝工具调用，最多 5 条。
     * 与 toolCalls 字段定义对齐（参见 MissionExecutor#summarizeToolCall）：
     * 含 skipped=true 表示控制工具门控拒绝；含 error 表示业务返回 success=false 的错误信息。
     */
    private void appendToolFailures(StringBuilder sb, List<Map<String, Object>> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        int rendered = 0;
        for (Map<String, Object> tc : toolCalls) {
            boolean skipped = Boolean.TRUE.equals(tc.get("skipped"));
            String error = tc.get("error") != null ? tc.get("error").toString() : null;
            boolean success = !Boolean.FALSE.equals(tc.get("success"));
            if (!skipped && error == null && success) {
                continue;
            }
            if (rendered == 0) {
                sb.append("    工具异常:\n");
            }
            String name = String.valueOf(tc.getOrDefault("toolName", "unknown"));
            if (skipped) {
                Object fired = tc.get("controlToolFired");
                sb.append("      · ").append(name).append(" 被门控拒绝（本步已先触发 ")
                        .append(fired != null ? fired : "其它控制工具")
                        .append("，单步只允许一个控制工具）\n");
            } else if (error != null) {
                sb.append("      · ").append(name).append(" 失败: ").append(error).append("\n");
            } else {
                sb.append("      · ").append(name).append(" 失败（无错误明细）\n");
            }
            if (++rendered >= 5) {
                sb.append("      · ... 更多失败已省略\n");
                break;
            }
        }
    }

    private void appendTaskStatus(StringBuilder sb, MissionPromptContext ctx) {
        MissionExecutionRecordService.MissionTaskStats taskStats = ctx.taskStats();
        if (taskStats == null) {
            return;
        }

        if (taskStats.completed() > 0 || taskStats.failed() > 0) {
            sb.append("\n[TASK STATUS]\n");
            sb.append("上一步委派的任务结果:\n");
            sb.append("  - 成功: ").append(taskStats.completed()).append(" 个\n");
            sb.append("  - 失败: ").append(taskStats.failed()).append(" 个\n");

            List<String> failedTaskIds = ctx.failedTaskIds();
            if (failedTaskIds != null && !failedTaskIds.isEmpty()) {
                sb.append("  失败的任务 ID: ").append(failedTaskIds).append("\n");
            }
        }
    }

    private void appendWarnings(StringBuilder sb, MissionPromptContext ctx) {
        if (ctx.noProgressCount() >= runtimeConfig.getMissionLoopWarnThreshold()) {
            int loopLimit = runtimeConfig.getMissionLoopFailThreshold();
            sb.append("\n[WARNING]\n");
            sb.append("检测到你已连续 ").append(ctx.noProgressCount())
                    .append(" 步未产生实质进展（重复调用相同工具或无工具调用）。")
                    .append("请改变策略、尝试不同方法、跳过当前步骤或调用 complete_mission / fail_mission 结束。")
                    .append("如继续无进展，Mission 将在第 ").append(loopLimit).append(" 步自动终止。\n");
        }
    }

    private void appendRules(StringBuilder sb, MissionPromptContext ctx) {
        sb.append("\n[控制工具硬性规则 — 必须遵守]\n");
        sb.append("一步内**有且仅有一个**控制工具会被接受；控制工具包括：\n");
        sb.append("  complete_mission, fail_mission, delegate_batch_generation, delegate_scope_generation, delegate_pipeline_generation\n");
        sb.append("\n");
        sb.append("  ✓ 正确示例：本步只调 complete_mission(summary=\"...\")\n");
        sb.append("  ✓ 正确示例：本步只调 delegate_batch_generation(...)；后续由下一步再考虑是否 complete\n");
        sb.append("  ✗ 错误示例：本步同时调 complete_mission + delegate_batch_generation —— 系统将按优先级\n");
        sb.append("    FAIL > DELEGATE > COMPLETE 取舍（丢弃其它），并在日志中记录错误\n");
        sb.append("\n");
        sb.append("判断原则：\n");
        sb.append("- 如果还需要再派发新任务 → 本步只调 delegate_*，下一步再决定是否 complete\n");
        sb.append("- 如果任务确已全部完成 → 本步只调 complete_mission，不要同时再 delegate\n");
        sb.append("- 如果遇到无法恢复的错误 → 本步只调 fail_mission\n");

        sb.append("\n[指令]\n");
        sb.append("请根据当前状态继续执行你的计划。你可以:\n");
        sb.append("1. 调用普通工具执行操作（创建/查询/更新实体等，可多个）\n");
        sb.append("2. 调用 delegate_batch_generation 委派单项批量生成（如为多个角色生成头像）\n");
        sb.append("3. 调用 delegate_scope_generation 委派作用域级批量生成（如为整个剧集生成所有分镜图）\n");
        sb.append("4. 调用 delegate_pipeline_generation 委派多步骤流水线生成（如先生成文本再生成图片）\n");
        sb.append("5. 调用 update_mission_plan 更新你的计划\n");
        sb.append("6. 调用 complete_mission(summary) 标记任务完成\n");
        sb.append("7. 调用 fail_mission(reason) 标记任务失败\n");

        sb.append("\n[其它规则]\n");
        sb.append("- missionId 已自动绑定到当前上下文，所有 Mission 控制工具无需传递 missionId\n");
        sb.append("- 严禁调用 create_mission（Mission 不可嵌套），新任务应在当前 Mission 内完成编排\n");
        sb.append("- 不要猜测不存在的 ID，先查询后再使用\n");
        sb.append("- 如有失败项，请自行判断是否重试、跳过或终止\n");
    }

    /**
     * Mission Prompt 构建上下文
     */
    public record MissionPromptContext(
            String missionId,
            int stepNo,
            String goal,
            Map<String, Object> plan,
            List<AgentMissionStep> recentSteps,
            MissionExecutionRecordService.MissionTaskStats taskStats,
            List<String> failedTaskIds,
            int noProgressCount
    ) {}
}
