package com.actionow.system.service;

import com.actionow.system.dto.GiftCodeRedeemRequest;
import com.actionow.system.dto.GiftCodeRedeemResponse;

/**
 * 礼包码兑换服务（用户侧）
 *
 * @author Actionow
 */
public interface GiftCodeRedemptionService {

    /**
     * 兑换礼包码
     *
     * @param request    包含 code
     * @param workspaceId 当前工作空间 id
     * @param userId      当前用户 id
     */
    GiftCodeRedeemResponse redeem(GiftCodeRedeemRequest request, String workspaceId, String userId);
}
