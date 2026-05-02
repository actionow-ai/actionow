package com.actionow.common.redis.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 运行时参数动态配置抽象基类
 * 各模块继承此类实现自己的配置管理
 *
 * 工作原理：
 * 1. 启动时注册硬编码默认值 → 从 Redis 加载实际值覆盖
 * 2. 监听 Redis Pub/Sub "system:config:changed" 通道
 * 3. 收到匹配自身 prefix 的变更通知时，从 Redis 读取新值并更新本地缓存
 * 4. 子类可 override onConfigChanged() 执行副作用（如调整 Semaphore、重配 RateLimiter）
 *
 * 注意：此类不是 @Component，由各模块子类作为 @Component 继承
 *
 * @author Actionow
 */
@Slf4j
public abstract class RuntimeConfigService implements MessageListener {

    /**
     * Pub/Sub 通道名 — 必须与 actionow-system 的
     * SystemConstants.Channel.CONFIG_CHANGED 保持一致。
     * 由于本模块无法依赖 actionow-system，这里以字面量复制；
     * 修改时请同步两处。
     */
    public static final String CHANNEL = "system:config:changed";

    /**
     * Redis 缓存 key 前缀 — 必须与 actionow-system 的
     * SystemConstants.CacheKey.CONFIG_PREFIX + "GLOBAL:" 保持一致。
     * 当前消费者只支持 GLOBAL scope（已知限制，参见架构文档）。
     */
    public static final String CACHE_KEY_PREFIX = "system:config:GLOBAL:";

    /** 本地配置缓存（configKey → value） */
    private final ConcurrentHashMap<String, String> localCache = new ConcurrentHashMap<>();

    /** 编译时默认值（configKey → value），启动后不可变 */
    private final ConcurrentHashMap<String, String> defaults = new ConcurrentHashMap<>();

    /** 外部可注册的配置变更监听器（在 onConfigChanged 之后触发） */
    private final List<Consumer<String>> externalListeners = new CopyOnWriteArrayList<>();

    /**
     * 注册外部配置变更监听器。
     * 任意配置键变更后被回调，参数为变更的键。监听器实现需自行判断是否关心该键。
     * 用途：让依赖运行时配置的组件（如 PluginResilienceConfig）在配置变更时
     * 主动失效内部缓存，避免"改了 Redis 但旧 CircuitBreaker/Pool 仍在用旧值"。
     */
    public void addChangeListener(Consumer<String> listener) {
        externalListeners.add(listener);
    }

    protected final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;

    protected RuntimeConfigService(StringRedisTemplate redisTemplate,
                                    RedisMessageListenerContainer listenerContainer) {
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
    }

    /**
     * 子类实现：返回配置键前缀（如 "runtime.agent"）
     * 仅处理以此前缀开头的配置变更
     */
    protected abstract String getPrefix();

    /**
     * 子类实现：注册默认值
     * 在 @PostConstruct 中调用，提供编译时兜底
     */
    protected abstract void registerDefaults(Map<String, String> defaults);

    /**
     * 子类可 override：配置变更回调
     * 在本地缓存更新之后调用，可用于执行副作用
     *
     * @param key      变更的配置键
     * @param oldValue 旧值（可能为 null）
     * @param newValue 新值
     */
    protected void onConfigChanged(String key, String oldValue, String newValue) {
        // 默认无操作，子类按需 override
    }

    @PostConstruct
    public void init() {
        // 1. 注册默认值
        registerDefaults(defaults);
        // 将默认值复制到本地缓存作为初始值
        localCache.putAll(defaults);

        // 2. 从 Redis 加载实际值覆盖
        int loaded = loadFromRedis();

        // 3. 订阅 Pub/Sub
        listenerContainer.addMessageListener(this, new ChannelTopic(CHANNEL));

        // 启动时把覆盖率打出来：让"全部回退到编译期默认值"这件事不再静默
        int total = defaults.size();
        if (loaded == 0 && total > 0) {
            log.warn("[RuntimeConfig] {} initialized with NO Redis overrides (prefix={}, keys={}). " +
                            "All values will use compile-time defaults — Redis cache may be empty " +
                            "or system service has not yet refreshed it.",
                    getClass().getSimpleName(), getPrefix(), total);
        } else {
            log.info("[RuntimeConfig] {} initialized: prefix={}, total_keys={}, redis_overrides={}",
                    getClass().getSimpleName(), getPrefix(), total, loaded);
        }
    }

    /**
     * 从 Redis 加载所有匹配前缀的配置值
     *
     * @return 实际从 Redis 加载到的 key 数量（用于调用方判断覆盖率）
     */
    private int loadFromRedis() {
        int loaded = 0;
        for (String key : defaults.keySet()) {
            try {
                String redisKey = CACHE_KEY_PREFIX + key;
                String value = redisTemplate.opsForValue().get(redisKey);
                if (value != null) {
                    localCache.put(key, value);
                    loaded++;
                    log.debug("[RuntimeConfig] Loaded from Redis: {}={}", key, value);
                }
            } catch (Exception e) {
                log.warn("[RuntimeConfig] Failed to load from Redis: key={}, using default. error={}",
                        key, e.getMessage());
            }
        }
        return loaded;
    }

    /**
     * Redis Pub/Sub 消息回调
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String configKey = new String(message.getBody());
            // 仅处理本服务关注的配置键（prefix 匹配或已注册默认值）
            if (!configKey.startsWith(getPrefix()) && !defaults.containsKey(configKey)) {
                return;
            }

            // 从 Redis 读取最新值
            String redisKey = CACHE_KEY_PREFIX + configKey;
            String newValue = redisTemplate.opsForValue().get(redisKey);
            String oldValue = localCache.get(configKey);

            if (newValue != null) {
                localCache.put(configKey, newValue);
            } else {
                // 配置被删除，回退到默认值
                String defaultValue = defaults.get(configKey);
                if (defaultValue != null) {
                    localCache.put(configKey, defaultValue);
                    newValue = defaultValue;
                }
            }

            log.info("[RuntimeConfig] Config changed: key={}, oldValue={}, newValue={}",
                    configKey, oldValue, newValue);

            onConfigChanged(configKey, oldValue, newValue);

            for (Consumer<String> listener : externalListeners) {
                try {
                    listener.accept(configKey);
                } catch (Exception ex) {
                    log.warn("[RuntimeConfig] external listener failed for key={}: {}", configKey, ex.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("[RuntimeConfig] Failed to process config change: {}", e.getMessage(), e);
        }
    }

    // ==================== 类型安全 Getter ====================

    public String getString(String key) {
        return localCache.getOrDefault(key, defaults.get(key));
    }

    public int getInt(String key) {
        String value = getString(key);
        if (value == null) {
            throw new IllegalStateException("Runtime config key not found: " + key);
        }
        return Integer.parseInt(value);
    }

    public long getLong(String key) {
        String value = getString(key);
        if (value == null) {
            throw new IllegalStateException("Runtime config key not found: " + key);
        }
        return Long.parseLong(value);
    }

    public boolean getBoolean(String key) {
        String value = getString(key);
        return Boolean.parseBoolean(value);
    }

    public float getFloat(String key) {
        String value = getString(key);
        if (value == null) {
            throw new IllegalStateException("Runtime config key not found: " + key);
        }
        return Float.parseFloat(value);
    }
}
