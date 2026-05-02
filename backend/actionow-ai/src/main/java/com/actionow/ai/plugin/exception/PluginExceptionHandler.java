package com.actionow.ai.plugin.exception;

import com.actionow.common.core.result.Result;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.concurrent.TimeoutException;

/**
 * 插件全局异常处理器
 *
 * @author Actionow
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.actionow.ai")
@Order(1)
public class PluginExceptionHandler {

    /**
     * 处理插件未找到异常
     */
    @ExceptionHandler(PluginNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handlePluginNotFound(PluginNotFoundException e) {
        log.warn("Plugin not found: {}", e.getMessage());
        return Result.fail(e.getErrorCode(), e.getMessage());
    }

    /**
     * 处理提供商未找到异常
     */
    @ExceptionHandler(ProviderNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleProviderNotFound(ProviderNotFoundException e) {
        log.warn("Provider not found: {}", e.getMessage());
        return Result.fail(e.getErrorCode(), e.getMessage());
    }

    /**
     * 处理配置异常
     */
    @ExceptionHandler(PluginConfigException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleConfigException(PluginConfigException e) {
        log.warn("Plugin config error: {}", e.getMessage());
        return Result.fail(e.getErrorCode(), e.getMessage());
    }

    /**
     * 处理认证异常
     */
    @ExceptionHandler(PluginAuthException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleAuthException(PluginAuthException e) {
        log.warn("Plugin auth error: {}", e.getMessage());
        return Result.fail(e.getErrorCode(), e.getMessage());
    }

    /**
     * 处理执行异常
     */
    @ExceptionHandler(PluginExecutionException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleExecutionException(PluginExecutionException e) {
        log.error("Plugin execution error for provider {}: {}", e.getProviderId(), e.getMessage(), e);
        return Result.fail(e.getErrorCode(), e.getMessage());
    }

    /**
     * 处理限流异常
     */
    @ExceptionHandler({PluginRateLimitException.class, RequestNotPermitted.class})
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Result<Void> handleRateLimitException(Exception e) {
        log.warn("Rate limit exceeded: {}", e.getMessage());
        String message = e instanceof PluginRateLimitException pe ? pe.getMessage() : "请求过于频繁，请稍后重试";
        return Result.fail("RATE_LIMIT_EXCEEDED", message);
    }

    /**
     * 处理熔断异常。
     * - HALF_OPEN：返回 503 + Retry-After 短延迟，告知客户端立即重试
     * - OPEN / FORCED_OPEN：返回 503 + Retry-After 长延迟（wait_open_seconds）
     * - 未识别 (CallNotPermittedException 直达此处)：按 OPEN 处理
     */
    @ExceptionHandler({PluginCircuitBreakerException.class, CallNotPermittedException.class})
    public ResponseEntity<Result<Void>> handleCircuitBreakerException(Exception e) {
        String state;
        int retryAfter;
        String errorCode;
        String message;
        if (e instanceof PluginCircuitBreakerException pe) {
            state = pe.getState();
            retryAfter = pe.getRetryAfterSeconds();
            errorCode = pe.getErrorCode();
            message = pe.getMessage();
        } else {
            String msg = e.getMessage();
            state = (msg != null && msg.contains("HALF_OPEN")) ? "HALF_OPEN" : "OPEN";
            retryAfter = "HALF_OPEN".equals(state) ? 2 : 30;
            errorCode = "CIRCUIT_BREAKER_" + state;
            message = "HALF_OPEN".equals(state)
                ? "服务正在恢复中，请稍后立即重试"
                : "服务暂时不可用，请稍后重试";
        }
        log.warn("Circuit breaker rejected: state={}, retryAfter={}s, msg={}", state, retryAfter, e.getMessage());
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .headers(headers)
            .body(Result.fail(errorCode, message));
    }

    /**
     * 处理超时异常
     */
    @ExceptionHandler({PluginTimeoutException.class, TimeoutException.class})
    @ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
    public Result<Void> handleTimeoutException(Exception e) {
        log.warn("Request timeout: {}", e.getMessage());
        String message = e instanceof PluginTimeoutException pe ? pe.getMessage() : "请求超时，请稍后重试";
        return Result.fail("TIMEOUT", message);
    }

    /**
     * 处理WebClient响应异常
     */
    @ExceptionHandler(WebClientResponseException.class)
    public Result<Void> handleWebClientException(WebClientResponseException e) {
        log.error("WebClient error: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());

        String errorCode;
        String message;
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());

        switch (status) {
            case UNAUTHORIZED:
                errorCode = "AUTH_FAILED";
                message = "认证失败，请检查API密钥";
                break;
            case FORBIDDEN:
                errorCode = "ACCESS_DENIED";
                message = "访问被拒绝，权限不足";
                break;
            case NOT_FOUND:
                errorCode = "ENDPOINT_NOT_FOUND";
                message = "API端点不存在";
                break;
            case TOO_MANY_REQUESTS:
                errorCode = "RATE_LIMIT_EXCEEDED";
                message = "请求过于频繁，请稍后重试";
                break;
            case BAD_REQUEST:
                errorCode = "BAD_REQUEST";
                message = "请求参数错误: " + extractErrorMessage(e);
                break;
            default:
                if (status.is5xxServerError()) {
                    errorCode = "UPSTREAM_ERROR";
                    message = "上游服务错误: " + e.getStatusCode().value();
                } else {
                    errorCode = "HTTP_ERROR";
                    message = "HTTP请求失败: " + e.getStatusCode().value();
                }
        }

        return Result.fail(errorCode, message);
    }

    /**
     * 处理通用插件异常
     */
    @ExceptionHandler(PluginException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handlePluginException(PluginException e) {
        log.error("Plugin error: code={}, provider={}, message={}",
            e.getErrorCode(), e.getProviderId(), e.getMessage(), e);
        return Result.fail(e.getErrorCode(), e.getMessage());
    }

    /**
     * 从WebClient异常中提取错误消息
     */
    private String extractErrorMessage(WebClientResponseException e) {
        String body = e.getResponseBodyAsString();
        if (body != null && !body.isEmpty()) {
            // 尝试提取JSON中的message字段
            if (body.contains("\"message\"")) {
                int start = body.indexOf("\"message\"") + 11;
                int end = body.indexOf("\"", start);
                if (end > start) {
                    return body.substring(start, end);
                }
            }
            // 返回截断的原始body
            return body.length() > 200 ? body.substring(0, 200) + "..." : body;
        }
        return e.getStatusText();
    }
}
