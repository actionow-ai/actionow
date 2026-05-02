package com.actionow.canvas.dto.feign;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Canvas 创建实体响应
 * 返回创建的实体基本信息
 *
 * @author Actionow
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CanvasEntityCreateResponse {

    /**
     * 实体ID
     */
    private String entityId;

    /**
     * 实体类型
     */
    private String entityType;

    /**
     * 实体名称
     */
    private String name;

    /**
     * 缩略图URL（如果有）
     */
    private String thumbnailUrl;
}
