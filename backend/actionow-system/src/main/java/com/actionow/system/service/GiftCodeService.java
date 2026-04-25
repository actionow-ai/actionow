package com.actionow.system.service;

import com.actionow.common.core.result.PageResult;
import com.actionow.system.dto.GiftCodeRedemptionResponse;
import com.actionow.system.dto.GiftCodeRequest;
import com.actionow.system.dto.GiftCodeResponse;

/**
 * 礼包码服务接口（管理员侧）
 *
 * @author Actionow
 */
public interface GiftCodeService {

    GiftCodeResponse create(GiftCodeRequest request, String operatorId);

    GiftCodeResponse update(String id, GiftCodeRequest request, String operatorId);

    void delete(String id, String operatorId);

    GiftCodeResponse getById(String id);

    PageResult<GiftCodeResponse> listPage(Long current, Long size, String keyword, String status);

    PageResult<GiftCodeRedemptionResponse> listRedemptions(String giftCodeId, Long current, Long size);
}
