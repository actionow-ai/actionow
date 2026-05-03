package com.actionow.canvas.service.impl;

import com.actionow.canvas.constant.CanvasConstants;
import com.actionow.canvas.dto.canvas.CanvasResponse;
import com.actionow.canvas.dto.edge.CreateEdgeRequest;
import com.actionow.canvas.dto.node.CreateNodeRequest;
import com.actionow.canvas.service.CanvasEdgeService;
import com.actionow.canvas.service.CanvasNodeService;
import com.actionow.canvas.service.CanvasService;
import com.actionow.canvas.service.CanvasSyncService;
import com.actionow.canvas.service.EntityCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

/**
 * Canvas 同步服务实现
 * 处理来自MQ的实体变更消息
 * 统一主画布模型：1 Script = 1 Canvas
 *
 * @author Actionow
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CanvasSyncServiceImpl implements CanvasSyncService {

    private final CanvasNodeService nodeService;
    private final CanvasEdgeService edgeService;
    private final CanvasService canvasService;
    private final EntityCacheService entityCacheService;

    /**
     * 已处理消息的简单去重缓存（5分钟过期，用于防止MQ重复投递导致的重复处理）
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> processedMessages = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long MESSAGE_DEDUP_TTL_MS = 5 * 60 * 1000; // 5分钟

    @Override
    public void handleEntityCreated(String entityType, String entityId, String scriptId,
                                    String parentEntityType, String parentEntityId,
                                    String workspaceId, String name) {
        String dedupKey = String.format("CREATED:%s:%s:%s", entityType, entityId, scriptId);

        cleanExpiredEntries();

        Long lastProcessed = processedMessages.get(dedupKey);
        if (lastProcessed != null) {
            log.debug("跳过重复消息: dedupKey={}, lastProcessedMs={}", dedupKey, System.currentTimeMillis() - lastProcessed);
            return;
        }

        processedMessages.put(dedupKey, System.currentTimeMillis());

        log.info("处理实体创建事件: entityType={}, entityId={}, scriptId={}, parentType={}, parentId={}",
                entityType, entityId, scriptId, parentEntityType, parentEntityId);

        try {
            if (CanvasConstants.EntityType.SCRIPT.equals(entityType)) {
                handleScriptCreated(entityId, workspaceId, name);
                return;
            }

            if (!StringUtils.hasText(scriptId)) {
                log.warn("scriptId 为空，无法创建节点: entityType={}, entityId={}", entityType, entityId);
                return;
            }

            CanvasResponse canvas = canvasService.getOrCreateByScriptId(scriptId, workspaceId, null);

            var existingNodes = nodeService.listByEntity(entityType, entityId);
            boolean nodeExistsInCanvas = existingNodes.stream()
                    .anyMatch(node -> canvas.getId().equals(node.getCanvasId()));

            if (!nodeExistsInCanvas) {
                createEntityNode(canvas, entityType, entityId, parentEntityType, parentEntityId,
                        workspaceId, name);
            } else {
                log.info("节点已存在，跳过节点创建: canvasId={}, entityType={}, entityId={}",
                        canvas.getId(), entityType, entityId);
            }

            if (StringUtils.hasText(parentEntityType) && StringUtils.hasText(parentEntityId)) {
                createEdgeToParent(canvas.getId(), parentEntityType, parentEntityId,
                        entityType, entityId, workspaceId);
            }

        } catch (Exception e) {
            log.error("自动创建画布节点失败: entityType={}, entityId={}, error={}",
                    entityType, entityId, e.getMessage(), e);
        }
    }

    /**
     * 创建实体节点
     */
    private void createEntityNode(CanvasResponse canvas, String entityType, String entityId,
                                   String parentEntityType, String parentEntityId,
                                   String workspaceId, String name) {
        // 计算节点位置（简单网格布局）
        int existingCount = canvas.getNodeCount();
        int columns = CanvasConstants.LayoutDefaults.COLUMNS;
        int nodeWidth = CanvasConstants.LayoutDefaults.NODE_WIDTH;
        int nodeHeight = CanvasConstants.LayoutDefaults.NODE_HEIGHT;
        int gapX = CanvasConstants.LayoutDefaults.GAP_X;
        int gapY = CanvasConstants.LayoutDefaults.GAP_Y;

        int row = existingCount / columns;
        int col = existingCount % columns;

        BigDecimal positionX = BigDecimal.valueOf((long) col * (nodeWidth + gapX));
        BigDecimal positionY = BigDecimal.valueOf((long) row * (nodeHeight + gapY));

        // 确定节点所属层（基于 entityType）
        String layer = CanvasConstants.Layer.fromEntityType(entityType);

        // 创建节点请求
        CreateNodeRequest nodeRequest = new CreateNodeRequest();
        nodeRequest.setCanvasId(canvas.getId());
        nodeRequest.setEntityType(entityType);
        nodeRequest.setEntityId(entityId);
        nodeRequest.setLayer(layer);
        nodeRequest.setPositionX(positionX);
        nodeRequest.setPositionY(positionY);
        nodeRequest.setWidth(BigDecimal.valueOf(nodeWidth));
        nodeRequest.setHeight(BigDecimal.valueOf(nodeHeight));
        nodeRequest.setCollapsed(false);
        nodeRequest.setLocked(false);

        // 设置父节点（如果有）
        if (StringUtils.hasText(parentEntityType) && StringUtils.hasText(parentEntityId)) {
            // 确保父节点存在
            ensureEntityNodeExists(canvas.getId(), parentEntityType, parentEntityId, workspaceId);
            // 查找父节点ID
            var parentNodes = nodeService.listByEntity(parentEntityType, parentEntityId);
            var parentNode = parentNodes.stream()
                    .filter(n -> canvas.getId().equals(n.getCanvasId()))
                    .findFirst();
            parentNode.ifPresent(n -> nodeRequest.setParentNodeId(n.getId()));
        }

        // 创建节点
        nodeService.createNode(nodeRequest, workspaceId, null);

        log.info("自动创建画布节点成功: canvasId={}, entityType={}, entityId={}, layer={}",
                canvas.getId(), entityType, entityId, layer);
    }

    /**
     * 处理 Script 创建事件
     * Script 创建时，同时创建对应的 Canvas
     */
    private void handleScriptCreated(String scriptId, String workspaceId, String name) {
        try {
            CanvasResponse canvas = canvasService.getOrCreateByScriptId(scriptId, workspaceId, null);
            log.info("Script 创建，画布已初始化: scriptId={}, canvasId={}", scriptId, canvas.getId());

            // Script 创建时也通知前端刷新（旧代码用 updateCachedInfo 但本身是 no-op）
            nodeService.notifyEntityRefreshed(CanvasConstants.EntityType.SCRIPT, scriptId);
        } catch (Exception e) {
            log.error("Script 创建时画布初始化失败: scriptId={}, error={}", scriptId, e.getMessage(), e);
        }
    }

    /**
     * 创建到父实体的边
     */
    private void createEdgeToParent(String canvasId, String parentType, String parentId,
                                    String entityType, String entityId, String workspaceId) {
        try {
            // 验证边是否允许
            if (!edgeService.validateEdge(canvasId, parentType, parentId, entityType, entityId)) {
                log.debug("边规则不允许: {}[{}] -> {}[{}]",
                        parentType, parentId, entityType, entityId);
                return;
            }

            // 创建边请求
            CreateEdgeRequest edgeRequest = new CreateEdgeRequest();
            edgeRequest.setCanvasId(canvasId);
            edgeRequest.setSourceType(parentType);
            edgeRequest.setSourceId(parentId);
            edgeRequest.setSourceHandle(CanvasConstants.HandlePosition.RIGHT);
            edgeRequest.setTargetType(entityType);
            edgeRequest.setTargetId(entityId);
            edgeRequest.setTargetHandle(CanvasConstants.HandlePosition.LEFT);

            edgeService.createEdge(edgeRequest, workspaceId, null);

            log.info("自动创建层级边成功: canvasId={}, {}[{}] -> {}[{}]",
                    canvasId, parentType, parentId, entityType, entityId);

        } catch (Exception e) {
            log.warn("自动创建层级边失败（忽略）: {}[{}] -> {}[{}], error={}",
                    parentType, parentId, entityType, entityId, e.getMessage());
        }
    }

    /**
     * 确保实体节点存在
     */
    private void ensureEntityNodeExists(String canvasId, String entityType, String entityId,
                                        String workspaceId) {
        try {
            var existingNodes = nodeService.listByEntity(entityType, entityId);
            boolean hasNodeInCanvas = existingNodes.stream()
                    .anyMatch(node -> canvasId.equals(node.getCanvasId()));

            if (hasNodeInCanvas) {
                return;
            }

            // 确定层
            String layer = CanvasConstants.Layer.fromEntityType(entityType);

            // 创建实体节点
            CreateNodeRequest nodeRequest = new CreateNodeRequest();
            nodeRequest.setCanvasId(canvasId);
            nodeRequest.setEntityType(entityType);
            nodeRequest.setEntityId(entityId);
            nodeRequest.setLayer(layer);
            nodeRequest.setPositionX(BigDecimal.valueOf(100));
            nodeRequest.setPositionY(BigDecimal.valueOf(100));
            nodeRequest.setWidth(BigDecimal.valueOf(CanvasConstants.LayoutDefaults.NODE_WIDTH));
            nodeRequest.setHeight(BigDecimal.valueOf(CanvasConstants.LayoutDefaults.NODE_HEIGHT));
            nodeRequest.setCollapsed(false);
            nodeRequest.setLocked(false);

            nodeService.createNode(nodeRequest, workspaceId, null);

            log.info("自动创建关联实体节点: canvasId={}, entityType={}, entityId={}",
                    canvasId, entityType, entityId);

        } catch (Exception e) {
            log.warn("创建关联实体节点失败（忽略）: entityType={}, entityId={}, error={}",
                    entityType, entityId, e.getMessage());
        }
    }

    @Override
    public void handleEntityUpdated(String entityType, String entityId, String name, String thumbnailUrl) {
        handleEntityUpdated(entityType, entityId, name, thumbnailUrl, null);
    }

    @Override
    public void handleEntityUpdated(String entityType, String entityId, String name, String thumbnailUrl,
                                    java.util.Map<String, Object> payload) {
        log.info("处理实体更新事件: entityType={}, entityId={}, hasPayload={}",
                entityType, entityId, payload != null);

        if (payload != null && !payload.isEmpty()) {
            // 全量覆盖缓存（含 fileUrl/fileKey/mimeType 等所有字段），后续读直接命中最新数据
            entityCacheService.cacheEntityFromPayload(entityType, entityId, payload);
        } else {
            // 没有 payload 兜底：失效缓存让下次读回源 Feign。partial update 已被废弃，
            // 因为它只支持 name/thumbnailUrl，会泄漏 fileUrl/fileKey 等字段。
            entityCacheService.evictCache(entityType, entityId);
        }

        // 通知所有引用该实体的节点：F2 直接 WS 广播 NODE_UPDATED + entityDetail
        // 传 payload 作为兜底：enrich Feign 失败时仍能让前端拿到 fileUrl 等关键字段
        nodeService.notifyEntityRefreshed(entityType, entityId, payload);
    }

    @Override
    public void handleEntityDeleted(String entityType, String entityId) {
        log.info("处理实体删除事件: entityType={}, entityId={}", entityType, entityId);

        // 失效 Redis 缓存
        entityCacheService.evictCache(entityType, entityId);

        // 删除所有包含该实体的节点
        nodeService.deleteByEntity(entityType, entityId);

        // 删除所有关联该实体的边
        edgeService.deleteByEntity(entityType, entityId);

        log.info("实体相关的节点和边已删除: entityType={}, entityId={}", entityType, entityId);
    }

    /**
     * 清理过期的去重缓存条目
     */
    private void cleanExpiredEntries() {
        long now = System.currentTimeMillis();
        processedMessages.entrySet().removeIf(entry -> now - entry.getValue() > MESSAGE_DEDUP_TTL_MS);
    }
}
