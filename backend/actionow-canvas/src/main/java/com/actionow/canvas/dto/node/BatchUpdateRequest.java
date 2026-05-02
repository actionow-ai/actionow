package com.actionow.canvas.dto.node;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class BatchUpdateRequest {
    private List<String> nodeIds;
    private BigDecimal deltaX;
    private BigDecimal deltaY;
    private Integer zIndex;
    private Boolean locked;
}
