package com.actionow.ai.plugin.queue;

import com.actionow.ai.config.AiRuntimeConfigService;
import com.actionow.ai.entity.ModelProvider;
import com.actionow.ai.service.ModelProviderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 解析 provider → QueueSpec。
 *
 * 解析顺序：
 *   1. ModelProvider.queueName 非空 → 使用 provider 自定义队列；
 *      字段缺失项落到全局默认值
 *   2. 否则使用全局默认队列（runtime.ai.queue_default_*）
 *
 * 同 provider 的 QueueSpec 缓存在 ConcurrentHashMap 中；
 * provider 变更时调用方需调 invalidate(providerId) 失效缓存。
 */
@Slf4j
@Component
public class ProviderQueueRouter {

    private final AiRuntimeConfigService runtimeConfig;
    private final ModelProviderService modelProviderService;
    private final ConcurrentHashMap<String, QueueSpec> cache = new ConcurrentHashMap<>();

    public ProviderQueueRouter(AiRuntimeConfigService runtimeConfig,
                               @Lazy ModelProviderService modelProviderService) {
        this.runtimeConfig = runtimeConfig;
        this.modelProviderService = modelProviderService;
    }

    public QueueSpec resolve(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return defaultSpec();
        }
        return cache.computeIfAbsent(providerId, this::resolveFresh);
    }

    public QueueSpec defaultSpec() {
        return QueueSpec.builder()
            .queueName(runtimeConfig.getQueueDefaultName())
            .concurrency(runtimeConfig.getQueueDefaultConcurrency())
            .prefetch(runtimeConfig.getQueueDefaultPrefetch())
            .maxLength(runtimeConfig.getQueueDefaultMaxLength())
            .messageTtlSeconds(runtimeConfig.getQueueMessageTtlSeconds())
            .defaultQueue(true)
            .build();
    }

    private QueueSpec resolveFresh(String providerId) {
        ModelProvider provider;
        try {
            provider = modelProviderService.getById(providerId);
        } catch (Exception e) {
            log.warn("[QueueRouter] provider lookup failed for {}, using default queue: {}", providerId, e.getMessage());
            return defaultSpec();
        }
        if (provider == null || provider.getQueueName() == null || provider.getQueueName().isBlank()) {
            return defaultSpec();
        }
        return QueueSpec.builder()
            .queueName(provider.getQueueName())
            .concurrency(orDefault(provider.getQueueConcurrency(), runtimeConfig.getQueueDefaultConcurrency()))
            .prefetch(orDefault(provider.getQueuePrefetch(), runtimeConfig.getQueueDefaultPrefetch()))
            .maxLength(orDefault(provider.getQueueMaxLength(), runtimeConfig.getQueueDefaultMaxLength()))
            .messageTtlSeconds(runtimeConfig.getQueueMessageTtlSeconds())
            .defaultQueue(false)
            .build();
    }

    private int orDefault(Integer v, int fallback) {
        return v != null && v > 0 ? v : fallback;
    }

    /** Provider 配置变更时由调用方失效缓存 */
    public void invalidate(String providerId) {
        cache.remove(providerId);
    }

    public void invalidateAll() {
        cache.clear();
    }
}
