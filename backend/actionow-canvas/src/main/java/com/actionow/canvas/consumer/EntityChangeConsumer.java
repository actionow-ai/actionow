package com.actionow.canvas.consumer;

import com.actionow.canvas.constant.CanvasConstants;
import com.actionow.common.core.context.UserContext;
import com.actionow.common.core.context.UserContextHolder;
import com.actionow.common.mq.constant.MqConstants;
import com.actionow.common.mq.consumer.ConsumerRetryHelper;
import com.actionow.common.mq.message.MessageWrapper;
import com.actionow.canvas.service.CanvasSyncService;
import com.actionow.common.mq.message.CollabEntityChangeEvent;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.*;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 实体变更消息消费者
 * 监听业务服务发送的实体变更消息，同步到Canvas
 *
 * @author Actionow
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EntityChangeConsumer {

    private static final int MAX_RETRIES = 3;

    private final CanvasSyncService canvasSyncService;
    private final ConsumerRetryHelper retryHelper;

    /**
     * 处理实体变更消息。
     *
     * 订阅 actionow.collab 交换机 + collab.entity.# 路由模式 — 与 project 模块的
     * EntityChangeEventPublisher 发送目标对齐（project 发到 EXCHANGE_COLLAB +
     * collab.entity.{created,updated,deleted}）。
     *
     * 历史坑：旧绑定订阅 actionow.topic + entity.change.#，与 project 完全不匹配，
     * canvas 多年不收任何 project 实体变更事件 → AI 生成结果一直无法回填到画布节点。
     *
     * 队列名与 collab 模块的 ENTITY_UPDATED 队列分开（独立 worker），实现 fan-out：
     * 同一条 project 事件被两边并行消费（collab 推 ws、canvas 刷缓存+广播节点）。
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "actionow.canvas.entity.change", durable = "true",
                    arguments = {
                            @Argument(name = "x-dead-letter-exchange", value = MqConstants.EXCHANGE_DEAD_LETTER),
                            @Argument(name = "x-dead-letter-routing-key", value = MqConstants.QUEUE_DEAD_LETTER)
                    }),
            exchange = @Exchange(value = MqConstants.EXCHANGE_COLLAB, type = "topic"),
            key = "collab.entity.#"
    ))
    public void handleEntityChange(MessageWrapper<CollabEntityChangeEvent> message, Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                                   @Header(value = AmqpHeaders.REDELIVERED, required = false) Boolean redelivered) {
        try {
            restoreContext(message);
            CollabEntityChangeEvent event = message.getPayload();

            if (Boolean.TRUE.equals(redelivered)) {
                log.warn("检测到消息重投递: messageId={}, entityType={}, entityId={}, scriptId={}, eventType={}, retryCount={}",
                        message.getMessageId(), event.getEntityType(), event.getEntityId(),
                        event.getScriptId(), event.getEventType(), message.getRetryCount());
            } else {
                log.info("收到实体变更消息: messageId={}, entityType={}, entityId={}, scriptId={}, eventType={}, operator={}",
                        message.getMessageId(), event.getEntityType(), event.getEntityId(),
                        event.getScriptId(), event.getEventType(), event.getOperatorId());
            }

            String entityType = event.getEntityType();
            String entityId = event.getEntityId();
            String eventType = event.getEventType();

            // 提取名称和缩略图（从 data 字段）
            // thumbnail 候选含 fileUrl：ASSET 类实体的"封面图"就是 fileUrl 本身
            String name = extractField(event.getData(), "name", "title");
            String thumbnailUrl = extractField(event.getData(),
                    "thumbnailUrl", "coverUrl", "fileUrl", "url");

            switch (eventType) {
                case CollabEntityChangeEvent.EventType.CREATED ->
                        canvasSyncService.handleEntityCreated(entityType, entityId,
                                event.getScriptId(), null, null,
                                event.getWorkspaceId(), name);
                case CollabEntityChangeEvent.EventType.UPDATED ->
                        canvasSyncService.handleEntityUpdated(entityType, entityId, name, thumbnailUrl,
                                event.getData() instanceof Map<?, ?> m ? castMap(m) : null);
                case CollabEntityChangeEvent.EventType.DELETED ->
                        canvasSyncService.handleEntityDeleted(entityType, entityId);
                default ->
                        log.warn("未知的事件类型: eventType={}", eventType);
            }

            channel.basicAck(deliveryTag, false);
            log.debug("实体变更消息处理完成: messageId={}", message.getMessageId());

        } catch (Exception e) {
            log.error("处理实体变更消息失败: messageId={}, error={}",
                    message.getMessageId(), e.getMessage(), e);
            handleException(channel, deliveryTag, message);
        } finally {
            UserContextHolder.clear();
        }
    }

    /**
     * 恢复上下文
     */
    private void restoreContext(MessageWrapper<?> message) {
        UserContext context = new UserContext();
        context.setUserId(message.getSenderId());
        context.setWorkspaceId(message.getWorkspaceId());
        context.setTenantSchema(message.getTenantSchema());
        context.setRequestId(message.getTraceId());
        UserContextHolder.setContext(context);
    }

    /**
     * 从 data 对象中提取字段（支持多个候选字段名）
     */
    private String extractField(Object data, String... fieldNames) {
        if (data == null || !(data instanceof Map)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> dataMap = (Map<String, Object>) data;
        for (String fieldName : fieldNames) {
            Object value = dataMap.get(fieldName);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    /**
     * 异常处理：通过重新发布消息递增 retryCount，超限后转入 DLQ
     */
    private void handleException(Channel channel, long deliveryTag, MessageWrapper<?> message) {
        try {
            // 重投回 collab exchange + 通配符 key，让 canvas 自己的队列再次绑定收到
            retryHelper.retryOrDlq(message, channel, deliveryTag, MAX_RETRIES,
                    MqConstants.EXCHANGE_COLLAB, MqConstants.Collab.ROUTING_ENTITY_UPDATED);
        } catch (Exception ex) {
            log.error("消息重试处理异常: {}", ex.getMessage(), ex);
        }
    }
}
