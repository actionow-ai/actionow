package com.actionow.ai.service;

import com.actionow.ai.entity.ProviderWorkspaceWhitelist;

import java.util.List;

/**
 * Provider workspace 白名单服务
 *
 * @author Actionow
 */
public interface ProviderWhitelistService {

    /**
     * 添加 workspace 到 provider 白名单。
     * 若已存在(未软删)，幂等返回现有记录；若存在但已软删，恢复(deleted=0)并更新 note。
     */
    ProviderWorkspaceWhitelist add(String providerId, String workspaceId, String note);

    /**
     * 从白名单移除(软删除)。
     */
    void remove(String providerId, String workspaceId);

    /**
     * 列出 provider 当前生效的白名单条目。
     */
    List<ProviderWorkspaceWhitelist> listByProvider(String providerId);

    /**
     * 单条检查 workspace 是否在该 provider 白名单内。
     */
    boolean isWhitelisted(String providerId, String workspaceId);
}
