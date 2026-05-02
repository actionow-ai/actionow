package com.actionow.project.dto.relation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 实体引用对（entityType + entityId），用于批量接口入参。
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
