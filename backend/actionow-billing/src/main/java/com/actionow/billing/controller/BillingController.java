package com.actionow.billing.controller;

import com.actionow.billing.config.BillingRuntimeConfigService;
import com.actionow.billing.dto.*;
import com.actionow.billing.service.OrderBillingService;
import com.actionow.billing.service.SubscriptionBillingService;
import com.actionow.common.core.context.UserContextHolder;
import com.actionow.common.core.result.Result;
import com.actionow.common.security.annotation.RequireWorkspaceMember;
import com.actionow.common.security.annotation.RequireWorkspaceMember.WorkspaceRole;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 计费对外接口
 */
@RestController
@RequestMapping("/billing")
@RequiredArgsConstructor
public class BillingController {

    private final OrderBillingService orderBillingService;
    private final SubscriptionBillingService subscriptionBillingService;
    private final BillingRuntimeConfigService billingRuntimeConfig;

    /**
     * 当前启用的支付渠道（用于前端动态渲染充值入口）
     */
    @GetMapping("/payment-providers")
    @RequireWorkspaceMember(minRole = WorkspaceRole.MEMBER)
    public Result<List<String>> listEnabledPaymentProviders() {
        return Result.success(billingRuntimeConfig.getEnabledPaymentProviders());
    }

    @PostMapping("/topups/orders")
    @RequireWorkspaceMember(minRole = WorkspaceRole.ADMIN)
    public Result<CreateTopupOrderResponse> createTopupOrder(@RequestBody @Valid CreateTopupOrderRequest request) {
        String workspaceId = resolveWorkspaceId(request.getWorkspaceId());
        String userId = UserContextHolder.getUserId();
        CreateTopupOrderResponse response = orderBillingService.createTopupOrder(request, workspaceId, userId);
        return Result.success(response);
    }

    @PostMapping("/topups/orders/{orderNo}/checkout-session")
    @RequireWorkspaceMember(minRole = WorkspaceRole.ADMIN)
    public Result<CreateCheckoutSessionResponse> createTopupCheckoutSession(
            @PathVariable String orderNo,
            @RequestBody(required = false) CreateCheckoutSessionRequest request) {
        String workspaceId = UserContextHolder.getWorkspaceId();
        CreateCheckoutSessionResponse response = orderBillingService.createTopupCheckoutSession(
                orderNo,
                request != null ? request : new CreateCheckoutSessionRequest(),
                workspaceId
        );
        return Result.success(response);
    }

    @GetMapping("/orders/{orderNo}")
    @RequireWorkspaceMember(minRole = WorkspaceRole.MEMBER)
    public Result<OrderDetailResponse> getOrder(@PathVariable String orderNo) {
        String workspaceId = UserContextHolder.getWorkspaceId();
        OrderDetailResponse response = orderBillingService.getOrder(orderNo, workspaceId);
        return Result.success(response);
    }

    @PostMapping("/orders/{orderNo}/verify-payment")
    @RequireWorkspaceMember(minRole = WorkspaceRole.MEMBER)
    public Result<OrderDetailResponse> verifyPayment(@PathVariable String orderNo) {
        String workspaceId = UserContextHolder.getWorkspaceId();
        OrderDetailResponse response = orderBillingService.verifyPayment(orderNo, workspaceId);
        return Result.success(response);
    }

    @GetMapping("/topups/rate")
    @RequireWorkspaceMember(minRole = WorkspaceRole.MEMBER)
    public Result<TopupRateResponse> getTopupRate(@RequestParam(defaultValue = "USD") String currency) {
        TopupRateResponse response = orderBillingService.getTopupRate(currency);
        return Result.success(response);
    }

    @PostMapping("/subscriptions/change-plan")
    @RequireWorkspaceMember(minRole = WorkspaceRole.CREATOR)
    public Result<ChangePlanResponse> changePlan(@RequestBody @Valid ChangePlanRequest request) {
        String workspaceId = resolveWorkspaceId(request.getWorkspaceId());
        String userId = UserContextHolder.getUserId();
        ChangePlanResponse response = subscriptionBillingService.changePlan(request, workspaceId, userId);
        return Result.success(response);
    }

    @GetMapping("/subscriptions/current")
    @RequireWorkspaceMember(minRole = WorkspaceRole.MEMBER)
    public Result<SubscriptionResponse> getCurrentSubscription() {
        String workspaceId = UserContextHolder.getWorkspaceId();
        SubscriptionResponse response = subscriptionBillingService.getCurrentSubscription(workspaceId);
        return Result.success(response);
    }

    @PostMapping("/subscriptions/{subscriptionId}/cancel")
    @RequireWorkspaceMember(minRole = WorkspaceRole.CREATOR)
    public Result<SubscriptionResponse> cancelSubscription(
            @PathVariable String subscriptionId,
            @RequestBody(required = false) CancelSubscriptionRequest request) {
        String workspaceId = UserContextHolder.getWorkspaceId();
        String userId = UserContextHolder.getUserId();
        SubscriptionResponse response = subscriptionBillingService.cancelSubscription(
                subscriptionId,
                request != null ? request : new CancelSubscriptionRequest(),
                workspaceId,
                userId
        );
        return Result.success(response);
    }

    private String resolveWorkspaceId(String requestWorkspaceId) {
        if (requestWorkspaceId != null && !requestWorkspaceId.isBlank()) {
            return requestWorkspaceId;
        }
        return UserContextHolder.getWorkspaceId();
    }
}
