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
     * 处理实体变更消息
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MqConstants.Canvas.QUEUE, durable = "true",
                    arguments = {
                            @Argument(name = "x-dead-letter-exchange", value = MqConstants.EXCHANGE_DEAD_LETTER),
                            @Argument(name = "x-dead-letter-routing-key", value = MqConstants.QUEUE_DEAD_LETTER)
                    }),
            exchange = @Exchange(value = MqConstants.EXCHANGE_TOPIC, type = "topic"),
            key = MqConstants.Canvas.ROUTING_ENTITY_CHANGE
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
            String name = extractField(event.getData(), "name", "title");
            String thumbnailUrl = extractField(event.getData(), "thumbnailUrl", "coverUrl", "url");

            switch (eventType) {
                case CollabEntityChangeEvent.EventType.CREATED ->
                        canvasSyncService.handleEntityCreated(entityType, entityId,
                                event.getScriptId(), null, null,
                                event.getWorkspaceId(), name);
                case CollabEntityChangeEvent.EventType.UPDATED ->
                        canvasSyncService.handleEntityUpdated(entityType, entityId, name, thumbnailUrl);
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

    /**
     * 异常处理：通过重新发布消息递增 retryCount，超限后转入 DLQ
     */
    private void handleException(Channel channel, long deliveryTag, MessageWrapper<?> message) {
        try {
            retryHelper.retryOrDlq(message, channel, deliveryTag, MAX_RETRIES,
                    MqConstants.EXCHANGE_TOPIC, MqConstants.Canvas.ROUTING_ENTITY_CHANGE);
        } catch (Exception ex) {
            log.error("消息重试处理异常: {}", ex.getMessage(), ex);
        }
    }
}
