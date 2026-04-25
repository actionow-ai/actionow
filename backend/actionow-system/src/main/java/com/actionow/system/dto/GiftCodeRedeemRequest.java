package com.actionow.system.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 礼包码兑换请求
 *
 * @author Actionow
 */
@Data
public class GiftCodeRedeemRequest {

    @NotBlank(message = "兑换码不能为空")
    private String code;
}
