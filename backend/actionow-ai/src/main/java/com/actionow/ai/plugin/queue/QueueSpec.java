package com.actionow.ai.plugin.queue;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 单个 provider 解析后的队列配置快照。
 *
 * 由 ProviderQueueRouter 从 ModelProvider + 全局默认值合成；
 * 注册到 RabbitMQ 与启动 Listener 容器时以此为准。
 */
@Data
@Builder
@AllArgsConstructor
public class QueueSpec {
    /** 物理队列名（即 RabbitMQ queue name 与 routing key） */
    private final String queueName;
    /** 消费者并发数（也作为 prefetch 的下界） */
    private final int concurrency;
    /** consumer prefetch */
    private final int prefetch;
    /** 队列最大消息数；满后 broker 直接拒绝发布（背压） */
    private final int maxLength;
    /** 消息在队列中最大寿命（秒）；超时进 DLX */
    private final int messageTtlSeconds;
    /** true 表示是全局默认队列（多个 provider 共享） */
    private final boolean defaultQueue;
}
