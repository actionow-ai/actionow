package com.actionow.task.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Task 模块核心指标。
 * <p>命名前缀 {@code actionow.task.}，与 AgentMetrics 命名风格保持一致。
 *
 * @author Actionow
 */
@Slf4j
@Component
public class TaskMetrics {

    // ==================== Provider Fallback (Phase 3.2) ====================

    private final Counter providerFallbackAttemptCounter;
    private final Counter providerFallbackSuccessCounter;
    private final Counter providerFallbackExhaustedCounter;

    public TaskMetrics(MeterRegistry registry) {
        // 触发 fallback 切换 provider 的次数（不论是否成功重投）
        this.providerFallbackAttemptCounter = Counter.builder("actionow.task.provider_fallback.total")
                .tag("outcome", "attempt")
                .description("Provider fallback 触发次数（任务失败后尝试切换 provider）")
                .register(registry);
        // fallback 成功改写并重投队列
        this.providerFallbackSuccessCounter = Counter.builder("actionow.task.provider_fallback.total")
                .tag("outcome", "success")
                .description("Provider fallback 成功改写 task.providerId 并重投队列")
                .register(registry);
        // fallback 已耗尽（超 max_attempts 或无更多候选 provider）
        this.providerFallbackExhaustedCounter = Counter.builder("actionow.task.provider_fallback.total")
                .tag("outcome", "exhausted")
                .description("Provider fallback 因 max_attempts 或无候选转入正常失败")
                .register(registry);

        log.info("TaskMetrics initialized (actionow.task.* meters registered)");
    }

    /** 一次 fallback 评估开始时调用。 */
    public void recordProviderFallbackAttempt() {
        providerFallbackAttemptCounter.increment();
    }

    /** fallback 成功改写并重投队列后调用。 */
    public void recordProviderFallbackSuccess() {
        providerFallbackSuccessCounter.increment();
    }

    /** fallback 因 budget / candidate 耗尽放弃时调用。 */
    public void recordProviderFallbackExhausted() {
        providerFallbackExhaustedCounter.increment();
    }
}
