package com.actionow.ai.plugin.queue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Provider 调用队列的 Micrometer 指标。
 *
 * 标签策略：
 * - providerId / queueName 是核心维度
 * - 不带 userId/workspaceId（高基数 → Prometheus 爆炸）
 *
 * 暴露的指标：
 *   ai.invocation.submitted{providerId, queueName}              counter
 *   ai.invocation.queue_full{providerId, queueName}             counter
 *   ai.invocation.completed{providerId, status=success|failed}  counter
 *   ai.invocation.duplicate_skipped{providerId}                 counter
 *   ai.invocation.cb_requeued{providerId}                       counter
 *   ai.invocation.duration{providerId, status}                  timer
 */
@Component
public class ProviderInvocationMetrics {

    private final MeterRegistry registry;

    public ProviderInvocationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordSubmitted(String providerId, String queueName) {
        Counter.builder("ai.invocation.submitted")
            .description("调用入队成功次数")
            .tags(safeTags(providerId, queueName))
            .register(registry)
            .increment();
    }

    public void recordQueueFull(String providerId, String queueName) {
        Counter.builder("ai.invocation.queue_full")
            .description("队列已满拒绝入队的次数（背压）")
            .tags(safeTags(providerId, queueName))
            .register(registry)
            .increment();
    }

    public void recordCompleted(String providerId, boolean success) {
        Counter.builder("ai.invocation.completed")
            .description("调用完成（含失败）次数")
            .tags(Tags.of("providerId", n(providerId), "status", success ? "success" : "failed"))
            .register(registry)
            .increment();
    }

    public void recordDuration(String providerId, boolean success, Duration duration) {
        Timer.builder("ai.invocation.duration")
            .description("调用从入队到完成的总耗时")
            .tags(Tags.of("providerId", n(providerId), "status", success ? "success" : "failed"))
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry)
            .record(duration);
    }

    public void recordDuplicateSkipped(String providerId) {
        Counter.builder("ai.invocation.duplicate_skipped")
            .description("RabbitMQ 重复投递被幂等保护拦截的次数")
            .tags(Tags.of("providerId", n(providerId)))
            .register(registry)
            .increment();
    }

    public void recordCbRequeued(String providerId) {
        Counter.builder("ai.invocation.cb_requeued")
            .description("熔断器拒绝导致消息 requeue 的次数")
            .tags(Tags.of("providerId", n(providerId)))
            .register(registry)
            .increment();
    }

    private Tags safeTags(String providerId, String queueName) {
        return Tags.of("providerId", n(providerId), "queueName", n(queueName));
    }

    private String n(String v) {
        return v != null ? v : "unknown";
    }
}
