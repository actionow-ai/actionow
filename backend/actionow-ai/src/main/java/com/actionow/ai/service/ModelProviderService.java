package com.actionow.ai.service;

import com.actionow.ai.entity.ModelProvider;
import com.actionow.ai.plugin.model.PluginConfig;
import com.actionow.common.core.result.PageResult;

import java.util.List;
import java.util.Optional;

/**
 * 模型提供商服务接口
 *
 * @author Actionow
 */
public interface ModelProviderService {

    /**
     * 创建提供商
     *
     * @param provider 提供商实体
     * @return 创建后的提供商
     */
    ModelProvider create(ModelProvider provider);

    /**
     * 更新提供商
     *
     * @param provider 提供商实体
     * @return 更新后的提供商
     */
    ModelProvider update(ModelProvider provider);

    /**
     * 根据ID查询
     *
     * @param id 提供商ID
     * @return 提供商
     */
    Optional<ModelProvider> findById(String id);

    /**
     * 根据ID查询（必须存在）
     *
     * @param id 提供商ID
     * @return 提供商
     * @throws IllegalArgumentException 如果不存在
     */
    ModelProvider getById(String id);

    /**
     * 删除提供商
     *
     * @param id 提供商ID
     */
    void delete(String id);

    /**
     * 查询所有启用的提供商
     *
     * @return 提供商列表
     */
    List<ModelProvider> findAllEnabled();

    /**
     * 分页查询提供商
     *
     * @param current      当前页码
     * @param size         每页大小
     * @param providerType 提供商类型（可选）
     * @param enabled      是否启用（可选）
     * @param name         名称模糊搜索（可选）
     * @return 分页结果
     */
    PageResult<ModelProvider> findPage(Long current, Long size, String providerType, Boolean enabled, String name);

    /**
     * 根据类型查询启用的提供商
     *
     * @param providerType 提供商类型
     * @return 提供商列表
     */
    List<ModelProvider> findEnabledByType(String providerType);

    /**
     * 按可见性过滤后的启用 provider 列表（按 workspace）
     *
     * 可见性规则：
     *   PUBLIC    → 所有 workspace 可见
     *   INTERNAL  → 仅 workspace.is_internal=true 可见
     *   WHITELIST → 仅在 t_provider_workspace_whitelist 中的 workspace 可见
     *
     * @param providerType 提供商类型
     * @param workspaceId  当前 workspace ID（null/空 时仅返回 PUBLIC）
     * @return 该 workspace 可见的 provider 列表
     */
    List<ModelProvider> findVisibleEnabledByType(String providerType, String workspaceId);

    /**
     * 根据插件ID查询
     *
     * @param pluginId 插件ID
     * @return 提供商列表
     */
    List<ModelProvider> findByPluginId(String pluginId);

    /**
     * 根据名称精确查询
     *
     * @param name 提供商名称
     * @return 提供商列表
     */
    List<ModelProvider> findByName(String name);

    /**
     * 启用提供商
     *
     * @param id 提供商ID
     */
    void enable(String id);

    /**
     * 禁用提供商
     *
     * @param id 提供商ID
     */
    void disable(String id);

    /**
     * 测试连接
     *
     * @param id 提供商ID
     * @return 测试结果
     */
    TestConnectionResult testConnection(String id);

    /**
     * 同步提供商配置（从外部API同步参数）
     *
     * @param id 提供商ID
     */
    void sync(String id);

    /**
     * 转换为插件配置
     *
     * @param provider 提供商实体
     * @return 插件配置
     */
    PluginConfig toPluginConfig(ModelProvider provider);

    /**
     * 测试执行模型
     * 使用提供的输入参数实际调用模型，返回执行结果
     *
     * @param id     提供商ID
     * @param inputs 测试输入参数
     * @param responseMode 响应模式（可选）
     * @param timeoutOverride 超时覆盖（可选）
     * @return 执行结果
     */
    TestExecutionResult testExecution(String id, java.util.Map<String, Object> inputs,
                                       String responseMode, Integer timeoutOverride);

    /**
     * 复制模型提供商
     * 基于现有提供商创建新的提供商（复制所有配置）
     *
     * @param id            源提供商ID
     * @param newName       新提供商名称
     * @param newDescription 新提供商描述（可选）
     * @param enabled       是否启用
     * @return 新创建的提供商
     */
    ModelProvider copy(String id, String newName, String newDescription, Boolean enabled);

    /**
     * 连接测试结果
     */
    record TestConnectionResult(boolean connected, String message, Long latencyMs) {
        public static TestConnectionResult success(long latencyMs) {
            return new TestConnectionResult(true, null, latencyMs);
        }

        public static TestConnectionResult failure(String message) {
            return new TestConnectionResult(false, message, null);
        }
    }

    /**
     * 模型测试执行结果
     */
    record TestExecutionResult(
            boolean success,
            String executionId,
            String status,
            java.util.Map<String, Object> outputs,
            java.util.List<java.util.Map<String, Object>> assets,
            String errorCode,
            String errorMessage,
            Long elapsedTimeMs,
            Object rawResponse
    ) {
        public static TestExecutionResult success(String executionId, java.util.Map<String, Object> outputs,
                                                   java.util.List<java.util.Map<String, Object>> assets,
                                                   Long elapsedTimeMs, Object rawResponse) {
            return new TestExecutionResult(true, executionId, "succeeded", outputs, assets,
                    null, null, elapsedTimeMs, rawResponse);
        }

        public static TestExecutionResult failure(String executionId, String errorCode, String errorMessage) {
            return new TestExecutionResult(false, executionId, "failed", null, null,
                    errorCode, errorMessage, null, null);
        }
    }
}
