package com.actionow.system.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 礼包码响应
 *
 * @author Actionow
 */
@Data
public class GiftCodeResponse {

    private String id;

    private String code;

    private String name;

    private String description;

    private Long points;

    private LocalDateTime validFrom;

    private LocalDateTime validUntil;

    private Integer maxRedemptions;

    private Integer redeemedCount;

    private String status;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
