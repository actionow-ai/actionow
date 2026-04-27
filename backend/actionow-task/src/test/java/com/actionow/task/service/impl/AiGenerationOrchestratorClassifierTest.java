package com.actionow.task.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiGenerationOrchestrator#isProviderSideError 分类器单元测试。
 * 覆盖 provider 自动 fallback 的白名单规则：用户/参数错误不 fallback，
 * 其他情况（5xx、超时、熔断、未分类）一律 fallback；大小写不敏感。
 */
class AiGenerationOrchestratorClassifierTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "PARAM_INVALID",
            "PARAM_MISSING",
            "param_invalid",
            "ParAm_BadInput",
            "VALIDATION_FAILED",
            "validation_required_field",
            "INSUFFICIENT_CREDIT",
            "INSUFFICIENT_CREDIT_BALANCE",
            "UNAUTHORIZED",
            "FORBIDDEN",
            "USER_NOT_FOUND",
            "ASSET_NOT_FOUND"
    })
    @DisplayName("用户/参数侧错误一律不 fallback")
    void clientErrorsNotEligibleForFallback(String errorCode) {
        assertFalse(AiGenerationOrchestrator.isProviderSideError(errorCode, "any message"),
                "errorCode=" + errorCode + " 不应触发 fallback");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "PROVIDER_INTERNAL_ERROR",
            "UPSTREAM_TIMEOUT",
            "CIRCUIT_BREAKER_OPEN",
            "RATE_LIMITED",
            "IO_EXCEPTION",
            "UNKNOWN_ERROR",
            "GATEWAY_5XX"
    })
    @DisplayName("provider 侧/未分类错误均可 fallback")
    void serverSideErrorsEligibleForFallback(String errorCode) {
        assertTrue(AiGenerationOrchestrator.isProviderSideError(errorCode, "upstream broke"),
                "errorCode=" + errorCode + " 应触发 fallback");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("无 errorCode 时按未分类错误处理，可 fallback")
    void absentCodeIsEligible(String errorCode) {
        assertTrue(AiGenerationOrchestrator.isProviderSideError(errorCode, "no code"));
    }

    @ParameterizedTest
    @CsvSource({
            "PARAM_INVALID,         false",
            "param_validation_xyz,  false",
            "USER_NOT_FOUND,        false",
            "PROVIDER_500,          true",
            "TIMEOUT,               true"
    })
    @DisplayName("综合用例：分类决策与白名单一致")
    void classifierIsCaseInsensitive(String code, boolean expected) {
        assertTrue(expected == AiGenerationOrchestrator.isProviderSideError(code, null),
                "code=" + code + " expected=" + expected);
    }
}
