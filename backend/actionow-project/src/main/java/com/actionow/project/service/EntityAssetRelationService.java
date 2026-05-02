package com.actionow.project.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.actionow.project.dto.relation.*;

import java.util.List;

/**
 * 实体-素材关联服务接口
 *
 * @author Actionow
 */
public interface EntityAssetRelationService {

    // ==================== 关联管理 ====================

    /**
     * 创建实体-素材关联
     */
    EntityAssetRelationResponse createRelation(CreateEntityAssetRelationRequest request, String workspaceId, String userId);

    /**
     * 创建实体-素材关联（可控制是否跳过 Canvas 同步）
     * 用于 Canvas 发起的创建，避免循环调用
     *
     * @param request         创建请求
     * @param workspaceId     工作空间ID
     * @param userId          用户ID
     * @param skipCanvasSync  是否跳过 Canvas 同步
     * @return 关联响应
     */
    EntityAssetRelationResponse createRelation(CreateEntityAssetRelationRequest request, String workspaceId, String userId, boolean skipCanvasSync);

    /**
     * 批量创建实体-素材关联
     */
    List<EntityAssetRelationResponse> batchCreateRelations(BatchCreateEntityAssetRelationRequest request, String workspaceId, String userId);

    /**
     * 删除关联（按ID）
     */
    void deleteRelation(String relationId, String userId);

    /**
     * 删除指定的实体-素材关联
     */
    void deleteRelation(String entityType, String entityId, String assetId, String relationType, String userId);

    /**
     * 更新关联类型
     *
     * @param relationId   关联ID
     * @param relationType 新的关联类型
     * @param userId       操作用户ID
     * @return 更新后的关联响应
     */
    EntityAssetRelationResponse updateRelationType(String relationId, String relationType, String userId);

    // ==================== 查询接口 ====================

    /**
     * 分页查询实体关联的素材
     */
    Page<EntityAssetRelationResponse> queryEntityAssets(EntityAssetQueryRequest request, String workspaceId);

    /**
     * 查询实体关联的素材列表（不分页）
     */
    List<EntityAssetRelationResponse> listEntityAssets(String entityType, String entityId, String workspaceId);

    /**
     * 批量查询多个实体的关联素材
     * 单次 SQL 替代 N 次单条查询，避免 N+1 问题（Canvas 模块画布 enrich 用）
     *
     * @param refs        实体引用列表 [{entityType, entityId}, ...]
     * @param workspaceId 工作空间ID（用于权限边界）
     * @return key 格式 "{entityType}:{entityId}" → 关联素材列表
     */
    java.util.Map<String, List<EntityAssetRelationResponse>> batchListEntityAssets(
            List<com.actionow.project.dto.relation.EntityRef> refs, String workspaceId);

    /**
     * 根据关联类型查询
     */
    List<EntityAssetRelationResponse> listByRelationType(String entityType, String entityId, String relationType, String workspaceId);

    /**
     * 查询素材被哪些实体关联
     */
    List<EntityAssetRelationResponse> listAssetRelations(String assetId, String workspaceId);

    // ==================== 统计接口 ====================

    /**
     * 获取实体的素材关联汇总
     */
    EntityAssetSummaryResponse getEntityAssetSummary(String entityType, String entityId, String workspaceId);

    // ==================== 便捷方法 ====================

    /**
     * 检查关联是否存在
     */
    boolean existsRelation(String entityType, String entityId, String assetId, String relationType);

    /**
     * 设置素材为正式素材（将其他同类型素材设为草稿）
     */
    void setAsOfficial(String entityType, String entityId, String assetId, String userId);

    // ==================== 挂载/解挂 ====================

    /**
     * 挂载素材到实体
     */
    EntityAssetRelationResponse mountAsset(MountAssetRequest request, String workspaceId, String userId);

    /**
     * 从实体解挂素材
     */
    void unmountAsset(UnmountAssetRequest request, String userId);
}
