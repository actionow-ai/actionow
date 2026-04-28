package com.actionow.ai.dto;

import com.actionow.ai.entity.ProviderWorkspaceWhitelist;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * provider 白名单条目响应
 *
 * @author Actionow
 */
@Data
@Builder
public class ProviderWhitelistResponse {

    private String id;
    private String providerId;
    private String workspaceId;
    private String note;
    private LocalDateTime createdAt;

    public static ProviderWhitelistResponse fromEntity(ProviderWorkspaceWhitelist entity) {
        return ProviderWhitelistResponse.builder()
                .id(entity.getId())
                .providerId(entity.getProviderId())
                .workspaceId(entity.getWorkspaceId())
                .note(entity.getNote())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
