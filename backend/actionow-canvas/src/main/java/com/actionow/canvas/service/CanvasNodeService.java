package com.actionow.canvas.service;

import com.actionow.canvas.dto.node.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 画布节点服务接口
 *
 * @author Actionow
 */
public interface CanvasNodeService {

    /**
     * 视口查询（性能优化）
     */
    List<CanvasNodeResponse> getNodesByViewport(String canvasId, ViewportQueryRequest request);

    /**
     * 创建分组
     */
    CanvasNodeResponse createGroup(CreateGroupRequest request, String workspaceId, String userId);

    /**
     * 整组移动
     */
    void moveGroup(String groupId, BigDecimal deltaX, BigDecimal deltaY);

    /**
     * 创建节点
     */
    CanvasNodeResponse createNode(CreateNodeRequest request, String workspaceId, String userId);

    /**
     * 批量创建节点
     */
    List<CanvasNodeResponse> batchCreateNodes(List<CreateNodeRequest> requests, String workspaceId, String userId);

    /**
     * 更新节点
     */
    CanvasNodeResponse updateNode(String nodeId, UpdateNodeRequest request, String userId);

    /**
     * 更新节点并同步实体信息到 Project 服务
     */
    CanvasNodeResponse updateNodeWithEntity(String nodeId, UpdateNodeWithEntityRequest request,
                                            String workspaceId, String userId);

    /**
     * 批量更新节点并同步实体信息到 Project 服务
     */
    List<CanvasNodeResponse> batchUpdateNodesWithEntity(List<UpdateNodeWithEntityRequest> requests,
                                                         String workspaceId, String userId);

    /**
     * 删除节点
     */
    void deleteNode(String nodeId, String userId);

    /**
     * 删除节点，可选同步删除 Project 中的实体
     *
     * @param nodeId        节点ID
     * @param userId        用户ID
     * @param syncToProject 是否同步删除 Project 中的实体
     */
    void deleteNode(String nodeId, String userId, boolean syncToProject);

    /**
     * 获取节点详情
     */
    CanvasNodeResponse getById(String nodeId);

    /**
     * 获取画布中的所有节点
     */
    List<CanvasNodeResponse> listByCanvasId(String canvasId);

    /**
     * 获取实体在所有画布中的节点
     */
    List<CanvasNodeResponse> listByEntity(String entityType, String entityId);

    /**
     * 批量更新节点位置
     */
    void batchUpdatePositions(List<UpdateNodeRequest> updates, String userId);

    /**
     * 删除画布中的所有节点
     */
    void deleteByCanvasId(String canvasId);

    /**
     * 删除所有包含该实体的节点
     */
    void deleteByEntity(String entityType, String entityId);

    /**
     * 验证节点类型在画布维度是否允许
     */
    boolean validateNodeType(String canvasId, String entityType);

    /**
     * 更新节点缓存信息
     */
    void updateCachedInfo(String entityType, String entityId, String name, String thumbnailUrl);

    /**
     * 批量更新节点
     */
    void batchUpdate(BatchUpdateRequest request, String userId);

    /**
     * 批量删除节点
     */
    void batchDelete(List<String> nodeIds, String userId);
}
