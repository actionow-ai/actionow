package com.actionow.ai.mapper;

import com.actionow.ai.entity.ProviderWorkspaceWhitelist;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Provider workspace 白名单 Mapper
 *
 * @author Actionow
 */
@Mapper
public interface ProviderWorkspaceWhitelistMapper extends BaseMapper<ProviderWorkspaceWhitelist> {

    @Select("SELECT * FROM t_provider_workspace_whitelist " +
            "WHERE provider_id = #{providerId} AND workspace_id = #{workspaceId} AND deleted = 0 LIMIT 1")
    ProviderWorkspaceWhitelist findByProviderAndWorkspace(@Param("providerId") String providerId,
                                                          @Param("workspaceId") String workspaceId);

    @Select("SELECT * FROM t_provider_workspace_whitelist " +
            "WHERE provider_id = #{providerId} AND deleted = 0 ORDER BY created_at DESC")
    List<ProviderWorkspaceWhitelist> listByProvider(@Param("providerId") String providerId);

    @Select("SELECT provider_id FROM t_provider_workspace_whitelist " +
            "WHERE workspace_id = #{workspaceId} AND deleted = 0")
    List<String> listProviderIdsByWorkspace(@Param("workspaceId") String workspaceId);
}
