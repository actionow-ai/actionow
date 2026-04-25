package com.actionow.system.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 礼包码兑换记录响应
 *
 * @author Actionow
 */
@Data
public class GiftCodeRedemptionResponse {

    private String id;

    private String giftCodeId;

    private String code;

    private String userId;

    private String workspaceId;

    private Long points;

    private LocalDateTime createdAt;
}
