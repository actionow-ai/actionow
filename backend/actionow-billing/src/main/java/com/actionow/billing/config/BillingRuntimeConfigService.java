package com.actionow.billing.config;

import com.actionow.common.redis.config.RuntimeConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Billing 模块运行时配置服务
 * 管理积分汇率、币种等可动态调整的业务参数
 *
 * @author Actionow
 */
@Slf4j
@Component
public class BillingRuntimeConfigService extends RuntimeConfigService {

    public static final String POINTS_PER_MAJOR_UNIT    = "runtime.billing.points_per_major_unit";
    public static final String MINOR_PER_MAJOR_UNIT     = "runtime.billing.minor_per_major_unit";
    public static final String SUBSCRIPTION_CURRENCY    = "runtime.billing.subscription_currency";
    public static final String SUPPORTED_CURRENCIES     = "runtime.billing.supported_currencies";
    public static final String ENABLED_PAYMENT_PROVIDERS = "runtime.billing.enabled_payment_providers";

    public BillingRuntimeConfigService(StringRedisTemplate redisTemplate,
                                        RedisMessageListenerContainer listenerContainer) {
        super(redisTemplate, listenerContainer);
    }

    @Override
    protected String getPrefix() {
        return "runtime.billing";
    }

    @Override
    protected void registerDefaults(Map<String, String> defaults) {
        defaults.put(POINTS_PER_MAJOR_UNIT, "10");
        defaults.put(MINOR_PER_MAJOR_UNIT, "100");
        defaults.put(SUBSCRIPTION_CURRENCY, "USD");
        defaults.put(SUPPORTED_CURRENCIES, "USD,CNY,EUR,GBP,JPY");
        defaults.put(ENABLED_PAYMENT_PROVIDERS, "STRIPE,WECHATPAY");
    }

    /**
     * 当前启用的支付渠道集合（管理员可在系统配置中调整）
     */
    public java.util.List<String> getEnabledPaymentProviders() {
        String raw = getString(ENABLED_PAYMENT_PROVIDERS);
        if (raw == null || raw.isBlank()) {
            return java.util.Collections.emptyList();
        }
        java.util.List<String> list = new java.util.ArrayList<>();
        for (String token : raw.split(",")) {
            String normalized = token.trim().toUpperCase();
            if (!normalized.isEmpty()) {
                list.add(normalized);
            }
        }
        return list;
    }

    public boolean isPaymentProviderEnabled(String provider) {
        if (provider == null || provider.isBlank()) {
            return false;
        }
        return getEnabledPaymentProviders().contains(provider.trim().toUpperCase());
    }

    // ==================== Named Getters ====================

    public long getPointsPerMajorUnit() {
        return getLong(POINTS_PER_MAJOR_UNIT);
    }

    public long getMinorPerMajorUnit() {
        return getLong(MINOR_PER_MAJOR_UNIT);
    }

    public String getSubscriptionCurrency() {
        return getString(SUBSCRIPTION_CURRENCY);
    }

    /**
     * 获取支持的币种白名单（逗号分隔的大写代码集合）。
     * 运营可通过 Redis 动态下发新币种，无需发版。
     */
    public java.util.Set<String> getSupportedCurrencies() {
        String raw = getString(SUPPORTED_CURRENCIES);
        if (raw == null || raw.isBlank()) {
            return java.util.Collections.emptySet();
        }
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String token : raw.split(",")) {
            String normalized = token.trim().toUpperCase();
            if (!normalized.isEmpty()) {
                set.add(normalized);
            }
        }
        return set;
    }

    /**
     * 判断某个币种是否在当前白名单内（大小写不敏感）。
     */
    public boolean isCurrencySupported(String currency) {
        if (currency == null || currency.isBlank()) {
            return false;
        }
        return getSupportedCurrencies().contains(currency.trim().toUpperCase());
    }
}
