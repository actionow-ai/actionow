package com.actionow.canvas.dto.feign;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Canvas 创建实体请求
 * 当在 Canvas 中创建节点时，需要同步创建对应的业务实体
 *
 * @author Actionow
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CanvasEntityCreateRequest {

    /**
     * 实体类型
     * CHARACTER, SCENE, PROP, STYLE, EPISODE, STORYBOARD, SCRIPT, ASSET
     */
    private String entityType;

    /**
     * 实体名称
     */
    private String name;

    /**
     * 实体描述
     */
    private String description;

    /**
     * 作用域（用于 CHARACTER, SCENE, PROP, STYLE）
     * WORKSPACE / SCRIPT
     */
    private String scope;

    /**
     * 关联的剧本ID（scope为SCRIPT时必填）
     */
    private String scriptId;

    /**
     * 关联的剧集ID（用于 STORYBOARD）
     */
    private String episodeId;

    /**
     * 工作空间ID
     */
    private String workspaceId;

    /**
     * Canvas 节点位置 X
     */
    private BigDecimal positionX;

    /**
     * Canvas 节点位置 Y
     */
    private BigDecimal positionY;

    /**
     * Canvas 节点宽度
     */
    private BigDecimal width;

    /**
     * Canvas 节点高度
     */
    private BigDecimal height;

    /**
     * Canvas 节点样式
     */
    private Map<String, Object> style;

    /**
     * 扩展数据（特定实体类型的额外字段）
     */
    private Map<String, Object> extraData;
}
