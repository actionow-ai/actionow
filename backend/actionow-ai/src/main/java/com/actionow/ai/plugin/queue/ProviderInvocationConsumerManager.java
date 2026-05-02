package com.actionow.ai.plugin.queue;

import com.actionow.ai.config.AiRuntimeConfigService;
import com.actionow.ai.entity.ModelProvider;
import com.actionow.ai.plugin.PluginExecutor;
import com.actionow.ai.plugin.exception.PluginCircuitBreakerException;
import com.actionow.ai.plugin.http.PluginHttpClient;
import com.actionow.ai.plugin.model.PluginConfig;
import com.actionow.ai.plugin.model.PluginExecutionResult;
import com.actionow.ai.service.ModelProviderService;
import com.actionow.common.core.context.UserContext;
import com.actionow.common.core.context.UserContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态管理每个队列的 SimpleMessageListenerContainer。
 *
 * 职责：
 * - 启动时扫描所有 enabled provider，确保每个目标队列（含默认队列）都有 listener
 * - provider 配置变更（CRUD / 队列字段调整）时通过 reconcile() 增删容器
 * - 每条消息：调用 PluginExecutor.execute → 写结果到 ResultStore；CB 拒绝则 nack+requeue
 * - 重建 UserContext，让下游脚本/审计能拿到原调用方信息
 */
@Slf4j
@Component
public class ProviderInvocationConsumerManager {

    private final ConnectionFactory connectionFactory;
    private final MessageConverter messageConverter;
    private final ProviderQueueRouter queueRouter;
    private final ProviderInvocationProducer producer;
    private final ProviderInvocationResultStore resultStore;
    private final PluginExecutor pluginExecutor;
    private final ModelProviderService modelProviderService;
    private final PluginHttpClient httpClient;
    private final AiRuntimeConfigService runtimeConfig;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final ProviderInvocationMetrics metrics;

    /** worker 进程内唯一 ID，用于幂等 claim 的 owner 标识 */
    private static final String WORKER_ID = UUID.randomUUID().toString();
    private static final String CLAIM_KEY_PREFIX = "ai:invocation:claim:";

    /** queueName → 容器；同一队列只一个 container（多 provider 共享默认队列时） */
    private final ConcurrentHashMap<String, SimpleMessageListenerContainer> containers = new ConcurrentHashMap<>();

    public ProviderInvocationConsumerManager(ConnectionFactory connectionFactory,
                                              MessageConverter messageConverter,
                                              ProviderQueueRouter queueRouter,
                                              ProviderInvocationProducer producer,
                                              ProviderInvocationResultStore resultStore,
                                              PluginExecutor pluginExecutor,
                                              @Lazy ModelProviderService modelProviderService,
                                              PluginHttpClient httpClient,
                                              AiRuntimeConfigService runtimeConfig,
                                              ObjectMapper objectMapper,
                                              StringRedisTemplate redis,
                                              ProviderInvocationMetrics metrics) {
        this.connectionFactory = connectionFactory;
        this.messageConverter = messageConverter;
        this.queueRouter = queueRouter;
        this.producer = producer;
        this.resultStore = resultStore;
        this.pluginExecutor = pluginExecutor;
        this.modelProviderService = modelProviderService;
        this.httpClient = httpClient;
        this.runtimeConfig = runtimeConfig;
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.metrics = metrics;
    }

    @PostConstruct
    public void init() {
        // 默认队列必须先起
        ensureContainer(queueRouter.defaultSpec());
        try {
            reconcile();
        } catch (Exception e) {
            // 启动期 DB 可能尚未 ready，不阻塞 provider listener 启动
            log.warn("[ConsumerManager] initial reconcile failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        containers.values().forEach(MessageListenerContainer::stop);
        containers.clear();
    }

    /**
     * 扫描所有启用 provider，确保自定义队列都有 listener；
     * 已不再使用的容器停掉。
     */
    public synchronized void reconcile() {
        java.util.List<ModelProvider> providers;
        try {
            providers = modelProviderService.findAllEnabled();
        } catch (Exception e) {
            log.warn("[ConsumerManager] list providers failed: {}", e.getMessage());
            return;
        }
        Set<String> wanted = new java.util.HashSet<>();
        wanted.add(queueRouter.defaultSpec().getQueueName());
        for (ModelProvider p : providers) {
            if (p == null || p.getId() == null) continue;
            QueueSpec spec = queueRouter.resolve(p.getId());
            wanted.add(spec.getQueueName());
            ensureContainer(spec);
        }
        // 停掉不再需要的 container
        for (String name : new java.util.HashSet<>(containers.keySet())) {
            if (!wanted.contains(name)) {
                SimpleMessageListenerContainer c = containers.remove(name);
                if (c != null) {
                    c.stop();
                    log.info("[ConsumerManager] stopped obsolete container for queue={}", name);
                }
            }
        }
    }

    /**
     * 公开方法：保证指定 spec 对应的队列+消费者已启动。
     * 由 Facade.submit() 在 publish 前调用，解决"运行期新增 provider 但消费者未启动"的隐患。
     * 内部 computeIfAbsent，多线程并发调用是安全的（startContainer 仅执行一次）。
     */
    public void ensureContainer(QueueSpec spec) {
        producer.declareIfAbsent(spec);
        containers.computeIfAbsent(spec.getQueueName(), name -> startContainer(spec));
    }

    private SimpleMessageListenerContainer startContainer(QueueSpec spec) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(spec.getQueueName());
        // listener 内部手动用 messageConverter.fromMessage() 解码，因此容器上无需 setMessageConverter
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setConcurrentConsumers(spec.getConcurrency());
        container.setMaxConcurrentConsumers(spec.getConcurrency());
        container.setPrefetchCount(spec.getPrefetch());
        container.setMessageListener((ChannelAwareMessageListener) (msg, channel) -> handle(msg, channel, spec));
        container.start();
        log.info("[ConsumerManager] started container queue={} concurrency={} prefetch={}",
            spec.getQueueName(), spec.getConcurrency(), spec.getPrefetch());
        return container;
    }

    private void handle(Message amqpMessage, com.rabbitmq.client.Channel channel, QueueSpec spec) {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        ProviderInvocationMessage msg;
        try {
            Object converted = messageConverter.fromMessage(amqpMessage);
            if (converted instanceof ProviderInvocationMessage m) {
                msg = m;
            } else if (converted instanceof java.util.Map<?, ?> map) {
                msg = objectMapper.convertValue(map, ProviderInvocationMessage.class);
            } else {
                throw new IllegalArgumentException("unsupported message type: " + converted.getClass());
            }
        } catch (Exception e) {
            log.error("[Consumer] message decode failed (queue={}): {}", spec.getQueueName(), e.getMessage(), e);
            safeAck(channel, deliveryTag, false, false); // 不 requeue 直接进 DLX
            return;
        }

        // 幂等保护：RabbitMQ 至少一次投递。worker 完成执行但 ack 前崩溃 → 同一消息会被
        // 重新投递。SETNX 占位防止重复执行（导致重复扣费/重复生图）。
        // claim 持续时间 = 2× 消息 TTL，确保即使 broker 反复重投也只第一次能拿到 claim。
        String claimKey = CLAIM_KEY_PREFIX + msg.getRequestId();
        long claimTtlSec = Math.max(60L, runtimeConfig.getQueueMessageTtlSeconds() * 2L);
        Boolean claimed = redis.opsForValue().setIfAbsent(
            claimKey, WORKER_ID, Duration.ofSeconds(claimTtlSec));
        if (!Boolean.TRUE.equals(claimed)) {
            log.warn("[Consumer] duplicate delivery skipped: requestId={}, owner={}",
                msg.getRequestId(), redis.opsForValue().get(claimKey));
            metrics.recordDuplicateSkipped(msg.getProviderId());
            safeAck(channel, deliveryTag, true, false); // 别人已在执行 → 直接 ack 丢弃副本
            return;
        }

        Instant startedAt = Instant.now();
        try {
            UserContextHolder.setContext(buildContext(msg));
            ModelProvider provider = modelProviderService.getById(msg.getProviderId());
            if (provider == null) {
                writeFailed(msg, startedAt, "PROVIDER_NOT_FOUND", "provider not found: " + msg.getProviderId());
                safeAck(channel, deliveryTag, false, false);
                return;
            }
            PluginConfig config = modelProviderService.toPluginConfig(provider);
            // 插件按 pluginType（如 "groovy"/"generic-http"）注册，不是 pluginId（具体 provider 名）
            String pluginImplId = provider.getPluginType() != null
                ? provider.getPluginType().toLowerCase()
                : provider.getPluginId();

            PluginExecutionResult execResult = pluginExecutor.execute(pluginImplId, config, msg.getRequest());

            ProviderInvocationResult result = ProviderInvocationResult.builder()
                .requestId(msg.getRequestId())
                .providerId(msg.getProviderId())
                .status(execResult.getStatus() == PluginExecutionResult.ExecutionStatus.FAILED
                    ? ProviderInvocationResult.Status.FAILED
                    : ProviderInvocationResult.Status.SUCCESS)
                .executionResult(execResult)
                .errorCode(execResult.getStatus() == PluginExecutionResult.ExecutionStatus.FAILED
                    ? execResult.getErrorCode() : null)
                .errorMessage(execResult.getStatus() == PluginExecutionResult.ExecutionStatus.FAILED
                    ? execResult.getErrorMessage() : null)
                .submittedAt(msg.getSubmittedAt())
                .startedAt(startedAt)
                .finishedAt(Instant.now())
                .build();
            resultStore.save(result);
            sendCallback(msg, result);
            boolean success = result.getStatus() == ProviderInvocationResult.Status.SUCCESS;
            metrics.recordCompleted(msg.getProviderId(), success);
            metrics.recordDuration(msg.getProviderId(), success, safeDuration(msg.getSubmittedAt(), startedAt));
            safeAck(channel, deliveryTag, true, false);
        } catch (PluginCircuitBreakerException cb) {
            // 上游被熔断 — requeue，让消息等下次 CB 恢复再消费（不计入失败结果）。
            // 必须释放 claim，否则 redelivery 会被幂等保护误杀
            log.warn("[Consumer] CB rejected, requeue: requestId={}, state={}", msg.getRequestId(), cb.getState());
            redis.delete(claimKey);
            metrics.recordCbRequeued(msg.getProviderId());
            safeAck(channel, deliveryTag, false, true);
        } catch (Exception e) {
            log.error("[Consumer] handle failed: requestId={}, err={}", msg.getRequestId(), e.getMessage(), e);
            writeFailed(msg, startedAt, "INVOCATION_ERROR", e.getMessage());
            safeAck(channel, deliveryTag, false, false);
        } finally {
            UserContextHolder.clear();
        }
    }

    private UserContext buildContext(ProviderInvocationMessage msg) {
        UserContext ctx = new UserContext();
        ctx.setUserId(msg.getUserId());
        ctx.setWorkspaceId(msg.getWorkspaceId());
        ctx.setTenantSchema(msg.getTenantSchema());
        ctx.setRequestId(msg.getRequestId());
        return ctx;
    }

    private void writeFailed(ProviderInvocationMessage msg, Instant startedAt, String code, String errMsg) {
        ProviderInvocationResult result = ProviderInvocationResult.builder()
            .requestId(msg.getRequestId())
            .providerId(msg.getProviderId())
            .status(ProviderInvocationResult.Status.FAILED)
            .errorCode(code)
            .errorMessage(errMsg)
            .submittedAt(msg.getSubmittedAt())
            .startedAt(startedAt)
            .finishedAt(Instant.now())
            .build();
        resultStore.save(result);
        sendCallback(msg, result);
        metrics.recordCompleted(msg.getProviderId(), false);
        metrics.recordDuration(msg.getProviderId(), false, safeDuration(msg.getSubmittedAt(), startedAt));
    }

    /**
     * 计算从 submittedAt（可能来自其它节点）到 now 的耗时。
     * 跨节点时钟漂移可能让 submittedAt > now → 负数会污染 Timer 直方图。
     * 防御：负数时回退到本地 startedAt 作基准；仍异常则返回 0。
     */
    private java.time.Duration safeDuration(Instant submittedAt, Instant startedAt) {
        Instant base = submittedAt != null ? submittedAt : startedAt;
        java.time.Duration d = java.time.Duration.between(base, Instant.now());
        if (d.isNegative()) {
            d = java.time.Duration.between(startedAt, Instant.now());
        }
        return d.isNegative() ? java.time.Duration.ZERO : d;
    }

    private void sendCallback(ProviderInvocationMessage msg, ProviderInvocationResult result) {
        if (msg.getCallbackUrl() == null || msg.getCallbackUrl().isBlank()) {
            return;
        }
        try {
            java.util.Map<String, Object> payload = objectMapper.convertValue(result, java.util.Map.class);
            httpClient.postCallback(msg.getCallbackUrl(), payload,
                msg.getUserId(), msg.getWorkspaceId(), msg.getTenantSchema());
        } catch (Exception e) {
            // 回调失败不影响结果存储；调用方可以拉 Redis
            log.warn("[Consumer] callback failed: requestId={}, url={}, err={}",
                msg.getRequestId(), msg.getCallbackUrl(), e.getMessage());
        }
    }

    private void safeAck(com.rabbitmq.client.Channel channel, long tag, boolean success, boolean requeue) {
        try {
            if (success) {
                channel.basicAck(tag, false);
            } else {
                channel.basicNack(tag, false, requeue);
            }
        } catch (java.io.IOException e) {
            log.warn("[Consumer] ack/nack failed (tag={}): {}", tag, e.getMessage());
        }
    }
}
