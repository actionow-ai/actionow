package com.actionow.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 批量作业子项实体
 * 每个子项 1:1 映射到一个 Task
 *
 * @author Actionow
 */
@Data
@TableName(value = "t_batch_job_item", autoResultMap = true)
public class BatchJobItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 所属批量作业ID
     */
    @TableField("batch_job_id")
    private String batchJobId;

    /**
     * 序号
     */
    @TableField("sequence_number")
    private Integer sequenceNumber;

    /**
     * 实体类型
     */
    @TableField("entity_type")
    private String entityType;

    /**
     * 实体ID
     */
    @TableField("entity_id")
    private String entityId;

    /**
     * 实体名称
     */
    @TableField("entity_name")
    private String entityName;

    /**
     * 生成参数（与 batch shared_params 合并，item 优先）
     */
    @TableField(value = "params", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> params;

    /**
     * Provider ID（覆盖 batch 级别）
     */
    @TableField("provider_id")
    private String providerId;

    /**
     * 生成类型（覆盖 batch 级别）
     */
    @TableField("generation_type")
    private String generationType;

    /**
     * Pipeline 步骤引用
     */
    @TableField("pipeline_step_id")
    private String pipelineStepId;

    /**
     * 关联的 Task ID
     */
    @TableField("task_id")
    private String taskId;

    /**
     * 关联的 Asset ID
     */
    @TableField("asset_id")
    private String assetId;

    /**
     * 关联的 Relation ID
     */
    @TableField("relation_id")
    private String relationId;

    /**
     * 条件跳过: NONE / ASSET_EXISTS
     */
    @TableField("skip_condition")
    private String skipCondition;

    /**
     * 是否已跳过
     */
    private Boolean skipped;

    /**
     * 跳过原因
     */
    @TableField("skip_reason")
    private String skipReason;

    /**
     * 变体索引
     */
    @TableField("variant_index")
    private Integer variantIndex;

    /**
     * 变体种子
     */
    @TableField("variant_seed")
    private Long variantSeed;

    /**
     * 子项状态: PENDING / SUBMITTED / RUNNING / COMPLETED / FAILED / SKIPPED / CANCELLED
     */
    private String status;

    /**
     * 错误信息
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * 积分消耗
     */
    @TableField("credit_cost")
    private Long creditCost;

    /**
     * Provider fallback 重试次数（不含 Resilience4j 同 provider 内重试）
     */
    @TableField("retry_count")
    private Integer retryCount;

    /**
     * 已失败的 provider ID 列表，fallback 时排除
     */
    @TableField(value = "failed_provider_ids", typeHandler = JacksonTypeHandler.class)
    private List<String> failedProviderIds;

    /**
     * 最近一次失败时间
     */
    @TableField("last_error_at")
    private LocalDateTime lastErrorAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
