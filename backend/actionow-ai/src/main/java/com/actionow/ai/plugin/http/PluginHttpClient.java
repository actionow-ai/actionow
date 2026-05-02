package com.actionow.ai.plugin.http;

import com.actionow.ai.config.AiRuntimeConfigService;
import com.actionow.ai.plugin.auth.AuthConfig;
import com.actionow.ai.plugin.auth.AuthStrategyFactory;
import com.actionow.ai.plugin.auth.AuthenticationStrategy;
import com.actionow.ai.plugin.exception.PluginCircuitBreakerException;
import com.actionow.ai.plugin.model.PluginConfig;
import com.actionow.ai.plugin.resilience.PluginResilienceConfig;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import com.actionow.common.core.constant.CommonConstants;
import com.actionow.common.core.security.InternalAuthProperties;
import com.actionow.common.core.security.InternalAuthUtils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 插件统一HTTP客户端
 * 基于WebClient实现，支持阻塞和流式请求
 * 集成Resilience4j提供重试、熔断和限流能力
 *
 * @author Actionow
 */
@Slf4j
public class PluginHttpClient {

    /**
     * 默认响应体最大内存缓冲（32MB），兜底值。
     * 实际值由 AiRuntimeConfigService.getHttpMaxInMemorySizeBytes() 动态控制，
     * 可通过 Redis 运行时调整以平衡 OOM 防护与大图兼容性：
     * - 16MB 适合普通图片（1080p）
     * - 32MB 适合 4K 图片（默认）
     * - 64MB+ 适合视频缩略图批量响应
     */
    private static final int DEFAULT_MAX_IN_MEMORY_SIZE = 32 * 1024 * 1024;
    private static final int DEFAULT_WRITE_TIMEOUT_SECONDS = 30;
    private static final int PENDING_ACQUIRE_TIMEOUT_SECONDS = 60;
    private static final int MAX_IDLE_TIME_SECONDS = 30;
    private static final int MAX_LIFE_TIME_SECONDS = 60;

    private final AuthStrategyFactory authStrategyFactory;
    private final PluginResilienceConfig resilienceConfig;
    private final InternalAuthProperties internalAuthProperties;
    private final AiRuntimeConfigService aiRuntimeConfig;
    private final ObjectMapper objectMapper;
    /** WebClient 缓存：1 小时 TTL，最大 200 个条目，防止 Provider 频繁变更导致内存泄露 */
    private final Cache<String, WebClient> clientCache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
            .maximumSize(200)
            .build();
    private final ConnectionProvider connectionProvider;

    private final int connectTimeoutMs;
    private final int readTimeoutSeconds;

    public PluginHttpClient(AuthStrategyFactory authStrategyFactory,
                            PluginResilienceConfig resilienceConfig,
                            InternalAuthProperties internalAuthProperties,
                            AiRuntimeConfigService aiRuntimeConfig) {
        this.authStrategyFactory = authStrategyFactory;
        this.resilienceConfig = resilienceConfig;
        this.internalAuthProperties = internalAuthProperties;
        this.aiRuntimeConfig = aiRuntimeConfig;
        this.objectMapper = createObjectMapper();
        this.connectTimeoutMs = aiRuntimeConfig.getHttpConnectTimeoutMs();
        this.readTimeoutSeconds = aiRuntimeConfig.getHttpReadTimeoutSeconds();
        int maxConnections = aiRuntimeConfig.getHttpMaxConnections();
        int maxConnectionsPerRoute = aiRuntimeConfig.getHttpMaxConnectionsPerRoute();
        this.connectionProvider = createConnectionProvider(maxConnections);
        log.info("PluginHttpClient initialized with connection pool: maxConnections={}, maxConnectionsPerRoute={}",
            maxConnections, maxConnectionsPerRoute);
    }

    /**
     * 创建连接池
     */
    private ConnectionProvider createConnectionProvider(int maxConnections) {
        return ConnectionProvider.builder("plugin-http-pool")
            .maxConnections(maxConnections)
            .maxIdleTime(Duration.ofSeconds(MAX_IDLE_TIME_SECONDS))
            .maxLifeTime(Duration.ofSeconds(MAX_LIFE_TIME_SECONDS))
            .pendingAcquireTimeout(Duration.ofSeconds(PENDING_ACQUIRE_TIMEOUT_SECONDS))
            .evictInBackground(Duration.ofSeconds(30))
            .metrics(true)
            .build();
    }

    /**
     * 执行阻塞请求
     *
     * @param config 插件配置
     * @param body 请求体
     * @param responseType 响应类型
     * @return 响应对象
     */
    public <T> T executeBlocking(PluginConfig config, Object body, Class<T> responseType) {
        WebClient webClient = getOrCreateClient(config);
        HttpHeaders headers = buildHeaders(config);

        String endpoint = StringUtils.hasText(config.getEndpoint())
            ? config.getEndpoint()
            : "";
        String fullUrl = config.getBaseUrl() + endpoint;
        String method = StringUtils.hasText(config.getHttpMethod())
            ? config.getHttpMethod().toUpperCase()
            : "POST";

        log.info("[PluginHttpClient] ========== HTTP请求开始 ==========");
        log.info("[PluginHttpClient] 请求方法: {}", method);
        log.info("[PluginHttpClient] 请求URL: {}", fullUrl);
        log.info("[PluginHttpClient] 请求头: {}", maskSensitiveHeaders(headers));
        log.debug("[PluginHttpClient] 请求体: {}", body);

        long startTime = System.currentTimeMillis();

        try {
            HttpMethod httpMethod = HttpMethod.valueOf(method);

            WebClient.RequestBodySpec requestSpec = webClient
                .method(httpMethod)
                .uri(endpoint)
                .headers(h -> h.addAll(headers))
                .contentType(MediaType.APPLICATION_JSON);

            WebClient.ResponseSpec responseSpec;
            if (body != null && (httpMethod == HttpMethod.POST || httpMethod == HttpMethod.PUT || httpMethod == HttpMethod.PATCH)) {
                responseSpec = requestSpec.bodyValue(body).retrieve();
            } else {
                responseSpec = requestSpec.retrieve();
            }

            // 构建带弹性能力的Mono
            Mono<T> responseMono = responseSpec
                .bodyToMono(responseType)
                .timeout(Duration.ofMillis(config.getTimeout()));

            // 应用弹性装饰器
            responseMono = applyResilienceDecorators(responseMono, config);

            T response = responseMono.block();

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[PluginHttpClient] ========== HTTP请求成功 ==========");
            log.info("[PluginHttpClient] 响应耗时: {}ms", elapsed);
            log.debug("[PluginHttpClient] 响应内容: {}", response);

            return response;

        } catch (WebClientResponseException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("[PluginHttpClient] ========== HTTP请求失败 ==========");
            log.error("[PluginHttpClient] 响应耗时: {}ms", elapsed);
            log.error("[PluginHttpClient] HTTP状态码: {}", e.getStatusCode());
            log.error("[PluginHttpClient] 响应体: {}", e.getResponseBodyAsString());
            throw new PluginHttpException("HTTP request failed: " + e.getResponseBodyAsString(), e);
        } catch (CallNotPermittedException e) {
            throw translateCircuitBreaker(config, e);
        } catch (Exception e) {
            // Reactor 会把 CallNotPermittedException 包到 RuntimeException.cause 里
            if (e.getCause() instanceof CallNotPermittedException cnp) {
                throw translateCircuitBreaker(config, cnp);
            }
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("[PluginHttpClient] ========== HTTP请求异常 ==========");
            log.error("[PluginHttpClient] 响应耗时: {}ms", elapsed);
            log.error("[PluginHttpClient] 异常类型: {}", e.getClass().getSimpleName());
            log.error("[PluginHttpClient] 异常信息: {}", e.getMessage(), e);
            throw new PluginHttpException("HTTP request error: " + e.getMessage(), e);
        }
    }

    /**
     * 将 Resilience4j 的 CallNotPermittedException 翻译为带状态信息的业务异常。
     * 区分 OPEN（建议长退避）与 HALF_OPEN（建议短延迟立即重试）。
     */
    private PluginCircuitBreakerException translateCircuitBreaker(PluginConfig config, CallNotPermittedException e) {
        String providerId = config != null ? config.getProviderId() : null;
        String state = "OPEN";
        int retryAfter = 30;
        try {
            // 从消息中提取实际状态（"CircuitBreaker 'xxx' is HALF_OPEN ..."）
            String msg = e.getMessage();
            if (msg != null && msg.contains("HALF_OPEN")) {
                state = "HALF_OPEN";
                retryAfter = 2;
            } else if (msg != null && msg.contains("FORCED_OPEN")) {
                state = "FORCED_OPEN";
            }
        } catch (Exception ignore) {
            // 安全兜底
        }
        log.warn("[PluginHttpClient] CircuitBreaker rejected provider={}, state={}, retryAfter={}s",
            providerId, state, retryAfter);
        return new PluginCircuitBreakerException(providerId, state, retryAfter);
    }

    /**
     * 执行阻塞请求，返回Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> executeBlocking(PluginConfig config, Object body) {
        return executeBlocking(config, body, Map.class);
    }

    /**
     * 执行流式请求
     *
     * @param config 插件配置
     * @param body 请求体
     * @return SSE事件流
     */
    public Flux<String> executeStreaming(PluginConfig config, Object body) {
        WebClient webClient = getOrCreateClient(config);
        HttpHeaders headers = buildHeaders(config);

        String endpoint = StringUtils.hasText(config.getEndpoint())
            ? config.getEndpoint()
            : "";

        Flux<String> responseFlux = webClient.post()
            .uri(endpoint)
            .headers(h -> h.addAll(headers))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(body)
            .retrieve()
            .bodyToFlux(String.class)
            .timeout(Duration.ofMillis(config.getTimeout()))
            .doOnError(e -> {
                if (e instanceof WebClientResponseException we) {
                    log.error("Streaming request failed: status={}, body={}",
                        we.getStatusCode(), we.getResponseBodyAsString());
                } else {
                    log.error("Streaming request error: {}", e.getMessage(), e);
                }
            });

        // 对流式请求应用限流和熔断（不应用重试，因为流式请求重试会导致数据重复）
        return applyResilienceDecoratorsForFlux(responseFlux, config);
    }

    /**
     * 执行异步请求（不等待结果）
     */
    public Mono<Map<String, Object>> executeAsync(PluginConfig config, Object body) {
        WebClient webClient = getOrCreateClient(config);
        HttpHeaders headers = buildHeaders(config);

        String endpoint = StringUtils.hasText(config.getEndpoint())
            ? config.getEndpoint()
            : "";

        Mono<Map<String, Object>> responseMono = webClient.post()
            .uri(endpoint)
            .headers(h -> h.addAll(headers))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono(Map.class)
            .timeout(Duration.ofMillis(config.getTimeout()))
            .map(m -> (Map<String, Object>) m);

        return applyResilienceDecorators(responseMono, config);
    }

    /**
     * 执行GET请求（用于轮询）
     * 不应用限流器，因为轮询请求已由轮询间隔和最大次数控制
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> executeGet(PluginConfig config, String path) {
        return executePollRequest(config, path, "GET", null);
    }

    /**
     * 执行轮询请求（支持 GET 和 POST）
     * 不应用限流器，轮询频率已由 pollingConfig.intervalMs 控制
     *
     * @param config     插件配置（提供 baseUrl、认证等）
     * @param path       轮询端点路径
     * @param httpMethod HTTP方法（GET 或 POST）
     * @param body       请求体（仅 POST 时使用，可为 null）
     * @return 响应 Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> executePollRequest(PluginConfig config, String path,
                                                   String httpMethod, Object body) {
        WebClient webClient = getOrCreateClient(config);
        HttpHeaders headers = buildHeaders(config);
        String method = (httpMethod != null) ? httpMethod.toUpperCase() : "GET";

        log.debug("[PluginHttpClient] Poll request: method={}, path={}", method, path);

        try {
            HttpMethod resolvedMethod = HttpMethod.valueOf(method);

            WebClient.RequestBodySpec requestSpec = webClient
                .method(resolvedMethod)
                .uri(path)
                .headers(h -> h.addAll(headers));

            Mono<Map> responseMono;
            if (body != null && (resolvedMethod == HttpMethod.POST || resolvedMethod == HttpMethod.PUT)) {
                responseMono = requestSpec
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofMillis(config.getTimeout()));
            } else {
                responseMono = requestSpec
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofMillis(config.getTimeout()));
            }

            // 轮询专用弹性策略：仅熔断，不限流、不重试
            responseMono = applyResilienceDecoratorsForPolling(responseMono, config);

            return responseMono.block();
        } catch (WebClientResponseException e) {
            log.error("Poll request failed: method={}, path={}, status={}, body={}",
                method, path, e.getStatusCode(), e.getResponseBodyAsString());
            throw new PluginHttpException("Poll request failed: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.error("Poll request error: method={}, path={}, error={}", method, path, e.getMessage(), e);
            throw new PluginHttpException("Poll request error: " + e.getMessage(), e);
        }
    }

    /**
     * 发送回调通知（用于轮询完成后通知调用方）
     *
     * @param callbackUrl 回调URL
     * @param payload     回调数据
     */
    public void postCallback(String callbackUrl, Map<String, Object> payload) {
        postCallback(callbackUrl, payload, null, null, null);
    }

    public void postCallback(String callbackUrl, Map<String, Object> payload,
                             String userId, String workspaceId, String tenantSchema) {
        try {
            log.info("[PluginHttpClient] ========== 发送回调通知 ==========");
            log.info("[PluginHttpClient] 回调URL: {}", callbackUrl);
            log.debug("[PluginHttpClient] 回调数据: {}", payload);

            WebClient client = getDefaultClient();

            String responseBody = client.post()
                    .uri(callbackUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> applyInternalAuthHeaders(headers, userId, workspaceId, tenantSchema))
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();

            log.info("[PluginHttpClient] ========== 回调通知成功 ==========");
            log.debug("[PluginHttpClient] 回调响应: {}", responseBody);

        } catch (WebClientResponseException e) {
            log.error("[PluginHttpClient] 回调通知失败: status={}, body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new PluginHttpException("Callback failed: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.error("[PluginHttpClient] 回调通知异常: {}", e.getMessage(), e);
            throw new PluginHttpException("Callback error: " + e.getMessage(), e);
        }
    }

    private void applyInternalAuthHeaders(HttpHeaders headers,
                                          String userId,
                                          String workspaceId,
                                          String tenantSchema) {
        if (!internalAuthProperties.isConfigured()) {
            log.warn("[PluginHttpClient] Internal auth secret is not configured, callback will not carry internal token");
            return;
        }

        String internalToken = InternalAuthUtils.generateInternalToken(
                internalAuthProperties.getAuthSecret(),
                userId,
                workspaceId,
                tenantSchema,
                internalAuthProperties.getInternalTokenExpireSeconds()
        );
        headers.set(CommonConstants.HEADER_INTERNAL_AUTH_TOKEN, internalToken);
        if (StringUtils.hasText(userId)) {
            headers.set(CommonConstants.HEADER_USER_ID, userId);
        }
        if (StringUtils.hasText(workspaceId)) {
            headers.set(CommonConstants.HEADER_WORKSPACE_ID, workspaceId);
        }
        if (StringUtils.hasText(tenantSchema)) {
            headers.set(CommonConstants.HEADER_TENANT_SCHEMA, tenantSchema);
        }
    }

    /**
     * 获取默认的 WebClient（用于回调等不需要特定 baseUrl 的场景）
     */
    private WebClient getDefaultClient() {
        return clientCache.get("_default_", k -> {
            HttpClient httpClient = HttpClient.create(connectionProvider)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                    .responseTimeout(Duration.ofSeconds(30))
                    .doOnConnected(conn -> conn
                            .addHandlerLast(new ReadTimeoutHandler(30, TimeUnit.SECONDS))
                            .addHandlerLast(new WriteTimeoutHandler(DEFAULT_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)));

            return WebClient.builder()
                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                    .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(resolveMaxInMemorySize()))
                    .build();
        });
    }

    /**
     * 应用Resilience4j装饰器到Mono
     */
    private <T> Mono<T> applyResilienceDecorators(Mono<T> mono, PluginConfig config) {
        String providerId = config.getProviderId();

        // 0. 应用 Bulkhead（最内层，紧贴 HTTP 调用）：单 provider 并发上限，
        //    超额立即拒绝，让上层把请求塞回队列或返回 BUSY，绝不让线程僵死等待
        Bulkhead bulkhead = resilienceConfig.getOrCreateBulkhead(providerId);
        mono = mono.transformDeferred(BulkheadOperator.of(bulkhead));

        // 1. 应用限流（超时时间与提供商请求超时对齐，避免慢提供商误触熔断）
        if (config.getRateLimit() != null && config.getRateLimit() > 0) {
            int providerTimeout = config.getTimeout() != null ? config.getTimeout() : 60000;
            RateLimiter rateLimiter = resilienceConfig.getOrCreateRateLimiter(providerId, config.getRateLimit(), providerTimeout);
            mono = mono.transformDeferred(RateLimiterOperator.of(rateLimiter));
        }

        // 2. 应用熔断
        CircuitBreaker circuitBreaker = resilienceConfig.getOrCreateCircuitBreaker(providerId, config);
        mono = mono.transformDeferred(CircuitBreakerOperator.of(circuitBreaker));

        // 3. 应用重试 — 长耗时阻塞型（IMAGE/VIDEO/AUDIO）禁用重试。
        // 原因：单次失败被 Retry 放大成 3 次失败计入 CB，加速 OPEN；且生图/视频
        // 业务侧通常已有任务级重排，HTTP 层重试纯属浪费配额。
        int maxRetries = config.getMaxRetries() != null ? config.getMaxRetries() : 3;
        if (maxRetries > 1 && !isLongBlockingProvider(config)) {
            Retry retry = resilienceConfig.getOrCreateRetry(providerId, maxRetries, 1000L);
            mono = mono.transformDeferred(RetryOperator.of(retry));
        }

        return mono;
    }

    /**
     * 判断是否为长耗时阻塞型 provider（IMAGE / VIDEO / AUDIO 等生成类）。
     * 这类 provider 单次调用通常 30s–300s，HTTP 层重试无意义且会放大 CB 失败计数。
     */
    private boolean isLongBlockingProvider(PluginConfig config) {
        String type = config.getProviderType();
        if (!StringUtils.hasText(type)) {
            return false;
        }
        String upper = type.toUpperCase();
        return "IMAGE".equals(upper) || "VIDEO".equals(upper) || "AUDIO".equals(upper);
    }

    /**
     * 应用Resilience4j装饰器到Mono（轮询专用）
     * 不应用限流器，因为轮询请求已由轮询间隔和最大次数控制
     */
    private <T> Mono<T> applyResilienceDecoratorsForPolling(Mono<T> mono, PluginConfig config) {
        String providerId = config.getProviderId();

        // 轮询不应用限流，轮询频率已由 pollingConfig.intervalMs 控制

        // 应用熔断
        CircuitBreaker circuitBreaker = resilienceConfig.getOrCreateCircuitBreaker(providerId, config);
        mono = mono.transformDeferred(CircuitBreakerOperator.of(circuitBreaker));

        // 轮询请求不应用重试，失败由轮询器处理下一次尝试
        return mono;
    }

    /**
     * 应用Resilience4j装饰器到Flux（流式请求）
     * 注意：流式请求不应用重试，避免数据重复
     */
    private <T> Flux<T> applyResilienceDecoratorsForFlux(Flux<T> flux, PluginConfig config) {
        String providerId = config.getProviderId();

        // 1. 应用限流（超时时间与提供商请求超时对齐）
        if (config.getRateLimit() != null && config.getRateLimit() > 0) {
            int providerTimeout = config.getTimeout() != null ? config.getTimeout() : 60000;
            RateLimiter rateLimiter = resilienceConfig.getOrCreateRateLimiter(providerId, config.getRateLimit(), providerTimeout);
            flux = flux.transformDeferred(RateLimiterOperator.of(rateLimiter));
        }

        // 2. 应用熔断
        CircuitBreaker circuitBreaker = resilienceConfig.getOrCreateCircuitBreaker(providerId, config);
        flux = flux.transformDeferred(CircuitBreakerOperator.of(circuitBreaker));

        // 不应用重试，流式请求重试会导致数据重复

        return flux;
    }

    /**
     * 构建请求头
     */
    private HttpHeaders buildHeaders(PluginConfig config) {
        HttpHeaders headers = new HttpHeaders();

        // 应用认证
        if (StringUtils.hasText(config.getAuthType()) && config.getAuthConfig() != null) {
            AuthenticationStrategy strategy = authStrategyFactory.getStrategy(config.getAuthType());
            AuthConfig authConfig = mapToAuthConfig(config.getAuthConfig(), config.getAuthType());
            strategy.applyAuth(headers, authConfig);
        }

        // 添加自定义头
        if (config.getCustomHeaders() != null) {
            config.getCustomHeaders().forEach(headers::set);
        }

        return headers;
    }

    /**
     * 隐藏敏感请求头（用于日志输出）
     */
    private String maskSensitiveHeaders(HttpHeaders headers) {
        Map<String, String> maskedHeaders = new java.util.LinkedHashMap<>();
        headers.forEach((key, values) -> {
            String value = values.isEmpty() ? "" : values.get(0);
            // 隐藏敏感头信息
            if (key.toLowerCase().contains("authorization") ||
                key.toLowerCase().contains("api-key") ||
                key.toLowerCase().contains("apikey") ||
                key.toLowerCase().contains("secret") ||
                key.toLowerCase().contains("token") ||
                key.toLowerCase().contains("x-api-key")) {
                // 只显示前4位和后4位
                if (value.length() > 12) {
                    value = value.substring(0, 4) + "****" + value.substring(value.length() - 4);
                } else {
                    value = "****";
                }
            }
            maskedHeaders.put(key, value);
        });
        return maskedHeaders.toString();
    }

    /**
     * 获取或创建WebClient
     */
    private WebClient getOrCreateClient(PluginConfig config) {
        String cacheKey = config.getProviderId() + "_" + config.getBaseUrl();
        return clientCache.get(cacheKey, k -> createWebClient(config));
    }

    /**
     * 创建WebClient
     */
    private WebClient createWebClient(PluginConfig config) {
        int timeout = config.getTimeout() != null ? config.getTimeout() : readTimeoutSeconds * 1000;

        HttpClient httpClient = HttpClient.create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
            .responseTimeout(Duration.ofMillis(timeout))
            .doOnConnected(conn -> conn
                .addHandlerLast(new ReadTimeoutHandler(timeout, TimeUnit.MILLISECONDS))
                .addHandlerLast(new WriteTimeoutHandler(DEFAULT_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)));

        return WebClient.builder()
            .baseUrl(config.getBaseUrl())
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(resolveMaxInMemorySize()))
            .build();
    }

    /**
     * Map转AuthConfig
     */
    @SuppressWarnings("unchecked")
    private AuthConfig mapToAuthConfig(Map<String, Object> configMap, String authType) {
        AuthConfig.AuthConfigBuilder builder = AuthConfig.builder()
            .authType(authType);

        if (configMap.containsKey("apiKey")) {
            builder.apiKey((String) configMap.get("apiKey"));
        }
        if (configMap.containsKey("apiKeyHeader")) {
            builder.apiKeyHeader((String) configMap.get("apiKeyHeader"));
        }
        if (configMap.containsKey("apiKeyPrefix")) {
            builder.apiKeyPrefix((String) configMap.get("apiKeyPrefix"));
        }
        if (configMap.containsKey("accessKey")) {
            builder.accessKey((String) configMap.get("accessKey"));
        }
        if (configMap.containsKey("secretKey")) {
            builder.secretKey((String) configMap.get("secretKey"));
        }
        if (configMap.containsKey("bearerToken")) {
            builder.bearerToken((String) configMap.get("bearerToken"));
        }
        if (configMap.containsKey("customHeaders")) {
            builder.customHeaders((Map<String, String>) configMap.get("customHeaders"));
        }

        return builder.build();
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    /**
     * 解析响应体最大内存缓冲大小。
     * 优先从 RuntimeConfig 读取动态配置，失败时回退到编译期默认值。
     */
    private int resolveMaxInMemorySize() {
        try {
            int configured = aiRuntimeConfig.getHttpMaxInMemorySizeBytes();
            return configured > 0 ? configured : DEFAULT_MAX_IN_MEMORY_SIZE;
        } catch (Exception e) {
            return DEFAULT_MAX_IN_MEMORY_SIZE;
        }
    }

    /**
     * 清除客户端缓存
     */
    public void clearCache() {
        clientCache.invalidateAll();
    }

    /**
     * 清除指定提供商的客户端缓存
     */
    public void clearCache(String providerId) {
        clientCache.asMap().entrySet().removeIf(entry -> entry.getKey().startsWith(providerId + "_"));
        resilienceConfig.clearCache(providerId);
    }

    /**
     * 关闭HTTP客户端，释放资源
     */
    public void shutdown() {
        clientCache.invalidateAll();
        connectionProvider.dispose();
        resilienceConfig.clearAllCache();
        log.info("PluginHttpClient shutdown completed");
    }

    /**
     * 获取熔断器状态
     */
    public String getCircuitBreakerState(String providerId) {
        return resilienceConfig.getCircuitBreakerState(providerId);
    }

    /**
     * 获取限流器指标
     */
    public PluginResilienceConfig.RateLimiterMetrics getRateLimiterMetrics(String providerId) {
        return resilienceConfig.getRateLimiterMetrics(providerId);
    }

    /**
     * HTTP异常
     */
    public static class PluginHttpException extends RuntimeException {
        public PluginHttpException(String message) {
            super(message);
        }

        public PluginHttpException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
