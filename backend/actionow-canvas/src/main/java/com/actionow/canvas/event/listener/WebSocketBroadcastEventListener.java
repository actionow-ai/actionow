package com.actionow.canvas.event.listener;

import com.actionow.canvas.entity.Canvas;
import com.actionow.canvas.entity.CanvasEdge;
import com.actionow.canvas.entity.CanvasNode;
import com.actionow.canvas.event.*;
import com.actionow.canvas.mapper.CanvasNodeMapper;
import com.actionow.canvas.websocket.CanvasWebSocketHandler;
import com.actionow.canvas.websocket.CanvasWebSocketMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WebSocket 广播事件监听器
 * 监听画布事件并通过 WebSocket 广播到客户端
 *
 * <p>核心设计原则：
 * <ul>
 *   <li>使用 @TransactionalEventListener(AFTER_COMMIT) 确保事务提交后才广播</li>
 *   <li>排除操作者，避免回声问题（解决节点/Viewport跳回）</li>
 *   <li>支持批量节点更新，减少网络开销</li>
 *   <li>使用 @Async 异步执行，不阻塞业务线程</li>
 * </ul>
 *
 * @author Actionow
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketBroadcastEventListener {

    private final CanvasWebSocketHandler webSocketHandler;
    private final CanvasNodeMapper nodeMapper;

    /**
     * 节点创建事件 - 广播给其他用户（排除操作者）
     *
     * 注意：如果是 MQ 消息触发的创建（如实体同步），triggeredBy 可能为 null，
     * 此时需要广播给所有人
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("canvasEventExecutor")
    public void onNodeCreated(NodeCreatedEvent event) {
        CanvasNode node = event.getNode();
        String canvasId = node.getCanvasId();
        String triggeredBy = event.getTriggeredBy();

        Map<String, Object> data = buildNodeData(node);
        data.put("triggeredBy", triggeredBy);

        // 排除操作者，避免回声问题
        webSocketHandler.broadcastToOthersExcludeUser(canvasId, triggeredBy,
                CanvasWebSocketMessage.nodeCreated(data));

        log.debug("广播节点创建: canvasId={}, nodeId={}, excludeUser={}",
                canvasId, node.getId(), triggeredBy);
    }

    /**
     * 节点更新事件 - 广播给其他用户（排除操作者）
     *
     * 这是解决"节点位置跳回"问题的关键：
     * 操作者不会收到自己操作的回声消息
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("canvasEventExecutor")
    public void onNodeUpdated(NodeUpdatedEvent event) {
        CanvasNode node = event.getNode();
        String canvasId = node.getCanvasId();
        String triggeredBy = event.getTriggeredBy();

        Map<String, Object> data = buildNodeData(node);
        data.put("triggeredBy", triggeredBy);

        // 排除操作者，避免回声问题（解决位置跳回）
        webSocketHandler.broadcastToOthersExcludeUser(canvasId, triggeredBy,
                CanvasWebSocketMessage.nodeUpdated(data));

        log.debug("广播节点更新: canvasId={}, nodeId={}, excludeUser={}",
                canvasId, node.getId(), triggeredBy);
    }

    /**
     * 节点删除事件 - 广播给其他用户（排除操作者）
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("canvasEventExecutor")
    public void onNodeDeleted(NodeDeletedEvent event) {
        String triggeredBy = event.getTriggeredBy();

        Map<String, Object> data = Map.of(
                "nodeId", event.getNodeId(),
                "triggeredBy", triggeredBy != null ? triggeredBy : ""
        );

        webSocketHandler.broadcastToOthersExcludeUser(event.getCanvasId(), triggeredBy,
                new CanvasWebSocketMessage(CanvasWebSocketMessage.Type.NODE_DELETED, data));

        log.debug("广播节点删除: canvasId={}, nodeId={}, excludeUser={}",
                event.getCanvasId(), event.getNodeId(), triggeredBy);
    }

    /**
     * 边创建事件 - 广播给其他用户（排除操作者）
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("canvasEventExecutor")
    public void onEdgeCreated(EdgeCreatedEvent event) {
        CanvasEdge edge = event.getEdge();
        String canvasId = edge.getCanvasId();
        String triggeredBy = event.getTriggeredBy();

        Map<String, Object> data = buildEdgeData(edge);
        data.put("triggeredBy", triggeredBy);

        webSocketHandler.broadcastToOthersExcludeUser(canvasId, triggeredBy,
                CanvasWebSocketMessage.edgeCreated(data));

        log.debug("广播边创建: canvasId={}, edgeId={}, excludeUser={}",
                canvasId, edge.getId(), triggeredBy);
    }

    /**
     * 边更新事件 - 广播给其他用户（排除操作者）
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("canvasEventExecutor")
    public void onEdgeUpdated(EdgeUpdatedEvent event) {
        CanvasEdge edge = event.getEdge();
        String canvasId = edge.getCanvasId();
        String triggeredBy = event.getTriggeredBy();

        Map<String, Object> data = buildEdgeData(edge);
        data.put("triggeredBy", triggeredBy);

        webSocketHandler.broadcastToOthersExcludeUser(canvasId, triggeredBy,
                CanvasWebSocketMessage.edgeUpdated(data));

        log.debug("广播边更新: canvasId={}, edgeId={}, excludeUser={}",
                canvasId, edge.getId(), triggeredBy);
    }

    /**
     * 边删除事件 - 广播给其他用户（排除操作者）
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("canvasEventExecutor")
    public void onEdgeDeleted(EdgeDeletedEvent event) {
        String triggeredBy = event.getTriggeredBy();

        Map<String, Object> data = Map.of(
                "edgeId", event.getEdgeId(),
                "triggeredBy", triggeredBy != null ? triggeredBy : ""
        );

        webSocketHandler.broadcastToOthersExcludeUser(event.getCanvasId(), triggeredBy,
                new CanvasWebSocketMessage(CanvasWebSocketMessage.Type.EDGE_DELETED, data));

        log.debug("广播边删除: canvasId={}, edgeId={}, excludeUser={}",
                event.getCanvasId(), event.getEdgeId(), triggeredBy);
    }

    /**
     * 画布布局变更事件 - 广播给其他用户（排除操作者）
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("canvasEventExecutor")
    public void onCanvasLayoutChanged(CanvasLayoutChangedEvent event) {
        String triggeredBy = event.getTriggeredBy();

        Map<String, Object> data = Map.of(
                "canvasId", event.getCanvasId(),
                "strategy", event.getLayoutStrategy(),
                "triggeredBy", triggeredBy != null ? triggeredBy : ""
        );

        webSocketHandler.broadcastToOthersExcludeUser(event.getCanvasId(), triggeredBy,
                new CanvasWebSocketMessage(CanvasWebSocketMessage.Type.LAYOUT_CHANGED, data));

        log.debug("广播布局变更: canvasId={}, strategy={}, excludeUser={}",
                event.getCanvasId(), event.getLayoutStrategy(), triggeredBy);
    }

    /**
     * 批量节点更新事件 - 广播给其他用户（排除操作者）
     *
     * 优化批量位置更新：将多个节点变更合并为单条 WebSocket 消息
     * 减少网络开销，提升协作体验
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("canvasEventExecutor")
    public void onBatchNodesUpdated(BatchNodesUpdatedEvent event) {
        String canvasId = event.getCanvasId();
        String triggeredBy = event.getTriggeredBy();

        List<Map<String, Object>> nodesData = new ArrayList<>();
        for (CanvasNode node : event.getNodes()) {
            nodesData.add(buildNodeData(node));
        }

        Map<String, Object> data = new HashMap<>();
        data.put("nodes", nodesData);
        data.put("triggeredBy", triggeredBy);
        data.put("count", event.getNodeCount());

        webSocketHandler.broadcastToOthersExcludeUser(canvasId, triggeredBy,
                CanvasWebSocketMessage.batchNodesUpdated(nodesData, triggeredBy));

        log.debug("广播批量节点更新: canvasId={}, nodeCount={}, excludeUser={}",
                canvasId, event.getNodeCount(), triggeredBy);
    }

    /**
     * 画布更新事件 - 广播给其他用户（排除操作者）
     *
     * 这是解决"Viewport跳回"问题的关键：
     * 操作者不会收到自己更新视口的回声消息
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("canvasEventExecutor")
    public void onCanvasUpdated(CanvasUpdatedEvent event) {
        Canvas canvas = event.getCanvas();
        String canvasId = canvas.getId();
        String triggeredBy = event.getTriggeredBy();

        Map<String, Object> data = buildCanvasData(canvas);
        data.put("triggeredBy", triggeredBy);

        // 排除操作者，避免回声问题（解决 Viewport 跳回）
        webSocketHandler.broadcastToOthersExcludeUser(canvasId, triggeredBy,
                CanvasWebSocketMessage.canvasUpdated(data));

        log.debug("广播画布更新: canvasId={}, excludeUser={}", canvasId, triggeredBy);
    }

    private Map<String, Object> buildNodeData(CanvasNode node) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", node.getId());
        data.put("workspaceId", node.getWorkspaceId());
        data.put("canvasId", node.getCanvasId());
        data.put("nodeType", node.getNodeType());
        data.put("entityType", node.getEntityType());
        data.put("entityId", node.getEntityId());
        data.put("layer", node.getLayer());
        data.put("parentNodeId", node.getParentNodeId());
        data.put("content", node.getContent());
        data.put("positionX", node.getPositionX());
        data.put("positionY", node.getPositionY());
        data.put("width", node.getWidth());
        data.put("height", node.getHeight());
        data.put("collapsed", node.getCollapsed());
        data.put("locked", node.getLocked());
        data.put("zIndex", node.getZIndex());
        data.put("style", node.getStyle() != null ? node.getStyle() : new HashMap<>());
        data.put("createdAt", node.getCreatedAt());
        data.put("updatedAt", node.getUpdatedAt());
        // 注意：不带 entityDetail（避免 broadcast 时阻塞 Feign 调用）。
        // 前端 upsert 时若 incoming.entityDetail 为空，保留本地缓存。
        return data;
    }

    private Map<String, Object> buildEdgeData(CanvasEdge edge) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", edge.getId());
        data.put("canvasId", edge.getCanvasId());
        data.put("sourceType", edge.getSourceType());
        data.put("sourceId", edge.getSourceId());
        data.put("sourceVersionId", edge.getSourceVersionId());
        data.put("sourceHandle", edge.getSourceHandle());
        data.put("targetType", edge.getTargetType());
        data.put("targetId", edge.getTargetId());
        data.put("targetVersionId", edge.getTargetVersionId());
        data.put("targetHandle", edge.getTargetHandle());
        data.put("relationType", edge.getRelationType());
        data.put("relationLabel", edge.getRelationLabel());
        data.put("description", edge.getDescription());
        data.put("lineStyle", edge.getLineStyle() != null ? edge.getLineStyle() : new HashMap<>());
        data.put("pathType", edge.getPathType());
        data.put("sequence", edge.getSequence());
        data.put("extraInfo", edge.getExtraInfo() != null ? edge.getExtraInfo() : new HashMap<>());
        data.put("createdAt", edge.getCreatedAt());
        data.put("updatedAt", edge.getUpdatedAt());
        // 反查 nodeId 让前端 React Flow 能直接渲染（不依赖前端再做反查）
        data.put("sourceNodeId", lookupNodeId(edge.getCanvasId(), edge.getSourceType(), edge.getSourceId()));
        data.put("targetNodeId", lookupNodeId(edge.getCanvasId(), edge.getTargetType(), edge.getTargetId()));
        return data;
    }

    private String lookupNodeId(String canvasId, String entityType, String entityId) {
        if (canvasId == null || entityType == null || entityId == null) return null;
        try {
            CanvasNode node = nodeMapper.selectByCanvasAndEntity(canvasId, entityType, entityId);
            return node != null ? node.getId() : null;
        } catch (Exception e) {
            log.warn("ws 反查节点ID失败: canvasId={}, entityType={}, entityId={}", canvasId, entityType, entityId);
            return null;
        }
    }

    private Map<String, Object> buildCanvasData(Canvas canvas) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", canvas.getId());
        data.put("name", canvas.getName());
        data.put("scriptId", canvas.getScriptId());
        data.put("layoutStrategy", canvas.getLayoutStrategy());
        data.put("locked", canvas.getLocked());
        data.put("viewport", canvas.getViewport());
        data.put("settings", canvas.getSettings());
        return data;
    }
}
