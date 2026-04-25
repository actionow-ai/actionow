package com.actionow.system.controller;

import com.actionow.common.core.result.Result;
import com.actionow.common.security.annotation.RequireWorkspaceMember;
import com.actionow.common.security.annotation.RequireWorkspaceMember.WorkspaceRole;
import com.actionow.common.security.util.SecurityUtils;
import com.actionow.system.dto.GiftCodeRedeemRequest;
import com.actionow.system.dto.GiftCodeRedeemResponse;
import com.actionow.system.service.GiftCodeRedemptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 礼包码兑换控制器（用户侧）
 *
 * @author Actionow
 */
@RestController
@RequestMapping("/system/gift-codes")
@RequiredArgsConstructor
public class GiftCodeRedeemController {

    private final GiftCodeRedemptionService redemptionService;

    /**
     * 兑换礼包码到当前工作空间钱包
     */
    @PostMapping("/redeem")
    @RequireWorkspaceMember(minRole = WorkspaceRole.MEMBER)
    public Result<GiftCodeRedeemResponse> redeem(@Valid @RequestBody GiftCodeRedeemRequest request) {
        String workspaceId = SecurityUtils.getCurrentWorkspaceId();
        String userId = SecurityUtils.requireCurrentUserId();
        return Result.success(redemptionService.redeem(request, workspaceId, userId));
    }
}
