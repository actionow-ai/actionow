package com.actionow.task.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.actionow.common.data.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 批量作业实体
 *
 * @author Actionow
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "t_batch_job", autoResultMap = true)
public class BatchJob extends BaseEntity {

    /**
     * 工作空间ID
     */
    @TableField("workspace_id")
    private String workspaceId;

    /**
     * 创建者ID
     */
    @TableField("creator_id")
    private String creatorId;

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
    @TableField("batch_type")
    private String batchType;

    /**
     * 所属剧本ID
     */
    @TableField("script_id")
    private String scriptId;

    /**
     * 作用域实体类型: EPISODE / SCRIPT
     */
    @TableField("scope_entity_type")
    private String scopeEntityType;

    /**
     * 作用域实体ID
     */
    @TableField("scope_entity_id")
    private String scopeEntityId;

    /**
     * 错误处理策略: CONTINUE / STOP / RETRY_THEN_CONTINUE
     */
    @TableField("error_strategy")
    private String errorStrategy;

    /**
     * 最大并发数
     */
    @TableField("max_concurrency")
    private Integer maxConcurrency;

    /**
     * 优先级
     */
    private Integer priority;

    /**
     * 共享参数模板（子项可覆盖）
     */
    @TableField(value = "shared_params", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> sharedParams;

    /**
     * 默认 Provider ID
     */
    @TableField("provider_id")
    private String providerId;

    /**
     * 默认生成类型
     */
    @TableField("generation_type")
    private String generationType;

    /**
     * 作业状态: CREATED / RUNNING / PAUSED / COMPLETED / FAILED / CANCELLED
     */
    private String status;

    /**
     * 总子项数
     */
    @TableField("total_items")
    private Integer totalItems;

    /**
     * 已完成子项数
     */
    @TableField("completed_items")
    private Integer completedItems;

    /**
     * 失败子项数
     */
    @TableField("failed_items")
    private Integer failedItems;

    /**
     * 跳过子项数
     */
    @TableField("skipped_items")
    private Integer skippedItems;

    /**
     * 进度百分比 (0-100)
     */
    private Integer progress;

    /**
     * 预估积分消耗
     */
    @TableField("estimated_credits")
    private Long estimatedCredits;

    /**
     * 实际积分消耗
     */
    @TableField("actual_credits")
    private Long actualCredits;

    /**
     * 关联的 Agent Mission ID
     */
    @TableField("mission_id")
    private String missionId;

    /**
     * 来源: API / AGENT / SCHEDULED
     */
    private String source;

    /**
     * 幂等键（与 missionId 配合）。Mission 委派工具基于稳定输入计算 SHA-256，
     * 同 mission 内同 key 命中时直接返回已有作业，避免 LLM 单步重复调用造成 N 倍重复执行。
     */
    @TableField("idempotency_key")
    private String idempotencyKey;

    /**
     * 开始时间
     */
    @TableField("started_at")
    private LocalDateTime startedAt;

    /**
     * 完成时间
     */
    @TableField("completed_at")
    private LocalDateTime completedAt;
}
