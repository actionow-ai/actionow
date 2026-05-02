package com.actionow.canvas.service.impl;

import com.actionow.canvas.constant.CanvasConstants;
import com.actionow.canvas.dto.edge.CanvasEdgeResponse;
import com.actionow.canvas.dto.edge.CreateEdgeRequest;
import com.actionow.canvas.dto.edge.UpdateEdgeRequest;
import com.actionow.canvas.entity.Canvas;
import com.actionow.canvas.entity.CanvasEdge;
import com.actionow.canvas.entity.CanvasNode;
import com.actionow.canvas.event.CanvasEventPublisher;
import com.actionow.canvas.event.EdgeCreatedEvent;
import com.actionow.canvas.event.EdgeDeletedEvent;
import com.actionow.canvas.event.EdgeUpdatedEvent;
import com.actionow.canvas.mapper.CanvasEdgeMapper;
import com.actionow.canvas.mapper.CanvasMapper;
import com.actionow.canvas.mapper.CanvasNodeMapper;
import com.actionow.canvas.service.CanvasEdgeService;
import com.actionow.common.core.exception.BusinessException;
import com.actionow.common.core.id.UuidGenerator;
import com.actionow.common.core.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 画布边服务实现
 *
 * @author Actionow
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CanvasEdgeServiceImpl implements CanvasEdgeService {

    private final CanvasEdgeMapper edgeMapper;
    private final CanvasMapper canvasMapper;
    private final CanvasNodeMapper nodeMapper;
    private final CanvasEventPublisher eventPublisher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CanvasEdgeResponse createEdge(CreateEdgeRequest request, String workspaceId, String userId) {
        // 验证画布存在
        Canvas canvas = canvasMapper.selectById(request.getCanvasId());
        if (canvas == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "画布不存在");
        }

        // 验证边规则（统一画布使用 SCRIPT 视图规则，最宽松）
        if (!CanvasConstants.EdgeRules.isEdgeAllowed(CanvasConstants.ViewKey.SCRIPT,
                request.getSourceType(), request.getTargetType())) {
            throw new BusinessException(ResultCode.PARAM_INVALID,
                    "不允许从 " + request.getSourceType() + " 连接到 " + request.getTargetType());
        }

        // 检查是否已存在
        CanvasEdge existing = edgeMapper.selectBySourceTargetAndType(
                request.getCanvasId(),
                request.getSourceType(), request.getSourceId(),
                request.getTargetType(), request.getTargetId(),
                StringUtils.hasText(request.getRelationType()) ?
                        request.getRelationType() :
                        CanvasConstants.EdgeRules.inferRelationType(request.getSourceType(), request.getTargetType()));
        if (existing != null) {
            throw new BusinessException(ResultCode.ALREADY_EXISTS, "边已存在");
        }

        // 推断关系类型
        String relationType = StringUtils.hasText(request.getRelationType()) ?
                request.getRelationType() :
                CanvasConstants.EdgeRules.inferRelationType(request.getSourceType(), request.getTargetType());

        // 获取序号
        Integer maxSequence = edgeMapper.selectMaxSequence(request.getCanvasId());

        CanvasEdge edge = new CanvasEdge();
        edge.setId(UuidGenerator.generateUuidV7());
        edge.setWorkspaceId(workspaceId);
        edge.setCanvasId(request.getCanvasId());
        edge.setSourceType(request.getSourceType());
        edge.setSourceId(request.getSourceId());
        edge.setSourceVersionId(request.getSourceVersionId());
        edge.setSourceHandle(request.getSourceHandle());
        edge.setTargetType(request.getTargetType());
        edge.setTargetId(request.getTargetId());
        edge.setTargetVersionId(request.getTargetVersionId());
        edge.setTargetHandle(request.getTargetHandle());
        edge.setRelationType(relationType);
        edge.setRelationLabel(StringUtils.hasText(request.getRelationLabel()) ?
                request.getRelationLabel() : CanvasConstants.RelationType.getLabel(relationType));
        edge.setDescription(request.getDescription());
        edge.setLineStyle(request.getLineStyle() != null ? request.getLineStyle() : getDefaultLineStyle());
        edge.setPathType(StringUtils.hasText(request.getPathType()) ?
                request.getPathType() : CanvasConstants.PathType.BEZIER);
        edge.setSequence(maxSequence + 1);
        edge.setExtraInfo(new HashMap<>());

        edgeMapper.insert(edge);

        log.info("创建画布边: edgeId={}, canvasId={}, {}[{}] -> {}[{}]",
                edge.getId(), edge.getCanvasId(),
                edge.getSourceType(), edge.getSourceId(),
                edge.getTargetType(), edge.getTargetId());

        // 发布边创建事件（WebSocket 广播）
        eventPublisher.publishAsync(new EdgeCreatedEvent(edge, userId, workspaceId));

        return toResponse(edge);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<CanvasEdgeResponse> batchCreateEdges(List<CreateEdgeRequest> requests,
                                                      String workspaceId, String userId) {
        List<CanvasEdgeResponse> responses = new ArrayList<>();
        for (CreateEdgeRequest request : requests) {
            try {
                responses.add(createEdge(request, workspaceId, userId));
            } catch (BusinessException e) {
                log.warn("批量创建边跳过: {}", e.getMessage());
            }
        }
        return responses;
    }

    @Override
    public CanvasEdgeResponse getEdge(String edgeId) {
        CanvasEdge edge = edgeMapper.selectById(edgeId);
        if (edge == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "边不存在");
        }
        return toResponse(edge);
    }

    @Override
    public List<CanvasEdgeResponse> listByCanvasId(String canvasId) {
        List<CanvasEdge> edges = edgeMapper.selectByCanvasId(canvasId);
        return edges.stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CanvasEdgeResponse updateEdge(String edgeId, UpdateEdgeRequest request, String userId) {
        CanvasEdge edge = edgeMapper.selectById(edgeId);
        if (edge == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "边不存在");
        }

        // 保存之前的状态用于事件
        CanvasEdge previousState = copyEdgeState(edge);

        if (StringUtils.hasText(request.getRelationLabel())) {
            edge.setRelationLabel(request.getRelationLabel());
        }
        if (StringUtils.hasText(request.getDescription())) {
            edge.setDescription(request.getDescription());
        }
        if (StringUtils.hasText(request.getSourceHandle())) {
            edge.setSourceHandle(request.getSourceHandle());
        }
        if (StringUtils.hasText(request.getTargetHandle())) {
            edge.setTargetHandle(request.getTargetHandle());
        }
        if (request.getLineStyle() != null) {
            edge.setLineStyle(request.getLineStyle());
        }
        if (StringUtils.hasText(request.getPathType())) {
            edge.setPathType(request.getPathType());
        }
        if (request.getSequence() != null) {
            edge.setSequence(request.getSequence());
        }
        if (request.getExtraInfo() != null) {
            edge.setExtraInfo(request.getExtraInfo());
        }

        edgeMapper.updateById(edge);

        log.info("更新画布边: edgeId={}", edgeId);

        // 发布边更新事件（WebSocket 广播）
        eventPublisher.publishAsync(new EdgeUpdatedEvent(edge, previousState, userId, edge.getWorkspaceId()));

        return toResponse(edge);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteEdge(String edgeId, String userId) {
        CanvasEdge edge = edgeMapper.selectById(edgeId);
        if (edge == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "边不存在");
        }

        String canvasId = edge.getCanvasId();
        String sourceType = edge.getSourceType();
        String sourceId = edge.getSourceId();
        String targetType = edge.getTargetType();
        String targetId = edge.getTargetId();
        String workspaceId = edge.getWorkspaceId();

        edgeMapper.deleteById(edgeId);
        log.info("删除画布边: edgeId={}", edgeId);

        // 发布边删除事件（WebSocket 广播）
        eventPublisher.publishAsync(new EdgeDeletedEvent(edgeId, canvasId, sourceType, sourceId, targetType, targetId, userId, workspaceId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByCanvasId(String canvasId) {
        int count = edgeMapper.deleteByCanvasId(canvasId);
        log.info("删除画布所有边: canvasId={}, count={}", canvasId, count);
    }

    @Override
    public boolean validateEdge(String canvasId, String sourceType, String sourceId,
                                String targetType, String targetId) {
        Canvas canvas = canvasMapper.selectById(canvasId);
        if (canvas == null) {
            return false;
        }
        // 统一画布使用 SCRIPT 视图规则验证边
        return CanvasConstants.EdgeRules.isEdgeAllowed(CanvasConstants.ViewKey.SCRIPT, sourceType, targetType);
    }

    @Override
    public String inferRelationType(String sourceType, String targetType) {
        return CanvasConstants.EdgeRules.inferRelationType(sourceType, targetType);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByEntity(String entityType, String entityId) {
        int count = edgeMapper.deleteByEntity(entityType, entityId);
        log.info("删除实体相关边: entityType={}, entityId={}, count={}", entityType, entityId, count);
    }

    private Map<String, Object> getDefaultLineStyle() {
        Map<String, Object> style = new HashMap<>();
        style.put("strokeColor", CanvasConstants.DefaultLineStyle.STROKE_COLOR);
        style.put("strokeWidth", CanvasConstants.DefaultLineStyle.STROKE_WIDTH);
        style.put("strokeStyle", CanvasConstants.DefaultLineStyle.STROKE_STYLE);
        style.put("animated", CanvasConstants.DefaultLineStyle.ANIMATED);
        return style;
    }

    private CanvasEdgeResponse toResponse(CanvasEdge edge) {
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
        // 通过 (canvasId, entityType, entityId) 反查 nodeId 填给前端 React Flow 渲染
        response.setSourceNodeId(lookupNodeId(edge.getCanvasId(), edge.getSourceType(), edge.getSourceId()));
        response.setTargetNodeId(lookupNodeId(edge.getCanvasId(), edge.getTargetType(), edge.getTargetId()));
        return response;
    }

    private String lookupNodeId(String canvasId, String entityType, String entityId) {
        if (canvasId == null || entityType == null || entityId == null) return null;
        try {
            CanvasNode node = nodeMapper.selectByCanvasAndEntity(canvasId, entityType, entityId);
            return node != null ? node.getId() : null;
        } catch (Exception e) {
            log.warn("反查节点ID失败: canvasId={}, entityType={}, entityId={}, error={}",
                    canvasId, entityType, entityId, e.getMessage());
            return null;
        }
    }

    /**
     * 复制边状态（用于事件发布时记录之前的状态）
     */
    private CanvasEdge copyEdgeState(CanvasEdge source) {
        CanvasEdge copy = new CanvasEdge();
        copy.setId(source.getId());
        copy.setWorkspaceId(source.getWorkspaceId());
        copy.setCanvasId(source.getCanvasId());
        copy.setSourceType(source.getSourceType());
        copy.setSourceId(source.getSourceId());
        copy.setSourceVersionId(source.getSourceVersionId());
        copy.setSourceHandle(source.getSourceHandle());
        copy.setTargetType(source.getTargetType());
        copy.setTargetId(source.getTargetId());
        copy.setTargetVersionId(source.getTargetVersionId());
        copy.setTargetHandle(source.getTargetHandle());
        copy.setRelationType(source.getRelationType());
        copy.setRelationLabel(source.getRelationLabel());
        copy.setDescription(source.getDescription());
        copy.setLineStyle(source.getLineStyle() != null ? new HashMap<>(source.getLineStyle()) : null);
        copy.setPathType(source.getPathType());
        copy.setSequence(source.getSequence());
        copy.setExtraInfo(source.getExtraInfo() != null ? new HashMap<>(source.getExtraInfo()) : null);
        return copy;
    }
}
