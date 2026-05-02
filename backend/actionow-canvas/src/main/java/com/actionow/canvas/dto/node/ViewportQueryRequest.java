package com.actionow.canvas.dto.node;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 视口查询请求
 */
@Data
public class ViewportQueryRequest {
    private BigDecimal minX;
    private BigDecimal minY;
    private BigDecimal maxX;
    private BigDecimal maxY;
    private Integer limit = 1000;  // 默认最多返回 1000 个节点
}
