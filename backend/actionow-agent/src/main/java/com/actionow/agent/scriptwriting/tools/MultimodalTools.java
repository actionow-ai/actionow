package com.actionow.agent.scriptwriting.tools;

import com.actionow.agent.billing.service.BillingIntegrationService;
import com.actionow.agent.config.entity.AgentConfigEntity;
import com.actionow.agent.config.service.AgentConfigService;
import com.actionow.agent.constant.AgentType;
import com.actionow.agent.core.agent.AgentStreamEvent;
import com.actionow.agent.core.scope.AgentContext;
import com.actionow.agent.core.scope.AgentContextHolder;
import com.actionow.agent.interaction.AgentStreamBridge;
import com.actionow.agent.interaction.HitlConfirmationHelper;
import com.actionow.agent.dto.request.BatchEntityGenerationRequest;
import com.actionow.agent.dto.request.EntityGenerationRequest;
import com.actionow.agent.dto.request.RetryGenerationRequest;
import com.actionow.agent.dto.response.EntityGenerationResponse;
import com.actionow.agent.feign.AiFeignClient;
import com.actionow.agent.feign.AssetFeignClient;
import com.actionow.agent.feign.ProjectFeignClient;
import com.actionow.agent.feign.dto.AssetDetailResponse;
import com.actionow.agent.feign.dto.AvailableProviderResponse;
import com.actionow.agent.saa.factory.SaaChatModelFactory;
import com.actionow.agent.service.EntityGenerationFacade;
import com.actionow.agent.tool.annotation.AgentToolOutput;
import com.actionow.agent.tool.annotation.AgentToolParamSpec;
import com.actionow.agent.tool.annotation.AgentToolSpec;
import com.actionow.agent.tool.annotation.ToolActionType;
import com.actionow.common.core.context.UserContext;
import com.actionow.common.core.context.UserContextHolder;
import com.actionow.common.core.result.Result;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 多模态 Agent 工具集（SAA v2）
 * 整合素材管理、AI Provider 查询、AI 生成任务提交等功能
 *
 * @author Actionow
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultimodalTools {

    private final AssetFeignClient assetFeignClient;
    private final AiFeignClient aiFeignClient;
    private final EntityGenerationFacade entityGenerationFacade;
    private final BillingIntegrationService billingService;
    private final ProjectFeignClient projectFeignClient;
    private final SaaChatModelFactory chatModelFactory;
    private final AgentConfigService agentConfigService;
    private final AgentStreamBridge streamBridge;
    private final HitlConfirmationHelper hitlConfirmationHelper;

    @Value("${actionow.agent.vision-llm-provider-id:}")
    private String visionLlmProviderId;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final long MAX_DOWNLOAD_BYTES = 20 * 1024 * 1024; // 20MB

    // ==================== 素材查询工具 ====================

    @Tool(name = "get_asset", description = "获取素材详细信息，包含文件URL、缩略图、元数据、生成状态等。")
    @AgentToolSpec(
            displayName = "获取素材详情",
            summary = "按素材 ID 获取完整素材信息。",
            purpose = "用于查看素材元数据、URL、状态以及后续生成相关信息。",
            actionType = ToolActionType.READ,
            tags = {"asset", "detail"},
            usageNotes = {"已知素材 ID 时优先使用本工具"},
            errorCases = {"缺少工作空间上下文会返回错误", "assetId 为空时会返回错误"},
            exampleInput = "{\"assetId\":\"asset_xxx\"}",
            exampleOutput = "{\"success\":true,\"asset\":{\"id\":\"asset_xxx\",\"name\":\"角色立绘\"}}"
    )
    @AgentToolOutput(
            description = "返回单个素材详情。",
            example = "{\"success\":true,\"asset\":{\"id\":\"asset_xxx\",\"name\":\"角色立绘\",\"generationStatus\":\"COMPLETED\"}}"
    )
    public Map<String, Object> getAsset(
            @AgentToolParamSpec(example = "asset_xxx")
            @ToolParam(description = "素材ID（必填）") String assetId) {

        UserContext userContext = UserContextHolder.getContext();
        String workspaceId = userContext != null ? userContext.getWorkspaceId() : null;

        if (workspaceId == null) {
            return Map.of("success", false, "error", "缺少工作空间上下文");
        }
        if (assetId == null || assetId.isBlank()) {
            return Map.of("success", false, "error", "素材ID不能为空");
        }

        try {
            Result<Map<String, Object>> result = assetFeignClient.getAsset(workspaceId, assetId);

            if (result.isSuccess() && result.getData() != null) {
                Map<String, Object> asset = result.getData();
                return Map.of(
                        "success", true,
                        "asset", asset,
                        "message", "获取素材成功"
                );
            } else {
                return Map.of("success", false, "error", "获取素材失败: " + result.getMessage());
            }
        } catch (Exception e) {
            log.error("获取素材异常: assetId={}", assetId, e);
            return Map.of("success", false, "error", "获取素材异常: " + e.getMessage());
        }
    }

    @Tool(name = "batch_get_assets", description = "批量获取素材详细信息。根据素材ID列表获取完整的素材信息，包括文件URL、类型、元数据等。")
    @AgentToolSpec(
            displayName = "批量获取素材",
            summary = "按素材 ID 列表批量获取素材详情。",
            purpose = "用于一次性读取多张图片/视频/音频素材的元数据。",
            actionType = ToolActionType.READ,
            tags = {"asset", "batch", "detail"},
            usageNotes = {"单次最多 50 个素材 ID"},
            errorCases = {"assetIds 为空或超过 50 个会返回错误"},
            exampleInput = "{\"assetIds\":[\"asset_1\",\"asset_2\"]}",
            exampleOutput = "{\"success\":true,\"requestedCount\":2,\"foundCount\":2,\"assets\":[]}"
    )
    @AgentToolOutput(
            description = "返回批量素材详情列表及数量统计。",
            example = "{\"success\":true,\"requestedCount\":2,\"foundCount\":2,\"assets\":[{\"id\":\"asset_1\"}]}"
    )
    public Map<String, Object> batchGetAssets(
            @AgentToolParamSpec(example = "[\"asset_1\",\"asset_2\"]")
            @ToolParam(description = "素材ID列表（必填），最多50个") List<String> assetIds) {

        if (assetIds == null || assetIds.isEmpty()) {
            return Map.of("success", false, "error", "素材ID列表不能为空");
        }
        if (assetIds.size() > 50) {
            return Map.of("success", false, "error", "单次最多查询50个素材");
        }

        try {
            Result<List<Map<String, Object>>> result = assetFeignClient.batchGetAssets(assetIds);

            if (result.isSuccess()) {
                List<Map<String, Object>> assets = result.getData() != null ? result.getData() : Collections.emptyList();
                return Map.of(
                        "success", true,
                        "requestedCount", assetIds.size(),
                        "foundCount", assets.size(),
                        "assets", assets,
                        "message", String.format("获取到 %d / %d 个素材", assets.size(), assetIds.size())
                );
            } else {
                return Map.of("success", false, "error", "批量获取素材失败: " + result.getMessage());
            }
        } catch (Exception e) {
            log.error("批量获取素材异常: assetIds count={}", assetIds.size(), e);
            return Map.of("success", false, "error", "批量获取素材异常: " + e.getMessage());
        }
    }

    // ==================== 实体-素材关联查询工具 ====================

    @Tool(name = "get_entity_assets", description = "查询实体关联的素材列表。可以查询角色、场景、道具、风格、分镜等实体关联的所有素材，用于了解已有素材或作为参考素材。")
    @AgentToolSpec(
            displayName = "查询实体素材",
            summary = "查询某个实体当前已关联的素材列表。",
            purpose = "用于了解某个角色、场景、道具或分镜已经有哪些可复用素材。",
            actionType = ToolActionType.READ,
            tags = {"asset", "entity", "relation"},
            usageNotes = {"实体类型需使用大写枚举值"},
            errorCases = {"缺少工作空间上下文会返回错误", "entityType 或 entityId 为空时会返回错误"},
            exampleInput = "{\"entityType\":\"CHARACTER\",\"entityId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\"}",
            exampleOutput = "{\"success\":true,\"count\":2,\"assets\":[{\"id\":\"asset_xxx\"}]}"
    )
    @AgentToolOutput(
            description = "返回实体关联素材列表与数量。",
            example = "{\"success\":true,\"entityType\":\"CHARACTER\",\"entityId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"count\":2,\"assets\":[]}"
    )
    public Map<String, Object> getEntityAssets(
            @AgentToolParamSpec(enumValues = {"CHARACTER", "SCENE", "PROP", "STYLE", "STORYBOARD", "EPISODE", "SCRIPT"})
            @ToolParam(description = "实体类型（必填）：CHARACTER(角色)、SCENE(场景)、PROP(道具)、STYLE(风格)、STORYBOARD(分镜)、EPISODE(剧集)、SCRIPT(剧本)") String entityType,
            @AgentToolParamSpec(example = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
            @ToolParam(description = "实体ID（必填）") String entityId) {

        UserContext userContext = UserContextHolder.getContext();
        String workspaceId = userContext != null ? userContext.getWorkspaceId() : null;

        if (workspaceId == null) {
            return Map.of("success", false, "error", "缺少工作空间上下文");
        }
        if (entityType == null || entityType.isBlank()) {
            return Map.of("success", false, "error", "实体类型不能为空");
        }
        if (entityId == null || entityId.isBlank()) {
            return Map.of("success", false, "error", "实体ID不能为空");
        }

        try {
            Result<List<Map<String, Object>>> result = assetFeignClient.getEntityAssets(
                    workspaceId, entityType.toUpperCase(), entityId);

            if (result.isSuccess()) {
                List<Map<String, Object>> assets = result.getData() != null ? result.getData() : Collections.emptyList();
                return Map.of(
                        "success", true,
                        "entityType", entityType.toUpperCase(),
                        "entityId", entityId,
                        "count", assets.size(),
                        "assets", assets,
                        "message", String.format("找到 %d 个关联素材", assets.size())
                );
            } else {
                return Map.of("success", false, "error", "查询实体素材失败: " + result.getMessage());
            }
        } catch (Exception e) {
            log.error("查询实体素材异常: entityType={}, entityId={}", entityType, entityId, e);
            return Map.of("success", false, "error", "查询实体素材异常: " + e.getMessage());
        }
    }

    @Tool(name = "get_entity_assets_by_type", description = "根据关联类型查询实体关联的素材。可以精确查询正式素材、草稿素材或参考素材。")
    @AgentToolSpec(
            displayName = "按类型查询实体素材",
            summary = "按关联类型过滤实体已挂载的素材。",
            purpose = "用于只查看正式素材、草稿素材或参考素材。",
            actionType = ToolActionType.READ,
            tags = {"asset", "entity", "filter"},
            usageNotes = {"relationType 支持 OFFICIAL / DRAFT / REFERENCE"},
            errorCases = {"缺少工作空间上下文或必要参数时会返回错误"},
            exampleInput = "{\"entityType\":\"CHARACTER\",\"entityId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"relationType\":\"OFFICIAL\"}",
            exampleOutput = "{\"success\":true,\"count\":1,\"assets\":[]}"
    )
    @AgentToolOutput(
            description = "返回按关系类型过滤后的实体素材列表。",
            example = "{\"success\":true,\"entityType\":\"CHARACTER\",\"entityId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"relationType\":\"OFFICIAL\",\"count\":1,\"assets\":[]}"
    )
    public Map<String, Object> getEntityAssetsByType(
            @AgentToolParamSpec(enumValues = {"CHARACTER", "SCENE", "PROP", "STYLE", "STORYBOARD", "EPISODE", "SCRIPT"})
            @ToolParam(description = "实体类型（必填）：CHARACTER(角色)、SCENE(场景)、PROP(道具)、STYLE(风格)、STORYBOARD(分镜)、EPISODE(剧集)、SCRIPT(剧本)") String entityType,
            @ToolParam(description = "实体ID（必填）") String entityId,
            @AgentToolParamSpec(enumValues = {"OFFICIAL", "DRAFT", "REFERENCE"})
            @ToolParam(description = "关联类型（必填）：OFFICIAL(正式素材)、DRAFT(草稿素材)、REFERENCE(参考素材)") String relationType) {

        UserContext userContext = UserContextHolder.getContext();
        String workspaceId = userContext != null ? userContext.getWorkspaceId() : null;

        if (workspaceId == null) {
            return Map.of("success", false, "error", "缺少工作空间上下文");
        }
        if (entityType == null || entityType.isBlank()) {
            return Map.of("success", false, "error", "实体类型不能为空");
        }
        if (entityId == null || entityId.isBlank()) {
            return Map.of("success", false, "error", "实体ID不能为空");
        }
        if (relationType == null || relationType.isBlank()) {
            return Map.of("success", false, "error", "关联类型不能为空");
        }

        try {
            Result<List<Map<String, Object>>> result = assetFeignClient.getEntityAssetsByType(
                    workspaceId, entityType.toUpperCase(), entityId, relationType.toUpperCase());

            if (result.isSuccess()) {
                List<Map<String, Object>> assets = result.getData() != null ? result.getData() : Collections.emptyList();
                return Map.of(
                        "success", true,
                        "entityType", entityType.toUpperCase(),
                        "entityId", entityId,
                        "relationType", relationType.toUpperCase(),
                        "count", assets.size(),
                        "assets", assets,
                        "message", String.format("找到 %d 个 %s 类型的关联素材", assets.size(), relationType.toUpperCase())
                );
            } else {
                return Map.of("success", false, "error", "查询实体素材失败: " + result.getMessage());
            }
        } catch (Exception e) {
            log.error("查询实体素材异常: entityType={}, entityId={}, relationType={}",
                    entityType, entityId, relationType, e);
            return Map.of("success", false, "error", "查询实体素材异常: " + e.getMessage());
        }
    }

    // ==================== AI Provider 查询工具 ====================

    // ==================== 素材搜索与更新工具 ====================

    @Tool(name = "query_assets", description = "搜索素材（可搜索列表）。支持按关键字模糊搜索名称、描述、标签、附加信息等，返回分页结果。" +
            "如果你知道素材ID请用 get_asset；如果需要按条件筛选请用此工具。")
    @AgentToolSpec(
            displayName = "搜索素材",
            summary = "按关键字、类型、来源和状态分页搜索素材。",
            purpose = "用于在素材库中定位目标素材，而不是只读取单个素材。",
            actionType = ToolActionType.SEARCH,
            tags = {"asset", "query", "search"},
            usageNotes = {"已知素材 ID 时优先使用 get_asset"},
            errorCases = {"过滤参数非法时由下游接口返回错误"},
            exampleInput = "{\"keyword\":\"立绘\",\"assetType\":\"IMAGE\",\"page\":1,\"size\":20}",
            exampleOutput = "{\"success\":true,\"data\":{\"records\":[]},\"message\":\"搜索素材成功\"}"
    )
    @AgentToolOutput(
            description = "返回分页素材搜索结果。",
            example = "{\"success\":true,\"data\":{\"records\":[{\"id\":\"asset_xxx\",\"name\":\"角色立绘\"}]},\"message\":\"搜索素材成功\"}"
    )
    public Map<String, Object> queryAssets(
            @ToolParam(description = "搜索关键字，匹配名称/描述/标签/附加信息", required = false) String keyword,
            @ToolParam(description = "所属剧本ID", required = false) String scriptId,
            @AgentToolParamSpec(enumValues = {"IMAGE", "VIDEO", "AUDIO"})
            @ToolParam(description = "素材类型过滤: IMAGE/VIDEO/AUDIO", required = false) String assetType,
            @AgentToolParamSpec(enumValues = {"AI_GENERATED", "UPLOADED"})
            @ToolParam(description = "来源过滤: AI_GENERATED/UPLOADED", required = false) String source,
            @AgentToolParamSpec(enumValues = {"DRAFT", "GENERATING", "COMPLETED", "FAILED"})
            @ToolParam(description = "生成状态过滤: DRAFT/GENERATING/COMPLETED/FAILED", required = false) String generationStatus,
            @AgentToolParamSpec(enumValues = {"WORKSPACE", "SCRIPT"})
            @ToolParam(description = "范围过滤: WORKSPACE/SCRIPT", required = false) String scope,
            @AgentToolParamSpec(defaultValue = "1", example = "1")
            @ToolParam(description = "页码，默认1", required = false) Integer page,
            @AgentToolParamSpec(defaultValue = "20", example = "20")
            @ToolParam(description = "每页数量，默认20", required = false) Integer size) {

        try {
            Result<Map<String, Object>> result = projectFeignClient.queryAssets(
                    keyword, scriptId, assetType, source, generationStatus, scope, page, size);

            if (result.isSuccess() && result.getData() != null) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("data", result.getData());
                response.put("message", "搜索素材成功");
                return response;
            } else {
                return Map.of("success", false, "error", "搜索素材失败: " + result.getMessage());
            }
        } catch (Exception e) {
            log.error("搜索素材异常", e);
            return Map.of("success", false, "error", "搜索素材异常: " + e.getMessage());
        }
    }

    @Tool(name = "update_asset", description = "更新素材信息，可修改名称、描述、附加信息等。附加信息(extraInfo)支持增量合并，不会覆盖未传字段。")
    @AgentToolSpec(
            displayName = "更新素材",
            summary = "更新素材名称、描述和附加信息。",
            purpose = "用于在生成完成后补充素材标签、说明和业务属性。",
            actionType = ToolActionType.WRITE,
            tags = {"asset", "update"},
            usageNotes = {"extraInfoJson 支持增量合并语义"},
            errorCases = {"assetId 为空时会返回错误", "extraInfoJson 非法时会返回 JSON 错误"},
            exampleInput = "{\"assetId\":\"asset_xxx\",\"name\":\"角色立绘-终版\"}",
            exampleOutput = "{\"success\":true,\"assetId\":\"asset_xxx\",\"message\":\"素材更新成功\"}"
    )
    @AgentToolOutput(
            description = "返回更新后的素材信息。",
            example = "{\"success\":true,\"assetId\":\"asset_xxx\",\"asset\":{\"id\":\"asset_xxx\"},\"message\":\"素材更新成功\"}"
    )
    public Map<String, Object> updateAsset(
            @AgentToolParamSpec(example = "asset_xxx")
            @ToolParam(description = "素材ID（必填）") String assetId,
            @ToolParam(description = "素材名称", required = false) String name,
            @ToolParam(description = "素材描述", required = false) String description,
            @ToolParam(description = "附加信息(JSON)，与现有数据合并而非替换。例如: {\"tag\":\"风景\",\"quality\":\"高清\"}", required = false) String extraInfoJson) {

        if (assetId == null || assetId.isBlank()) {
            return Map.of("success", false, "error", "素材ID不能为空");
        }

        Map<String, Object> request = new HashMap<>();
        if (name != null && !name.isBlank()) {
            request.put("name", name);
        }
        if (description != null && !description.isBlank()) {
            request.put("description", description);
        }
        if (extraInfoJson != null && !extraInfoJson.isBlank()) {
            try {
                Map<String, Object> extraInfo = OBJECT_MAPPER.readValue(extraInfoJson,
                        new TypeReference<Map<String, Object>>() {});
                request.put("extraInfo", extraInfo);
            } catch (Exception e) {
                return Map.of("success", false, "error", "extraInfo JSON 格式错误: " + e.getMessage());
            }
        }

        if (request.isEmpty()) {
            return Map.of("success", false, "error", "没有需要更新的字段");
        }

        try {
            Result<Map<String, Object>> result = projectFeignClient.updateAsset(assetId, request);

            if (result.isSuccess()) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("assetId", assetId);
                response.put("asset", result.getData());
                response.put("message", "素材更新成功");
                return response;
            } else {
                return Map.of("success", false, "error", "更新素材失败: " + result.getMessage());
            }
        } catch (Exception e) {
            log.error("更新素材异常: assetId={}", assetId, e);
            return Map.of("success", false, "error", "更新素材异常: " + e.getMessage());
        }
    }

    // ==================== AI Provider 查询工具 (原有) ====================

    @Tool(name = "list_ai_providers", description = "查询可用的 AI 生成服务提供商列表。了解各 Provider 的能力、费用和支持的参数，用于选择合适的生成服务。")
    @AgentToolSpec(
            displayName = "列出 AI Provider",
            summary = "按生成类型列出可用 AI Provider。",
            purpose = "用于在发起 AI 生成前选择可用模型和了解成本。",
            actionType = ToolActionType.SEARCH,
            tags = {"provider", "ai", "generation"},
            usageNotes = {"providerType 必填"},
            errorCases = {"providerType 为空时会返回错误"},
            exampleInput = "{\"providerType\":\"IMAGE\"}",
            exampleOutput = "{\"success\":true,\"providerType\":\"IMAGE\",\"providers\":[]}"
    )
    @AgentToolOutput(
            description = "返回指定生成类型的 Provider 列表。",
            example = "{\"success\":true,\"providerType\":\"IMAGE\",\"count\":2,\"providers\":[{\"id\":\"provider_xxx\"}]}"
    )
    public Map<String, Object> listAiProviders(
            @AgentToolParamSpec(enumValues = {"IMAGE", "VIDEO", "AUDIO"})
            @ToolParam(description = "Provider 类型（必填）：IMAGE(图片)、VIDEO(视频)、AUDIO(音频)") String providerType) {

        if (providerType == null || providerType.isBlank()) {
            return Map.of("success", false, "error", "Provider 类型不能为空");
        }

        try {
            Result<List<AvailableProviderResponse>> result = aiFeignClient.getAvailableProviders(providerType.toUpperCase());

            if (result.isSuccess() && result.getData() != null) {
                List<AvailableProviderResponse> providers = result.getData();

                // 转换为简洁的响应格式
                List<Map<String, Object>> providerList = providers.stream()
                        .map(p -> {
                            Map<String, Object> info = new HashMap<>();
                            info.put("id", p.getId());
                            info.put("name", p.getName());
                            info.put("description", p.getDescription());
                            info.put("providerType", p.getProviderType());
                            info.put("creditCost", p.getCreditCost());
                            info.put("supportsBlocking", p.getSupportsBlocking());
                            info.put("supportsStreaming", p.getSupportsStreaming());
                            // 简化的参数信息
                            if (p.getInputSchema() != null && !p.getInputSchema().isEmpty()) {
                                List<String> paramNames = p.getInputSchema().stream()
                                        .map(param -> (String) param.get("name"))
                                        .filter(Objects::nonNull)
                                        .collect(Collectors.toList());
                                info.put("supportedParams", paramNames);
                            }
                            return info;
                        })
                        .collect(Collectors.toList());

                return Map.of(
                        "success", true,
                        "providerType", providerType.toUpperCase(),
                        "count", providerList.size(),
                        "providers", providerList,
                        "message", String.format("找到 %d 个 %s 类型的 Provider", providerList.size(), providerType.toUpperCase())
                );
            } else {
                return Map.of("success", false, "error", "查询 Provider 列表失败: " + result.getMessage());
            }
        } catch (Exception e) {
            log.error("查询 Provider 列表异常: providerType={}", providerType, e);
            return Map.of("success", false, "error", "查询 Provider 列表异常: " + e.getMessage());
        }
    }

    @Tool(name = "get_ai_provider_detail", description = "获取指定 AI Provider 的详细信息，包括完整的参数定义、取值范围等。用于了解如何正确调用该 Provider。")
    @AgentToolSpec(
            displayName = "获取 AI Provider 详情",
            summary = "按 Provider ID 获取模型详情与输入参数定义。",
            purpose = "用于在调用生成接口前了解模型能力、输入 schema 和可选参数。",
            actionType = ToolActionType.READ,
            tags = {"provider", "ai", "detail"},
            usageNotes = {"已知 providerId 时优先使用"},
            errorCases = {"providerId 为空时会返回错误"},
            exampleInput = "{\"providerId\":\"provider_xxx\"}",
            exampleOutput = "{\"success\":true,\"provider\":{\"id\":\"provider_xxx\",\"providerType\":\"IMAGE\"}}"
    )
    @AgentToolOutput(
            description = "返回单个 AI Provider 的完整能力说明。",
            example = "{\"success\":true,\"provider\":{\"id\":\"provider_xxx\",\"inputSchema\":[]}}"
    )
    public Map<String, Object> getAiProviderDetail(
            @AgentToolParamSpec(example = "provider_xxx")
            @ToolParam(description = "Provider ID（必填）") String providerId) {

        if (providerId == null || providerId.isBlank()) {
            return Map.of("success", false, "error", "Provider ID 不能为空");
        }

        try {
            Result<AvailableProviderResponse> result = aiFeignClient.getProviderDetail(providerId);

            if (result.isSuccess() && result.getData() != null) {
                AvailableProviderResponse provider = result.getData();

                Map<String, Object> detail = new HashMap<>();
                detail.put("id", provider.getId());
                detail.put("name", provider.getName());
                detail.put("description", provider.getDescription());
                detail.put("providerType", provider.getProviderType());
                detail.put("creditCost", provider.getCreditCost());
                detail.put("supportsBlocking", provider.getSupportsBlocking());
                detail.put("supportsStreaming", provider.getSupportsStreaming());
                detail.put("supportsCallback", provider.getSupportsCallback());
                detail.put("supportsPolling", provider.getSupportsPolling());
                detail.put("inputSchema", provider.getInputSchema());
                detail.put("inputGroups", provider.getInputGroups());

                return Map.of(
                        "success", true,
                        "provider", detail,
                        "message", "获取 Provider 详情成功: " + provider.getName()
                );
            } else {
                return Map.of("success", false, "error", "获取 Provider 详情失败: " + result.getMessage());
            }
        } catch (Exception e) {
            log.error("获取 Provider 详情异常: providerId={}", providerId, e);
            return Map.of("success", false, "error", "获取 Provider 详情异常: " + e.getMessage());
        }
    }

    // ==================== AI 生成任务工具（一体化接口） ====================

    @Tool(name = "generate_entity_asset", description = "为实体生成素材（一体化接口）。自动创建素材记录、建立实体关联、提交AI生成任务。任务异步执行，立即返回任务ID和素材ID。" +
            "scriptId 自动解析（上下文优先，缺失时按 entityId 反查），无需手动传；如需跨剧本生成可显式覆盖。")
    @AgentToolSpec(
            displayName = "生成实体素材",
            summary = "为角色、场景、道具、分镜等实体发起一次 AI 素材生成任务。",
            purpose = "统一完成素材记录创建、实体关联和异步任务提交。",
            actionType = ToolActionType.GENERATE,
            tags = {"asset", "generation", "entity"},
            usageNotes = {"任务异步执行，返回 taskId 后需轮询状态", "providerId 可省略，系统会自动选择",
                    "【scope 自动行为】asset 的作用域由 scriptId 决定：" +
                    "scriptId 解析优先级 = LLM 显式参数 > 当前会话 AgentContext.scriptId > 按 entityId 反查实体所属剧本（STORYBOARD/EPISODE/CHARACTER/SCENE/PROP/STYLE 都支持反查）。" +
                    "三层都拿不到才会落到 WORKSPACE 作用域。多数情况下不需要手动传 scriptId，反推会自动生效。",
                    "【prompt 构建流程】生成素材前应先查询实体详情以获取完整描述数据，然后综合以下信息构造 prompt：" +
                    "1) 实体 description + fixedDesc；" +
                    "2) 实体 appearanceData 中的外貌/环境属性；" +
                    "3) 关联 Style 的 fixedDesc 和 styleParams（如有）；" +
                    "4) 分镜场景需额外综合 sceneOverride、角色位置/动作/表情、道具交互方式"},
            errorCases = {"缺少工作空间上下文会返回错误", "必填实体/生成类型参数为空时会失败"},
            exampleInput = "{\"entityType\":\"CHARACTER\",\"entityId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"generationType\":\"IMAGE\",\"prompt\":\"A young pilot\"}",
            exampleOutput = "{\"success\":true,\"assetId\":\"asset_xxx\",\"taskId\":\"task_xxx\",\"taskStatus\":\"PENDING\"}"
    )
    @AgentToolOutput(
            description = "返回新素材 ID、任务 ID、Provider 和预估积分消耗。",
            example = "{\"success\":true,\"assetId\":\"asset_xxx\",\"taskId\":\"task_xxx\",\"providerId\":\"provider_xxx\",\"creditCost\":120}"
    )
    public Map<String, Object> generateEntityAsset(
            @AgentToolParamSpec(enumValues = {"CHARACTER", "SCENE", "PROP", "STYLE", "STORYBOARD", "EPISODE", "SCRIPT", "ASSET"})
            @ToolParam(description = "实体类型（必填）：CHARACTER(角色)、SCENE(场景)、PROP(道具)、STYLE(风格)、STORYBOARD(分镜)、EPISODE(剧集)、SCRIPT(剧本)、ASSET(素材衍生)") String entityType,
            @ToolParam(description = "实体ID（必填）") String entityId,
            @AgentToolParamSpec(enumValues = {"IMAGE", "VIDEO", "AUDIO"})
            @ToolParam(description = "生成类型（必填）：IMAGE(图片)、VIDEO(视频)、AUDIO(音频)") String generationType,
            @ToolParam(description = "生成提示词（必填）：描述要生成的内容") String prompt,
            @ToolParam(description = "负面提示词（可选）：描述不希望出现的内容", required = false) String negativePrompt,
            @ToolParam(description = "AI Provider 标识（可选）：不指定则自动选择。支持 UUID、pluginId（如 seedream-4-5）或模型名称（如 Seedream 4.5）。可通过 list_ai_providers 查看可用列表", required = false) String providerId,
            @ToolParam(description = "素材名称（可选）：不指定则自动生成", required = false) String assetName,
            @AgentToolParamSpec(defaultValue = "DRAFT", enumValues = {"DRAFT", "OFFICIAL", "REFERENCE"})
            @ToolParam(description = "关联类型（可选）：DRAFT(草稿,默认)、OFFICIAL(正式)、REFERENCE(参考)", required = false) String relationType,
            @ToolParam(description = "额外参数JSON（可选）：如 {\"width\":1024,\"height\":1024,\"steps\":30}", required = false) String paramsJson,
            @ToolParam(description = "参考素材ID列表（可选）：用于img2img等需要参考图的场景", required = false) List<String> referenceAssetIds,
            @ToolParam(description = "剧本ID（可选）：通常无需提供，工具会优先使用上下文，再按 entityId 反查实体所属剧本。" +
                    "仅在跨剧本生成或上下文不可信时显式指定，会覆盖自动解析", required = false) String scriptId) {

        UserContext userContext = UserContextHolder.getContext();
        AgentContext agentContext = AgentContextHolder.getContext();

        String workspaceId = userContext != null ? userContext.getWorkspaceId() : null;
        String userId = userContext != null ? userContext.getUserId() : "system";
        String sessionId = agentContext != null ? agentContext.getSessionId() : null;
        // scriptId 解析优先级：LLM 显式传入 > AgentContext > （Orchestrator 在 createAsset 中按 entityId 反推）
        String resolvedScriptId = (scriptId != null && !scriptId.isBlank())
                ? scriptId
                : (agentContext != null ? agentContext.getScriptId() : null);

        if (workspaceId == null) {
            return Map.of("success", false, "error", "缺少工作空间上下文");
        }

        // 构建请求
        EntityGenerationRequest request = new EntityGenerationRequest();
        request.setEntityType(entityType);
        request.setEntityId(entityId);
        request.setGenerationType(generationType);
        request.setProviderId(providerId);
        request.setAssetName(assetName);
        request.setRelationType(relationType != null ? relationType : "DRAFT");
        request.setScriptId(resolvedScriptId);
        request.setReferenceAssetIds(referenceAssetIds);

        // 构建 params Map - 包含 prompt, negative_prompt 及其他参数
        Map<String, Object> params = new HashMap<>();
        if (prompt != null && !prompt.isBlank()) {
            params.put("prompt", prompt);
        }
        if (negativePrompt != null && !negativePrompt.isBlank()) {
            params.put("negative_prompt", negativePrompt);
        }

        // 解析额外参数并合并
        if (paramsJson != null && !paramsJson.isBlank()) {
            try {
                Map<String, Object> extraParams = OBJECT_MAPPER.readValue(paramsJson,
                        new TypeReference<Map<String, Object>>() {});
                params.putAll(extraParams);
            } catch (Exception e) {
                log.warn("解析额外参数失败: {}", e.getMessage());
            }
        }
        request.setParams(params);

        try {
            log.info("生成实体素材: entityType={}, entityId={}, generationType={}", entityType, entityId, generationType);

            EntityGenerationResponse response = entityGenerationFacade.submitEntityGeneration(
                    request, workspaceId, userId);

            // 记录计费
            if (sessionId != null && Boolean.TRUE.equals(response.getSuccess()) && response.getCreditCost() != null) {
                billingService.recordAiToolUsage(sessionId, "entity_generation", response.getCreditCost());
            }

            if (Boolean.TRUE.equals(response.getSuccess())) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("assetId", response.getAssetId());
                result.put("relationId", response.getRelationId());
                result.put("taskId", response.getTaskId());
                result.put("taskStatus", response.getTaskStatus());
                result.put("providerId", response.getProviderId());
                result.put("creditCost", response.getCreditCost());
                result.put("message", String.format("生成任务已提交，素材ID: %s，任务ID: %s，任务在后台异步执行。",
                        response.getAssetId(), response.getTaskId()));
                return result;
            } else {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("error", response.getErrorMessage());
                if (response.getAssetId() != null) {
                    result.put("assetId", response.getAssetId());
                }
                return result;
            }
        } catch (Exception e) {
            log.error("生成实体素材异常: entityType={}, entityId={}", entityType, entityId, e);
            return Map.of("success", false, "error", "生成实体素材异常: " + e.getMessage());
        }
    }

    @Tool(name = "batch_generate_entity_assets", description = "批量为实体生成素材。接受JSON数组，一次性为多个实体生成素材。每个请求自动完成素材创建、关联建立、任务提交。" +
            "每条请求支持可选 scriptId 字段；缺失时按上下文 / entity 反推。")
    @AgentToolSpec(
            displayName = "批量生成实体素材",
            summary = "一次性为多个实体提交 AI 素材生成任务。",
            purpose = "用于角色立绘、场景图、分镜图等大批量生成场景。",
            actionType = ToolActionType.GENERATE,
            tags = {"asset", "generation", "batch"},
            usageNotes = {"任务异步执行", "parallel=true 时会并行提交",
                    "【scope 自动行为】每条请求可在 JSON 里加 scriptId 字段；不传时按"
                    + " AgentContext > entity 反推 顺序解析，三层都未命中才落 WORKSPACE 作用域"},
            errorCases = {"generationsJson 为空时会返回错误", "缺少工作空间上下文会返回错误"},
            exampleInput = "{\"generationsJson\":\"[{\\\"entityType\\\":\\\"CHARACTER\\\",\\\"entityId\\\":\\\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\\\",\\\"generationType\\\":\\\"IMAGE\\\",\\\"prompt\\\":\\\"A young pilot\\\"}]\"}",
            exampleOutput = "{\"success\":true,\"totalRequested\":1,\"successCount\":1,\"tasks\":[]}"
    )
    @AgentToolOutput(
            description = "返回批量生成任务的提交结果和统计信息。",
            example = "{\"success\":true,\"totalRequested\":2,\"successCount\":2,\"failedCount\":0,\"tasks\":[]}"
    )
    public Map<String, Object> batchGenerateEntityAssets(
            @ToolParam(description = "生成请求JSON数组，每个元素包含: entityType(必填), entityId(必填), generationType(必填:IMAGE/VIDEO/AUDIO), prompt(必填), negativePrompt(可选), providerId(可选), assetName(可选), relationType(可选), params(可选), referenceAssetIds(可选)。例如: [{\"entityType\":\"CHARACTER\",\"entityId\":\"xxx\",\"generationType\":\"IMAGE\",\"prompt\":\"一个年轻女孩\"}]") String generationsJson,
            @AgentToolParamSpec(defaultValue = "false", enumValues = {"true", "false"})
            @ToolParam(description = "是否并行处理（可选）：true-并行提交，false-顺序提交（默认）", required = false) Boolean parallel) {

        if (generationsJson == null || generationsJson.isBlank()) {
            return Map.of("success", false, "error", "生成请求JSON数组不能为空");
        }

        UserContext userContext = UserContextHolder.getContext();
        AgentContext agentContext = AgentContextHolder.getContext();

        String workspaceId = userContext != null ? userContext.getWorkspaceId() : null;
        String userId = userContext != null ? userContext.getUserId() : "system";
        String sessionId = agentContext != null ? agentContext.getSessionId() : null;
        String scriptId = agentContext != null ? agentContext.getScriptId() : null;

        if (workspaceId == null) {
            return Map.of("success", false, "error", "缺少工作空间上下文");
        }

        try {
            List<Map<String, Object>> requestMaps = OBJECT_MAPPER.readValue(generationsJson,
                    new TypeReference<List<Map<String, Object>>>() {});

            if (requestMaps.isEmpty()) {
                return Map.of("success", false, "error", "生成请求列表不能为空");
            }

            // 转换为 EntityGenerationRequest 列表
            List<EntityGenerationRequest> requests = new ArrayList<>();
            for (Map<String, Object> reqMap : requestMaps) {
                EntityGenerationRequest req = new EntityGenerationRequest();
                req.setEntityType((String) reqMap.get("entityType"));
                req.setEntityId((String) reqMap.get("entityId"));
                req.setGenerationType((String) reqMap.get("generationType"));
                req.setProviderId((String) reqMap.get("providerId"));
                req.setAssetName((String) reqMap.get("assetName"));
                req.setRelationType((String) reqMap.getOrDefault("relationType", "DRAFT"));
                // scriptId 优先级：单条请求显式 > 批量统一上下文 >（Orchestrator 反推）
                Object perItemScriptId = reqMap.get("scriptId");
                req.setScriptId(perItemScriptId instanceof String s && !s.isBlank() ? s : scriptId);

                // 构建 params Map - 包含 prompt, negative_prompt 及其他参数
                Map<String, Object> params = new HashMap<>();
                String promptVal = (String) reqMap.get("prompt");
                String negativePromptVal = (String) reqMap.get("negativePrompt");
                if (promptVal != null && !promptVal.isBlank()) {
                    params.put("prompt", promptVal);
                }
                if (negativePromptVal != null && !negativePromptVal.isBlank()) {
                    params.put("negative_prompt", negativePromptVal);
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> extraParams = (Map<String, Object>) reqMap.get("params");
                if (extraParams != null) {
                    params.putAll(extraParams);
                }
                req.setParams(params);

                @SuppressWarnings("unchecked")
                List<String> refAssetIds = (List<String>) reqMap.get("referenceAssetIds");
                if (refAssetIds != null) {
                    req.setReferenceAssetIds(refAssetIds);
                }

                requests.add(req);
            }

            // 构建批量请求
            BatchEntityGenerationRequest batchRequest = new BatchEntityGenerationRequest();
            batchRequest.setRequests(requests);
            batchRequest.setParallel(parallel != null ? parallel : false);

            log.info("批量生成实体素材: count={}, parallel={}", requests.size(), batchRequest.getParallel());

            emitBatchProgress(sessionId, "batch_start",
                    "开始批量生成素材（0/" + requests.size() + "）", 0.0,
                    0, requests.size());

            List<EntityGenerationResponse> responses = entityGenerationFacade.submitBatchEntityGeneration(
                    batchRequest, workspaceId, userId);

            // 统计结果
            long successCount = responses.stream().filter(r -> Boolean.TRUE.equals(r.getSuccess())).count();
            long failedCount = responses.size() - successCount;
            long totalCreditCost = responses.stream()
                    .filter(r -> Boolean.TRUE.equals(r.getSuccess()) && r.getCreditCost() != null)
                    .mapToLong(EntityGenerationResponse::getCreditCost)
                    .sum();

            // 记录计费
            if (sessionId != null && totalCreditCost > 0) {
                billingService.recordAiToolUsage(sessionId, "batch_entity_generation", totalCreditCost);
            }

            // 构建响应
            List<Map<String, Object>> results = new ArrayList<>();
            List<Map<String, Object>> errors = new ArrayList<>();

            for (EntityGenerationResponse resp : responses) {
                if (Boolean.TRUE.equals(resp.getSuccess())) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("assetId", resp.getAssetId());
                    item.put("relationId", resp.getRelationId());
                    item.put("taskId", resp.getTaskId());
                    item.put("taskStatus", resp.getTaskStatus());
                    item.put("providerId", resp.getProviderId());
                    item.put("creditCost", resp.getCreditCost());
                    results.add(item);
                } else {
                    Map<String, Object> errorItem = new HashMap<>();
                    errorItem.put("assetId", resp.getAssetId());
                    errorItem.put("error", resp.getErrorMessage());
                    errors.add(errorItem);
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", failedCount == 0);
            response.put("totalRequested", requests.size());
            response.put("successCount", successCount);
            response.put("failedCount", failedCount);
            response.put("tasks", results);
            if (!errors.isEmpty()) {
                response.put("errors", errors);
            }
            if (totalCreditCost > 0) {
                response.put("totalCreditCost", totalCreditCost);
            }
            response.put("message", String.format("批量提交 %d/%d 个生成任务成功，任务在后台异步执行。",
                    successCount, requests.size()));

            emitBatchProgress(sessionId, "batch_complete",
                    "批量生成已提交（" + successCount + "/" + requests.size() + "）", 1.0,
                    (int) successCount, requests.size());

            return response;

        } catch (Exception e) {
            log.error("批量生成实体素材异常", e);
            return Map.of("success", false, "error", "批量生成实体素材异常: " + e.getMessage());
        }
    }

    @Tool(name = "retry_generation", description = "重试失败的生成任务。基于已有素材重新提交生成，可选择性覆盖原有参数。")
    @AgentToolSpec(
            displayName = "重试生成任务",
            summary = "基于已有素材重新提交生成任务。",
            purpose = "用于对失败任务或效果不佳的任务进行参数覆盖后重试。",
            actionType = ToolActionType.GENERATE,
            tags = {"asset", "generation", "retry"},
            usageNotes = {"不传覆盖参数时默认沿用原任务配置"},
            errorCases = {"缺少工作空间上下文或 assetId 为空时会返回错误"},
            exampleInput = "{\"assetId\":\"asset_xxx\",\"prompt\":\"A brighter portrait\"}",
            exampleOutput = "{\"success\":true,\"assetId\":\"asset_xxx\",\"taskId\":\"task_retry_xxx\"}"
    )
    @AgentToolOutput(
            description = "返回新重试任务的 taskId、providerId 和成本信息。",
            example = "{\"success\":true,\"assetId\":\"asset_xxx\",\"taskId\":\"task_retry_xxx\",\"creditCost\":120}"
    )
    public Map<String, Object> retryGeneration(
            @AgentToolParamSpec(example = "asset_xxx")
            @ToolParam(description = "素材ID（必填）：要重试的素材") String assetId,
            @ToolParam(description = "新提示词（可选）：不指定则使用原提示词", required = false) String prompt,
            @ToolParam(description = "新负面提示词（可选）：不指定则使用原参数", required = false) String negativePrompt,
            @ToolParam(description = "新Provider ID（可选）：不指定则使用原Provider", required = false) String providerId,
            @ToolParam(description = "新参数JSON（可选）：合并覆盖原参数", required = false) String paramsJson) {

        UserContext userContext = UserContextHolder.getContext();
        AgentContext agentContext = AgentContextHolder.getContext();

        String workspaceId = userContext != null ? userContext.getWorkspaceId() : null;
        String userId = userContext != null ? userContext.getUserId() : "system";
        String sessionId = agentContext != null ? agentContext.getSessionId() : null;

        if (workspaceId == null) {
            return Map.of("success", false, "error", "缺少工作空间上下文");
        }
        if (assetId == null || assetId.isBlank()) {
            return Map.of("success", false, "error", "素材ID不能为空");
        }

        // 构建重试请求 - 覆盖参数放入 params
        RetryGenerationRequest request = new RetryGenerationRequest();
        request.setAssetId(assetId);
        request.setProviderId(providerId);

        // 构建覆盖 params
        Map<String, Object> params = new HashMap<>();
        if (prompt != null && !prompt.isBlank()) {
            params.put("prompt", prompt);
        }
        if (negativePrompt != null && !negativePrompt.isBlank()) {
            params.put("negative_prompt", negativePrompt);
        }

        // 解析额外参数并合并
        if (paramsJson != null && !paramsJson.isBlank()) {
            try {
                Map<String, Object> extraParams = OBJECT_MAPPER.readValue(paramsJson,
                        new TypeReference<Map<String, Object>>() {});
                params.putAll(extraParams);
            } catch (Exception e) {
                log.warn("解析额外参数失败: {}", e.getMessage());
            }
        }
        if (!params.isEmpty()) {
            request.setParams(params);
        }

        try {
            log.info("重试生成任务: assetId={}", assetId);

            EntityGenerationResponse response = entityGenerationFacade.retryGeneration(
                    request, workspaceId, userId);

            // 记录计费
            if (sessionId != null && Boolean.TRUE.equals(response.getSuccess()) && response.getCreditCost() != null) {
                billingService.recordAiToolUsage(sessionId, "retry_generation", response.getCreditCost());
            }

            if (Boolean.TRUE.equals(response.getSuccess())) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("assetId", response.getAssetId());
                result.put("taskId", response.getTaskId());
                result.put("taskStatus", response.getTaskStatus());
                result.put("providerId", response.getProviderId());
                result.put("creditCost", response.getCreditCost());
                result.put("message", String.format("重试任务已提交，任务ID: %s，任务在后台异步执行。", response.getTaskId()));
                return result;
            } else {
                return Map.of(
                        "success", false,
                        "assetId", assetId,
                        "error", response.getErrorMessage()
                );
            }
        } catch (Exception e) {
            log.error("重试生成任务异常: assetId={}", assetId, e);
            return Map.of("success", false, "error", "重试生成任务异常: " + e.getMessage());
        }
    }

    @Tool(name = "get_generation_status", description = "查询素材的生成状态。包括任务状态、进度、错误信息等。")
    @AgentToolSpec(
            displayName = "查询生成状态",
            summary = "查询某个素材当前生成任务的状态。",
            purpose = "用于轮询查看生成任务进度、失败原因与完成状态。",
            actionType = ToolActionType.READ,
            tags = {"asset", "generation", "status"},
            usageNotes = {"通常配合 generate_entity_asset 或 retry_generation 返回的 assetId 使用"},
            errorCases = {"缺少工作空间上下文或 assetId 为空时会返回错误"},
            exampleInput = "{\"assetId\":\"asset_xxx\"}",
            exampleOutput = "{\"success\":true,\"assetId\":\"asset_xxx\",\"generationStatus\":\"COMPLETED\"}"
    )
    @AgentToolOutput(
            description = "返回素材当前生成状态及相关任务信息。",
            example = "{\"success\":true,\"assetId\":\"asset_xxx\",\"generationStatus\":\"COMPLETED\",\"taskStatus\":\"SUCCEEDED\"}"
    )
    public Map<String, Object> getGenerationStatus(
            @AgentToolParamSpec(example = "asset_xxx")
            @ToolParam(description = "素材ID（必填）") String assetId) {

        UserContext userContext = UserContextHolder.getContext();
        String workspaceId = userContext != null ? userContext.getWorkspaceId() : null;

        if (workspaceId == null) {
            return Map.of("success", false, "error", "缺少工作空间上下文");
        }
        if (assetId == null || assetId.isBlank()) {
            return Map.of("success", false, "error", "素材ID不能为空");
        }

        try {
            log.debug("查询生成状态: assetId={}", assetId);

            Map<String, Object> status = entityGenerationFacade.getGenerationStatus(assetId, workspaceId);
            return status;
        } catch (Exception e) {
            log.error("查询生成状态异常: assetId={}", assetId, e);
            return Map.of("success", false, "error", "查询生成状态异常: " + e.getMessage());
        }
    }

    // ==================== 素材分析工具 ====================

    @Tool(name = "analyze_assets", description = """
            使用多模态 LLM 分析一个或多个素材内容（支持图片、视频、音频、文档）。
            工具会下载素材文件并发送给视觉 LLM 进行分析，返回文字描述结果。
            适用场景：分析角色形象图风格/质量、对比多张素材差异、审查分镜画面内容等。
            注意：分析能力取决于所配置的 LLM 是否支持对应的媒体类型。""")
    @AgentToolSpec(
            displayName = "分析素材内容",
            summary = "使用多模态 LLM 分析素材，返回文字描述。",
            purpose = "让 Agent 能「看到」素材内容并进行分析，弥补工具返回 URL 时模型无法感知图像的缺陷。",
            actionType = ToolActionType.READ,
            tags = {"asset", "analysis", "vision", "multimodal"},
            usageNotes = {
                    "最多同时分析 10 个素材",
                    "支持 IMAGE / VIDEO / AUDIO / PDF 等类型，具体取决于 LLM 能力",
                    "大文件（>20MB）会被跳过",
                    "先用 get_entity_assets 获取素材列表，再用本工具分析"
            },
            errorCases = {
                    "assetIds 为空或超过 10 个",
                    "缺少工作空间上下文",
                    "所有素材下载失败时返回错误",
                    "LLM Provider 不支持对应媒体类型时可能返回不完整分析"
            },
            exampleInput = "{\"assetIds\":[\"asset_xxx\",\"asset_yyy\"],\"analysisPrompt\":\"分析这两张角色形象图的风格差异\"}",
            exampleOutput = "{\"success\":true,\"analyzedCount\":2,\"analysis\":\"第一张形象图采用赛博朋克风格...\"}"
    )
    @AgentToolOutput(
            description = "返回 LLM 对素材的分析结果文本，以及每个素材的处理状态。",
            example = "{\"success\":true,\"analyzedCount\":2,\"skippedCount\":0,\"analysis\":\"分析内容...\",\"analyzedAssets\":[{\"index\":1,\"assetId\":\"asset_xxx\",\"name\":\"角色立绘\"}]}"
    )
    public Map<String, Object> analyzeAssets(
            @AgentToolParamSpec(example = "[\"asset_xxx\",\"asset_yyy\"]")
            @ToolParam(description = "素材 ID 列表（必填），最多 10 个") List<String> assetIds,

            @AgentToolParamSpec(example = "分析这些角色形象图的风格、构图和色彩特点")
            @ToolParam(description = "分析提示词（必填）：告诉 LLM 如何分析这些素材") String analysisPrompt,

            @ToolParam(description = "自定义系统提示词（可选）：覆盖默认的分析系统提示", required = false) String systemPrompt,

            @AgentToolParamSpec(example = "llm_provider_xxx")
            @ToolParam(description = "指定 LLM Provider ID（可选）：不传则使用默认视觉模型", required = false) String llmProviderId) {

        // 1. 参数校验
        if (assetIds == null || assetIds.isEmpty()) {
            return Map.of("success", false, "error", "assetIds 不能为空");
        }
        if (assetIds.size() > 10) {
            return Map.of("success", false, "error", "最多支持同时分析 10 个素材");
        }
        if (!StringUtils.hasText(analysisPrompt)) {
            return Map.of("success", false, "error", "analysisPrompt 不能为空");
        }

        // 2. 获取上下文
        UserContext userContext = UserContextHolder.getContext();
        AgentContext agentContext = AgentContextHolder.getContext();
        String workspaceId = userContext != null ? userContext.getWorkspaceId() : null;
        String sessionId = agentContext != null ? agentContext.getSessionId() : null;

        if (!StringUtils.hasText(workspaceId)) {
            return Map.of("success", false, "error", "缺少工作空间上下文");
        }

        try {
            // 3. 批量获取素材详情
            Result<List<AssetDetailResponse>> assetsResult = assetFeignClient.batchGetAssetDetails(assetIds);
            if (!assetsResult.isSuccess() || assetsResult.getData() == null || assetsResult.getData().isEmpty()) {
                return Map.of("success", false, "error", "获取素材详情失败: " +
                        (assetsResult.getMessage() != null ? assetsResult.getMessage() : "未找到素材"));
            }

            List<AssetDetailResponse> assets = assetsResult.getData();

            // 4. 下载素材并构造 Media
            List<Media> mediaList = new ArrayList<>();
            List<Map<String, Object>> analyzedAssets = new ArrayList<>();
            List<Map<String, Object>> skippedAssets = new ArrayList<>();

            for (int i = 0; i < assets.size(); i++) {
                AssetDetailResponse asset = assets.get(i);
                String assetId = asset.getId();
                String assetName = asset.getName();
                String fileUrl = asset.getFileUrl();
                String mimeType = asset.getMimeType();

                if (!StringUtils.hasText(fileUrl)) {
                    skippedAssets.add(Map.of("index", i + 1, "assetId", Objects.toString(assetId, ""),
                            "name", Objects.toString(assetName, ""), "reason", "无文件 URL"));
                    continue;
                }

                byte[] bytes = downloadAssetBytes(fileUrl);
                if (bytes == null || bytes.length == 0) {
                    skippedAssets.add(Map.of("index", i + 1, "assetId", Objects.toString(assetId, ""),
                            "name", Objects.toString(assetName, ""), "reason", "下载失败或文件为空"));
                    continue;
                }

                org.springframework.util.MimeType parsedMimeType = StringUtils.hasText(mimeType)
                        ? MimeTypeUtils.parseMimeType(mimeType) : MimeTypeUtils.APPLICATION_OCTET_STREAM;
                mediaList.add(Media.builder().mimeType(parsedMimeType).data(bytes).build());
                analyzedAssets.add(Map.of("index", i + 1, "assetId", Objects.toString(assetId, ""),
                        "name", Objects.toString(assetName, ""), "mimeType", Objects.toString(mimeType, "")));
            }

            if (mediaList.isEmpty()) {
                return Map.of("success", false, "error", "所有素材均无法下载或无有效文件",
                        "skippedAssets", skippedAssets);
            }

            // 5. 构建分析提示词（含素材索引）
            StringBuilder promptBuilder = new StringBuilder(analysisPrompt);
            promptBuilder.append("\n\n以下是待分析的素材列表：\n");
            for (Map<String, Object> meta : analyzedAssets) {
                promptBuilder.append(String.format("- #%s: %s (%s)\n",
                        meta.get("index"), meta.get("name"), meta.get("mimeType")));
            }

            // 6. 解析 LLM Provider
            String effectiveProviderId = resolveVisionLlmProviderId(llmProviderId);
            if (!StringUtils.hasText(effectiveProviderId)) {
                return Map.of("success", false, "error",
                        "未配置视觉分析 LLM Provider，请通过 llmProviderId 参数指定或配置 actionow.agent.vision-llm-provider-id");
            }

            ChatModel visionModel = chatModelFactory.createModel(effectiveProviderId);

            // 7. 构造 Prompt 并调用 LLM
            List<Message> messages = new ArrayList<>();
            String effectiveSystemPrompt = StringUtils.hasText(systemPrompt) ? systemPrompt
                    : "你是一位专业的视觉分析助手。请仔细观察用户提供的素材，按照用户的要求进行详细分析。" +
                      "如果有多个素材，请使用素材编号（如 #1、#2）逐一分析或对比。";
            messages.add(new SystemMessage(effectiveSystemPrompt));
            messages.add(UserMessage.builder()
                    .text(promptBuilder.toString())
                    .media(mediaList)
                    .build());

            ChatResponse chatResponse = visionModel.call(new Prompt(messages));
            String analysisResult = null;
            if (chatResponse != null && chatResponse.getResult() != null
                    && chatResponse.getResult().getOutput() != null) {
                analysisResult = chatResponse.getResult().getOutput().getText();
            }
            if (!StringUtils.hasText(analysisResult)) {
                return Map.of("success", false, "error", "LLM 返回空结果，请检查 Provider 是否支持多模态输入");
            }

            // 8. 记录 billing
            if (sessionId != null) {
                billingService.recordAiToolUsage(sessionId, "analyze_assets", mediaList.size() * 50L);
            }

            log.info("素材分析完成: analyzedCount={}, skippedCount={}, sessionId={}",
                    analyzedAssets.size(), skippedAssets.size(), sessionId);

            // 9. 返回结果
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("analyzedCount", analyzedAssets.size());
            result.put("skippedCount", skippedAssets.size());
            result.put("analysis", analysisResult);
            result.put("analyzedAssets", analyzedAssets);
            if (!skippedAssets.isEmpty()) {
                result.put("skippedAssets", skippedAssets);
            }
            return result;

        } catch (Exception e) {
            log.error("素材分析异常: assetIds={}", assetIds, e);
            return Map.of("success", false, "error", "素材分析异常: " + e.getMessage());
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 解析视觉分析 LLM Provider ID
     * 优先级：参数传入 > @Value 配置 > Universal Agent 的 Provider
     */
    private String resolveVisionLlmProviderId(String paramProviderId) {
        if (StringUtils.hasText(paramProviderId)) {
            return paramProviderId;
        }
        if (StringUtils.hasText(visionLlmProviderId)) {
            return visionLlmProviderId;
        }
        // Fallback: 使用 Universal Agent 的 LLM Provider
        try {
            AgentConfigEntity config = agentConfigService.getEntityByAgentType(AgentType.UNIVERSAL.getCode());
            if (config != null && config.getLlmProviderId() != null) {
                return config.getLlmProviderId();
            }
        } catch (Exception e) {
            log.warn("获取 Universal Agent LLM Provider 失败", e);
        }
        return null;
    }

    /**
     * 下载素材字节（最大 20MB，超时 30s）
     *
     * @return 文件字节，失败返回 null
     */
    private byte[] downloadAssetBytes(String fileUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(fileUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                log.warn("素材下载失败: url={}, status={}", fileUrl, response.statusCode());
                return null;
            }
            if (response.body().length > MAX_DOWNLOAD_BYTES) {
                log.warn("素材文件过大，跳过: url={}, size={}", fileUrl, response.body().length);
                return null;
            }
            return response.body();
        } catch (Exception e) {
            log.warn("素材下载异常: url={}, error={}", fileUrl, e.getMessage());
            return null;
        }
    }

    private void emitBatchProgress(String sessionId, String phase, String label,
                                   double progress, int done, int total) {
        if (sessionId == null) return;
        try {
            Map<String, Object> details = new HashMap<>();
            details.put("done", done);
            details.put("total", total);
            streamBridge.publish(sessionId, AgentStreamEvent.status(phase, label, progress, details));
        } catch (Exception e) {
            log.debug("emit batch progress failed phase={}: {}", phase, e.getMessage());
        }
    }

    @Tool(name = "batch_delete_assets", description = "批量软删除素材（删除入回收站，可恢复，仅软删，不暴露永久删除/清空入口）。" +
            "工具内部强制弹 HITL 确认对话框，用户拒绝/超时则不执行任何删除并返回 cancelled=true。" +
            "建议先用 query_assets/get_entity_assets 核对待删除 ID。返回 {success, deleted, failed, cancelled}。")
    @AgentToolSpec(
            displayName = "批量删除素材",
            summary = "对一组素材 ID 执行软删除，删除前必须经用户在前端确认。",
            purpose = "在用户明确请求删除若干素材时使用；底层走逻辑删除，可在回收站恢复。",
            actionType = ToolActionType.WRITE,
            tags = {"asset", "batch", "destructive"},
            usageNotes = {"破坏性操作，工具内部已强制 HITL 确认；不要再额外调用 ask_user_confirm",
                    "软删除可恢复，永久删除/回收站清空请走运维通道"},
            errorCases = {"assetIdsJson 解析失败 / 为空时返回错误",
                    "无活跃会话时返回错误",
                    "用户拒绝或超时返回 cancelled=true",
                    "缺少工作空间上下文会返回错误"},
            exampleInput = "{\"assetIdsJson\":\"[\\\"asset_1\\\",\\\"asset_2\\\"]\"}",
            exampleOutput = "{\"success\":true,\"deleted\":[\"asset_1\",\"asset_2\"],\"failed\":[],\"cancelled\":false}"
    )
    @AgentToolOutput(
            description = "返回成功/失败/取消的汇总。",
            example = "{\"success\":true,\"deleted\":[\"asset_1\"],\"failed\":[],\"cancelled\":false}"
    )
    public Map<String, Object> batchDeleteAssets(
            @ToolParam(description = "素材ID JSON 数组，例: [\"asset_1\",\"asset_2\"]") String assetIdsJson) {

        if (assetIdsJson == null || assetIdsJson.isBlank()) {
            return Map.of("success", false, "error", "素材ID列表不能为空");
        }
        UserContext userContext = UserContextHolder.getContext();
        String workspaceId = userContext != null ? userContext.getWorkspaceId() : null;
        String userId = userContext != null ? userContext.getUserId() : "system";
        if (workspaceId == null) {
            return Map.of("success", false, "error", "缺少工作空间上下文");
        }

        List<String> ids;
        try {
            List<String> raw = OBJECT_MAPPER.readValue(assetIdsJson, new TypeReference<List<String>>() {});
            ids = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String id : raw) {
                if (id == null || id.isBlank()) continue;
                String trimmed = id.trim();
                if (seen.add(trimmed)) ids.add(trimmed);
            }
        } catch (Exception e) {
            return Map.of("success", false, "error", "ID 数组解析失败（期望 [\"id1\",\"id2\"]）: " + e.getMessage());
        }
        if (ids.isEmpty()) {
            return Map.of("success", false, "error", "素材ID列表不能为空");
        }

        String question = "确认删除 " + ids.size() + " 个素材？此操作可在回收站恢复。";
        HitlConfirmationHelper.ConfirmationResult cr = hitlConfirmationHelper.confirmDestructiveAction(question, 120);

        return switch (cr) {
            case HitlConfirmationHelper.ConfirmationResult.Confirmed c -> performAssetDelete(ids, workspaceId, userId);
            case HitlConfirmationHelper.ConfirmationResult.Declined d -> assetCancelled(d.askId(), d.reason());
            case HitlConfirmationHelper.ConfirmationResult.TimedOut t -> assetCancelled(t.askId(), "TIMEOUT");
            case HitlConfirmationHelper.ConfirmationResult.NoSession n -> Map.of(
                    "success", false,
                    "error", "批量删除必须在 chat / mission 上下文内调用（无活跃会话无法弹 HITL）");
            case HitlConfirmationHelper.ConfirmationResult.Failed f -> Map.of(
                    "success", false, "error", "HITL 确认失败: " + f.error());
        };
    }

    private Map<String, Object> assetCancelled(String askId, String reason) {
        Map<String, Object> data = new HashMap<>();
        data.put("success", true);
        data.put("cancelled", true);
        data.put("reason", reason);
        if (askId != null) data.put("askId", askId);
        data.put("deleted", List.of());
        data.put("failed", List.of());
        data.put("message", "用户取消批量删除（" + reason + "），未执行任何删除");
        return data;
    }

    private Map<String, Object> performAssetDelete(List<String> ids, String workspaceId, String userId) {
        List<String> deleted = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (String id : ids) {
            try {
                Result<Void> r = assetFeignClient.deleteAsset(workspaceId, userId, id);
                if (r != null && r.isSuccess()) {
                    deleted.add(id);
                } else {
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("id", id);
                    entry.put("reason", r != null ? r.getMessage() : "Feign 返回 null");
                    failed.add(entry);
                }
            } catch (Exception e) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("id", id);
                entry.put("reason", e.getMessage());
                failed.add(entry);
                log.warn("批量删除素材单项失败: id={}", id, e);
            }
        }
        Map<String, Object> data = new HashMap<>();
        data.put("success", true);
        data.put("deleted", deleted);
        data.put("failed", failed);
        data.put("cancelled", false);
        data.put("message", "已删除 " + deleted.size() + " / " + ids.size()
                + (failed.isEmpty() ? "" : "，失败 " + failed.size() + " 项"));
        return data;
    }
}
