package com.actionow.canvas.history;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 画布操作记录
 */
@Data
public class Operation {
    private String id;
    private String canvasId;
    private String userId;
    private OperationType type;
    private String targetId;  // 节点/边 ID
    private Map<String, Object> beforeState;  // 操作前状态
    private Map<String, Object> afterState;   // 操作后状态
    private LocalDateTime timestamp;

    public enum OperationType {
        CREATE_NODE,
        UPDATE_NODE,
        DELETE_NODE,
        CREATE_EDGE,
        UPDATE_EDGE,
        DELETE_EDGE,
        BATCH_UPDATE
    }
}
