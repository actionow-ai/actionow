package com.actionow.ai.plugin.exception;

import lombok.Getter;

/**
 * 插件熔断异常。
 *
 * 区分 OPEN 与 HALF_OPEN：
 *  - OPEN：上游确诊故障，建议中长期退避（默认 wait_open_seconds）
 *  - HALF_OPEN：恢复试探中，仅是并发数瞬时超过 permittedCallsInHalfOpen，
 *    建议短延迟后立即重试，不应当作"服务不可用"
 */
@Getter
public class PluginCircuitBreakerException extends PluginException {

    /** 熔断器状态：OPEN / HALF_OPEN / FORCED_OPEN 等 */
    private final String state;
    /** 建议客户端重试等待秒数（HALF_OPEN 通常 1-2s，OPEN 用 wait_open_seconds） */
    private final int retryAfterSeconds;

    public PluginCircuitBreakerException(String providerId) {
        this(providerId, "OPEN", 30);
    }

    public PluginCircuitBreakerException(String providerId, String state, int retryAfterSeconds) {
        super("CIRCUIT_BREAKER_" + state, buildMessage(state), providerId);
        this.state = state;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    private static String buildMessage(String state) {
        if ("HALF_OPEN".equals(state)) {
            return "服务正在恢复中，并发试探额度已满，请稍后立即重试";
        }
        return "上游服务暂不可用，熔断器已打开";
    }
}
