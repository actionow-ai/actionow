package com.actionow.canvas.conflict;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 冲突信息
 */
@Data
public class ConflictInfo {
    private String nodeId;
    private String userId;
    private String userName;
    private String operation;  // UPDATE / DELETE
    private LocalDateTime timestamp;
    private Integer expectedVersion;
    private Integer actualVersion;
}
