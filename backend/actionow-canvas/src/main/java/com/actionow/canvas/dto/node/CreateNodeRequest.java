package com.actionow.canvas.dto.node;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 创建画布节点请求
 * 支持两种模式：
 * 1. 已有实体模式：提供 entityId，直接创建节点引用
 * 2. 新建实体模式：不提供 entityId，提供 entityName，会先创建实体再创建节点
 *
 * @author Actionow
 */
@Data
public class CreateNodeRequest {

    /**
     * 画布ID
     */
    @NotBlank(message = "画布ID不能为空")
    private String canvasId;

    /**
     * 实体类型
     */
    @NotBlank(message = "实体类型不能为空")
    private String entityType;

    /**
     * 实体ID（已有实体模式必填，新建实体模式不填）
     */
    private String entityId;

    // ==================== 新建实体字段 ====================

    /**
     * 实体名称（新建实体模式必填）
     */
    private String entityName;

    /**
     * 实体描述
     */
    private String entityDescription;

    /**
     * 实体作用域（用于 CHARACTER, SCENE, PROP, STYLE）
     * WORKSPACE / SCRIPT
     */
    private String entityScope;

    /**
     * 关联的剧本ID（SCRIPT作用域时必填；EPISODE/STORYBOARD创建时需要）
     */
    private String scriptId;

    /**
     * 关联的剧集ID（STORYBOARD创建时需要）
     */
    private String episodeId;

    /**
     * 实体扩展数据（特定实体类型的额外字段）
     */
    private Map<String, Object> entityExtraData;

    /**
     * ASSET 子类型：IMAGE / VIDEO / AUDIO / TEXT
     * 仅 entityType=ASSET 且新建实体模式时使用，会通过 extraData.assetType 传给 Project
     */
    private String mediaType;

    /**
     * 节点类型: ENTITY / STICKY_NOTE / IFRAME / SHAPE / GROUP
     */
    private String nodeType;

    /**
     * freeform 节点内容（仅 nodeType!=ENTITY 时使用）
     */
    private Map<String, Object> content;

    /**
     * 节点层级（可选，不填则根据 entityType 自动推断）
     * SCRIPT, EPISODE, STORYBOARD, CHARACTER, SCENE, PROP, ASSET
     */
    private String layer;

    /**
     * 父节点ID（可选，用于层级关系）
     */
    private String parentNodeId;

    /**
     * X 坐标位置
     */
    @NotNull(message = "X坐标不能为空")
    private BigDecimal positionX;

    /**
     * Y 坐标位置
     */
    @NotNull(message = "Y坐标不能为空")
    private BigDecimal positionY;

    /**
     * 节点宽度
     */
    private BigDecimal width;

    /**
     * 节点高度
     */
    private BigDecimal height;

    /**
     * 是否折叠
     */
    private Boolean collapsed;

    /**
     * 是否锁定
     */
    private Boolean locked;

    /**
     * 层级顺序
     */
    private Integer zIndex;

    /**
     * 节点样式
     */
    private Map<String, Object> style;

    // ==================== 边关联字段（可选）====================
    // 如果提供以下字段，则在创建节点后自动创建从源节点到新节点的边

    /**
     * 源节点实体类型（用于创建边）
     */
    private String sourceNodeType;

    /**
     * 源节点实体ID（用于创建边）
     */
    private String sourceNodeId;

    /**
     * 源节点连接点位置
     */
    private String sourceHandle;

    /**
     * 目标节点（新节点）连接点位置
     */
    private String targetHandle;

    /**
     * 关系类型（可选，不传则自动推断）
     */
    private String relationType;

    /**
     * 关系标签
     */
    private String relationLabel;

    /**
     * 边的线条样式
     */
    private Map<String, Object> edgeLineStyle;

    /**
     * 是否有边信息（用于判断是否需要创建边）
     */
    public boolean hasEdgeInfo() {
        return sourceNodeType != null && !sourceNodeType.isBlank()
                && sourceNodeId != null && !sourceNodeId.isBlank();
    }
}
