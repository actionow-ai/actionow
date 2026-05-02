package com.actionow.canvas.websocket;

import com.actionow.common.redis.lock.DistributedLockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 画布 WebSocket 处理器
 * 管理画布的实时连接和消息广播
 *
 * @author Actionow
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CanvasWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final DistributedLockService distributedLockService;

    /**
     * 画布ID -> 连接的会话集合
     */
    private final Map<String, Set<WebSocketSession>> canvasSessions = new ConcurrentHashMap<>();

    /**
     * 会话ID -> 画布ID 的反向映射
     */
    private final Map<String, String> sessionCanvasMap = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String canvasId = getCanvasId(session);
        String userId = getUserId(session);

        if (canvasId == null) {
            log.warn("WebSocket 连接缺少 canvasId, 关闭连接: sessionId={}", session.getId());
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        // 添加到画布会话集合
        canvasSessions.computeIfAbsent(canvasId, k -> new CopyOnWriteArraySet<>()).add(session);
        sessionCanvasMap.put(session.getId(), canvasId);

        log.info("WebSocket 连接建立: canvasId={}, userId={}, sessionId={}, 当前连接数={}",
                canvasId, userId, session.getId(), canvasSessions.get(canvasId).size());

        // 发送连接确认
        sendMessage(session, new CanvasWebSocketMessage(
                CanvasWebSocketMessage.Type.CONNECTED,
                Map.of("canvasId", canvasId, "sessionId", session.getId())
        ));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String canvasId = getCanvasId(session);
        String userId = getUserId(session);

        try {
            CanvasWebSocketMessage wsMessage = objectMapper.readValue(
                    message.getPayload(), CanvasWebSocketMessage.class);

            log.trace("收到 WebSocket 消息: canvasId={}, type={}, userId={}",
                    canvasId, wsMessage.getType(), userId);

            // 根据消息类型处理
            switch (wsMessage.getType()) {
                case PING -> sendMessage(session, new CanvasWebSocketMessage(
                        CanvasWebSocketMessage.Type.PONG, null));
                case CURSOR_MOVE -> broadcastToOthers(canvasId, session, wsMessage);
                case NODE_DRAG -> broadcastToOthers(canvasId, session, wsMessage);
                case SELECTION_CHANGE -> broadcastToOthers(canvasId, session, wsMessage);
                default -> log.warn("未知消息类型: {}", wsMessage.getType());
            }
        } catch (Exception e) {
            log.error("处理 WebSocket 消息失败: canvasId={}, error={}", canvasId, e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        String canvasId = sessionCanvasMap.remove(sessionId);

        if (canvasId != null) {
            Set<WebSocketSession> sessions = canvasSessions.get(canvasId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    canvasSessions.remove(canvasId);
                }
            }
        }

        log.info("WebSocket 连接关闭: canvasId={}, sessionId={}, status={}",
                canvasId, sessionId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket 传输错误: sessionId={}, error={}",
                session.getId(), exception.getMessage());
    }

    /**
     * 广播消息到画布的所有连接
     */
    public void broadcastToCanvas(String canvasId, CanvasWebSocketMessage message) {
        Set<WebSocketSession> sessions = canvasSessions.get(canvasId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        for (WebSocketSession session : sessions) {
            sendMessage(session, message);
        }

        log.trace("广播消息到画布: canvasId={}, type={}, 接收者数={}",
                canvasId, message.getType(), sessions.size());
    }

    /**
     * 广播消息到画布的其他连接（排除指定用户）
     * 用于避免操作者收到自己操作的回声消息
     *
     * @param canvasId 画布ID
     * @param excludeUserId 要排除的用户ID
     * @param message 消息
     */
    public void broadcastToOthersExcludeUser(String canvasId, String excludeUserId,
                                              CanvasWebSocketMessage message) {
        Set<WebSocketSession> sessions = canvasSessions.get(canvasId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        int sentCount = 0;
        for (WebSocketSession session : sessions) {
            String sessionUserId = getUserId(session);
            // 排除指定用户的所有连接（支持多设备/多Tab）
            if (excludeUserId == null || !excludeUserId.equals(sessionUserId)) {
                sendMessage(session, message);
                sentCount++;
            }
        }

        log.trace("广播消息到画布(排除用户): canvasId={}, type={}, excludeUserId={}, 接收者数={}",
                canvasId, message.getType(), excludeUserId, sentCount);
    }

    /**
     * 广播消息到画布的其他连接（排除指定会话）
     * 用于排除特定的 WebSocket 连接
     *
     * @param canvasId 画布ID
     * @param excludeSessionId 要排除的会话ID
     * @param message 消息
     */
    public void broadcastToOthersExcludeSession(String canvasId, String excludeSessionId,
                                                 CanvasWebSocketMessage message) {
        Set<WebSocketSession> sessions = canvasSessions.get(canvasId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        int sentCount = 0;
        for (WebSocketSession session : sessions) {
            if (!session.getId().equals(excludeSessionId)) {
                sendMessage(session, message);
                sentCount++;
            }
        }

        log.trace("广播消息到画布(排除会话): canvasId={}, type={}, excludeSessionId={}, 接收者数={}",
                canvasId, message.getType(), excludeSessionId, sentCount);
    }

    /**
     * 广播消息到画布的其他连接（排除发送者）
     */
    private void broadcastToOthers(String canvasId, WebSocketSession sender, CanvasWebSocketMessage message) {
        Set<WebSocketSession> sessions = canvasSessions.get(canvasId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        for (WebSocketSession session : sessions) {
            if (!session.getId().equals(sender.getId())) {
                sendMessage(session, message);
            }
        }
    }

    /**
     * 发送消息到指定会话（线程安全，使用分布式锁）
     */
    private void sendMessage(WebSocketSession session, CanvasWebSocketMessage message) {
        if (!session.isOpen()) {
            return;
        }

        String lockKey = "canvas:ws:session:" + session.getId();
        distributedLockService.executeWithLock(lockKey, 5, 5, java.util.concurrent.TimeUnit.SECONDS, () -> {
            if (session.isOpen()) {
                try {
                    String json = objectMapper.writeValueAsString(message);
                    session.sendMessage(new TextMessage(json));
                } catch (IOException e) {
                    log.error("发送 WebSocket 消息失败: sessionId={}, error={}",
                            session.getId(), e.getMessage());
                }
            }
        });
    }

    /**
     * 获取指定画布的在线用户数
     */
    public int getOnlineCount(String canvasId) {
        Set<WebSocketSession> sessions = canvasSessions.get(canvasId);
        return sessions != null ? sessions.size() : 0;
    }

    private String getCanvasId(WebSocketSession session) {
        return (String) session.getAttributes().get(WebSocketAuthInterceptor.ATTR_CANVAS_ID);
    }

    private String getUserId(WebSocketSession session) {
        return (String) session.getAttributes().get(WebSocketAuthInterceptor.ATTR_USER_ID);
    }
}
