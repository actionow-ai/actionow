package com.actionow.canvas.dto.node;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 创建分组请求
 */
@Data
public class CreateGroupRequest {
    @NotBlank
    private String canvasId;

    private String title;
    private List<String> nodeIds;  // 要加入分组的节点ID
    private BigDecimal positionX;
    private BigDecimal positionY;
}
