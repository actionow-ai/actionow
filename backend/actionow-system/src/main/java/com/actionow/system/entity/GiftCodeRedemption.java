package com.actionow.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldFill;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 礼包码兑换记录
 *
 * @author Actionow
 */
@Data
@TableName("t_gift_code_redemption")
public class GiftCodeRedemption {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String giftCodeId;

    private String userId;

    private String workspaceId;

    private Long points;

    private String transactionId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
