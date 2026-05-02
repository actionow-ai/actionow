package com.actionow.ai.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.actionow.common.data.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 模型提供商配置实体
 * 统一管理各种AI模型的配置信息
 *
 * DDL 已拆分为 3 张表:
 *   - t_model_provider           (主表)
 *   - t_model_provider_script    (Groovy 脚本 & 定价)
 *   - t_model_provider_schema    (I/O Schema)
 *
 * 被拆出的字段标记为 @TableField(exist = false)，
 * 需从子表单独查询填充。
 *
 * @author Actionow
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "t_model_provider", autoResultMap = true)
public class ModelProvider extends BaseEntity {

    // ========== 主表字段 ==========

    /**
     * 提供商名称
     */
    private String name;

    /**
     * 提供商描述
     */
    private String description;

    /**
     * 插件ID
     * 如: groovy, generic-http
     */
    private String pluginId;

    /**
     * 插件类型
     * GROOVY, GENERIC_HTTP
     */
    private String pluginType;

    /**
     * 提供商类型（生成类型）
     * IMAGE, VIDEO, AUDIO, TEXT
     */
    private String providerType;

    /**
     * API基础URL
     */
    private String baseUrl;

    /**
     * API端点
     */
    private String endpoint;

    /**
     * HTTP方法
     */
    private String httpMethod;

    /**
     * 认证类型
     * API_KEY, AK_SK, BEARER, OAUTH2, CUSTOM
     */
    private String authType;

    /**
     * 认证配置（加密存储）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> authConfig;

    /**
     * API Key 引用
     * 引用 t_system_config.config_key，运行时解析 API Key
     */
    private String apiKeyRef;

    /**
     * Base URL 引用
     * 引用 t_system_config.config_key，运行时解析 Base URL
     */
    private String baseUrlRef;

    /**
     * 响应模式列表 ["BLOCKING","STREAMING","CALLBACK","POLLING"]
     * 替代原来的 4 个布尔字段
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> supportedModes;

    /**
     * 回调配置
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> callbackConfig;

    /**
     * 轮询配置
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> pollingConfig;

    /**
     * 单次调用积分消耗（静态兜底值）
     */
    private Long creditCost;

    /**
     * 每分钟请求限制
     */
    private Integer rateLimit;

    /**
     * 超时时间（毫秒）
     */
    private Integer timeout;

    /**
     * 最大重试次数
     */
    private Integer maxRetries;

    /**
     * 图标URL
     */
    private String iconUrl;

    /**
     * 优先级（数值越大优先级越高）
     */
    private Integer priority;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 可见性: PUBLIC=所有workspace可见, INTERNAL=仅 is_internal=true 的 workspace 可见, WHITELIST=按白名单
     */
    private String visibility;

    /**
     * 自定义请求头
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, String> customHeaders;

    /**
     * TEXT 类型专属配置 JSONB: {llmProviderId, systemPrompt, responseSchema, multimodalConfig}
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> textConfig;

    // ========== 调用队列配置（per-provider，未配置则走全局默认队列） ==========

    /** RabbitMQ 队列名；NULL 走 runtime.ai.queue_default_name */
    private String queueName;

    /** 消费者并发数；NULL 取 runtime.ai.queue_default_concurrency */
    private Integer queueConcurrency;

    /** consumer prefetch；NULL 取 runtime.ai.queue_default_prefetch */
    private Integer queuePrefetch;

    /** 队列最大积压消息数；满后新消息被 broker reject（背压） */
    private Integer queueMaxLength;

    // ========== 子表字段（exist = false，需从子表加载） ==========

    /**
     * 请求构建Groovy脚本（来自 t_model_provider_script）
     */
    @TableField(exist = false)
    private String requestBuilderScript;

    /**
     * 响应映射Groovy脚本（来自 t_model_provider_script）
     */
    @TableField(exist = false)
    private String responseMapperScript;

    /**
     * 自定义逻辑Groovy脚本（来自 t_model_provider_script）
     */
    @TableField(exist = false)
    private String customLogicScript;

    /**
     * 请求构建模板ID引用（来自 t_model_provider_script）
     */
    @TableField(exist = false)
    private String requestBuilderTemplateId;

    /**
     * 响应映射模板ID引用（来自 t_model_provider_script）
     */
    @TableField(exist = false)
    private String responseMapperTemplateId;

    /**
     * 自定义逻辑模板ID引用（来自 t_model_provider_script）
     */
    @TableField(exist = false)
    private String customLogicTemplateId;

    /**
     * 动态积分计算规则（来自 t_model_provider_script）
     */
    @TableField(exist = false)
    private Map<String, Object> pricingRules;

    /**
     * 动态积分计算 Groovy 脚本（来自 t_model_provider_script）
     */
    @TableField(exist = false)
    private String pricingScript;

    /**
     * 输入参数定义列表（来自 t_model_provider_schema）
     */
    @TableField(exist = false)
    private List<Map<String, Object>> inputSchema;

    /**
     * 输入参数分组列表（来自 t_model_provider_schema）
     */
    @TableField(exist = false)
    private List<Map<String, Object>> inputGroups;

    /**
     * 互斥参数组列表（来自 t_model_provider_schema）
     */
    @TableField(exist = false)
    private List<Map<String, Object>> exclusiveGroups;

    /**
     * 输出参数Schema（来自 t_model_provider_schema）
     */
    @TableField(exist = false)
    private List<Map<String, Object>> outputSchema;

    // ========== 兼容字段（exist = false，从 supportedModes 派生） ==========

    @TableField(exist = false)
    private Boolean supportsBlocking;

    @TableField(exist = false)
    private Boolean supportsStreaming;

    @TableField(exist = false)
    private Boolean supportsCallback;

    @TableField(exist = false)
    private Boolean supportsPolling;

    // ========== 兼容字段（exist = false，从 textConfig 派生） ==========

    @TableField(exist = false)
    private String llmProviderId;

    @TableField(exist = false)
    private String systemPrompt;

    @TableField(exist = false)
    private Map<String, Object> responseSchema;

    @TableField(exist = false)
    private Map<String, Object> multimodalConfig;

    // ========== 已移除字段（exist = false） ==========

    @TableField(exist = false)
    private LocalDateTime lastSyncedAt;

    @TableField(exist = false)
    private String syncStatus;

    @TableField(exist = false)
    private String syncMessage;

    // ========== 便捷方法：从 supportedModes 派生布尔值 ==========

    public Boolean getSupportsBlocking() {
        if (supportsBlocking != null) return supportsBlocking;
        return supportedModes != null && supportedModes.contains("BLOCKING");
    }

    public Boolean getSupportsStreaming() {
        if (supportsStreaming != null) return supportsStreaming;
        return supportedModes != null && supportedModes.contains("STREAMING");
    }

    public Boolean getSupportsCallback() {
        if (supportsCallback != null) return supportsCallback;
        return supportedModes != null && supportedModes.contains("CALLBACK");
    }

    public Boolean getSupportsPolling() {
        if (supportsPolling != null) return supportsPolling;
        return supportedModes != null && supportedModes.contains("POLLING");
    }

    // ========== 便捷方法：从 textConfig 派生字段 ==========

    public String getLlmProviderId() {
        if (llmProviderId != null) return llmProviderId;
        return textConfig != null ? (String) textConfig.get("llmProviderId") : null;
    }

    public String getSystemPrompt() {
        if (systemPrompt != null) return systemPrompt;
        return textConfig != null ? (String) textConfig.get("systemPrompt") : null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getResponseSchema() {
        if (responseSchema != null) return responseSchema;
        return textConfig != null ? (Map<String, Object>) textConfig.get("responseSchema") : null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMultimodalConfig() {
        if (multimodalConfig != null) return multimodalConfig;
        return textConfig != null ? (Map<String, Object>) textConfig.get("multimodalConfig") : null;
    }
}
