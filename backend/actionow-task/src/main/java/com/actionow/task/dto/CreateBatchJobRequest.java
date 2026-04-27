package com.actionow.task.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 创建批量作业请求
 *
 * @author Actionow
 */
@Data
public class CreateBatchJobRequest {

    /**
     * 作业名称
     */
    private String name;

    /**
     * 作业描述
     */
    private String description;

    /**
     * 批量类型: SIMPLE / PIPELINE / VARIATION / SCOPE / AB_TEST
     */
    private String batchType;

    /**
     * 所属剧本ID
     */
    private String scriptId;

    /**
     * 错误处理策略: CONTINUE / STOP / RETRY_THEN_CONTINUE
     */
    private String errorStrategy;

    /**
     * 最大并发数（默认5）
     */
    private Integer maxConcurrency;

    /**
     * 优先级（默认5）
     */
    private Integer priority;

    /**
     * 共享参数模板（子项可覆盖）
     */
    private Map<String, Object> sharedParams;

    /**
     * 默认 Provider ID
     */
    private String providerId;

    /**
     * 默认生成类型: IMAGE / VIDEO / AUDIO / TEXT / TTS
     */
    private String generationType;

    /**
     * 关联的 Agent Mission ID
     */
    private String missionId;

    /**
     * 来源: API / AGENT / SCHEDULED
     */
    private String source;

    /**
     * 幂等键（可选，配合 missionId 生效）。
     * 当 (missionId, idempotencyKey) 命中已存在的 BatchJob 时，
     * 直接返回原作业，不再重复创建。由 Mission 委派工具基于稳定输入哈希生成。
     */
    private String idempotencyKey;

    // ==================== Scope 展开配置 ====================

    /**
     * 作用域实体类型（batchType=SCOPE 时使用）
     * EPISODE: 展开为该集下所有分镜
     * SCRIPT: 展开为该剧本下所有集的所有分镜
     * CHARACTER: 展开为该剧本下所有角色
     * SCENE: 展开为该剧本下所有场景
     * PROP: 展开为该剧本下所有道具
     */
    private String scopeEntityType;

    /**
     * 作用域实体ID（EPISODE 时为 episodeId，SCRIPT 时为 scriptId）
     */
    private String scopeEntityId;

    /**
     * 条件跳过: 展开时是否跳过已有 asset 的实体
     */
    private String skipCondition;

    // ==================== A/B 对比配置 ====================

    /**
     * A/B 对比 Provider 列表（batchType=AB_TEST 时使用）
     * 每个 Provider 会对同一实体各生成一次
     */
    private List<String> abTestProviderIds;

    /**
     * 子项列表
     * SCOPE 类型时可以为空（由系统自动展开）
     */
    @Size(max = 200, message = "单次最多提交200个子项")
    @Valid
    private List<BatchJobItemRequest> items;

    // ==================== Pipeline 配置 ====================

    /**
     * Pipeline 模板代码（batchType=PIPELINE 时使用）
     * 例如: TEXT_TO_PROMPT_TO_IMAGE, FULL_STORYBOARD
     */
    private String pipelineTemplate;

    /**
     * 自定义 Pipeline 步骤（batchType=PIPELINE 且不使用模板时）
     */
    private List<PipelineStepRequest> pipelineSteps;

    /**
     * Pipeline 步骤 Provider 覆盖（batchType=PIPELINE 且使用模板时）
     * key: 步骤编号（从1开始），value: providerId
     * 用于为模板的各步骤指定不同的 Provider
     */
    private Map<Integer, String> stepProviderOverrides;

    /**
     * 批量作业子项请求
     */
    @Data
    public static class BatchJobItemRequest {
        /**
         * 实体类型: STORYBOARD / CHARACTER / SCENE / PROP / STYLE
         */
        private String entityType;

        /**
         * 实体ID
         */
        private String entityId;

        /**
         * 实体名称
         */
        private String entityName;

        /**
         * 生成参数（与 batch shared_params 合并，item 优先）
         */
        private Map<String, Object> params;

        /**
         * Provider ID（覆盖 batch 级别）
         */
        private String providerId;

        /**
         * 生成类型（覆盖 batch 级别）
         */
        private String generationType;

        /**
         * 条件跳过: NONE / ASSET_EXISTS
         */
        private String skipCondition;

        /**
         * 变体数量（默认1，>1时生成多个变体）
         */
        private Integer variantCount;

        /**
         * 固定种子（可选）
         * 指定后所有变体使用此种子（确定性生成），不指定则使用随机种子
         */
        private Long seed;
    }

    /**
     * Pipeline 步骤请求（自定义 Pipeline 时使用）
     */
    @Data
    public static class PipelineStepRequest {
        /**
         * 步骤名称
         */
        private String name;

        /**
         * 步骤类型: GENERATE_TEXT / GENERATE_IMAGE / GENERATE_VIDEO / GENERATE_AUDIO / GENERATE_TTS
         */
        private String stepType;

        /**
         * 生成类型: TEXT / IMAGE / VIDEO / AUDIO / TTS
         */
        private String generationType;

        /**
         * Provider ID
         */
        private String providerId;

        /**
         * 参数模板（支持 {{steps[N].output.xxx}} 插值）
         */
        private Map<String, Object> paramsTemplate;

        /**
         * 依赖的步骤编号列表（从1开始）
         */
        private List<Integer> dependsOn;

        /**
         * 扇出数量（变体/多输出）
         */
        private Integer fanOutCount;
    }
}
