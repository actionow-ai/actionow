package com.actionow.canvas.dto.feign;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 实体引用对（entityType + entityId），Canvas 模块用作批量 Feign 入参。
 *
 * @author Actionow
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EntityRef {

    private String entityType;
    private String entityId;
}
