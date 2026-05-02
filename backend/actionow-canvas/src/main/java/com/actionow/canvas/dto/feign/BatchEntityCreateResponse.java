package com.actionow.canvas.dto.feign;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量创建实体响应
 *
 * @author Actionow
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchEntityCreateResponse {

    /**
     * 创建结果列表
     */
    private List<EntityCreateResult> results;

    /**
     * 成功数量
     */
    private Integer successCount;

    /**
     * 失败数量
     */
    private Integer failedCount;

    /**
     * 总数量
     */
    private Integer totalCount;

    /**
     * 单个实体创建结果
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntityCreateResult {
        /**
         * 请求索引（对应请求列表中的位置）
         */
        private Integer index;

        /**
         * 是否成功
         */
        private Boolean success;

        /**
         * 实体ID（成功时返回）
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
         * 缩略图URL
         */
        private String thumbnailUrl;

        /**
         * 错误消息（失败时返回）
         */
        private String errorMessage;
    }
}
