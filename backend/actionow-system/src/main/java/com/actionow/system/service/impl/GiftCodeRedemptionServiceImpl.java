package com.actionow.system.service.impl;

import com.actionow.common.core.exception.BusinessException;
import com.actionow.common.core.result.Result;
import com.actionow.common.core.result.ResultCode;
import com.actionow.system.dto.GiftCodeRedeemRequest;
import com.actionow.system.dto.GiftCodeRedeemResponse;
import com.actionow.system.entity.GiftCode;
import com.actionow.system.entity.GiftCodeRedemption;
import com.actionow.system.feign.WalletFeignClient;
import com.actionow.system.feign.WalletTopupRequest;
import com.actionow.system.mapper.GiftCodeMapper;
import com.actionow.system.mapper.GiftCodeRedemptionMapper;
import com.actionow.system.service.GiftCodeRedemptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 礼包码兑换服务实现
 *
 * 兑换流程：
 *   1. 校验 code 状态/有效期/剩余次数
 *   2. 原子地 redeemed_count++（CAS 防超兑）
 *   3. 写 t_gift_code_redemption（UNIQUE 防同一用户重复兑换）
 *   4. 调用 wallet.topup（paymentOrderId=gift_{redemptionId} 保证幂等）
 *
 * @author Actionow
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GiftCodeRedemptionServiceImpl implements GiftCodeRedemptionService {

    private static final String OPERATOR_ID = "gift-code-system";
    private static final String PAYMENT_METHOD = "GIFT_CODE";

    private final GiftCodeMapper giftCodeMapper;
    private final GiftCodeRedemptionMapper redemptionMapper;
    private final WalletFeignClient walletFeignClient;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GiftCodeRedeemResponse redeem(GiftCodeRedeemRequest request,
                                         String workspaceId,
                                         String userId) {
        if (!StringUtils.hasText(workspaceId)) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "请先选择工作空间");
        }
        String code = request.getCode().trim().toUpperCase();
        GiftCode giftCode = giftCodeMapper.selectByCode(code);
        if (giftCode == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "兑换码不存在");
        }
        validate(giftCode);

        // 用户重复兑换前置检查（兜底由 UNIQUE 索引保证）
        if (redemptionMapper.existsByCodeAndUser(giftCode.getId(), userId)) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "您已兑换过该礼包码");
        }

        // CAS 占名额
        int updated = giftCodeMapper.incrementRedeemedCount(giftCode.getId());
        if (updated == 0) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "兑换码已被领完");
        }

        GiftCodeRedemption redemption = new GiftCodeRedemption();
        redemption.setGiftCodeId(giftCode.getId());
        redemption.setUserId(userId);
        redemption.setWorkspaceId(workspaceId);
        redemption.setPoints(giftCode.getPoints());
        try {
            redemptionMapper.insert(redemption);
        } catch (DuplicateKeyException ex) {
            // 同一用户在 CAS 之后才被识别为重复（极小概率竞态）
            throw new BusinessException(ResultCode.PARAM_INVALID, "您已兑换过该礼包码");
        }

        // 调用钱包入账，paymentOrderId 用于钱包侧的幂等
        WalletTopupRequest topup = new WalletTopupRequest();
        topup.setAmount(giftCode.getPoints());
        topup.setDescription("礼包码兑换: " + code);
        topup.setPaymentOrderId("gift_" + redemption.getId());
        topup.setPaymentMethod(PAYMENT_METHOD);

        Result<Object> result = walletFeignClient.topup(workspaceId, topup, OPERATOR_ID);
        if (result == null || !result.isSuccess()) {
            String error = result != null ? result.getMessage() : "钱包服务无响应";
            log.error("礼包码兑换钱包入账失败 code={} workspaceId={} error={}", code, workspaceId, error);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "积分入账失败：" + error);
        }

        // 兑完判断：刷一次 max
        if (giftCode.getMaxRedemptions() != null
                && giftCode.getRedeemedCount() != null
                && giftCode.getRedeemedCount() + 1 >= giftCode.getMaxRedemptions()) {
            giftCodeMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<GiftCode>()
                            .eq(GiftCode::getId, giftCode.getId())
                            .eq(GiftCode::getStatus, "ACTIVE")
                            .set(GiftCode::getStatus, "EXHAUSTED"));
        }

        log.info("礼包码兑换成功 code={} userId={} workspaceId={} points={}",
                code, userId, workspaceId, giftCode.getPoints());

        GiftCodeRedeemResponse resp = new GiftCodeRedeemResponse();
        resp.setCode(code);
        resp.setPoints(giftCode.getPoints());
        resp.setRedemptionId(redemption.getId());
        resp.setWorkspaceId(workspaceId);
        return resp;
    }

    private void validate(GiftCode giftCode) {
        if (!"ACTIVE".equals(giftCode.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_INVALID,
                    "DISABLED".equals(giftCode.getStatus()) ? "兑换码已被禁用"
                            : "EXHAUSTED".equals(giftCode.getStatus()) ? "兑换码已被领完"
                            : "兑换码已过期");
        }
        LocalDateTime now = LocalDateTime.now();
        if (giftCode.getValidFrom() != null && now.isBefore(giftCode.getValidFrom())) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "兑换码尚未生效");
        }
        if (giftCode.getValidUntil() != null && now.isAfter(giftCode.getValidUntil())) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "兑换码已过期");
        }
        if (giftCode.getRedeemedCount() != null
                && giftCode.getMaxRedemptions() != null
                && giftCode.getRedeemedCount() >= giftCode.getMaxRedemptions()) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "兑换码已被领完");
        }
    }
}
