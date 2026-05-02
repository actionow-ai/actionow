package com.actionow.ai.plugin.queue;

import com.actionow.ai.config.AiRuntimeConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Provider 调用结果的 Redis 存储 + Pub/Sub 等待。
 *
 * 性能关键设计：
 * - 启动时**一次性**注册 PSubscribe `ai:invocation:done:*`（不是 per-await 注册）
 * - 千并发 await 只是 ConcurrentHashMap 的 latch 注册，零 Redis 订阅开销
 * - 一条 pubsub 消息到达后，按 channel 名称解析 requestId，唤醒对应 latch
 * - 跨节点也能拿到通知（Pub/Sub 广播到所有订阅者）
 */
@Slf4j
@Component
public class ProviderInvocationResultStore {

    private static final String KEY_PREFIX = "ai:invocation:";
    private static final String CHANNEL_PREFIX = "ai:invocation:done:";
    private static final String CHANNEL_PATTERN = "ai:invocation:done:*";

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer listenerContainer;
    private final AiRuntimeConfigService runtimeConfig;
    private final ObjectMapper objectMapper;

    /** requestId → 等待 latch（同节点等待方使用） */
    private final ConcurrentHashMap<String, CountDownLatch> waiters = new ConcurrentHashMap<>();

    private final MessageListener globalListener = (msg, pattern) -> {
        try {
            String channel = new String(msg.getChannel());
            if (!channel.startsWith(CHANNEL_PREFIX)) return;
            String requestId = channel.substring(CHANNEL_PREFIX.length());
            CountDownLatch latch = waiters.get(requestId);
            if (latch != null) {
                latch.countDown();
            }
        } catch (Exception e) {
            log.warn("[ResultStore] global listener failed: {}", e.getMessage());
        }
    };

    public ProviderInvocationResultStore(StringRedisTemplate redis,
                                          RedisMessageListenerContainer listenerContainer,
                                          AiRuntimeConfigService runtimeConfig,
                                          ObjectMapper objectMapper) {
        this.redis = redis;
        this.listenerContainer = listenerContainer;
        this.runtimeConfig = runtimeConfig;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        listenerContainer.addMessageListener(globalListener, new PatternTopic(CHANNEL_PATTERN));
        log.info("[ResultStore] subscribed to {}", CHANNEL_PATTERN);
    }

    @PreDestroy
    void shutdown() {
        try {
            listenerContainer.removeMessageListener(globalListener, new PatternTopic(CHANNEL_PATTERN));
        } catch (Exception ignore) {
            // container 可能已停
        }
    }

    public void save(ProviderInvocationResult result) {
        if (result == null || result.getRequestId() == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(result);
            redis.opsForValue().set(
                KEY_PREFIX + result.getRequestId(),
                json,
                Duration.ofSeconds(runtimeConfig.getQueueResultTtlSeconds()));
            if (result.isTerminal()) {
                redis.convertAndSend(CHANNEL_PREFIX + result.getRequestId(), result.getStatus().name());
            }
        } catch (Exception e) {
            log.error("[ResultStore] failed to save result for {}: {}", result.getRequestId(), e.getMessage(), e);
        }
    }

    public ProviderInvocationResult get(String requestId) {
        if (requestId == null) {
            return null;
        }
        String json = redis.opsForValue().get(KEY_PREFIX + requestId);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ProviderInvocationResult.class);
        } catch (Exception e) {
            log.warn("[ResultStore] failed to deserialize result for {}: {}", requestId, e.getMessage());
            return null;
        }
    }

    /**
     * 阻塞等待结果，直到状态变为 SUCCESS / FAILED 或超时。
     * 性能：零 Redis 订阅开销（仅 CHM 操作）；唤醒由全局 listener 完成。
     *
     * @return 结果；超时仍返回最新（可能是 PENDING）
     */
    public ProviderInvocationResult await(String requestId, long timeoutMs) throws InterruptedException {
        ProviderInvocationResult immediate = get(requestId);
        if (immediate != null && immediate.isTerminal()) {
            return immediate;
        }

        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch existing = waiters.putIfAbsent(requestId, latch);
        if (existing != null) {
            latch = existing;
        }

        try {
            // 处理 race：注册 latch 后立即查一次，防止结果在注册前已写入
            ProviderInvocationResult mid = get(requestId);
            if (mid != null && mid.isTerminal()) {
                return mid;
            }

            long deadline = System.currentTimeMillis() + timeoutMs;
            // 主路径：globalListener countDown 唤醒
            // 兜底：每 2s 主动 poll 一次（防消息丢失，廉价）
            while (System.currentTimeMillis() < deadline) {
                long remain = Math.min(2000L, deadline - System.currentTimeMillis());
                if (remain <= 0) break;
                if (latch.await(remain, TimeUnit.MILLISECONDS)) {
                    return get(requestId);
                }
                ProviderInvocationResult poll = get(requestId);
                if (poll != null && poll.isTerminal()) {
                    return poll;
                }
            }
            return get(requestId);
        } finally {
            waiters.remove(requestId, latch);
        }
    }

    public void delete(String requestId) {
        redis.delete(KEY_PREFIX + requestId);
    }
}
