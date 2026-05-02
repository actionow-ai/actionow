package com.actionow.canvas.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.actionow.common.data.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 画布节点实体
 * 存储画布中实体节点的位置和状态信息
 * 实体数据通过 entityType + entityId 引用 actionow-project 中的实际数据
 *
 * @author Actionow
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "t_canvas_node", autoResultMap = true)
public class CanvasNode extends TenantBaseEntity {

    /**
     * 所属画布ID
     */
    @TableField("canvas_id")
    private String canvasId;

    /**
     * 节点类型: ENTITY(业务实体) / STICKY_NOTE(便签) / IFRAME(嵌入) / SHAPE(形状) / GROUP(分组)
     */
    @TableField("node_type")
    private String nodeType;

    /**
     * 实体类型: SCRIPT, EPISODE, STORYBOARD, CHARACTER, SCENE, PROP, STYLE, ASSET
     * 仅当 nodeType = ENTITY 时有效
     */
    @TableField("entity_type")
    private String entityType;

    /**
     * 实体ID
     * 仅当 nodeType = ENTITY 时有效
     */
    @TableField("entity_id")
    private String entityId;

    /**
     * freeform 节点内容（JSON）
     * 仅当 nodeType != ENTITY 时有效
     * STICKY_NOTE: {text, color, fontSize}
     * IFRAME: {url, title}
     * SHAPE: {shape, fill, stroke, strokeWidth}
     * GROUP: {title, collapsed}
     */
    @TableField(value = "content", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> content;

    /**
     * 节点层级: SCRIPT, EPISODE, STORYBOARD, CHARACTER, SCENE, PROP, ASSET
     * 用于视图过滤和层级展示
     */
    private String layer;

    /**
     * 父节点ID，用于层级关系
     */
    @TableField("parent_node_id")
    private String parentNodeId;

    /**
     * X 坐标位置
     */
    @TableField("position_x")
    private BigDecimal positionX;

    /**
     * Y 坐标位置
     */
    @TableField("position_y")
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
     * 层级顺序（z-index）
     */
    @TableField("z_index")
    private Integer zIndex;

    /**
     * 节点样式 (JSON)
     * 格式: { "backgroundColor": "#fff", "borderColor": "#ddd", "borderRadius": 8 }
     */
    @TableField(value = "style", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> style;
}
