package com.actionow.ai.plugin.queue;

import com.actionow.ai.plugin.model.PluginExecutionRequest;
import com.actionow.ai.plugin.model.PluginExecutionResult;
import com.actionow.ai.plugin.model.ResponseMode;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 队列调用集成测试：覆盖 happy path、幂等、背压、并发四个核心场景。
 *
 * 运行前需要 docker；本地用 `mvn -pl actionow-ai test -Dtest=ProviderInvocationQueueIT` 触发。
 */
class ProviderInvocationQueueIT extends ProviderInvocationQueueITBase {

    @BeforeEach
    void boot() throws Exception {
        super.setUp();
    }

    @AfterEach
    void teardown() {
        try { consumerManager.shutdown(); } catch (Exception ignore) {}
    }

    @Test
    @DisplayName("submitAndAwait：消息入队 → 消费 → 结果返回")
    void happyPath() {
        PluginExecutionRequest req = PluginExecutionRequest.builder()
            .providerId(testProviderId())
            .responseMode(ResponseMode.BLOCKING)
            .inputs(Map.of("prompt", "a cat"))
            .build();

        PluginExecutionResult result = facade.submitAndAwait(testProviderId(), req, 10_000L);

        assertNotNull(result);
        assertEquals(PluginExecutionResult.ExecutionStatus.SUCCEEDED, result.getStatus());
    }

    @Test
    @DisplayName("幂等：同一 requestId 被重复投递时只执行一次")
    void idempotencyOnRedelivery() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        when(pluginExecutor.execute(anyString(), any(), any())).thenAnswer(inv -> {
            executions.incrementAndGet();
            var req = inv.getArgument(2, PluginExecutionRequest.class);
            return PluginExecutionResult.builder()
                .executionId(req.getExecutionId())
                .status(PluginExecutionResult.ExecutionStatus.SUCCEEDED)
                .build();
        });

        String fixedId = UUID.randomUUID().toString();
        PluginExecutionRequest req = PluginExecutionRequest.builder()
            .executionId(fixedId)
            .providerId(testProviderId())
            .responseMode(ResponseMode.BLOCKING)
            .build();

        // 同一 requestId 提交两次（模拟重复投递）
        facade.submit(testProviderId(), req, null);
        facade.submit(testProviderId(), req, null);

        // 给 consumer 时间处理两次投递
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertNotNull(facade.getStatus(fixedId)));

        // 等待第二次投递被幂等保护拦截
        Thread.sleep(2000);

        assertEquals(1, executions.get(),
            "同一 requestId 重复投递应只执行一次；实际执行 " + executions.get() + " 次");
    }

    @Test
    @DisplayName("背压：队列满时 submit 抛 503 + Retry-After")
    void backPressureWhenQueueFull() throws Exception {
        // 关键：concurrency=1 + prefetch=1 + maxLength=1 才能精确控制 in-flight=1 + ready=1
        // 否则默认 prefetch=4 会让 consumer 一次拿走 4+ 条，queue depth 永远长不到 maxLength
        when(runtimeConfig.getQueueDefaultName()).thenReturn("ai.provider.it.backpressure");
        when(runtimeConfig.getQueueDefaultConcurrency()).thenReturn(1);
        when(runtimeConfig.getQueueDefaultPrefetch()).thenReturn(1);
        when(runtimeConfig.getQueueDefaultMaxLength()).thenReturn(1);
        router.invalidateAll();

        // mock 慢消费：让第一条消息一直占着 in-flight 槽
        when(pluginExecutor.execute(anyString(), any(), any())).thenAnswer(inv -> {
            Thread.sleep(30_000);
            var req = inv.getArgument(2, PluginExecutionRequest.class);
            return PluginExecutionResult.builder()
                .executionId(req.getExecutionId())
                .status(PluginExecutionResult.ExecutionStatus.SUCCEEDED)
                .build();
        });

        // 第一条 → consumer 立刻 prefetch 走，进入 in-flight（处理 30s 不返回）
        facade.submit(testProviderId(), newReq(), null);
        // 等 broker 把消息推给 consumer，确保 in-flight 槽被占住
        Thread.sleep(800);

        // 第二条 → 入队 ready，queue depth=1
        facade.submit(testProviderId(), newReq(), null);
        Thread.sleep(300);

        // 第三条 → 入队会让 ready=2 > maxLength=1 → broker reject → 翻译为 503
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
            facade.submit(testProviderId(), newReq(), null));
        assertEquals(503, ex.getStatusCode().value());
        assertTrue(ex.getHeaders().containsKey("Retry-After"),
            "503 应携带 Retry-After 头给客户端退避建议");
    }

    @Test
    @DisplayName("并发：100 个并发 submit 全部完成且无线程雪崩")
    void concurrentSubmitDoesNotMelt() throws Exception {
        when(runtimeConfig.getQueueDefaultName()).thenReturn("ai.provider.it.concurrent");
        when(runtimeConfig.getQueueDefaultMaxLength()).thenReturn(500);
        router.invalidateAll();

        ExecutorService pool = Executors.newFixedThreadPool(50);
        try {
            List<CompletableFuture<PluginExecutionResult>> futures = IntStream.range(0, 100)
                .mapToObj(i -> CompletableFuture.supplyAsync(() ->
                    facade.submitAndAwait(testProviderId(), newReq(), 30_000L), pool))
                .toList();

            long start = System.currentTimeMillis();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(60, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;

            int succeeded = (int) futures.stream()
                .map(CompletableFuture::join)
                .filter(r -> r.getStatus() == PluginExecutionResult.ExecutionStatus.SUCCEEDED)
                .count();
            assertEquals(100, succeeded, "100 并发 submit 应全部成功");
            // 4 worker × 单任务约 50ms（mock）→ 100 任务 ≈ 1.5s 数量级；保守上限 30s
            assertTrue(elapsed < 30_000, "100 并发耗时不应超过 30s，实际 " + elapsed + "ms");
        } finally {
            pool.shutdown();
        }
    }

    private PluginExecutionRequest newReq() {
        return PluginExecutionRequest.builder()
            .providerId(testProviderId())
            .responseMode(ResponseMode.BLOCKING)
            .build();
    }
}
