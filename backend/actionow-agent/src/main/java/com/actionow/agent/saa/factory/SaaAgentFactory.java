package com.actionow.agent.saa.factory;

import com.alibaba.cloud.ai.graph.agent.Agent;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.actionow.agent.config.AgentRuntimeConfigService;
import com.actionow.agent.config.SaaAgentProperties;
import com.actionow.agent.config.entity.AgentConfigEntity;
import com.actionow.agent.config.service.AgentConfigService;
import com.actionow.agent.constant.AgentType;
import com.actionow.agent.entity.AgentSkillEntity;
import com.actionow.agent.mapper.AgentSkillMapper;
import com.actionow.agent.registry.DatabaseSkillRegistry;
import com.actionow.agent.resolution.dto.ResolvedAgentProfile;
import com.actionow.agent.resolution.service.AgentResolutionService;
import com.actionow.agent.interaction.AgentStreamBridge;
import com.actionow.agent.mission.ControlToolGuardCallback;
import com.actionow.agent.mission.MissionStepControlState;
import com.actionow.agent.saa.hook.FilteredSkillRegistryAdapter;
import com.actionow.agent.saa.hook.SelfDialogueGuardHook;
import com.actionow.agent.saa.hook.StatusEmittingHook;
import com.actionow.agent.saa.hook.SystemMessageCoalesceHook;
import com.actionow.agent.saa.hook.ToolDeduplicationHook;
import com.actionow.agent.scriptwriting.tools.StructuredOutputTools;
import com.actionow.agent.tool.registry.ProjectToolRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * SAA Agent 工厂
 * 替代 v1 的 DynamicAgentFactory，使用 Spring AI Alibaba Agent Framework
 *
 * ## 核心架构（Skill 化）
 * - 1 个 UniversalAgent + SkillsAgentHook，替代原 9 个独立专家 ReactAgent
 * - SkillsAgentHook 按需 lazy 加载专家技能，大幅减少 Token 消耗
 * - Skill 定义由 DatabaseSkillRegistry 管理，管理员可通过 CRUD API 热更新
 *
 * @author Actionow
 */
@Slf4j
@Component
@DependsOn("projectToolScanner")
@RequiredArgsConstructor
public class SaaAgentFactory {

    private final AgentConfigService agentConfigService;
    private final ProjectToolRegistry toolRegistry;
    private final SaaChatModelFactory chatModelFactory;
    private final SaaAgentProperties saaProperties;
    private final AgentRuntimeConfigService runtimeConfig;
    private final DatabaseSkillRegistry skillRegistry;
    private final StructuredOutputTools structuredOutputTools;
    private final AgentSkillMapper skillMapper;
    private final AgentResolutionService agentResolutionService;
    private final AgentStreamBridge streamBridge;
    private final MissionStepControlState missionStepControlState;
    private final com.actionow.agent.metrics.AgentMetrics agentMetrics;

    private final AtomicLong lastBuildTime = new AtomicLong(0);

    private volatile long lastVersionCheckMs = 0L;
    private static final long VERSION_CHECK_INTERVAL_MS = 30_000L;

    /**
     * 原子缓存快照 — 2 个字段一次性替换，消除重建期间的可见性窗口。
     * 外部读者总是看到完整一致的旧快照或新快照，不会看到半成品状态。
     */
    private record AgentCacheSnapshot(
            SupervisorAgent supervisor,
            List<Agent> expertAgents) {

        static AgentCacheSnapshot empty() {
            return new AgentCacheSnapshot(null, new ArrayList<>());
        }
    }

    private volatile AgentCacheSnapshot cache = AgentCacheSnapshot.empty();

    private final Object rebuildLock = new Object();

    @PostConstruct
    public void init() {
        log.info("SaaAgentFactory initialized - using Spring AI Alibaba SupervisorAgent + ReactAgent");
    }

    /**
     * 检查是否需要重建 Agent
     */
    public boolean needsRebuild() {
        return agentConfigService.hasHotUpdates();
    }

    /**
     * 时间门控版本检查（兜底 pub/sub 消息丢失场景）
     * 每 30 秒最多向 Redis 发起一次轮询
     */
    private boolean timedNeedsRebuild() {
        long now = System.currentTimeMillis();
        if (now - lastVersionCheckMs > VERSION_CHECK_INTERVAL_MS) {
            lastVersionCheckMs = now;
            return agentConfigService.hasHotUpdates();
        }
        return false;
    }

    /**
     * 获取协调器 Agent（支持热更新）
     *
     * ## 双重版本检查机制
     */
    public SupervisorAgent getCoordinatorAgent() {
        if (cache.supervisor() == null || timedNeedsRebuild()) {
            synchronized (rebuildLock) {
                if (cache.supervisor() == null || timedNeedsRebuild()) {
                    long versionBeforeRebuild = agentConfigService.getCurrentRemoteVersion();

                    chatModelFactory.evictAllCache();
                    cache = buildNewSnapshot();
                    lastBuildTime.set(System.currentTimeMillis());

                    long versionAfterRebuild = agentConfigService.getCurrentRemoteVersion();
                    if (versionAfterRebuild > versionBeforeRebuild) {
                        log.warn("检测到重建期间配置发生变更 (version: {} -> {})，下次请求将再次重建",
                                versionBeforeRebuild, versionAfterRebuild);
                    } else {
                        agentConfigService.syncCacheVersion();
                    }

                    log.info("SupervisorAgent built/rebuilt at: {}", lastBuildTime.get());
                }
            }
        }
        return cache.supervisor();
    }

    /**
     * 强制重建所有 Agent
     */
    public void forceRebuild() {        synchronized (rebuildLock) {
            agentConfigService.refreshCache();
            chatModelFactory.evictAllCache();
            cache = buildNewSnapshot();
            lastBuildTime.set(System.currentTimeMillis());
            agentConfigService.syncCacheVersion();
            log.info("Forced rebuild of all agents at: {}", lastBuildTime.get());
        }
    }

    /**
     * 使缓存失效（用于 Redis 订阅通知）
     */
    public void invalidateCache() {
        synchronized (rebuildLock) {
            cache = AgentCacheSnapshot.empty();
            log.info("Agent 缓存已失效，下次请求将触发重建");
        }
    }

    /**
     * 清除所有缓存
     */
    public void clearAllCache() {
        synchronized (rebuildLock) {
            chatModelFactory.evictAllCache();
            cache = AgentCacheSnapshot.empty();
            log.info("All agent caches cleared");
        }
    }

    /**
     * 根据解析结果构建请求作用域 Agent。
     */
    public SupervisorAgent buildResolvedAgent(ResolvedAgentProfile profile) {
        return buildResolvedAgent(profile, null);
    }

    public SupervisorAgent buildResolvedAgent(ResolvedAgentProfile profile, Collection<String> allowedToolIds) {
        if (profile == null) {
            return getCoordinatorAgent();
        }

        if (Boolean.TRUE.equals(profile.getCoordinator())) {
            return buildCoordinatorAgent(profile, allowedToolIds);
        }

        ReactAgent worker = buildWorkerAgent(profile, allowedToolIds);
        return SupervisorAgent.builder()
                .name(profile.getAgentType().toLowerCase(Locale.ROOT) + "-supervisor")
                .model(getModelForAgentType(profile.getAgentType()))
                .systemPrompt(resolvePromptOrDefault(profile.getAgentType(), profile.getResolvedPrompt()))
                .mainAgent(worker)
                .subAgents(List.of(worker))
                .build();
    }

    // ==================== 构建方法 ====================

    /**
     * 构建全新的原子缓存快照（必须在 synchronized 块内调用）
     *
     * <p>Skill 化架构：构建单个 UniversalAgent + SkillsAgentHook，
     * 替代原来的 9 个独立 ReactAgent，按需 lazy 加载专家技能。
     */
    private AgentCacheSnapshot buildNewSnapshot() {
        log.info("Building UniversalAgent with SkillsAgentHook (Skill 化架构)");

        // 1. 从 DB 构建 groupedTools Map：skillName → List<ToolCallback>
        Map<String, List<ToolCallback>> groupedTools = buildGroupedToolsFromSkills();

        // 2. UniversalAgent 常驻工具（output_structured_result）
        ToolCallback[] universalTools = MethodToolCallbackProvider.builder()
                .toolObjects(structuredOutputTools)
                .build()
                .getToolCallbacks();

        // 3. 构建 SkillsAgentHook
        SkillsAgentHook hook = SkillsAgentHook.builder()
                .skillRegistry(skillRegistry)
                .autoReload(false)
                .groupedTools(groupedTools)
                .build();

        log.info("SkillsAgentHook built with {} skills, universalTools={}", hook.getSkillCount(), universalTools.length);

        // 3b. ModelCallLimitHook — 限制 ReAct 最大 LLM 调用次数（对应 maxIterations 配置）
        ModelCallLimitHook modelCallLimitHook = ModelCallLimitHook.builder()
                .threadLimit(runtimeConfig.getMaxIterations())
                .exitBehavior(ModelCallLimitHook.ExitBehavior.END)
                .build();

        // 4. 构建 UniversalAgent
        String universalPrompt = getPromptForAgent(AgentType.UNIVERSAL);
        ChatModel universalModel = getModelForAgent(AgentType.UNIVERSAL);

        ReactAgent universalAgent = ReactAgent.builder()
                .name("universal-agent")
                .model(universalModel)
                .systemPrompt(universalPrompt)
                .tools(universalTools)
                .hooks(hook, new ToolDeduplicationHook(),
                        new SystemMessageCoalesceHook(),
                        new SelfDialogueGuardHook(),
                        new StatusEmittingHook(streamBridge),
                        modelCallLimitHook)
                .build();

        List<Agent> expertAgents = List.of(universalAgent);
        log.info("UniversalAgent built successfully");

        // 5. COORDINATOR（含 UniversalAgent 子 Agent）
        String coordinatorPrompt = getPromptForAgent(AgentType.COORDINATOR);
        ChatModel coordinatorModel = getModelForAgent(AgentType.COORDINATOR);

        SupervisorAgent supervisor = SupervisorAgent.builder()
                .name("kaka-coordinator")
                .model(coordinatorModel)
                .systemPrompt(coordinatorPrompt)
                .mainAgent(universalAgent)
                .subAgents(expertAgents)
                .build();

        log.info("SupervisorAgent built with {} subAgents (UniversalAgent)", expertAgents.size());
        return new AgentCacheSnapshot(supervisor, expertAgents);
    }

    /**
     * 从 DB Skill 定义构建 groupedTools Map。
     *
     * <p>Skill 中的 groupedToolIds 按精确 toolId 解析为单个 ToolCallback，
     * 不再按 Bean 整体暴露所有 @Tool 方法，避免 Skill 实际可见工具超出配置范围。
     */
    private Map<String, List<ToolCallback>> buildGroupedToolsFromSkills() {
        Map<String, List<ToolCallback>> result = new LinkedHashMap<>();

        for (AgentSkillEntity skill : skillMapper.selectAllEnabled()) {
            List<String> toolIds = skill.getGroupedToolIds();
            if (toolIds == null || toolIds.isEmpty()) {
                log.debug("Skill {} 没有配置 groupedToolIds，跳过", skill.getName());
                continue;
            }

            Set<String> seenToolNames = new LinkedHashSet<>();
            List<ToolCallback> callbacks = new ArrayList<>();

            for (String toolId : toolIds) {
                toolRegistry.getProjectToolCallback(toolId).ifPresentOrElse(callback -> {
                    String callbackName = callback.getToolDefinition().name();
                    if (seenToolNames.add(callbackName)) {
                        callbacks.add(wrapIfControlTool(callback));
                        log.debug("Skill {} 注册工具: {} ({})",
                                skill.getName(), toolId, callbackName);
                    }
                }, () -> log.warn("Skill {} 的 toolId={} 未找到注册的工具回调", skill.getName(), toolId));
            }

            if (!callbacks.isEmpty()) {
                result.put(skill.getName(), callbacks);
                log.info("Skill {} 配置了 {} 个工具", skill.getName(), callbacks.size());
            }
        }

        log.info("buildGroupedToolsFromSkills 完成，共 {} 个 Skill 有工具配置", result.size());
        return result;
    }

    /**
     * 按名称列表构建 groupedTools（按需加载用）
     *
     * @param skillNames  需要的 Skill 名称列表
     * @param workspaceId 当前工作空间 ID
     */
    private Map<String, List<ToolCallback>> buildGroupedToolsForSkills(
            List<String> skillNames, String workspaceId) {
        return buildGroupedToolsForSkills(skillNames, null, workspaceId);
    }

    private Map<String, List<ToolCallback>> buildGroupedToolsForSkills(
            List<String> skillNames, Collection<String> allowedToolIds, String workspaceId) {
        Set<String> nameSet = new HashSet<>(skillNames);
        Set<String> allowedToolIdSet = allowedToolIds != null ? new LinkedHashSet<>(allowedToolIds) : null;
        Map<String, List<ToolCallback>> result = new LinkedHashMap<>();

        Map<String, AgentSkillEntity> snapshot = skillRegistry.getCacheSnapshotForWorkspace(workspaceId);
        for (Map.Entry<String, AgentSkillEntity> entry : snapshot.entrySet()) {
            if (!nameSet.contains(entry.getKey())) continue;
            AgentSkillEntity skill = entry.getValue();
            List<String> toolIds = skill.getGroupedToolIds();
            if (toolIds == null || toolIds.isEmpty()) {
                log.debug("Skill {} 没有配置 groupedToolIds，跳过", skill.getName());
                continue;
            }

            Set<String> seenToolNames = new LinkedHashSet<>();
            List<ToolCallback> callbacks = new ArrayList<>();

            for (String toolId : toolIds) {
                String normalizedToolId = toolRegistry.getProjectTool(toolId)
                        .map(tool -> tool.getToolId())
                        .orElse(toolId);
                if (allowedToolIdSet != null && !allowedToolIdSet.contains(normalizedToolId)) {
                    continue;
                }
                toolRegistry.getProjectToolCallback(toolId).ifPresentOrElse(callback -> {
                    String callbackName = callback.getToolDefinition().name();
                    if (seenToolNames.add(callbackName)) {
                        callbacks.add(wrapIfControlTool(callback));
                    }
                }, () -> log.warn("Skill {} 的 toolId={} 未找到注册的工具回调", skill.getName(), toolId));
            }

            if (!callbacks.isEmpty()) {
                result.put(skill.getName(), callbacks);
                log.debug("Scoped Skill {} 配置了 {} 个工具", skill.getName(), callbacks.size());
            }
        }

        log.debug("buildGroupedToolsForSkills 完成，skillNames={}, 结果 {} 个 Skill 有工具",
                skillNames, result.size());
        return result;
    }

    private SupervisorAgent buildCoordinatorAgent(ResolvedAgentProfile coordinatorProfile, Collection<String> allowedToolIds) {
        List<ReactAgent> workerAgents = new ArrayList<>();

        for (String subAgentType : coordinatorProfile.getSubAgentTypes()) {
            try {
                ResolvedAgentProfile subProfile = agentResolutionService.resolve(
                        subAgentType,
                        coordinatorProfile.getWorkspaceId(),
                        coordinatorProfile.getUserId(),
                        null
                );
                if (Boolean.TRUE.equals(subProfile.getCoordinator())) {
                    log.warn("暂不支持嵌套协调者 Agent，已跳过: parent={}, child={}",
                            coordinatorProfile.getAgentType(), subAgentType);
                    continue;
                }
                workerAgents.add(buildWorkerAgent(subProfile, allowedToolIds));
            } catch (Exception e) {
                log.warn("构建子 Agent 失败，已跳过: parent={}, child={}, error={}",
                        coordinatorProfile.getAgentType(), subAgentType, e.getMessage());
            }
        }

        if (workerAgents.isEmpty()) {
            ResolvedAgentProfile fallbackProfile = agentResolutionService.resolve(
                    AgentType.UNIVERSAL.getCode(),
                    coordinatorProfile.getWorkspaceId(),
                    coordinatorProfile.getUserId(),
                    coordinatorProfile.getRequestedSkillNames()
            );
            workerAgents = List.of(buildWorkerAgent(fallbackProfile, allowedToolIds));
        }

        ReactAgent mainAgent = workerAgents.get(0);
        return SupervisorAgent.builder()
                .name(coordinatorProfile.getAgentType().toLowerCase(Locale.ROOT) + "-coordinator")
                .model(getModelForAgentType(coordinatorProfile.getAgentType()))
                .systemPrompt(resolvePromptOrDefault(coordinatorProfile.getAgentType(), coordinatorProfile.getResolvedPrompt()))
                .mainAgent(mainAgent)
                .subAgents(new ArrayList<>(workerAgents))
                .build();
    }

    private ReactAgent buildWorkerAgent(ResolvedAgentProfile profile, Collection<String> allowedToolIds) {
        Collection<String> effectiveAllowedToolIds = allowedToolIds != null ? allowedToolIds : profile.getResolvedToolIds();
        // 技能工具由 groupedToolIds 自治，不受 allowedToolIds 过滤；
        // allowedToolIds 仅约束 buildDirectProjectCallbacks 中的直接工具
        Map<String, List<ToolCallback>> groupedTools = profile.getResolvedSkillNames().isEmpty()
                ? Map.of()
                : buildGroupedToolsForSkills(
                        profile.getResolvedSkillNames(),
                        profile.getWorkspaceId());

        // 常驻工具（structured output 等框架级工具）
        ToolCallback[] universalTools = MethodToolCallbackProvider.builder()
                .toolObjects(structuredOutputTools)
                .build()
                .getToolCallbacks();

        // Direct project callbacks（Mission 控制工具等不属于任何 Skill 的回调）
        // 必须合并到 staticTools，因为 SAA AgentToolNode.resolve() 只搜索
        // staticTools / configMetadata / toolCallbackResolver，不搜索 dynamicToolCallbacks。
        // 若同一工具同时出现在 Skill 的 groupedToolIds 中（例如 mission_expert Skill
        // 把 mission_delegate* 也列为技能工具），必须从 groupedTools 中剔除，
        // 否则 Spring AI ToolCallingChatOptions.validateToolCallbacks 会抛
        // "Multiple tools with the same name"。
        List<ToolCallback> directCallbacks = buildDirectProjectCallbacks(effectiveAllowedToolIds, groupedTools);
        removeCallbacksFromGroupedTools(groupedTools, directCallbacks);
        ToolCallback[] staticTools = mergeStaticTools(universalTools, directCallbacks);

        FilteredSkillRegistryAdapter filteredRegistry =
                new FilteredSkillRegistryAdapter(skillRegistry, profile.getResolvedSkillNames(), profile.getWorkspaceId());

        SkillsAgentHook hook = SkillsAgentHook.builder()
                .skillRegistry(filteredRegistry)
                .autoReload(false)
                .groupedTools(groupedTools)
                .build();

        // 不变式：direct callbacks 只走 staticTools 路径。
        // AgentToolNode.resolve() 只搜索 staticTools / configMetadata / toolCallbackResolver，
        // 不搜索 dynamicToolCallbacks，所以 staticTools 已足够。再额外注入
        // dynamicToolCallbacks 会与 staticTools 重复，触发 Spring AI
        // ToolCallingChatOptions.validateToolCallbacks 抛 "Multiple tools with the same name"。

        ModelCallLimitHook modelCallLimitHook = ModelCallLimitHook.builder()
                .threadLimit(runtimeConfig.getMaxIterations())
                .exitBehavior(ModelCallLimitHook.ExitBehavior.END)
                .build();

        return ReactAgent.builder()
                .name(profile.getAgentType().toLowerCase(Locale.ROOT) + "-agent")
                .model(getModelForAgentType(profile.getAgentType()))
                .systemPrompt(resolvePromptOrDefault(profile.getAgentType(), profile.getResolvedPrompt()))
                .tools(staticTools)
                .hooks(hook, new ToolDeduplicationHook(),
                        new SystemMessageCoalesceHook(),
                        new SelfDialogueGuardHook(),
                        new StatusEmittingHook(streamBridge),
                        modelCallLimitHook)
                .build();
    }

    /**
     * 若 groupedTools 中包含与 direct tool 同名的 callback（例如 mission_expert Skill
     * 把 mission_delegate* 也列为技能工具），需要在合入 staticTools 之前从 groupedTools 剔除，
     * 否则 Spring AI 校验工具列表时会抛 "Multiple tools with the same name"。
     */
    private void removeCallbacksFromGroupedTools(
            Map<String, List<ToolCallback>> groupedTools,
            List<ToolCallback> directCallbacks) {
        if (groupedTools == null || groupedTools.isEmpty()
                || directCallbacks == null || directCallbacks.isEmpty()) {
            return;
        }
        Set<String> directNames = directCallbacks.stream()
                .map(cb -> cb.getToolDefinition().name())
                .collect(Collectors.toSet());
        for (List<ToolCallback> skillTools : groupedTools.values()) {
            skillTools.removeIf(cb -> directNames.contains(cb.getToolDefinition().name()));
        }
    }

    /**
     * 控制工具（complete_mission / fail_mission / delegate_*）按 Mission Step 维度门控，
     * 避免单步内 LLM 反复 emit 同名决策工具导致 BatchJob / Mission 状态机被重复推进。
     * 非控制工具原样返回。
     */
    private ToolCallback wrapIfControlTool(ToolCallback callback) {
        if (callback == null) return null;
        String name = callback.getToolDefinition().name();
        if (ControlToolGuardCallback.CONTROL_TOOL_NAMES.contains(name)) {
            return new ControlToolGuardCallback(callback, missionStepControlState, agentMetrics);
        }
        return callback;
    }

    private ToolCallback[] mergeStaticTools(ToolCallback[] universalTools, List<ToolCallback> directCallbacks) {
        List<ToolCallback> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        if (universalTools != null) {
            for (ToolCallback tool : universalTools) {
                if (tool != null && seen.add(tool.getToolDefinition().name())) {
                    merged.add(tool);
                }
            }
        }

        if (directCallbacks != null) {
            for (ToolCallback tool : directCallbacks) {
                if (tool != null && seen.add(tool.getToolDefinition().name())) {
                    merged.add(tool);
                }
            }
        }

        return merged.toArray(ToolCallback[]::new);
    }

    private List<ToolCallback> buildDirectProjectCallbacks(
            Collection<String> allowedToolIds,
            Map<String, List<ToolCallback>> groupedTools) {
        if (allowedToolIds == null || allowedToolIds.isEmpty()) {
            return List.of();
        }

        Set<String> groupedCallbackNames = groupedTools.values().stream()
                .flatMap(List::stream)
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());

        List<ToolCallback> callbacks = new ArrayList<>();
        Set<String> seenNames = new LinkedHashSet<>();
        for (String toolId : allowedToolIds) {
            try {
                toolRegistry.getProjectTool(toolId).ifPresentOrElse(tool -> {
                    String callbackName = tool.getCallbackName() != null ? tool.getCallbackName() : tool.getToolName();
                    if (callbackName == null) {
                        log.debug("buildDirectProjectCallbacks: skip {} (null callbackName)", toolId);
                        return;
                    }
                    // 仅当工具不是直接工具（@ChatDirectTool / @MissionDirectTool）且已被 Skill 覆盖时才跳过。
                    // 直接工具必须出现在 staticTools 中，否则模型首步工具调用会触发
                    // "No ToolCallback found"（SkillsAgentHook 需 read_skill 后才注入）。
                    boolean isDirectTool = tool.getDirectToolMode() != null;
                    if (!isDirectTool && groupedCallbackNames.contains(callbackName)) {
                        return; // skill tool, expected skip
                    }
                    if (!seenNames.add(callbackName)) {
                        log.debug("buildDirectProjectCallbacks: skip {} (duplicate callbackName={})", toolId, callbackName);
                        return;
                    }
                    toolRegistry.getProjectToolCallback(toolId).ifPresentOrElse(
                            callback -> callbacks.add(wrapIfControlTool(callback)),
                            () -> log.warn("buildDirectProjectCallbacks: callback not resolved for toolId={}, callbackName={}", toolId, callbackName)
                    );
                }, () -> log.debug("buildDirectProjectCallbacks: toolId={} not found in registry", toolId));
            } catch (Exception e) {
                log.warn("buildDirectProjectCallbacks: error resolving toolId={}: {}", toolId, e.getMessage());
            }
        }
        log.debug("buildDirectProjectCallbacks: built {} direct callback(s) from {} allowedToolIds", callbacks.size(), allowedToolIds.size());
        return callbacks;
    }

    // ==================== 提示词和模型 ====================

    private String getPromptForAgent(AgentType agentType) {
        try {
            String prompt = agentConfigService.getResolvedPrompt(agentType.getCode());
            if (prompt != null && !prompt.isBlank()) {
                return prompt;
            }
        } catch (Exception e) {
            log.warn("Failed to get prompt for agent {}: {}", agentType, e.getMessage());
        }
        return getDefaultPrompt(agentType);
    }

    private ChatModel getModelForAgent(AgentType agentType) {
        try {
            AgentConfigEntity config = agentConfigService.getEntityByAgentType(agentType.getCode());
            if (config != null && config.getLlmProviderId() != null) {
                return chatModelFactory.createModel(config.getLlmProviderId());
            }
            log.warn("Agent {} 未配置 LLM Provider", agentType);
        } catch (Exception e) {
            log.warn("获取 Agent {} 的模型配置失败: {}", agentType, e.getMessage());
        }
        return chatModelFactory.createUnavailableModel(agentType.getCode(), "未配置 LLM Provider");
    }

    private String getPromptForAgentType(String agentType) {
        try {
            String prompt = agentConfigService.getResolvedPrompt(agentType);
            if (prompt != null && !prompt.isBlank()) {
                return prompt;
            }
        } catch (Exception e) {
            log.warn("Failed to get prompt for agentType {}: {}", agentType, e.getMessage());
        }

        AgentType builtInType = AgentType.fromCodeStrict(agentType);
        if (builtInType != null) {
            return getDefaultPrompt(builtInType);
        }
        return String.format("你是 %s，ActioNow 的专业创作助手。请专业、创意地完成用户的请求。", agentType);
    }

    private ChatModel getModelForAgentType(String agentType) {
        try {
            AgentConfigEntity config = agentConfigService.getEntityByAgentType(agentType);
            if (config != null && config.getLlmProviderId() != null) {
                return chatModelFactory.createModel(config.getLlmProviderId());
            }
            log.warn("Agent {} 未配置 LLM Provider", agentType);
        } catch (Exception e) {
            log.warn("获取 Agent {} 的模型配置失败: {}", agentType, e.getMessage());
        }
        return chatModelFactory.createUnavailableModel(agentType, "未配置 LLM Provider");
    }

    private String resolvePromptOrDefault(String agentType, String prompt) {
        String base = (prompt != null && !prompt.isBlank()) ? prompt : getPromptForAgentType(agentType);
        return appendSafetyRails(base);
    }

    /**
     * 通用安全护栏 / 人机交互公约。
     *
     * <p>由 {@link #resolvePromptOrDefault} 统一追加到所有 agent 的系统提示词末尾，
     * 无论 prompt 来源于：默认常量、DB 配置、Nacos 下发；避免在各处 prompt 里
     * 各自拷贝同一段约束造成漂移。
     *
     * <p>修改本段需同时评估对 COORDINATOR / UNIVERSAL / 各专家 agent 的整体影响。
     */
    private static String appendSafetyRails(String base) {
        if (base == null) return SAFETY_RAILS;
        if (base.contains(SAFETY_RAILS_MARKER)) return base;
        String sep = base.endsWith("\n") ? "\n" : "\n\n";
        return base + sep + SAFETY_RAILS;
    }

    /** 幂等标记：用于避免重复追加（例如上游已注入过）。 */
    private static final String SAFETY_RAILS_MARKER = "<!-- actionow:safety-rails:v1 -->";

    private static final String SAFETY_RAILS = """
            <!-- actionow:safety-rails:v1 -->
            # 人机交互铁律（全局护栏 · 必须遵守）
            - 【禁止】在输出中扮演或模拟用户。禁止写 "好的，我们选择 A"、"确认。"、"就这么定"、"我选 B" 等任何代表用户做决定的句式
            - 【禁止】在自然语言里抛出选择题后继续替用户回答、自行推进流程
            - 需要用户做决策 / 确认 / 补充输入时，必须调用 HITL 工具：
              · `ask_user_choice`：单选
              · `ask_user_confirm`：是/否确认
              · `ask_user_multi_choice`：多选
              · `ask_user_text`：文本输入
              · `ask_user_number`：数字输入
            - 调用 HITL 工具后**停止生成**，等待工具返回用户真实答复；拿到答复再继续
            - 如果决定直接执行、不请求确认，那就直接执行，不要先用一句话提问再自己回答
            """;

    private String getDefaultPrompt(AgentType agentType) {
        if (agentType == AgentType.COORDINATOR) {
            return DEFAULT_COORDINATOR_PROMPT;
        }
        if (agentType == AgentType.UNIVERSAL) {
            return DEFAULT_UNIVERSAL_PROMPT;
        }
        return String.format("你是 %s，ActioNow 的专业创作助手。请专业、创意地完成用户的请求。",
                agentType.getName());
    }

    // ==================== 默认提示词常量 ====================

    /**
     * 协调者 Agent 默认提示词
     * 涵盖: 角色定义、实体层级模型、作用域说明、任务转发策略
     */
    private static final String DEFAULT_COORDINATOR_PROMPT = """
            # 角色
            你是 Kaka（咔咔），ActioNow 平台的首席剧本创编助手。
            你的职责是判断当前请求应该直接回答还是委派给 universal-agent（通用创作专家）执行。

            # 处理原则
            - 闲聊、解释、创意讨论、方案比较：直接回答
            - 涉及查询、创建、修改、批量处理、素材生成等执行型任务：转交给 universal-agent
            - 仅在完全无法推断用户意图时才简短澄清（例如：用户只说"帮我做点什么"但无任何上下文）
            - 当上下文（剧本名、章节标题、已知作品等）已提供足够线索时，优先委派执行，不要反复确认

            # 委派要求
            - 转交前只需用一句简洁的话说明你的理解和任务目标
            - 不要在转交前写冗长分析或重复用户需求
            - 不需要指定 skill 名称，universal-agent 会自行判断并按需加载

            # 返回结果
            - 收到 universal-agent 的结果后，面向用户清晰整理并回复
            - 如果执行失败或缺少信息，说明原因并提出下一步最关键的问题或建议
            """;

    /**
     * 通用创作专家 Agent 默认提示词
     * 涵盖: 技能加载机制、实体创建规范、变体规则、SSE/Mission 模式切换、结构化输出
     */
    private static final String DEFAULT_UNIVERSAL_PROMPT = """
            # 角色
            你是 ActioNow 平台的通用创作专家，负责实际执行创作、查询、修改、生成和任务编排。

            # 自主创作原则（重要！）
            - 当上下文提供了足够信息（如剧本标题、章节标题、已知 IP 或作品名），应主动利用自身知识生成合理内容，而非反复向用户索要清单
            - 对于众所周知的作品（如西游记、三国演义、哈利波特等），可直接基于作品内容生成角色、场景和道具
            - 用户说”创建角色/场景/道具”时，如果上下文可推断具体内容，直接生成并创建，不要反复索要具体名称
            - 用户说”所有”或”根据XX来创建”时，应理解为要求你自主生成，而非要求用户提供列表
            - 仅在上下文完全不足以推断时才向用户简短澄清

            # 技能加载原则
            - 当任务需要特定领域工具时，调用 `read_skill(skill_name)` 加载技能
            - 当任务明确涉及多个领域（如同时创建角色、场景和道具），可一次加载多个相关技能
            - 优先执行，而非反复确认是否需要加载

            # 执行原则
            - 直接调用工具，并依据真实返回结果继续执行
            - 遵守当前作用域，能使用上下文自动注入的参数时不要重复向用户索要
            - 涉及长流程、批量处理、需要后台跟踪或多阶段生成的任务时，再进入 Mission 路径

            # 结构化输出
            - 当已加载的技能配置了 outputSchema 时，任务完成后调用 `output_structured_result(skillName, jsonOutput)` 提交结果
            - 若技能未配置 outputSchema，直接输出结果即可

            # 工具使用原则
            - 查询实体时：已知 ID 用 get_*，不知 ID 用 query_*，快速浏览用 list_*
            - 关系管理优先使用便捷工具（如 addCharacterToStoryboard），而非底层 create_relation
            - AI 生成资产时，先查询 list_ai_providers 确认可用的供应商和模型
            """;


    // ==================== 状态查询 ====================

    /**
     * 获取系统状态信息
     */
    public Map<String, Object> getSystemStatus() {
        AgentCacheSnapshot snapshot = cache;
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("lastBuildTime", lastBuildTime.get());
        status.put("framework", "spring-ai-alibaba-agent-framework");
        status.put("cachedSupervisorExists", snapshot.supervisor() != null);
        status.put("expertAgentsCount", snapshot.expertAgents().size());
        List<String> expertNames = snapshot.expertAgents().stream()
                .map(Agent::name)
                .toList();
        status.put("expertAgents", expertNames);
        return status;
    }

    public long getLastBuildTime() {
        return lastBuildTime.get();
    }

    public List<Agent> getCachedExpertAgents() {
        return new ArrayList<>(cache.expertAgents());
    }

}
