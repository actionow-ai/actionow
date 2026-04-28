package com.actionow.common.security.workspace;

import com.actionow.common.core.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * 基于 JDBC 的工作空间内部客户端
 * 直接查询 public schema 的 t_workspace / t_workspace_member 表，
 * 避免所有服务通过 Feign 远程调用 workspace 服务
 *
 * @author Actionow
 */
@Slf4j
@RequiredArgsConstructor
public class JdbcWorkspaceInternalClient implements WorkspaceInternalClient {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Result<WorkspaceMembershipInfo> getMembership(String workspaceId, String userId) {
        // 查询工作空间
        List<Map<String, Object>> workspaceRows = jdbcTemplate.queryForList(
                "SELECT name, schema_name FROM public.t_workspace WHERE id = ? AND deleted = 0",
                workspaceId);

        if (workspaceRows.isEmpty()) {
            return Result.success(WorkspaceMembershipInfo.builder()
                    .workspaceId(workspaceId)
                    .userId(userId)
                    .member(false)
                    .build());
        }

        Map<String, Object> workspace = workspaceRows.getFirst();
        String workspaceName = (String) workspace.get("name");
        String tenantSchema = (String) workspace.get("schema_name");

        // 查询成员关系
        List<Map<String, Object>> memberRows = jdbcTemplate.queryForList(
                "SELECT role FROM public.t_workspace_member WHERE workspace_id = ? AND user_id = ? AND deleted = 0",
                workspaceId, userId);

        if (memberRows.isEmpty()) {
            return Result.success(WorkspaceMembershipInfo.builder()
                    .workspaceId(workspaceId)
                    .userId(userId)
                    .member(false)
                    .workspaceName(workspaceName)
                    .tenantSchema(tenantSchema)
                    .build());
        }

        String role = (String) memberRows.getFirst().get("role");
        return Result.success(WorkspaceMembershipInfo.builder()
                .workspaceId(workspaceId)
                .userId(userId)
                .member(true)
                .role(role)
                .tenantSchema(tenantSchema)
                .workspaceName(workspaceName)
                .build());
    }

    @Override
    public Result<String> getTenantSchema(String workspaceId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT schema_name FROM public.t_workspace WHERE id = ? AND deleted = 0",
                workspaceId);

        if (rows.isEmpty()) {
            return Result.success(null);
        }

        return Result.success((String) rows.getFirst().get("schema_name"));
    }

    @Override
    public Result<Boolean> isInternal(String workspaceId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT is_internal FROM public.t_workspace WHERE id = ? AND deleted = 0",
                workspaceId);

        if (rows.isEmpty()) {
            return Result.success(Boolean.FALSE);
        }

        Object value = rows.getFirst().get("is_internal");
        return Result.success(Boolean.TRUE.equals(value));
    }
}
