package com.actionow.ai.plugin.resilience;

import com.actionow.ai.config.AiRuntimeConfigService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * 插件弹性配置
 * 基于Resilience4j提供重试、熔断和限流能力
 *
 * @author Actionow
 */
@Slf4j
@Component
public class PluginResilienceConfig {

    // 默认配置（仅用于注册中心初始化默认值；运行时实际取值来自 AiRuntimeConfigService，可热更新）
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_RETRY_WAIT_MS = 1000;
    private static final int DEFAULT_RATE_LIMIT = 60;

    // 注册中心
    private final RetryRegistry retryRegistry;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    // 缓存
    private final ConcurrentHashMap<String, Retry> retryCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateLimiter> rateLimiterCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CircuitBreaker> circuitBreakerCache = new ConcurrentHashMap<>();

    private final AiRuntimeConfigService runtimeConfig;

    public PluginResilienceConfig(AiRuntimeConfigService runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
        // 注册中心仅用静态默认值初始化；每个 Provider 的 Retry/RateLimiter/CircuitBreaker
        // 在 getOrCreate* 中按 runtimeConfig 当时取值构建并缓存，热更新需 clearCache。
        RetryConfig defaultRetryConfig = RetryConfig.custom()
            .maxAttempts(DEFAULT_MAX_RETRIES)
            .waitDuration(Duration.ofMillis(DEFAULT_RETRY_WAIT_MS))
            .retryExceptions(IOException.class, TimeoutException.class)
            .retryOnException(this::shouldRetry)
            .build();
        this.retryRegistry = RetryRegistry.of(defaultRetryConfig);

        RateLimiterConfig defaultRateLimiterConfig = RateLimiterConfig.custom()
            .limitForPeriod(DEFAULT_RATE_LIMIT)
            .limitRefreshPeriod(Duration.ofSeconds(runtimeConfig.getRateLimitRefreshPeriodSeconds()))
            .timeoutDuration(Duration.ofSeconds(10))
            .build();
        this.rateLimiterRegistry = RateLimiterRegistry.of(defaultRateLimiterConfig);

        CircuitBreakerConfig defaultCircuitBreakerConfig = CircuitBreakerConfig.custom()
            .failureRateThreshold(runtimeConfig.getFailureRateThreshold())
            .waitDurationInOpenState(Duration.ofSeconds(runtimeConfig.getCbWaitDurationOpenStateSeconds()))
            .slidingWindowSize(runtimeConfig.getCbSlidingWindowSize())
            .minimumNumberOfCalls(runtimeConfig.getCbMinimumCalls())
            .permittedNumberOfCallsInHalfOpenState(runtimeConfig.getCbHalfOpenPermittedCalls())
            .recordExceptions(IOException.class, TimeoutException.class)
            .recordException(this::shouldRecordAsFailure)
            .build();
        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(defaultCircuitBreakerConfig);

        log.info("PluginResilienceConfig initialized: cb_slidingWindow={}, cb_minimumCalls={}, cb_halfOpenPermitted={}, cb_waitOpenSec={}, failureRateThreshold={}, retryWaitMs={}, rateLimitRefreshSec={}",
            runtimeConfig.getCbSlidingWindowSize(),
            runtimeConfig.getCbMinimumCalls(),
            runtimeConfig.getCbHalfOpenPermittedCalls(),
            runtimeConfig.getCbWaitDurationOpenStateSeconds(),
            runtimeConfig.getFailureRateThreshold(),
            runtimeConfig.getRetryWaitDurationMs(),
            runtimeConfig.getRateLimitRefreshPeriodSeconds());
    }

    /**
     * 获取或创建重试策略
     *
     * @param providerId 提供商ID
     * @param maxRetries 最大重试次数
     * @param waitDurationMs 重试等待时间(毫秒)
     * @return Retry实例
     */
    public Retry getOrCreateRetry(String providerId, int maxRetries, long waitDurationMs) {
        String key = providerId + "_retry";
        return retryCache.computeIfAbsent(key, k -> {
            int effectiveMaxRetries = maxRetries > 0 ? maxRetries : runtimeConfig.getDefaultMaxRetries();
            long effectiveWaitMs = waitDurationMs > 0 ? waitDurationMs : runtimeConfig.getRetryWaitDurationMs();
            RetryConfig config = RetryConfig.custom()
                .maxAttempts(effectiveMaxRetries)
                .waitDuration(Duration.ofMillis(effectiveWaitMs))
                .retryExceptions(IOException.class, TimeoutException.class)
                .retryOnException(this::shouldRetry)
                .build();

            Retry retry = retryRegistry.retry(key, config);

            // 添加事件监听
            retry.getEventPublisher()
                .onRetry(event -> log.warn("Retry attempt {} for provider {}: {}",
                    event.getNumberOfRetryAttempts(), providerId, event.getLastThrowable().getMessage()))
                .onSuccess(event -> log.debug("Request succeeded for provider {} after {} attempts",
                    providerId, event.getNumberOfRetryAttempts()))
                .onError(event -> log.error("All retries exhausted for provider {}: {}",
                    providerId, event.getLastThrowable().getMessage()));

            return retry;
        });
    }

    /**
     * 获取或创建限流器
     *
     * @param providerId 提供商ID
     * @param limitPerMinute 每分钟请求限制
     * @param providerTimeoutMs 提供商请求超时时间(毫秒)，Rate Limiter 等待时间不应小于此值
     * @return RateLimiter实例
     */
    public RateLimiter getOrCreateRateLimiter(String providerId, int limitPerMinute, int providerTimeoutMs) {
        String key = providerId + "_ratelimiter";
        return rateLimiterCache.computeIfAbsent(key, k -> {
            int effectiveLimit = limitPerMinute > 0 ? limitPerMinute : runtimeConfig.getDefaultRateLimit();
            // 等待时间至少为提供商超时时间，避免慢提供商因 Rate Limiter 超时而触发熔断
            long timeoutSeconds = Math.max(providerTimeoutMs / 1000, 30);
            RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(effectiveLimit)
                .limitRefreshPeriod(Duration.ofSeconds(runtimeConfig.getRateLimitRefreshPeriodSeconds()))
                .timeoutDuration(Duration.ofSeconds(timeoutSeconds))
                .build();

            RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(key, config);

            // 添加事件监听
            rateLimiter.getEventPublisher()
                .onSuccess(event -> log.debug("Rate limiter permitted request for provider {}", providerId))
                .onFailure(event -> log.warn("Rate limit exceeded for provider {}", providerId));

            return rateLimiter;
        });
    }

    /**
     * 获取或创建熔断器
     *
     * @param providerId 提供商ID
     * @return CircuitBreaker实例
     */
    public CircuitBreaker getOrCreateCircuitBreaker(String providerId) {
        String key = providerId + "_circuitbreaker";
        return circuitBreakerCache.computeIfAbsent(key, k -> {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(key);

            // 添加事件监听
            circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("Circuit breaker for provider {} transitioned from {} to {}",
                    providerId, event.getStateTransition().getFromState(), event.getStateTransition().getToState()))
                .onCallNotPermitted(event -> log.warn("Circuit breaker OPEN for provider {}, call not permitted", providerId))
                .onError(event -> log.debug("Circuit breaker recorded error for provider {}: {}",
                    providerId, event.getThrowable().getMessage()));

            return circuitBreaker;
        });
    }

    /**
     * 判断是否应该重试
     */
    private boolean shouldRetry(Throwable throwable) {
        if (throwable instanceof WebClientResponseException e) {
            int statusCode = e.getStatusCode().value();
            // 仅对5xx错误和特定4xx错误重试
            return statusCode >= 500 || statusCode == 429 || statusCode == 408;
        }
        // 对网络异常重试
        return throwable instanceof IOException ||
               throwable instanceof TimeoutException ||
               throwable.getCause() instanceof IOException;
    }

    /**
     * 判断是否应记录为熔断器失败
     */
    private boolean shouldRecordAsFailure(Throwable throwable) {
        if (throwable instanceof WebClientResponseException e) {
            int statusCode = e.getStatusCode().value();
            // 5xx错误和超时记录为失败
            return statusCode >= 500;
        }
        return throwable instanceof IOException || throwable instanceof TimeoutException;
    }

    /**
     * 清除指定提供商的弹性配置缓存
     */
    public void clearCache(String providerId) {
        retryCache.remove(providerId + "_retry");
        rateLimiterCache.remove(providerId + "_ratelimiter");
        circuitBreakerCache.remove(providerId + "_circuitbreaker");
        log.info("Cleared resilience cache for provider: {}", providerId);
    }

    /**
     * 清除所有缓存
     */
    public void clearAllCache() {
        retryCache.clear();
        rateLimiterCache.clear();
        circuitBreakerCache.clear();
        log.info("Cleared all resilience cache");
    }

    /**
     * 获取熔断器状态
     */
    public String getCircuitBreakerState(String providerId) {
        String key = providerId + "_circuitbreaker";
        CircuitBreaker cb = circuitBreakerCache.get(key);
        return cb != null ? cb.getState().name() : "NOT_CREATED";
    }

    /**
     * 获取限流器指标
     */
    public RateLimiterMetrics getRateLimiterMetrics(String providerId) {
        String key = providerId + "_ratelimiter";
        RateLimiter rl = rateLimiterCache.get(key);
        if (rl == null) {
            return null;
        }
        return new RateLimiterMetrics(
            rl.getMetrics().getAvailablePermissions(),
            rl.getMetrics().getNumberOfWaitingThreads()
        );
    }

    /**
     * 限流器指标
     */
    public record RateLimiterMetrics(int availablePermissions, int waitingThreads) {}
}
