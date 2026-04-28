package com.actionow.workspace.dto;

import com.actionow.workspace.entity.Workspace;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统管理员视角的 workspace 响应
 *
 * @author Actionow
 */
@Data
@Builder
public class WorkspaceAdminResponse {

    private String id;
    private String name;
    private String slug;
    private String ownerId;
    private String status;
    private String planType;
    private Boolean isInternal;
    private Integer memberCount;
    private LocalDateTime createdAt;

    public static WorkspaceAdminResponse fromEntity(Workspace w) {
        return WorkspaceAdminResponse.builder()
                .id(w.getId())
                .name(w.getName())
                .slug(w.getSlug())
                .ownerId(w.getOwnerId())
                .status(w.getStatus())
                .planType(w.getPlanType())
                .isInternal(Boolean.TRUE.equals(w.getIsInternal()))
                .memberCount(w.getMemberCount())
                .createdAt(w.getCreatedAt())
                .build();
    }
}
