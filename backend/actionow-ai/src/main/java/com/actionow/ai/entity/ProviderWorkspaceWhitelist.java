package com.actionow.ai.entity;

import com.actionow.common.data.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Provider 的 workspace 白名单 (visibility=WHITELIST 时生效)
 *
 * @author Actionow
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_provider_workspace_whitelist")
public class ProviderWorkspaceWhitelist extends BaseEntity {

    @TableField("provider_id")
    private String providerId;

    @TableField("workspace_id")
    private String workspaceId;

    private String note;
}
