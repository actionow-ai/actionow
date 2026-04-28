package com.actionow.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 创建模型提供商请求
 *
 * @author Actionow
 */
@Data
public class CreateModelProviderRequest {

    /**
     * 提供商名称
     */
    @NotBlank(message = "名称不能为空")
    private String name;

    /**
     * 提供商描述
     */
    private String description;

    /**
     * 插件ID
     */
    @NotBlank(message = "插件ID不能为空")
    private String pluginId;

    /**
     * 插件类型
     */
    private String pluginType;

    /**
     * 提供商类型
     */
    @NotBlank(message = "提供商类型不能为空")
    private String providerType;

    /**
     * API基础URL
     */
    @NotBlank(message = "Base URL不能为空")
    private String baseUrl;

    /**
     * Base URL 引用（引用系统配置中的 URL）
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
    @NotBlank(message = "认证类型不能为空")
    private String authType;

    /**
     * 认证配置
     */
    @NotNull(message = "认证配置不能为空")
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
    private Boolean supportsBlocking = true;

    /**
     * 是否支持流式模式
     */
    private Boolean supportsStreaming = false;

    /**
     * 是否支持回调模式
     */
    private Boolean supportsCallback = false;

    /**
     * 是否支持轮询模式
     */
    private Boolean supportsPolling = false;

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
    private Long creditCost = 0L;

    /**
     * 每分钟请求限制
     */
    private Integer rateLimit;

    /**
     * 超时时间（毫秒）
     */
    private Integer timeout = 60000;

    /**
     * 最大重试次数
     */
    private Integer maxRetries = 3;

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
    private Integer priority = 0;

    /**
     * 是否启用
     */
    private Boolean enabled = true;

    /**
     * 可见性: PUBLIC(默认) / INTERNAL / WHITELIST
     */
    private String visibility = "PUBLIC";

    /**
     * 自定义请求头
     */
    private Map<String, String> customHeaders;
}
