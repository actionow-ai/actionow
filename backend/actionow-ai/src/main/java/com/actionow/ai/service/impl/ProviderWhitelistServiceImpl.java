package com.actionow.ai.service.impl;

import com.actionow.ai.entity.ModelProvider;
import com.actionow.ai.entity.ProviderWorkspaceWhitelist;
import com.actionow.ai.mapper.ProviderWorkspaceWhitelistMapper;
import com.actionow.ai.service.ModelProviderService;
import com.actionow.ai.service.ProviderWhitelistService;
import com.actionow.common.core.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Provider workspace 白名单服务实现
 *
 * @author Actionow
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderWhitelistServiceImpl implements ProviderWhitelistService {

    private final ProviderWorkspaceWhitelistMapper whitelistMapper;
    private final ModelProviderService modelProviderService;

    @Override
    @Transactional
    public ProviderWorkspaceWhitelist add(String providerId, String workspaceId, String note) {
        if (providerId == null || providerId.isBlank()) {
            throw new BusinessException("providerId 不能为空");
        }
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new BusinessException("workspaceId 不能为空");
        }

        // 校验 provider 存在
        ModelProvider provider = modelProviderService.findById(providerId)
                .orElseThrow(() -> new BusinessException("provider 不存在: " + providerId));

        // 即使 provider.visibility != WHITELIST 也允许写入（管理员可以先加白名单再切 visibility）
        // 直接幂等写：先查未软删记录
        ProviderWorkspaceWhitelist existing = whitelistMapper.findByProviderAndWorkspace(providerId, workspaceId);
        if (existing != null) {
            existing.setNote(note);
            whitelistMapper.updateById(existing);
            return existing;
        }

        // 再查软删记录（数据库唯一索引按 (provider_id, workspace_id) 不带 deleted 条件）
        ProviderWorkspaceWhitelist softDeleted = whitelistMapper.selectOne(
                new LambdaQueryWrapper<ProviderWorkspaceWhitelist>()
                        .eq(ProviderWorkspaceWhitelist::getProviderId, providerId)
                        .eq(ProviderWorkspaceWhitelist::getWorkspaceId, workspaceId)
                        .last("LIMIT 1")
        );
        if (softDeleted != null) {
            // 恢复
            softDeleted.setDeleted(0);
            softDeleted.setNote(note);
            whitelistMapper.updateById(softDeleted);
            log.info("Restored provider whitelist: providerId={}, workspaceId={}, name={}",
                    providerId, workspaceId, provider.getName());
            return softDeleted;
        }

        ProviderWorkspaceWhitelist entry = new ProviderWorkspaceWhitelist();
        entry.setProviderId(providerId);
        entry.setWorkspaceId(workspaceId);
        entry.setNote(note);
        whitelistMapper.insert(entry);
        log.info("Added provider whitelist: providerId={}, workspaceId={}, name={}",
                providerId, workspaceId, provider.getName());
        return entry;
    }

    @Override
    @Transactional
    public void remove(String providerId, String workspaceId) {
        ProviderWorkspaceWhitelist existing = whitelistMapper.findByProviderAndWorkspace(providerId, workspaceId);
        if (existing == null) {
            return; // 幂等
        }
        whitelistMapper.deleteById(existing.getId());
        log.info("Removed provider whitelist: providerId={}, workspaceId={}", providerId, workspaceId);
    }

    @Override
    public List<ProviderWorkspaceWhitelist> listByProvider(String providerId) {
        return whitelistMapper.listByProvider(providerId);
    }

    @Override
    public boolean isWhitelisted(String providerId, String workspaceId) {
        return whitelistMapper.findByProviderAndWorkspace(providerId, workspaceId) != null;
    }
}
