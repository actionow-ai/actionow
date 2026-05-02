package com.actionow.canvas.dto.feign;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token验证响应（从用户服务返回）
 *
 * @author Actionow
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenValidateResponse {

    /**
     * Token是否有效
     */
    private boolean valid;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 用户名
     */
    private String username;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 错误信息（Token无效时）
     */
    private String errorMessage;
}
