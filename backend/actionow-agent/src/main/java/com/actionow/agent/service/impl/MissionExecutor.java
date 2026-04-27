package com.actionow.agent.service.impl;

import com.actionow.agent.config.AgentRuntimeConfigService;
import com.actionow.agent.config.constant.AgentExecutionMode;
import com.actionow.agent.constant.MissionStatus;
import com.actionow.agent.constant.MissionStepStatus;
import com.actionow.agent.constant.MissionStepType;
import com.actionow.agent.billing.service.AgentBillingCalculator;
import com.actionow.agent.context.ContextWindowManager;
import com.actionow.agent.mission.MissionDecision;
import com.actionow.agent.mission.MissionDecisionException;
import com.actionow.agent.mission.MissionDecisionValidator;
import com.actionow.agent.mission.MissionPromptBuilder;
import com.actionow.agent.mission.MissionReducer;
import com.actionow.agent.mission.MissionStepControlState;
import com.actionow.agent.core.context.SessionContextHolder;
import com.actionow.agent.core.scope.AgentContext;
import com.actionow.agent.core.scope.AgentContextBuilder;
import com.actionow.agent.core.scope.AgentContextHolder;
import com.actionow.agent.dto.request.SendMessageRequest;
import com.actionow.agent.entity.AgentMission;
import com.actionow.agent.entity.AgentMissionStep;
import com.actionow.agent.entity.AgentSessionEntity;
import com.actionow.agent.mapper.AgentMissionStepMapper;
import com.actionow.agent.mapper.AgentSessionMapper;
import com.actionow.agent.core.agent.AgentResponse;
import com.actionow.agent.resolution.service.AgentResolutionService;
import com.actionow.agent.runtime.AgentRuntimeGateway;
import com.actionow.agent.runtime.ExecutionRequest;
import com.actionow.agent.runtime.ExecutionTranscript;
import com.actionow.agent.runtime.ToolAccessPolicy;
import com.actionow.agent.service.MissionExecutionRecordService;
import com.actionow.agent.service.MissionService;
import com.actionow.agent.service.MissionSseService;
import com.actionow.common.core.context.UserContextHolder;
import com.actionow.common.mq.constant.MqConstants;
import com.actionow.common.mq.message.MessageWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;

/**
 * Mission 执行器
 * MQ Consumer，驱动 Agent 自主执行循环
 *
 * 核心流程:
 * 1. 收到 mission.step.execute 消息
 * 2. 构建上下文，调用 Agent (SYNC 模式)
 * 3. 解析 Agent 决策（通过工具调用判断）
 * 4. 根据决策推进 Mission 状态
 *
 * @author Actionow
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MissionExecutor {

    private final AgentRuntimeConfigService runtimeConfig;
    private final MissionService missionService;
    private final AgentRuntimeGateway runtimeGateway;
    private final AgentResolutionService agentResolutionService;
    private final AgentMissionStepMapper stepMapper;
    private final AgentSessionMapper sessionMapper;
    private final MissionSseService missionSseService;
    private final MissionExecutionRecordService missionExecutionRecordService;
    private final MissionPromptBuilder missionPromptBuilder;
    private final MissionDecisionValidator missionDecisionValidator;
    private final MissionReducer missionReducer;
    private final MissionStepControlState missionStepControlState;
    private final ToolAccessPolicy toolAccessPolicy;
    private final AgentBillingCalculator billingCalculator;
    private final AgentContextBuilder agentContextBuilder;
    private final com.actionow.agent.registry.DatabaseSkillRegistry skillRegistry;
    private final ContextWindowManager contextWindowManager;
    private final com.actionow.agent.saa.session.SaaSessionService saaSessionService;
    private final com.actionow.agent.saa.stream.SaaStreamProcessor streamProcessor;

    /**
     * Mission Agent 实例缓存（P3-1）。
     * Key = missionId，Mission 内 agentType/skills 不变，首步构建后后续步骤复用。
     * 终态时自动清理。
     */
    private final ConcurrentHashMap<String, SupervisorAgent> missionAgentCache = new ConcurrentHashMap<>();

    @RabbitListener(queues = MqConstants.Mission.QUEUE, containerFactory = "rabbitListenerContainerFactory")
    public void onMissionStep(MessageWrapper<Map<String, Object>> message, Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        String missionId = null;
        try {
            missionId = (String) message.getPayload().get("missionId");
            log.info("收到 Mission Step 执行事件: missionId={}", missionId);

            executeMissionStep(missionId);

            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("Mission Step 执行失败: missionId={}", missionId, e);
            try {
                handleStepFailure(missionId, e);
                channel.basicAck(deliveryTag, false);
            } catch (Exception ackEx) {
                log.error("消息确认失败", ackEx);
            }
        }
    }

    /**
     * 执行 Mission 的一个 Step
     */
    private void executeMissionStep(String missionId) {
        AgentMission mission = missionService.getEntityById(missionId);

        // 检查是否已取消或终态
        if (mission.isTerminal()) {
            log.info("Mission 已处于终态，跳过执行: missionId={}, status={}", missionId, mission.getStatus());
            evictAgentCache(missionId);
            return;
        }

        // 检查步骤上限
        int maxSteps = runtimeConfig.getMissionMaxSteps();
        if (mission.getCurrentStep() >= maxSteps) {
            missionReducer.applyFatalFail(mission, "超过最大步骤限制 (" + maxSteps + ")");
            evictAgentCache(missionId);
            return;
        }

        // 循环检测
        int loopFailThreshold = runtimeConfig.getMissionLoopFailThreshold();
        int noProgressCount = detectConsecutiveNoProgress(missionId, mission.getCurrentStep());
        if (noProgressCount >= loopFailThreshold) {
            missionReducer.applyFatalFail(mission, "检测到循环执行：连续 " + noProgressCount + " 步无实质进展，自动终止");
            evictAgentCache(missionId);
            return;
        }

        // 更新状态为 EXECUTING
        mission.setStatus(MissionStatus.EXECUTING.getCode());
        if (mission.getStartedAt() == null) {
            mission.setStartedAt(LocalDateTime.now());
        }
        missionService.save(mission);

        // 创建步骤记录
        int stepNumber = mission.getCurrentStep() + 1;
        AgentMissionStep step = missionService.createStep(missionId, stepNumber, MissionStepType.AGENT_INVOKE.getCode());
        step.setStatus(MissionStepStatus.RUNNING.getCode());
        step.setStartedAt(LocalDateTime.now());
        missionService.updateStep(step);

        // SSE 推送步骤开始
        missionSseService.pushStepStarted(missionId, stepNumber, MissionStepType.AGENT_INVOKE.getCode());
        missionExecutionRecordService.recordEvent(
                missionId,
                "STEP_STARTED",
                "开始执行第 " + stepNumber + " 步",
                Map.of("stepNumber", stepNumber, "stepType", MissionStepType.AGENT_INVOKE.getCode())
        );

        // 构建 Agent 输入上下文
        MissionPromptBuilder.MissionPromptContext promptContext = buildPromptContext(mission, step, noProgressCount);
        String context = missionPromptBuilder.build(promptContext);
        step.setInputSummary(truncate(context, 2000));
        missionService.updateStep(step);
        missionExecutionRecordService.recordTrace(
                missionId,
                step.getId(),
                "STEP_INPUT",
                Map.of("prompt", context)
        );

        // 设置执行上下文（UserContext + AgentContext + SessionContextHolder）
        setupExecutionContext(mission);

        // Skill 版本漂移检测（WARN only，不阻断执行）
        checkSkillVersionDrift(mission);

        try {
            // 调用 Agent (SYNC 模式)
            SendMessageRequest request = new SendMessageRequest();
            request.setMessage(context);
            request.setExecutionMode(AgentExecutionMode.MISSION.name());
            request.setMissionId(missionId);
            request.setMissionStepId(step.getId());
            String effectiveSessionId = normalizeSessionId(mission.getRuntimeSessionId());
            if (effectiveSessionId == null) {
                throw new IllegalStateException("Mission 缺少有效会话ID，无法继续执行");
            }

            // 使用 Mission 创建时快照的 agentType 和 skillNames，而非重新解析
            String resolvedAgentType = mission.getAgentType() != null
                    ? mission.getAgentType()
                    : agentTypeOrDefault(mission, effectiveSessionId);
            // null 表示"使用 Agent 自身默认 Skills"，空列表表示"不要任何 Skill"
            List<String> resolvedSkillNames = mission.getAgentSkillNames() != null
                    && !mission.getAgentSkillNames().isEmpty()
                    ? mission.getAgentSkillNames()
                    : null;

            var resolvedProfile = agentResolutionService.resolve(
                    resolvedAgentType,
                    mission.getWorkspaceId(),
                    mission.getCreatorId(),
                    resolvedSkillNames);

            // Mission 执行不需要 Coordinator 层，直接展开为 worker Agent，
            // 否则 ToolAccessPolicy 会因为 COORDINATOR 的 resolvedTools 为空而丢失所有业务工具
            if (Boolean.TRUE.equals(resolvedProfile.getCoordinator())) {
                String workerType = resolvedProfile.getSubAgentTypes() != null
                        && !resolvedProfile.getSubAgentTypes().isEmpty()
                        ? resolvedProfile.getSubAgentTypes().get(0)
                        : "UNIVERSAL";
                resolvedProfile = agentResolutionService.resolve(
                        workerType,
                        mission.getWorkspaceId(),
                        mission.getCreatorId(),
                        resolvedSkillNames);
                log.info("Mission 执行展开 COORDINATOR -> {}: missionId={}", workerType, missionId);

                // 创建新的 AgentContext 副本更新 agentType，避免原地突变导致并发线程读到中间状态
                AgentContext currentCtx = AgentContextHolder.getContext();
                if (currentCtx != null) {
                    AgentContext updatedCtx = currentCtx.toBuilder()
                            .agentType(workerType)
                            .build();
                    AgentContextHolder.setContext(updatedCtx);
                    if (effectiveSessionId != null) {
                        SessionContextHolder.set(effectiveSessionId,
                                UserContextHolder.getContext(), updatedCtx);
                    }
                }
            }

            // P3-1: 复用缓存的 Agent 实例（Mission 多步间不变）
            SupervisorAgent cachedAgent = missionAgentCache.get(missionId);

            // 构建 toolName → skillName 映射
            Map<String, String> toolSkillMapping = new HashMap<>();
            if (resolvedProfile.getResolvedSkills() != null) {
                for (var skill : resolvedProfile.getResolvedSkills()) {
                    if (skill.getToolIds() != null) {
                        for (String toolId : skill.getToolIds()) {
                            toolSkillMapping.put(toolId, skill.getName());
                        }
                    }
                }
            }

            // 构建上下文消息：Mission 的 runtimeSessionId 中包含完整的工具调用历史
            List<org.springframework.ai.chat.messages.Message> contextMessages = null;
            try {
                String llmProviderId = resolvedProfile.getLlmProviderId();
                if (llmProviderId != null && effectiveSessionId != null) {
                    contextMessages = contextWindowManager.buildContextMessages(
                            effectiveSessionId, context, llmProviderId);
                }
            } catch (Exception e) {
                log.warn("Mission 构建上下文消息失败，回退为无历史模式: missionId={}, error={}",
                        missionId, e.getMessage());
            }

            ExecutionTranscript transcript = runtimeGateway.execute(ExecutionRequest.builder()
                    .mode(AgentExecutionMode.MISSION)
                    .sessionId(effectiveSessionId)
                    .resolvedAgent(resolvedProfile)
                    .input(context)
                    .contextMessages(contextMessages)
                    .inputTokens((long) countEstimatedInputTokens(context))
                    .toolAccessPolicy(toolAccessPolicy)
                    .toolSkillMapping(toolSkillMapping)
                    .cachedAgent(cachedAgent)
                    .build());

            // 首步构建后缓存 Agent 实例
            if (cachedAgent == null && transcript.getAgent() != null) {
                missionAgentCache.put(missionId, transcript.getAgent());
                log.debug("缓存 Agent 实例: missionId={}", missionId);
            }

            // 将工具调用事件和助手回复持久化到会话消息表，供后续步骤的上下文历史引用
            persistStepMessages(effectiveSessionId, context, transcript);

            // 记录步骤结果
            step.setOutputSummary(truncate(transcript.getFinalText(), 2000));
            step.setInputTokens(transcript.getUsage().getInputTokens());
            step.setOutputTokens(transcript.getUsage().getOutputTokens());
            step.setModelName(transcript.getModelName());
            step.setCreditCost(calculateStepCost(transcript));
            if (transcript.getToolCalls() != null) {
                step.setToolCalls(transcript.getToolCalls().stream()
                        .map(this::summarizeToolCall)
                        .toList());
                step.setArtifacts(extractArtifacts(transcript.getToolCalls()));
                missionExecutionRecordService.recordTrace(
                        missionId,
                        step.getId(),
                        "TOOL_CALLS",
                        Map.of("toolCalls", transcript.getToolCalls())
                );
            }
            step.setStatus(MissionStepStatus.COMPLETED.getCode());
            step.setCompletedAt(LocalDateTime.now());
            step.setDurationMs(java.time.Duration.between(step.getStartedAt(), step.getCompletedAt()).toMillis());
            missionService.updateStep(step);
            mission.setTotalCreditCost((mission.getTotalCreditCost() != null ? mission.getTotalCreditCost() : 0L)
                    + (step.getCreditCost() != null ? step.getCreditCost() : 0L));
            missionExecutionRecordService.recordTrace(
                    missionId,
                    step.getId(),
                    "ASSISTANT",
                    Map.of("content", transcript.getFinalText() != null ? transcript.getFinalText() : "")
            );

            // SSE 推送步骤完成
            String responseSummary = truncate(transcript.getFinalText(), 200);
            if (responseSummary == null) {
                responseSummary = "";
            }
            missionSseService.pushStepCompleted(missionId, step.getStepNumber(), responseSummary);
            missionExecutionRecordService.recordEvent(
                    missionId,
                    "STEP_COMPLETED",
                    "第 " + step.getStepNumber() + " 步执行完成",
                    Map.of("stepNumber", step.getStepNumber(), "summary", responseSummary)
            );

            // 解析 Agent 决策并推进 Mission
            MissionDecision decision;
            try {
                decision = missionDecisionValidator.validate(missionId, transcript);
            } catch (MissionDecisionException e) {
                log.error("Mission 决策校验失败: missionId={}, error={}", missionId, e.getMessage());
                decision = new MissionDecision.Fail("INVALID_DECISION", e.getMessage());
            }
            missionReducer.apply(mission, step, decision);

            // P3-1: 终态时清理缓存的 Agent 实例
            if (decision instanceof MissionDecision.Complete || decision instanceof MissionDecision.Fail) {
                evictAgentCache(missionId);
            }

        } catch (Exception e) {
            step.setStatus(MissionStepStatus.FAILED.getCode());
            step.setOutputSummary("执行异常: " + e.getMessage());
            step.setCompletedAt(LocalDateTime.now());
            missionService.updateStep(step);
            missionExecutionRecordService.recordEvent(
                    missionId,
                    "STEP_FAILED",
                    "第 " + step.getStepNumber() + " 步执行失败",
                    Map.of("stepNumber", step.getStepNumber(), "error", e.getMessage())
            );
            throw e;
        } finally {
            // 释放控制工具门控状态，避免 ConcurrentHashMap 长期堆积
            missionStepControlState.release(step.getId());
            UserContextHolder.clear();
            AgentContextHolder.clearContext();
            String effectiveSessionId = normalizeSessionId(mission.getRuntimeSessionId());
            if (effectiveSessionId != null) {
                SessionContextHolder.clear(effectiveSessionId);
                SessionContextHolder.clearCurrentSessionId();
            }
        }
    }

    private MissionPromptBuilder.MissionPromptContext buildPromptContext(
            AgentMission mission, AgentMissionStep currentStep, int noProgressCount) {
        List<AgentMissionStep> previousSteps = getPreviousSteps(mission.getId(), currentStep.getStepNumber());
        MissionExecutionRecordService.MissionTaskStats taskStats = missionExecutionRecordService.summarize(mission.getId());
        List<String> failedTaskIds = missionExecutionRecordService.listTasks(mission.getId()).stream()
                .filter(task -> "FAILED".equalsIgnoreCase(task.getStatus()))
                .map(task -> task.getExternalTaskId() != null ? task.getExternalTaskId() : task.getBatchJobId())
                .filter(id -> id != null && !id.isBlank())
                .limit(5)
                .toList();

        return new MissionPromptBuilder.MissionPromptContext(
                mission.getId(),
                currentStep.getStepNumber(),
                mission.getGoal(),
                mission.getPlan(),
                previousSteps,
                taskStats,
                failedTaskIds,
                noProgressCount
        );
    }

    /**
     * 检测连续无进展步骤数
     *
     * <p>改进点（相对于仅比较工具名称集合）：
     * <ol>
     *   <li>工具签名包含参数摘要 (hash)，相同工具不同参数视为有进展</li>
     *   <li>失败的工具调用标记为 :DENIED，连续相同失败也视为无进展</li>
     *   <li>辅助判断：连续步骤 outputSummary 高度相似也计为无进展</li>
     * </ol>
     *
     * @param missionId         Mission ID
     * @param currentStepNumber 当前步骤编号（不含当前正在执行的）
     * @return 连续无进展步骤数
     */
    private int detectConsecutiveNoProgress(String missionId, int currentStepNumber) {
        int loopFailThreshold = runtimeConfig.getMissionLoopFailThreshold();
        try {
            List<AgentMissionStep> recentSteps = stepMapper.selectList(new LambdaQueryWrapper<AgentMissionStep>()
                    .eq(AgentMissionStep::getMissionId, missionId)
                    .eq(AgentMissionStep::getStepType, MissionStepType.AGENT_INVOKE.getCode())
                    .le(AgentMissionStep::getStepNumber, currentStepNumber)
                    .orderByDesc(AgentMissionStep::getStepNumber)
                    .last("LIMIT " + (loopFailThreshold + 1)));

            if (recentSteps.size() < 2) {
                return 0;
            }

            int noProgressCount = 0;
            Set<String> previousSignatureSet = null;
            String previousOutput = null;

            for (AgentMissionStep step : recentSteps) {
                Set<String> signatureSet = extractToolSignatureSet(step);
                String output = step.getOutputSummary();

                if (previousSignatureSet == null) {
                    previousSignatureSet = signatureSet;
                    previousOutput = output;
                    continue;
                }

                // 工具签名集合完全相同（含参数 hash）→ 无进展
                // 或工具签名不同但输出高度相似 → 也视为无进展
                if (signatureSet.equals(previousSignatureSet) || isOutputSimilar(output, previousOutput)) {
                    noProgressCount++;
                    previousSignatureSet = signatureSet;
                    previousOutput = output;
                } else {
                    break;
                }
            }

            if (noProgressCount > 0) {
                noProgressCount++;
                log.warn("Mission 循环检测: missionId={}, noProgressSteps={}", missionId, noProgressCount);
            }

            return noProgressCount;
        } catch (Exception e) {
            log.debug("循环检测失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 提取工具签名集合（包含参数摘要）
     * 格式: toolName:paramsHash 或 toolName:DENIED
     */
    private Set<String> extractToolSignatureSet(AgentMissionStep step) {
        Set<String> signatures = new HashSet<>();
        if (step.getToolCalls() != null) {
            for (Map<String, Object> tc : step.getToolCalls()) {
                Object nameObj = tc.get("toolName");
                if (nameObj == null) continue;
                String name = nameObj.toString();
                boolean success = Boolean.TRUE.equals(tc.get("success"));
                if (!success) {
                    signatures.add(name + ":DENIED");
                } else {
                    signatures.add(name + ":" + computeParamsHash(tc));
                }
            }
        }
        return signatures;
    }

    private String computeParamsHash(Map<String, Object> tc) {
        Object args = tc.get("arguments");
        if (args == null) return "EMPTY";
        return Integer.toHexString(args.toString().hashCode());
    }

    /**
     * 判断两个步骤的输出是否高度相似（简单实现：前 200 字符相同）
     */
    private boolean isOutputSimilar(String output1, String output2) {
        if (output1 == null && output2 == null) return true;
        if (output1 == null || output2 == null) return false;
        int compareLen = Math.min(200, Math.min(output1.length(), output2.length()));
        if (compareLen == 0) return output1.isEmpty() && output2.isEmpty();
        return output1.substring(0, compareLen).equals(output2.substring(0, compareLen));
    }

    /**
     * 处理步骤执行失败
     * 连续失败次数未达上限时重试，否则标记 Mission 失败
     */
    private void handleStepFailure(String missionId, Exception e) {
        if (missionId == null) return;

        try {
            AgentMission mission = missionService.getEntityById(missionId);
            if (mission.isTerminal()) return;

            // 统计连续失败的步骤数
            int consecutiveFailures = countConsecutiveFailedSteps(missionId);
            int maxRetries = runtimeConfig.getMissionMaxRetries();

            if (consecutiveFailures < maxRetries) {
                log.warn("Mission Step 执行失败 ({}/{}), 将重试: missionId={}, error={}",
                        consecutiveFailures + 1, maxRetries, missionId, e.getMessage());
                // 重试：发布下一步执行事件（currentStep 不变，会创建新的 Step 记录）
                missionService.publishMissionStepEvent(missionId);
            } else {
                missionReducer.applyFatalFail(mission, "连续 " + maxRetries + " 次步骤执行异常: " + e.getMessage());
                evictAgentCache(missionId);
            }
        } catch (Exception ex) {
            log.error("处理步骤失败时发生异常: missionId={}", missionId, ex);
        }
    }

    /**
     * 统计 Mission 最近连续失败的步骤数
     */
    private int countConsecutiveFailedSteps(String missionId) {
        try {
            List<AgentMissionStep> steps = stepMapper.selectList(new LambdaQueryWrapper<AgentMissionStep>()
                    .eq(AgentMissionStep::getMissionId, missionId)
                    .orderByDesc(AgentMissionStep::getStepNumber));
            int count = 0;
            for (AgentMissionStep step : steps) {
                if (MissionStepStatus.FAILED.getCode().equals(step.getStatus())) {
                    count++;
                } else {
                    break;
                }
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 获取之前的步骤（用于构建上下文）
     */
    private List<AgentMissionStep> getPreviousSteps(String missionId, int currentStepNumber) {
        try {
            return stepMapper.selectList(new LambdaQueryWrapper<AgentMissionStep>()
                    .eq(AgentMissionStep::getMissionId, missionId)
                    .lt(AgentMissionStep::getStepNumber, currentStepNumber)
                    .orderByAsc(AgentMissionStep::getStepNumber));
        } catch (Exception e) {
            log.debug("获取之前步骤失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 设置执行上下文（UserContext + AgentContext + SessionContextHolder）
     * MissionExecutor 作为 MQ Consumer 在后台线程执行，绕过了 AgentPreflightService，
     * 通过共享的 AgentContextBuilder 设置完整上下文。
     */
    private void setupExecutionContext(AgentMission mission) {
        String effectiveSessionId = normalizeSessionId(mission.getRuntimeSessionId());

        // 从 Session 读取 agentType / 全部锚点（兼容 Mission 未快照时的降级）
        String agentType = mission.getAgentType();
        String scriptId = null;
        String episodeId = null;
        String storyboardId = null;
        String characterId = null;
        String sceneId = null;
        String propId = null;
        String styleId = null;
        String assetId = null;
        if (effectiveSessionId != null) {
            AgentSessionEntity session = sessionMapper.selectById(effectiveSessionId);
            if (session != null) {
                if (agentType == null) {
                    agentType = session.getAgentType();
                }
                // session.scope_context 内已包含全部锚点；与 AgentPreflightService 注入的字段保持一致，
                // 防止 Mission 后台执行时 episode/storyboard/character 等细粒度上下文丢失。
                scriptId     = session.getScriptId();
                episodeId    = session.getEpisodeId();
                storyboardId = session.getStoryboardId();
                characterId  = session.getCharacterId();
                sceneId      = session.getSceneId();
                propId       = session.getPropId();
                styleId      = session.getStyleId();
                assetId      = session.getAssetId();
            }
        }

        agentContextBuilder.buildAndRegister(AgentContextBuilder.ContextParams.builder()
                .sessionId(effectiveSessionId)
                .agentType(agentType)
                .skillNames(mission.getAgentSkillNames())
                .executionMode(AgentExecutionMode.MISSION.name())
                .userId(mission.getCreatorId())
                .workspaceId(mission.getWorkspaceId())
                .tenantSchema(mission.getTenantSchema())
                .scriptId(scriptId)
                .episodeId(episodeId)
                .storyboardId(storyboardId)
                .characterId(characterId)
                .sceneId(sceneId)
                .propId(propId)
                .styleId(styleId)
                .assetId(assetId)
                .missionId(mission.getId())
                .build());
    }

    /**
     * Skill 版本漂移检测。
     * 比对 Mission 创建时快照的 Skill 版本与当前版本，变更时 WARN + 记录事件。
     */
    private void checkSkillVersionDrift(AgentMission mission) {
        Map<String, Long> snapshotVersions = mission.getSkillVersions();
        if (snapshotVersions == null || snapshotVersions.isEmpty()) {
            return;
        }
        try {
            Map<String, Long> currentVersions = skillRegistry.getSkillVersions(
                    mission.getAgentSkillNames(), mission.getWorkspaceId());
            for (Map.Entry<String, Long> entry : snapshotVersions.entrySet()) {
                Long current = currentVersions.get(entry.getKey());
                if (current != null && !current.equals(entry.getValue())) {
                    log.warn("Mission {} Skill '{}' 版本已变更: snapshot={}, current={}",
                            mission.getId(), entry.getKey(), entry.getValue(), current);
                    missionExecutionRecordService.recordEvent(mission.getId(), "SKILL_VERSION_DRIFT",
                            "Skill '" + entry.getKey() + "' 版本已变更",
                            Map.of("skill", entry.getKey(),
                                    "snapshotVersion", entry.getValue(),
                                    "currentVersion", current));
                }
            }
        } catch (Exception e) {
            log.debug("Skill 版本检测失败: {}", e.getMessage());
        }
    }

    /**
     * 清理 Mission Agent 缓存（P3-1）。
     * 终态路径（Complete/Fail/Cancel）及外部调用方（如 MissionServiceImpl.cancel()）统一使用。
     */
    public void evictAgentCache(String missionId) {
        if (missionId != null && missionAgentCache.remove(missionId) != null) {
            log.debug("已清理 Agent 缓存: missionId={}", missionId);
        }
    }

    /**
     * 截断字符串
     */
    /**
     * 从工具调用结果中提取结构化 artifacts。
     * 收集每个成功工具调用的工具名和结果，供后续步骤通过 prompt 引用。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractArtifacts(List<AgentResponse.ToolCallInfo> toolCalls) {
        Map<String, Object> artifacts = new java.util.LinkedHashMap<>();
        List<Map<String, Object>> results = new java.util.ArrayList<>();

        for (AgentResponse.ToolCallInfo tc : toolCalls) {
            if (!tc.isSuccess() || tc.getResult() == null) {
                continue;
            }
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("tool", tc.getToolName());
            // 保留结构化结果（Map/List），对字符串截断避免过大
            if (tc.getResult() instanceof Map || tc.getResult() instanceof List) {
                entry.put("result", tc.getResult());
            } else {
                String resultStr = tc.getResult().toString();
                entry.put("result", truncate(resultStr, 500));
            }
            results.add(entry);
        }

        if (!results.isEmpty()) {
            artifacts.put("toolResults", results);
        }
        return artifacts.isEmpty() ? null : artifacts;
    }

    /**
     * 将 {@link AgentResponse.ToolCallInfo} 摘要为持久化到 step.toolCalls 的 Map。
     *
     * <p>除 toolName/success 外，额外捕获：
     * <ul>
     *   <li>{@code skipped=true}：表明该调用被 {@link com.actionow.agent.mission.ControlToolGuardCallback}
     *       门控拒绝；同时携带 {@code controlToolFired} 指明本步首个生效的控制工具。</li>
     *   <li>{@code error}：业务层返回 {@code success=false} 时的错误信息（截断至 300 字符）；
     *       或工具自身抛错时的字符串化结果。</li>
     * </ul>
     * 这些字段供 {@link com.actionow.agent.mission.MissionPromptBuilder} 在下一步 Prompt 中
     * 显式回显失败 / 被拒原因，让 LLM 不再重复同一错误决策。
     */
    private Map<String, Object> summarizeToolCall(AgentResponse.ToolCallInfo tc) {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("toolName", tc.getToolName());
        entry.put("success", tc.isSuccess());

        Object result = tc.getResult();
        if (result instanceof Map<?, ?> map) {
            if (Boolean.TRUE.equals(map.get("skipped"))) {
                entry.put("skipped", true);
                Object firedTool = map.get("controlToolFired");
                if (firedTool != null) {
                    entry.put("controlToolFired", firedTool.toString());
                }
            }
            Object successFlag = map.get("success");
            Object errObj = map.get("error");
            if (Boolean.FALSE.equals(successFlag) && errObj != null) {
                entry.put("error", truncate(errObj.toString(), 300));
            }
        } else if (!tc.isSuccess() && result != null) {
            entry.put("error", truncate(result.toString(), 300));
        }
        return entry;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return null;
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    private String normalizeSessionId(String sessionId) {
        if (sessionId == null) {
            return null;
        }

        String normalized = sessionId.trim();
        if (normalized.isEmpty()) {
            return null;
        }

        if ("none".equalsIgnoreCase(normalized)
                || "null".equalsIgnoreCase(normalized)
                || "undefined".equalsIgnoreCase(normalized)) {
            return null;
        }

        try {
            UUID.fromString(normalized);
            return normalized;
        } catch (IllegalArgumentException e) {
            log.warn("忽略无效 Mission sessionId: {}", normalized);
            return null;
        }
    }

    private String agentTypeOrDefault(AgentMission mission, String runtimeSessionId) {
        if (runtimeSessionId != null) {
            AgentSessionEntity session = sessionMapper.selectById(runtimeSessionId);
            if (session != null && session.getAgentType() != null && !session.getAgentType().isBlank()) {
                return session.getAgentType();
            }
        }
        return "COORDINATOR";
    }

    private int countEstimatedInputTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    /**
     * 将 Mission 步骤的用户输入、工具调用事件和助手回复持久化到会话消息表。
     * 使后续步骤通过 ContextWindowManager 能加载完整的工具调用历史。
     */
    private void persistStepMessages(String sessionId, String userInput, ExecutionTranscript transcript) {
        try {
            // 保存步骤输入（作为 user 消息）
            saaSessionService.saveMessage(sessionId, "user", userInput, null);

            // 保存工具调用和结果
            if (transcript.getRawEvents() != null) {
                for (var event : transcript.getRawEvents()) {
                    if (com.actionow.agent.constant.AgentConstants.EVENT_TOOL_CALL.equals(event.getType())) {
                        saaSessionService.saveToolCallMessage(
                                sessionId, event.getToolCallId(),
                                event.getToolName(), event.getToolArguments());
                    } else if (com.actionow.agent.constant.AgentConstants.EVENT_TOOL_RESULT.equals(event.getType())) {
                        Map<String, Object> parsed = streamProcessor.parseToolResultAsMap(event.getContent());
                        boolean success = !(parsed.get("success") instanceof Boolean value) || value;
                        saaSessionService.saveToolResultMessage(
                                sessionId, event.getToolCallId(),
                                event.getToolName(), success, event.getContent());
                    }
                }
            }

            // 保存助手回复
            if (transcript.getFinalText() != null && !transcript.getFinalText().isBlank()) {
                saaSessionService.saveAssistantMessage(sessionId, transcript.getFinalText(), null);
            }
        } catch (Exception e) {
            log.warn("Mission 步骤消息持久化失败（不影响执行）: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    private long calculateStepCost(ExecutionTranscript transcript) {
        if (transcript == null || transcript.getUsage() == null) {
            return 0L;
        }
        return billingCalculator.calculateTokenCost(
                transcript.getModelName(),
                (int) transcript.getUsage().getInputTokens(),
                (int) transcript.getUsage().getOutputTokens(),
                0
        );
    }
}
