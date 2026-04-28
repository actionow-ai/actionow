package com.actionow.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 添加 provider 白名单请求
 *
 * @author Actionow
 */
@Data
public class AddProviderWhitelistRequest {

    @NotBlank(message = "workspaceId 不能为空")
    private String workspaceId;

    /**
     * 备注: 例如灰度批次说明
     */
    private String note;
}
