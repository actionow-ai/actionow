package com.actionow.canvas.service.impl;

import com.actionow.canvas.constant.CanvasConstants;
import com.actionow.common.core.constant.CommonConstants;
import com.actionow.canvas.dto.feign.EntityInfo;
import com.actionow.canvas.dto.canvas.*;
import com.actionow.canvas.dto.edge.CanvasEdgeResponse;
import com.actionow.canvas.dto.node.CanvasNodeResponse;
import com.actionow.canvas.dto.view.ViewDataRequest;
import com.actionow.canvas.dto.view.ViewDataResponse;
import com.actionow.canvas.entity.Canvas;
import com.actionow.canvas.entity.CanvasEdge;
import com.actionow.canvas.entity.CanvasNode;
import com.actionow.canvas.entity.CanvasView;
import com.actionow.canvas.event.CanvasEventPublisher;
import com.actionow.canvas.event.CanvasLayoutChangedEvent;
import com.actionow.canvas.event.CanvasUpdatedEvent;
import com.actionow.canvas.feign.ProjectFeignClient;
import com.actionow.canvas.layout.LayoutConfig;
import com.actionow.canvas.layout.LayoutEngine;
import com.actionow.canvas.layout.LayoutEngineFactory;
import com.actionow.canvas.mapper.CanvasEdgeMapper;
import com.actionow.canvas.mapper.CanvasMapper;
import com.actionow.canvas.mapper.CanvasNodeMapper;
import com.actionow.canvas.mapper.CanvasViewMapper;
import com.actionow.canvas.service.CanvasService;
import com.actionow.canvas.service.CanvasViewService;
import com.actionow.canvas.service.NodeEnrichmentService;
import com.actionow.common.core.exception.BusinessException;
import com.actionow.common.core.id.UuidGenerator;
import com.actionow.common.core.result.Result;
import com.actionow.common.core.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 画布服务实现
 * 统一主画布模型：1 Script = 1 Canvas
 *
 * @author Actionow
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CanvasServiceImpl implements CanvasService {

    private final CanvasMapper canvasMapper;
    private final CanvasNodeMapper nodeMapper;
    private final CanvasEdgeMapper edgeMapper;
    private final CanvasViewMapper viewMapper;
    private final ProjectFeignClient projectFeignClient;
    private final LayoutEngineFactory layoutEngineFactory;
    private final CanvasEventPublisher eventPublisher;
    private final CanvasViewService viewService;
    private final NodeEnrichmentService nodeEnrichmentService;

    @Override
    public List<CanvasResponse> listByWorkspace(String workspaceId) {
        return canvasMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Canvas>()
                        .eq(Canvas::getWorkspaceId, workspaceId)
                        .orderByDesc(Canvas::getCreatedAt)
        ).stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CanvasResponse createCanvas(CreateCanvasRequest request, String workspaceId, String userId) {
        // 检查剧本是否已有画布
        if (canvasMapper.existsByScriptId(request.getScriptId())) {
            throw new BusinessException(ResultCode.ALREADY_EXISTS, "该剧本已存在画布");
        }

        Canvas canvas = new Canvas();
        canvas.setId(UuidGenerator.generateUuidV7());
        canvas.setWorkspaceId(workspaceId);
        canvas.setScriptId(request.getScriptId());
        canvas.setName(StringUtils.hasText(request.getName()) ? request.getName() : "剧本画布");
        canvas.setDescription(request.getDescription());
        canvas.setLayoutStrategy(StringUtils.hasText(request.getLayoutStrategy()) ?
                request.getLayoutStrategy() : CanvasConstants.LayoutStrategy.GRID);
        canvas.setLocked(false);
        canvas.setViewport(Map.of("x", 0, "y", 0, "zoom", 1));
        canvas.setSettings(request.getSettings() != null ? request.getSettings() : new HashMap<>());

        canvasMapper.insert(canvas);

        // 初始化预设视图
        viewService.initPresetViews(canvas.getId(), workspaceId);

        // 初始化骨架节点（SCRIPT + 5 个 GROUP）
        initSkeletonNodes(canvas.getId(), request.getScriptId(), workspaceId);

        log.info("创建画布: canvasId={}, scriptId={}, workspaceId={}",
                canvas.getId(), canvas.getScriptId(), workspaceId);

        return toResponse(canvas);
    }

    @Override
    public CanvasResponse getCanvas(String canvasId) {
        Canvas canvas = getCanvasEntity(canvasId);
        return toResponse(canvas);
    }

    @Override
    public CanvasResponse getCanvasByScriptId(String scriptId) {
        Canvas canvas = canvasMapper.selectByScriptId(scriptId);
        if (canvas == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "画布不存在");
        }
        return toResponse(canvas);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CanvasResponse getOrCreateByScriptId(String scriptId, String workspaceId, String userId) {
        Canvas canvas = canvasMapper.selectByScriptId(scriptId);
        if (canvas != null) {
            // 老画布也补一次骨架（幂等：已存在的 GROUP 节点不会被重复插入）
            initSkeletonNodes(canvas.getId(), scriptId, workspaceId);
            return toResponse(canvas);
        }

        CreateCanvasRequest request = new CreateCanvasRequest();
        request.setScriptId(scriptId);

        return createCanvas(request, workspaceId, userId);
    }

    /**
     * 初始化画布骨架节点：
     * - 1 个 SCRIPT ENTITY 节点（中心顶部）
     * - 5 个 GROUP 容器节点（CHARACTER / SCENE / PROP / EPISODE / STORYBOARD）
     *
     * 幂等：如果该 canvas 已存在某类型的 GROUP 节点，则跳过该类型；SCRIPT 节点同理。
     * 用 nodeMapper 直接 insert，绕过 nodeService 的"必须有 entityId/entityName"校验。
     */
    private void initSkeletonNodes(String canvasId, String scriptId, String workspaceId) {
        // 不限 deleted 查（DB unique 约束不带 WHERE deleted=0，软删的旧记录仍占 key 槽位）
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CanvasNode> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.eq(CanvasNode::getCanvasId, canvasId)
                .eq(CanvasNode::getEntityType, CanvasConstants.EntityType.SCRIPT)
                .eq(CanvasNode::getEntityId, scriptId)
                .last("LIMIT 1");
        CanvasNode existing = nodeMapper.selectOne(wrapper);

        if (existing == null) {
            CanvasNode scriptNode = new CanvasNode();
            scriptNode.setId(UuidGenerator.generateUuidV7());
            scriptNode.setWorkspaceId(workspaceId);
            scriptNode.setCanvasId(canvasId);
            scriptNode.setNodeType("ENTITY");
            scriptNode.setEntityType(CanvasConstants.EntityType.SCRIPT);
            scriptNode.setEntityId(scriptId);
            scriptNode.setLayer(CanvasConstants.Layer.SCRIPT);
            scriptNode.setPositionX(BigDecimal.valueOf(560));
            scriptNode.setPositionY(BigDecimal.valueOf(40));
            scriptNode.setWidth(BigDecimal.valueOf(280));
            scriptNode.setHeight(BigDecimal.valueOf(120));
            scriptNode.setCollapsed(false);
            scriptNode.setLocked(false);
            scriptNode.setZIndex(0);
            scriptNode.setStyle(new HashMap<>());
            nodeMapper.insert(scriptNode);
        } else if (existing.getDeleted() != null
                && existing.getDeleted() == CommonConstants.DELETED) {
            // 软删过的 SCRIPT 节点 → 复活（避免 unique key 冲突）
            existing.setDeleted(CommonConstants.NOT_DELETED);
            existing.setPositionX(BigDecimal.valueOf(560));
            existing.setPositionY(BigDecimal.valueOf(40));
            nodeMapper.updateById(existing);
            log.info("复活已软删的 SCRIPT 节点: canvasId={}, scriptId={}", canvasId, scriptId);
        }
        // 否则节点存在且未删，跳过

        // 不再创建 5 个 GROUP 容器节点（TapNow 风格画布只保留 SCRIPT，
        // 其他节点由用户双击空白时自由创建）。
    }

    /**
     * 把 parent_node_id 为 null 的老 ENTITY 节点挂到对应类型的 GROUP 节点下。
     * 幂等：跑完一次后续基本是空操作（每次只处理 parent_node_id IS NULL 的孤儿）。
     */
    private void backfillOrphanParentNodeIds(String canvasId) {
        List<CanvasNode> all = nodeMapper.selectByCanvasId(canvasId);
        Map<String, String> groupIdByEntityType = new HashMap<>();
        for (CanvasNode n : all) {
            if ("GROUP".equals(n.getNodeType()) && n.getEntityType() != null) {
                groupIdByEntityType.putIfAbsent(n.getEntityType(), n.getId());
            }
        }

        int backfilled = 0;
        for (CanvasNode n : all) {
            if (!"ENTITY".equals(n.getNodeType())) continue;
            if (n.getParentNodeId() != null) continue;
            // SCRIPT 节点不挂到任何组下
            if (CanvasConstants.EntityType.SCRIPT.equals(n.getEntityType())) continue;
            String groupId = groupIdByEntityType.get(n.getEntityType());
            if (groupId == null) continue;
            n.setParentNodeId(groupId);
            nodeMapper.updateById(n);
            backfilled++;
        }
        if (backfilled > 0) {
            log.info("回填 parent_node_id 完成: canvasId={}, count={}", canvasId, backfilled);
        }
    }

    @Override
    public CanvasFullResponse getCanvasFull(String canvasId) {
        Canvas canvas = getCanvasEntity(canvasId);

        List<CanvasNode> nodes = nodeMapper.selectByCanvasId(canvasId);
        List<CanvasEdge> edges = edgeMapper.selectByCanvasId(canvasId);

        // 转换节点响应
        List<CanvasNodeResponse> nodeResponses = nodes.stream()
                .map(CanvasNodeResponse::fromEntity)
                .collect(Collectors.toList());

        // 批量获取实体详情并填充
        enrichNodeResponses(nodeResponses);

        // 构建实体到节点ID的映射 (entityType:entityId -> nodeId)
        Map<String, String> entityToNodeIdMap = nodes.stream()
                .collect(Collectors.toMap(
                        n -> n.getEntityType() + ":" + n.getEntityId(),
                        CanvasNode::getId,
                        (existing, replacement) -> existing
                ));

        CanvasFullResponse response = new CanvasFullResponse();
        copyToFullResponse(canvas, response);
        response.setNodeCount(nodes.size());
        response.setEdgeCount(edges.size());
        response.setNodes(nodeResponses);
        response.setEdges(edges.stream()
                .map(edge -> toEdgeResponse(edge, entityToNodeIdMap))
                .collect(Collectors.toList()));

        return response;
    }

    @Override
    public ViewDataResponse getViewData(ViewDataRequest request) {
        Canvas canvas = getCanvasEntity(request.getCanvasId());
        String viewKey = request.getViewKey();
        String canvasId = request.getCanvasId();

        // 检查是否为聚焦模式
        boolean isFocusMode = StringUtils.hasText(request.getFocusEntityType())
                && StringUtils.hasText(request.getFocusEntityId());

        // 获取可见实体类型
        Set<String> visibleTypes;
        String viewName;
        if (StringUtils.hasText(viewKey)) {
            visibleTypes = CanvasConstants.VisibleEntityTypes.getByViewKey(viewKey);
            CanvasView view = viewMapper.selectByCanvasIdAndViewKey(canvasId, viewKey);
            viewName = view != null ? view.getName() : viewKey;
        } else {
            // 不指定视图则返回所有
            visibleTypes = CanvasConstants.VisibleEntityTypes.SCRIPT_VIEW;
            viewName = "全部";
        }

        List<CanvasNode> nodes;
        List<CanvasEdge> visibleEdges;
        String focusEntityName = null;

        if (isFocusMode) {
            // === 聚焦模式：只显示特定实体及其关联节点 ===
            String focusEntityType = request.getFocusEntityType();
            String focusEntityId = request.getFocusEntityId();
            int depth = request.getDepth() != null ? request.getDepth() : 1;

            // 收集需要显示的实体键集合
            Set<String> visibleEntityKeys = new HashSet<>();
            visibleEntityKeys.add(focusEntityType + ":" + focusEntityId);

            // 收集相关边
            Set<CanvasEdge> allRelatedEdges = new HashSet<>();

            // 逐层收集关联实体
            Set<String> currentLevelKeys = new HashSet<>(visibleEntityKeys);
            for (int d = 0; d < depth; d++) {
                List<CanvasEdge> levelEdges = edgeMapper.selectByCanvasAndEntityKeys(
                        canvasId, new ArrayList<>(currentLevelKeys));
                allRelatedEdges.addAll(levelEdges);

                // 收集下一层的实体键
                Set<String> nextLevelKeys = new HashSet<>();
                for (CanvasEdge edge : levelEdges) {
                    String sourceKey = edge.getSourceType() + ":" + edge.getSourceId();
                    String targetKey = edge.getTargetType() + ":" + edge.getTargetId();
                    if (!visibleEntityKeys.contains(sourceKey)) {
                        nextLevelKeys.add(sourceKey);
                    }
                    if (!visibleEntityKeys.contains(targetKey)) {
                        nextLevelKeys.add(targetKey);
                    }
                }
                visibleEntityKeys.addAll(nextLevelKeys);
                currentLevelKeys = nextLevelKeys;

                if (nextLevelKeys.isEmpty()) {
                    break; // 没有更多关联实体
                }
            }

            // 查询所有可见节点，然后过滤
            List<CanvasNode> allNodes = nodeMapper.selectByCanvasId(canvasId);
            nodes = allNodes.stream()
                    .filter(n -> visibleEntityKeys.contains(n.getEntityType() + ":" + n.getEntityId()))
                    .collect(Collectors.toList());

            // 聚焦实体的名称在 enrichNodeResponses 后从 entityDetail.name 读取（见下方）

            // 过滤边：只保留两端都在可见节点中的边
            Set<String> visibleNodeEntityKeys = nodes.stream()
                    .map(n -> n.getEntityType() + ":" + n.getEntityId())
                    .collect(Collectors.toSet());
            visibleEdges = allRelatedEdges.stream()
                    .filter(edge -> {
                        String sourceKey = edge.getSourceType() + ":" + edge.getSourceId();
                        String targetKey = edge.getTargetType() + ":" + edge.getTargetId();
                        return visibleNodeEntityKeys.contains(sourceKey)
                                && visibleNodeEntityKeys.contains(targetKey);
                    })
                    .collect(Collectors.toList());

        } else {
            // === 普通模式：按视图类型过滤 ===
            List<CanvasEdge> allEdges = edgeMapper.selectByCanvasId(canvasId);

            // SCRIPT 视图显示所有节点（全量视图）
            if (CanvasConstants.ViewKey.SCRIPT.equals(viewKey)) {
                nodes = nodeMapper.selectVisibleByCanvasIdAndEntityTypes(
                        canvasId, new ArrayList<>(visibleTypes));
            } else {
                // 非 SCRIPT 视图：ASSET 节点只显示与其他节点有边连接的
                // 1. 获取非 ASSET 类型的可见节点
                List<String> nonAssetTypes = visibleTypes.stream()
                        .filter(t -> !CanvasConstants.EntityType.ASSET.equals(t))
                        .collect(Collectors.toList());

                List<CanvasNode> nonAssetNodes = nonAssetTypes.isEmpty()
                        ? List.of()
                        : nodeMapper.selectVisibleByCanvasIdAndEntityTypes(canvasId, nonAssetTypes);

                // 2. 收集非 ASSET 节点的实体键
                Set<String> nonAssetEntityKeys = nonAssetNodes.stream()
                        .map(n -> n.getEntityType() + ":" + n.getEntityId())
                        .collect(Collectors.toSet());

                // 3. 找出与非 ASSET 节点有边连接的 ASSET 实体键
                Set<String> connectedAssetEntityKeys = new HashSet<>();
                for (CanvasEdge edge : allEdges) {
                    String sourceType = CanvasConstants.EntityType.normalize(edge.getSourceType());
                    String targetType = CanvasConstants.EntityType.normalize(edge.getTargetType());
                    String sourceKey = edge.getSourceType() + ":" + edge.getSourceId();
                    String targetKey = edge.getTargetType() + ":" + edge.getTargetId();

                    // 源是非 ASSET，目标是 ASSET
                    if (nonAssetEntityKeys.contains(sourceKey)
                            && CanvasConstants.EntityType.isAssetType(targetType)) {
                        connectedAssetEntityKeys.add(targetKey);
                    }
                    // 源是 ASSET，目标是非 ASSET
                    if (CanvasConstants.EntityType.isAssetType(sourceType)
                            && nonAssetEntityKeys.contains(targetKey)) {
                        connectedAssetEntityKeys.add(sourceKey);
                    }
                    // ASSET 视图：ASSET 与 ASSET 之间的连接
                    if (CanvasConstants.ViewKey.ASSET.equals(viewKey)
                            && CanvasConstants.EntityType.isAssetType(sourceType)
                            && CanvasConstants.EntityType.isAssetType(targetType)) {
                        connectedAssetEntityKeys.add(sourceKey);
                        connectedAssetEntityKeys.add(targetKey);
                    }
                }

                // 4. 查询有边连接的 ASSET 节点
                List<CanvasNode> connectedAssetNodes = List.of();
                if (!connectedAssetEntityKeys.isEmpty()) {
                    List<CanvasNode> allAssetNodes = nodeMapper.selectVisibleByCanvasIdAndEntityTypes(
                            canvasId, List.of(CanvasConstants.EntityType.ASSET));
                    connectedAssetNodes = allAssetNodes.stream()
                            .filter(n -> connectedAssetEntityKeys.contains(n.getEntityType() + ":" + n.getEntityId()))
                            .collect(Collectors.toList());
                }

                // 5. 合并节点列表
                nodes = new ArrayList<>(nonAssetNodes);
                nodes.addAll(connectedAssetNodes);
            }

            // 查询相关的边（两端节点都在可见类型中）
            visibleEdges = allEdges.stream()
                    .filter(edge -> {
                        String normalizedSource = CanvasConstants.EntityType.normalize(edge.getSourceType());
                        String normalizedTarget = CanvasConstants.EntityType.normalize(edge.getTargetType());
                        return visibleTypes.contains(normalizedSource)
                                && visibleTypes.contains(normalizedTarget);
                    })
                    .collect(Collectors.toList());
        }

        // 转换节点响应
        List<CanvasNodeResponse> nodeResponses = nodes.stream()
                .map(CanvasNodeResponse::fromEntity)
                .collect(Collectors.toList());

        // 填充实体详情（如果需要）
        if (Boolean.TRUE.equals(request.getIncludeEntityDetail())) {
            enrichNodeResponses(nodeResponses);
            // 如果聚焦模式下还没有获取到名称，尝试从详情中获取
            if (isFocusMode && focusEntityName == null) {
                String focusEntityType = request.getFocusEntityType();
                String focusEntityId = request.getFocusEntityId();
                for (CanvasNodeResponse nodeResp : nodeResponses) {
                    if (focusEntityType.equals(nodeResp.getEntityType())
                            && focusEntityId.equals(nodeResp.getEntityId())) {
                        if (nodeResp.getEntityDetail() != null) {
                            Object name = nodeResp.getEntityDetail().get("name");
                            if (name != null) {
                                focusEntityName = name.toString();
                            }
                        }
                        break;
                    }
                }
            }
        }

        // 构建响应
        Map<String, String> entityToNodeIdMap = nodes.stream()
                .collect(Collectors.toMap(
                        n -> n.getEntityType() + ":" + n.getEntityId(),
                        CanvasNode::getId,
                        (existing, replacement) -> existing
                ));

        ViewDataResponse response = new ViewDataResponse();
        response.setCanvasId(canvasId);
        response.setViewKey(viewKey);
        response.setViewName(viewName);
        response.setFocusMode(isFocusMode);
        if (isFocusMode) {
            response.setFocusEntityType(request.getFocusEntityType());
            response.setFocusEntityId(request.getFocusEntityId());
            response.setFocusEntityName(focusEntityName);
        }
        response.setNodes(nodeResponses);
        response.setEdges(visibleEdges.stream()
                .map(edge -> toEdgeResponse(edge, entityToNodeIdMap))
                .collect(Collectors.toList()));
        response.setTotalNodes(nodeResponses.size());
        response.setTotalEdges(visibleEdges.size());

        // 统计各类型节点数量
        Map<String, Integer> nodeCountByType = nodeResponses.stream()
                .collect(Collectors.groupingBy(
                        CanvasNodeResponse::getEntityType,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
                ));
        response.setNodeCountByType(nodeCountByType);

        return response;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CanvasResponse updateCanvas(String canvasId, UpdateCanvasRequest request, String userId) {
        Canvas canvas = getCanvasEntity(canvasId);

        Canvas previousState = copyCanvasState(canvas);

        if (StringUtils.hasText(request.getName())) {
            canvas.setName(request.getName());
        }
        if (request.getDescription() != null) {
            canvas.setDescription(request.getDescription());
        }
        if (StringUtils.hasText(request.getLayoutStrategy())) {
            canvas.setLayoutStrategy(request.getLayoutStrategy());
        }
        if (request.getLocked() != null) {
            canvas.setLocked(request.getLocked());
        }
        if (request.getViewport() != null) {
            canvas.setViewport(request.getViewport());
        }
        if (request.getSettings() != null) {
            canvas.setSettings(request.getSettings());
        }

        canvasMapper.updateById(canvas);

        log.info("更新画布: canvasId={}", canvasId);

        eventPublisher.publishAsync(new CanvasUpdatedEvent(canvas, previousState, userId, canvas.getWorkspaceId()));

        return toResponse(canvas);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateViewport(String canvasId, Map<String, Object> viewport, String userId) {
        Canvas canvas = getCanvasEntity(canvasId);

        // 保存之前的状态用于事件
        Canvas previousState = copyCanvasState(canvas);

        canvas.setViewport(viewport);
        canvasMapper.updateById(canvas);

        log.debug("更新画布视口: canvasId={}, viewport={}", canvasId, viewport);

        // 发布画布更新事件，广播给其他用户（不会发给操作者自己）
        // 这是解决 Viewport 跳回问题的关键
        eventPublisher.publishAsync(new CanvasUpdatedEvent(canvas, previousState, userId, canvas.getWorkspaceId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCanvas(String canvasId, String userId) {
        Canvas canvas = getCanvasEntity(canvasId);

        // 删除所有视图
        viewMapper.deleteByCanvasId(canvasId);
        // 删除所有节点和边
        nodeMapper.deleteByCanvasId(canvasId);
        edgeMapper.deleteByCanvasId(canvasId);
        // 删除画布
        canvasMapper.deleteById(canvasId);

        log.info("删除画布: canvasId={}", canvasId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByScriptId(String scriptId, String userId) {
        Canvas canvas = canvasMapper.selectByScriptId(scriptId);
        if (canvas != null) {
            deleteCanvas(canvas.getId(), userId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CanvasFullResponse autoLayout(String canvasId, String strategy, String viewKey, String userId) {
        Canvas canvas = getCanvasEntity(canvasId);

        // 根据视图筛选节点
        List<CanvasNode> nodes;
        if (StringUtils.hasText(viewKey)) {
            Set<String> visibleTypes = CanvasConstants.VisibleEntityTypes.getByViewKey(viewKey);
            nodes = nodeMapper.selectVisibleByCanvasIdAndEntityTypes(canvasId, new ArrayList<>(visibleTypes));
        } else {
            nodes = nodeMapper.selectByCanvasId(canvasId);
        }

        if (nodes.isEmpty()) {
            return getCanvasFull(canvasId);
        }

        List<CanvasEdge> edges = edgeMapper.selectByCanvasId(canvasId);
        String layoutStrategy = StringUtils.hasText(strategy) ? strategy : canvas.getLayoutStrategy();

        LayoutEngine engine = layoutEngineFactory.getEngine(layoutStrategy);
        LayoutConfig config = LayoutConfig.builder()
                .centerX(500.0)
                .centerY(400.0)
                .build();

        engine.applyLayout(nodes, edges, config);

        for (CanvasNode node : nodes) {
            nodeMapper.updateById(node);
        }

        log.info("自动布局画布: canvasId={}, strategy={}, viewKey={}, nodeCount={}",
                canvasId, layoutStrategy, viewKey, nodes.size());

        eventPublisher.publishAsync(new CanvasLayoutChangedEvent(canvasId, layoutStrategy, nodes.size(), userId, canvas.getWorkspaceId()));

        return getCanvasFull(canvasId);
    }

    @Override
    public Canvas getCanvasEntity(String canvasId) {
        Canvas canvas = canvasMapper.selectById(canvasId);
        if (canvas == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "画布不存在");
        }
        return canvas;
    }

    @Override
    public boolean existsByScriptId(String scriptId) {
        return canvasMapper.existsByScriptId(scriptId);
    }

    /**
     * 增强节点列表 - delegate 到共享的 NodeEnrichmentService
     */
    private void enrichNodeResponses(List<CanvasNodeResponse> nodeResponses) {
        nodeEnrichmentService.enrich(nodeResponses);
    }

    private CanvasResponse toResponse(Canvas canvas) {
        CanvasResponse response = new CanvasResponse();
        response.setId(canvas.getId());
        response.setScriptId(canvas.getScriptId());
        response.setName(canvas.getName());
        response.setDescription(canvas.getDescription());
        response.setViewport(canvas.getViewport());
        response.setLayoutStrategy(canvas.getLayoutStrategy());
        response.setLocked(canvas.getLocked());
        response.setSettings(canvas.getSettings());
        response.setCreatedAt(canvas.getCreatedAt());
        response.setUpdatedAt(canvas.getUpdatedAt());

        // 获取节点和边数量
        response.setNodeCount((int) nodeMapper.countByCanvasId(canvas.getId()));
        response.setEdgeCount((int) edgeMapper.countByCanvasId(canvas.getId()));

        // 获取视图列表
        List<CanvasView> views = viewMapper.selectByCanvasId(canvas.getId());
        response.setViews(views.stream()
                .map(this::toViewResponse)
                .collect(Collectors.toList()));

        return response;
    }

    private void copyToFullResponse(Canvas canvas, CanvasFullResponse response) {
        response.setId(canvas.getId());
        response.setScriptId(canvas.getScriptId());
        response.setName(canvas.getName());
        response.setDescription(canvas.getDescription());
        response.setViewport(canvas.getViewport());
        response.setLayoutStrategy(canvas.getLayoutStrategy());
        response.setLocked(canvas.getLocked());
        response.setSettings(canvas.getSettings());
        response.setCreatedAt(canvas.getCreatedAt());
        response.setUpdatedAt(canvas.getUpdatedAt());

        List<CanvasView> views = viewMapper.selectByCanvasId(canvas.getId());
        response.setViews(views.stream()
                .map(this::toViewResponse)
                .collect(Collectors.toList()));
    }

    private CanvasResponse.CanvasViewResponse toViewResponse(CanvasView view) {
        CanvasResponse.CanvasViewResponse response = new CanvasResponse.CanvasViewResponse();
        response.setId(view.getId());
        response.setViewKey(view.getViewKey());
        response.setName(view.getName());
        response.setIcon(view.getIcon());
        response.setViewType(view.getViewType());
        response.setRootEntityType(view.getRootEntityType());
        response.setVisibleEntityTypes(view.getVisibleEntityTypes());
        response.setSequence(view.getSequence());
        response.setIsDefault(view.getIsDefault());
        return response;
    }

    private CanvasEdgeResponse toEdgeResponse(CanvasEdge edge, Map<String, String> entityToNodeIdMap) {
        CanvasEdgeResponse response = new CanvasEdgeResponse();
        response.setId(edge.getId());
        response.setCanvasId(edge.getCanvasId());
        response.setSourceType(edge.getSourceType());
        response.setSourceId(edge.getSourceId());
        response.setSourceVersionId(edge.getSourceVersionId());
        response.setSourceHandle(edge.getSourceHandle());
        response.setTargetType(edge.getTargetType());
        response.setTargetId(edge.getTargetId());
        response.setTargetVersionId(edge.getTargetVersionId());
        response.setTargetHandle(edge.getTargetHandle());
        response.setRelationType(edge.getRelationType());
        response.setRelationLabel(edge.getRelationLabel());
        response.setDescription(edge.getDescription());
        response.setLineStyle(edge.getLineStyle());
        response.setPathType(edge.getPathType());
        response.setSequence(edge.getSequence());
        response.setExtraInfo(edge.getExtraInfo());
        response.setCreatedAt(edge.getCreatedAt());
        response.setUpdatedAt(edge.getUpdatedAt());

        if (entityToNodeIdMap != null) {
            String sourceKey = edge.getSourceType() + ":" + edge.getSourceId();
            String targetKey = edge.getTargetType() + ":" + edge.getTargetId();
            response.setSourceNodeId(entityToNodeIdMap.get(sourceKey));
            response.setTargetNodeId(entityToNodeIdMap.get(targetKey));
        }

        return response;
    }

    private Canvas copyCanvasState(Canvas source) {
        Canvas copy = new Canvas();
        copy.setId(source.getId());
        copy.setWorkspaceId(source.getWorkspaceId());
        copy.setScriptId(source.getScriptId());
        copy.setName(source.getName());
        copy.setDescription(source.getDescription());
        copy.setLayoutStrategy(source.getLayoutStrategy());
        copy.setLocked(source.getLocked());
        copy.setViewport(source.getViewport() != null ? new HashMap<>(source.getViewport()) : null);
        copy.setSettings(source.getSettings() != null ? new HashMap<>(source.getSettings()) : null);
        return copy;
    }
}
