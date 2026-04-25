package com.actionow.system.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 礼包码实体
 *
 * @author Actionow
 */
@Data
@TableName("t_gift_code")
public class GiftCode {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String code;

    private String name;

    private String description;

    private Long points;

    private LocalDateTime validFrom;

    private LocalDateTime validUntil;

    private Integer maxRedemptions;

    private Integer redeemedCount;

    /**
     * ACTIVE / DISABLED / EXHAUSTED / EXPIRED
     */
    private String status;

    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private String updatedBy;

    @TableLogic
    private Integer deleted;

    @Version
    private Integer version;
}
