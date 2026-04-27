package com.actionow.agent.mission.tools;

import com.actionow.agent.billing.service.AgentBillingCalculator;
import com.actionow.agent.core.scope.AgentContext;
import com.actionow.agent.core.scope.AgentContextHolder;
import com.actionow.agent.dto.request.CreateMissionRequest;
import com.actionow.agent.dto.response.MissionResponse;
import com.actionow.agent.entity.AgentMission;
import com.actionow.agent.feign.TaskFeignClient;
import com.actionow.agent.feign.WalletFeignClient;
import com.actionow.agent.saa.session.SaaSessionService;
import com.actionow.agent.service.MissionExecutionRecordService;
import com.actionow.agent.service.MissionService;
import com.actionow.agent.tool.annotation.AgentToolOutput;
import com.actionow.agent.tool.annotation.AgentToolParamSpec;
import com.actionow.agent.tool.annotation.AgentToolSpec;
import com.actionow.agent.tool.annotation.ChatDirectTool;
import com.actionow.agent.tool.annotation.MissionDirectTool;
import com.actionow.agent.tool.annotation.ToolActionType;
import com.actionow.agent.tool.response.ToolResponse;
import com.actionow.common.core.context.UserContext;
import com.actionow.common.core.context.UserContextHolder;
import com.actionow.common.core.result.Result;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Mission 工具类
 * 提供 Agent 在 SSE 对话和 Mission 后台执行中可用的工具
 *
 * @author Actionow
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MissionTools {

    private final MissionService missionService;
    private final TaskFeignClient taskFeignClient;
    private final WalletFeignClient walletFeignClient;
    private final AgentBillingCalculator billingCalculator;
    private final SaaSessionService sessionService;
    private final MissionExecutionRecordService missionExecutionRecordService;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ==================== SSE 对话中使用的工具 ====================

    @ChatDirectTool
    @Tool(name = "create_mission",
            description = "创建一个后台自主执行的 Mission。当用户请求复杂的、耗时的多步任务时使用此工具（如：批量生成图片、完整视频制作等）。" +
                    "Mission 创建后会在后台由 Agent 自主执行，不受 SSE 超时限制。" +
                    "参数: title(标题), goal(用户原始请求), planJson(可选，初始计划JSON)。" +
                    "当前 Agent 会话上下文会自动派生为 Mission 的 runtimeSession，无需手动传 runtimeSessionId。")
    @AgentToolSpec(
            displayName = "创建 Mission",
            summary = "创建一个后台自主执行的长任务。",
            purpose = "用于把复杂、耗时、多步骤的请求转为后台 Mission 执行。",
            actionType = ToolActionType.CONTROL,
            tags = {"mission", "creation"},
            usageNotes = {"Mission 创建后会在后台执行，不受 SSE 超时影响", "当前对话上下文会自动派生为 runtimeSession，无需传 runtimeSessionId"},
            errorCases = {"title 或 goal 为空时会返回校验错误", "缺少用户上下文时会返回错误"},
            exampleInput = "{\"title\":\"批量生成分镜图\",\"goal\":\"为第一集所有分镜生成图片\"}",
            exampleOutput = "{\"success\":true,\"missionId\":\"mission_xxx\",\"status\":\"CREATED\"}"
    )
    @AgentToolOutput(
            description = "返回 Mission ID、标题与初始状态。",
            example = "{\"success\":true,\"missionId\":\"mission_xxx\",\"title\":\"批量生成分镜图\",\"status\":\"CREATED\"}"
    )
    public Map<String, Object> createMission(
            @AgentToolParamSpec(example = "批量生成分镜图")
            @ToolParam(description = "Mission 标题，简洁描述任务目标") String title,
            @AgentToolParamSpec(example = "为第一集所有分镜生成图片")
            @ToolParam(description = "用户的原始请求全文") String goal,
            @ToolParam(description = "初始计划JSON对象(可选)，例如: {\"description\":\"初步计划\",\"steps\":[\"步骤1\",\"步骤2\"]}") String planJson) {

        if (title == null || title.isBlank()) {
            return ToolResponse.validationError("title").toMap();
        }
        if (goal == null || goal.isBlank()) {
            return ToolResponse.validationError("goal").toMap();
        }

        try {
            UserContext userContext = UserContextHolder.getContext();
            if (userContext == null) {
                return ToolResponse.error("缺少用户上下文").toMap();
            }
            AgentContext agentContext = AgentContextHolder.getContext();
            String runtimeSessionId = sessionService.createInternalMissionSession(
                    agentContext,
                    userContext.getUserId(),
                    userContext.getWorkspaceId()
            ).getId();

            CreateMissionRequest request = new CreateMissionRequest();
            request.setTitle(title);
            request.setGoal(goal);
            request.setRuntimeSessionId(runtimeSessionId);

            // 快照当前 Agent 上下文，Mission 执行时继承
            request.setTenantSchema(userContext.getTenantSchema());
            request.setAgentType(agentContext.getAgentType());
            request.setSkillNames(agentContext.getSkillNames());

            if (planJson != null && !planJson.isBlank()) {
                Map<String, Object> plan = OBJECT_MAPPER.readValue(planJson, new TypeReference<>() {});
                request.setPlan(plan);
            }

            MissionResponse response = missionService.create(request, userContext.getWorkspaceId(), userContext.getUserId());

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("missionId", response.getId());
            result.put("title", response.getTitle());
            result.put("status", response.getStatus());
            result.put("message", "Mission 已创建，将在后台自主执行。用户可通过 Mission ID 查询进度。");
            return result;
        } catch (Exception e) {
            log.error("创建 Mission 失败", e);
            return ToolResponse.error("创建 Mission 失败: " + e.getMessage()).toMap();
        }
    }

    @ChatDirectTool
    @MissionDirectTool
    @Tool(name = "get_mission_status",
            description = "查询 Mission 当前状态和进度。当用户询问任务进度时使用。参数: missionId(Mission ID)")
    @AgentToolSpec(
            displayName = "查询 Mission 状态",
            summary = "查询 Mission 当前进度、步骤和活动状态。",
            purpose = "用于向用户汇报长任务执行进展。",
            actionType = ToolActionType.READ,
            tags = {"mission", "status"},
            usageNotes = {"missionId 必填"},
            errorCases = {"missionId 为空时会返回校验错误"},
            exampleInput = "{\"missionId\":\"mission_xxx\"}",
            exampleOutput = "{\"success\":true,\"missionId\":\"mission_xxx\",\"status\":\"EXECUTING\",\"progress\":45}"
    )
    @AgentToolOutput(
            description = "返回 Mission 当前状态、进度和步骤信息。",
            example = "{\"success\":true,\"missionId\":\"mission_xxx\",\"status\":\"EXECUTING\",\"progress\":45,\"steps\":[]}"
    )
    public Map<String, Object> getMissionStatus(
            @AgentToolParamSpec(example = "mission_xxx")
            @ToolParam(description = "Mission ID") String missionId) {

        if (missionId == null || missionId.isBlank()) {
            return ToolResponse.validationError("missionId").toMap();
        }

        try {
            var progress = missionService.getProgress(missionId);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("missionId", progress.getId());
            result.put("title", progress.getTitle());
            result.put("status", progress.getStatus());
            result.put("progress", progress.getProgress());
            result.put("currentStep", progress.getCurrentStep());
            result.put("totalSteps", progress.getTotalSteps());
            result.put("currentActivity", progress.getCurrentActivity());
            result.put("pendingTasks", progress.getPendingTasks());
            result.put("steps", progress.getSteps());
            return result;
        } catch (Exception e) {
            log.error("查询 Mission 状态失败: missionId={}", missionId, e);
            return ToolResponse.error("查询 Mission 状态失败: " + e.getMessage()).toMap();
        }
    }

    // ==================== Mission 后台执行中使用的工具 ====================

    @MissionDirectTool
    @Tool(name = "delegate_scope_generation",
            description = "委派作用域级别批量 AI 生成任务。自动将 Episode/Script/Character/Scene/Prop 展开为所有子实体并批量生成。" +
                    "例如：指定 episodeId 自动展开为该集所有分镜并批量生图；指定 scriptId 自动展开为所有分镜。" +
                    "支持条件跳过（已有素材的实体自动跳过）。" +
                    "missionId 已自动绑定到当前执行上下文，无需手动传递。" +
                    "参数: scopeEntityType(EPISODE/SCRIPT/CHARACTER/SCENE/PROP), scopeEntityId, generationType(IMAGE/VIDEO), providerId(可选), skipExisting(是否跳过已有素材)")
    @AgentToolSpec(
            displayName = "委派作用域生成",
            summary = "按作用域展开实体并批量委派 AI 生成任务。",
            purpose = "用于对整集、整剧本或某类实体做自动展开后的批量生成。",
            actionType = ToolActionType.CONTROL,
            tags = {"mission", "generation", "scope"},
            usageNotes = {"scopeEntityType 决定展开策略", "skipExisting=true 时可自动跳过已有素材实体", "missionId 自动从执行上下文获取"},
            errorCases = {"作用域参数为空时会返回校验错误", "BatchJob 创建失败时会返回错误", "非 Mission 执行上下文时返回错误"},
            exampleInput = "{\"scopeEntityType\":\"EPISODE\",\"scopeEntityId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"generationType\":\"IMAGE\"}",
            exampleOutput = "{\"success\":true,\"batchJobId\":\"batch_xxx\",\"totalItems\":12}"
    )
    @AgentToolOutput(
            description = "返回生成作业的 batchJobId 与展开后的实体数量。",
            example = "{\"success\":true,\"batchJobId\":\"batch_xxx\",\"totalItems\":12,\"message\":\"成功创建 EPISODE 级别批量作业\"}"
    )
    public Map<String, Object> delegateScopeGeneration(
            @AgentToolParamSpec(enumValues = {"EPISODE", "SCRIPT", "CHARACTER", "SCENE", "PROP"})
            @ToolParam(description = "作用域类型: EPISODE(展开为分镜), SCRIPT(展开为所有集的分镜), CHARACTER(展开为角色列表), SCENE(展开为场景列表), PROP(展开为道具列表)")
            String scopeEntityType,
            @ToolParam(description = "作用域实体 ID。EPISODE 类型传 episodeId，SCRIPT 类型传 scriptId")
            String scopeEntityId,
            @AgentToolParamSpec(enumValues = {"IMAGE", "VIDEO", "AUDIO", "TTS"})
            @ToolParam(description = "生成类型: IMAGE/VIDEO/AUDIO/TTS")
            String generationType,
            @ToolParam(description = "AI Provider 标识（可选，不传则自动选择优先级最高的 Provider）。" +
                    "支持: UUID（如 00000000-0000-0000-0003-000000000001）、pluginId（如 seedream-4-5）、模型名称（如 Seedream 4.5）。" +
                    "可通过 list_ai_providers 查看可用列表", required = false)
            String providerId,
            @ToolParam(description = "剧本 ID（CHARACTER/SCENE/PROP 类型必传，SCRIPT 类型可选）")
            String scriptId,
            @ToolParam(description = "是否跳过已有素材的实体（true/false），默认 false")
            String skipExisting) {

        String missionId = requireMissionId();
        if (missionId == null) {
            return ToolResponse.error("此工具仅在 Mission 执行上下文中可用").toMap();
        }
        if (scopeEntityType == null || scopeEntityType.isBlank()) {
            return ToolResponse.validationError("scopeEntityType").toMap();
        }
        if (scopeEntityId == null || scopeEntityId.isBlank()) {
            return ToolResponse.validationError("scopeEntityId").toMap();
        }
        if (generationType == null || generationType.isBlank()) {
            return ToolResponse.validationError("generationType").toMap();
        }

        try {
            AgentMission mission = missionService.getEntityById(missionId);
            UserContext userContext = UserContextHolder.getContext();
            String workspaceId = userContext != null ? userContext.getWorkspaceId() : mission.getWorkspaceId();
            String userId = userContext != null ? userContext.getUserId() : mission.getCreatorId();

            // 构建 SCOPE 类型 BatchJob 请求
            Map<String, Object> batchRequest = new HashMap<>();
            batchRequest.put("name", "Mission scope: " + scopeEntityType + " " + scopeEntityId);
            batchRequest.put("batchType", "SCOPE");
            batchRequest.put("missionId", missionId);
            batchRequest.put("source", "AGENT");
            batchRequest.put("errorStrategy", "CONTINUE");
            batchRequest.put("scopeEntityType", scopeEntityType);
            batchRequest.put("scopeEntityId", scopeEntityId);
            batchRequest.put("generationType", generationType);
            if (providerId != null && !providerId.isBlank()) {
                batchRequest.put("providerId", providerId);
            }

            if (scriptId != null && !scriptId.isBlank()) {
                batchRequest.put("scriptId", scriptId);
            }

            if ("true".equalsIgnoreCase(skipExisting)) {
                batchRequest.put("skipCondition", "ASSET_EXISTS");
            }

            // 幂等键：同一步骤内 LLM 重复调用同 (scope,entity,gen) → 命中已有 BatchJob，避免 N 倍重复执行
            batchRequest.put("idempotencyKey", computeIdempotencyKey(
                    "SCOPE",
                    scopeEntityType,
                    scopeEntityId,
                    generationType,
                    providerId,
                    scriptId,
                    skipExisting));

            Result<Map<String, Object>> batchResult = taskFeignClient.createBatchJob(workspaceId, userId, batchRequest);

            if (batchResult.isSuccess() && batchResult.getData() != null) {
                String batchJobId = (String) batchResult.getData().get("id");
                int totalItems = batchResult.getData().get("totalItems") != null
                        ? ((Number) batchResult.getData().get("totalItems")).intValue() : 0;

                // 更新 Mission（追加到 plan.batchJobIds 列表，保留 plan.batchJobId 为最近值）
                Map<String, Object> scopeExtras = new HashMap<>();
                scopeExtras.put("scopeEntityType", scopeEntityType);
                scopeExtras.put("scopeEntityId", scopeEntityId);
                recordBatchJobInPlan(mission, batchJobId, scopeExtras);
                missionService.save(mission);
                missionExecutionRecordService.registerTask(
                        missionId,
                        currentMissionStepId(),
                        "BATCH_JOB_SCOPE",
                        batchJobId,
                        batchJobId,
                        scopeEntityType,
                        scopeEntityId,
                        batchRequest
                );
                missionExecutionRecordService.recordEvent(
                        missionId,
                        "TASK_REGISTERED",
                        "已登记 Scope 批量作业",
                        Map.of("batchJobId", batchJobId, "scopeEntityType", scopeEntityType, "scopeEntityId", scopeEntityId)
                );

                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("batchJobId", batchJobId);
                result.put("totalItems", totalItems);
                result.put("scopeEntityType", scopeEntityType);
                result.put("message", "成功创建 " + scopeEntityType + " 级别批量作业（" + totalItems + " 个实体），Mission 将进入等待状态");
                return result;
            } else {
                String errorMsg = batchResult != null && batchResult.getMessage() != null
                        ? batchResult.getMessage() : "创建 Scope 批量作业失败";
                return ToolResponse.error(errorMsg).toMap();
            }
        } catch (Exception e) {
            log.error("委派 Scope 生成失败: missionId={}, scope={}:{}", missionId, scopeEntityType, scopeEntityId, e);
            return ToolResponse.error("委派 Scope 生成失败: " + e.getMessage()).toMap();
        }
    }

    @MissionDirectTool
    @Tool(name = "delegate_batch_generation",
            description = "委派批量 AI 生成任务到 Task 模块。调用后 Mission 将进入等待状态直到所有任务完成。" +
                    "使用 BatchJob 统一提交，支持并发控制和进度追踪。" +
                    "missionId 已自动绑定到当前执行上下文，无需手动传递。" +
                    "参数: requestsJson(生成请求JSON数组)")
    @AgentToolSpec(
            displayName = "委派批量生成",
            summary = "将一批实体生成请求统一提交为 BatchJob。",
            purpose = "用于多个实体的大批量并行生成任务。",
            actionType = ToolActionType.CONTROL,
            tags = {"mission", "generation", "batch"},
            usageNotes = {"内部会先做积分预检查", "提交后 Mission 进入等待状态", "missionId 自动从执行上下文获取"},
            errorCases = {"requestsJson 为空时会返回错误", "积分不足时会返回 INSUFFICIENT_CREDITS", "非 Mission 执行上下文时返回错误"},
            exampleInput = "{\"requestsJson\":\"[{\\\"entityType\\\":\\\"CHARACTER\\\",\\\"entityId\\\":\\\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\\\",\\\"generationType\\\":\\\"IMAGE\\\",\\\"params\\\":{\\\"prompt\\\":\\\"...\\\"}}]\"}",
            exampleOutput = "{\"success\":true,\"batchJobId\":\"batch_xxx\",\"totalItems\":10}"
    )
    @AgentToolOutput(
            description = "返回批量作业 ID 与子项总数。",
            example = "{\"success\":true,\"batchJobId\":\"batch_xxx\",\"totalItems\":10,\"message\":\"成功创建批量作业\"}"
    )
    public Map<String, Object> delegateBatchGeneration(
            @ToolParam(description = "生成请求JSON数组，每个元素包含: entityType(CHARACTER/SCENE/PROP/STORYBOARD), entityId, generationType(IMAGE/VIDEO), params(含prompt等)。" +
                    "可选字段 providerId（支持 UUID/pluginId/模型名称，省略则自动选择）。" +
                    "例如: [{\"entityType\":\"CHARACTER\",\"entityId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxx1\",\"generationType\":\"IMAGE\",\"params\":{\"prompt\":\"...\"}}]")
            String requestsJson) {

        String missionId = requireMissionId();
        if (missionId == null) {
            return ToolResponse.error("此工具仅在 Mission 执行上下文中可用").toMap();
        }
        if (requestsJson == null || requestsJson.isBlank()) {
            return ToolResponse.validationError("requestsJson").toMap();
        }

        try {
            AgentMission mission = missionService.getEntityById(missionId);
            UserContext userContext = UserContextHolder.getContext();
            String workspaceId = userContext != null ? userContext.getWorkspaceId() : mission.getWorkspaceId();
            String userId = userContext != null ? userContext.getUserId() : mission.getCreatorId();

            List<Map<String, Object>> requests = OBJECT_MAPPER.readValue(requestsJson,
                    new TypeReference<List<Map<String, Object>>>() {});

            if (requests.isEmpty()) {
                return ToolResponse.error("生成请求列表不能为空").toMap();
            }

            // 积分预检查
            try {
                long totalEstimate = 0;
                for (Map<String, Object> req : requests) {
                    String generationType = (String) req.get("generationType");
                    if ("IMAGE".equalsIgnoreCase(generationType)) {
                        totalEstimate += billingCalculator.getDefaultFreezeAmount();
                    } else if ("VIDEO".equalsIgnoreCase(generationType)) {
                        totalEstimate += billingCalculator.getDefaultFreezeAmount() * 3;
                    } else {
                        totalEstimate += billingCalculator.estimateFreezeAmount(
                                (String) req.get("providerId"), 2000, 1000);
                    }
                }

                Result<Boolean> quotaResult = walletFeignClient.checkQuota(workspaceId, userId, totalEstimate);
                if (quotaResult.isSuccess() && Boolean.FALSE.equals(quotaResult.getData())) {
                    Map<String, Object> errorResult = new HashMap<>();
                    errorResult.put("success", false);
                    errorResult.put("errorCode", "INSUFFICIENT_CREDITS");
                    errorResult.put("estimatedCost", totalEstimate);
                    errorResult.put("requestCount", requests.size());
                    errorResult.put("message", "积分余额不足，无法提交 " + requests.size() + " 个生成任务（预估需要 " + totalEstimate + " 积分）。建议：减少任务数量或联系管理员充值。");
                    return errorResult;
                }
            } catch (Exception e) {
                log.warn("积分预检查失败（不阻塞提交）: missionId={}, error={}", missionId, e.getMessage());
            }

            // 构建 BatchJob 请求
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> req : requests) {
                Map<String, Object> item = new HashMap<>();
                item.put("entityType", req.get("entityType"));
                item.put("entityId", req.get("entityId"));
                item.put("entityName", req.get("entityName"));
                item.put("generationType", req.get("generationType"));
                item.put("providerId", req.get("providerId"));
                item.put("params", req.get("params"));
                items.add(item);
            }

            Map<String, Object> batchRequest = new HashMap<>();
            batchRequest.put("name", "Mission batch: " + mission.getTitle());
            batchRequest.put("batchType", "SIMPLE");
            batchRequest.put("missionId", missionId);
            batchRequest.put("source", "AGENT");
            batchRequest.put("errorStrategy", "CONTINUE");
            batchRequest.put("items", items);

            // 幂等键：基于 items 内容签名（entityType+entityId+generationType+providerId 组合排序后哈希）
            // LLM 单步内重复调用相同请求 → 同 key → 复用已有 BatchJob
            batchRequest.put("idempotencyKey", computeIdempotencyKey(
                    "SIMPLE",
                    itemsSignature(items)));

            // 一次调用创建 BatchJob
            Result<Map<String, Object>> batchResult = taskFeignClient.createBatchJob(workspaceId, userId, batchRequest);

            if (batchResult.isSuccess() && batchResult.getData() != null) {
                String batchJobId = (String) batchResult.getData().get("id");
                int totalItems = batchResult.getData().get("totalItems") != null
                        ? ((Number) batchResult.getData().get("totalItems")).intValue() : requests.size();

                // 更新 Mission：追加到 plan.batchJobIds 列表，保留 plan.batchJobId 兼容旧读路径
                recordBatchJobInPlan(mission, batchJobId, null);
                missionService.save(mission);
                missionExecutionRecordService.registerTask(
                        missionId,
                        currentMissionStepId(),
                        "BATCH_JOB_SIMPLE",
                        batchJobId,
                        batchJobId,
                        null,
                        null,
                        batchRequest
                );
                missionExecutionRecordService.recordEvent(
                        missionId,
                        "TASK_REGISTERED",
                        "已登记批量生成作业",
                        Map.of("batchJobId", batchJobId, "totalItems", totalItems)
                );

                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("batchJobId", batchJobId);
                result.put("totalItems", totalItems);
                result.put("message", "成功创建批量作业（" + totalItems + " 个子项），Mission 将进入等待状态");
                return result;
            } else {
                String errorMsg = batchResult != null && batchResult.getMessage() != null
                        ? batchResult.getMessage() : "创建批量作业失败";
                return ToolResponse.error(errorMsg).toMap();
            }
        } catch (Exception e) {
            log.error("委派批量生成失败: missionId={}", missionId, e);
            return ToolResponse.error("委派批量生成失败: " + e.getMessage()).toMap();
        }
    }

    @MissionDirectTool
    @Tool(name = "delegate_pipeline_generation",
            description = "委派 Pipeline（多步链式）AI 生成任务到 Task 模块。当需要串联多步生成时使用（如：先生提示词再生图、文生图再图生视频、分镜全流程等）。" +
                    "调用后 Mission 将进入等待状态直到 Pipeline 全部步骤完成。" +
                    "支持预定义模板和自定义步骤链。" +
                    "missionId 已自动绑定到当前执行上下文，无需手动传递。" +
                    "参数: pipelineTemplate(模板代码), requestsJson(实体列表JSON), customStepsJson(可选，自定义步骤)")
    @AgentToolSpec(
            displayName = "委派 Pipeline 生成",
            summary = "将多步链式生成流程委派给 Task 模块执行。",
            purpose = "用于需要模板化或自定义步骤链的复杂生成任务。",
            actionType = ToolActionType.CONTROL,
            tags = {"mission", "generation", "pipeline"},
            usageNotes = {"需要提供 pipelineTemplate 或 customStepsJson 二选一", "missionId 自动从执行上下文获取"},
            errorCases = {"requestsJson 为空时会返回错误", "pipelineTemplate 与 customStepsJson 同时缺失时会返回错误", "非 Mission 执行上下文时返回错误"},
            exampleInput = "{\"pipelineTemplate\":\"TEXT_TO_IMAGE_TO_VIDEO\",\"requestsJson\":\"[...]\"}",
            exampleOutput = "{\"success\":true,\"batchJobId\":\"batch_xxx\",\"pipelineTemplate\":\"TEXT_TO_IMAGE_TO_VIDEO\"}"
    )
    @AgentToolOutput(
            description = "返回 Pipeline 批量作业 ID、模板和子项数量。",
            example = "{\"success\":true,\"batchJobId\":\"batch_xxx\",\"totalItems\":8,\"pipelineTemplate\":\"FULL_STORYBOARD\"}"
    )
    public Map<String, Object> delegatePipelineGeneration(
            @ToolParam(description = "Pipeline 模板代码（与 customStepsJson 二选一）。常用模板: " +
                    "TEXT_TO_PROMPT_TO_IMAGE(先润色prompt再生图), TEXT_TO_PROMPT_TO_VIDEO(先润色prompt再生视频), " +
                    "TEXT_TO_IMAGE_TO_VIDEO(文生图再图生视频), FULL_STORYBOARD(分镜全流程:文→图→视频)。" +
                    "模板由 Task 模块管理，如需自定义多步流程，使用 customStepsJson 替代。传 null 则使用 customStepsJson。") String pipelineTemplate,
            @ToolParam(description = "实体列表JSON数组，每个元素包含: entityType, entityId, entityName, params。" +
                    "例如: [{\"entityType\":\"STORYBOARD\",\"entityId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxx1\",\"entityName\":\"开场\",\"params\":{\"prompt\":\"...\"}}]")
            String requestsJson,
            @ToolParam(description = "自定义 Pipeline 步骤JSON数组(可选，当不使用预定义模板时)。" +
                    "每个步骤: {\"name\":\"步骤名\",\"stepType\":\"GENERATE_IMAGE\",\"generationType\":\"IMAGE\"," +
                    "\"providerId\":\"Seedream 4.5\",\"paramsTemplate\":{\"prompt\":\"{{steps[1].output.text}}\"},\"dependsOn\":[1],\"fanOutCount\":1}。" +
                    "providerId 可选，支持 UUID/pluginId/模型名称，省略则按 generationType 自动选择")
            String customStepsJson) {

        String missionId = requireMissionId();
        if (missionId == null) {
            return ToolResponse.error("此工具仅在 Mission 执行上下文中可用").toMap();
        }
        if (requestsJson == null || requestsJson.isBlank()) {
            return ToolResponse.validationError("requestsJson").toMap();
        }
        if ((pipelineTemplate == null || pipelineTemplate.isBlank())
                && (customStepsJson == null || customStepsJson.isBlank())) {
            return ToolResponse.error("必须提供 pipelineTemplate 或 customStepsJson").toMap();
        }

        try {
            AgentMission mission = missionService.getEntityById(missionId);
            UserContext userContext = UserContextHolder.getContext();
            String workspaceId = userContext != null ? userContext.getWorkspaceId() : mission.getWorkspaceId();
            String userId = userContext != null ? userContext.getUserId() : mission.getCreatorId();

            List<Map<String, Object>> requests = OBJECT_MAPPER.readValue(requestsJson,
                    new TypeReference<List<Map<String, Object>>>() {});

            if (requests.isEmpty()) {
                return ToolResponse.error("实体列表不能为空").toMap();
            }

            // 构建 BatchJob 请求
            Map<String, Object> batchRequest = new HashMap<>();
            batchRequest.put("name", "Mission pipeline: " + mission.getTitle());
            batchRequest.put("batchType", "PIPELINE");
            batchRequest.put("missionId", missionId);
            batchRequest.put("source", "AGENT");
            batchRequest.put("errorStrategy", "CONTINUE");

            if (pipelineTemplate != null && !pipelineTemplate.isBlank()) {
                batchRequest.put("pipelineTemplate", pipelineTemplate);
            }

            if (customStepsJson != null && !customStepsJson.isBlank()) {
                List<Map<String, Object>> customSteps = OBJECT_MAPPER.readValue(customStepsJson,
                        new TypeReference<List<Map<String, Object>>>() {});
                batchRequest.put("pipelineSteps", customSteps);
            }

            // items 作为 Pipeline 的初始输入
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> req : requests) {
                Map<String, Object> item = new HashMap<>();
                item.put("entityType", req.get("entityType"));
                item.put("entityId", req.get("entityId"));
                item.put("entityName", req.get("entityName"));
                item.put("params", req.get("params"));
                items.add(item);
            }
            batchRequest.put("items", items);

            // 幂等键：pipelineTemplate（或 customSteps）+ items 内容签名
            String stepsSig = customStepsJson != null ? sha256Hex(customStepsJson) : "";
            batchRequest.put("idempotencyKey", computeIdempotencyKey(
                    "PIPELINE",
                    pipelineTemplate,
                    stepsSig,
                    itemsSignature(items)));

            Result<Map<String, Object>> batchResult = taskFeignClient.createBatchJob(workspaceId, userId, batchRequest);

            if (batchResult.isSuccess() && batchResult.getData() != null) {
                String batchJobId = (String) batchResult.getData().get("id");
                int totalItems = batchResult.getData().get("totalItems") != null
                        ? ((Number) batchResult.getData().get("totalItems")).intValue() : requests.size();

                // 更新 Mission（追加到 plan.batchJobIds 列表，保留 plan.batchJobId 兼容旧读路径）
                Map<String, Object> pipelineExtras = new HashMap<>();
                pipelineExtras.put("pipelineTemplate", pipelineTemplate);
                recordBatchJobInPlan(mission, batchJobId, pipelineExtras);
                missionService.save(mission);
                missionExecutionRecordService.registerTask(
                        missionId,
                        currentMissionStepId(),
                        "BATCH_JOB_PIPELINE",
                        batchJobId,
                        batchJobId,
                        null,
                        null,
                        batchRequest
                );
                missionExecutionRecordService.recordEvent(
                        missionId,
                        "TASK_REGISTERED",
                        "已登记 Pipeline 作业",
                        buildPipelineEventPayload(batchJobId, pipelineTemplate, totalItems)
                );

                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("batchJobId", batchJobId);
                result.put("totalItems", totalItems);
                result.put("pipelineTemplate", pipelineTemplate);
                result.put("message", "成功创建 Pipeline 批量作业（" + totalItems + " 个初始子项），Mission 将进入等待状态");
                return result;
            } else {
                String errorMsg = batchResult != null && batchResult.getMessage() != null
                        ? batchResult.getMessage() : "创建 Pipeline 批量作业失败";
                return ToolResponse.error(errorMsg).toMap();
            }
        } catch (Exception e) {
            log.error("委派 Pipeline 生成失败: missionId={}", missionId, e);
            return ToolResponse.error("委派 Pipeline 生成失败: " + e.getMessage()).toMap();
        }
    }

    @MissionDirectTool
    @Tool(name = "update_mission_plan",
            description = "更新 Mission 的执行计划。当需要调整策略、跳过步骤或添加新步骤时使用。" +
                    "missionId 已自动绑定到当前执行上下文，无需手动传递。" +
                    "参数: planJson(新计划JSON)")
    @AgentToolSpec(
            displayName = "更新 Mission 计划",
            summary = "更新 Mission 的执行计划。",
            purpose = "用于中途调整任务策略、步骤或说明。",
            actionType = ToolActionType.CONTROL,
            tags = {"mission", "plan"},
            usageNotes = {"planJson 应传完整计划对象", "missionId 自动从执行上下文获取"},
            errorCases = {"planJson 为空时会返回校验错误", "非 Mission 执行上下文时返回错误"},
            exampleInput = "{\"planJson\":\"{\\\"steps\\\":[\\\"步骤1\\\",\\\"步骤2\\\"]}\"}",
            exampleOutput = "{\"success\":true,\"message\":\"Mission 计划已更新\"}"
    )
    @AgentToolOutput(
            description = "返回更新计划后的确认消息。",
            example = "{\"success\":true,\"message\":\"Mission 计划已更新\"}"
    )
    public Map<String, Object> updateMissionPlan(
            @ToolParam(description = "更新后的计划JSON对象，例如: {\"description\":\"调整后的计划\",\"steps\":[\"步骤1\",\"步骤2\"],\"notes\":\"跳过了scene_03\"}") String planJson) {

        String missionId = requireMissionId();
        if (missionId == null) {
            return ToolResponse.error("此工具仅在 Mission 执行上下文中可用").toMap();
        }
        if (planJson == null || planJson.isBlank()) {
            return ToolResponse.validationError("planJson").toMap();
        }

        try {
            Map<String, Object> plan = OBJECT_MAPPER.readValue(planJson, new TypeReference<>() {});
            missionService.updatePlan(missionId, plan);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Mission 计划已更新");
            return result;
        } catch (Exception e) {
            log.error("更新 Mission 计划失败: missionId={}", missionId, e);
            return ToolResponse.error("更新 Mission 计划失败: " + e.getMessage()).toMap();
        }
    }

    @MissionDirectTool
    @Tool(name = "complete_mission",
            description = "标记 Mission 已完成。当所有工作都已完成时调用此工具。" +
                    "missionId 已自动绑定到当前执行上下文，无需手动传递。" +
                    "注意：此工具仅发出完成信号，实际状态变更由执行引擎统一处理。" +
                    "参数: summary(完成摘要)")
    @AgentToolSpec(
            displayName = "完成 Mission",
            summary = "将 Mission 标记为已完成。",
            purpose = "用于在长任务完成后显式结束 Mission。",
            actionType = ToolActionType.CONTROL,
            tags = {"mission", "complete"},
            usageNotes = {"建议提供 summary 说明完成结果", "missionId 自动从执行上下文获取", "工具仅返回信号，状态由执行引擎统一变更"},
            errorCases = {"非 Mission 执行上下文时返回错误"},
            exampleInput = "{\"summary\":\"12 个分镜图已生成完成\"}",
            exampleOutput = "{\"success\":true,\"action\":\"COMPLETE\",\"summary\":\"12 个分镜图已生成完成\"}"
    )
    @AgentToolOutput(
            description = "返回完成信号，由执行引擎统一处理状态变更。",
            example = "{\"success\":true,\"action\":\"COMPLETE\",\"summary\":\"12 个分镜图已生成完成\"}"
    )
    public Map<String, Object> completeMission(
            @ToolParam(description = "完成摘要，描述完成了什么") String summary) {

        String missionId = requireMissionId();
        if (missionId == null) {
            return ToolResponse.error("此工具仅在 Mission 执行上下文中可用").toMap();
        }

        // 不再直接更新 Mission 状态，只返回控制信号
        // 实际状态变更由 MissionExecutor.processAgentDecision() 统一负责
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("action", "COMPLETE");
        result.put("summary", summary != null ? summary : "");
        result.put("message", "完成信号已发出，Mission 将由执行引擎标记为完成");
        return result;
    }

    @MissionDirectTool
    @Tool(name = "fail_mission",
            description = "标记 Mission 失败。当无法继续执行时调用此工具。" +
                    "missionId 已自动绑定到当前执行上下文，无需手动传递。" +
                    "注意：此工具仅发出失败信号，实际状态变更由执行引擎统一处理。" +
                    "参数: reason(失败原因)")
    @AgentToolSpec(
            displayName = "失败 Mission",
            summary = "将 Mission 标记为失败。",
            purpose = "用于在无法继续推进任务时显式终止 Mission。",
            actionType = ToolActionType.CONTROL,
            tags = {"mission", "fail"},
            usageNotes = {"建议提供清晰失败原因，便于前端展示和后续排查", "missionId 自动从执行上下文获取", "工具仅返回信号，状态由执行引擎统一变更"},
            errorCases = {"非 Mission 执行上下文时返回错误"},
            exampleInput = "{\"reason\":\"Provider 连续超时\"}",
            exampleOutput = "{\"success\":true,\"action\":\"FAIL\",\"reason\":\"Provider 连续超时\"}"
    )
    @AgentToolOutput(
            description = "返回失败信号，由执行引擎统一处理状态变更。",
            example = "{\"success\":true,\"action\":\"FAIL\",\"reason\":\"Provider 连续超时\"}"
    )
    public Map<String, Object> failMission(
            @ToolParam(description = "失败原因") String reason) {

        String missionId = requireMissionId();
        if (missionId == null) {
            return ToolResponse.error("此工具仅在 Mission 执行上下文中可用").toMap();
        }

        // 不再直接更新 Mission 状态，只返回控制信号
        // 实际状态变更由 MissionExecutor.processAgentDecision() 统一负责
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("action", "FAIL");
        result.put("reason", reason != null ? reason : "");
        result.put("message", "失败信号已发出，Mission 将由执行引擎标记为失败");
        return result;
    }

    /**
     * 从当前执行上下文中获取 missionId。
     * Mission 控制工具不再接受模型传入的 missionId，统一从 AgentContext 获取。
     *
     * @return missionId，如果不在 Mission 执行上下文中则返回 null
     */
    private String requireMissionId() {
        AgentContext agentContext = AgentContextHolder.getContext();
        if (agentContext == null || !agentContext.isMissionExecution()) {
            return null;
        }
        return agentContext.getMissionId();
    }

    private String currentMissionStepId() {
        AgentContext agentContext = AgentContextHolder.getContext();
        return agentContext != null ? agentContext.getMissionStepId() : null;
    }

    /**
     * 生成 BatchJob 幂等键。
     * 输入应包含 missionId、stepId（让不同步骤可以独立重试）、batchType、以及内容稳定签名。
     * 用 SHA-256 是为了避免 hashCode 碰撞 + 让长度可预期（VARCHAR(64)）。
     *
     * <p>当 stepId 为空时（理论不应发生）退化为 missionId 级别幂等，
     * 仍可拦下同一步骤内 LLM 重复调用，但跨步骤需要重试时 Mission 必须先把上一步骤标记完成。
     */
    private String computeIdempotencyKey(String batchType, String... parts) {
        StringBuilder sb = new StringBuilder();
        sb.append("mission=").append(requireMissionId()).append('|')
          .append("step=").append(currentMissionStepId() != null ? currentMissionStepId() : "_").append('|')
          .append("type=").append(batchType);
        for (String part : parts) {
            sb.append('|').append(part != null ? part : "");
        }
        return sha256Hex(sb.toString());
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JVM 强制规范实现，不可能走到这里
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * items 列表稳定签名：仅取 entityType / entityId / generationType / providerId 字段，
     * 按 (entityType,entityId) 排序后拼接 → 与 LLM 输入顺序无关、与 params 无关（params 中包含模型生成内容，不稳定）。
     * 这样"用户原始请求 → 5 次复读"会得到相同 key；不同实体集合得到不同 key。
     */
    private static String itemsSignature(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            return "EMPTY";
        }
        List<String> normalized = new ArrayList<>(items.size());
        for (Map<String, Object> it : items) {
            String et = String.valueOf(it.get("entityType"));
            String eid = String.valueOf(it.get("entityId"));
            String gt = String.valueOf(it.get("generationType"));
            String pid = String.valueOf(it.get("providerId"));
            normalized.add(et + ":" + eid + ":" + gt + ":" + pid);
        }
        normalized.sort(String::compareTo);
        return sha256Hex(String.join(",", normalized));
    }

    /**
     * 把新创建的 BatchJob ID 累计到 mission.plan 中。
     *
     * <p>历史实现只写 {@code plan.batchJobId}（标量），后写覆盖前写——多 BatchJob 的
     * Mission 会丢失早期作业的关联。新模型保留 {@code batchJobId} 为"最近一次"标量
     * 兼容已有读路径，同时维护 {@code batchJobIds} 列表（去重保序）作为完整历史。
     *
     * <p>extras 用于附带各 batchType 特有字段（scopeEntityType 等），调用方按需传入。
     */
    @SuppressWarnings("unchecked")
    private void recordBatchJobInPlan(AgentMission mission, String batchJobId, Map<String, Object> extras) {
        Map<String, Object> plan = mission.getPlan() != null ? new HashMap<>(mission.getPlan()) : new HashMap<>();

        Object existingIds = plan.get("batchJobIds");
        List<String> ids;
        if (existingIds instanceof List<?> list) {
            ids = new ArrayList<>(list.size() + 1);
            for (Object o : list) {
                if (o != null) ids.add(o.toString());
            }
        } else {
            ids = new ArrayList<>();
        }
        if (batchJobId != null && !ids.contains(batchJobId)) {
            ids.add(batchJobId);
        }

        plan.put("batchJobIds", ids);
        plan.put("batchJobId", batchJobId); // 最近一次，兼容旧读路径
        if (extras != null) {
            plan.putAll(extras);
        }
        mission.setPlan(plan);
    }

    private Map<String, Object> buildPipelineEventPayload(String batchJobId, String pipelineTemplate, int totalItems) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("batchJobId", batchJobId);
        payload.put("pipelineTemplate", pipelineTemplate);
        payload.put("totalItems", totalItems);
        return payload;
    }
}
