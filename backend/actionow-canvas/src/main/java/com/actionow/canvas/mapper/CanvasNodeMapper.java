package com.actionow.canvas.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.actionow.canvas.entity.CanvasNode;
import com.actionow.common.core.constant.CommonConstants;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 画布节点 Mapper
 *
 * @author Actionow
 */
@Mapper
public interface CanvasNodeMapper extends BaseMapper<CanvasNode> {

    /**
     * 根据画布ID查询所有节点
     */
    default List<CanvasNode> selectByCanvasId(String canvasId) {
        return selectList(new LambdaQueryWrapper<CanvasNode>()
                .eq(CanvasNode::getCanvasId, canvasId)
                .eq(CanvasNode::getDeleted, CommonConstants.NOT_DELETED)
                .orderByAsc(CanvasNode::getZIndex));
    }

    /**
     * 根据画布ID和层级列表查询节点
     */
    default List<CanvasNode> selectByCanvasIdAndLayers(String canvasId, List<String> layers) {
        return selectList(new LambdaQueryWrapper<CanvasNode>()
                .eq(CanvasNode::getCanvasId, canvasId)
                .in(CanvasNode::getLayer, layers)
                .eq(CanvasNode::getDeleted, CommonConstants.NOT_DELETED)
                .orderByAsc(CanvasNode::getZIndex));
    }

    /**
     * 根据画布ID和实体类型列表查询节点
     */
    default List<CanvasNode> selectByCanvasIdAndEntityTypes(String canvasId, List<String> entityTypes) {
        if (entityTypes == null || entityTypes.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapper<CanvasNode>()
                .eq(CanvasNode::getCanvasId, canvasId)
                .in(CanvasNode::getEntityType, entityTypes)
                .eq(CanvasNode::getDeleted, CommonConstants.NOT_DELETED)
                .orderByAsc(CanvasNode::getZIndex));
    }

    /**
     * 根据画布ID和实体类型列表查询可见节点
     */
    default List<CanvasNode> selectVisibleByCanvasIdAndEntityTypes(String canvasId, List<String> entityTypes) {
        if (entityTypes == null || entityTypes.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapper<CanvasNode>()
                .eq(CanvasNode::getCanvasId, canvasId)
                .in(CanvasNode::getEntityType, entityTypes)
                .eq(CanvasNode::getDeleted, CommonConstants.NOT_DELETED)
                .orderByAsc(CanvasNode::getZIndex));
    }

    /**
     * 根据父节点ID查询子节点
     */
    default List<CanvasNode> selectByParentNodeId(String parentNodeId) {
        return selectList(new LambdaQueryWrapper<CanvasNode>()
                .eq(CanvasNode::getParentNodeId, parentNodeId)
                .eq(CanvasNode::getDeleted, CommonConstants.NOT_DELETED)
                .orderByAsc(CanvasNode::getZIndex));
    }

    /**
     * 根据实体查询所有画布中的节点
     */
    default List<CanvasNode> selectByEntity(String entityType, String entityId) {
        return selectList(new LambdaQueryWrapper<CanvasNode>()
                .eq(CanvasNode::getEntityType, entityType)
                .eq(CanvasNode::getEntityId, entityId)
                .eq(CanvasNode::getDeleted, CommonConstants.NOT_DELETED));
    }

    /**
     * 查询画布中特定实体的节点
     */
    default CanvasNode selectByCanvasAndEntity(String canvasId, String entityType, String entityId) {
        return selectOne(new LambdaQueryWrapper<CanvasNode>()
                .eq(CanvasNode::getCanvasId, canvasId)
                .eq(CanvasNode::getEntityType, entityType)
                .eq(CanvasNode::getEntityId, entityId)
                .eq(CanvasNode::getDeleted, CommonConstants.NOT_DELETED));
    }

    /**
     * 根据实体类型查询画布中的节点
     */
    default List<CanvasNode> selectByCanvasAndEntityType(String canvasId, String entityType) {
        return selectList(new LambdaQueryWrapper<CanvasNode>()
                .eq(CanvasNode::getCanvasId, canvasId)
                .eq(CanvasNode::getEntityType, entityType)
                .eq(CanvasNode::getDeleted, CommonConstants.NOT_DELETED)
                .orderByAsc(CanvasNode::getZIndex));
    }

    /**
     * 统计画布中特定层级的节点数量
     */
    default long countByCanvasIdAndLayer(String canvasId, String layer) {
        return selectCount(new LambdaQueryWrapper<CanvasNode>()
                .eq(CanvasNode::getCanvasId, canvasId)
                .eq(CanvasNode::getLayer, layer)
                .eq(CanvasNode::getDeleted, CommonConstants.NOT_DELETED));
    }

    /**
     * 删除画布下的所有节点
     */
    default int deleteByCanvasId(String canvasId) {
        return delete(new LambdaQueryWrapper<CanvasNode>()
                .eq(CanvasNode::getCanvasId, canvasId));
    }

    /**
     * 删除与实体相关的所有节点
     */
    default int deleteByEntity(String entityType, String entityId) {
        return delete(new LambdaQueryWrapper<CanvasNode>()
                .eq(CanvasNode::getEntityType, entityType)
                .eq(CanvasNode::getEntityId, entityId));
    }

    /**
     * 获取画布中的最大z-index
     */
    default Integer selectMaxZIndex(String canvasId) {
        CanvasNode node = selectOne(new LambdaQueryWrapper<CanvasNode>()
                .eq(CanvasNode::getCanvasId, canvasId)
                .eq(CanvasNode::getDeleted, CommonConstants.NOT_DELETED)
                .orderByDesc(CanvasNode::getZIndex)
                .last("LIMIT 1"));
        return node != null ? node.getZIndex() : 0;
    }

    /**
     * 统计画布中的节点数量
     */
    default long countByCanvasId(String canvasId) {
        return selectCount(new LambdaQueryWrapper<CanvasNode>()
                .eq(CanvasNode::getCanvasId, canvasId)
                .eq(CanvasNode::getDeleted, CommonConstants.NOT_DELETED));
    }

    /**
     * 批量查询多个实体ID的节点
     */
    default List<CanvasNode> selectByEntityIds(String canvasId, String entityType, List<String> entityIds) {
        return selectList(new LambdaQueryWrapper<CanvasNode>()
                .eq(CanvasNode::getCanvasId, canvasId)
                .eq(CanvasNode::getEntityType, entityType)
                .in(CanvasNode::getEntityId, entityIds)
                .eq(CanvasNode::getDeleted, CommonConstants.NOT_DELETED));
    }

    /**
     * 视口空间查询（性能优化）
     * 查询指定视口范围内的节点
     */
    default List<CanvasNode> selectByViewport(String canvasId,
                                               java.math.BigDecimal minX, java.math.BigDecimal minY,
                                               java.math.BigDecimal maxX, java.math.BigDecimal maxY,
                                               Integer limit) {
        LambdaQueryWrapper<CanvasNode> wrapper = new LambdaQueryWrapper<CanvasNode>()
                .eq(CanvasNode::getCanvasId, canvasId)
                .ge(CanvasNode::getPositionX, minX)
                .le(CanvasNode::getPositionX, maxX)
                .ge(CanvasNode::getPositionY, minY)
                .le(CanvasNode::getPositionY, maxY)
                .eq(CanvasNode::getDeleted, CommonConstants.NOT_DELETED)
                .orderByAsc(CanvasNode::getZIndex);

        if (limit != null && limit > 0) {
            wrapper.last("LIMIT " + limit);
        }

        return selectList(wrapper);
    }
}
