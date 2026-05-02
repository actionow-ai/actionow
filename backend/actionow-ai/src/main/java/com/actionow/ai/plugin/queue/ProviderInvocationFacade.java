package com.actionow.ai.plugin.queue;

import com.actionow.ai.config.AiRuntimeConfigService;
import com.actionow.ai.entity.ModelProvider;
import com.actionow.ai.plugin.exception.PluginException;
import com.actionow.ai.plugin.model.PluginExecutionRequest;
import com.actionow.ai.plugin.model.PluginExecutionResult;
import com.actionow.ai.service.ModelProviderService;
import com.actionow.common.core.context.UserContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

/**
 * Provider 调用对外门面。
 *
 * 入口三 API：
 * - submit(...)        异步入队，返回 requestId
 * - awaitBlocking(...) 同步等待结果（看起来像直接调用）
 * - getStatus(...)     轮询查询
 * - submitAndAwait(...) 一步完成（最常见）
 *
 * 队列满时抛 ResponseStatusException(503, Retry-After=10)，
 * 让前端立刻拿到背压信号；不会让调用方僵在 publish 上。
 */
@Slf4j
@Component
public class ProviderInvocationFacade {

    private final ProviderQueueRouter router;
    private final ProviderInvocationProducer producer;
    private final ProviderInvocationResultStore resultStore;
    private final AiRuntimeConfigService runtimeConfig;
    private final ModelProviderService modelProviderService;
    private final ProviderInvocationConsumerManager consumerManager;
    private final ProviderInvocationMetrics metrics;

    public ProviderInvocationFacade(ProviderQueueRouter router,
                                     ProviderInvocationProducer producer,
                                     ProviderInvocationResultStore resultStore,
                                     AiRuntimeConfigService runtimeConfig,
                                     @Lazy ModelProviderService modelProviderService,
                                     ProviderInvocationConsumerManager consumerManager,
                                     ProviderInvocationMetrics metrics) {
        this.router = router;
        this.producer = producer;
        this.resultStore = resultStore;
        this.runtimeConfig = runtimeConfig;
        this.modelProviderService = modelProviderService;
        this.consumerManager = consumerManager;
        this.metrics = metrics;
    }

    /**
     * 异步提交。立即返回 requestId；调用方稍后用 awaitBlocking / getStatus 拿结果，
     * 或在 message 上设置 callbackUrl 接收推送。
     */
    public String submit(String providerId, PluginExecutionRequest request, String callbackUrl) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId is required");
        }
        ModelProvider provider = modelProviderService.getById(providerId);
        if (provider == null) {
            throw PluginException.providerNotFound(providerId);
        }
        QueueSpec spec = router.resolve(providerId);
        // 关键：publish 前必须确保 consumer 已启动，否则运行期新增 provider 的消息会无声堆积。
        // ensureContainer 内部 computeIfAbsent，重复调用零开销。
        consumerManager.ensureContainer(spec);

        String requestId = request.getExecutionId() != null ? request.getExecutionId() : UUID.randomUUID().toString();
        Instant submittedAt = Instant.now();

        // 先写 PENDING：让任何 race 的 await 调用都能看到状态
        resultStore.save(ProviderInvocationResult.pending(requestId, providerId, submittedAt));

        ProviderInvocationMessage msg = ProviderInvocationMessage.builder()
            .requestId(requestId)
            .providerId(providerId)
            .pluginId(provider.getPluginId())
            .request(request)
            .userId(UserContextHolder.getUserId())
            .workspaceId(UserContextHolder.getWorkspaceId())
            .tenantSchema(UserContextHolder.getTenantSchema())
            .callbackUrl(callbackUrl)
            .submittedAt(submittedAt)
            .build();

        try {
            producer.publish(spec, msg);
        } catch (ProviderInvocationProducer.ProviderQueueFullException e) {
            metrics.recordQueueFull(providerId, spec.getQueueName());
            // 队列满 → 立即清掉占位 PENDING，向上游返 503 + Retry-After
            resultStore.delete(requestId);
            ResponseStatusException ex = new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Provider queue full: " + e.getQueueName());
            ex.getHeaders().add("Retry-After", "10");
            throw ex;
        }
        metrics.recordSubmitted(providerId, spec.getQueueName());
        log.info("[Facade] submitted: requestId={} provider={} queue={}", requestId, providerId, spec.getQueueName());
        return requestId;
    }

    /** 阻塞等待结果；超时返回最新（可能仍是 PENDING）状态 */
    public ProviderInvocationResult awaitBlocking(String requestId, long timeoutMs) {
        try {
            ProviderInvocationResult r = resultStore.await(requestId, timeoutMs);
            if (r == null) {
                return ProviderInvocationResult.builder()
                    .requestId(requestId)
                    .status(ProviderInvocationResult.Status.FAILED)
                    .errorCode("RESULT_NOT_FOUND")
                    .errorMessage("result expired or never written")
                    .build();
            }
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProviderInvocationResult.builder()
                .requestId(requestId)
                .status(ProviderInvocationResult.Status.FAILED)
                .errorCode("INTERRUPTED")
                .errorMessage("await interrupted")
                .build();
        }
    }

    /**
     * 一次完成 submit + await（适合替换原同步 PluginExecutor.execute() 调用点）。
     * timeoutMs 默认取 runtime.ai.queue_submit_timeout_ms。
     */
    public PluginExecutionResult submitAndAwait(String providerId,
                                                 PluginExecutionRequest request,
                                                 Long timeoutMs) {
        String id = submit(providerId, request, null);
        long t = timeoutMs != null && timeoutMs > 0 ? timeoutMs : runtimeConfig.getQueueSubmitTimeoutMs();
        ProviderInvocationResult r = awaitBlocking(id, t);
        if (r.getExecutionResult() != null) {
            return r.getExecutionResult();
        }
        return PluginExecutionResult.failure(id,
            r.getErrorCode() != null ? r.getErrorCode() : "QUEUE_TIMEOUT",
            r.getErrorMessage() != null ? r.getErrorMessage() : "queue invocation timed out");
    }

    public ProviderInvocationResult getStatus(String requestId) {
        return resultStore.get(requestId);
    }
}
