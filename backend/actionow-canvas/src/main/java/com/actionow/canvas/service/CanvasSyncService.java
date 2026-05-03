package com.actionow.canvas.service;

/**
 * Canvas 同步服务接口
 * 处理实体变更事件，同步节点和边的状态
 * 统一主画布模型：1 Script = 1 Canvas
 *
 * @author Actionow
 */
public interface CanvasSyncService {

    /**
     * 处理实体创建事件
     * 自动在剧本对应的画布上创建节点
     *
     * @param entityType       实体类型
     * @param entityId         实体ID
     * @param scriptId         剧本ID（画布标识）
     * @param parentEntityType 父实体类型（可选，用于创建层级边）
     * @param parentEntityId   父实体ID（可选）
     * @param workspaceId      工作空间ID
     * @param name             实体名称
     */
    void handleEntityCreated(String entityType, String entityId, String scriptId,
                             String parentEntityType, String parentEntityId,
                             String workspaceId, String name);

    /**
     * 处理实体更新事件
     * 更新所有包含该实体的节点的缓存信息
     *
     * @param entityType   实体类型
     * @param entityId     实体ID
     * @param name         实体名称
     * @param thumbnailUrl 缩略图URL
     */
    void handleEntityUpdated(String entityType, String entityId, String name, String thumbnailUrl);

    /**
     * 处理实体更新事件（携带完整 payload 版本）。
     * payload 是 MQ event.data 的全量字段（fileUrl/fileKey/mimeType/generationStatus 等）。
     * 收到 payload 后实现可直接覆盖 Redis 缓存（无需 evict + 回源）+ WS 推送给前端。
     */
    default void handleEntityUpdated(String entityType, String entityId, String name,
                                     String thumbnailUrl, java.util.Map<String, Object> payload) {
        // 默认实现：忽略 payload，回退到旧路径（保持向后兼容）
        handleEntityUpdated(entityType, entityId, name, thumbnailUrl);
    }

    /**
     * 处理实体删除事件
     * 删除所有包含该实体的节点和边
     *
     * @param entityType 实体类型
     * @param entityId   实体ID
     */
    void handleEntityDeleted(String entityType, String entityId);
}
