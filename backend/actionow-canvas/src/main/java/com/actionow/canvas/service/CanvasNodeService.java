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
     * 通知所有引用了该实体的节点：实体已变更，需要让前端重新加载 entityDetail。
     *
     * 实现：
     *  - 找出所有 entity_type/entity_id 匹配的节点
     *  - 用 NodeEnrichmentService 拉最新 entityDetail（直接 project Feign，不读缓存）
     *  - 通过 webSocketHandler.broadcastToCanvas 推 NODE_UPDATED + entityDetail 给所有用户
     */
    void notifyEntityRefreshed(String entityType, String entityId);

    /**
     * notifyEntityRefreshed 的 payload 兜底版本：当传入 payload 时，
     * 一旦 enrich 失败（Feign 不可达 / 上游慢 / 异常），用 payload 直接构建 entityDetail 广播，
     * 避免 silent failure 让前端永久看不到新数据。
     */
    default void notifyEntityRefreshed(String entityType, String entityId,
                                        java.util.Map<String, Object> fallbackPayload) {
        notifyEntityRefreshed(entityType, entityId);
    }

    /**
     * 批量更新节点
     */
    void batchUpdate(BatchUpdateRequest request, String userId);

    /**
     * 批量删除节点
     */
    void batchDelete(List<String> nodeIds, String userId);

    /**
     * 用 sourceAsset 的文件信息替换节点关联 asset 的内容
     * 用于 AI 生成完成后的回填：节点 entityId 不变，仅 fileUrl/coverUrl 等更新
     *
     * @param nodeId        目标节点ID（必须是 ASSET 类型）
     * @param sourceAssetId 提供内容的源 asset ID
     * @param userId        操作用户ID
     * @return 更新后的节点
     */
    CanvasNodeResponse replaceAssetContent(String nodeId, String sourceAssetId, String userId);
}
