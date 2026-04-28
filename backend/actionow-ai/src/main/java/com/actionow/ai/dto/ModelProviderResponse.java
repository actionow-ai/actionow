package com.actionow.ai.dto;

import com.actionow.ai.entity.ModelProvider;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型提供商响应
 *
 * @author Actionow
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelProviderResponse {

    private String id;

    private String name;

    private String description;

    private String pluginId;

    private String pluginType;

    private String providerType;

    private String baseUrl;

    private String endpoint;

    private String httpMethod;

    private String authType;

    /**
     * 认证配置（脱敏后返回，敏感字段用 *** 替代）
     */
    private Map<String, Object> authConfig;

    /**
     * API Key 引用（引用 t_system_config.config_key）
     */
    private String apiKeyRef;

    /**
     * Base URL 引用（引用 t_system_config.config_key）
     */
    private String baseUrlRef;

    // ========== Groovy 脚本配置 ==========

    /**
     * 请求构建 Groovy 脚本（内联）
     */
    private String requestBuilderScript;

    /**
     * 响应映射 Groovy 脚本（内联）
     */
    private String responseMapperScript;

    /**
     * 自定义逻辑 Groovy 脚本（内联）
     */
    private String customLogicScript;

    /**
     * 请求构建模板ID
     */
    private String requestBuilderTemplateId;

    /**
     * 响应映射模板ID
     */
    private String responseMapperTemplateId;

    /**
     * 自定义逻辑模板ID
     */
    private String customLogicTemplateId;

    // ========== 响应模式支持 ==========

    /**
     * 是否支持阻塞模式
     */
    private Boolean supportsBlocking;

    /**
     * 是否支持流式模式
     */
    private Boolean supportsStreaming;

    /**
     * 是否支持回调模式
     */
    private Boolean supportsCallback;

    /**
     * 是否支持轮询模式
     */
    private Boolean supportsPolling;

    /**
     * 支持的响应模式集合（便捷字段）
     */
    private Set<String> supportedModes;

    /**
     * 回调配置
     */
    private Map<String, Object> callbackConfig;

    /**
     * 轮询配置
     */
    private Map<String, Object> pollingConfig;

    // ========== 其他配置 ==========

    /**
     * 单次调用积分消耗（静态兜底值）
     */
    private Long creditCost;

    /**
     * 动态积分计算规则
     */
    private Map<String, Object> pricingRules;

    /**
     * 动态积分计算 Groovy 脚本
     */
    private String pricingScript;

    private Integer rateLimit;

    private Integer timeout;

    private Integer maxRetries;

    /**
     * 输入参数定义列表
     */
    private List<Map<String, Object>> inputSchema;

    /**
     * 输入参数分组列表
     */
    private List<Map<String, Object>> inputGroups;

    /**
     * 互斥参数组列表
     */
    private List<Map<String, Object>> exclusiveGroups;

    /**
     * 输出参数定义列表
     */
    private List<Map<String, Object>> outputSchema;

    private String iconUrl;

    private Integer priority;

    private Boolean enabled;

    /**
     * 可见性: PUBLIC / INTERNAL / WHITELIST
     */
    private String visibility;

    /**
     * 自定义请求头
     */
    private Map<String, String> customHeaders;

    // ========== 同步状态 ==========

    private LocalDateTime lastSyncedAt;

    private String syncStatus;

    /**
     * 同步消息
     */
    private String syncMessage;

    // ========== TEXT 类型专属字段 ==========

    /**
     * TEXT 类型关联的 LLM Provider ID
     */
    private String llmProviderId;

    /**
     * TEXT 类型系统提示词
     */
    private String systemPrompt;

    /**
     * TEXT 类型结构化输出 JSON Schema
     */
    private Map<String, Object> responseSchema;

    /**
     * TEXT 类型多模态能力配置
     */
    private Map<String, Object> multimodalConfig;

    // ========== 时间戳 ==========

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * 从实体转换
     */
    public static ModelProviderResponse fromEntity(ModelProvider entity) {
        Set<String> modes = new java.util.HashSet<>();
        if (Boolean.TRUE.equals(entity.getSupportsBlocking())) modes.add("BLOCKING");
        if (Boolean.TRUE.equals(entity.getSupportsStreaming())) modes.add("STREAMING");
        if (Boolean.TRUE.equals(entity.getSupportsCallback())) modes.add("CALLBACK");
        if (Boolean.TRUE.equals(entity.getSupportsPolling())) modes.add("POLLING");

        return ModelProviderResponse.builder()
            .id(entity.getId())
            .name(entity.getName())
            .description(entity.getDescription())
            .pluginId(entity.getPluginId())
            .pluginType(entity.getPluginType())
            .providerType(entity.getProviderType())
            .baseUrl(entity.getBaseUrl())
            .endpoint(entity.getEndpoint())
            .httpMethod(entity.getHttpMethod())
            .authType(entity.getAuthType())
            .authConfig(maskSensitiveAuthConfig(entity.getAuthConfig()))
            .apiKeyRef(entity.getApiKeyRef())
            .baseUrlRef(entity.getBaseUrlRef())
            // Groovy 脚本配置
            .requestBuilderScript(entity.getRequestBuilderScript())
            .responseMapperScript(entity.getResponseMapperScript())
            .customLogicScript(entity.getCustomLogicScript())
            .requestBuilderTemplateId(entity.getRequestBuilderTemplateId())
            .responseMapperTemplateId(entity.getResponseMapperTemplateId())
            .customLogicTemplateId(entity.getCustomLogicTemplateId())
            // 响应模式支持
            .supportsBlocking(entity.getSupportsBlocking())
            .supportsStreaming(entity.getSupportsStreaming())
            .supportsCallback(entity.getSupportsCallback())
            .supportsPolling(entity.getSupportsPolling())
            .supportedModes(modes)
            .callbackConfig(entity.getCallbackConfig())
            .pollingConfig(entity.getPollingConfig())
            // 其他配置
            .creditCost(entity.getCreditCost())
            .pricingRules(entity.getPricingRules())
            .pricingScript(entity.getPricingScript())
            .rateLimit(entity.getRateLimit())
            .timeout(entity.getTimeout())
            .maxRetries(entity.getMaxRetries())
            .inputSchema(entity.getInputSchema())
            .inputGroups(entity.getInputGroups())
            .exclusiveGroups(entity.getExclusiveGroups())
            .outputSchema(entity.getOutputSchema())
            .iconUrl(entity.getIconUrl())
            .priority(entity.getPriority())
            .enabled(entity.getEnabled())
            .visibility(entity.getVisibility())
            .customHeaders(entity.getCustomHeaders())
            // 同步状态
            .lastSyncedAt(entity.getLastSyncedAt())
            .syncStatus(entity.getSyncStatus())
            .syncMessage(entity.getSyncMessage())
            // TEXT 类型专属字段
            .llmProviderId(entity.getLlmProviderId())
            .systemPrompt(entity.getSystemPrompt())
            .responseSchema(entity.getResponseSchema())
            .multimodalConfig(entity.getMultimodalConfig())
            // 时间戳
            .createdAt(entity.getCreatedAt())
            .updatedAt(entity.getUpdatedAt())
            .build();
    }

    /**
     * 脱敏认证配置（隐藏敏感字段如 apiKey, secretKey 等）
     */
    private static Map<String, Object> maskSensitiveAuthConfig(Map<String, Object> authConfig) {
        if (authConfig == null || authConfig.isEmpty()) {
            return authConfig;
        }
        Map<String, Object> masked = new java.util.HashMap<>(authConfig);
        // 脱敏敏感字段
        for (String key : masked.keySet()) {
            String lowerKey = key.toLowerCase();
            if (lowerKey.contains("key") || lowerKey.contains("secret")
                    || lowerKey.contains("password") || lowerKey.contains("token")) {
                Object value = masked.get(key);
                if (value instanceof String str && !str.isEmpty()) {
                    masked.put(key, "***");
                }
            }
        }
        return masked;
    }
}
