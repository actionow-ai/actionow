package com.actionow.ai.plugin.queue;

import com.actionow.ai.config.AiRuntimeConfigService;
import com.actionow.ai.entity.ModelProvider;
import com.actionow.ai.plugin.PluginExecutor;
import com.actionow.ai.plugin.http.PluginHttpClient;
import com.actionow.ai.plugin.model.PluginConfig;
import com.actionow.ai.plugin.model.PluginExecutionResult;
import com.actionow.ai.service.ModelProviderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 队列调用集成测试基类。
 * 启动真实 RabbitMQ + Redis 容器；用 mock 替换 PluginExecutor / ModelProviderService。
 *
 * 子类在 @BeforeEach 后即可使用 facade、producer、consumerManager、resultStore。
 */
@Testcontainers(disabledWithoutDocker = true)
abstract class ProviderInvocationQueueITBase {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(
        DockerImageName.parse("rabbitmq:3.12-management"));

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>(
        DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    @BeforeAll
    static void assumeDockerAvailable() {
        // 当 docker daemon 不可用时，让整个 IT 类被跳过而不是 ERROR
        Assumptions.assumeTrue(
            org.testcontainers.DockerClientFactory.instance().isDockerAvailable(),
            "Docker daemon unavailable — skipping ProviderInvocationQueueIT");
    }

    protected ConnectionFactory amqpConnectionFactory;
    protected RabbitTemplate rabbitTemplate;
    protected AmqpAdmin amqpAdmin;
    protected MessageConverter messageConverter;
    protected ObjectMapper objectMapper;
    protected StringRedisTemplate redis;
    protected RedisMessageListenerContainer redisListenerContainer;

    protected AiRuntimeConfigService runtimeConfig;
    protected ModelProviderService modelProviderService;
    protected PluginExecutor pluginExecutor;
    protected PluginHttpClient httpClient;

    protected ProviderQueueRouter router;
    protected ProviderInvocationProducer producer;
    protected ProviderInvocationResultStore resultStore;
    protected ProviderInvocationConsumerManager consumerManager;
    protected ProviderInvocationFacade facade;
    protected ProviderInvocationMetrics metrics;

    /** 子类 @BeforeEach 触发：构造完整对象图 */
    protected void setUp() throws Exception {
        // ---- AMQP infra ----
        CachingConnectionFactory cf = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        cf.setUsername(RABBIT.getAdminUsername());
        cf.setPassword(RABBIT.getAdminPassword());
        cf.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
        cf.setPublisherReturns(true);
        amqpConnectionFactory = cf;

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTrustedPackages("com.actionow.*");
        converter.setJavaTypeMapper(typeMapper);
        messageConverter = converter;

        rabbitTemplate = new RabbitTemplate(amqpConnectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter);
        amqpAdmin = new RabbitAdmin(amqpConnectionFactory);

        // ---- Redis infra ----
        RedisStandaloneConfiguration redisCfg = new RedisStandaloneConfiguration(
            REDIS.getHost(), REDIS.getMappedPort(6379));
        LettuceConnectionFactory lcf = new LettuceConnectionFactory(redisCfg);
        lcf.afterPropertiesSet();
        redis = new StringRedisTemplate(lcf);
        redis.afterPropertiesSet();

        redisListenerContainer = new RedisMessageListenerContainer();
        redisListenerContainer.setConnectionFactory(lcf);
        redisListenerContainer.afterPropertiesSet();
        redisListenerContainer.start();

        // ---- 配置（mock：直接返回固定值，避开 RuntimeConfigService 的 init）----
        runtimeConfig = mock(AiRuntimeConfigService.class);
        when(runtimeConfig.getQueueDefaultName()).thenReturn("ai.provider.default.it");
        when(runtimeConfig.getQueueDefaultConcurrency()).thenReturn(4);
        when(runtimeConfig.getQueueDefaultPrefetch()).thenReturn(4);
        when(runtimeConfig.getQueueDefaultMaxLength()).thenReturn(100);
        when(runtimeConfig.getQueueResultTtlSeconds()).thenReturn(60);
        when(runtimeConfig.getQueueMessageTtlSeconds()).thenReturn(60);
        when(runtimeConfig.getQueueSubmitTimeoutMs()).thenReturn(15_000L);

        // ---- 业务 mock ----
        modelProviderService = mock(ModelProviderService.class);
        ModelProvider provider = new ModelProvider();
        provider.setId(testProviderId());
        provider.setPluginId("groovy");
        provider.setProviderType("IMAGE");
        provider.setEnabled(true);
        when(modelProviderService.getById(anyString())).thenReturn(provider);
        when(modelProviderService.findAllEnabled()).thenReturn(java.util.List.of(provider));
        when(modelProviderService.toPluginConfig(any())).thenReturn(
            PluginConfig.builder().providerId(testProviderId()).providerType("IMAGE").build());

        pluginExecutor = mock(PluginExecutor.class);
        when(pluginExecutor.execute(anyString(), any(), any())).thenAnswer(inv -> {
            var req = inv.getArgument(2, com.actionow.ai.plugin.model.PluginExecutionRequest.class);
            return PluginExecutionResult.builder()
                .executionId(req.getExecutionId())
                .status(PluginExecutionResult.ExecutionStatus.SUCCEEDED)
                .build();
        });

        httpClient = mock(PluginHttpClient.class);

        // ---- queue components ----
        metrics = new ProviderInvocationMetrics(new SimpleMeterRegistry());
        router = new ProviderQueueRouter(runtimeConfig, modelProviderService);
        producer = new ProviderInvocationProducer(rabbitTemplate, amqpAdmin);
        producer.initTemplate();
        resultStore = new ProviderInvocationResultStore(redis, redisListenerContainer, runtimeConfig, objectMapper);
        resultStore.init();
        consumerManager = new ProviderInvocationConsumerManager(
            amqpConnectionFactory, messageConverter, router, producer, resultStore,
            pluginExecutor, modelProviderService, httpClient, runtimeConfig, objectMapper, redis, metrics);
        facade = new ProviderInvocationFacade(router, producer, resultStore, runtimeConfig,
            modelProviderService, consumerManager, metrics);
    }

    protected String testProviderId() {
        return "00000000-0000-0000-0000-0000000000aa";
    }
}
