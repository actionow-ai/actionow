package com.actionow.workspace.controller;

import com.actionow.common.core.result.PageResult;
import com.actionow.common.core.result.Result;
import com.actionow.common.security.annotation.RequireSystemTenant;
import com.actionow.workspace.dto.WorkspaceAdminResponse;
import com.actionow.workspace.entity.Workspace;
import com.actionow.workspace.mapper.WorkspaceMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 系统租户管理员视角的 workspace 接口（用于灰度白名单 / 内部测试 workspace 管理）。
 *
 * @author Actionow
 */
@Slf4j
@RestController
@RequestMapping("/workspaces/admin")
@RequiredArgsConstructor
@Tag(name = "Workspace 管理员接口", description = "系统租户管理员对 workspace 的查询与标记")
@RequireSystemTenant(minRole = "ADMIN")
public class WorkspaceAdminController {

    private final WorkspaceMapper workspaceMapper;

    /**
     * 分页搜索 workspace（按 name/slug 模糊匹配，可选只看内部测试 workspace）
     */
    @Operation(summary = "分页搜索 workspace")
    @GetMapping
    public Result<PageResult<WorkspaceAdminResponse>> search(
            @RequestParam(value = "current", defaultValue = "1") long current,
            @RequestParam(value = "size", defaultValue = "20") long size,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "internalOnly", required = false) Boolean internalOnly) {
        if (current < 1) current = 1;
        if (size < 1) size = 20;
        if (size > 100) size = 100;

        Page<Workspace> page = new Page<>(current, size);
        IPage<Workspace> result = workspaceMapper.adminSearchPage(page, q, internalOnly);

        List<WorkspaceAdminResponse> records = result.getRecords().stream()
                .map(WorkspaceAdminResponse::fromEntity)
                .toList();

        return Result.success(PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), records));
    }

    /**
     * 设置 workspace 是否为内部测试 workspace
     */
    @Operation(summary = "设置 internal 标记")
    @PatchMapping("/{id}/internal-flag")
    public Result<WorkspaceAdminResponse> setInternal(@PathVariable("id") String workspaceId,
                                                     @RequestParam("internal") boolean internal) {
        Workspace ws = workspaceMapper.selectById(workspaceId);
        if (ws == null) {
            throw new com.actionow.common.core.exception.BusinessException("workspace 不存在: " + workspaceId);
        }
        ws.setIsInternal(internal);
        workspaceMapper.updateById(ws);
        log.info("[admin] set workspace.is_internal: workspaceId={}, name={}, internal={}",
                workspaceId, ws.getName(), internal);
        return Result.success(WorkspaceAdminResponse.fromEntity(ws));
    }
}
