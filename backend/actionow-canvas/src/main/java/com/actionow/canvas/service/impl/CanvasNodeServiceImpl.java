package com.actionow.canvas.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.actionow.canvas.constant.CanvasConstants;
import com.actionow.canvas.dto.feign.CanvasEntityCreateRequest;
import com.actionow.canvas.dto.feign.CanvasEntityCreateResponse;
import com.actionow.canvas.dto.feign.CanvasEntityUpdateRequest;
import com.actionow.canvas.dto.feign.CanvasEntityUpdateResponse;
import com.actionow.canvas.dto.feign.CreateEntityAssetRelationRequest;
import com.actionow.canvas.dto.edge.CanvasEdgeResponse;
import com.actionow.canvas.dto.edge.CreateEdgeRequest;
import com.actionow.canvas.dto.node.*;
import com.actionow.canvas.entity.Canvas;
import com.actionow.canvas.entity.CanvasNode;
import com.actionow.canvas.event.BatchNodesUpdatedEvent;
import com.actionow.canvas.event.CanvasEventPublisher;
import com.actionow.canvas.event.NodeCreatedEvent;
import com.actionow.canvas.event.NodeDeletedEvent;
import com.actionow.canvas.event.NodeUpdatedEvent;
import com.actionow.canvas.feign.ProjectFeignClient;
import com.actionow.canvas.mapper.CanvasMapper;
import com.actionow.canvas.mapper.CanvasNodeMapper;
import com.actionow.canvas.service.CanvasEdgeService;
import com.actionow.canvas.service.CanvasNodeService;
import com.actionow.canvas.service.NodeEnrichmentService;
import com.actionow.canvas.history.OperationHistoryService;
import com.actionow.common.core.constant.CommonConstants;
import com.actionow.common.core.exception.BusinessException;
import com.actionow.common.core.id.UuidGenerator;
import com.actionow.common.core.result.Result;
import com.actionow.common.core.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 画布节点服务实现
 *
 * @author Actionow
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CanvasNodeServiceImpl implements CanvasNodeService {

    private final CanvasNodeMapper nodeMapper;
    private final CanvasMapper canvasMapper;
    private final ProjectFeignClient projectFeignClient;
    private final CanvasEventPublisher eventPublisher;
    @Lazy
    private final CanvasEdgeService edgeService;
    private final OperationHistoryService historyService;
    private final NodeEnrichmentService nodeEnrichmentService;

    @Override
    public List<CanvasNodeResponse> getNodesByViewport(String canvasId, ViewportQueryRequest request) {
        List<CanvasNode> nodes = nodeMapper.selectByViewport(
                canvasId,
                request.getMinX(), request.getMinY(),
                request.getMaxX(), request.getMaxY(),
                request.getLimit()
        );
        return nodes.stream()
                .map(CanvasNodeResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CanvasNodeResponse createGroup(CreateGroupRequest request, String workspaceId, String userId) {
        CreateNodeRequest nodeRequest = new CreateNodeRequest();
        nodeRequest.setCanvasId(request.getCanvasId());
        nodeRequest.setNodeType("GROUP");
        nodeRequest.setContent(new HashMap<>(Map.of("title", request.getTitle() != null ? request.getTitle() : "分组")));
        nodeRequest.setPositionX(request.getPositionX() != null ? request.getPositionX() : BigDecimal.ZERO);
        nodeRequest.setPositionY(request.getPositionY() != null ? request.getPositionY() : BigDecimal.ZERO);

        CanvasNodeResponse group = createNode(nodeRequest, workspaceId, userId);

        if (request.getNodeIds() != null && !request.getNodeIds().isEmpty()) {
            for (String nodeId : request.getNodeIds()) {
                CanvasNode node = nodeMapper.selectById(nodeId);
                if (node != null) {
                    node.setParentNodeId(group.getId());
                    nodeMapper.updateById(node);
                }
            }
        }

        return group;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void moveGroup(String groupId, BigDecimal deltaX, BigDecimal deltaY) {
        CanvasNode group = nodeMapper.selectById(groupId);
        if (group == null || !"GROUP".equals(group.getNodeType())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "分组不存在");
        }

        group.setPositionX(group.getPositionX().add(deltaX));
        group.setPositionY(group.getPositionY().add(deltaY));
        nodeMapper.updateById(group);

        List<CanvasNode> children = nodeMapper.selectByParentNodeId(groupId);
        for (CanvasNode child : children) {
            child.setPositionX(child.getPositionX().add(deltaX));
            child.setPositionY(child.getPositionY().add(deltaY));
            nodeMapper.updateById(child);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CanvasNodeResponse createNode(CreateNodeRequest request, String workspaceId, String userId) {
        // 验证画布存在
        Canvas canvas = canvasMapper.selectById(request.getCanvasId());
        if (canvas == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "画布不存在");
        }

        // 处理实体ID：如果未提供，需要先创建实体
        String entityId = request.getEntityId();
        boolean isGroupNode = "GROUP".equals(request.getNodeType());

        if (!StringUtils.hasText(entityId) && !isGroupNode) {
            // 新建实体模式：需要提供 entityName
            if (!StringUtils.hasText(request.getEntityName())) {
                throw new BusinessException(ResultCode.PARAM_INVALID, "entityId 或 entityName 必须提供其一");
            }

            // 调用 Project 服务创建实体
            CanvasEntityCreateResponse createdEntity = createEntityInProject(request, workspaceId, canvas);
            entityId = createdEntity.getEntityId();

            log.info("Canvas 新建实体模式: 已创建实体 entityType={}, entityId={}, name={}",
                    request.getEntityType(), entityId, createdEntity.getName());
        }

        // 检查是否已存在节点
        CanvasNode existing = nodeMapper.selectByCanvasAndEntity(
                request.getCanvasId(), request.getEntityType(), entityId);
        if (existing != null) {
            throw new BusinessException(ResultCode.ALREADY_EXISTS, "节点已存在");
        }

        // 获取最大 z-index
        Integer maxZIndex = nodeMapper.selectMaxZIndex(request.getCanvasId());

        // 确定节点层级
        String layer = request.getLayer();
        if (!StringUtils.hasText(layer)) {
            // 根据实体类型自动推断层级
            layer = CanvasConstants.Layer.fromEntityType(request.getEntityType());
        }

        CanvasNode node = new CanvasNode();
        node.setId(UuidGenerator.generateUuidV7());
        node.setWorkspaceId(workspaceId);
        node.setCanvasId(request.getCanvasId());
        node.setNodeType(request.getNodeType() != null ? request.getNodeType() : "ENTITY");
        node.setEntityType(request.getEntityType());
        node.setEntityId(entityId);
        node.setContent(request.getContent());
        node.setLayer(layer);
        node.setParentNodeId(request.getParentNodeId());
        node.setPositionX(request.getPositionX());
        node.setPositionY(request.getPositionY());
        node.setWidth(request.getWidth() != null ? request.getWidth()
                : BigDecimal.valueOf(CanvasConstants.LayoutDefaults.NODE_WIDTH));
        node.setHeight(request.getHeight() != null ? request.getHeight()
                : BigDecimal.valueOf(CanvasConstants.LayoutDefaults.NODE_HEIGHT));
        node.setCollapsed(request.getCollapsed() != null ? request.getCollapsed() : false);
        node.setLocked(request.getLocked() != null ? request.getLocked() : false);
        node.setZIndex(request.getZIndex() != null ? request.getZIndex() : maxZIndex + 1);
        node.setStyle(request.getStyle() != null ? request.getStyle() : new HashMap<>());

        nodeMapper.insert(node);

        // 记录操作历史
        historyService.recordNodeCreate(node, userId);

        log.info("画布节点创建成功: nodeId={}, canvasId={}, entityType={}, entityId={}, layer={}",
                node.getId(), request.getCanvasId(), request.getEntityType(), entityId, layer);

        // 发布节点创建事件（WebSocket 广播）
        eventPublisher.publishAsync(new NodeCreatedEvent(node, !StringUtils.hasText(request.getEntityId()), userId, workspaceId));

        CanvasNodeResponse response = CanvasNodeResponse.fromEntity(node);

        // 如果请求中包含边信息，则同时创建边
        if (request.hasEdgeInfo()) {
            try {
                CanvasEdgeResponse createdEdge = createEdgeForNode(request, entityId, workspaceId, userId);
                response.setCreatedEdge(createdEdge);
                log.info("节点创建时同时创建边成功: sourceType={}, sourceId={} -> targetType={}, targetId={}",
                        request.getSourceNodeType(), request.getSourceNodeId(),
                        request.getEntityType(), entityId);
            } catch (Exception e) {
                // 边创建失败不影响节点创建，仅记录警告
                log.warn("节点创建时创建边失败（节点已创建）: error={}", e.getMessage());
            }
        }

        // 主动 enrich entityDetail，让前端立即拿到 mediaType / coverUrl 等字段
        // 否则前端需要刷新页面才能看到正确的节点类型
        try {
            nodeEnrichmentService.enrich(java.util.Collections.singletonList(response));
        } catch (Exception e) {
            log.warn("createNode enrich 失败（节点已创建）: nodeId={}, error={}", node.getId(), e.getMessage());
        }

        return response;
    }

    /**
     * 为新创建的节点创建边（从源节点到新节点）
     * 注意：边存储的是实体ID（entityId），不是节点ID（nodeId）
     *
     * 当目标类型为 ASSET 时，会同步创建 EntityAssetRelation 记录
     */
    private CanvasEdgeResponse createEdgeForNode(CreateNodeRequest request, String entityId,
                                                   String workspaceId, String userId) {
        // 查找源节点获取其 entityId（边存储实体ID而非节点ID）
        CanvasNode sourceNode = nodeMapper.selectById(request.getSourceNodeId());
        if (sourceNode == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "源节点不存在: " + request.getSourceNodeId());
        }

        CreateEdgeRequest edgeRequest = new CreateEdgeRequest();
        edgeRequest.setCanvasId(request.getCanvasId());
        edgeRequest.setSourceType(sourceNode.getEntityType());
        edgeRequest.setSourceId(sourceNode.getEntityId());  // 使用实体ID而非节点ID
        edgeRequest.setSourceHandle(request.getSourceHandle() != null
                ? request.getSourceHandle() : CanvasConstants.HandlePosition.RIGHT);
        edgeRequest.setTargetType(request.getEntityType());
        edgeRequest.setTargetId(entityId);
        edgeRequest.setTargetHandle(request.getTargetHandle() != null
                ? request.getTargetHandle() : CanvasConstants.HandlePosition.LEFT);
        edgeRequest.setRelationType(request.getRelationType());
        edgeRequest.setRelationLabel(request.getRelationLabel());
        edgeRequest.setLineStyle(request.getEdgeLineStyle());

        CanvasEdgeResponse edgeResponse = edgeService.createEdge(edgeRequest, workspaceId, userId);

        // 当目标类型为 ASSET 时，同步创建 EntityAssetRelation
        if (CanvasConstants.EntityType.ASSET.equals(request.getEntityType())) {
            createEntityAssetRelation(sourceNode.getEntityType(), sourceNode.getEntityId(),
                    entityId, request.getRelationType());
        }

        return edgeResponse;
    }

    /**
     * 创建实体-素材关联（调用 Project 服务）
     * 保证画布边与实体素材关联数据一致性
     */
    private void createEntityAssetRelation(String entityType, String entityId,
                                            String assetId, String relationType) {
        try {
            CreateEntityAssetRelationRequest relationRequest = CreateEntityAssetRelationRequest.builder()
                    .entityType(entityType)
                    .entityId(entityId)
                    .assetId(assetId)
                    .relationType(relationType != null ? relationType : "DRAFT")
                    .build();

            Result<?> result = projectFeignClient.createEntityAssetRelation(relationRequest);
            if (result != null && result.isSuccess()) {
                log.info("创建实体素材关联成功: entityType={}, entityId={}, assetId={}",
                        entityType, entityId, assetId);
            } else {
                String errorMsg = result != null ? result.getMessage() : "Project 服务不可用";
                log.warn("创建实体素材关联失败（边已创建）: entityType={}, entityId={}, assetId={}, error={}",
                        entityType, entityId, assetId, errorMsg);
            }
        } catch (Exception e) {
            // 关联创建失败不影响边创建，仅记录警告
            log.warn("创建实体素材关联异常（边已创建）: entityType={}, entityId={}, assetId={}, error={}",
                    entityType, entityId, assetId, e.getMessage());
        }
    }

    /**
     * 调用 Project 服务创建实体
     * 从画布获取 scriptId，从请求获取 episodeId
     */
    private CanvasEntityCreateResponse createEntityInProject(CreateNodeRequest request, String workspaceId, Canvas canvas) {
        // 从画布获取 scriptId（统一主画布模型：画布与剧本 1:1 关联）
        String scriptId = canvas.getScriptId();
        // episodeId 从请求中获取
        String episodeId = request.getEpisodeId();

        // 把 mediaType 注入 extraData.assetType（Project 端从此处取媒介子类型）
        Map<String, Object> extraData = request.getEntityExtraData() != null
                ? new HashMap<>(request.getEntityExtraData())
                : new HashMap<>();
        if (StringUtils.hasText(request.getMediaType())) {
            extraData.put("assetType", request.getMediaType().toUpperCase());
        }

        CanvasEntityCreateRequest createRequest = CanvasEntityCreateRequest.builder()
                .entityType(request.getEntityType())
                .name(request.getEntityName())
                .description(request.getEntityDescription())
                .scope(request.getEntityScope())
                .scriptId(scriptId)
                .episodeId(episodeId)
                .workspaceId(workspaceId)
                .positionX(request.getPositionX())
                .positionY(request.getPositionY())
                .width(request.getWidth())
                .height(request.getHeight())
                .style(request.getStyle())
                .extraData(extraData)
                .build();

        Result<CanvasEntityCreateResponse> result = projectFeignClient.createEntity(createRequest);

        if (result == null || !result.isSuccess() || result.getData() == null) {
            String errorMsg = result != null ? result.getMessage() : "Project 服务不可用";
            log.error("Canvas 创建实体失败: entityType={}, name={}, error={}",
                    request.getEntityType(), request.getEntityName(), errorMsg);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "创建实体失败: " + errorMsg);
        }

        return result.getData();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<CanvasNodeResponse> batchCreateNodes(List<CreateNodeRequest> requests,
                                                      String workspaceId, String userId) {
        List<CanvasNodeResponse> responses = new ArrayList<>();
        for (CreateNodeRequest request : requests) {
            try {
                responses.add(createNode(request, workspaceId, userId));
            } catch (BusinessException e) {
                log.warn("批量创建节点跳过: {}", e.getMessage());
            }
        }
        return responses;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CanvasNodeResponse updateNode(String nodeId, UpdateNodeRequest request, String userId) {
        CanvasNode node = getNodeOrThrow(nodeId);

        // 保存之前的状态用于事件
        CanvasNode previousState = copyNodeState(node);

        if (request.getPositionX() != null) {
            node.setPositionX(request.getPositionX());
        }
        if (request.getPositionY() != null) {
            node.setPositionY(request.getPositionY());
        }
        if (request.getWidth() != null) {
            node.setWidth(request.getWidth());
        }
        if (request.getHeight() != null) {
            node.setHeight(request.getHeight());
        }
        if (request.getCollapsed() != null) {
            node.setCollapsed(request.getCollapsed());
        }
        if (request.getLocked() != null) {
            node.setLocked(request.getLocked());
        }
        if (request.getZIndex() != null) {
            node.setZIndex(request.getZIndex());
        }
        if (request.getStyle() != null) {
            node.setStyle(request.getStyle());
        }

        nodeMapper.updateById(node);

        log.info("画布节点更新成功: nodeId={}", nodeId);

        // 发布节点更新事件（WebSocket 广播）
        eventPublisher.publishAsync(new NodeUpdatedEvent(node, previousState, userId, node.getWorkspaceId()));

        return CanvasNodeResponse.fromEntity(node);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CanvasNodeResponse updateNodeWithEntity(String nodeId, UpdateNodeWithEntityRequest request,
                                                   String workspaceId, String userId) {
        CanvasNode node = getNodeOrThrow(nodeId);

        // 更新节点布局字段
        if (request.getPositionX() != null) {
            node.setPositionX(request.getPositionX());
        }
        if (request.getPositionY() != null) {
            node.setPositionY(request.getPositionY());
        }
        if (request.getWidth() != null) {
            node.setWidth(request.getWidth());
        }
        if (request.getHeight() != null) {
            node.setHeight(request.getHeight());
        }
        if (request.getCollapsed() != null) {
            node.setCollapsed(request.getCollapsed());
        }
        if (request.getLocked() != null) {
            node.setLocked(request.getLocked());
        }
        if (request.getZIndex() != null) {
            node.setZIndex(request.getZIndex());
        }
        if (request.getStyle() != null) {
            node.setStyle(request.getStyle());
        }

        // 如果有实体更新信息，同步到 Project 服务
        if (request.hasEntityUpdateInfo()) {
            try {
                CanvasEntityUpdateResponse entityResponse = updateEntityInProject(
                        node.getEntityType(), node.getEntityId(), request, workspaceId);

                // 更新节点缓存信息
                if (entityResponse != null && entityResponse.isSuccess()) {
                    if (entityResponse.getName() != null) {
                    }
                    if (entityResponse.getThumbnailUrl() != null) {
                    }
                    log.info("节点实体同步更新成功: nodeId={}, entityType={}, entityId={}",
                            nodeId, node.getEntityType(), node.getEntityId());
                }
            } catch (Exception e) {
                log.warn("节点实体同步更新失败（节点仍更新）: nodeId={}, error={}", nodeId, e.getMessage());
            }
        }

        nodeMapper.updateById(node);

        log.info("画布节点更新成功（含实体同步）: nodeId={}", nodeId);

        return CanvasNodeResponse.fromEntity(node);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<CanvasNodeResponse> batchUpdateNodesWithEntity(List<UpdateNodeWithEntityRequest> requests,
                                                                String workspaceId, String userId) {
        List<CanvasNodeResponse> responses = new ArrayList<>();
        for (UpdateNodeWithEntityRequest request : requests) {
            if (request.getNodeId() == null || request.getNodeId().isBlank()) {
                continue;
            }
            try {
                responses.add(updateNodeWithEntity(request.getNodeId(), request, workspaceId, userId));
            } catch (BusinessException e) {
                log.warn("批量更新节点（含实体）跳过: nodeId={}, error={}", request.getNodeId(), e.getMessage());
            }
        }
        log.info("批量更新节点（含实体同步）完成: count={}", responses.size());
        return responses;
    }

    /**
     * 调用 Project 服务更新实体
     */
    private CanvasEntityUpdateResponse updateEntityInProject(String entityType, String entityId,
                                                              UpdateNodeWithEntityRequest request,
                                                              String workspaceId) {
        CanvasEntityUpdateRequest updateRequest = CanvasEntityUpdateRequest.builder()
                .entityType(entityType)
                .entityId(entityId)
                .name(request.getEntityName())
                .description(request.getEntityDescription())
                .thumbnailUrl(request.getEntityThumbnailUrl())
                .workspaceId(workspaceId)
                .extraData(request.getEntityExtraData())
                .build();

        Result<CanvasEntityUpdateResponse> result = projectFeignClient.updateEntity(updateRequest);

        if (result == null || !result.isSuccess() || result.getData() == null) {
            String errorMsg = result != null ? result.getMessage() : "Project 服务不可用";
            log.error("Canvas 更新实体失败: entityType={}, entityId={}, error={}",
                    entityType, entityId, errorMsg);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "更新实体失败: " + errorMsg);
        }

        return result.getData();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteNode(String nodeId, String userId) {
        deleteNode(nodeId, userId, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteNode(String nodeId, String userId, boolean syncToProject) {
        CanvasNode node = getNodeOrThrow(nodeId);
        String canvasId = node.getCanvasId();
        String entityType = node.getEntityType();
        String entityId = node.getEntityId();
        String workspaceId = node.getWorkspaceId();

        // 删除节点
        nodeMapper.deleteById(nodeId);
        log.info("画布节点删除成功: nodeId={}", nodeId);

        // 发布节点删除事件（WebSocket 广播）
        eventPublisher.publishAsync(new NodeDeletedEvent(nodeId, canvasId, entityType, entityId, userId, workspaceId));

        // 同步删除 Project 中的实体
        if (syncToProject) {
            try {
                Result<Void> result = projectFeignClient.deleteEntity(entityType, entityId);
                if (result != null && result.isSuccess()) {
                    log.info("Canvas 删除节点同步到 Project 成功: entityType={}, entityId={}",
                            entityType, entityId);
                } else {
                    String errorMsg = result != null ? result.getMessage() : "Project 服务不可用";
                    log.warn("Canvas 删除节点同步到 Project 失败（节点已删除）: entityType={}, entityId={}, error={}",
                            entityType, entityId, errorMsg);
                }
            } catch (Exception e) {
                // 同步失败不影响节点删除
                log.warn("Canvas 删除节点同步到 Project 异常（节点已删除）: entityType={}, entityId={}, error={}",
                        entityType, entityId, e.getMessage());
            }
        }
    }

    @Override
    public CanvasNodeResponse getById(String nodeId) {
        CanvasNode node = getNodeOrThrow(nodeId);
        return CanvasNodeResponse.fromEntity(node);
    }

    @Override
    public List<CanvasNodeResponse> listByCanvasId(String canvasId) {
        List<CanvasNode> nodes = nodeMapper.selectByCanvasId(canvasId);
        List<CanvasNodeResponse> responses = nodes.stream()
                .map(CanvasNodeResponse::fromEntity)
                .collect(Collectors.toList());
        nodeEnrichmentService.enrich(responses);
        return responses;
    }

    @Override
    public List<CanvasNodeResponse> listByEntity(String entityType, String entityId) {
        List<CanvasNode> nodes = nodeMapper.selectByEntity(entityType, entityId);
        List<CanvasNodeResponse> responses = nodes.stream()
                .map(CanvasNodeResponse::fromEntity)
                .collect(Collectors.toList());
        nodeEnrichmentService.enrich(responses);
        return responses;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdatePositions(List<UpdateNodeRequest> updates, String userId) {
        if (updates == null || updates.isEmpty()) {
            return;
        }

        List<CanvasNode> updatedNodes = new ArrayList<>();
        List<CanvasNode> previousStates = new ArrayList<>();
        String canvasId = null;
        String workspaceId = null;

        for (UpdateNodeRequest update : updates) {
            if (update.getNodeId() == null || update.getNodeId().isBlank()) {
                continue;
            }

            CanvasNode node = findNodeByNodeId(update.getNodeId());
            if (node == null) {
                log.warn("批量更新节点未找到: nodeId={}", update.getNodeId());
                continue;
            }

            // 记录 canvasId 和 workspaceId（取第一个有效节点的值）
            if (canvasId == null) {
                canvasId = node.getCanvasId();
                workspaceId = node.getWorkspaceId();
            }

            // 保存之前的状态用于事件
            previousStates.add(copyNodeState(node));

            if (update.getPositionX() != null) {
                node.setPositionX(update.getPositionX());
            }
            if (update.getPositionY() != null) {
                node.setPositionY(update.getPositionY());
            }
            if (update.getWidth() != null) {
                node.setWidth(update.getWidth());
            }
            if (update.getHeight() != null) {
                node.setHeight(update.getHeight());
            }
            if (update.getCollapsed() != null) {
                node.setCollapsed(update.getCollapsed());
            }
            if (update.getLocked() != null) {
                node.setLocked(update.getLocked());
            }
            if (update.getZIndex() != null) {
                node.setZIndex(update.getZIndex());
            }
            if (update.getStyle() != null) {
                node.setStyle(update.getStyle());
            }

            nodeMapper.updateById(node);
            updatedNodes.add(node);
        }

        // 发布批量节点更新事件（一次广播，减少网络开销）
        if (!updatedNodes.isEmpty() && canvasId != null) {
            eventPublisher.publishAsync(new BatchNodesUpdatedEvent(
                    canvasId, updatedNodes, previousStates, userId, workspaceId));
        }

        log.info("批量更新节点位置完成: count={}", updatedNodes.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByCanvasId(String canvasId) {
        int count = nodeMapper.deleteByCanvasId(canvasId);
        log.info("删除画布所有节点: canvasId={}, count={}", canvasId, count);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByEntity(String entityType, String entityId) {
        int count = nodeMapper.deleteByEntity(entityType, entityId);
        log.info("删除实体所有节点: entityType={}, entityId={}, count={}", entityType, entityId, count);
    }

    @Override
    public boolean validateNodeType(String canvasId, String entityType) {
        // 在统一主画布模型中，所有实体类型都允许添加到画布中
        // 视图筛选在前端/查询时处理，不在创建时限制
        Canvas canvas = canvasMapper.selectById(canvasId);
        return canvas != null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateCachedInfo(String entityType, String entityId, String name, String thumbnailUrl) {
        List<CanvasNode> nodes = nodeMapper.selectByEntity(entityType, entityId);
        for (CanvasNode node : nodes) {
            nodeMapper.updateById(node);
        }
        log.info("更新节点缓存信息: entityType={}, entityId={}, count={}", entityType, entityId, nodes.size());
    }

    /**
     * 根据 nodeId 查找节点
     * 支持纯 UUID 格式和组合格式 {entityType}-{entityId}
     */
    private CanvasNode findNodeByNodeId(String nodeId) {
        // 1. 先尝试直接按ID查找
        CanvasNode node = nodeMapper.selectById(nodeId);
        if (node != null && node.getDeleted() != CommonConstants.DELETED) {
            return node;
        }

        // 2. 尝试解析组合格式 {entityType}-{entityId}
        int dashIndex = nodeId.indexOf('-');
        if (dashIndex > 0) {
            String entityTypePart = nodeId.substring(0, dashIndex).toUpperCase();
            String entityIdPart = nodeId.substring(dashIndex + 1);

            // 验证 entityId 是否为有效 UUID 格式
            if (entityIdPart.contains("-") && entityIdPart.length() >= 36) {
                List<CanvasNode> nodes = nodeMapper.selectByEntity(entityTypePart, entityIdPart);
                if (!nodes.isEmpty()) {
                    if (nodes.size() > 1) {
                        log.warn("组合ID查找到多个节点，返回第一个: nodeId={}, count={}", nodeId, nodes.size());
                    }
                    return nodes.get(0);
                }
            }
        }

        return null;
    }

    /**
     * 获取节点或抛出异常
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdate(BatchUpdateRequest request, String userId) {
        if (request.getNodeIds() == null || request.getNodeIds().isEmpty()) {
            return;
        }

        List<CanvasNode> updatedNodes = new ArrayList<>();
        List<CanvasNode> previousStates = new ArrayList<>();
        String canvasId = null;
        String workspaceId = null;

        for (String nodeId : request.getNodeIds()) {
            CanvasNode node = nodeMapper.selectById(nodeId);
            if (node == null) continue;

            if (canvasId == null) {
                canvasId = node.getCanvasId();
                workspaceId = node.getWorkspaceId();
            }

            previousStates.add(copyNodeState(node));

            if (request.getDeltaX() != null || request.getDeltaY() != null) {
                node.setPositionX(node.getPositionX().add(request.getDeltaX() != null ? request.getDeltaX() : BigDecimal.ZERO));
                node.setPositionY(node.getPositionY().add(request.getDeltaY() != null ? request.getDeltaY() : BigDecimal.ZERO));
            }
            if (request.getZIndex() != null) node.setZIndex(request.getZIndex());
            if (request.getLocked() != null) node.setLocked(request.getLocked());

            nodeMapper.updateById(node);
            updatedNodes.add(node);
        }

        if (!updatedNodes.isEmpty() && canvasId != null) {
            eventPublisher.publishAsync(new BatchNodesUpdatedEvent(
                    canvasId, updatedNodes, previousStates, userId, workspaceId));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchDelete(List<String> nodeIds, String userId) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return;
        }

        List<CanvasNode> deletedNodes = new ArrayList<>();
        for (String nodeId : nodeIds) {
            CanvasNode node = nodeMapper.selectById(nodeId);
            if (node != null) {
                node.setDeleted(CommonConstants.DELETED);
                nodeMapper.updateById(node);
                deletedNodes.add(node);
            }
        }

        for (CanvasNode node : deletedNodes) {
            eventPublisher.publishAsync(new NodeDeletedEvent(
                    node.getId(), node.getCanvasId(),
                    node.getEntityType(), node.getEntityId(),
                    userId, node.getWorkspaceId()));
        }
    }

    private CanvasNode getNodeOrThrow(String nodeId) {
        CanvasNode node = nodeMapper.selectById(nodeId);
        if (node == null || node.getDeleted() == CommonConstants.DELETED) {
            throw new BusinessException(ResultCode.NOT_FOUND, "节点不存在");
        }
        return node;
    }

    /**
     * 复制节点状态（用于事件发布时记录之前的状态）
     */
    private CanvasNode copyNodeState(CanvasNode source) {
        CanvasNode copy = new CanvasNode();
        copy.setId(source.getId());
        copy.setWorkspaceId(source.getWorkspaceId());
        copy.setCanvasId(source.getCanvasId());
        copy.setEntityType(source.getEntityType());
        copy.setEntityId(source.getEntityId());
        copy.setPositionX(source.getPositionX());
        copy.setPositionY(source.getPositionY());
        copy.setWidth(source.getWidth());
        copy.setHeight(source.getHeight());
        copy.setCollapsed(source.getCollapsed());
        copy.setLocked(source.getLocked());
        copy.setZIndex(source.getZIndex());
        copy.setStyle(source.getStyle() != null ? new HashMap<>(source.getStyle()) : null);
        return copy;
    }
}
