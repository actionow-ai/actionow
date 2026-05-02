package com.actionow.canvas.dto.feign;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Canvas 更新实体响应
 * 更新业务实体后的返回信息
 *
 * @author Actionow
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CanvasEntityUpdateResponse {

    /**
     * 实体ID
     */
    private String entityId;

    /**
     * 实体类型
     */
    private String entityType;

    /**
     * 更新后的实体名称
     */
    private String name;

    /**
     * 更新后的缩略图URL
     */
    private String thumbnailUrl;

    /**
     * 是否更新成功
     */
    private boolean success;

    /**
     * 错误信息（更新失败时）
     */
    private String errorMessage;
}
