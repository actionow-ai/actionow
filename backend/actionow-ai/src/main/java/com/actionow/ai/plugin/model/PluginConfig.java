package com.actionow.ai.plugin.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 插件配置
 * 包含插件运行所需的所有配置信息
 *
 * @author Actionow
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginConfig {

    /**
     * 提供商ID
     */
    private String providerId;

    /**
     * 提供商名称
     */
    private String providerName;

    /**
     * 基础URL
     */
    private String baseUrl;

    /**
     * API端点
     */
    private String endpoint;

    /**
     * HTTP方法
     */
    @Builder.Default
    private String httpMethod = "POST";

    /**
     * 认证类型
     */
    private String authType;

    /**
     * 认证配置
     */
    private Map<String, Object> authConfig;

    // ========== Groovy 脚本字段 ==========

    /**
     * 请求构建Groovy脚本（内联）
     */
    private String requestBuilderScript;

    /**
     * 响应映射Groovy脚本（内联）
     */
    private String responseMapperScript;

    /**
     * 自定义逻辑Groovy脚本（内联）
     */
    private String customLogicScript;

    /**
     * 请求构建模板ID引用
     */
    private String requestBuilderTemplateId;

    /**
     * 响应映射模板ID引用
     */
    private String responseMapperTemplateId;

    /**
     * 自定义逻辑模板ID引用
     */
    private String customLogicTemplateId;

    // ========== Schema ==========

    /**
     * 输入参数 Schema（来自 ModelProviderSchema.inputSchema）
     * 用于 RequestHelper 自动构建请求体
     */
    private List<Map<String, Object>> inputSchema;

    // ========== 响应模式配置 ==========

    /**
     * 支持的响应模式
     */
    private Set<ResponseMode> supportedModes;

    /**
     * 回调配置
     */
    private CallbackConfig callbackConfig;

    /**
     * 轮询配置
     */
    private PollingConfig pollingConfig;

    /**
     * 超时时间（毫秒）
     */
    @Builder.Default
    private Integer timeout = 60000;

    /**
     * 最大重试次数
     */
    @Builder.Default
    private Integer maxRetries = 3;

    /**
     * 每次调用积分消耗
     */
    @Builder.Default
    private Long creditCost = 0L;

    /**
     * 每分钟请求限制
     */
    private Integer rateLimit;

    /**
     * 自定义请求头
     */
    private Map<String, String> customHeaders;

    /**
     * 每 Provider 熔断器配置覆盖（可选）
     * 不设置则使用 AiRuntimeConfigService 的全局默认。
     * 生图/视频类长耗时 provider 建议配置 TIME_BASED 滑窗。
     */
    private CircuitBreakerOverride circuitBreaker;

    // ========== TEXT 类型专属字段 ==========

    /**
     * 提供商类型: IMAGE, VIDEO, AUDIO, TEXT
     */
    private String providerType;

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

    /**
     * 每 Provider 熔断器覆盖配置
     *
     * 字段为 null 时使用 AiRuntimeConfigService 全局默认。
     * windowType=TIME_BASED 时，slidingWindowSize 表示秒数（例如 60 = 最近 60 秒）。
     * windowType=COUNT_BASED 时，slidingWindowSize 表示调用次数。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CircuitBreakerOverride {
        /** COUNT_BASED 或 TIME_BASED；生图/视频建议 TIME_BASED */
        private String windowType;
        /** 滑窗大小（次数 or 秒，取决于 windowType） */
        private Integer slidingWindowSize;
        /** 满多少次才计算失败率 */
        private Integer minimumNumberOfCalls;
        /** HALF_OPEN 时允许试探的并发数 */
        private Integer permittedCallsInHalfOpen;
        /** OPEN 状态等待秒数 */
        private Integer waitDurationOpenSeconds;
        /** 失败率阈值（0-100） */
        private Float failureRateThreshold;
    }

    /**
     * 回调配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CallbackConfig {
        /**
         * 回调URL（相对路径，如 /api/ai/callback）
         */
        private String callbackPath;

        /**
         * 回调签名密钥（强烈建议配置）
         * 配置后所有回调必须携带正确签名，否则拒绝处理。
         */
        private String signatureSecret;

        /**
         * 签名头名称
         */
        @Builder.Default
        private String signatureHeader = "X-Signature";

        /**
         * 状态JSONPath
         */
        @Builder.Default
        private String statusPath = "$.status";

        /**
         * 结果JSONPath
         */
        @Builder.Default
        private String resultPath = "$.data";

        /**
         * 是否允许无签名验证的回调（高安全风险）
         * <p>
         * 默认为 false：未配置 signatureSecret 时拒绝所有回调请求。
         * 仅当第三方 AI 服务确实不支持签名验证时，才可显式设置为 true。
         * 生产环境强烈建议始终配置 signatureSecret 并保持此字段为 false。
         */
        @Builder.Default
        private boolean allowUnauthenticated = false;
    }

    /**
     * 轮询配置
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PollingConfig {
        /**
         * 轮询端点
         * 支持占位符: {taskId}, {task_id}, {runId}, {run_id}
         */
        @JsonAlias("pollingEndpoint")
        private String endpoint;

        /**
         * 轮询HTTP方法（GET或POST）
         * 默认GET，部分API（如阿里云、百度）需要POST方式轮询
         */
        @Builder.Default
        private String httpMethod = "GET";

        /**
         * POST轮询请求体模板
         * 支持占位符: {taskId}, {task_id}, {runId}, {run_id}
         * 仅当 httpMethod 为 POST 时生效
         * 示例: {"task_id": "{taskId}", "action": "query"}
         */
        private Map<String, Object> requestBodyTemplate;

        /**
         * 轮询间隔（毫秒）
         */
        @Builder.Default
        private Integer intervalMs = 2000;

        /**
         * 最大轮询次数
         */
        @Builder.Default
        private Integer maxAttempts = 60;

        /**
         * 状态JSONPath
         */
        @Builder.Default
        private String statusPath = "$.status";

        /**
         * 结果JSONPath
         */
        @Builder.Default
        private String resultPath = "$.data";

        /**
         * 成功状态值集合（支持多个可能的成功状态）
         */
        @Builder.Default
        private Set<String> successStatuses = Collections.singleton("succeeded");

        /**
         * 失败状态值集合（支持多个可能的失败状态）
         */
        @Builder.Default
        private Set<String> failedStatuses = Collections.singleton("failed");
    }

    /**
     * 转换为Map，供Groovy脚本使用
     *
     * @return 配置Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new java.util.HashMap<>();

        // 基础信息
        map.put("providerId", providerId);
        map.put("providerName", providerName);
        map.put("baseUrl", baseUrl);
        map.put("endpoint", endpoint);
        map.put("httpMethod", httpMethod);
        map.put("authType", authType);
        map.put("timeout", timeout);
        map.put("maxRetries", maxRetries);
        map.put("creditCost", creditCost);
        map.put("rateLimit", rateLimit);

        // 认证配置（敏感信息）
        if (authConfig != null) {
            map.put("authConfig", authConfig);
        }

        // 自定义请求头
        if (customHeaders != null) {
            map.put("customHeaders", customHeaders);
        }

        // 回调配置
        if (callbackConfig != null) {
            Map<String, Object> cbMap = new java.util.HashMap<>();
            cbMap.put("callbackPath", callbackConfig.getCallbackPath());
            cbMap.put("signatureSecret", callbackConfig.getSignatureSecret());
            cbMap.put("signatureHeader", callbackConfig.getSignatureHeader());
            cbMap.put("statusPath", callbackConfig.getStatusPath());
            cbMap.put("resultPath", callbackConfig.getResultPath());
            map.put("callbackConfig", cbMap);
        }

        // 轮询配置
        if (pollingConfig != null) {
            Map<String, Object> pollMap = new java.util.HashMap<>();
            pollMap.put("endpoint", pollingConfig.getEndpoint());
            pollMap.put("httpMethod", pollingConfig.getHttpMethod());
            pollMap.put("requestBodyTemplate", pollingConfig.getRequestBodyTemplate());
            pollMap.put("intervalMs", pollingConfig.getIntervalMs());
            pollMap.put("maxAttempts", pollingConfig.getMaxAttempts());
            pollMap.put("statusPath", pollingConfig.getStatusPath());
            pollMap.put("resultPath", pollingConfig.getResultPath());
            pollMap.put("successStatuses", pollingConfig.getSuccessStatuses());
            pollMap.put("failedStatuses", pollingConfig.getFailedStatuses());
            map.put("pollingConfig", pollMap);
        }

        // 响应模式
        if (supportedModes != null) {
            map.put("supportedModes", supportedModes.stream()
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toSet()));
        }

        // TEXT 类型专属字段
        if (providerType != null) {
            map.put("providerType", providerType);
        }
        if (llmProviderId != null) {
            map.put("llmProviderId", llmProviderId);
        }
        if (systemPrompt != null) {
            map.put("systemPrompt", systemPrompt);
        }
        if (responseSchema != null) {
            map.put("responseSchema", responseSchema);
        }
        if (multimodalConfig != null) {
            map.put("multimodalConfig", multimodalConfig);
        }

        return map;
    }
}
