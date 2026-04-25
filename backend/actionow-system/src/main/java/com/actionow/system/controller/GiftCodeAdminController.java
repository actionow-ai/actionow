package com.actionow.system.controller;

import com.actionow.common.core.result.PageResult;
import com.actionow.common.core.result.Result;
import com.actionow.common.security.annotation.RequireSystemTenant;
import com.actionow.common.security.util.SecurityUtils;
import com.actionow.system.dto.GiftCodeRedemptionResponse;
import com.actionow.system.dto.GiftCodeRequest;
import com.actionow.system.dto.GiftCodeResponse;
import com.actionow.system.service.GiftCodeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 礼包码管理控制器（仅平台管理员可访问）
 *
 * @author Actionow
 */
@RestController
@RequestMapping("/system/gift-codes")
@RequiredArgsConstructor
@RequireSystemTenant(minRole = "ADMIN")
public class GiftCodeAdminController {

    private final GiftCodeService giftCodeService;

    @GetMapping
    public Result<PageResult<GiftCodeResponse>> listPage(
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "20") Long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return Result.success(giftCodeService.listPage(current, size, keyword, status));
    }

    @PostMapping
    public Result<GiftCodeResponse> create(@Valid @RequestBody GiftCodeRequest request) {
        String operatorId = SecurityUtils.requireCurrentUserId();
        return Result.success(giftCodeService.create(request, operatorId));
    }

    @PutMapping("/{id}")
    public Result<GiftCodeResponse> update(@PathVariable String id,
                                           @RequestBody GiftCodeRequest request) {
        String operatorId = SecurityUtils.requireCurrentUserId();
        return Result.success(giftCodeService.update(id, request, operatorId));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        String operatorId = SecurityUtils.requireCurrentUserId();
        giftCodeService.delete(id, operatorId);
        return Result.success();
    }

    @GetMapping("/{id}")
    public Result<GiftCodeResponse> getById(@PathVariable String id) {
        return Result.success(giftCodeService.getById(id));
    }

    @GetMapping("/{id}/redemptions")
    public Result<PageResult<GiftCodeRedemptionResponse>> listRedemptions(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") Long current,
            @RequestParam(defaultValue = "20") Long size) {
        return Result.success(giftCodeService.listRedemptions(id, current, size));
    }
}
