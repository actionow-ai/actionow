package com.actionow.task.config;

import com.actionow.common.redis.config.RuntimeConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Task 模块运行时配置服务
 * 管理任务超时、重试、补偿、分布式锁等核心运行参数
 * 同时处理 runtime.task 和 runtime.mq 前缀
 *
 * @author Actionow
 */
@Slf4j
@Component
public class TaskRuntimeConfigService extends RuntimeConfigService {

    // ==================== 配置键常量 ====================

    public static final String DEFAULT_TIMEOUT_SECONDS          = "runtime.task.default_timeout_seconds";
    public static final String DEFAULT_MAX_RETRY                = "runtime.task.default_max_retry";
    public static final String COMPENSATION_MAX_RETRY_COUNT     = "runtime.task.compensation.max_retry_count";
    public static final String COMPENSATION_SCAN_INTERVAL_MS    = "runtime.task.compensation.scan_interval_ms";
    /** 补偿任务首次重试延迟（秒） */
    public static final String COMPENSATION_INITIAL_RETRY_DELAY_SECONDS = "runtime.task.compensation.initial_retry_delay_seconds";
    /** 补偿任务最大重试延迟（秒），指数退避封顶 */
    public static final String COMPENSATION_MAX_RETRY_DELAY_SECONDS     = "runtime.task.compensation.max_retry_delay_seconds";
    public static final String GENERATION_LOCK_EXPIRE_SECONDS   = "runtime.task.generation_lock_expire_seconds";
    public static final String MQ_PREFETCH_COUNT                = "runtime.mq.prefetch_count";
    /** POLLING 模式轮询扫描间隔（毫秒） */
    public static final String POLLING_SCAN_INTERVAL_MS         = "runtime.task.polling.scan_interval_ms";
    /** POLLING 模式每次扫描任务数 */
    public static final String POLLING_SCAN_BATCH_SIZE          = "runtime.task.polling.batch_size";
    /** Batch 作业无进展超时（毫秒） */
    public static final String BATCH_STALE_TIMEOUT_MS           = "runtime.task.batch.stale_timeout_ms";
    /** Task 调用 AI 服务 Feign 读取超时时间（毫秒） */
    public static final String AI_FEIGN_READ_TIMEOUT_MS         = "runtime.task.ai_feign_read_timeout_ms";
    /**
     * AI 任务队列并发 consumer 数（同时执行的 AI 生成任务数）
     * <p>
     * 【三层并发控制体系说明】
     * <pre>
     * 第 1 层（全局 MQ 并发）：MQ_TASK_CONCURRENCY / MQ_TASK_MAX_CONCURRENCY
     *   作用范围：全局所有工作空间的 AI 任务
     *   控制对象：RabbitMQ taskExecutorListener 的 Consumer 线程数
     *   调整方式：通过 Redis Pub/Sub 动态生效，无需重启
     *   优先级：最高（约束上限，其余层只能在此范围内工作）
     *
     * 第 2 层（工作空间 Batch 并发）：TaskRuntimeConfigService.WORKSPACE_BATCH_LIMIT
     *   作用范围：单个工作空间内的 Batch 子项并行数
     *   控制对象：BatchConcurrencyService 的 workspace 计数器（Redis INCR）
     *   调整方式：RuntimeConfig Redis key
     *   优先级：中（在全局 MQ 并发允许的前提下，再约束 Workspace 维度）
     *
     * 第 3 层（单 BatchJob 并发）：BatchJob.maxConcurrency
     *   作用范围：单个 BatchJob 内的子项并行数
     *   控制对象：BatchConcurrencyService 的 batch 计数器（Redis INCR）
     *   调整方式：BatchJob 创建时指定，默认值 5
     *   优先级：最低（在前两层允许的前提下，进一步精细控制单 Job 并行度）
     *
     * 生效顺序：MQ Consumer 先消费消息 → BatchConcurrencyService.tryAcquire() 同时检查 Layer2+Layer3
     * 全部通过才执行任务；任何一层阻塞则跳过（非阻塞式：批任务重新入队或等待下次扫描）
     * </pre>
     */
    public static final String MQ_TASK_CONCURRENCY              = "runtime.mq.task_concurrency";
    /** AI 任务队列并发 consumer 数上限（队列积压时自动扩展） */
    public static final String MQ_TASK_MAX_CONCURRENCY          = "runtime.mq.task_max_concurrency";
    /** Scope 展开后最大 item 数量 */
    public static final String SCOPE_MAX_ITEMS                  = "runtime.task.scope_max_items";
    /** 每个工作空间的最大批量并发数 */
    public static final String WORKSPACE_BATCH_LIMIT            = "runtime.task.workspace_batch_limit";
    /** Feign 连接超时（毫秒） */
    public static final String FEIGN_CONNECT_TIMEOUT_MS         = "runtime.task.feign_connect_timeout_ms";
    /** AI 服务 URL */
    public static final String AI_SERVICE_URL                   = "runtime.task.ai_service_url";
    /** taskExecutorListener 的注册 ID，与 @RabbitListener(id=...) 保持一致 */
    public static final String TASK_EXECUTOR_LISTENER_ID        = "taskExecutorListener";
    /** 启用 BatchJobItem provider 自动 fallback */
    public static final String PROVIDER_FALLBACK_ENABLED        = "runtime.task.provider_fallback_enabled";
    /** 单个 BatchJobItem 最多尝试 fallback 的次数（不含原始 provider） */
    public static final String PROVIDER_FALLBACK_MAX_ATTEMPTS   = "runtime.task.provider_fallback_max_attempts";

    /** 用于运行时调整 consumer 数量 */
    @Autowired
    private RabbitListenerEndpointRegistry endpointRegistry;

    public TaskRuntimeConfigService(StringRedisTemplate redisTemplate,
                                     RedisMessageListenerContainer listenerContainer) {
        super(redisTemplate, listenerContainer);
    }

    @Override
    protected String getPrefix() {
        // runtime.mq.* 键通过 defaults.containsKey() 兜底匹配
        return "runtime.task";
    }

    @Override
    protected void registerDefaults(Map<String, String> defaults) {
        defaults.put(DEFAULT_TIMEOUT_SECONDS, "300");
        defaults.put(DEFAULT_MAX_RETRY, "3");
        defaults.put(COMPENSATION_MAX_RETRY_COUNT, "5");
        defaults.put(COMPENSATION_SCAN_INTERVAL_MS, "30000");
        defaults.put(COMPENSATION_INITIAL_RETRY_DELAY_SECONDS, "30");
        defaults.put(COMPENSATION_MAX_RETRY_DELAY_SECONDS, "3600");
        defaults.put(GENERATION_LOCK_EXPIRE_SECONDS, "600");
        defaults.put(POLLING_SCAN_INTERVAL_MS, "15000");
        defaults.put(POLLING_SCAN_BATCH_SIZE, "50");
        defaults.put(BATCH_STALE_TIMEOUT_MS, "600000");
        defaults.put(AI_FEIGN_READ_TIMEOUT_MS, "120000");
        defaults.put(MQ_PREFETCH_COUNT, "10");
        defaults.put(MQ_TASK_CONCURRENCY, "5");
        defaults.put(MQ_TASK_MAX_CONCURRENCY, "10");
        defaults.put(SCOPE_MAX_ITEMS, "500");
        defaults.put(WORKSPACE_BATCH_LIMIT, "10");
        defaults.put(FEIGN_CONNECT_TIMEOUT_MS, "10000");
        defaults.put(AI_SERVICE_URL, "http://actionow-ai:8086");
        // Provider fallback 默认关闭，验证稳定后再开启
        defaults.put(PROVIDER_FALLBACK_ENABLED, "false");
        defaults.put(PROVIDER_FALLBACK_MAX_ATTEMPTS, "2");
    }

    /**
     * 配置变更回调：并发数变更时直接调整运行中的 consumer 数量，无需重启
     */
    @Override
    protected void onConfigChanged(String key, String oldValue, String newValue) {
        if (MQ_TASK_CONCURRENCY.equals(key) || MQ_TASK_MAX_CONCURRENCY.equals(key)) {
            adjustTaskConsumerConcurrency();
        }
    }

    /**
     * 调整 AI 任务 consumer 并发数
     */
    private void adjustTaskConsumerConcurrency() {
        try {
            SimpleMessageListenerContainer container = (SimpleMessageListenerContainer)
                    endpointRegistry.getListenerContainer(TASK_EXECUTOR_LISTENER_ID);
            if (container == null || !container.isRunning()) {
                log.warn("[RuntimeConfig] taskExecutorListener 未运行，跳过并发数调整");
                return;
            }
            int concurrency = getInt(MQ_TASK_CONCURRENCY);
            int maxConcurrency = getInt(MQ_TASK_MAX_CONCURRENCY);
            container.setMaxConcurrentConsumers(maxConcurrency);
            container.setConcurrentConsumers(concurrency);
            log.info("[RuntimeConfig] AI 任务 consumer 并发数已调整: concurrency={}, max={}",
                    concurrency, maxConcurrency);
        } catch (Exception e) {
            log.error("[RuntimeConfig] 调整 AI 任务 consumer 并发数失败", e);
        }
    }

    // ==================== Named Getters ====================

    public int getDefaultTimeoutSeconds() {
        return getInt(DEFAULT_TIMEOUT_SECONDS);
    }

    public int getDefaultMaxRetry() {
        return getInt(DEFAULT_MAX_RETRY);
    }

    public int getCompensationMaxRetryCount() {
        return getInt(COMPENSATION_MAX_RETRY_COUNT);
    }

    public long getCompensationScanIntervalMs() {
        return getLong(COMPENSATION_SCAN_INTERVAL_MS);
    }

    public int getCompensationInitialRetryDelaySeconds() {
        return getInt(COMPENSATION_INITIAL_RETRY_DELAY_SECONDS);
    }

    public int getCompensationMaxRetryDelaySeconds() {
        return getInt(COMPENSATION_MAX_RETRY_DELAY_SECONDS);
    }

    public long getGenerationLockExpireSeconds() {
        return getLong(GENERATION_LOCK_EXPIRE_SECONDS);
    }

    public long getPollingScanIntervalMs() {
        return getLong(POLLING_SCAN_INTERVAL_MS);
    }

    public int getPollingScanBatchSize() {
        return getInt(POLLING_SCAN_BATCH_SIZE);
    }

    public long getBatchStaleTimeoutMs() {
        return getLong(BATCH_STALE_TIMEOUT_MS);
    }

    public int getAiFeignReadTimeoutMs() {
        return getInt(AI_FEIGN_READ_TIMEOUT_MS);
    }

    public int getMqPrefetchCount() {
        return getInt(MQ_PREFETCH_COUNT);
    }

    public int getMqTaskConcurrency() {
        return getInt(MQ_TASK_CONCURRENCY);
    }

    public int getMqTaskMaxConcurrency() {
        return getInt(MQ_TASK_MAX_CONCURRENCY);
    }

    public int getScopeMaxItems() {
        return getInt(SCOPE_MAX_ITEMS);
    }

    public int getWorkspaceBatchLimit() {
        return getInt(WORKSPACE_BATCH_LIMIT);
    }

    public int getFeignConnectTimeoutMs() {
        return getInt(FEIGN_CONNECT_TIMEOUT_MS);
    }

    public String getAiServiceUrl() {
        return getString(AI_SERVICE_URL);
    }

    public boolean isProviderFallbackEnabled() {
        return getBoolean(PROVIDER_FALLBACK_ENABLED);
    }

    public int getProviderFallbackMaxAttempts() {
        return getInt(PROVIDER_FALLBACK_MAX_ATTEMPTS);
    }
}
