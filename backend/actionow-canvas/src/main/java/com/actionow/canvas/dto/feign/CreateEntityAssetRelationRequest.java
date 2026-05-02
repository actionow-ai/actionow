package com.actionow.canvas.dto.feign;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 创建实体-素材关联请求（Canvas 模块 Feign 调用使用）
 *
 * @author Actionow
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateEntityAssetRelationRequest {

    /**
     * 实体类型: SCRIPT, EPISODE, STORYBOARD, CHARACTER, SCENE, PROP, STYLE
     */
    private String entityType;

    /**
     * 实体ID
     */
    private String entityId;

    /**
     * 素材ID
     */
    private String assetId;

    /**
     * 关联类型: REFERENCE, OFFICIAL, DRAFT
     */
    private String relationType;

    /**
     * 描述
     */
    private String description;

    /**
     * 排序
     */
    private Integer sequence;

    /**
     * 扩展信息
     */
    private Map<String, Object> extraInfo;
}
