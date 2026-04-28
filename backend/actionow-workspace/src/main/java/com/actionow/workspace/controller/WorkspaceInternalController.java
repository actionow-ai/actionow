package com.actionow.workspace.controller;

import com.actionow.common.core.result.Result;
import com.actionow.common.security.annotation.IgnoreAuth;
import com.actionow.workspace.dto.WorkspaceMembershipInfo;
import com.actionow.workspace.entity.Workspace;
import com.actionow.workspace.entity.WorkspaceMember;
import com.actionow.workspace.service.WorkspaceMemberService;
import com.actionow.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * 工作空间内部接口
 * 供其他微服务调用，不对外暴露
 *
 * @author Actionow
 */
@RestController
@RequestMapping("/internal/workspace")
@RequiredArgsConstructor
@IgnoreAuth
public class WorkspaceInternalController {

    private final WorkspaceService workspaceService;
    private final WorkspaceMemberService memberService;

    /**
     * 验证用户是否是工作空间成员
     *
     * @param workspaceId 工作空间ID
     * @param userId      用户ID
     * @return 成员身份信息
     */
    @GetMapping("/{workspaceId}/membership")
    public Result<WorkspaceMembershipInfo> getMembership(
            @PathVariable String workspaceId,
            @RequestParam String userId) {

        Optional<Workspace> workspaceOpt = workspaceService.findById(workspaceId);
        if (workspaceOpt.isEmpty()) {
            return Result.success(WorkspaceMembershipInfo.builder()
                    .workspaceId(workspaceId)
                    .userId(userId)
                    .member(false)
                    .build());
        }

        Workspace workspace = workspaceOpt.get();
        Optional<WorkspaceMember> memberOpt = memberService.getMember(workspaceId, userId);

        if (memberOpt.isEmpty()) {
            return Result.success(WorkspaceMembershipInfo.builder()
                    .workspaceId(workspaceId)
                    .userId(userId)
                    .member(false)
                    .workspaceName(workspace.getName())
                    .tenantSchema(workspace.getSchemaName())
                    .build());
        }

        WorkspaceMember member = memberOpt.get();
        return Result.success(WorkspaceMembershipInfo.builder()
                .workspaceId(workspaceId)
                .userId(userId)
                .member(true)
                .role(member.getRole())
                .tenantSchema(workspace.getSchemaName())
                .workspaceName(workspace.getName())
                .build());
    }

    /**
     * 获取工作空间的租户Schema
     *
     * @param workspaceId 工作空间ID
     * @return 租户Schema
     */
    @GetMapping("/{workspaceId}/schema")
    public Result<String> getTenantSchema(@PathVariable String workspaceId) {
        return workspaceService.findById(workspaceId)
                .map(Workspace::getSchemaName)
                .map(Result::success)
                .orElse(Result.success(null));
    }

    /**
     * 查询工作空间是否为内部测试 workspace
     */
    @GetMapping("/{workspaceId}/is-internal")
    public Result<Boolean> isInternal(@PathVariable String workspaceId) {
        boolean internal = workspaceService.findById(workspaceId)
                .map(w -> Boolean.TRUE.equals(w.getIsInternal()))
                .orElse(false);
        return Result.success(internal);
    }

    /**
     * 同步工作空间订阅计划（供 Billing 服务调用）
     *
     * @param workspaceId 工作空间ID
     * @param planType    计划类型（Free/Basic/Pro/Enterprise）
     * @param operatorId  操作者标识（服务账号）
     */
    @PostMapping("/{workspaceId}/plan")
    public Result<Void> updatePlanInternal(@PathVariable String workspaceId,
                                           @RequestParam String planType,
                                           @RequestParam(defaultValue = "billing-system") String operatorId) {
        workspaceService.updatePlanInternal(workspaceId, planType, operatorId);
        return Result.success();
    }

    /**
     * 将用户以 GUEST 角色添加为工作空间成员
     * 供 project 服务在剧本创建者邀请非成员协作时调用
     *
     * @param workspaceId 工作空间ID
     * @param userId      被邀请用户ID
     * @param invitedBy   邀请人ID
     */
    @PostMapping("/{workspaceId}/members/guest")
    public Result<Void> addGuestMember(@PathVariable String workspaceId,
                                       @RequestParam String userId,
                                       @RequestParam String invitedBy) {
        memberService.addMember(workspaceId, userId, "GUEST", invitedBy);
        return Result.success();
    }
}
