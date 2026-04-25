package com.actionow.system.dto;

import lombok.Data;

/**
 * 礼包码兑换结果
 *
 * @author Actionow
 */
@Data
public class GiftCodeRedeemResponse {

    private String code;

    private Long points;

    private String redemptionId;

    private String workspaceId;
}
