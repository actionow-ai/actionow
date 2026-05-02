package com.actionow.ai.plugin.queue;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 周期采样所有"已知"调用队列的消息数与消费者数，注册为 Micrometer gauge。
 *
 * 采样周期 5s，对 RabbitMQ 压力可忽略；只采样
 * ProviderInvocationProducer 已声明过的队列，避免误采业务无关队列。
 *
 * 暴露指标：
 *   ai.invocation.queue.depth{queueName}      当前队列消息数
 *   ai.invocation.queue.consumers{queueName}  当前消费者数
 */
@Slf4j
@Component
public class ProviderInvocationQueueDepthSampler {

    private final AmqpAdmin amqpAdmin;
    private final MeterRegistry registry;
    private final ProviderInvocationProducer producer;

    private final ConcurrentHashMap<String, AtomicLong> depthHolders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> consumerHolders = new ConcurrentHashMap<>();

    public ProviderInvocationQueueDepthSampler(AmqpAdmin amqpAdmin,
                                                MeterRegistry registry,
                                                ProviderInvocationProducer producer) {
        this.amqpAdmin = amqpAdmin;
        this.registry = registry;
        this.producer = producer;
    }

    @Scheduled(fixedDelay = 5000L, initialDelay = 10_000L)
    public void sample() {
        for (String queueName : producer.declaredQueueNames()) {
            try {
                QueueInformation info = amqpAdmin.getQueueInfo(queueName);
                if (info == null) {
                    continue;
                }
                getOrRegisterGauge("ai.invocation.queue.depth", queueName, depthHolders)
                    .set(info.getMessageCount());
                getOrRegisterGauge("ai.invocation.queue.consumers", queueName, consumerHolders)
                    .set(info.getConsumerCount());
            } catch (Exception e) {
                // 单个队列采样失败不影响其它队列
                log.debug("[Sampler] failed to sample {}: {}", queueName, e.getMessage());
            }
        }
    }

    private AtomicLong getOrRegisterGauge(String name, String queueName,
                                          ConcurrentHashMap<String, AtomicLong> holders) {
        return holders.computeIfAbsent(queueName, q -> {
            AtomicLong holder = new AtomicLong(0);
            Gauge.builder(name, holder, AtomicLong::get)
                .tags(Tags.of("queueName", q))
                .register(registry);
            return holder;
        });
    }
}
