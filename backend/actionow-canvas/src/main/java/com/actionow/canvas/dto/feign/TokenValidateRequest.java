package com.actionow.canvas.dto.feign;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token验证请求
 *
 * @author Actionow
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenValidateRequest {

    /**
     * JWT token字符串
     */
    private String token;
}
