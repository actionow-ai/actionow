package com.actionow.canvas.feign;

import com.actionow.canvas.dto.feign.TokenValidateRequest;
import com.actionow.canvas.dto.feign.TokenValidateResponse;
import com.actionow.common.core.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 用户服务 Feign 客户端
 *
 * @author Actionow
 */
@FeignClient(
        name = "actionow-user",
        path = "/internal/users",
        fallbackFactory = UserFeignClientFallbackFactory.class
)
public interface UserFeignClient {

    /**
     * 验证Token并获取用户信息
     *
     * @param request Token验证请求
     * @return Token验证结果，包含用户信息
     */
    @PostMapping("/token/validate")
    Result<TokenValidateResponse> validateToken(@RequestBody TokenValidateRequest request);
}
