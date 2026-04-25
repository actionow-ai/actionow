package com.actionow.system.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 创建/更新礼包码请求
 *
 * @author Actionow
 */
@Data
public class GiftCodeRequest {

    /**
     * 兑换码（创建时可留空，由后端自动生成）
     */
    private String code;

    private String name;

    private String description;

    @NotNull(message = "积分不能为空")
    @Min(value = 1, message = "积分必须大于 0")
    private Long points;

    private LocalDateTime validFrom;

    private LocalDateTime validUntil;

    @Min(value = 1, message = "可兑换次数必须大于 0")
    private Integer maxRedemptions;

    /**
     * ACTIVE / DISABLED （仅更新时生效）
     */
    private String status;
}
