package com.actionow.ai.plugin.queue;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

import static com.actionow.common.mq.constant.MqConstants.EXCHANGE_DEAD_LETTER;
import static com.actionow.common.mq.constant.MqConstants.QUEUE_DEAD_LETTER;

/**
 * 队列声明 + 消息发布。
 *
 * 队列幂等声明：每个 QueueSpec 第一次使用时声明 Queue + Binding 到 ai.provider.exchange，
 * 并设置 x-max-length / overflow=reject-publish 实现背压；超寿消息进入 DLX。
 *
 * 发布失败（包括 broker reject 因队列满）抛 ProviderQueueFullException，
 * 由 facade 翻译成 503 + Retry-After。
 */
@Slf4j
@Component
public class ProviderInvocationProducer {

    public static final String EXCHANGE = "ai.provider.exchange";

    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;
    private final ConcurrentHashMap<String, QueueSpec> declaredQueues = new ConcurrentHashMap<>();

    public ProviderInvocationProducer(RabbitTemplate rabbitTemplate, AmqpAdmin amqpAdmin) {
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;
    }

    @PostConstruct
    void initTemplate() {
        // mandatory + reject-publish 配合使用：队列满时 broker 通过 channel 异常返回，
        // RabbitTemplate.convertAndSend 抛 AmqpException → 我们翻译成 ProviderQueueFullException
        rabbitTemplate.setMandatory(true);
    }

    /**
     * 幂等声明指定 QueueSpec 对应的物理队列。
     * 重复声明同名同参队列是 RabbitMQ idempotent；不同参数会被 broker 拒绝（PRECONDITION_FAILED）。
     */
    public synchronized void declareIfAbsent(QueueSpec spec) {
        QueueSpec existing = declaredQueues.get(spec.getQueueName());
        if (existing != null) {
            return;
        }
        try {
            Queue queue = QueueBuilder.durable(spec.getQueueName())
                .withArgument("x-max-length", spec.getMaxLength())
                .withArgument("x-overflow", "reject-publish")
                .withArgument("x-message-ttl", spec.getMessageTtlSeconds() * 1000L)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DEAD_LETTER)
                .withArgument("x-dead-letter-routing-key", QUEUE_DEAD_LETTER)
                .build();
            DirectExchange exchange = new DirectExchange(EXCHANGE, true, false);
            Binding binding = BindingBuilder.bind(queue).to(exchange).with(spec.getQueueName());

            amqpAdmin.declareExchange(exchange);
            amqpAdmin.declareQueue(queue);
            amqpAdmin.declareBinding(binding);
            declaredQueues.put(spec.getQueueName(), spec);
            log.info("[Queue] declared: name={} maxLen={} concurrency={} prefetch={} ttlSec={}",
                spec.getQueueName(), spec.getMaxLength(), spec.getConcurrency(),
                spec.getPrefetch(), spec.getMessageTtlSeconds());
        } catch (Exception e) {
            log.error("[Queue] declare failed for {}: {}", spec.getQueueName(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 发布消息。开启 publisher confirm + mandatory：队列满时 broker NACK，会抛异常。
     */
    public void publish(QueueSpec spec, ProviderInvocationMessage message) {
        declareIfAbsent(spec);
        try {
            rabbitTemplate.convertAndSend(EXCHANGE, spec.getQueueName(), message);
        } catch (AmqpException e) {
            log.warn("[Queue] publish rejected (likely queue full): queue={}, requestId={}, err={}",
                spec.getQueueName(), message.getRequestId(), e.getMessage());
            throw new ProviderQueueFullException(spec.getQueueName(), e);
        }
    }

    /** 已声明的队列名集合（供监控采样用） */
    public java.util.Set<String> declaredQueueNames() {
        return java.util.Collections.unmodifiableSet(declaredQueues.keySet());
    }

    /** 队列满 / broker 拒绝发布，调用方应快速失败而不是重试 */
    public static class ProviderQueueFullException extends RuntimeException {
        private final String queueName;
        public ProviderQueueFullException(String queueName, Throwable cause) {
            super("Provider queue full: " + queueName, cause);
            this.queueName = queueName;
        }
        public String getQueueName() { return queueName; }
    }
}
