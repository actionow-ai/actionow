package com.actionow.system.mapper;

import com.actionow.system.entity.GiftCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 礼包码 Mapper
 *
 * @author Actionow
 */
@Mapper
public interface GiftCodeMapper extends BaseMapper<GiftCode> {

    default GiftCode selectByCode(String code) {
        return selectOne(new LambdaQueryWrapper<GiftCode>()
                .eq(GiftCode::getCode, code));
    }

    /**
     * 原子地增加 redeemed_count，仅在 redeemed_count < max_redemptions 时成功。
     * 返回受影响行数，1=成功，0=已耗尽。
     */
    @Update("UPDATE t_gift_code SET redeemed_count = redeemed_count + 1, " +
            "updated_at = now() " +
            "WHERE id = #{id}::uuid AND deleted = 0 AND redeemed_count < max_redemptions")
    int incrementRedeemedCount(@Param("id") String id);
}
