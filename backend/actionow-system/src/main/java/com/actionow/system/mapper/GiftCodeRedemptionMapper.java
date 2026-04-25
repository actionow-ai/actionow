package com.actionow.system.mapper;

import com.actionow.system.entity.GiftCodeRedemption;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 礼包码兑换记录 Mapper
 *
 * @author Actionow
 */
@Mapper
public interface GiftCodeRedemptionMapper extends BaseMapper<GiftCodeRedemption> {

    default boolean existsByCodeAndUser(String giftCodeId, String userId) {
        return selectCount(new LambdaQueryWrapper<GiftCodeRedemption>()
                .eq(GiftCodeRedemption::getGiftCodeId, giftCodeId)
                .eq(GiftCodeRedemption::getUserId, userId)) > 0;
    }

    default Long countByCode(String giftCodeId) {
        return selectCount(new LambdaQueryWrapper<GiftCodeRedemption>()
                .eq(GiftCodeRedemption::getGiftCodeId, giftCodeId));
    }
}
