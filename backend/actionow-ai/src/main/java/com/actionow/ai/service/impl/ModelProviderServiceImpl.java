package com.actionow.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.actionow.ai.entity.ModelProvider;
import com.actionow.ai.entity.ModelProviderScript;
import com.actionow.ai.entity.ModelProviderSchema;
import com.actionow.ai.entity.ProviderWorkspaceWhitelist;
import com.actionow.ai.feign.SystemFeignClient;
import com.actionow.ai.mapper.ModelProviderMapper;
import com.actionow.ai.mapper.ModelProviderScriptMapper;
import com.actionow.ai.mapper.ModelProviderSchemaMapper;
import com.actionow.ai.mapper.ProviderWorkspaceWhitelistMapper;
import com.actionow.common.security.workspace.WorkspaceInternalClient;
import com.actionow.ai.plugin.AiModelPlugin;
import com.actionow.ai.plugin.PluginExecutor;
import com.actionow.ai.plugin.PluginRegistry;
import com.actionow.ai.plugin.cache.ProviderConfigCache;
import com.actionow.ai.plugin.exception.ProviderNotFoundException;
import com.actionow.ai.plugin.model.PluginConfig;
import com.actionow.ai.plugin.model.PluginExecutionRequest;
import com.actionow.ai.plugin.model.PluginExecutionResult;
import com.actionow.ai.plugin.model.ResponseMode;
import com.actionow.ai.service.GroovyTemplateService;
import com.actionow.ai.service.ModelProviderService;
import com.actionow.common.core.result.PageResult;
import com.actionow.common.core.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 模型提供商服务实现
 *
 * @author Actionow
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelProviderServiceImpl implements ModelProviderService {

    private final ModelProviderMapper providerMapper;
    private final ModelProviderScriptMapper scriptMapper;
    private final ModelProviderSchemaMapper schemaMapper;
    private final PluginRegistry pluginRegistry;
    private final PluginExecutor pluginExecutor;
    private final ProviderConfigCache configCache;
    private final GroovyTemplateService groovyTemplateService;
    private final SystemFeignClient systemFeignClient;
    private final ProviderWorkspaceWhitelistMapper whitelistMapper;
    private final WorkspaceInternalClient workspaceInternalClient;
    private final com.actionow.ai.plugin.queue.ProviderQueueRouter providerQueueRouter;

    @Override
    @Transactional
    public ModelProvider create(ModelProvider provider) {
        // 设置默认值
        if (provider.getEnabled() == null) {
            provider.setEnabled(true);
        }
        if (provider.getVisibility() == null || provider.getVisibility().isBlank()) {
            provider.setVisibility("PUBLIC");
        }
        if (provider.getPriority() == null) {
            provider.setPriority(0);
        }
        if (provider.getTimeout() == null) {
            provider.setTimeout(60000);
        }
        if (provider.getMaxRetries() == null) {
            provider.setMaxRetries(3);
        }
        if (provider.getCreditCost() == null) {
            provider.setCreditCost(0L);
        }
        if (provider.getHttpMethod() == null) {
            provider.setHttpMethod("POST");
        }

        // 将布尔模式标志合并到 supportedModes JSONB 列
        mergeSupportedModesFromBooleans(provider);
        // 将 TEXT 类型专属字段合并到 textConfig JSONB 列
        mergeTextConfigFromFields(provider);

        providerMapper.insert(provider);

        // 保存子表数据
        saveChildScriptIfNeeded(provider);
        saveChildSchemaIfNeeded(provider);

        log.info("Created model provider: {} ({})", provider.getName(), provider.getId());
        return provider;
    }

    @Override
    @Transactional
    public ModelProvider update(ModelProvider provider) {
        ModelProvider existing = getById(provider.getId());

        // 更新主表字段
        if (provider.getName() != null) existing.setName(provider.getName());
        if (provider.getDescription() != null) existing.setDescription(provider.getDescription());
        if (provider.getBaseUrl() != null) existing.setBaseUrl(provider.getBaseUrl());
        if (provider.getEndpoint() != null) existing.setEndpoint(provider.getEndpoint());
        if (provider.getHttpMethod() != null) existing.setHttpMethod(provider.getHttpMethod());
        if (provider.getAuthType() != null) existing.setAuthType(provider.getAuthType());
        // 特殊处理 authConfig，保留被脱敏的敏感字段原值
        if (provider.getAuthConfig() != null) {
            existing.setAuthConfig(mergeAuthConfig(existing.getAuthConfig(), provider.getAuthConfig()));
        }
        if (provider.getCallbackConfig() != null) existing.setCallbackConfig(provider.getCallbackConfig());
        if (provider.getPollingConfig() != null) existing.setPollingConfig(provider.getPollingConfig());
        if (provider.getCreditCost() != null) existing.setCreditCost(provider.getCreditCost());
        if (provider.getRateLimit() != null) existing.setRateLimit(provider.getRateLimit());
        if (provider.getTimeout() != null) existing.setTimeout(provider.getTimeout());
        if (provider.getMaxRetries() != null) existing.setMaxRetries(provider.getMaxRetries());
        if (provider.getIconUrl() != null) existing.setIconUrl(provider.getIconUrl());
        if (provider.getPriority() != null) existing.setPriority(provider.getPriority());
        if (provider.getEnabled() != null) existing.setEnabled(provider.getEnabled());
        if (provider.getVisibility() != null) existing.setVisibility(provider.getVisibility());
        if (provider.getCustomHeaders() != null) existing.setCustomHeaders(provider.getCustomHeaders());
        if (provider.getApiKeyRef() != null) existing.setApiKeyRef(provider.getApiKeyRef());
        if (provider.getBaseUrlRef() != null) existing.setBaseUrlRef(provider.getBaseUrlRef());

        // 队列配置（null = 不更新，"" = 清空回退到全局默认）
        if (provider.getQueueName() != null) {
            existing.setQueueName(provider.getQueueName().isBlank() ? null : provider.getQueueName());
        }
        if (provider.getQueueConcurrency() != null) existing.setQueueConcurrency(provider.getQueueConcurrency());
        if (provider.getQueuePrefetch() != null) existing.setQueuePrefetch(provider.getQueuePrefetch());
        if (provider.getQueueMaxLength() != null) existing.setQueueMaxLength(provider.getQueueMaxLength());

        // 将布尔模式标志合并到 supportedModes JSONB 列
        if (provider.getSupportsBlocking() != null || provider.getSupportsStreaming() != null
                || provider.getSupportsCallback() != null || provider.getSupportsPolling() != null) {
            // 先把 incoming booleans 写到 existing 的 in-memory 字段
            if (provider.getSupportsBlocking() != null) existing.setSupportsBlocking(provider.getSupportsBlocking());
            if (provider.getSupportsStreaming() != null) existing.setSupportsStreaming(provider.getSupportsStreaming());
            if (provider.getSupportsCallback() != null) existing.setSupportsCallback(provider.getSupportsCallback());
            if (provider.getSupportsPolling() != null) existing.setSupportsPolling(provider.getSupportsPolling());
            mergeSupportedModesFromBooleans(existing);
        }

        // 将 TEXT 类型专属字段合并到 textConfig JSONB 列
        if (provider.getLlmProviderId() != null || provider.getSystemPrompt() != null
                || provider.getResponseSchema() != null || provider.getMultimodalConfig() != null) {
            if (provider.getLlmProviderId() != null) existing.setLlmProviderId(provider.getLlmProviderId());
            if (provider.getSystemPrompt() != null) existing.setSystemPrompt(provider.getSystemPrompt());
            if (provider.getResponseSchema() != null) existing.setResponseSchema(provider.getResponseSchema());
            if (provider.getMultimodalConfig() != null) existing.setMultimodalConfig(provider.getMultimodalConfig());
            mergeTextConfigFromFields(existing);
        }

        providerMapper.updateById(existing);

        // 更新子表: Groovy脚本 + 定价
        boolean hasScriptUpdate = provider.getRequestBuilderScript() != null || provider.getResponseMapperScript() != null
                || provider.getCustomLogicScript() != null || provider.getRequestBuilderTemplateId() != null
                || provider.getResponseMapperTemplateId() != null || provider.getCustomLogicTemplateId() != null
                || provider.getPricingRules() != null || provider.getPricingScript() != null;
        if (hasScriptUpdate) {
            if (provider.getRequestBuilderScript() != null) existing.setRequestBuilderScript(provider.getRequestBuilderScript());
            if (provider.getResponseMapperScript() != null) existing.setResponseMapperScript(provider.getResponseMapperScript());
            if (provider.getCustomLogicScript() != null) existing.setCustomLogicScript(provider.getCustomLogicScript());
            if (provider.getRequestBuilderTemplateId() != null) existing.setRequestBuilderTemplateId(provider.getRequestBuilderTemplateId());
            if (provider.getResponseMapperTemplateId() != null) existing.setResponseMapperTemplateId(provider.getResponseMapperTemplateId());
            if (provider.getCustomLogicTemplateId() != null) existing.setCustomLogicTemplateId(provider.getCustomLogicTemplateId());
            if (provider.getPricingRules() != null) existing.setPricingRules(provider.getPricingRules());
            if (provider.getPricingScript() != null) existing.setPricingScript(provider.getPricingScript());
            saveOrUpdateScript(existing);
        }

        // 更新子表: I/O Schema
        boolean hasSchemaUpdate = provider.getInputSchema() != null || provider.getInputGroups() != null
                || provider.getExclusiveGroups() != null || provider.getOutputSchema() != null;
        if (hasSchemaUpdate) {
            if (provider.getInputSchema() != null) existing.setInputSchema(provider.getInputSchema());
            if (provider.getInputGroups() != null) existing.setInputGroups(provider.getInputGroups());
            if (provider.getExclusiveGroups() != null) existing.setExclusiveGroups(provider.getExclusiveGroups());
            if (provider.getOutputSchema() != null) existing.setOutputSchema(provider.getOutputSchema());
            saveOrUpdateSchema(existing);
        }

        configCache.invalidate(existing.getId());
        // 队列路由缓存失效；下次 Facade.submit 会用新 queue_* 配置 ensureContainer
        providerQueueRouter.invalidate(existing.getId());
        log.info("Updated model provider: {} ({})", existing.getName(), existing.getId());
        return existing;
    }

    /**
     * 合并认证配置，保留被脱敏的敏感字段原值
     * 当新值为 "***" 时，保留原有的敏感字段值
     *
     * @param existingConfig 现有配置
     * @param newConfig      新配置
     * @return 合并后的配置
     */
    private Map<String, Object> mergeAuthConfig(Map<String, Object> existingConfig, Map<String, Object> newConfig) {
        if (existingConfig == null || existingConfig.isEmpty()) {
            return newConfig;
        }
        if (newConfig == null || newConfig.isEmpty()) {
            return existingConfig;
        }

        Map<String, Object> merged = new HashMap<>(newConfig);
        for (Map.Entry<String, Object> entry : merged.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // 如果新值是脱敏值 "***"，则保留原有值
            if (value instanceof String str && "***".equals(str)) {
                Object originalValue = existingConfig.get(key);
                if (originalValue != null) {
                    merged.put(key, originalValue);
                    log.debug("Preserved masked sensitive field: {}", key);
                }
            }
        }
        return merged;
    }

    @Override
    public Optional<ModelProvider> findById(String id) {
        if (!isValidUuid(id)) {
            // 非 UUID 格式，按 pluginId → 名称 依次尝试解析
            log.info("findById called with non-UUID value '{}', trying pluginId/name fallback", id);

            // 1. 尝试 pluginId 精确匹配（点号与空格均转连字符: "seedream 4.5" → "seedream-4-5"）
            String normalizedPluginId = id.replace('.', '-').replace(' ', '-').toLowerCase(Locale.ROOT);
            List<ModelProvider> providers = findByPluginId(normalizedPluginId);
            if (!providers.isEmpty()) return Optional.of(providers.get(0));

            // 2. 尝试名称精确匹配（如 "Seedream 4.5"）
            providers = findByName(id);
            if (!providers.isEmpty()) return Optional.of(providers.get(0));

            // 3. 名称不区分大小写匹配（容忍 Agent 输出的 "seedream 4.5"）
            providers = findByNameIgnoreCase(id);
            if (!providers.isEmpty()) return Optional.of(providers.get(0));

            return Optional.empty();
        }
        // 先从缓存获取
        ModelProvider provider = configCache.getOrLoadProvider(id, pid -> {
            ModelProvider p = providerMapper.selectById(pid);
            if (p != null) enrichWithChildTables(p);
            return p;
        });
        return Optional.ofNullable(provider);
    }

    private static boolean isValidUuid(String value) {
        if (value == null || value.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public ModelProvider getById(String id) {
        return findById(id)
            .orElseThrow(() -> new ProviderNotFoundException(id));
    }

    @Override
    @Transactional
    public void delete(String id) {
        ModelProvider provider = getById(id);
        providerMapper.deleteById(id);
        configCache.invalidate(id);
        log.info("Deleted model provider: {} ({})", provider.getName(), id);
    }

    @Override
    public List<ModelProvider> findAllEnabled() {
        // 使用 LambdaQueryWrapper 而非 @Select 注解，确保 JSON 字段的 TypeHandler 生效
        List<ModelProvider> providers = providerMapper.selectList(
            new LambdaQueryWrapper<ModelProvider>()
                .eq(ModelProvider::getEnabled, true)
                .eq(ModelProvider::getDeleted, 0)
                .orderByDesc(ModelProvider::getPriority)
        );
        enrichWithChildTables(providers);
        return providers;
    }

    @Override
    public PageResult<ModelProvider> findPage(Long current, Long size, String providerType, Boolean enabled, String name) {
        // 参数校验
        if (current == null || current < 1) {
            current = 1L;
        }
        if (size == null || size < 1) {
            size = 20L;
        }
        if (size > 100) {
            size = 100L;
        }

        // 分页查询
        Page<ModelProvider> page = new Page<>(current, size);
        IPage<ModelProvider> providerPage = providerMapper.selectPage(page, providerType, enabled, name);

        if (providerPage.getRecords().isEmpty()) {
            return PageResult.empty(current, size);
        }

        enrichWithChildTables(providerPage.getRecords());

        return PageResult.of(providerPage.getCurrent(), providerPage.getSize(),
                providerPage.getTotal(), providerPage.getRecords());
    }

    @Override
    public List<ModelProvider> findEnabledByType(String providerType) {
        // 使用 LambdaQueryWrapper 而非 @Select 注解，确保 JSON 字段的 TypeHandler 生效
        List<ModelProvider> providers = providerMapper.selectList(
            new LambdaQueryWrapper<ModelProvider>()
                .eq(ModelProvider::getProviderType, providerType)
                .eq(ModelProvider::getEnabled, true)
                .eq(ModelProvider::getDeleted, 0)
                .orderByDesc(ModelProvider::getPriority)
        );
        enrichWithChildTables(providers);
        return providers;
    }

    @Override
    public List<ModelProvider> findVisibleEnabledByType(String providerType, String workspaceId) {
        List<ModelProvider> all = findEnabledByType(providerType);
        if (all.isEmpty()) {
            return all;
        }
        return filterByVisibility(all, workspaceId);
    }

    /**
     * 按可见性规则过滤 provider 列表（package-private 便于单测）。
     */
    List<ModelProvider> filterByVisibility(List<ModelProvider> providers, String workspaceId) {
        boolean hasInternal = false;
        boolean hasWhitelist = false;
        for (ModelProvider p : providers) {
            String v = p.getVisibility();
            if ("INTERNAL".equals(v)) {
                hasInternal = true;
            } else if ("WHITELIST".equals(v)) {
                hasWhitelist = true;
            }
        }

        Boolean isInternalWs = null;
        Set<String> whitelistedProviderIds = null;

        if ((hasInternal || hasWhitelist) && (workspaceId == null || workspaceId.isBlank())) {
            // 没有 workspace 上下文时，INTERNAL/WHITELIST 一律不可见
            return providers.stream()
                    .filter(p -> p.getVisibility() == null || "PUBLIC".equals(p.getVisibility()))
                    .collect(Collectors.toList());
        }

        if (hasInternal) {
            try {
                Result<Boolean> resp = workspaceInternalClient.isInternal(workspaceId);
                isInternalWs = resp != null && resp.isSuccess() && Boolean.TRUE.equals(resp.getData());
            } catch (Exception ex) {
                log.warn("调用 workspaceInternalClient.isInternal 失败，按非内部处理: workspaceId={}, err={}",
                        workspaceId, ex.getMessage());
                isInternalWs = false;
            }
        }

        if (hasWhitelist) {
            try {
                whitelistedProviderIds = new HashSet<>(
                        whitelistMapper.listProviderIdsByWorkspace(workspaceId));
            } catch (Exception ex) {
                log.warn("查询 provider 白名单失败，按空白名单处理: workspaceId={}, err={}",
                        workspaceId, ex.getMessage());
                whitelistedProviderIds = Set.of();
            }
        }

        final boolean internalAllowed = Boolean.TRUE.equals(isInternalWs);
        final Set<String> wlIds = whitelistedProviderIds == null ? Set.of() : whitelistedProviderIds;

        return providers.stream().filter(p -> {
            String v = p.getVisibility();
            if (v == null || "PUBLIC".equals(v)) {
                return true;
            }
            if ("INTERNAL".equals(v)) {
                return internalAllowed;
            }
            if ("WHITELIST".equals(v)) {
                return wlIds.contains(p.getId());
            }
            return false;
        }).collect(Collectors.toList());
    }

    @Override
    public List<ModelProvider> findByPluginId(String pluginId) {
        // 使用 LambdaQueryWrapper 而非 @Select 注解，确保 JSON 字段的 TypeHandler 生效
        List<ModelProvider> providers = providerMapper.selectList(
            new LambdaQueryWrapper<ModelProvider>()
                .eq(ModelProvider::getPluginId, pluginId)
                .eq(ModelProvider::getDeleted, 0)
        );
        enrichWithChildTables(providers);
        return providers;
    }

    @Override
    public List<ModelProvider> findByName(String name) {
        List<ModelProvider> providers = providerMapper.selectList(
            new LambdaQueryWrapper<ModelProvider>()
                .eq(ModelProvider::getName, name)
                .eq(ModelProvider::getDeleted, 0)
        );
        enrichWithChildTables(providers);
        return providers;
    }

    /**
     * 不区分大小写的名称匹配（Postgres LOWER 比较）。仅在 findById 回退阶段使用，
     * 用于容忍 Agent/LLM 输出的 "seedream 4.5" 这类非规范大小写。
     */
    private List<ModelProvider> findByNameIgnoreCase(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        List<ModelProvider> providers = providerMapper.selectList(
            new LambdaQueryWrapper<ModelProvider>()
                .apply("LOWER(name) = {0}", name.toLowerCase(Locale.ROOT))
                .eq(ModelProvider::getDeleted, 0)
        );
        enrichWithChildTables(providers);
        return providers;
    }

    @Override
    @Transactional
    public void enable(String id) {
        ModelProvider provider = getById(id);
        provider.setEnabled(true);
        providerMapper.updateById(provider);
        configCache.invalidate(id);
        log.info("Enabled model provider: {}", id);
    }

    @Override
    @Transactional
    public void disable(String id) {
        ModelProvider provider = getById(id);
        provider.setEnabled(false);
        providerMapper.updateById(provider);
        configCache.invalidate(id);
        log.info("Disabled model provider: {}", id);
    }

    @Override
    public TestConnectionResult testConnection(String id) {
        ModelProvider provider = getById(id);

        try {
            AiModelPlugin plugin = pluginRegistry.getPlugin(resolvePluginImplId(provider.getPluginType()));
            PluginConfig config = toPluginConfig(provider);

            AiModelPlugin.TestConnectionResult result = plugin.testConnection(config);

            if (result.connected()) {
                return TestConnectionResult.success(result.latencyMs() != null ? result.latencyMs() : 0);
            } else {
                return TestConnectionResult.failure(result.message());
            }
        } catch (Exception e) {
            log.error("Test connection failed for provider {}: {}", id, e.getMessage());
            return TestConnectionResult.failure(e.getMessage());
        }
    }

    @Override
    @Transactional
    public void sync(String id) {
        ModelProvider provider = getById(id);

        try {
            // 同步逻辑预留（如从外部API获取参数定义）
            // NOTE: lastSyncedAt/syncStatus/syncMessage 字段已从 DDL 移除，
            // 同步结果仅记录日志，不持久化到数据库
            configCache.invalidate(id);
            log.info("Synced model provider: {}", id);
        } catch (Exception e) {
            log.error("Sync failed for provider {}: {}", id, e.getMessage());
            throw new RuntimeException("同步失败: " + e.getMessage(), e);
        }
    }

    @Override
    public TestExecutionResult testExecution(String id, Map<String, Object> inputs,
                                              String responseMode, Integer timeoutOverride) {
        ModelProvider provider = getById(id);

        try {
            PluginConfig config = toPluginConfig(provider);
            ResponseMode mode = parseResponseMode(responseMode);

            // 构建执行请求
            PluginExecutionRequest request = PluginExecutionRequest.builder()
                    .providerId(id)
                    .inputs(inputs)
                    .responseMode(mode)
                    .timeoutOverride(timeoutOverride)
                    .skipCreditCheck(true) // 测试不消耗积分
                    .build();

            // 执行插件
            PluginExecutionResult result = pluginExecutor.execute(
                    resolvePluginImplId(provider.getPluginType()), config, request);

            // 如果是轮询模式且状态为 PENDING，等待轮询完成
            if (mode == ResponseMode.POLLING &&
                (result.getStatus() == PluginExecutionResult.ExecutionStatus.PENDING ||
                 result.getStatus() == PluginExecutionResult.ExecutionStatus.RUNNING)) {

                log.info("Test execution waiting for polling result: executionId={}", result.getExecutionId());

                // 计算超时时间：使用提供商配置的轮询超时，默认5分钟
                PluginConfig.PollingConfig pollingConfig = config.getPollingConfig();
                int maxAttempts = pollingConfig != null ? pollingConfig.getMaxAttempts() : 60;
                int intervalMs = pollingConfig != null ? pollingConfig.getIntervalMs() : 2000;
                long timeoutMs = (long) maxAttempts * intervalMs + 30000L; // 额外30秒缓冲

                try {
                    result = pluginExecutor.awaitPollingResult(
                            result.getExecutionId(),
                            timeoutMs,
                            java.util.concurrent.TimeUnit.MILLISECONDS
                    );
                    log.info("Polling completed: executionId={}, status={}",
                            result.getExecutionId(), result.getStatus());
                } catch (java.util.concurrent.TimeoutException e) {
                    log.error("Polling timeout for execution {}", result.getExecutionId());
                    return TestExecutionResult.failure(
                            result.getExecutionId(),
                            "POLLING_TIMEOUT",
                            "轮询超时，请检查模型响应时间"
                    );
                } catch (Exception e) {
                    log.error("Error waiting for polling result: {}", e.getMessage());
                    return TestExecutionResult.failure(
                            result.getExecutionId(),
                            "POLLING_ERROR",
                            "等待轮询结果失败: " + e.getMessage()
                    );
                }
            }

            if (result.getStatus() == PluginExecutionResult.ExecutionStatus.SUCCEEDED) {
                // 转换资产列表
                List<Map<String, Object>> assetMaps = null;
                if (result.getAssets() != null) {
                    assetMaps = result.getAssets().stream()
                            .map(this::assetToMap)
                            .toList();
                }
                return TestExecutionResult.success(
                        result.getExecutionId(),
                        result.getOutputs(),
                        assetMaps,
                        result.getElapsedTimeMs(),
                        result.getRawResponse()
                );
            } else {
                return TestExecutionResult.failure(
                        result.getExecutionId(),
                        result.getErrorCode(),
                        result.getErrorMessage()
                );
            }
        } catch (Exception e) {
            log.error("Test execution failed for provider {}: {}", id, e.getMessage(), e);
            return TestExecutionResult.failure(null, "EXECUTION_ERROR", e.getMessage());
        }
    }

    @Override
    @Transactional
    public ModelProvider copy(String id, String newName, String newDescription, Boolean enabled) {
        ModelProvider source = getById(id);

        // 创建新的提供商实例（只复制主表字段）
        ModelProvider copy = new ModelProvider();
        copy.setName(newName);
        copy.setDescription(newDescription != null ? newDescription : source.getDescription());
        copy.setPluginId(source.getPluginId());
        copy.setPluginType(source.getPluginType());
        copy.setProviderType(source.getProviderType());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setEndpoint(source.getEndpoint());
        copy.setHttpMethod(source.getHttpMethod());
        copy.setAuthType(source.getAuthType());
        copy.setAuthConfig(source.getAuthConfig() != null ? new HashMap<>(source.getAuthConfig()) : null);
        copy.setSupportedModes(source.getSupportedModes() != null ? new ArrayList<>(source.getSupportedModes()) : null);
        copy.setCallbackConfig(source.getCallbackConfig() != null ? new HashMap<>(source.getCallbackConfig()) : null);
        copy.setPollingConfig(source.getPollingConfig() != null ? new HashMap<>(source.getPollingConfig()) : null);
        copy.setCreditCost(source.getCreditCost());
        copy.setRateLimit(source.getRateLimit());
        copy.setTimeout(source.getTimeout());
        copy.setMaxRetries(source.getMaxRetries());
        copy.setIconUrl(source.getIconUrl());
        copy.setPriority(source.getPriority());
        copy.setCustomHeaders(source.getCustomHeaders() != null ? new HashMap<>(source.getCustomHeaders()) : null);
        copy.setTextConfig(source.getTextConfig() != null ? new HashMap<>(source.getTextConfig()) : null);
        copy.setApiKeyRef(source.getApiKeyRef());
        copy.setBaseUrlRef(source.getBaseUrlRef());
        copy.setEnabled(enabled != null ? enabled : false);

        providerMapper.insert(copy);

        // 复制子表数据: Groovy脚本 + 定价
        copy.setRequestBuilderScript(source.getRequestBuilderScript());
        copy.setResponseMapperScript(source.getResponseMapperScript());
        copy.setCustomLogicScript(source.getCustomLogicScript());
        copy.setRequestBuilderTemplateId(source.getRequestBuilderTemplateId());
        copy.setResponseMapperTemplateId(source.getResponseMapperTemplateId());
        copy.setCustomLogicTemplateId(source.getCustomLogicTemplateId());
        copy.setPricingRules(source.getPricingRules());
        copy.setPricingScript(source.getPricingScript());
        saveChildScriptIfNeeded(copy);

        // 复制子表数据: I/O Schema
        copy.setInputSchema(source.getInputSchema() != null ? new ArrayList<>(source.getInputSchema()) : null);
        copy.setInputGroups(source.getInputGroups() != null ? new ArrayList<>(source.getInputGroups()) : null);
        copy.setExclusiveGroups(source.getExclusiveGroups() != null ? new ArrayList<>(source.getExclusiveGroups()) : null);
        copy.setOutputSchema(source.getOutputSchema() != null ? new ArrayList<>(source.getOutputSchema()) : null);
        saveChildSchemaIfNeeded(copy);

        log.info("Copied model provider: {} -> {} ({})", source.getName(), newName, copy.getId());
        return copy;
    }

    private ResponseMode parseResponseMode(String mode) {
        if (mode == null || mode.isEmpty()) {
            return ResponseMode.BLOCKING;
        }
        try {
            return ResponseMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseMode.BLOCKING;
        }
    }

    private Map<String, Object> assetToMap(PluginExecutionResult.GeneratedAsset asset) {
        Map<String, Object> map = new HashMap<>();
        map.put("assetType", asset.getAssetType());
        map.put("url", asset.getUrl());
        map.put("base64", asset.isBase64());
        map.put("mimeType", asset.getMimeType());
        map.put("fileName", asset.getFileName());
        map.put("fileSize", asset.getFileSize());
        map.put("width", asset.getWidth());
        map.put("height", asset.getHeight());
        map.put("duration", asset.getDuration());
        map.put("metadata", asset.getMetadata());
        return map;
    }

    @Override
    public PluginConfig toPluginConfig(ModelProvider provider) {
        // 尝试从缓存获取
        return configCache.getOrLoadPluginConfig(provider.getId(), id -> buildPluginConfig(provider));
    }

    /**
     * 构建PluginConfig
     */
    private PluginConfig buildPluginConfig(ModelProvider provider) {
        // 解析 baseUrl：ref 优先，fallback 到直接值
        String resolvedBaseUrl = provider.getBaseUrl();
        if (StringUtils.hasText(provider.getBaseUrlRef())) {
            String val = resolveConfigValue(provider.getBaseUrlRef());
            if (val != null) resolvedBaseUrl = val;
        }

        // 解析 apiKey：ref 优先，注入到 authConfig
        Map<String, Object> resolvedAuth = provider.getAuthConfig() != null
                ? new HashMap<>(provider.getAuthConfig()) : new HashMap<>();
        if (StringUtils.hasText(provider.getApiKeyRef())) {
            String val = resolveConfigValue(provider.getApiKeyRef());
            if (val != null) resolvedAuth.put("apiKey", val);
        }

        Set<ResponseMode> supportedModes = new HashSet<>();
        if (Boolean.TRUE.equals(provider.getSupportsBlocking())) {
            supportedModes.add(ResponseMode.BLOCKING);
        }
        if (Boolean.TRUE.equals(provider.getSupportsStreaming())) {
            supportedModes.add(ResponseMode.STREAMING);
        }
        if (Boolean.TRUE.equals(provider.getSupportsCallback())) {
            supportedModes.add(ResponseMode.CALLBACK);
        }
        if (Boolean.TRUE.equals(provider.getSupportsPolling())) {
            supportedModes.add(ResponseMode.POLLING);
        }

        PluginConfig.PluginConfigBuilder builder = PluginConfig.builder()
            .providerId(provider.getId())
            .providerName(provider.getName())
            .baseUrl(resolvedBaseUrl)
            .endpoint(provider.getEndpoint())
            .httpMethod(provider.getHttpMethod())
            .authType(provider.getAuthType())
            .authConfig(resolvedAuth)
            .supportedModes(supportedModes)
            .timeout(provider.getTimeout())
            .maxRetries(provider.getMaxRetries())
            .creditCost(provider.getCreditCost())
            .rateLimit(provider.getRateLimit())
            .customHeaders(provider.getCustomHeaders())
            .providerType(provider.getProviderType())
            .llmProviderId(provider.getLlmProviderId())
            .systemPrompt(provider.getSystemPrompt())
            .responseSchema(provider.getResponseSchema())
            .multimodalConfig(provider.getMultimodalConfig());

        // ========== Groovy脚本配置 ==========

        // 请求构建脚本：优先使用内联脚本，其次解析模板引用
        String requestBuilderScript = provider.getRequestBuilderScript();
        if (!StringUtils.hasText(requestBuilderScript) && StringUtils.hasText(provider.getRequestBuilderTemplateId())) {
            requestBuilderScript = groovyTemplateService.getScriptContent(provider.getRequestBuilderTemplateId());
        }
        builder.requestBuilderScript(requestBuilderScript);
        builder.requestBuilderTemplateId(provider.getRequestBuilderTemplateId());

        // 响应映射脚本
        String responseMapperScript = provider.getResponseMapperScript();
        if (!StringUtils.hasText(responseMapperScript) && StringUtils.hasText(provider.getResponseMapperTemplateId())) {
            responseMapperScript = groovyTemplateService.getScriptContent(provider.getResponseMapperTemplateId());
        }
        builder.responseMapperScript(responseMapperScript);
        builder.responseMapperTemplateId(provider.getResponseMapperTemplateId());

        // 自定义逻辑脚本
        String customLogicScript = provider.getCustomLogicScript();
        if (!StringUtils.hasText(customLogicScript) && StringUtils.hasText(provider.getCustomLogicTemplateId())) {
            customLogicScript = groovyTemplateService.getScriptContent(provider.getCustomLogicTemplateId());
        }
        builder.customLogicScript(customLogicScript);
        builder.customLogicTemplateId(provider.getCustomLogicTemplateId());

        // 转换回调配置
        if (provider.getCallbackConfig() != null) {
            builder.callbackConfig(convertCallbackConfig(provider.getCallbackConfig()));
        }

        // 转换轮询配置
        if (provider.getPollingConfig() != null) {
            builder.pollingConfig(convertPollingConfig(provider.getPollingConfig()));
        }

        // 加载 InputSchema（供 RequestHelper 自动构建请求体）
        ModelProviderSchema providerSchema = schemaMapper.selectByProviderId(provider.getId());
        if (providerSchema != null && providerSchema.getInputSchema() != null) {
            builder.inputSchema(providerSchema.getInputSchema());
        }

        return builder.build();
    }

    private PluginConfig.CallbackConfig convertCallbackConfig(Map<String, Object> config) {
        return PluginConfig.CallbackConfig.builder()
            .callbackPath((String) config.get("callbackPath"))
            .signatureSecret((String) config.get("signatureSecret"))
            .signatureHeader((String) config.getOrDefault("signatureHeader", "X-Signature"))
            .statusPath((String) config.getOrDefault("statusPath", "$.status"))
            .resultPath((String) config.getOrDefault("resultPath", "$.data"))
            .build();
    }

    private PluginConfig.PollingConfig convertPollingConfig(Map<String, Object> config) {
        // 支持两种字段名: pollingEndpoint (新) 和 endpoint (旧)
        String endpoint = (String) config.get("pollingEndpoint");
        if (endpoint == null) {
            endpoint = (String) config.get("endpoint");
        }

        // 支持两种间隔字段名: pollingInterval (新) 和 intervalMs (旧)
        int intervalMs = 2000;
        if (config.containsKey("pollingInterval")) {
            intervalMs = ((Number) config.get("pollingInterval")).intValue();
        } else if (config.containsKey("intervalMs")) {
            intervalMs = ((Number) config.get("intervalMs")).intValue();
        }

        // 支持两种最大轮询时间字段: maxPollingTime (毫秒) 和 maxAttempts (次数)
        int maxAttempts = 60;
        if (config.containsKey("maxPollingTime")) {
            // 将毫秒转换为次数: maxPollingTime / intervalMs
            int maxPollingTimeMs = ((Number) config.get("maxPollingTime")).intValue();
            maxAttempts = Math.max(1, maxPollingTimeMs / intervalMs);
        } else if (config.containsKey("maxAttempts")) {
            maxAttempts = ((Number) config.get("maxAttempts")).intValue();
        }

        return PluginConfig.PollingConfig.builder()
            .endpoint(endpoint)
            .intervalMs(intervalMs)
            .maxAttempts(maxAttempts)
            .httpMethod((String) config.getOrDefault("httpMethod", "GET"))
            .requestBodyTemplate((Map<String, Object>) config.get("requestBodyTemplate"))
            .statusPath((String) config.getOrDefault("statusPath", "$.status"))
            .resultPath((String) config.getOrDefault("resultPath", "$.data"))
            .successStatuses(convertToStatusSet(config.get("successStatus"), config.get("successStatuses"), "succeeded"))
            .failedStatuses(convertToStatusSet(config.get("failedStatus"), config.get("failedStatuses"), "failed"))
            .build();
    }

    /**
     * 将状态值转换为 Set，支持 String、List 和新旧字段名
     */
    @SuppressWarnings("unchecked")
    private Set<String> convertToStatusSet(Object singleValue, Object multiValue, String defaultValue) {
        // 优先使用新的复数形式字段
        if (multiValue != null) {
            if (multiValue instanceof Collection<?> collection) {
                return collection.stream()
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.toSet());
            } else if (multiValue instanceof String str) {
                return Collections.singleton(str);
            }
        }
        // 兼容旧的单数形式字段
        if (singleValue != null) {
            if (singleValue instanceof Collection<?> collection) {
                return collection.stream()
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.toSet());
            } else if (singleValue instanceof String str) {
                return Collections.singleton(str);
            }
        }
        // 使用默认值
        return Collections.singleton(defaultValue);
    }

    /**
     * 从系统配置解析变量引用值
     *
     * @param configKey 系统配置的 config_key
     * @return 解析后的值，失败时返回 null
     */
    private String resolveConfigValue(String configKey) {
        try {
            Result<String> result = systemFeignClient.getConfigValue(configKey);
            return (result != null && result.isSuccess()) ? result.getData() : null;
        } catch (Exception e) {
            log.warn("从系统配置解析变量失败: key={}", configKey, e);
            return null;
        }
    }

    /**
     * 将数据库 plugin_type（如 "GROOVY"）解析为 PluginRegistry 中注册的插件实现 ID
     * plugin_id 字段存储的是模型标识（如 "midjourney-niji7"），不能直接用于插件查找
     */
    private String resolvePluginImplId(String pluginType) {
        if (pluginType == null) {
            return "groovy";
        }
        return pluginType.toLowerCase().replace("_", "-");
    }

    // ==================== 子表数据加载 ====================

    /**
     * 批量加载子表数据并合并到 ModelProvider 实体
     * 从 t_model_provider_script 和 t_model_provider_schema 加载数据
     */
    private void enrichWithChildTables(List<ModelProvider> providers) {
        if (providers == null || providers.isEmpty()) return;

        List<String> providerIds = providers.stream()
                .map(ModelProvider::getId)
                .collect(Collectors.toList());

        // 批量加载脚本数据
        Map<String, ModelProviderScript> scriptMap = Collections.emptyMap();
        try {
            List<ModelProviderScript> scripts = scriptMapper.selectByProviderIds(providerIds);
            scriptMap = scripts.stream()
                    .collect(Collectors.toMap(ModelProviderScript::getProviderId, Function.identity(), (a, b) -> a));
        } catch (Exception e) {
            log.warn("Failed to load provider scripts: {}", e.getMessage());
        }

        // 批量加载 Schema 数据
        Map<String, ModelProviderSchema> schemaMap = Collections.emptyMap();
        try {
            List<ModelProviderSchema> schemas = schemaMapper.selectByProviderIds(providerIds);
            schemaMap = schemas.stream()
                    .collect(Collectors.toMap(ModelProviderSchema::getProviderId, Function.identity(), (a, b) -> a));
        } catch (Exception e) {
            log.warn("Failed to load provider schemas: {}", e.getMessage());
        }

        // 合并到 ModelProvider 实体
        for (ModelProvider provider : providers) {
            ModelProviderScript script = scriptMap.get(provider.getId());
            if (script != null) {
                provider.setRequestBuilderScript(script.getRequestBuilderScript());
                provider.setResponseMapperScript(script.getResponseMapperScript());
                provider.setCustomLogicScript(script.getCustomLogicScript());
                provider.setRequestBuilderTemplateId(script.getRequestBuilderTemplateId());
                provider.setResponseMapperTemplateId(script.getResponseMapperTemplateId());
                provider.setCustomLogicTemplateId(script.getCustomLogicTemplateId());
                provider.setPricingRules(script.getPricingRules());
                provider.setPricingScript(script.getPricingScript());
            }

            ModelProviderSchema schema = schemaMap.get(provider.getId());
            if (schema != null) {
                provider.setInputSchema(schema.getInputSchema());
                provider.setInputGroups(schema.getInputGroups());
                provider.setExclusiveGroups(schema.getExclusiveGroups());
                provider.setOutputSchema(schema.getOutputSchema());
            }
        }
    }

    /**
     * 加载单个 Provider 的子表数据
     */
    private void enrichWithChildTables(ModelProvider provider) {
        if (provider == null) return;
        enrichWithChildTables(List.of(provider));
    }

    // ==================== 子表写入辅助方法 ====================

    /**
     * 将布尔模式标志（supportsBlocking 等）合并到 supportedModes JSONB 列
     */
    private void mergeSupportedModesFromBooleans(ModelProvider provider) {
        List<String> modes = provider.getSupportedModes() != null
                ? new ArrayList<>(provider.getSupportedModes()) : new ArrayList<>();
        mergeModeFlag(modes, "BLOCKING", provider.getSupportsBlocking());
        mergeModeFlag(modes, "STREAMING", provider.getSupportsStreaming());
        mergeModeFlag(modes, "CALLBACK", provider.getSupportsCallback());
        mergeModeFlag(modes, "POLLING", provider.getSupportsPolling());
        if (modes.isEmpty()) {
            modes.add("BLOCKING"); // 默认至少支持 BLOCKING
        }
        provider.setSupportedModes(modes);
    }

    private void mergeModeFlag(List<String> modes, String mode, Boolean flag) {
        if (flag == null) return;
        if (Boolean.TRUE.equals(flag) && !modes.contains(mode)) {
            modes.add(mode);
        } else if (Boolean.FALSE.equals(flag)) {
            modes.remove(mode);
        }
    }

    /**
     * 将 TEXT 类型专属字段（llmProviderId 等）合并到 textConfig JSONB 列
     */
    @SuppressWarnings("unchecked")
    private void mergeTextConfigFromFields(ModelProvider provider) {
        Map<String, Object> config = provider.getTextConfig() != null
                ? new HashMap<>(provider.getTextConfig()) : new HashMap<>();
        if (provider.getLlmProviderId() != null) config.put("llmProviderId", provider.getLlmProviderId());
        if (provider.getSystemPrompt() != null) config.put("systemPrompt", provider.getSystemPrompt());
        if (provider.getResponseSchema() != null) config.put("responseSchema", provider.getResponseSchema());
        if (provider.getMultimodalConfig() != null) config.put("multimodalConfig", provider.getMultimodalConfig());
        if (!config.isEmpty()) {
            provider.setTextConfig(config);
        }
    }

    /**
     * 如果 provider 有任何 script 数据，则插入 t_model_provider_script 子表行
     */
    private void saveChildScriptIfNeeded(ModelProvider provider) {
        if (!hasAnyScriptData(provider)) return;
        ModelProviderScript script = new ModelProviderScript();
        script.setProviderId(provider.getId());
        script.setRequestBuilderScript(provider.getRequestBuilderScript());
        script.setResponseMapperScript(provider.getResponseMapperScript());
        script.setCustomLogicScript(provider.getCustomLogicScript());
        script.setRequestBuilderTemplateId(provider.getRequestBuilderTemplateId());
        script.setResponseMapperTemplateId(provider.getResponseMapperTemplateId());
        script.setCustomLogicTemplateId(provider.getCustomLogicTemplateId());
        script.setPricingRules(provider.getPricingRules());
        script.setPricingScript(provider.getPricingScript());
        scriptMapper.insert(script);
    }

    /**
     * 如果 provider 有任何 schema 数据，则插入 t_model_provider_schema 子表行
     */
    private void saveChildSchemaIfNeeded(ModelProvider provider) {
        if (!hasAnySchemaData(provider)) return;
        ModelProviderSchema schema = new ModelProviderSchema();
        schema.setProviderId(provider.getId());
        schema.setInputSchema(provider.getInputSchema());
        schema.setInputGroups(provider.getInputGroups());
        schema.setExclusiveGroups(provider.getExclusiveGroups());
        schema.setOutputSchema(provider.getOutputSchema());
        schemaMapper.insert(schema);
    }

    /**
     * 更新或插入脚本子表（先查后决策）
     */
    private void saveOrUpdateScript(ModelProvider provider) {
        ModelProviderScript existing = scriptMapper.selectByProviderId(provider.getId());
        if (existing != null) {
            existing.setRequestBuilderScript(provider.getRequestBuilderScript());
            existing.setResponseMapperScript(provider.getResponseMapperScript());
            existing.setCustomLogicScript(provider.getCustomLogicScript());
            existing.setRequestBuilderTemplateId(provider.getRequestBuilderTemplateId());
            existing.setResponseMapperTemplateId(provider.getResponseMapperTemplateId());
            existing.setCustomLogicTemplateId(provider.getCustomLogicTemplateId());
            existing.setPricingRules(provider.getPricingRules());
            existing.setPricingScript(provider.getPricingScript());
            scriptMapper.updateById(existing);
        } else {
            saveChildScriptIfNeeded(provider);
        }
    }

    /**
     * 更新或插入 Schema 子表（先查后决策）
     */
    private void saveOrUpdateSchema(ModelProvider provider) {
        ModelProviderSchema existing = schemaMapper.selectByProviderId(provider.getId());
        if (existing != null) {
            existing.setInputSchema(provider.getInputSchema());
            existing.setInputGroups(provider.getInputGroups());
            existing.setExclusiveGroups(provider.getExclusiveGroups());
            existing.setOutputSchema(provider.getOutputSchema());
            schemaMapper.updateById(existing);
        } else {
            saveChildSchemaIfNeeded(provider);
        }
    }

    private boolean hasAnyScriptData(ModelProvider p) {
        return StringUtils.hasText(p.getRequestBuilderScript())
                || StringUtils.hasText(p.getResponseMapperScript())
                || StringUtils.hasText(p.getCustomLogicScript())
                || StringUtils.hasText(p.getRequestBuilderTemplateId())
                || StringUtils.hasText(p.getResponseMapperTemplateId())
                || StringUtils.hasText(p.getCustomLogicTemplateId())
                || p.getPricingRules() != null
                || StringUtils.hasText(p.getPricingScript());
    }

    private boolean hasAnySchemaData(ModelProvider p) {
        return (p.getInputSchema() != null && !p.getInputSchema().isEmpty())
                || (p.getInputGroups() != null && !p.getInputGroups().isEmpty())
                || (p.getExclusiveGroups() != null && !p.getExclusiveGroups().isEmpty())
                || (p.getOutputSchema() != null && !p.getOutputSchema().isEmpty());
    }
}
