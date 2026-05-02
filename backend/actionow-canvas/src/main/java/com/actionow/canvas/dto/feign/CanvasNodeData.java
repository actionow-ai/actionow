package com.actionow.canvas.dto.feign;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Canvas Node Data DTO
 * 存储画布节点关联的实体数据（从 EntityInfo 转换而来）
 *
 * @author Actionow
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CanvasNodeData {

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
     * 实体描述
     */
    private String description;

    /**
     * 封面/缩略图URL
     */
    private String coverUrl;

    /**
     * 版本号
     */
    private Integer version;

    /**
     * 状态
     */
    private String status;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
