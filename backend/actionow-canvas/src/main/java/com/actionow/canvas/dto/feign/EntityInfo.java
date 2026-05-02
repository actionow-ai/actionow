package com.actionow.canvas.dto.feign;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 实体信息 DTO
 * 用于 Feign 调用返回的通用实体信息
 *
 * @author Actionow
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityInfo {

    /**
     * 实体ID
     */
    private String id;

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
     * 获取缩略图URL（兼容字段名）
     * @return coverUrl 的值
     */
    public String getThumbnailUrl() {
        return coverUrl;
    }

    /**
     * 设置缩略图URL（兼容字段名）
     * @param thumbnailUrl 缩略图URL
     */
    public void setThumbnailUrl(String thumbnailUrl) {
        this.coverUrl = thumbnailUrl;
    }

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

    /**
     * 实体详情 - 包含实体类型特有的字段
     */
    private Map<String, Object> detail;

    /**
     * 转换为 CanvasNodeData
     */
    public CanvasNodeData toCanvasNodeData(String fallbackEntityType) {
        return CanvasNodeData.builder()
                .entityId(this.id)
                .entityType(this.entityType != null ? this.entityType : fallbackEntityType)
                .name(this.name)
                .description(this.description)
                .coverUrl(this.coverUrl)
                .version(this.version)
                .status(this.status)
                .updatedAt(this.updatedAt)
                .build();
    }

    /**
     * 转换为完整的实体详情 Map
     * 包含通用字段和实体特有字段
     */
    public Map<String, Object> toEntityDetailMap() {
        Map<String, Object> result = new HashMap<>();
        // 通用字段
        result.put("id", this.id);
        result.put("entityType", this.entityType);
        result.put("name", this.name);
        result.put("description", this.description);
        result.put("coverUrl", this.coverUrl);
        result.put("version", this.version);
        result.put("status", this.status);
        result.put("updatedAt", this.updatedAt);
        // 合并实体特有字段
        if (this.detail != null) {
            result.putAll(this.detail);
        }
        return result;
    }
}
