package com.actionow.canvas.dto.feign;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Canvas 更新实体请求
 * 当在 Canvas 中更新节点时，需要同步更新对应的业务实体
 *
 * @author Actionow
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CanvasEntityUpdateRequest {

    /**
     * 实体类型
     * CHARACTER, SCENE, PROP, STYLE, EPISODE, STORYBOARD, SCRIPT, ASSET
     */
    private String entityType;

    /**
     * 实体ID
     */
    private String entityId;

    /**
     * 实体名称（可选更新）
     */
    private String name;

    /**
     * 实体描述（可选更新）
     */
    private String description;

    /**
     * 实体缩略图URL（可选更新）
     */
    private String thumbnailUrl;

    /**
     * 工作空间ID
     */
    private String workspaceId;

    /**
     * 扩展数据（特定实体类型的额外字段）
     */
    private Map<String, Object> extraData;
}
