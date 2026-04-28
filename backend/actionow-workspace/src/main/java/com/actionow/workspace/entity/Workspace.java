package com.actionow.workspace.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.actionow.common.data.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 工作空间实体
 *
 * @author Actionow
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "t_workspace", autoResultMap = true)
public class Workspace extends BaseEntity {

    /**
     * 工作空间名称
     */
    private String name;

    /**
     * URL友好标识，唯一
     */
    private String slug;

    /**
     * 工作空间描述
     */
    private String description;

    /**
     * 工作空间Logo URL
     */
    @TableField("logo_url")
    private String logoUrl;

    /**
     * 创建者用户ID
     */
    @TableField("owner_id")
    private String ownerId;

    /**
     * 租户Schema名称（用于数据隔离）
     * 格式: tenant_{workspaceId前8位}_{创建时间戳}
     */
    @TableField("schema_name")
    private String schemaName;

    /**
     * 工作空间状态: ACTIVE, SUSPENDED, DELETED
     */
    private String status;

    /**
     * 订阅计划类型: Free, Basic, Pro, Enterprise
     */
    @TableField("plan_type")
    private String planType;

    /**
     * 最大成员数（根据订阅计划）
     */
    @TableField("max_members")
    private Integer maxMembers;

    /**
     * 当前成员数量
     */
    @TableField("member_count")
    private Integer memberCount;

    /**
     * 是否为内部测试 workspace（用于看到 visibility=INTERNAL 的 provider）
     */
    @TableField("is_internal")
    private Boolean isInternal;

    /**
     * 空间级配置（JSONB）
     * 包含: features, defaults, limits, branding
     */
    @TableField(value = "config", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> config;

    /**
     * 删除时间（软删除时记录）
     */
    @TableField("deleted_at")
    private LocalDateTime deletedAt;
}
