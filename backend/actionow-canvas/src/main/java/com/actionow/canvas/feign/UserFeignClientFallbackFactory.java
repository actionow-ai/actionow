package com.actionow.canvas.feign;

import com.actionow.canvas.dto.feign.TokenValidateRequest;
import com.actionow.canvas.dto.feign.TokenValidateResponse;
import com.actionow.common.core.result.Result;
import com.actionow.common.core.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * User Feign 客户端降级工厂
 *
 * @author Actionow
 */
@Slf4j
@Component
public class UserFeignClientFallbackFactory implements FallbackFactory<UserFeignClient> {

    @Override
    public UserFeignClient create(Throwable cause) {
        log.error("调用User服务失败: {}", cause.getMessage());

        return new UserFeignClient() {
            @Override
            public Result<TokenValidateResponse> validateToken(TokenValidateRequest request) {
                log.warn("Token验证降级: User服务不可用");
                TokenValidateResponse response = TokenValidateResponse.builder()
                        .valid(false)
                        .errorMessage("User服务不可用，无法验证Token")
                        .build();
                return Result.fail(ResultCode.INTERNAL_ERROR.getCode(), "User服务不可用");
            }
        };
    }
}
