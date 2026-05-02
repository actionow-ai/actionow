package com.actionow.canvas.dto.node;

import com.actionow.canvas.dto.edge.CanvasEdgeResponse;
import com.actionow.canvas.entity.CanvasNode;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 画布节点响应
 *
 * @author Actionow
 */
@Data
public class CanvasNodeResponse {

    private String id;
    private String workspaceId;

    // 画布信息
    private String canvasId;

    // 实体引用
    private String entityType;
    private String entityId;

    // 层级信息
    private String layer;
    private String parentNodeId;

    // 位置和尺寸
    private BigDecimal positionX;
    private BigDecimal positionY;
    private BigDecimal width;
    private BigDecimal height;

    // 状态
    private Boolean collapsed;
    private Boolean locked;
    private Integer zIndex;

    // 样式
    private Map<String, Object> style;

    /**
     * 实体详情 - 包含完整的实体信息
     * 通用字段: id, entityType, name, description, coverUrl, version, status, updatedAt
     * 根据 entityType 不同，还包含实体特有的字段（存储在 detail 中）
     */
    private Map<String, Object> entityDetail;

    /**
     * 同时创建的边（仅在请求中包含边信息时返回）
     */
    private CanvasEdgeResponse createdEdge;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static CanvasNodeResponse fromEntity(CanvasNode node) {
        CanvasNodeResponse response = new CanvasNodeResponse();
        response.setId(node.getId());
        response.setWorkspaceId(node.getWorkspaceId());
        response.setCanvasId(node.getCanvasId());
        response.setEntityType(node.getEntityType());
        response.setEntityId(node.getEntityId());
        response.setLayer(node.getLayer());
        response.setParentNodeId(node.getParentNodeId());
        response.setPositionX(node.getPositionX());
        response.setPositionY(node.getPositionY());
        response.setWidth(node.getWidth());
        response.setHeight(node.getHeight());
        response.setCollapsed(node.getCollapsed());
        response.setLocked(node.getLocked());
        response.setZIndex(node.getZIndex());
        response.setStyle(node.getStyle());
        response.setCreatedAt(node.getCreatedAt());
        response.setUpdatedAt(node.getUpdatedAt());
        return response;
    }
}
