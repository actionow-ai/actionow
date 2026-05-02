package com.actionow.ai.plugin.resilience;

import com.actionow.ai.config.AiRuntimeConfigService;
import com.actionow.ai.plugin.model.PluginConfig;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import jakarta.annotation.PostConstruct;
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
    private final BulkheadRegistry bulkheadRegistry;

    // 缓存
    private final ConcurrentHashMap<String, Retry> retryCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateLimiter> rateLimiterCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CircuitBreaker> circuitBreakerCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bulkhead> bulkheadCache = new ConcurrentHashMap<>();

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
            // 仅 IO 类故障计入熔断；TimeoutException 由 shouldRecordAsFailure 单独裁决
            // （连接超时算失败，响应体读取超时不算 — 生图本就慢，不应被熔断）
            .recordExceptions(IOException.class)
            .recordException(this::shouldRecordAsFailure)
            .build();
        this.circuitBreakerRegistry = CircuitBreakerRegistry.of(defaultCircuitBreakerConfig);

        // Bulkhead：默认基于 Semaphore，maxWaitDuration=0 → 超额立即拒绝
        // 与队列削峰是两层防护：队列控入口积压，Bulkhead 控 worker→上游瞬时并发
        BulkheadConfig defaultBulkheadConfig = BulkheadConfig.custom()
            .maxConcurrentCalls(runtimeConfig.getBulkheadMaxConcurrent())
            .maxWaitDuration(Duration.ZERO)
            .build();
        this.bulkheadRegistry = BulkheadRegistry.of(defaultBulkheadConfig);

        log.info("PluginResilienceConfig initialized: cb_slidingWindow={}, cb_minimumCalls={}, cb_halfOpenPermitted={}, cb_waitOpenSec={}, failureRateThreshold={}, retryWaitMs={}, rateLimitRefreshSec={}, bulkheadMax={}",
            runtimeConfig.getCbSlidingWindowSize(),
            runtimeConfig.getCbMinimumCalls(),
            runtimeConfig.getCbHalfOpenPermittedCalls(),
            runtimeConfig.getCbWaitDurationOpenStateSeconds(),
            runtimeConfig.getFailureRateThreshold(),
            runtimeConfig.getRetryWaitDurationMs(),
            runtimeConfig.getRateLimitRefreshPeriodSeconds(),
            runtimeConfig.getBulkheadMaxConcurrent());
    }

    /**
     * 获取或创建 Bulkhead（每 Provider 独立配额）。
     * 超额立即拒绝（maxWaitDuration=0），让上层把请求塞回队列或返回 BUSY，
     * 而不是把线程僵死在 acquire 等待上。
     */
    public Bulkhead getOrCreateBulkhead(String providerId) {
        String key = providerId + "_bulkhead";
        return bulkheadCache.computeIfAbsent(key, k -> {
            BulkheadConfig config = BulkheadConfig.custom()
                .maxConcurrentCalls(runtimeConfig.getBulkheadMaxConcurrent())
                .maxWaitDuration(Duration.ZERO)
                .build();
            Bulkhead bh = bulkheadRegistry.bulkhead(key, config);
            bh.getEventPublisher()
                .onCallRejected(event -> log.warn("Bulkhead rejected provider {}: max concurrent reached", providerId));
            return bh;
        });
    }

    /**
     * 注册到 RuntimeConfigService：CB / Retry / RateLimiter 相关键变更时
     * 主动失效缓存，让下次 getOrCreate* 重新读取最新值构建。
     * 解决"改了 Redis 但旧 CircuitBreaker 还在用启动快照"的问题。
     */
    @PostConstruct
    public void registerConfigChangeListener() {
        runtimeConfig.addChangeListener(key -> {
            if (key == null) {
                return;
            }
            if (key.startsWith("runtime.ai.cb_") || key.equals(AiRuntimeConfigService.FAILURE_RATE_THRESHOLD)) {
                log.info("[Resilience] CB-related config changed (key={}), invalidating circuit breaker cache", key);
                circuitBreakerCache.clear();
            } else if (key.startsWith("runtime.ai.retry_") || key.equals(AiRuntimeConfigService.DEFAULT_MAX_RETRIES)) {
                log.info("[Resilience] Retry config changed (key={}), invalidating retry cache", key);
                retryCache.clear();
            } else if (key.startsWith("runtime.ai.rate_limit_") || key.equals(AiRuntimeConfigService.DEFAULT_RATE_LIMIT)) {
                log.info("[Resilience] RateLimiter config changed (key={}), invalidating rate limiter cache", key);
                rateLimiterCache.clear();
            } else if (key.equals(AiRuntimeConfigService.BULKHEAD_MAX_CONCURRENT)) {
                log.info("[Resilience] Bulkhead config changed (key={}), invalidating bulkhead cache", key);
                bulkheadCache.clear();
            }
        });
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
     * 获取或创建熔断器（无 PluginConfig 重载，使用全局默认）
     */
    public CircuitBreaker getOrCreateCircuitBreaker(String providerId) {
        return getOrCreateCircuitBreaker(providerId, null);
    }

    /**
     * 获取或创建熔断器，支持 PluginConfig 级覆盖。
     *
     * 解析顺序：PluginConfig.circuitBreaker 字段 → 全局 runtimeConfig 默认。
     * IMAGE/VIDEO/AUDIO 类 provider 若未显式设置，自动启用 TIME_BASED 滑窗
     * （60 秒窗口，更适合慢请求频率不均的生图场景）。
     */
    public CircuitBreaker getOrCreateCircuitBreaker(String providerId, PluginConfig config) {
        String key = providerId + "_circuitbreaker";
        return circuitBreakerCache.computeIfAbsent(key, k -> {
            CircuitBreakerConfig cbConfig = buildCircuitBreakerConfig(config);
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(key, cbConfig);

            circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("Circuit breaker for provider {} transitioned from {} to {}",
                    providerId, event.getStateTransition().getFromState(), event.getStateTransition().getToState()))
                .onCallNotPermitted(event -> log.warn("Circuit breaker {} for provider {}, call not permitted",
                    circuitBreaker.getState(), providerId))
                .onError(event -> log.debug("Circuit breaker recorded error for provider {}: {}",
                    providerId, event.getThrowable().getMessage()));

            return circuitBreaker;
        });
    }

    /**
     * 根据 PluginConfig 覆盖 + 全局默认 + provider 类型，构建 CircuitBreakerConfig。
     */
    private CircuitBreakerConfig buildCircuitBreakerConfig(PluginConfig config) {
        PluginConfig.CircuitBreakerOverride override = config != null ? config.getCircuitBreaker() : null;
        boolean longBlocking = config != null && isLongBlockingProviderType(config.getProviderType());

        // 滑窗类型：override > 长耗时默认 TIME_BASED > COUNT_BASED
        CircuitBreakerConfig.SlidingWindowType windowType;
        if (override != null && override.getWindowType() != null) {
            windowType = "TIME_BASED".equalsIgnoreCase(override.getWindowType())
                ? CircuitBreakerConfig.SlidingWindowType.TIME_BASED
                : CircuitBreakerConfig.SlidingWindowType.COUNT_BASED;
        } else if (longBlocking) {
            windowType = CircuitBreakerConfig.SlidingWindowType.TIME_BASED;
        } else {
            windowType = CircuitBreakerConfig.SlidingWindowType.COUNT_BASED;
        }

        int windowSize;
        if (override != null && override.getSlidingWindowSize() != null) {
            windowSize = override.getSlidingWindowSize();
        } else if (windowType == CircuitBreakerConfig.SlidingWindowType.TIME_BASED) {
            // 时间窗默认 60s（生图调用率低，需要更长统计窗口才能积累有效样本）
            windowSize = 60;
        } else {
            windowSize = runtimeConfig.getCbSlidingWindowSize();
        }

        int minCalls = override != null && override.getMinimumNumberOfCalls() != null
            ? override.getMinimumNumberOfCalls()
            : runtimeConfig.getCbMinimumCalls();
        int halfOpenPermitted = override != null && override.getPermittedCallsInHalfOpen() != null
            ? override.getPermittedCallsInHalfOpen()
            : runtimeConfig.getCbHalfOpenPermittedCalls();
        int waitOpenSec = override != null && override.getWaitDurationOpenSeconds() != null
            ? override.getWaitDurationOpenSeconds()
            : runtimeConfig.getCbWaitDurationOpenStateSeconds();
        float failureRate = override != null && override.getFailureRateThreshold() != null
            ? override.getFailureRateThreshold()
            : runtimeConfig.getFailureRateThreshold();

        return CircuitBreakerConfig.custom()
            .slidingWindowType(windowType)
            .slidingWindowSize(windowSize)
            .minimumNumberOfCalls(minCalls)
            .permittedNumberOfCallsInHalfOpenState(halfOpenPermitted)
            .waitDurationInOpenState(Duration.ofSeconds(waitOpenSec))
            .failureRateThreshold(failureRate)
            .recordExceptions(IOException.class)
            .recordException(this::shouldRecordAsFailure)
            .build();
    }

    private boolean isLongBlockingProviderType(String providerType) {
        if (providerType == null) {
            return false;
        }
        String upper = providerType.toUpperCase();
        return "IMAGE".equals(upper) || "VIDEO".equals(upper) || "AUDIO".equals(upper);
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
     * 判断是否应记录为熔断器失败。
     *
     * 设计原则：只把"上游真的挂了"的信号计入失败率，把"上游正常但慢"排除。
     * - 5xx：上游真错 → 计失败
     * - IOException：连接异常 → 计失败
     * - TimeoutException：不计失败（生图/视频长耗时正常会触顶 timeout，
     *   不应让 CB 误判服务挂掉。真挂会以 IOException/5xx 体现）
     */
    private boolean shouldRecordAsFailure(Throwable throwable) {
        if (throwable instanceof WebClientResponseException e) {
            int statusCode = e.getStatusCode().value();
            return statusCode >= 500;
        }
        return throwable instanceof IOException;
    }

    /**
     * 清除指定提供商的弹性配置缓存
     */
    public void clearCache(String providerId) {
        retryCache.remove(providerId + "_retry");
        rateLimiterCache.remove(providerId + "_ratelimiter");
        circuitBreakerCache.remove(providerId + "_circuitbreaker");
        bulkheadCache.remove(providerId + "_bulkhead");
        log.info("Cleared resilience cache for provider: {}", providerId);
    }

    /**
     * 清除所有缓存
     */
    public void clearAllCache() {
        retryCache.clear();
        rateLimiterCache.clear();
        circuitBreakerCache.clear();
        bulkheadCache.clear();
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
