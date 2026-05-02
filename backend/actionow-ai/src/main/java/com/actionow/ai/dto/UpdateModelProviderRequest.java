package com.actionow.ai.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 更新模型提供商请求
 *
 * @author Actionow
 */
@Data
public class UpdateModelProviderRequest {

    /**
     * 提供商名称
     */
    private String name;

    /**
     * 提供商描述
     */
    private String description;

    /**
     * API基础URL
     */
    private String baseUrl;

    /**
     * Base URL 引用（与 baseUrl 互斥，引用系统配置中的 URL）
     */
    private String baseUrlRef;

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
     */
    private String authType;

    /**
     * 认证配置
     */
    private Map<String, Object> authConfig;

    /**
     * API Key 引用（引用系统配置中的密钥名称）
     */
    private String apiKeyRef;

    // ========== Groovy 脚本配置 ==========

    /**
     * 请求构建 Groovy 脚本（内联）
     * 优先级高于 requestBuilderTemplateId
     */
    private String requestBuilderScript;

    /**
     * 响应映射 Groovy 脚本（内联）
     * 优先级高于 responseMapperTemplateId
     */
    private String responseMapperScript;

    /**
     * 自定义逻辑 Groovy 脚本（内联）
     */
    private String customLogicScript;

    /**
     * 请求构建模板 ID 引用
     * 引用 t_groovy_template 表中的模板
     */
    private String requestBuilderTemplateId;

    /**
     * 响应映射模板 ID 引用
     * 引用 t_groovy_template 表中的模板
     */
    private String responseMapperTemplateId;

    /**
     * 自定义逻辑模板 ID 引用
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
     * 回调配置
     */
    private Map<String, Object> callbackConfig;

    /**
     * 轮询配置
     */
    private Map<String, Object> pollingConfig;

    /**
     * 单次调用积分消耗
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
     * 输入参数Schema
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
     * 输出参数Schema
     */
    private List<Map<String, Object>> outputSchema;

    /**
     * 图标URL
     */
    private String iconUrl;

    /**
     * 优先级
     */
    private Integer priority;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 可见性: PUBLIC / INTERNAL / WHITELIST
     */
    private String visibility;

    /**
     * 自定义请求头
     */
    private Map<String, String> customHeaders;

    // ========== 调用队列配置（部分字段为 null = 不更新） ==========

    /** RabbitMQ 队列名 */
    private String queueName;
    /** 消费者并发数 */
    private Integer queueConcurrency;
    /** consumer prefetch */
    private Integer queuePrefetch;
    /** 队列最大积压消息数（背压上限） */
    private Integer queueMaxLength;

    // ========== TEXT 类型专属字段 ==========

    /**
     * 关联的 LLM Provider ID（TEXT 类型使用）
     */
    private String llmProviderId;

    /**
     * 系统提示词（TEXT 类型使用）
     */
    private String systemPrompt;

    /**
     * 结构化输出 JSON Schema（TEXT 类型使用）
     */
    private Map<String, Object> responseSchema;

    /**
     * 多模态能力配置（TEXT 类型使用）
     */
    private Map<String, Object> multimodalConfig;

    /**
     * 动态定价规则
     */
    private Map<String, Object> pricingRules;

    /**
     * 动态定价 Groovy 脚本
     */
    private String pricingScript;
}
