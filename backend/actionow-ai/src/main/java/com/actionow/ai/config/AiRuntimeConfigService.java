package com.actionow.ai.config;

import com.actionow.common.redis.config.RuntimeConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * AI 模块运行时配置服务
 * 管理 AI 轮询、限流、重试、Groovy 执行等核心运行参数
 *
 * @author Actionow
 */
@Slf4j
@Component
public class AiRuntimeConfigService extends RuntimeConfigService {

    // ==================== 配置键常量 ====================

    public static final String MAX_ACTIVE_POLLS             = "runtime.ai.max_active_polls";
    public static final String DEFAULT_RATE_LIMIT           = "runtime.ai.default_rate_limit";
    public static final String DEFAULT_MAX_RETRIES          = "runtime.ai.default_max_retries";
    public static final String GROOVY_MAX_EXECUTION_TIME_MS = "runtime.ai.groovy_max_execution_time_ms";
    public static final String FAILURE_RATE_THRESHOLD       = "runtime.ai.failure_rate_threshold";
    public static final String ALERT_ENABLED               = "runtime.ai.alert_enabled";
    public static final String ALERT_ERROR_RATE_THRESHOLD  = "runtime.ai.alert_error_rate_threshold";
    public static final String ALERT_RESPONSE_TIME_THRESHOLD_MS = "runtime.ai.alert_response_time_threshold_ms";
    public static final String ALERT_CONSECUTIVE_FAILURES  = "runtime.ai.alert_consecutive_failures_threshold";
    public static final String HTTP_CONNECT_TIMEOUT_MS     = "runtime.ai.http_connect_timeout_ms";
    public static final String HTTP_READ_TIMEOUT_SECONDS   = "runtime.ai.http_read_timeout_seconds";
    public static final String HTTP_MAX_CONNECTIONS         = "runtime.ai.http_max_connections";
    public static final String HTTP_MAX_CONNECTIONS_PER_ROUTE = "runtime.ai.http_max_connections_per_route";
    public static final String HTTP_MAX_IN_MEMORY_SIZE_BYTES = "runtime.ai.http_max_in_memory_size_bytes";

    // ---- Resilience (CircuitBreaker / Retry / RateLimiter) ----
    public static final String CB_SLIDING_WINDOW_SIZE              = "runtime.ai.cb_sliding_window_size";
    public static final String CB_MINIMUM_CALLS                    = "runtime.ai.cb_minimum_calls";
    public static final String CB_HALF_OPEN_PERMITTED_CALLS        = "runtime.ai.cb_half_open_permitted_calls";
    public static final String CB_WAIT_DURATION_OPEN_STATE_SECONDS = "runtime.ai.cb_wait_duration_open_state_seconds";
    public static final String RETRY_WAIT_DURATION_MS              = "runtime.ai.retry_wait_duration_ms";
    public static final String RATE_LIMIT_REFRESH_PERIOD_SECONDS   = "runtime.ai.rate_limit_refresh_period_seconds";

    // ---- Queue (Provider invocation) ----
    public static final String QUEUE_DEFAULT_NAME                  = "runtime.ai.queue_default_name";
    public static final String QUEUE_DEFAULT_CONCURRENCY           = "runtime.ai.queue_default_concurrency";
    public static final String QUEUE_DEFAULT_PREFETCH              = "runtime.ai.queue_default_prefetch";
    public static final String QUEUE_DEFAULT_MAX_LENGTH            = "runtime.ai.queue_default_max_length";
    public static final String QUEUE_RESULT_TTL_SECONDS            = "runtime.ai.queue_result_ttl_seconds";
    public static final String QUEUE_SUBMIT_TIMEOUT_MS             = "runtime.ai.queue_submit_timeout_ms";
    public static final String QUEUE_MESSAGE_TTL_SECONDS           = "runtime.ai.queue_message_ttl_seconds";

    // ---- Bulkhead ----
    public static final String BULKHEAD_MAX_CONCURRENT             = "runtime.ai.bulkhead_max_concurrent";

    public AiRuntimeConfigService(StringRedisTemplate redisTemplate,
                                   RedisMessageListenerContainer listenerContainer) {
        super(redisTemplate, listenerContainer);
    }

    @Override
    protected String getPrefix() {
        return "runtime.ai";
    }

    @Override
    protected void registerDefaults(Map<String, String> defaults) {
        defaults.put(MAX_ACTIVE_POLLS, "100");
        defaults.put(DEFAULT_RATE_LIMIT, "60");
        defaults.put(DEFAULT_MAX_RETRIES, "3");
        // 300s 默认值：满足大视频上传场景（OssBinding.uploadFromUrl 会从外部下载并上传到 OSS，
        // 视频资源可能需要数分钟）。请求/响应映射脚本实际执行时间很短，大值只是兜底。
        defaults.put(GROOVY_MAX_EXECUTION_TIME_MS, "300000");
        defaults.put(FAILURE_RATE_THRESHOLD, "50");
        defaults.put(ALERT_ENABLED, "true");
        defaults.put(ALERT_ERROR_RATE_THRESHOLD, "0.1");
        defaults.put(ALERT_RESPONSE_TIME_THRESHOLD_MS, "30000");
        defaults.put(ALERT_CONSECUTIVE_FAILURES, "5");
        defaults.put(HTTP_CONNECT_TIMEOUT_MS, "10000");
        defaults.put(HTTP_READ_TIMEOUT_SECONDS, "60");
        defaults.put(HTTP_MAX_CONNECTIONS, "500");
        defaults.put(HTTP_MAX_CONNECTIONS_PER_ROUTE, "50");
        // 32MB 响应缓冲：平衡 OOM 防护（10 并发 × 32MB = 320MB）与大图兼容性（支持 4K Base64）
        defaults.put(HTTP_MAX_IN_MEMORY_SIZE_BYTES, String.valueOf(32 * 1024 * 1024));

        // Resilience 默认值
        // CB_HALF_OPEN_PERMITTED_CALLS 从 3 提升到 10：避免 HALF_OPEN 时阻塞型生图调用因
        // 并发数瞬时超限被拒（旧值 3 在生图场景常误伤一波正常请求）。
        defaults.put(CB_SLIDING_WINDOW_SIZE, "10");
        defaults.put(CB_MINIMUM_CALLS, "5");
        defaults.put(CB_HALF_OPEN_PERMITTED_CALLS, "10");
        defaults.put(CB_WAIT_DURATION_OPEN_STATE_SECONDS, "30");
        defaults.put(RETRY_WAIT_DURATION_MS, "1000");
        defaults.put(RATE_LIMIT_REFRESH_PERIOD_SECONDS, "60");

        // Queue defaults — provider 调用统一走 RabbitMQ 队列消峰
        defaults.put(QUEUE_DEFAULT_NAME, "ai.provider.default");
        defaults.put(QUEUE_DEFAULT_CONCURRENCY, "20");
        defaults.put(QUEUE_DEFAULT_PREFETCH, "20");
        defaults.put(QUEUE_DEFAULT_MAX_LENGTH, "5000");
        defaults.put(QUEUE_RESULT_TTL_SECONDS, "3600");
        defaults.put(QUEUE_SUBMIT_TIMEOUT_MS, "120000");
        defaults.put(QUEUE_MESSAGE_TTL_SECONDS, "600");

        // Bulkhead — 单 provider 并发上限（worker 内部对上游的二次防护）
        defaults.put(BULKHEAD_MAX_CONCURRENT, "40");
    }

    // ==================== Named Getters ====================

    public int getMaxActivePolls() {
        return getInt(MAX_ACTIVE_POLLS);
    }

    public int getDefaultRateLimit() {
        return getInt(DEFAULT_RATE_LIMIT);
    }

    public int getDefaultMaxRetries() {
        return getInt(DEFAULT_MAX_RETRIES);
    }

    public long getGroovyMaxExecutionTimeMs() {
        return getLong(GROOVY_MAX_EXECUTION_TIME_MS);
    }

    public float getFailureRateThreshold() {
        return getFloat(FAILURE_RATE_THRESHOLD);
    }

    public boolean isAlertEnabled() {
        return getBoolean(ALERT_ENABLED);
    }

    public float getAlertErrorRateThreshold() {
        return getFloat(ALERT_ERROR_RATE_THRESHOLD);
    }

    public long getAlertResponseTimeThresholdMs() {
        return getLong(ALERT_RESPONSE_TIME_THRESHOLD_MS);
    }

    public int getAlertConsecutiveFailuresThreshold() {
        return getInt(ALERT_CONSECUTIVE_FAILURES);
    }

    public int getHttpConnectTimeoutMs() {
        return getInt(HTTP_CONNECT_TIMEOUT_MS);
    }

    public int getHttpReadTimeoutSeconds() {
        return getInt(HTTP_READ_TIMEOUT_SECONDS);
    }

    public int getHttpMaxConnections() {
        return getInt(HTTP_MAX_CONNECTIONS);
    }

    public int getHttpMaxConnectionsPerRoute() {
        return getInt(HTTP_MAX_CONNECTIONS_PER_ROUTE);
    }

    public int getHttpMaxInMemorySizeBytes() {
        return getInt(HTTP_MAX_IN_MEMORY_SIZE_BYTES);
    }

    public int getCbSlidingWindowSize() {
        return getInt(CB_SLIDING_WINDOW_SIZE);
    }

    public int getCbMinimumCalls() {
        return getInt(CB_MINIMUM_CALLS);
    }

    public int getCbHalfOpenPermittedCalls() {
        return getInt(CB_HALF_OPEN_PERMITTED_CALLS);
    }

    public int getCbWaitDurationOpenStateSeconds() {
        return getInt(CB_WAIT_DURATION_OPEN_STATE_SECONDS);
    }

    public long getRetryWaitDurationMs() {
        return getLong(RETRY_WAIT_DURATION_MS);
    }

    public int getRateLimitRefreshPeriodSeconds() {
        return getInt(RATE_LIMIT_REFRESH_PERIOD_SECONDS);
    }

    // ==================== Queue / Bulkhead Getters ====================

    public String getQueueDefaultName() {
        return getString(QUEUE_DEFAULT_NAME);
    }

    public int getQueueDefaultConcurrency() {
        return getInt(QUEUE_DEFAULT_CONCURRENCY);
    }

    public int getQueueDefaultPrefetch() {
        return getInt(QUEUE_DEFAULT_PREFETCH);
    }

    public int getQueueDefaultMaxLength() {
        return getInt(QUEUE_DEFAULT_MAX_LENGTH);
    }

    public int getQueueResultTtlSeconds() {
        return getInt(QUEUE_RESULT_TTL_SECONDS);
    }

    public long getQueueSubmitTimeoutMs() {
        return getLong(QUEUE_SUBMIT_TIMEOUT_MS);
    }

    public int getQueueMessageTtlSeconds() {
        return getInt(QUEUE_MESSAGE_TTL_SECONDS);
    }

    public int getBulkheadMaxConcurrent() {
        return getInt(BULKHEAD_MAX_CONCURRENT);
    }
}
