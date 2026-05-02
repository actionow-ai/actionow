package com.actionow.canvas.consumer;

import com.actionow.canvas.service.CanvasSyncService;
import com.actionow.common.mq.consumer.ConsumerRetryHelper;
import com.actionow.common.mq.message.CollabEntityChangeEvent;
import com.actionow.common.mq.message.MessageWrapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 1 测试：CollabEntityChangeEvent 集成
 */
@ExtendWith(MockitoExtension.class)
class EntityChangeConsumerTest {

    @Mock
    private CanvasSyncService canvasSyncService;
    @Mock
    private ConsumerRetryHelper retryHelper;
    @Mock
    private Channel channel;

    @InjectMocks
    private EntityChangeConsumer consumer;

    @Test
    void testHandleEntityChange_created() throws Exception {
        MessageWrapper<CollabEntityChangeEvent> message = new MessageWrapper<>();
        message.setMessageId("msg-1");
        message.setWorkspaceId("ws-1");
        message.setSenderId("user-1");

        CollabEntityChangeEvent event = new CollabEntityChangeEvent();
        event.setEntityType("SCRIPT");
        event.setEntityId("script-1");
        event.setScriptId("script-1");
        event.setWorkspaceId("ws-1");
        event.setEventType(CollabEntityChangeEvent.EventType.CREATED);
        event.setOperatorId("user-1");

        message.setPayload(event);

        consumer.handleEntityChange(message, channel, 1L, false);

        verify(canvasSyncService, times(1)).handleEntityCreated(
            eq("SCRIPT"), eq("script-1"), eq("script-1"),
            isNull(), isNull(), eq("ws-1"), isNull()
        );
        verify(channel, times(1)).basicAck(1L, false);
    }

    @Test
    void testHandleEntityChange_updated() throws Exception {
        MessageWrapper<CollabEntityChangeEvent> message = new MessageWrapper<>();
        message.setMessageId("msg-2");

        CollabEntityChangeEvent event = new CollabEntityChangeEvent();
        event.setEntityType("SCRIPT");
        event.setEntityId("script-1");
        event.setEventType(CollabEntityChangeEvent.EventType.UPDATED);

        message.setPayload(event);

        consumer.handleEntityChange(message, channel, 2L, false);

        verify(canvasSyncService, times(1)).handleEntityUpdated(
            eq("SCRIPT"), eq("script-1"), isNull(), isNull()
        );
        verify(channel, times(1)).basicAck(2L, false);
    }

    @Test
    void testHandleEntityChange_deleted() throws Exception {
        MessageWrapper<CollabEntityChangeEvent> message = new MessageWrapper<>();
        message.setMessageId("msg-3");

        CollabEntityChangeEvent event = new CollabEntityChangeEvent();
        event.setEntityType("SCRIPT");
        event.setEntityId("script-1");
        event.setEventType(CollabEntityChangeEvent.EventType.DELETED);

        message.setPayload(event);

        consumer.handleEntityChange(message, channel, 3L, false);

        verify(canvasSyncService, times(1)).handleEntityDeleted("SCRIPT", "script-1");
        verify(channel, times(1)).basicAck(3L, false);
    }
}
