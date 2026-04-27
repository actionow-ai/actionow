-- =====================================================
-- Actionow 平台数据库初始化脚本
-- 01-core-schema.sql - 核心 DDL（扩展 / 公共 Schema / 租户函数 / 计费 / 认证）
-- PostgreSQL 16+
-- =====================================================

-- =====================================================
-- 1. PostgreSQL 扩展与公共函数
-- =====================================================


-- 启用必要扩展
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "vector";  -- pgvector for RAG
-- pg_uuidv7: not bundled in pgvector/pgvector:pg16; UUIDv7 generation is
-- handled in application layer (Java) so this extension is not needed.
-- CREATE EXTENSION IF NOT EXISTS "pg_uuidv7";

-- =====================================================
-- 公共触发器函数
-- =====================================================

-- 自动更新 updated_at 触发器函数
CREATE OR REPLACE FUNCTION trigger_set_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
BEGIN
    RAISE NOTICE 'PostgreSQL extensions and functions initialized successfully!';
END $$;

-- =====================================================
-- 2. Public Schema 表结构
-- =====================================================


-- =====================================================
-- 1. 用户表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(50) UNIQUE NOT NULL,
    nickname VARCHAR(100),
    avatar VARCHAR(500),
    email VARCHAR(255) UNIQUE,
    phone VARCHAR(20) UNIQUE,
    password VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'BANNED', 'INACTIVE')),
    login_fail_count INTEGER DEFAULT 0,
    locked_until TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    last_login_ip VARCHAR(50),
    email_verified BOOLEAN DEFAULT FALSE,
    phone_verified BOOLEAN DEFAULT FALSE,
    extra_info JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0
);

COMMENT ON TABLE t_user IS '用户表';
COMMENT ON COLUMN t_user.status IS '状态: ACTIVE-正常, BANNED-已禁用, INACTIVE-未激活';
COMMENT ON COLUMN t_user.deleted IS '软删除: 0-正常, 1-已删除';
COMMENT ON COLUMN t_user.extra_info IS '扩展信息：bio, location, company, website, preferences等';

-- 邀请人相关字段
ALTER TABLE t_user ADD COLUMN IF NOT EXISTS invited_by UUID;
ALTER TABLE t_user ADD COLUMN IF NOT EXISTS invitation_code_used VARCHAR(32);
COMMENT ON COLUMN t_user.invited_by IS '邀请人用户ID';
COMMENT ON COLUMN t_user.invitation_code_used IS '注册时使用的邀请码';

CREATE INDEX IF NOT EXISTS idx_t_user_email ON t_user(email) WHERE email IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_t_user_phone ON t_user(phone) WHERE phone IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_t_user_username ON t_user(username) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_user_status ON t_user(status) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_user_invited_by ON t_user(invited_by) WHERE invited_by IS NOT NULL;

-- =====================================================
-- 2. 用户OAuth绑定表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_user_oauth (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES t_user(id) ON DELETE CASCADE,
    provider VARCHAR(50) NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    union_id VARCHAR(255),
    provider_username VARCHAR(255),
    provider_email VARCHAR(255),
    provider_avatar VARCHAR(500),
    access_token TEXT,
    refresh_token TEXT,
    expires_in INTEGER,
    token_expires_at TIMESTAMPTZ,
    extra_info JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE (provider, provider_user_id)
);

COMMENT ON TABLE t_user_oauth IS '用户OAuth绑定表';
COMMENT ON COLUMN t_user_oauth.provider IS '提供商: wechat, github, google, apple';
COMMENT ON COLUMN t_user_oauth.provider_user_id IS 'OAuth提供商的用户ID (OpenID)';
COMMENT ON COLUMN t_user_oauth.extra_info IS '第三方返回的原始用户信息';

CREATE INDEX IF NOT EXISTS idx_t_user_oauth_user_id ON t_user_oauth(user_id) WHERE deleted = 0;

-- =====================================================
-- 3. 工作空间表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_workspace (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(100) UNIQUE NOT NULL,
    description TEXT,
    logo_url VARCHAR(500),
    owner_id UUID NOT NULL REFERENCES t_user(id),
    schema_name VARCHAR(63) UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    plan_type VARCHAR(50) DEFAULT 'Free',
    max_members INTEGER DEFAULT 5,
    member_count INTEGER DEFAULT 0,
    config JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted_at TIMESTAMPTZ,
    deleted INTEGER NOT NULL DEFAULT 0,
    version INTEGER NOT NULL DEFAULT 0
);

COMMENT ON TABLE t_workspace IS '工作空间表';
COMMENT ON COLUMN t_workspace.slug IS 'URL友好标识，唯一';
COMMENT ON COLUMN t_workspace.schema_name IS '租户Schema名称，格式: tenant_{id前8位}_{时间戳}';
COMMENT ON COLUMN t_workspace.status IS '状态: ACTIVE, SUSPENDED, DELETED';
COMMENT ON COLUMN t_workspace.plan_type IS '订阅计划: Free, Basic, Pro, Enterprise';
COMMENT ON COLUMN t_workspace.config IS '空间级配置（JSONB）';
COMMENT ON COLUMN t_workspace.deleted IS '软删除: 0-正常, 1-已删除';

CREATE INDEX IF NOT EXISTS idx_t_workspace_owner_id ON t_workspace(owner_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_workspace_status ON t_workspace(status) WHERE deleted = 0;

-- =====================================================
-- 3a. 订阅计划表
-- 定义平台可用的订阅方案 (Free, Pro, Enterprise 等)
-- =====================================================

CREATE TABLE IF NOT EXISTS t_subscription_plan (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    code VARCHAR(50) NOT NULL UNIQUE,
    description TEXT,
    plan_type VARCHAR(50) NOT NULL,
    billing_cycle VARCHAR(20) NOT NULL DEFAULT 'MONTHLY' CHECK (billing_cycle IN ('MONTHLY', 'YEARLY', 'LIFETIME')),
    price_cents BIGINT NOT NULL DEFAULT 0,
    currency VARCHAR(10) NOT NULL DEFAULT 'CNY',
    -- 配额限制
    max_members INTEGER NOT NULL DEFAULT 1,
    max_scripts INTEGER NOT NULL DEFAULT 5,
    max_storage_mb BIGINT NOT NULL DEFAULT 1024,
    max_ai_credits_monthly BIGINT NOT NULL DEFAULT 0,
    -- 功能开关
    features JSONB NOT NULL DEFAULT '{}',
    -- 排序与状态
    sort_order INTEGER DEFAULT 0,
    is_public BOOLEAN DEFAULT TRUE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE', 'DEPRECATED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0
);

COMMENT ON TABLE t_subscription_plan IS '订阅计划表 - 定义平台可用的订阅方案';
COMMENT ON COLUMN t_subscription_plan.code IS '计划编码: FREE, PRO, ENTERPRISE';
COMMENT ON COLUMN t_subscription_plan.plan_type IS '计划类型: FREE, BASIC, PRO, ENTERPRISE';
COMMENT ON COLUMN t_subscription_plan.features IS '功能开关 JSON: {exportPdf: true, aiAssist: true, ...}';

CREATE INDEX IF NOT EXISTS idx_t_subscription_plan_code ON t_subscription_plan(code) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_subscription_plan_status ON t_subscription_plan(status) WHERE deleted = 0;

-- =====================================================
-- 3b. 工作空间订阅表
-- 记录工作空间当前生效的订阅
-- =====================================================

CREATE TABLE IF NOT EXISTS t_subscription (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES t_workspace(id) ON DELETE CASCADE,
    plan_id UUID NOT NULL REFERENCES t_subscription_plan(id),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'TRIALING', 'PAST_DUE', 'CANCELED', 'EXPIRED')),
    -- 周期
    current_period_start TIMESTAMPTZ NOT NULL,
    current_period_end TIMESTAMPTZ NOT NULL,
    trial_end TIMESTAMPTZ,
    -- 取消
    cancel_at_period_end BOOLEAN DEFAULT FALSE,
    canceled_at TIMESTAMPTZ,
    -- 关联支付
    subscription_contract_id UUID,
    -- 审计
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0
);

COMMENT ON TABLE t_subscription IS '工作空间订阅表 - 记录工作空间当前生效的订阅';
COMMENT ON COLUMN t_subscription.subscription_contract_id IS '关联 t_subscription_contract 支付合约';

CREATE UNIQUE INDEX IF NOT EXISTS uk_t_subscription_workspace_active
    ON t_subscription(workspace_id) WHERE deleted = 0 AND status IN ('ACTIVE', 'TRIALING');
CREATE INDEX IF NOT EXISTS idx_t_subscription_plan_id ON t_subscription(plan_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_subscription_period_end ON t_subscription(current_period_end) WHERE deleted = 0;

-- =====================================================
-- 3c. 支付历史表
-- 面向用户的支付流水记录
-- =====================================================

CREATE TABLE IF NOT EXISTS t_payment_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES t_workspace(id) ON DELETE CASCADE,
    subscription_id UUID REFERENCES t_subscription(id),
    -- 支付信息
    payment_type VARCHAR(50) NOT NULL,
    amount_cents BIGINT NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'CNY',
    payment_method VARCHAR(50),
    -- 状态
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'REFUNDED')),
    -- 关联支付订单
    payment_order_id UUID,
    -- 描述
    description VARCHAR(500),
    meta JSONB DEFAULT '{}',
    -- 时间
    paid_at TIMESTAMPTZ,
    refunded_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0
);

COMMENT ON TABLE t_payment_history IS '支付历史表 - 面向用户的支付流水记录';
COMMENT ON COLUMN t_payment_history.payment_type IS '支付类型: SUBSCRIPTION, TOPUP, ADDON';
COMMENT ON COLUMN t_payment_history.payment_order_id IS '关联 t_payment_order 支付订单';

CREATE INDEX IF NOT EXISTS idx_t_payment_history_workspace ON t_payment_history(workspace_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_payment_history_subscription ON t_payment_history(subscription_id) WHERE deleted = 0 AND subscription_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_t_payment_history_status ON t_payment_history(status) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_payment_history_created_at ON t_payment_history(created_at DESC) WHERE deleted = 0;

-- =====================================================
-- 4. 工作空间成员表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_workspace_member (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES t_workspace(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES t_user(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE', 'INVITED')),
    nickname VARCHAR(100),
    invited_by UUID,
    joined_at TIMESTAMPTZ DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE (workspace_id, user_id)
);

COMMENT ON TABLE t_workspace_member IS '工作空间成员表';
COMMENT ON COLUMN t_workspace_member.role IS '角色: CREATOR, ADMIN, MEMBER, GUEST';
COMMENT ON COLUMN t_workspace_member.status IS '状态: ACTIVE, INACTIVE, INVITED';

CREATE INDEX IF NOT EXISTS idx_t_workspace_member_workspace_id ON t_workspace_member(workspace_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_workspace_member_user_id ON t_workspace_member(user_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_workspace_member_status ON t_workspace_member(workspace_id, status) WHERE deleted = 0;

-- =====================================================
-- 5. 工作空间邀请表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_workspace_invitation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES t_workspace(id) ON DELETE CASCADE,
    code VARCHAR(20) UNIQUE NOT NULL,
    inviter_id UUID NOT NULL REFERENCES t_user(id),
    invitee_email VARCHAR(255),
    role VARCHAR(20) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    max_uses INTEGER DEFAULT 1,
    used_count INTEGER DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0
);

COMMENT ON TABLE t_workspace_invitation IS '工作空间邀请表';
COMMENT ON COLUMN t_workspace_invitation.code IS '邀请码';
COMMENT ON COLUMN t_workspace_invitation.status IS '状态: ACTIVE-有效, DISABLED-禁用';

CREATE INDEX IF NOT EXISTS idx_t_workspace_invitation_code ON t_workspace_invitation(code) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_t_workspace_invitation_workspace_id ON t_workspace_invitation(workspace_id) WHERE deleted = 0;

-- =====================================================
-- 6. 工作空间钱包表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_workspace_wallet (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID UNIQUE NOT NULL REFERENCES t_workspace(id) ON DELETE CASCADE,
    balance BIGINT DEFAULT 0 CHECK (balance >= 0),
    frozen BIGINT DEFAULT 0 CHECK (frozen >= 0),
    total_recharged BIGINT DEFAULT 0,
    total_consumed BIGINT DEFAULT 0,
    status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ
);

COMMENT ON TABLE t_workspace_wallet IS '工作空间钱包表';
COMMENT ON COLUMN t_workspace_wallet.status IS '钱包状态: ACTIVE=正常, FROZEN=冻结, CLOSED=已关闭';
COMMENT ON COLUMN t_workspace_wallet.version IS '乐观锁版本号';

-- =====================================================
-- 7. 成员配额表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_member_quota (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES t_workspace(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES t_user(id) ON DELETE CASCADE,
    limit_amount BIGINT DEFAULT 1000,
    used_amount BIGINT DEFAULT 0,
    reset_cycle VARCHAR(20) DEFAULT 'MONTHLY',
    last_reset_at TIMESTAMPTZ DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE (workspace_id, user_id)
);

COMMENT ON TABLE t_member_quota IS '成员配额表';
COMMENT ON COLUMN t_member_quota.limit_amount IS '-1 表示无限制';
COMMENT ON COLUMN t_member_quota.reset_cycle IS '重置周期: DAILY, WEEKLY, MONTHLY, NEVER';

CREATE INDEX IF NOT EXISTS idx_t_member_quota_workspace_id ON t_member_quota(workspace_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_member_quota_user_id ON t_member_quota(user_id) WHERE deleted = 0;

-- =====================================================
-- 8. 积分流水表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_point_transaction (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL,
    user_id UUID,
    operator_id VARCHAR(64),
    transaction_type VARCHAR(50) NOT NULL,
    amount BIGINT NOT NULL,
    balance_before BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    description TEXT,
    related_task_id UUID,
    meta JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE IF NOT EXISTS t_point_transaction_default PARTITION OF t_point_transaction DEFAULT;

COMMENT ON TABLE t_point_transaction IS '积分流水表';
COMMENT ON COLUMN t_point_transaction.transaction_type IS '交易类型: TOPUP, CONSUME, REFUND, TRANSFER, FREEZE, UNFREEZE';

CREATE INDEX IF NOT EXISTS idx_t_point_transaction_workspace_id ON t_point_transaction(workspace_id);
CREATE INDEX IF NOT EXISTS idx_t_point_transaction_user_id ON t_point_transaction(user_id);
CREATE INDEX IF NOT EXISTS idx_t_point_transaction_created_at ON t_point_transaction(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_t_point_transaction_type_time ON t_point_transaction(transaction_type, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_t_point_transaction_task ON t_point_transaction(related_task_id) WHERE related_task_id IS NOT NULL;

-- =====================================================
-- 9. 冻结流水表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_frozen_transaction (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES t_workspace(id) ON DELETE CASCADE,
    user_id UUID REFERENCES t_user(id),
    amount BIGINT NOT NULL CHECK (amount > 0),
    reason VARCHAR(200),
    related_task_id UUID,
    status VARCHAR(20) NOT NULL DEFAULT 'FROZEN' CHECK (status IN ('FROZEN', 'UNFROZEN', 'CONSUMED', 'EXPIRED')),
    expires_at TIMESTAMPTZ,
    unfrozen_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE t_frozen_transaction IS '冻结流水表';
COMMENT ON COLUMN t_frozen_transaction.status IS '状态: FROZEN, UNFROZEN, CONSUMED, EXPIRED';

CREATE INDEX IF NOT EXISTS idx_t_frozen_transaction_workspace_id ON t_frozen_transaction(workspace_id);
CREATE INDEX IF NOT EXISTS idx_t_frozen_transaction_status ON t_frozen_transaction(status);
CREATE INDEX IF NOT EXISTS idx_t_frozen_transaction_expires ON t_frozen_transaction(expires_at) WHERE status = 'FROZEN' AND expires_at IS NOT NULL;

-- =====================================================
-- 10. 系统配置表
-- 支持多租户、多作用域的配置管理
-- =====================================================

CREATE TABLE IF NOT EXISTS t_system_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    config_key VARCHAR(100) NOT NULL,
    config_value TEXT NOT NULL,
    config_type VARCHAR(50),
    scope VARCHAR(20) DEFAULT 'GLOBAL',
    scope_id UUID,
    description TEXT,
    default_value TEXT,
    value_type VARCHAR(20) DEFAULT 'STRING',
    validation JSONB,
    enabled BOOLEAN DEFAULT TRUE,
    sensitive BOOLEAN DEFAULT FALSE,
    module VARCHAR(30) DEFAULT 'system',
    group_name VARCHAR(50),
    display_name VARCHAR(100),
    sort_order INTEGER DEFAULT 0,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0
);

-- 部分唯一索引：仅对未删除记录生效，使用 COALESCE 处理 NULL 值
CREATE UNIQUE INDEX IF NOT EXISTS uk_system_config_key ON t_system_config(config_key, scope, COALESCE(scope_id, '00000000-0000-0000-0000-000000000000'::UUID)) WHERE deleted = 0;

COMMENT ON TABLE t_system_config IS '系统配置表';
COMMENT ON COLUMN t_system_config.config_key IS '配置键';
COMMENT ON COLUMN t_system_config.config_value IS '配置值';
COMMENT ON COLUMN t_system_config.config_type IS '配置类型（SYSTEM/FEATURE/LIMIT等）';
COMMENT ON COLUMN t_system_config.scope IS '作用域（GLOBAL/WORKSPACE/USER）';
COMMENT ON COLUMN t_system_config.scope_id IS '作用域ID（工作空间ID或用户ID）';
COMMENT ON COLUMN t_system_config.value_type IS '值类型（STRING/INTEGER/BOOLEAN/JSON）';
COMMENT ON COLUMN t_system_config.validation IS '验证规则（JSON Schema）';
COMMENT ON COLUMN t_system_config.enabled IS '是否启用';
COMMENT ON COLUMN t_system_config.sensitive IS '是否为敏感配置（API Key等，返回时掩码）';
COMMENT ON COLUMN t_system_config.module IS '所属模块（user/agent/task/ai/gateway/project/billing/canvas/mq/system）';
COMMENT ON COLUMN t_system_config.group_name IS '分组名（同一模块内的子分类，如 timeout/concurrency/cache）';
COMMENT ON COLUMN t_system_config.display_name IS '前端展示名（人类可读的配置名称）';
COMMENT ON COLUMN t_system_config.sort_order IS '排序序号';

CREATE INDEX IF NOT EXISTS idx_t_system_config_key ON t_system_config(config_key) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_system_config_scope ON t_system_config(scope, scope_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_system_config_type ON t_system_config(config_type) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_system_config_module ON t_system_config(module, group_name) WHERE deleted = 0;

-- =====================================================
-- 11. 模型提供商配置表（瘦身后）
-- 通用的AI模型提供商配置，支持多种插件类型
-- =====================================================

CREATE TABLE IF NOT EXISTS t_model_provider (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    description TEXT,

    -- 插件标识
    plugin_id VARCHAR(100) NOT NULL,
    plugin_type VARCHAR(50) NOT NULL,
    provider_type VARCHAR(50) NOT NULL,

    -- 连接配置
    base_url VARCHAR(500) NOT NULL,
    endpoint VARCHAR(500),
    http_method VARCHAR(10) DEFAULT 'POST',

    -- 认证配置
    auth_type VARCHAR(50) NOT NULL,
    auth_config JSONB DEFAULT '{}',
    api_key_ref  VARCHAR(100),
    base_url_ref VARCHAR(100),

    -- 响应模式支持（合并 4 个布尔列）
    supported_modes JSONB NOT NULL DEFAULT '["BLOCKING"]',
    callback_config JSONB DEFAULT '{}',
    polling_config JSONB DEFAULT '{}',

    -- 限流和配额
    credit_cost BIGINT DEFAULT 0,
    rate_limit INTEGER DEFAULT 60,
    timeout INTEGER DEFAULT 60000,
    max_retries INTEGER DEFAULT 3,

    -- 元数据
    icon_url VARCHAR(500),
    priority INTEGER DEFAULT 0,
    enabled BOOLEAN DEFAULT TRUE,
    custom_headers JSONB DEFAULT '{}',

    -- TEXT 类型专属配置（合并为 JSONB）
    text_config JSONB,

    -- 审计字段
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0
);

COMMENT ON TABLE t_model_provider IS '模型提供商配置表（瘦身版）';
COMMENT ON COLUMN t_model_provider.plugin_id IS '插件ID: groovy, generic-http等';
COMMENT ON COLUMN t_model_provider.plugin_type IS '插件类型: GROOVY, GENERIC_HTTP';
COMMENT ON COLUMN t_model_provider.provider_type IS '提供商类型: IMAGE, VIDEO, AUDIO, TEXT';
COMMENT ON COLUMN t_model_provider.auth_type IS '认证类型: API_KEY, AK_SK, BEARER, OAUTH2, CUSTOM, NONE';
COMMENT ON COLUMN t_model_provider.auth_config IS '认证配置(加密存储)';
COMMENT ON COLUMN t_model_provider.api_key_ref IS '引用 t_system_config.config_key，运行时解析 API Key';
COMMENT ON COLUMN t_model_provider.base_url_ref IS '引用 t_system_config.config_key，运行时解析 Base URL';
COMMENT ON COLUMN t_model_provider.supported_modes IS '响应模式: ["BLOCKING","STREAMING","CALLBACK","POLLING"]';
COMMENT ON COLUMN t_model_provider.callback_config IS '回调配置';
COMMENT ON COLUMN t_model_provider.polling_config IS '轮询配置(interval, endpoint, statusPath)';
COMMENT ON COLUMN t_model_provider.text_config IS 'TEXT类型专属配置: {llmProviderId, systemPrompt, responseSchema, multimodalConfig}';

CREATE INDEX IF NOT EXISTS idx_t_model_provider_plugin ON t_model_provider(plugin_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_model_provider_type ON t_model_provider(provider_type) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_model_provider_enabled ON t_model_provider(enabled) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_model_provider_priority ON t_model_provider(provider_type, priority DESC) WHERE deleted = 0 AND enabled = TRUE;

-- =====================================================
-- 11.1 模型提供商脚本表（从 t_model_provider 拆出）
-- =====================================================

CREATE TABLE IF NOT EXISTS t_model_provider_script (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id UUID NOT NULL REFERENCES t_model_provider(id) ON DELETE CASCADE,

    -- Groovy 脚本
    request_builder_script TEXT,
    response_mapper_script TEXT,
    custom_logic_script TEXT,
    request_builder_template_id UUID,
    response_mapper_template_id UUID,
    custom_logic_template_id UUID,

    -- 动态定价
    pricing_rules JSONB,
    pricing_script TEXT,

    -- 审计
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0
);

COMMENT ON TABLE t_model_provider_script IS '模型提供商脚本表 - Groovy脚本与定价规则';
COMMENT ON COLUMN t_model_provider_script.request_builder_script IS '请求构建Groovy脚本（内联）';
COMMENT ON COLUMN t_model_provider_script.response_mapper_script IS '响应映射Groovy脚本（内联）';
COMMENT ON COLUMN t_model_provider_script.custom_logic_script IS '自定义逻辑Groovy脚本（内联）';
COMMENT ON COLUMN t_model_provider_script.pricing_rules IS '动态积分计算规则(JSON)，优先级低于pricing_script';
COMMENT ON COLUMN t_model_provider_script.pricing_script IS '动态积分计算Groovy脚本，优先级最高';

CREATE UNIQUE INDEX IF NOT EXISTS uk_model_provider_script_provider ON t_model_provider_script(provider_id) WHERE deleted = 0;

-- =====================================================
-- 11.2 模型提供商 I/O Schema 表（从 t_model_provider 拆出）
-- =====================================================

CREATE TABLE IF NOT EXISTS t_model_provider_schema (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id UUID NOT NULL REFERENCES t_model_provider(id) ON DELETE CASCADE,

    -- 输入/输出 Schema
    input_schema JSONB DEFAULT '[]',
    input_groups JSONB DEFAULT '[]',
    exclusive_groups JSONB DEFAULT '[]',
    output_schema JSONB DEFAULT '[]',

    -- 审计
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0
);

COMMENT ON TABLE t_model_provider_schema IS '模型提供商 I/O Schema 定义';
COMMENT ON COLUMN t_model_provider_schema.input_schema IS '输入参数定义列表';
COMMENT ON COLUMN t_model_provider_schema.input_groups IS '输入参数分组列表';
COMMENT ON COLUMN t_model_provider_schema.exclusive_groups IS '互斥参数组列表';
COMMENT ON COLUMN t_model_provider_schema.output_schema IS '输出参数定义列表';

CREATE UNIQUE INDEX IF NOT EXISTS uk_model_provider_schema_provider ON t_model_provider_schema(provider_id) WHERE deleted = 0;

-- =====================================================
-- 12. 模型提供商执行记录表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_model_provider_execution (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    task_id UUID,
    user_id UUID,

    -- 插件信息
    plugin_id VARCHAR(100) NOT NULL,
    provider_name VARCHAR(200),

    -- 外部引用
    external_task_id VARCHAR(200),
    external_run_id VARCHAR(200),

    -- 请求/响应数据
    input_data JSONB DEFAULT '{}',
    output_data JSONB DEFAULT '{}',

    -- 执行状态
    response_mode VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    error_code VARCHAR(50),
    error_message TEXT,

    -- 指标
    credit_cost BIGINT DEFAULT 0,
    elapsed_time INTEGER,
    total_tokens INTEGER,

    -- 回调/轮询追踪
    callback_received BOOLEAN DEFAULT FALSE,
    callback_received_at TIMESTAMPTZ,
    poll_count INTEGER DEFAULT 0,
    last_polled_at TIMESTAMPTZ,

    -- 时间戳
    submitted_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 1
);

COMMENT ON TABLE t_model_provider_execution IS '模型提供商执行记录表';
COMMENT ON COLUMN t_model_provider_execution.response_mode IS '响应模式: BLOCKING, STREAMING, CALLBACK, POLLING';
COMMENT ON COLUMN t_model_provider_execution.status IS '执行状态: PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED';
COMMENT ON COLUMN t_model_provider_execution.external_task_id IS '第三方服务返回的任务ID';
COMMENT ON COLUMN t_model_provider_execution.external_run_id IS '第三方服务返回的运行ID';

CREATE INDEX IF NOT EXISTS idx_t_provider_execution_provider ON t_model_provider_execution(provider_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_provider_execution_workspace ON t_model_provider_execution(workspace_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_provider_execution_task ON t_model_provider_execution(task_id) WHERE task_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_t_provider_execution_status ON t_model_provider_execution(status) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_provider_execution_external ON t_model_provider_execution(external_task_id) WHERE external_task_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_t_provider_execution_callback ON t_model_provider_execution(callback_received, status) WHERE callback_received = FALSE AND status IN ('PENDING', 'RUNNING');

-- =====================================================
-- 14. 数据字典类型表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_dict_type (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type_code VARCHAR(100) UNIQUE NOT NULL,
    type_name VARCHAR(200) NOT NULL,
    description TEXT,
    is_system BOOLEAN DEFAULT FALSE,
    enabled BOOLEAN DEFAULT TRUE,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0
);

COMMENT ON TABLE t_dict_type IS '数据字典类型表';
COMMENT ON COLUMN t_dict_type.is_system IS '是否系统内置';

CREATE INDEX IF NOT EXISTS idx_t_dict_type_code ON t_dict_type(type_code) WHERE deleted = 0;

-- =====================================================
-- 15. 数据字典项表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_dict_item (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type_id UUID NOT NULL REFERENCES t_dict_type(id) ON DELETE CASCADE,
    type_code VARCHAR(100) NOT NULL,
    item_code VARCHAR(100) NOT NULL,
    item_name VARCHAR(200) NOT NULL,
    item_value VARCHAR(500),
    extra JSONB DEFAULT '{}',
    is_default BOOLEAN DEFAULT FALSE,
    enabled BOOLEAN DEFAULT TRUE,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE (type_code, item_code)
);

COMMENT ON TABLE t_dict_item IS '数据字典项表';

CREATE INDEX IF NOT EXISTS idx_t_dict_item_type ON t_dict_item(type_code) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_dict_item_type_id ON t_dict_item(type_id) WHERE deleted = 0;

-- =====================================================
-- 16. 平台统计表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_platform_stats (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stats_date DATE NOT NULL,
    period VARCHAR(20) NOT NULL,
    metric_type VARCHAR(50) NOT NULL,
    workspace_id UUID,
    metric_value BIGINT DEFAULT 0,
    details JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE t_platform_stats IS '平台统计表';
COMMENT ON COLUMN t_platform_stats.period IS '统计周期: DAILY, WEEKLY, MONTHLY';
COMMENT ON COLUMN t_platform_stats.metric_type IS '指标类型: USER_COUNT, WORKSPACE_COUNT, TASK_COUNT等';

CREATE INDEX IF NOT EXISTS idx_t_platform_stats_date ON t_platform_stats(stats_date DESC);
CREATE INDEX IF NOT EXISTS idx_t_platform_stats_metric ON t_platform_stats(metric_type, period);
CREATE INDEX IF NOT EXISTS idx_t_platform_stats_workspace ON t_platform_stats(workspace_id) WHERE workspace_id IS NOT NULL;

-- =====================================================
-- 17. Groovy 脚本模板表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_groovy_template (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    description TEXT,

    -- 模板分类
    template_type VARCHAR(50) NOT NULL,    -- REQUEST_BUILDER, RESPONSE_MAPPER, CUSTOM_LOGIC
    generation_type VARCHAR(50),            -- IMAGE, VIDEO, AUDIO, TEXT, ALL

    -- 脚本内容
    script_content TEXT NOT NULL,
    script_version VARCHAR(20) DEFAULT '1.0.0',

    -- 系统模板标识（只读）
    is_system BOOLEAN DEFAULT FALSE,

    -- 示例和文档
    example_input JSONB,
    example_output JSONB,
    documentation TEXT,

    -- 状态
    enabled BOOLEAN DEFAULT TRUE,

    -- 审计字段
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0
);

COMMENT ON TABLE t_groovy_template IS 'Groovy脚本模板表';
COMMENT ON COLUMN t_groovy_template.template_type IS '模板类型: REQUEST_BUILDER, RESPONSE_MAPPER, CUSTOM_LOGIC';
COMMENT ON COLUMN t_groovy_template.generation_type IS '生成类型: IMAGE, VIDEO, AUDIO, TEXT, ALL';
COMMENT ON COLUMN t_groovy_template.is_system IS '是否为系统模板（只读）';

CREATE INDEX IF NOT EXISTS idx_groovy_template_type ON t_groovy_template(template_type) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_groovy_template_gen_type ON t_groovy_template(generation_type) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_groovy_template_system ON t_groovy_template(is_system) WHERE deleted = 0;

-- 确保系统模板不会被意外修改
CREATE OR REPLACE FUNCTION protect_system_groovy_template()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.is_system = TRUE THEN
        RAISE EXCEPTION 'System templates cannot be modified or deleted';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_protect_system_groovy_template ON t_groovy_template;
CREATE TRIGGER trigger_protect_system_groovy_template
    BEFORE UPDATE OR DELETE ON t_groovy_template
    FOR EACH ROW
    EXECUTE FUNCTION protect_system_groovy_template();

-- =====================================================
-- 18. 任务表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_task (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL,
    creator_id UUID NOT NULL,
    task_type VARCHAR(50) NOT NULL,
    title VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    -- 实体上下文
    script_id UUID,                          -- 所属剧本ID
    entity_id UUID,                          -- 目标实体ID
    entity_type VARCHAR(50),                        -- 实体类型: EPISODE, STORYBOARD, CHARACTER, SCENE, PROP, STYLE, ASSET
    entity_name VARCHAR(200),                       -- 实体名称（创建时快照）
    -- 生成上下文
    provider_id VARCHAR(100),                       -- AI 模型提供商ID
    generation_type VARCHAR(50),                    -- 生成类型: IMAGE, VIDEO, AUDIO, TEXT
    thumbnail_url VARCHAR(1000),                    -- 缩略图URL（完成后回填）
    -- 费用与来源
    credit_cost INTEGER DEFAULT 0,                  -- 实际消耗积分（完成后回填）
    source VARCHAR(50),                             -- 任务来源: MANUAL, BATCH, RETRY, SCHEDULED
    priority INTEGER DEFAULT 0,
    progress INTEGER DEFAULT 0,
    retry_count INTEGER DEFAULT 0,
    max_retries INTEGER DEFAULT 3,
    timeout_seconds INTEGER DEFAULT 300,
    input_params JSONB DEFAULT '{}',
    output_result JSONB DEFAULT '{}',
    error_message TEXT,
    error_code VARCHAR(50),
    error_detail JSONB DEFAULT '{}',
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    timeout_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0
);

COMMENT ON TABLE t_task IS '任务表';
COMMENT ON COLUMN t_task.task_type IS '任务类型: AI_GENERATE, EXPORT, BATCH';
COMMENT ON COLUMN t_task.status IS '状态: PENDING, QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED';
COMMENT ON COLUMN t_task.script_id IS '所属剧本ID';
COMMENT ON COLUMN t_task.entity_id IS '目标实体ID（剧集、分镜、角色、场景、道具等）';
COMMENT ON COLUMN t_task.entity_type IS '实体类型: SCRIPT, EPISODE, STORYBOARD, CHARACTER, SCENE, PROP, STYLE, ASSET';
COMMENT ON COLUMN t_task.entity_name IS '实体名称（创建时快照）';
COMMENT ON COLUMN t_task.provider_id IS 'AI 模型提供商ID';
COMMENT ON COLUMN t_task.generation_type IS '生成类型: IMAGE, VIDEO, AUDIO, TEXT';
COMMENT ON COLUMN t_task.thumbnail_url IS '缩略图URL（任务完成后回填）';
COMMENT ON COLUMN t_task.credit_cost IS '实际消耗积分（任务完成后回填）';
COMMENT ON COLUMN t_task.source IS '任务来源: MANUAL, BATCH, RETRY, SCHEDULED';
COMMENT ON COLUMN t_task.error_code IS '错误码';
COMMENT ON COLUMN t_task.timeout_at IS '超时时间';

CREATE INDEX IF NOT EXISTS idx_t_task_workspace_id ON t_task(workspace_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_task_creator_id ON t_task(creator_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_task_status ON t_task(status) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_task_script_id ON t_task(script_id) WHERE deleted = 0 AND script_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_t_task_entity ON t_task(entity_type, entity_id) WHERE deleted = 0 AND entity_id IS NOT NULL;

-- =====================================================
-- 19. 补偿任务表
-- 用于处理积分操作失败后的自动重试
-- =====================================================

CREATE TABLE IF NOT EXISTS t_compensation_task (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type VARCHAR(50) NOT NULL,                    -- 补偿类型: UNFREEZE, CONFIRM_CONSUME
    workspace_id UUID NOT NULL,            -- 工作空间 ID
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'EXHAUSTED')),         -- 状态: PENDING, PROCESSING, COMPLETED, EXHAUSTED
    retry_count INTEGER DEFAULT 0,                -- 已重试次数
    next_retry_at TIMESTAMPTZ,                      -- 下次重试时间
    payload JSONB DEFAULT '{}',                   -- 补偿参数 {transactionId, businessId, amount, remark}
    last_error TEXT,                              -- 最后错误信息
    completed_at TIMESTAMPTZ,                       -- 完成时间
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 1
);

COMMENT ON TABLE t_compensation_task IS '补偿任务表 - 用于处理积分操作失败后的自动重试';
COMMENT ON COLUMN t_compensation_task.type IS '补偿类型: UNFREEZE(解冻积分), CONFIRM_CONSUME(确认消费)';
COMMENT ON COLUMN t_compensation_task.status IS '状态: PENDING(待处理), PROCESSING(处理中), COMPLETED(已完成), EXHAUSTED(已耗尽重试)';
COMMENT ON COLUMN t_compensation_task.payload IS '补偿参数 JSON: {transactionId, businessId, amount, remark}';

CREATE INDEX IF NOT EXISTS idx_compensation_task_status_retry ON t_compensation_task(status, next_retry_at) WHERE status IN ('PENDING', 'PROCESSING');
CREATE INDEX IF NOT EXISTS idx_compensation_task_workspace ON t_compensation_task(workspace_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_compensation_task_type ON t_compensation_task(type) WHERE deleted = 0;

-- =====================================================
-- 19.5 Outbox 消息表（Transactional Outbox Pattern）
-- 保证业务操作与消息发送的原子性
-- =====================================================

CREATE TABLE IF NOT EXISTS t_outbox_message (
    id VARCHAR(64) PRIMARY KEY,                       -- 消息ID（与 MessageWrapper.messageId 一致）
    exchange VARCHAR(200) NOT NULL,                    -- RabbitMQ Exchange
    routing_key VARCHAR(200) NOT NULL,                 -- RabbitMQ Routing Key
    message_type VARCHAR(100) NOT NULL,                -- 消息类型（如 TASK_CREATED）
    message_json JSONB NOT NULL,                       -- 完整 MessageWrapper JSON
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'      -- PENDING, SENT, FAILED
        CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    retry_count INTEGER DEFAULT 0,                     -- 发送重试次数
    max_retries INTEGER DEFAULT 10,                    -- 最大重试次数
    last_error TEXT,                                    -- 最后一次发送错误
    next_retry_at TIMESTAMPTZ,                         -- 下次重试时间
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),     -- 写入时间
    sent_at TIMESTAMPTZ                                -- 发送成功时间
);

COMMENT ON TABLE t_outbox_message IS 'Outbox 消息表 - Transactional Outbox Pattern，保证业务操作与消息发送原子性';
COMMENT ON COLUMN t_outbox_message.status IS '状态: PENDING(待发送), SENT(已发送), FAILED(发送失败/重试耗尽)';
COMMENT ON COLUMN t_outbox_message.message_json IS '完整的 MessageWrapper JSON，包含 payload、traceId、workspaceId 等';

-- 轮询索引：PENDING 状态 + 重试时间排序（FOR UPDATE SKIP LOCKED 使用）
CREATE INDEX IF NOT EXISTS idx_outbox_message_pending
    ON t_outbox_message(created_at ASC)
    WHERE status = 'PENDING';

-- 清理索引：按发送时间清理已完成消息
CREATE INDEX IF NOT EXISTS idx_outbox_message_sent_cleanup
    ON t_outbox_message(sent_at)
    WHERE status = 'SENT';

-- 失败消息监控索引
CREATE INDEX IF NOT EXISTS idx_outbox_message_failed
    ON t_outbox_message(created_at DESC)
    WHERE status = 'FAILED';

-- =====================================================
-- 20. 通知表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_notification (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    workspace_id UUID,
    user_id UUID NOT NULL,
    type VARCHAR(50) NOT NULL,
    title VARCHAR(500) NOT NULL,
    content TEXT,
    payload JSONB DEFAULT '{}',
    entity_type VARCHAR(50),
    entity_id UUID,
    sender_id UUID,
    sender_name VARCHAR(100),
    is_read BOOLEAN DEFAULT FALSE,
    read_at TIMESTAMPTZ,
    priority INTEGER DEFAULT 2,
    expire_at TIMESTAMPTZ,
    event_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE IF NOT EXISTS t_notification_default PARTITION OF t_notification DEFAULT;

COMMENT ON TABLE t_notification IS '用户通知表';
COMMENT ON COLUMN t_notification.workspace_id IS '工作空间ID，null表示系统级通知';
COMMENT ON COLUMN t_notification.user_id IS '接收通知的用户ID';
COMMENT ON COLUMN t_notification.type IS '通知类型: TASK_PROGRESS, TASK_COMPLETED, TASK_FAILED, GENERATION_RESULT, COMMENT_MENTION, COMMENT_REPLY, SYSTEM_ALERT, SYSTEM_ANNOUNCEMENT';
COMMENT ON COLUMN t_notification.payload IS '附加数据，JSON格式存储扩展信息';
COMMENT ON COLUMN t_notification.entity_type IS '关联实体类型: TASK, ASSET, SCRIPT, COMMENT等';
COMMENT ON COLUMN t_notification.entity_id IS '关联实体ID';
COMMENT ON COLUMN t_notification.priority IS '优先级: 1-低优先级, 2-普通, 3-高优先级';
COMMENT ON COLUMN t_notification.expire_at IS '过期时间，null表示永不过期';
COMMENT ON COLUMN t_notification.event_id IS '幂等事件ID：基于业务键派生，用于跨发布者/多消费者去重';

CREATE INDEX IF NOT EXISTS idx_t_notification_user_id ON t_notification(user_id);
CREATE INDEX IF NOT EXISTS idx_t_notification_user_unread ON t_notification(user_id, is_read) WHERE is_read = FALSE;
CREATE INDEX IF NOT EXISTS idx_t_notification_workspace ON t_notification(workspace_id) WHERE workspace_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_t_notification_workspace_user ON t_notification(workspace_id, user_id) WHERE workspace_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_t_notification_type ON t_notification(type);
CREATE INDEX IF NOT EXISTS idx_t_notification_created_at ON t_notification(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_t_notification_entity ON t_notification(entity_type, entity_id) WHERE entity_type IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_t_notification_expire ON t_notification(expire_at) WHERE expire_at IS NOT NULL;
-- 复合索引：覆盖 listByUser(userId, workspaceId, type, isRead) + ORDER BY created_at DESC
CREATE INDEX IF NOT EXISTS idx_t_notification_user_ws_read_time ON t_notification(user_id, workspace_id, is_read, created_at DESC) WHERE workspace_id IS NOT NULL;
-- 已读通知定时清理索引
CREATE INDEX IF NOT EXISTS idx_t_notification_read_created ON t_notification(is_read, created_at) WHERE is_read = TRUE;
-- event_id 普通索引（分区表限制：UNIQUE 必须包含分区键，无法在父表做全局 UNIQUE）
-- 对具体分区创建 UNIQUE，可防止同一分区内重复；跨分区依赖应用层 Redis 去重兜底
CREATE INDEX IF NOT EXISTS idx_t_notification_event_id ON t_notification(event_id) WHERE event_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_t_notification_default_event_id
    ON t_notification_default(event_id) WHERE event_id IS NOT NULL;

-- =====================================================
-- 21. 邀请码表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_invitation_code (
    id              UUID     PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(32)     NOT NULL,
    name            VARCHAR(100),

    -- 类型区分: SYSTEM(管理员创建) / USER(用户专属)
    type            VARCHAR(20)     NOT NULL DEFAULT 'SYSTEM',
    -- 所属用户ID（USER类型必填）
    owner_id        UUID,

    -- 使用控制
    max_uses        INT             NOT NULL DEFAULT 1,
    used_count      INT             NOT NULL DEFAULT 0,

    -- 时间控制
    valid_from      TIMESTAMPTZ,
    valid_until     TIMESTAMPTZ,

    -- 状态: ACTIVE/DISABLED/EXHAUSTED/EXPIRED/REPLACED
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED', 'EXHAUSTED', 'EXPIRED', 'REPLACED')),

    -- 批次ID（批量生成时相同）
    batch_id        UUID,

    -- 审计字段
    created_by      UUID     NOT NULL,
    created_at      TIMESTAMPTZ       NOT NULL DEFAULT now(),
    updated_by      UUID,
    updated_at      TIMESTAMPTZ,

    -- 软删除
    deleted         INT             NOT NULL DEFAULT 0,
    version         INT             NOT NULL DEFAULT 1,

    CONSTRAINT uk_invitation_code UNIQUE (code)
);

COMMENT ON TABLE t_invitation_code IS '邀请码表';
COMMENT ON COLUMN t_invitation_code.id IS '主键ID（UUIDv7）';
COMMENT ON COLUMN t_invitation_code.code IS '邀请码（唯一）';
COMMENT ON COLUMN t_invitation_code.name IS '邀请码名称/备注';
COMMENT ON COLUMN t_invitation_code.type IS '类型：SYSTEM-管理员创建，USER-用户专属';
COMMENT ON COLUMN t_invitation_code.owner_id IS '所属用户ID（USER类型）';
COMMENT ON COLUMN t_invitation_code.max_uses IS '最大使用次数（-1表示无限）';
COMMENT ON COLUMN t_invitation_code.used_count IS '已使用次数';
COMMENT ON COLUMN t_invitation_code.valid_from IS '生效时间（NULL表示立即生效）';
COMMENT ON COLUMN t_invitation_code.valid_until IS '失效时间（NULL表示永不过期）';
COMMENT ON COLUMN t_invitation_code.status IS '状态：ACTIVE-可用，DISABLED-已禁用，EXHAUSTED-已耗尽，EXPIRED-已过期，REPLACED-已替换';
COMMENT ON COLUMN t_invitation_code.batch_id IS '批次ID（批量生成时相同）';

CREATE INDEX IF NOT EXISTS idx_ic_code ON t_invitation_code(code) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_ic_status ON t_invitation_code(status) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_ic_type ON t_invitation_code(type) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_ic_owner ON t_invitation_code(owner_id, type) WHERE deleted = 0 AND owner_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ic_batch ON t_invitation_code(batch_id) WHERE deleted = 0 AND batch_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ic_created_at ON t_invitation_code(created_at DESC) WHERE deleted = 0;

-- =====================================================
-- 22. 邀请码使用记录表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_invitation_code_usage (
    id                  UUID     PRIMARY KEY DEFAULT gen_random_uuid(),
    invitation_code_id  UUID     NOT NULL,
    code                VARCHAR(32)     NOT NULL,

    -- 邀请人信息（USER类型邀请码时）
    inviter_id          UUID,

    -- 被邀请人信息
    invitee_id          UUID     NOT NULL,
    invitee_username    VARCHAR(64),
    invitee_email       VARCHAR(128),

    -- 使用信息
    used_at             TIMESTAMPTZ       NOT NULL DEFAULT now(),
    ip_address          VARCHAR(45),
    user_agent          VARCHAR(500),

    CONSTRAINT fk_usage_invitation_code FOREIGN KEY (invitation_code_id)
        REFERENCES t_invitation_code(id) ON DELETE CASCADE
);

COMMENT ON TABLE t_invitation_code_usage IS '邀请码使用记录表';
COMMENT ON COLUMN t_invitation_code_usage.id IS '主键ID（UUIDv7）';
COMMENT ON COLUMN t_invitation_code_usage.invitation_code_id IS '邀请码ID';
COMMENT ON COLUMN t_invitation_code_usage.code IS '邀请码（冗余）';
COMMENT ON COLUMN t_invitation_code_usage.inviter_id IS '邀请人用户ID';
COMMENT ON COLUMN t_invitation_code_usage.invitee_id IS '被邀请人用户ID';
COMMENT ON COLUMN t_invitation_code_usage.invitee_username IS '被邀请人用户名';
COMMENT ON COLUMN t_invitation_code_usage.invitee_email IS '被邀请人邮箱';
COMMENT ON COLUMN t_invitation_code_usage.used_at IS '使用时间';
COMMENT ON COLUMN t_invitation_code_usage.ip_address IS '注册IP地址';
COMMENT ON COLUMN t_invitation_code_usage.user_agent IS '浏览器UA';

CREATE INDEX IF NOT EXISTS idx_icu_code_id ON t_invitation_code_usage(invitation_code_id);
CREATE INDEX IF NOT EXISTS idx_icu_inviter ON t_invitation_code_usage(inviter_id) WHERE inviter_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_icu_invitee ON t_invitation_code_usage(invitee_id);
CREATE INDEX IF NOT EXISTS idx_icu_used_at ON t_invitation_code_usage(used_at DESC);

-- =====================================================
-- 23. LLM Provider 全局配置表
-- 定义可用的 LLM 模型，与 Agent 解耦
-- =====================================================

CREATE TABLE IF NOT EXISTS t_llm_provider (
    id                  VARCHAR(100) PRIMARY KEY,

    -- 模型标识
    provider            VARCHAR(50) NOT NULL,           -- GOOGLE, OPENAI, ANTHROPIC, VOLCENGINE, ZHIPU, MOONSHOT
    model_id            VARCHAR(100) NOT NULL,          -- gemini-3-flash-preview, gpt-4o, claude-sonnet-4
    model_name          VARCHAR(200) NOT NULL,          -- 显示名称

    -- 模型参数
    temperature         DECIMAL(3, 2) DEFAULT 0.7,
    max_output_tokens   INTEGER DEFAULT 8192,
    top_p               DECIMAL(3, 2) DEFAULT 0.95,
    top_k               INTEGER DEFAULT 40,

    -- API 配置
    api_endpoint        VARCHAR(500),                   -- 可选，覆盖默认端点
    api_endpoint_ref    VARCHAR(100),                   -- 引用 t_system_config.config_key，运行时解析 API Endpoint
    completions_path    VARCHAR(255),                   -- 自定义 completions API 路径
    api_key_ref         VARCHAR(100),                   -- 引用密钥配置，如 'GOOGLE_API_KEY'
    extra_config        JSONB DEFAULT '{}',             -- 额外配置参数

    -- 上下文限制
    context_window      INTEGER DEFAULT 128000,         -- 上下文窗口大小
    max_input_tokens    INTEGER DEFAULT 100000,         -- 最大输入 token

    -- 状态
    enabled             BOOLEAN DEFAULT TRUE,
    priority            INTEGER DEFAULT 0,              -- 多模型选择优先级

    -- 描述
    description         TEXT,

    -- 审计
    created_at          TIMESTAMPTZ DEFAULT now(),
    updated_at          TIMESTAMPTZ DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    deleted             INTEGER DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    version             INTEGER DEFAULT 0
);

-- 部分唯一索引：仅对未删除记录生效，避免软删除后的重复问题
CREATE UNIQUE INDEX IF NOT EXISTS uk_llm_provider_model ON t_llm_provider(provider, model_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_llm_provider_provider ON t_llm_provider(provider) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_llm_provider_enabled ON t_llm_provider(enabled, priority) WHERE deleted = 0;

COMMENT ON TABLE t_llm_provider IS 'LLM Provider 全局配置，定义可用的 LLM 模型';
COMMENT ON COLUMN t_llm_provider.provider IS '模型厂商: GOOGLE, OPENAI, ANTHROPIC, VOLCENGINE, ZHIPU, MOONSHOT';
COMMENT ON COLUMN t_llm_provider.api_key_ref IS '引用系统配置中的 API 密钥名称';
COMMENT ON COLUMN t_llm_provider.api_endpoint_ref IS '引用 t_system_config.config_key，运行时解析 API Endpoint';
COMMENT ON COLUMN t_llm_provider.completions_path IS '自定义 completions API 路径（豆包:/v3, 智谱:/paas/v4）';

-- =====================================================
-- 24. LLM 计费规则表
-- 关联 LlmProvider，而非直接存储 model_provider/model_id
-- =====================================================

CREATE TABLE IF NOT EXISTS t_llm_billing_rule (
    id                  VARCHAR(100) PRIMARY KEY,

    -- 关联 LlmProvider
    llm_provider_id     VARCHAR(100) NOT NULL REFERENCES t_llm_provider(id),

    -- Token 定价（积分/1K tokens）
    input_price         DECIMAL(10, 4) NOT NULL DEFAULT 0,
    output_price        DECIMAL(10, 4) NOT NULL DEFAULT 0,

    -- 定价有效期
    effective_from      TIMESTAMPTZ NOT NULL DEFAULT now(),
    effective_to        TIMESTAMPTZ,                      -- NULL 表示无限期

    -- 速率限制
    rate_limit_rpm      INTEGER DEFAULT 60,             -- 每分钟请求数
    rate_limit_tpm      INTEGER DEFAULT 1000000,        -- 每分钟 token 数

    -- 状态
    enabled             BOOLEAN DEFAULT TRUE,
    priority            INTEGER DEFAULT 0,

    -- 描述
    description         TEXT,

    -- 审计
    created_at          TIMESTAMPTZ DEFAULT now(),
    updated_at          TIMESTAMPTZ DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    deleted             INTEGER DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    version             INTEGER DEFAULT 0
);

-- 部分唯一索引：仅对未删除记录生效
CREATE UNIQUE INDEX IF NOT EXISTS uk_llm_billing_rule ON t_llm_billing_rule(llm_provider_id, effective_from) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_llm_billing_rule_provider ON t_llm_billing_rule(llm_provider_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_llm_billing_rule_effective ON t_llm_billing_rule(effective_from, effective_to)
    WHERE deleted = 0 AND enabled = TRUE;

COMMENT ON TABLE t_llm_billing_rule IS 'LLM 计费规则，关联 LlmProvider';

-- =====================================================
-- 25. Agent 配置表
-- 整合提示词 + LLM 选择，一个 Agent 类型一条记录
-- =====================================================

CREATE TABLE IF NOT EXISTS t_agent_config (
    id                  VARCHAR(100) PRIMARY KEY,

    -- Agent 标识（唯一）
    agent_type          VARCHAR(50) NOT NULL UNIQUE,    -- COORDINATOR, SCRIPT_EXPERT 等
    agent_name          VARCHAR(200) NOT NULL,          -- 显示名称

    -- 关联 LLM Provider
    llm_provider_id     VARCHAR(100) REFERENCES t_llm_provider(id),

    -- 提示词配置
    prompt_content      TEXT NOT NULL,                  -- 提示词内容
    includes            JSONB DEFAULT '[]',             -- 包含的其他 prompt_key 列表

    -- 可用的 AI Provider 类型
    ai_provider_types   JSONB DEFAULT '[]',             -- ["IMAGE", "VIDEO", "AUDIO", "TEXT"]

    -- Skill 加载策略
    default_skill_names JSONB DEFAULT '[]',             -- 默认 Skill 列表
    allowed_skill_names JSONB DEFAULT '[]',             -- 允许加载的 Skill 白名单
    skill_load_mode     VARCHAR(32) DEFAULT 'ALL_ENABLED', -- ALL_ENABLED / DEFAULT_ONLY / REQUEST_SCOPED / DISABLED
    execution_mode      VARCHAR(16) DEFAULT 'BOTH',     -- CHAT / MISSION / BOTH

    -- 状态
    enabled             BOOLEAN DEFAULT TRUE,
    is_system           BOOLEAN DEFAULT TRUE,           -- 系统配置不可删除

    -- 描述
    description         TEXT,

    -- 版本控制
    current_version     INTEGER DEFAULT 1,

    -- ==================== 自定义 Agent 扩展字段 ====================

    -- 协调者配置
    is_coordinator      BOOLEAN DEFAULT FALSE,          -- 是否为协调者 Agent
    sub_agent_types     JSONB DEFAULT '[]',             -- 子 Agent 类型列表

    -- 作用域配置
    scope               VARCHAR(20) DEFAULT 'SYSTEM',   -- SYSTEM, WORKSPACE, USER
    workspace_id        UUID,                    -- 所属工作空间 ID（scope=WORKSPACE 时必填）
    creator_id          UUID,                    -- 创建者用户 ID（scope=USER 时必填）

    -- 独立调用配置
    standalone_enabled  BOOLEAN DEFAULT FALSE,          -- 是否支持独立调用

    -- 展示配置
    icon_url            VARCHAR(500),                   -- Agent 图标 URL
    tags                JSONB DEFAULT '[]',             -- 分类标签

    -- 审计
    created_at          TIMESTAMPTZ DEFAULT now(),
    updated_at          TIMESTAMPTZ DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    deleted             INTEGER DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    version             INTEGER DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_agent_config_type ON t_agent_config(agent_type) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_agent_config_llm ON t_agent_config(llm_provider_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_agent_config_scope ON t_agent_config(scope) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_agent_config_workspace ON t_agent_config(workspace_id) WHERE deleted = 0 AND scope = 'WORKSPACE';
CREATE INDEX IF NOT EXISTS idx_agent_config_creator ON t_agent_config(creator_id) WHERE deleted = 0 AND scope = 'USER';
CREATE INDEX IF NOT EXISTS idx_agent_config_standalone ON t_agent_config(standalone_enabled) WHERE deleted = 0 AND standalone_enabled = TRUE;
CREATE INDEX IF NOT EXISTS idx_agent_config_coordinator ON t_agent_config(is_coordinator) WHERE deleted = 0 AND is_coordinator = TRUE;

COMMENT ON TABLE t_agent_config IS 'Agent 配置，整合提示词与 LLM 选择，支持自定义 Agent';
COMMENT ON COLUMN t_agent_config.agent_type IS 'Agent类型: COORDINATOR, SCRIPT_EXPERT 等系统类型，或用户自定义类型';
COMMENT ON COLUMN t_agent_config.is_coordinator IS '是否为协调者 Agent，可以将任务委派给子 Agent';
COMMENT ON COLUMN t_agent_config.sub_agent_types IS '子 Agent 类型列表（协调者专用）';
COMMENT ON COLUMN t_agent_config.scope IS '作用域: SYSTEM=系统级, WORKSPACE=工作空间级, USER=用户级';
COMMENT ON COLUMN t_agent_config.standalone_enabled IS '是否支持独立调用（不通过协调者）';
COMMENT ON COLUMN t_agent_config.default_skill_names IS '默认 Skill 列表';
COMMENT ON COLUMN t_agent_config.allowed_skill_names IS '允许加载的 Skill 白名单';
COMMENT ON COLUMN t_agent_config.skill_load_mode IS 'Skill 加载模式: ALL_ENABLED / DEFAULT_ONLY / REQUEST_SCOPED / DISABLED';
COMMENT ON COLUMN t_agent_config.execution_mode IS '执行模式: CHAT / MISSION / BOTH';

-- =====================================================
-- 26. Agent 计费会话表
-- 记录每个 Agent 会话的计费信息，存储在 public schema 以支持跨租户结算
-- =====================================================

CREATE TABLE IF NOT EXISTS t_agent_billing_session (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- 关联信息
    workspace_id        UUID NOT NULL,           -- 工作空间 ID
    conversation_id     UUID NOT NULL,           -- 关联的会话 ID
    user_id             UUID NOT NULL,           -- 用户 ID

    -- 模型信息
    model_provider      VARCHAR(50) NOT NULL,           -- 模型厂商
    model_id            VARCHAR(100) NOT NULL,          -- 模型 ID
    model_name          VARCHAR(100),                   -- 模型名称

    -- 钱包事务
    transaction_id      UUID,                    -- 钱包冻结事务 ID
    frozen_amount       BIGINT DEFAULT 0,               -- 冻结金额（积分）

    -- 消费统计
    total_input_tokens  BIGINT DEFAULT 0,               -- 总输入 token 数
    total_output_tokens BIGINT DEFAULT 0,               -- 总输出 token 数
    total_thought_tokens BIGINT DEFAULT 0,              -- 总思考 token 数（模型内部推理）
    total_cached_tokens BIGINT DEFAULT 0,               -- 总缓存 token 数（复用缓存）
    llm_cost            BIGINT DEFAULT 0,               -- LLM 对话消费（积分）
    ai_tool_calls       INTEGER DEFAULT 0,              -- AI 工具调用次数
    ai_tool_cost        BIGINT DEFAULT 0,               -- AI 工具消费（积分）
    total_cost          BIGINT DEFAULT 0,               -- 总消费 = llm_cost + ai_tool_cost

    -- 定价快照
    pricing_snapshot    JSONB,                          -- 会话创建时的定价快照

    -- 状态
    status              VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SETTLING', 'SETTLED', 'FAILED')),   -- ACTIVE, SETTLING, SETTLED, FAILED
    settled_amount      BIGINT,                         -- 结算金额
    settled_at          TIMESTAMPTZ,                      -- 结算时间
    settle_error        TEXT,                           -- 结算失败原因

    -- 活动追踪
    last_activity_at    TIMESTAMPTZ DEFAULT now(),

    -- 审计
    created_at          TIMESTAMPTZ DEFAULT now(),
    updated_at          TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_billing_session_workspace ON t_agent_billing_session(workspace_id);
CREATE INDEX IF NOT EXISTS idx_billing_session_conversation ON t_agent_billing_session(conversation_id);
CREATE INDEX IF NOT EXISTS idx_billing_session_user ON t_agent_billing_session(user_id);
CREATE INDEX IF NOT EXISTS idx_billing_session_status ON t_agent_billing_session(status);
CREATE INDEX IF NOT EXISTS idx_billing_session_last_activity ON t_agent_billing_session(last_activity_at) WHERE status = 'ACTIVE';

COMMENT ON TABLE t_agent_billing_session IS 'Agent 计费会话，记录每个会话的消费和结算信息';
COMMENT ON COLUMN t_agent_billing_session.pricing_snapshot IS '会话创建时的定价快照，包含 modelProvider, modelId, inputPrice, outputPrice';
COMMENT ON COLUMN t_agent_billing_session.status IS 'ACTIVE-活跃中, SETTLING-结算中, SETTLED-已结算, FAILED-结算失败';

-- =====================================================
-- 27. Agent 配置版本历史表
-- 记录 Agent 配置的变更历史，支持回滚
-- =====================================================

CREATE TABLE IF NOT EXISTS t_agent_config_version (
    id                  VARCHAR(100) PRIMARY KEY,

    -- 关联 AgentConfig
    agent_config_id     VARCHAR(100) NOT NULL REFERENCES t_agent_config(id),
    version_number      INTEGER NOT NULL,

    -- 快照数据
    prompt_content      TEXT NOT NULL,
    includes            JSONB DEFAULT '[]',
    llm_provider_id     VARCHAR(100),
    default_skill_names JSONB DEFAULT '[]',
    allowed_skill_names JSONB DEFAULT '[]',
    skill_load_mode     VARCHAR(32),
    execution_mode      VARCHAR(16),
    is_coordinator      BOOLEAN DEFAULT FALSE,
    sub_agent_types     JSONB DEFAULT '[]',
    standalone_enabled  BOOLEAN DEFAULT FALSE,

    -- 变更说明
    change_summary      VARCHAR(500),

    -- 审计
    created_at          TIMESTAMPTZ DEFAULT now(),
    created_by          UUID,
    updated_at          TIMESTAMPTZ DEFAULT now(),
    updated_by          UUID,
    deleted             INTEGER DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    version             INTEGER DEFAULT 1,

    CONSTRAINT uk_agent_config_version UNIQUE (agent_config_id, version_number)
);

CREATE INDEX IF NOT EXISTS idx_agent_config_version_config ON t_agent_config_version(agent_config_id);

COMMENT ON TABLE t_agent_config_version IS 'Agent 配置版本历史，支持回滚';

ALTER TABLE t_agent_config
    ADD COLUMN IF NOT EXISTS default_skill_names JSONB DEFAULT '[]',
    ADD COLUMN IF NOT EXISTS allowed_skill_names JSONB DEFAULT '[]',
    ADD COLUMN IF NOT EXISTS skill_load_mode VARCHAR(32) DEFAULT 'ALL_ENABLED',
    ADD COLUMN IF NOT EXISTS execution_mode VARCHAR(16) DEFAULT 'BOTH';

ALTER TABLE t_agent_config_version
    ADD COLUMN IF NOT EXISTS default_skill_names JSONB DEFAULT '[]',
    ADD COLUMN IF NOT EXISTS allowed_skill_names JSONB DEFAULT '[]',
    ADD COLUMN IF NOT EXISTS skill_load_mode VARCHAR(32),
    ADD COLUMN IF NOT EXISTS execution_mode VARCHAR(16),
    ADD COLUMN IF NOT EXISTS is_coordinator BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS sub_agent_types JSONB DEFAULT '[]',
    ADD COLUMN IF NOT EXISTS standalone_enabled BOOLEAN DEFAULT FALSE;

-- =====================================================
-- 28. Agent 工具访问权限表
-- 统一管理 PROJECT 和 AI 两类工具的访问权限
-- 支持多对多关系：同一工具可被多个 Agent 使用
-- =====================================================

CREATE TABLE IF NOT EXISTS t_agent_tool_access (
    id                  VARCHAR(100) PRIMARY KEY,

    -- Agent 标识
    agent_type          VARCHAR(50) NOT NULL,           -- COORDINATOR, SCRIPT_EXPERT 等

    -- 工具分类
    tool_category       VARCHAR(20) NOT NULL,           -- PROJECT | AI

    -- 工具标识
    -- PROJECT: 工具名称（如 write_script, create_character）
    -- AI: model_provider_id（关联 actionow-ai 的 t_model_provider.id）
    tool_id             VARCHAR(100) NOT NULL,

    -- 工具显示信息（可选覆盖）
    tool_name           VARCHAR(200),                   -- 覆盖默认工具名
    tool_description    TEXT,                           -- 覆盖默认描述

    -- 访问模式
    access_mode         VARCHAR(20) DEFAULT 'FULL',     -- FULL | READONLY | DISABLED

    -- 配额控制
    daily_quota         INTEGER DEFAULT -1,             -- 每日调用限额，-1 表示无限

    -- 优先级
    priority            INTEGER DEFAULT 0,

    -- 状态
    enabled             BOOLEAN DEFAULT TRUE,

    -- 审计
    created_at          TIMESTAMPTZ DEFAULT now(),
    updated_at          TIMESTAMPTZ DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    deleted             INTEGER DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    version             INTEGER DEFAULT 0
);

-- 部分唯一索引：仅对未删除记录生效
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_tool_access ON t_agent_tool_access(agent_type, tool_category, tool_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_agent_tool_access_agent ON t_agent_tool_access(agent_type) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_agent_tool_access_category ON t_agent_tool_access(tool_category) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_agent_tool_access_tool ON t_agent_tool_access(tool_id) WHERE deleted = 0;

COMMENT ON TABLE t_agent_tool_access IS 'Agent 工具访问权限，统一管理 PROJECT 和 AI 工具';
COMMENT ON COLUMN t_agent_tool_access.tool_category IS '工具分类: PROJECT(业务工具), AI(生成工具)';
COMMENT ON COLUMN t_agent_tool_access.tool_id IS 'PROJECT: 工具名称, AI: model_provider_id';
COMMENT ON COLUMN t_agent_tool_access.access_mode IS '访问模式: FULL(完全), READONLY(只读), DISABLED(禁用)';

-- =====================================================
-- 29. Agent Skill 技能表（公共 Schema）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_agent_skill (
    id               VARCHAR(100) PRIMARY KEY,
    name             VARCHAR(64)  NOT NULL,
    display_name     VARCHAR(128),
    description      VARCHAR(512) NOT NULL,
    content          TEXT         NOT NULL,
    grouped_tool_ids JSONB        NOT NULL DEFAULT '[]',
    output_schema    JSONB,
    tags             JSONB                  DEFAULT '[]',
    "references"     JSONB                  DEFAULT '[]',
    examples         JSONB                  DEFAULT '[]',
    enabled          BOOLEAN      NOT NULL DEFAULT true,
    version          INTEGER      NOT NULL DEFAULT 1,
    scope            VARCHAR(32)  NOT NULL DEFAULT 'SYSTEM',
    workspace_id     UUID,
    creator_id       UUID,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    deleted          INTEGER      NOT NULL DEFAULT 0,
    deleted_at       TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_skill_name_scope
    ON t_agent_skill(name, COALESCE(workspace_id, '00000000-0000-0000-0000-000000000000'::UUID), deleted) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_agent_skill_name
    ON t_agent_skill(name) WHERE deleted = 0;
COMMENT ON TABLE t_agent_skill IS 'Agent 技能定义，每个 Skill 封装专家知识 + 工具集，由 SkillsAgentHook 按需加载';

-- =====================================================
-- 30. Agent 会话表（public schema）
-- agent 服务统一读写 public schema，会话与消息不走多租户隔离
-- =====================================================

CREATE TABLE IF NOT EXISTS t_agent_session (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    UUID NOT NULL,
    user_id         UUID NOT NULL,
    agent_type      VARCHAR(50) NOT NULL,
    scope           VARCHAR(20) DEFAULT 'global',
    scope_context   JSONB DEFAULT '{}',
    status          VARCHAR(20) DEFAULT 'ACTIVE',
    title           VARCHAR(500),
    message_count   INTEGER DEFAULT 0,
    total_tokens    BIGINT DEFAULT 0,
    extras          JSONB DEFAULT '{}',
    skill_names     JSONB DEFAULT NULL,
    last_active_at  TIMESTAMPTZ DEFAULT now(),
    archived_at     TIMESTAMPTZ,
    expire_at       TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    deleted         INTEGER DEFAULT 0,
    version         INTEGER DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_agent_session_user      ON t_agent_session(user_id, deleted);
CREATE INDEX IF NOT EXISTS idx_agent_session_workspace ON t_agent_session(workspace_id, deleted);
CREATE INDEX IF NOT EXISTS idx_agent_session_status    ON t_agent_session(status, last_active_at);
CREATE INDEX IF NOT EXISTS idx_agent_session_scope_ctx ON t_agent_session USING GIN (scope_context) WHERE deleted = 0;

-- skip-placeholder 路径引入：会话级别的"正在生成"状态 + 心跳挂载点，替代空 placeholder 行。
-- generating_since 非 NULL 表示当前 session 有活跃 ReAct 循环；last_heartbeat_at 由心跳调度器周期更新。
-- /state 端点在 placeholder 缺失时从这两列推断 inFlight / staleMs。
ALTER TABLE t_agent_session ADD COLUMN IF NOT EXISTS generating_since   TIMESTAMPTZ;
ALTER TABLE t_agent_session ADD COLUMN IF NOT EXISTS last_heartbeat_at  TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS t_agent_message (
    id              UUID NOT NULL DEFAULT gen_random_uuid(),
    session_id      UUID NOT NULL,
    role            VARCHAR(20) NOT NULL,
    content         TEXT,
    tool_call_id    VARCHAR(128),
    tool_name       VARCHAR(100),
    tool_arguments  JSONB DEFAULT '{}',
    tool_result     JSONB DEFAULT '{}',
    token_count     INTEGER DEFAULT 0,
    sequence        INTEGER NOT NULL,
    status          VARCHAR(20) DEFAULT 'completed',
    extras          JSONB DEFAULT '{}',
    attachment_ids  JSONB DEFAULT '[]',
    -- 最近一次心跳时间（仅 status='generating' 的占位消息使用）；跨 pod 重连时用于估算
    -- 后端是否仍在活跃生成。NULL 表示未曾心跳（非占位消息或老数据）。
    last_heartbeat_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    deleted         INTEGER DEFAULT 0,
    deleted_at      TIMESTAMPTZ,
    version         INTEGER DEFAULT 0,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE IF NOT EXISTS t_agent_message_default PARTITION OF t_agent_message DEFAULT;
CREATE INDEX IF NOT EXISTS idx_agent_message_session ON t_agent_message(session_id, deleted, sequence);
CREATE INDEX IF NOT EXISTS idx_agent_message_role ON t_agent_message(session_id, role) WHERE deleted = 0;

-- 老库升级：心跳列在 v3 引入；如列已存在则 ADD COLUMN IF NOT EXISTS 幂等跳过。
ALTER TABLE t_agent_message ADD COLUMN IF NOT EXISTS last_heartbeat_at TIMESTAMPTZ;

-- per-segment-write 路径引入：每条 assistant_segment 行直接落 AgentStreamBridge 分配的 eventId，
-- 用于跨 pod 重放 / runner 重连场景下的 app 层幂等去重（读到相同 session_id + event_id 即跳过 insert）。
-- 索引为部分索引（仅对 event_id IS NOT NULL 的新数据生效），不影响既有心跳 / tool pair 等行。
-- 注意：t_agent_message 为 RANGE(created_at) 分区表，PG 的全局 UNIQUE 约束要求包含分区键；
-- 为避免把分区键写进业务唯一键，这里只建"非 UNIQUE 索引 + app 层 dedup 检查"的组合。
ALTER TABLE t_agent_message ADD COLUMN IF NOT EXISTS event_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_agent_message_event
    ON t_agent_message (session_id, event_id)
    WHERE event_id IS NOT NULL AND deleted = 0;

-- HITL ask/answer 审计记录（每次 ask_user_* 工具调用一行）
CREATE TABLE IF NOT EXISTS t_agent_ask_history (
    id            UUID NOT NULL DEFAULT gen_random_uuid(),
    session_id    UUID NOT NULL,
    ask_id        VARCHAR(64) NOT NULL,
    question      TEXT NOT NULL,
    input_type    VARCHAR(32) NOT NULL,
    choices       JSONB DEFAULT '[]',
    constraints   JSONB DEFAULT '{}',
    answer        JSONB DEFAULT '{}',
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    responded_at  TIMESTAMPTZ,
    timeout_sec   INTEGER,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ DEFAULT now(),
    created_by    UUID,
    updated_by    UUID,
    deleted       INTEGER DEFAULT 0,
    deleted_at    TIMESTAMPTZ,
    version       INTEGER DEFAULT 0,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_ask_history_askid ON t_agent_ask_history(ask_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_agent_ask_history_session ON t_agent_ask_history(session_id, created_at) WHERE deleted = 0;

-- =====================================================
-- 30. 自动更新 updated_at 触发器
-- =====================================================

DO $$
DECLARE
    tables TEXT[] := ARRAY[
        't_user', 't_user_oauth', 't_workspace', 't_workspace_member',
        't_workspace_invitation', 't_workspace_wallet', 't_member_quota',
        't_system_config', 't_frozen_transaction', 't_model_provider',
        't_model_provider_script', 't_model_provider_schema',
        't_dict_type', 't_dict_item', 't_platform_stats',
        't_groovy_template', 't_task', 't_compensation_task', 't_invitation_code',
        't_llm_provider', 't_llm_billing_rule', 't_agent_config', 't_agent_tool_access',
        't_agent_skill', 't_agent_billing_session', 't_agent_session', 't_agent_message', 't_agent_ask_history',
        't_model_provider_execution', 't_agent_config_version',
        't_billing_account', 't_provider_customer', 't_payment_order',
        't_payment_attempt', 't_provider_event', 't_subscription_contract',
        't_entitlement_grant', 't_payment_route_rule', 't_billing_plan_price',
        't_auth_session', 't_refresh_token', 't_auth_revocation_event',
        't_subscription_plan', 't_subscription', 't_payment_history'
    ];
    tbl TEXT;
BEGIN
    FOREACH tbl IN ARRAY tables LOOP
        BEGIN
            EXECUTE format('DROP TRIGGER IF EXISTS trigger_%s_updated_at ON %s', tbl, tbl);
            EXECUTE format('CREATE TRIGGER trigger_%s_updated_at BEFORE UPDATE ON %s
                            FOR EACH ROW EXECUTE FUNCTION trigger_set_timestamp()', tbl, tbl);
        EXCEPTION WHEN undefined_table THEN
            RAISE NOTICE 'Skipping trigger for missing table: %', tbl;
        END;
    END LOOP;
END $$;

-- =====================================================
-- 完成
-- =====================================================

DO $$
BEGIN
    RAISE NOTICE 'Actionow public schema initialized successfully!';
END $$;

-- =====================================================
-- 3. Tenant Schema 初始化函数
-- =====================================================


-- =====================================================
-- 租户 Schema 创建函数
-- =====================================================

CREATE OR REPLACE FUNCTION create_tenant_schema(p_schema_name VARCHAR)
RETURNS VOID AS $$
BEGIN
    -- 创建 Schema
    EXECUTE format('CREATE SCHEMA IF NOT EXISTS %I', p_schema_name);

    -- =====================================================
    -- 1. 剧本表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_script (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        title VARCHAR(500) NOT NULL,
        status VARCHAR(20) NOT NULL DEFAULT ''DRAFT'',
        synopsis TEXT,
        content TEXT,
        cover_asset_id UUID,
        doc_asset_id UUID,
        extra_info JSONB DEFAULT ''{}''::jsonb,
        current_version_id UUID,
        version_number INTEGER DEFAULT 1,
        version INTEGER NOT NULL DEFAULT 1,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ
    )', p_schema_name);

    -- =====================================================
    -- 2. 剧集表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_episode (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        script_id UUID NOT NULL,
        title VARCHAR(500) NOT NULL,
        sequence INTEGER NOT NULL,
        status VARCHAR(20) NOT NULL DEFAULT ''DRAFT'',
        synopsis TEXT,
        content TEXT,
        cover_asset_id UUID,
        doc_asset_id UUID,
        extra_info JSONB DEFAULT ''{}''::jsonb,
        current_version_id UUID,
        version_number INTEGER DEFAULT 1,
        version INTEGER NOT NULL DEFAULT 1,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ
    )', p_schema_name);

    -- =====================================================
    -- 3. 分镜表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_storyboard (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        script_id UUID NOT NULL,
        episode_id UUID NOT NULL,
        title VARCHAR(500),
        sequence INTEGER NOT NULL,
        status VARCHAR(20) NOT NULL DEFAULT ''DRAFT'',
        synopsis TEXT,
        duration INTEGER,
        visual_desc JSONB DEFAULT ''{}''::jsonb,
        audio_desc JSONB DEFAULT ''{}''::jsonb,
        cover_asset_id UUID,
        doc_asset_id UUID,
        extra_info JSONB DEFAULT ''{}''::jsonb,
        current_version_id UUID,
        version_number INTEGER DEFAULT 1,
        version INTEGER NOT NULL DEFAULT 1,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ
    )', p_schema_name);

    -- =====================================================
    -- 4. 角色表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_character (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        scope VARCHAR(20) DEFAULT ''WORKSPACE'',
        script_id UUID,
        name VARCHAR(200) NOT NULL,
        description TEXT,
        fixed_desc TEXT,
        age INTEGER,
        gender VARCHAR(20),
        character_type VARCHAR(50),
        voice_seed_id VARCHAR(100),
        appearance_data JSONB DEFAULT ''{}''::jsonb,
        cover_asset_id UUID,
        extra_info JSONB DEFAULT ''{}''::jsonb,
        current_version_id UUID,
        version_number INTEGER DEFAULT 1,
        version INTEGER NOT NULL DEFAULT 1,
        published_at TIMESTAMPTZ,
        published_by UUID,
        publish_note TEXT,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ
    )', p_schema_name);

    -- =====================================================
    -- 5. 场景表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_scene (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        scope VARCHAR(20) DEFAULT ''WORKSPACE'',
        scene_type VARCHAR(20) DEFAULT ''INTERIOR'',
        script_id UUID,
        name VARCHAR(200) NOT NULL,
        description TEXT,
        fixed_desc TEXT,
        appearance_data JSONB DEFAULT ''{}''::jsonb,
        cover_asset_id UUID,
        extra_info JSONB DEFAULT ''{}''::jsonb,
        current_version_id UUID,
        version_number INTEGER DEFAULT 1,
        version INTEGER NOT NULL DEFAULT 1,
        published_at TIMESTAMPTZ,
        published_by UUID,
        publish_note TEXT,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ
    )', p_schema_name);

    -- =====================================================
    -- 6. 道具表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_prop (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        scope VARCHAR(20) DEFAULT ''WORKSPACE'',
        script_id UUID,
        name VARCHAR(200) NOT NULL,
        description TEXT,
        fixed_desc TEXT,
        prop_type VARCHAR(100),
        appearance_data JSONB DEFAULT ''{}''::jsonb,
        cover_asset_id UUID,
        extra_info JSONB DEFAULT ''{}''::jsonb,
        current_version_id UUID,
        version_number INTEGER DEFAULT 1,
        version INTEGER NOT NULL DEFAULT 1,
        published_at TIMESTAMPTZ,
        published_by UUID,
        publish_note TEXT,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ
    )', p_schema_name);

    -- =====================================================
    -- 7. 风格表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_style (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        scope VARCHAR(20) DEFAULT ''WORKSPACE'',
        script_id UUID,
        name VARCHAR(200) NOT NULL,
        description TEXT,
        fixed_desc TEXT,
        style_params JSONB DEFAULT ''{}''::jsonb,
        cover_asset_id UUID,
        extra_info JSONB DEFAULT ''{}''::jsonb,
        current_version_id UUID,
        version_number INTEGER DEFAULT 1,
        version INTEGER NOT NULL DEFAULT 1,
        published_at TIMESTAMPTZ,
        published_by UUID,
        publish_note TEXT,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ
    )', p_schema_name);

    -- =====================================================
    -- 8. 实体关系表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_entity_relation (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        source_type VARCHAR(50) NOT NULL,
        source_id UUID NOT NULL,
        source_version_id UUID,
        target_type VARCHAR(50) NOT NULL,
        target_id UUID NOT NULL,
        target_version_id UUID,
        relation_type VARCHAR(100) NOT NULL,
        relation_label VARCHAR(100),
        description TEXT,
        sequence INTEGER DEFAULT 0,
        extra_info JSONB DEFAULT ''{}''::jsonb,
        version INTEGER NOT NULL DEFAULT 1,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ,
        UNIQUE (source_type, source_id, target_type, target_id, relation_type)
    )', p_schema_name);

    -- =====================================================
    -- 9. 画布表 (Canvas) - 统一主画布模型：1 Script = 1 Canvas
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_canvas (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        script_id UUID NOT NULL,
        name VARCHAR(200),
        description TEXT,
        viewport JSONB DEFAULT ''{"x": 0, "y": 0, "zoom": 1}''::jsonb,
        layout_strategy VARCHAR(50) DEFAULT ''GRID'',
        locked BOOLEAN DEFAULT FALSE,
        settings JSONB DEFAULT ''{}''::jsonb,
        version INTEGER NOT NULL DEFAULT 1,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ,
        UNIQUE (script_id)
    )', p_schema_name);

    -- 添加画布表注释
    EXECUTE format('COMMENT ON TABLE %I.t_canvas IS ''画布表 - 每个剧本对应一个画布''', p_schema_name);
    EXECUTE format('COMMENT ON COLUMN %I.t_canvas.script_id IS ''关联的剧本ID，1:1关系''', p_schema_name);

    -- =====================================================
    -- 9.1 画布边/连线表 (CanvasEdge)
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_canvas_edge (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        canvas_id UUID NOT NULL,
        source_type VARCHAR(50) NOT NULL,
        source_id UUID NOT NULL,
        source_version_id UUID,
        source_handle VARCHAR(50),
        target_type VARCHAR(50) NOT NULL,
        target_id UUID NOT NULL,
        target_version_id UUID,
        target_handle VARCHAR(50),
        relation_type VARCHAR(100) NOT NULL,
        relation_label VARCHAR(100),
        description TEXT,
        line_style JSONB DEFAULT ''{"strokeColor": "#666", "strokeWidth": 2, "strokeStyle": "solid", "animated": false}''::jsonb,
        path_type VARCHAR(50) DEFAULT ''bezier'',
        sequence INTEGER DEFAULT 0,
        extra_info JSONB DEFAULT ''{}''::jsonb,
        version INTEGER NOT NULL DEFAULT 1,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ,
        UNIQUE (canvas_id, source_type, source_id, target_type, target_id, relation_type)
    )', p_schema_name);

    -- =====================================================
    -- 10. 剧本权限表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_script_permission (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        script_id UUID NOT NULL,
        user_id UUID NOT NULL,
        permission_type VARCHAR(50) NOT NULL,
        grant_source VARCHAR(50) NOT NULL DEFAULT ''WORKSPACE_ADMIN'',
        granted_by UUID NOT NULL,
        granted_at TIMESTAMPTZ DEFAULT now(),
        expires_at TIMESTAMPTZ,
        version INTEGER NOT NULL DEFAULT 1,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ,
        UNIQUE (script_id, user_id)
    )', p_schema_name);

    -- =====================================================
    -- 11. 素材表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_asset (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        scope VARCHAR(20) DEFAULT ''WORKSPACE'',
        script_id UUID,
        name VARCHAR(500) NOT NULL,
        description TEXT,
        asset_type VARCHAR(50) NOT NULL,
        source VARCHAR(50) NOT NULL DEFAULT ''UPLOAD'',
        file_key TEXT,
        file_url TEXT,
        thumbnail_url TEXT,
        file_size BIGINT,
        mime_type VARCHAR(100),
        meta_info JSONB DEFAULT ''{}''::jsonb,
        extra_info JSONB DEFAULT ''{}''::jsonb,
        generation_status VARCHAR(20) NOT NULL DEFAULT ''DRAFT'',
        workflow_id UUID,
        task_id UUID,
        current_version_id UUID,
        version_number INTEGER DEFAULT 1,
        version INTEGER NOT NULL DEFAULT 1,
        published_at TIMESTAMPTZ,
        published_by UUID,
        publish_note TEXT,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ,
        trash_path TEXT
    )', p_schema_name);

    -- =====================================================
    -- 12. 素材溯源表（AI生成关系）
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_asset_lineage (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        output_asset_id UUID NOT NULL,
        input_type VARCHAR(50) NOT NULL,
        input_id UUID NOT NULL,
        input_role VARCHAR(50),
        sequence INTEGER DEFAULT 0,
        task_id UUID,
        extra_info JSONB DEFAULT ''{}''::jsonb,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID
    )', p_schema_name);

    -- =====================================================
    -- 12.1 实体-素材关联表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_entity_asset_relation (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        entity_type VARCHAR(50) NOT NULL,
        entity_id UUID NOT NULL,
        asset_id UUID NOT NULL,
        relation_type VARCHAR(50) NOT NULL,
        description TEXT,
        sequence INTEGER DEFAULT 0,
        extra_info JSONB DEFAULT ''{}''::jsonb,
        version INTEGER NOT NULL DEFAULT 1,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ,
        UNIQUE (entity_type, entity_id, asset_id, relation_type)
    )', p_schema_name);

    -- 添加注释
    EXECUTE format('COMMENT ON TABLE %I.t_entity_asset_relation IS ''实体-素材关联表''', p_schema_name);
    EXECUTE format('COMMENT ON COLUMN %I.t_entity_asset_relation.entity_type IS ''实体类型: SCRIPT, EPISODE, STORYBOARD, CHARACTER, SCENE, PROP, STYLE''', p_schema_name);
    EXECUTE format('COMMENT ON COLUMN %I.t_entity_asset_relation.relation_type IS ''关联类型: REFERENCE(参考), OFFICIAL(正式), DRAFT(草稿)''', p_schema_name);

    -- =====================================================
    -- 13. 提示词模板表（租户级）
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_prompt_template (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        scope VARCHAR(20) DEFAULT ''WORKSPACE'',
        name VARCHAR(200) NOT NULL,
        description TEXT,
        category VARCHAR(100),
        template_content TEXT NOT NULL,
        parameters JSONB DEFAULT ''{}''::jsonb,
        usage_count INTEGER DEFAULT 0,
        is_favorite BOOLEAN DEFAULT FALSE,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ
    )', p_schema_name);

    -- =====================================================
    -- 14. 标签表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_tag (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        scope VARCHAR(20) DEFAULT ''WORKSPACE'',
        name VARCHAR(100) NOT NULL,
        color VARCHAR(20) DEFAULT ''#666666'',
        description TEXT,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ
    )', p_schema_name);

    -- =====================================================
    -- 15. 标签关系表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_tag_relation (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        tag_id UUID NOT NULL,
        target_type VARCHAR(50) NOT NULL,
        target_id UUID NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        UNIQUE (tag_id, target_type, target_id)
    )', p_schema_name);

    -- =====================================================
    -- 17. 评论表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_comment (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        target_type VARCHAR(50) NOT NULL,
        target_id UUID NOT NULL,
        script_id UUID,
        parent_id UUID,
        content TEXT NOT NULL,
        mentions JSONB DEFAULT ''[]''::jsonb,
        status VARCHAR(20) DEFAULT ''OPEN'',
        resolved_by UUID,
        resolved_at TIMESTAMPTZ,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ,
        version INTEGER NOT NULL DEFAULT 0
    )', p_schema_name);

    -- =====================================================
    -- 17b. 评论附件表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_comment_attachment (
        id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        comment_id     UUID NOT NULL,
        asset_id       UUID NOT NULL,
        asset_type     VARCHAR(20),
        file_name      VARCHAR(500),
        file_url       VARCHAR(1000),
        thumbnail_url  VARCHAR(1000),
        file_size      BIGINT,
        mime_type      VARCHAR(100),
        meta_info      JSONB DEFAULT ''{}''::jsonb,
        sequence       INTEGER DEFAULT 0,
        created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
        UNIQUE (comment_id, asset_id)
    )', p_schema_name);

    -- =====================================================
    -- 17c. 评论表情反应表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_comment_reaction (
        id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        comment_id   UUID NOT NULL,
        emoji        VARCHAR(20) NOT NULL,
        created_by   UUID NOT NULL,
        created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
        UNIQUE (comment_id, created_by, emoji)
    )', p_schema_name);

    -- =====================================================
    -- 17d. 实体关注表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_entity_watch (
        id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        user_id      UUID NOT NULL,
        entity_type  VARCHAR(50) NOT NULL,
        entity_id    UUID NOT NULL,
        watch_type   VARCHAR(20) DEFAULT ''ALL'',
        created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
        UNIQUE (user_id, entity_type, entity_id)
    )', p_schema_name);

    -- =====================================================
    -- 17e. 审核表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_review (
        id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id   UUID NOT NULL,
        entity_type    VARCHAR(50) NOT NULL,
        entity_id      UUID NOT NULL,
        title          VARCHAR(500),
        description    TEXT,
        status         VARCHAR(20) NOT NULL DEFAULT ''PENDING'',
        requester_id   UUID NOT NULL,
        reviewer_id    UUID,
        reviewed_at    TIMESTAMPTZ,
        review_comment TEXT,
        version_number INTEGER,
        created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by     UUID,
        updated_by     UUID,
        deleted        INTEGER NOT NULL DEFAULT 0,
        deleted_at     TIMESTAMPTZ,
        version        INTEGER NOT NULL DEFAULT 0
    )', p_schema_name);

    -- =====================================================
    -- 18. 操作日志表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_audit_log (
        id UUID NOT NULL DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        user_id UUID,
        action VARCHAR(100) NOT NULL,
        resource_type VARCHAR(50),
        resource_id UUID,
        details JSONB DEFAULT ''{}''::jsonb,
        ip_address VARCHAR(50),
        user_agent TEXT,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        PRIMARY KEY (id, created_at)
    ) PARTITION BY RANGE (created_at)', p_schema_name);

    EXECUTE format('CREATE TABLE IF NOT EXISTS %I.t_audit_log_default PARTITION OF %I.t_audit_log DEFAULT', p_schema_name, p_schema_name);

    -- =====================================================
    -- 19. 向量文档表（RAG 知识库）
    -- 注：t_agent_session / t_agent_message 已统一在 public schema 管理，
    --     agent 不使用租户 schema 隔离会话数据，此处不再创建
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_vector_document (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        document_type VARCHAR(50) NOT NULL,
        content TEXT NOT NULL,
        ref_id UUID,
        ref_type VARCHAR(50),
        metadata JSONB DEFAULT ''{}''::jsonb,
        embedding vector(768),
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ,
        version INTEGER NOT NULL DEFAULT 0
    )', p_schema_name);

    -- =====================================================
    -- 22. 知识库表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_knowledge_base (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        name VARCHAR(200) NOT NULL,
        description TEXT,
        source_type VARCHAR(50) NOT NULL,
        source_url TEXT,
        document_count INTEGER DEFAULT 0,
        status VARCHAR(20) NOT NULL DEFAULT ''ACTIVE'',
        metadata JSONB DEFAULT ''{}''::jsonb,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ,
        version INTEGER NOT NULL DEFAULT 0
    )', p_schema_name);

    -- =====================================================
    -- 22. 画布节点表 (独立存储节点位置) - 增加层级和父节点支持
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_canvas_node (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        canvas_id UUID NOT NULL,
        entity_type VARCHAR(50) NOT NULL,
        entity_id UUID NOT NULL,
        entity_version_id UUID,
        layer VARCHAR(50) NOT NULL,
        parent_node_id UUID,
        position_x DECIMAL(10,2) DEFAULT 0,
        position_y DECIMAL(10,2) DEFAULT 0,
        width DECIMAL(10,2) DEFAULT 200,
        height DECIMAL(10,2) DEFAULT 150,
        collapsed BOOLEAN DEFAULT FALSE,
        locked BOOLEAN DEFAULT FALSE,
        hidden BOOLEAN DEFAULT FALSE,
        z_index INTEGER DEFAULT 0,
        style JSONB DEFAULT ''{}''::jsonb,
        cached_name VARCHAR(500),
        cached_thumbnail_url TEXT,
        cached_status VARCHAR(50),
        version INTEGER NOT NULL DEFAULT 1,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ,
        UNIQUE (canvas_id, entity_type, entity_id)
    )', p_schema_name);

    -- 添加画布节点表注释
    EXECUTE format('COMMENT ON TABLE %I.t_canvas_node IS ''画布节点表 - 存储节点位置和样式''', p_schema_name);
    EXECUTE format('COMMENT ON COLUMN %I.t_canvas_node.layer IS ''节点层级: SCRIPT, EPISODE, STORYBOARD, CHARACTER, SCENE, PROP, ASSET''', p_schema_name);
    EXECUTE format('COMMENT ON COLUMN %I.t_canvas_node.parent_node_id IS ''父节点ID，用于层级关系''', p_schema_name);
    EXECUTE format('COMMENT ON COLUMN %I.t_canvas_node.hidden IS ''节点是否隐藏''', p_schema_name);
    EXECUTE format('COMMENT ON COLUMN %I.t_canvas_node.cached_status IS ''缓存的实体状态''', p_schema_name);

    -- =====================================================
    -- 22.1 画布视图表 (Canvas View) - 预设和自定义视图
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_canvas_view (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        canvas_id UUID NOT NULL,
        view_key VARCHAR(100) NOT NULL,
        name VARCHAR(200) NOT NULL,
        icon VARCHAR(50),
        view_type VARCHAR(50) NOT NULL DEFAULT ''PRESET'',
        root_entity_type VARCHAR(50),
        visible_entity_types TEXT[] NOT NULL,
        visible_layers TEXT[],
        filter_config JSONB DEFAULT ''{}''::jsonb,
        viewport JSONB,
        layout_strategy VARCHAR(50),
        sequence INTEGER DEFAULT 0,
        is_default BOOLEAN DEFAULT FALSE,
        version INTEGER NOT NULL DEFAULT 1,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ,
        UNIQUE (canvas_id, view_key)
    )', p_schema_name);

    -- 添加画布视图表注释
    EXECUTE format('COMMENT ON TABLE %I.t_canvas_view IS ''画布视图表 - 存储预设和自定义视图配置''', p_schema_name);
    EXECUTE format('COMMENT ON COLUMN %I.t_canvas_view.view_key IS ''视图标识: SCRIPT, EPISODE, STORYBOARD, CHARACTER, SCENE, PROP, ASSET''', p_schema_name);
    EXECUTE format('COMMENT ON COLUMN %I.t_canvas_view.view_type IS ''视图类型: PRESET(预设), CUSTOM(自定义)''', p_schema_name);
    EXECUTE format('COMMENT ON COLUMN %I.t_canvas_view.root_entity_type IS ''根节点实体类型''', p_schema_name);
    EXECUTE format('COMMENT ON COLUMN %I.t_canvas_view.visible_entity_types IS ''可见实体类型数组''', p_schema_name);
    EXECUTE format('COMMENT ON COLUMN %I.t_canvas_view.is_default IS ''是否为默认视图''', p_schema_name);

    -- =====================================================
    -- 23. 通用实体版本表 (替代 8 张独立版本表)
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_entity_version (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        entity_type VARCHAR(50) NOT NULL,
        entity_id UUID NOT NULL,
        version_number INTEGER NOT NULL,
        snapshot JSONB NOT NULL DEFAULT ''{}''::jsonb,
        change_summary VARCHAR(500),
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        UNIQUE (entity_type, entity_id, version_number)
    )', p_schema_name);

    EXECUTE format('COMMENT ON TABLE %I.t_entity_version IS ''通用实体版本历史表''', p_schema_name);
    EXECUTE format('COMMENT ON COLUMN %I.t_entity_version.entity_type IS ''实体类型: SCRIPT, EPISODE, STORYBOARD, CHARACTER, SCENE, PROP, STYLE, ASSET''', p_schema_name);
    EXECUTE format('COMMENT ON COLUMN %I.t_entity_version.entity_id IS ''实体ID''', p_schema_name);
    EXECUTE format('COMMENT ON COLUMN %I.t_entity_version.snapshot IS ''版本快照 (完整实体数据的 JSON 副本)''', p_schema_name);

    -- =====================================================
    -- 24. 实体版本表（按实体类型独立建表，与 Java 版本实体对应）
    -- =====================================================

    -- 24.1 剧本版本
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_script_version (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        script_id UUID NOT NULL,
        workspace_id UUID NOT NULL,
        version_number INTEGER NOT NULL,
        change_summary VARCHAR(500),
        title VARCHAR(500),
        status VARCHAR(20),
        synopsis TEXT,
        content TEXT,
        cover_asset_id UUID,
        doc_asset_id UUID,
        extra_info JSONB DEFAULT ''{}''::jsonb,
        created_by UUID,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    )', p_schema_name);

    -- 24.2 剧集版本
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_episode_version (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        episode_id UUID NOT NULL,
        script_id UUID,
        workspace_id UUID NOT NULL,
        version_number INTEGER NOT NULL,
        change_summary VARCHAR(500),
        title VARCHAR(500),
        sequence INTEGER,
        status VARCHAR(20),
        synopsis TEXT,
        content TEXT,
        cover_asset_id UUID,
        doc_asset_id UUID,
        extra_info JSONB DEFAULT ''{}''::jsonb,
        created_by UUID,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    )', p_schema_name);

    -- 24.3 分镜版本
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_storyboard_version (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        storyboard_id UUID NOT NULL,
        script_id UUID,
        episode_id UUID,
        workspace_id UUID NOT NULL,
        version_number INTEGER NOT NULL,
        change_summary VARCHAR(500),
        title VARCHAR(500),
        sequence INTEGER,
        status VARCHAR(20),
        synopsis TEXT,
        duration INTEGER,
        visual_desc JSONB DEFAULT ''{}''::jsonb,
        audio_desc JSONB DEFAULT ''{}''::jsonb,
        extra_info JSONB DEFAULT ''{}''::jsonb,
        cover_asset_id UUID,
        created_by UUID,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    )', p_schema_name);

    -- 24.4 角色版本
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_character_version (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        character_id UUID NOT NULL,
        scope VARCHAR(50),
        script_id UUID,
        workspace_id UUID NOT NULL,
        version_number INTEGER NOT NULL,
        change_summary VARCHAR(500),
        name VARCHAR(200),
        description TEXT,
        fixed_desc TEXT,
        age INTEGER,
        gender VARCHAR(50),
        character_type VARCHAR(50),
        voice_seed_id UUID,
        appearance_data JSONB DEFAULT ''{}''::jsonb,
        cover_asset_id UUID,
        extra_info JSONB DEFAULT ''{}''::jsonb,
        created_by UUID,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    )', p_schema_name);

    -- 24.5 场景版本
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_scene_version (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        scene_id UUID NOT NULL,
        scope VARCHAR(50),
        scene_type VARCHAR(50),
        script_id UUID,
        workspace_id UUID NOT NULL,
        version_number INTEGER NOT NULL,
        change_summary VARCHAR(500),
        name VARCHAR(200),
        description TEXT,
        fixed_desc TEXT,
        appearance_data JSONB DEFAULT ''{}''::jsonb,
        cover_asset_id UUID,
        extra_info JSONB DEFAULT ''{}''::jsonb,
        created_by UUID,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    )', p_schema_name);

    -- 24.6 道具版本
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_prop_version (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        prop_id UUID NOT NULL,
        scope VARCHAR(50),
        script_id UUID,
        workspace_id UUID NOT NULL,
        version_number INTEGER NOT NULL,
        change_summary VARCHAR(500),
        name VARCHAR(200),
        description TEXT,
        fixed_desc TEXT,
        prop_type VARCHAR(50),
        appearance_data JSONB DEFAULT ''{}''::jsonb,
        cover_asset_id UUID,
        extra_info JSONB DEFAULT ''{}''::jsonb,
        created_by UUID,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    )', p_schema_name);

    -- 24.7 风格版本
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_style_version (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        style_id UUID NOT NULL,
        scope VARCHAR(50),
        script_id UUID,
        workspace_id UUID NOT NULL,
        version_number INTEGER NOT NULL,
        change_summary VARCHAR(500),
        name VARCHAR(200),
        description TEXT,
        fixed_desc TEXT,
        style_params JSONB DEFAULT ''{}''::jsonb,
        cover_asset_id UUID,
        extra_info JSONB DEFAULT ''{}''::jsonb,
        created_by UUID,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    )', p_schema_name);

    -- 24.8 素材版本
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_asset_version (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        asset_id UUID NOT NULL,
        scope VARCHAR(50),
        script_id UUID,
        workspace_id UUID NOT NULL,
        version_number INTEGER NOT NULL,
        change_summary VARCHAR(500),
        name VARCHAR(200),
        description TEXT,
        asset_type VARCHAR(50),
        source VARCHAR(500),
        file_key VARCHAR(500),
        file_url VARCHAR(1000),
        thumbnail_url VARCHAR(1000),
        file_size BIGINT,
        mime_type VARCHAR(100),
        meta_info JSONB DEFAULT ''{}''::jsonb,
        extra_info JSONB DEFAULT ''{}''::jsonb,
        generation_status VARCHAR(50),
        workflow_id UUID,
        task_id UUID,
        created_by UUID,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
    )', p_schema_name);

    -- =====================================================
    -- 灵感会话表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_inspiration_session (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        user_id UUID NOT NULL,
        title VARCHAR(200) NOT NULL DEFAULT '''',
        cover_url TEXT,
        record_count INTEGER NOT NULL DEFAULT 0,
        total_credits DECIMAL(10,2) NOT NULL DEFAULT 0,
        status VARCHAR(20) NOT NULL DEFAULT ''ACTIVE'',
        last_active_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ,
        version INTEGER NOT NULL DEFAULT 1
    )', p_schema_name);

    -- =====================================================
    -- 灵感生成记录表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_inspiration_record (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        session_id UUID NOT NULL,
        prompt TEXT NOT NULL,
        negative_prompt TEXT,
        generation_type VARCHAR(20) NOT NULL,
        provider_id UUID,
        provider_name VARCHAR(100),
        provider_icon_url TEXT,
        params JSONB DEFAULT ''{}''::jsonb,
        status VARCHAR(20) NOT NULL DEFAULT ''PENDING'',
        task_id UUID,
        credit_cost DECIMAL(10,2) NOT NULL DEFAULT 0,
        progress INTEGER NOT NULL DEFAULT 0,
        error_message TEXT,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        completed_at TIMESTAMPTZ,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ,
        version INTEGER NOT NULL DEFAULT 1
    )', p_schema_name);

    -- =====================================================
    -- 灵感记录资产关联表
    -- =====================================================
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_inspiration_record_asset (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        record_id UUID NOT NULL,
        asset_id UUID,
        asset_type VARCHAR(20) NOT NULL,
        url TEXT NOT NULL,
        thumbnail_url TEXT,
        width INTEGER,
        height INTEGER,
        duration FLOAT,
        mime_type VARCHAR(100),
        file_size BIGINT,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by UUID,
        updated_by UUID,
        deleted INTEGER NOT NULL DEFAULT 0,
        deleted_at TIMESTAMPTZ,
        version INTEGER NOT NULL DEFAULT 1
    )', p_schema_name);

    -- =====================================================
    -- 租户表 updated_at 自动更新触发器
    -- =====================================================
    DECLARE
        tenant_tables TEXT[] := ARRAY[
            't_script', 't_episode', 't_storyboard', 't_character', 't_scene',
            't_prop', 't_style', 't_entity_relation', 't_canvas', 't_canvas_edge',
            't_script_permission', 't_asset', 't_entity_asset_relation',
            't_prompt_template', 't_tag', 't_comment', 't_review', 't_vector_document',
            't_knowledge_base', 't_canvas_node', 't_canvas_view',
            't_inspiration_session', 't_inspiration_record', 't_inspiration_record_asset'
        ];
        tbl TEXT;
    BEGIN
        FOREACH tbl IN ARRAY tenant_tables LOOP
            EXECUTE format('DROP TRIGGER IF EXISTS trigger_%s_updated_at ON %I.%I', tbl, p_schema_name, tbl);
            EXECUTE format('CREATE TRIGGER trigger_%s_updated_at BEFORE UPDATE ON %I.%I
                            FOR EACH ROW EXECUTE FUNCTION trigger_set_timestamp()', tbl, p_schema_name, tbl);
        END LOOP;
    END;

    -- =====================================================
    -- 租户表内 FK 补全（幂等：已存在则跳过）
    -- =====================================================
    DECLARE
        v_fk_defs TEXT[][] := ARRAY[
            ARRAY['t_episode',          'fk_episode_script',            'script_id',  't_script'],
            ARRAY['t_storyboard',       'fk_storyboard_script',         'script_id',  't_script'],
            ARRAY['t_storyboard',       'fk_storyboard_episode',        'episode_id', 't_episode'],
            ARRAY['t_canvas',           'fk_canvas_script',             'script_id',  't_script'],
            ARRAY['t_canvas_edge',      'fk_canvas_edge_canvas',        'canvas_id',  't_canvas'],
            ARRAY['t_canvas_node',      'fk_canvas_node_canvas',        'canvas_id',  't_canvas'],
            ARRAY['t_canvas_view',      'fk_canvas_view_canvas',        'canvas_id',  't_canvas'],
            ARRAY['t_tag_relation',     'fk_tag_relation_tag',          'tag_id',     't_tag'],
            ARRAY['t_script_permission','fk_script_permission_script',  'script_id',  't_script'],
            ARRAY['t_inspiration_record','fk_inspiration_record_session','session_id', 't_inspiration_session'],
            ARRAY['t_inspiration_record_asset','fk_inspiration_asset_record','record_id','t_inspiration_record']
        ];
        v_fk TEXT[];
    BEGIN
        FOREACH v_fk SLICE 1 IN ARRAY v_fk_defs LOOP
            IF NOT EXISTS (
                SELECT 1 FROM pg_constraint c
                JOIN pg_namespace n ON n.oid = c.connamespace
                WHERE n.nspname = p_schema_name AND c.conname = v_fk[2]
            ) THEN
                EXECUTE format(
                    'ALTER TABLE %I.%I ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %I.%I(id) ON DELETE CASCADE',
                    p_schema_name, v_fk[1], v_fk[2], v_fk[3], p_schema_name, v_fk[4]
                );
            END IF;
        END LOOP;
    END;

    RAISE NOTICE 'Tenant schema % created successfully', p_schema_name;
END;
$$ LANGUAGE plpgsql;

-- =====================================================
-- 租户索引创建函数
-- =====================================================

CREATE OR REPLACE FUNCTION create_tenant_indexes(p_schema_name VARCHAR)
RETURNS VOID AS $$
BEGIN
    -- Scripts
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_script_workspace_id ON %I.t_script(workspace_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_script_status ON %I.t_script(status) WHERE deleted = 0', p_schema_name);

    -- Episodes
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_episode_script_id ON %I.t_episode(script_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_episode_sequence ON %I.t_episode(script_id, sequence) WHERE deleted = 0', p_schema_name);

    -- Storyboards
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_storyboard_episode_id ON %I.t_storyboard(episode_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_storyboard_sequence ON %I.t_storyboard(episode_id, sequence) WHERE deleted = 0', p_schema_name);

    -- Characters
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_character_workspace_id ON %I.t_character(workspace_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_character_scope ON %I.t_character(scope) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_character_script_id ON %I.t_character(script_id) WHERE script_id IS NOT NULL AND deleted = 0', p_schema_name);

    -- Scenes
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_scene_workspace_id ON %I.t_scene(workspace_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_scene_scope ON %I.t_scene(scope) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_scene_script_id ON %I.t_scene(script_id) WHERE script_id IS NOT NULL AND deleted = 0', p_schema_name);

    -- Props
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_prop_workspace_id ON %I.t_prop(workspace_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_prop_scope ON %I.t_prop(scope) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_prop_script_id ON %I.t_prop(script_id) WHERE script_id IS NOT NULL AND deleted = 0', p_schema_name);

    -- Styles
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_style_workspace_id ON %I.t_style(workspace_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_style_scope ON %I.t_style(scope) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_style_script_id ON %I.t_style(script_id) WHERE script_id IS NOT NULL AND deleted = 0', p_schema_name);

    -- Entity Relations
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_entity_relation_source ON %I.t_entity_relation(source_type, source_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_entity_relation_target ON %I.t_entity_relation(target_type, target_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_entity_relation_type ON %I.t_entity_relation(relation_type) WHERE deleted = 0', p_schema_name);

    -- Canvas - 更新为 script_id 索引
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_canvas_script_id ON %I.t_canvas(script_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_canvas_workspace_id ON %I.t_canvas(workspace_id) WHERE deleted = 0', p_schema_name);

    -- Canvas Edges
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_canvas_edge_canvas_id ON %I.t_canvas_edge(canvas_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_canvas_edge_source ON %I.t_canvas_edge(source_type, source_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_canvas_edge_target ON %I.t_canvas_edge(target_type, target_id) WHERE deleted = 0', p_schema_name);

    -- Script Permissions
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_script_permission_script ON %I.t_script_permission(script_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_script_permission_user ON %I.t_script_permission(user_id) WHERE deleted = 0', p_schema_name);

    -- Assets
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_asset_workspace_id ON %I.t_asset(workspace_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_asset_type ON %I.t_asset(asset_type) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_asset_source ON %I.t_asset(source) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_asset_scope ON %I.t_asset(scope) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_asset_script_id ON %I.t_asset(script_id) WHERE script_id IS NOT NULL AND deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_asset_created_by ON %I.t_asset(created_by) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_asset_generation_status ON %I.t_asset(generation_status) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_asset_task_id ON %I.t_asset(task_id) WHERE task_id IS NOT NULL AND deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_asset_workflow_id ON %I.t_asset(workflow_id) WHERE workflow_id IS NOT NULL AND deleted = 0', p_schema_name);

    -- Asset Lineage
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_asset_lineage_output ON %I.t_asset_lineage(output_asset_id)', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_asset_lineage_input ON %I.t_asset_lineage(input_type, input_id)', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_asset_lineage_task ON %I.t_asset_lineage(task_id) WHERE task_id IS NOT NULL', p_schema_name);

    -- Entity-Asset Relations
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_entity_asset_relation_entity ON %I.t_entity_asset_relation(entity_type, entity_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_entity_asset_relation_asset ON %I.t_entity_asset_relation(asset_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_entity_asset_relation_workspace ON %I.t_entity_asset_relation(workspace_id) WHERE deleted = 0', p_schema_name);

    -- Comments
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_comment_target ON %I.t_comment(target_type, target_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_comment_script ON %I.t_comment(script_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_comment_parent ON %I.t_comment(parent_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_comment_status ON %I.t_comment(status) WHERE deleted = 0', p_schema_name);

    -- Comment Attachments
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_comment_attachment_comment ON %I.t_comment_attachment(comment_id)', p_schema_name);

    -- Comment Reactions
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_comment_reaction_comment ON %I.t_comment_reaction(comment_id)', p_schema_name);

    -- Entity Watches
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_entity_watch_entity ON %I.t_entity_watch(entity_type, entity_id)', p_schema_name);

    -- Reviews
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_review_entity ON %I.t_review(entity_type, entity_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_review_reviewer ON %I.t_review(reviewer_id, status) WHERE deleted = 0', p_schema_name);

    -- Audit Logs
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_audit_log_created_at ON %I.t_audit_log(created_at DESC)', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_audit_log_resource ON %I.t_audit_log(resource_type, resource_id)', p_schema_name);

    -- 注：t_agent_session / t_agent_message 已统一在 public schema 管理，无需在租户 schema 创建索引
    -- 注：t_agent_billing_session 索引已移至 public schema (01-core-schema.sql)

    -- Vector Documents
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_vector_document_workspace_id ON %I.t_vector_document(workspace_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_vector_document_type ON %I.t_vector_document(document_type) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_vector_document_ref ON %I.t_vector_document(ref_type, ref_id) WHERE ref_id IS NOT NULL AND deleted = 0', p_schema_name);
    -- Note: Vector index (IVFFlat) needs to be created separately after data is inserted

    -- Knowledge Base
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_knowledge_base_workspace_id ON %I.t_knowledge_base(workspace_id) WHERE deleted = 0', p_schema_name);

    -- Canvas Nodes - 增加 layer 和 parent_node_id 索引
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_canvas_node_canvas_id ON %I.t_canvas_node(canvas_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_canvas_node_entity ON %I.t_canvas_node(entity_type, entity_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_canvas_node_layer ON %I.t_canvas_node(canvas_id, layer) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_canvas_node_parent ON %I.t_canvas_node(parent_node_id) WHERE parent_node_id IS NOT NULL AND deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_canvas_node_hidden ON %I.t_canvas_node(canvas_id, hidden) WHERE deleted = 0', p_schema_name);

    -- Canvas Views
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_canvas_view_canvas_id ON %I.t_canvas_view(canvas_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_canvas_view_view_key ON %I.t_canvas_view(canvas_id, view_key) WHERE deleted = 0', p_schema_name);

    -- Entity Version
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_entity_version_entity ON %I.t_entity_version(entity_type, entity_id)', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_entity_version_workspace ON %I.t_entity_version(workspace_id)', p_schema_name);

    -- Per-entity version table indexes
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_script_version_fk ON %I.t_script_version(script_id, version_number DESC)', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_episode_version_fk ON %I.t_episode_version(episode_id, version_number DESC)', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_storyboard_version_fk ON %I.t_storyboard_version(storyboard_id, version_number DESC)', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_character_version_fk ON %I.t_character_version(character_id, version_number DESC)', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_scene_version_fk ON %I.t_scene_version(scene_id, version_number DESC)', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_prop_version_fk ON %I.t_prop_version(prop_id, version_number DESC)', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_style_version_fk ON %I.t_style_version(style_id, version_number DESC)', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_asset_version_fk ON %I.t_asset_version(asset_id, version_number DESC)', p_schema_name);

    -- Inspiration Session
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_inspiration_session_workspace ON %I.t_inspiration_session(workspace_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_inspiration_session_user ON %I.t_inspiration_session(user_id, last_active_at DESC) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_inspiration_session_status ON %I.t_inspiration_session(status) WHERE deleted = 0', p_schema_name);

    -- Inspiration Record
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_inspiration_record_session ON %I.t_inspiration_record(session_id, created_at ASC) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_inspiration_record_task ON %I.t_inspiration_record(task_id) WHERE task_id IS NOT NULL AND deleted = 0', p_schema_name);

    -- Inspiration Record Asset
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_inspiration_record_asset_record ON %I.t_inspiration_record_asset(record_id) WHERE deleted = 0', p_schema_name);

    RAISE NOTICE 'Indexes for schema % created successfully', p_schema_name;
END;
$$ LANGUAGE plpgsql;

-- =====================================================
-- 完整初始化工作空间函数
-- =====================================================

CREATE OR REPLACE FUNCTION initialize_workspace(
    p_workspace_id VARCHAR,
    p_schema_name VARCHAR
)
RETURNS VOID AS $$
BEGIN
    -- 创建 Schema 和表
    PERFORM create_tenant_schema(p_schema_name);

    -- 创建索引
    PERFORM create_tenant_indexes(p_schema_name);

    RAISE NOTICE 'Workspace % initialized with schema %', p_workspace_id, p_schema_name;
END;
$$ LANGUAGE plpgsql;

-- =====================================================
-- 删除工作空间 Schema 函数
-- =====================================================

CREATE OR REPLACE FUNCTION drop_workspace_schema(p_schema_name VARCHAR)
RETURNS VOID AS $$
BEGIN
    EXECUTE format('DROP SCHEMA IF EXISTS %I CASCADE', p_schema_name);
    RAISE NOTICE 'Schema % dropped successfully', p_schema_name;
END;
$$ LANGUAGE plpgsql;

-- =====================================================
-- 完成
-- =====================================================

DO $$
BEGIN
    RAISE NOTICE 'Actionow tenant schema functions initialized successfully!';
END $$;

-- =====================================================
-- 画布预设视图初始化函数
-- =====================================================

CREATE OR REPLACE FUNCTION init_canvas_preset_views(
    p_schema_name VARCHAR,
    p_canvas_id UUID,
    p_workspace_id UUID,
    p_created_by UUID DEFAULT NULL
)
RETURNS VOID AS $$
DECLARE
    v_now TIMESTAMPTZ := now();
BEGIN
    -- SCRIPT 视图（默认视图）- 全景视图
    EXECUTE format('
    INSERT INTO %I.t_canvas_view (
        id, workspace_id, canvas_id, view_key, name, icon, view_type,
        root_entity_type, visible_entity_types, sequence, is_default,
        created_at, updated_at, created_by
    ) VALUES (
        gen_random_uuid(), $1, $2,
        ''SCRIPT'', ''剧本全景'', ''film'', ''PRESET'',
        ''SCRIPT'', ARRAY[''SCRIPT'',''EPISODE'',''STORYBOARD'',''CHARACTER'',''SCENE'',''PROP'',''ASSET''],
        0, TRUE, $3, $3, $4
    ) ON CONFLICT (canvas_id, view_key) DO NOTHING', p_schema_name)
    USING p_workspace_id, p_canvas_id, v_now, p_created_by;

    -- EPISODE 视图 - 剧集视图
    EXECUTE format('
    INSERT INTO %I.t_canvas_view (
        id, workspace_id, canvas_id, view_key, name, icon, view_type,
        root_entity_type, visible_entity_types, sequence, is_default,
        created_at, updated_at, created_by
    ) VALUES (
        gen_random_uuid(), $1, $2,
        ''EPISODE'', ''剧集视图'', ''video'', ''PRESET'',
        ''EPISODE'', ARRAY[''EPISODE'',''STORYBOARD'',''CHARACTER'',''SCENE'',''PROP'',''ASSET''],
        1, FALSE, $3, $3, $4
    ) ON CONFLICT (canvas_id, view_key) DO NOTHING', p_schema_name)
    USING p_workspace_id, p_canvas_id, v_now, p_created_by;

    -- STORYBOARD 视图 - 分镜视图
    EXECUTE format('
    INSERT INTO %I.t_canvas_view (
        id, workspace_id, canvas_id, view_key, name, icon, view_type,
        root_entity_type, visible_entity_types, sequence, is_default,
        created_at, updated_at, created_by
    ) VALUES (
        gen_random_uuid(), $1, $2,
        ''STORYBOARD'', ''分镜视图'', ''layout'', ''PRESET'',
        ''STORYBOARD'', ARRAY[''STORYBOARD'',''CHARACTER'',''SCENE'',''PROP'',''ASSET''],
        2, FALSE, $3, $3, $4
    ) ON CONFLICT (canvas_id, view_key) DO NOTHING', p_schema_name)
    USING p_workspace_id, p_canvas_id, v_now, p_created_by;

    -- CHARACTER 视图 - 角色视图
    EXECUTE format('
    INSERT INTO %I.t_canvas_view (
        id, workspace_id, canvas_id, view_key, name, icon, view_type,
        root_entity_type, visible_entity_types, sequence, is_default,
        created_at, updated_at, created_by
    ) VALUES (
        gen_random_uuid(), $1, $2,
        ''CHARACTER'', ''角色视图'', ''user'', ''PRESET'',
        ''CHARACTER'', ARRAY[''CHARACTER'',''ASSET''],
        3, FALSE, $3, $3, $4
    ) ON CONFLICT (canvas_id, view_key) DO NOTHING', p_schema_name)
    USING p_workspace_id, p_canvas_id, v_now, p_created_by;

    -- SCENE 视图 - 场景视图
    EXECUTE format('
    INSERT INTO %I.t_canvas_view (
        id, workspace_id, canvas_id, view_key, name, icon, view_type,
        root_entity_type, visible_entity_types, sequence, is_default,
        created_at, updated_at, created_by
    ) VALUES (
        gen_random_uuid(), $1, $2,
        ''SCENE'', ''场景视图'', ''map-pin'', ''PRESET'',
        ''SCENE'', ARRAY[''SCENE'',''ASSET''],
        4, FALSE, $3, $3, $4
    ) ON CONFLICT (canvas_id, view_key) DO NOTHING', p_schema_name)
    USING p_workspace_id, p_canvas_id, v_now, p_created_by;

    -- PROP 视图 - 道具视图
    EXECUTE format('
    INSERT INTO %I.t_canvas_view (
        id, workspace_id, canvas_id, view_key, name, icon, view_type,
        root_entity_type, visible_entity_types, sequence, is_default,
        created_at, updated_at, created_by
    ) VALUES (
        gen_random_uuid(), $1, $2,
        ''PROP'', ''道具视图'', ''box'', ''PRESET'',
        ''PROP'', ARRAY[''PROP'',''ASSET''],
        5, FALSE, $3, $3, $4
    ) ON CONFLICT (canvas_id, view_key) DO NOTHING', p_schema_name)
    USING p_workspace_id, p_canvas_id, v_now, p_created_by;

    -- ASSET 视图 - 素材视图
    EXECUTE format('
    INSERT INTO %I.t_canvas_view (
        id, workspace_id, canvas_id, view_key, name, icon, view_type,
        root_entity_type, visible_entity_types, sequence, is_default,
        created_at, updated_at, created_by
    ) VALUES (
        gen_random_uuid(), $1, $2,
        ''ASSET'', ''素材视图'', ''image'', ''PRESET'',
        ''ASSET'', ARRAY[''ASSET''],
        6, FALSE, $3, $3, $4
    ) ON CONFLICT (canvas_id, view_key) DO NOTHING', p_schema_name)
    USING p_workspace_id, p_canvas_id, v_now, p_created_by;

    RAISE NOTICE 'Preset views for canvas % initialized successfully', p_canvas_id;
END;
$$ LANGUAGE plpgsql;

-- =====================================================
-- 使用示例
-- =====================================================

-- 创建新工作空间时调用：
-- SELECT initialize_workspace('workspace-uuid', 'tenant_abc123_20241201');

-- 删除工作空间Schema：
-- SELECT drop_workspace_schema('tenant_abc123_20241201');

-- =====================================================
-- 4. Billing Schema
-- =====================================================

-- 1) 金额使用最小货币单位（如分）保存为 BIGINT
-- 2) 所有回调事件必须可幂等重放
-- =====================================================

-- =====================================================
-- 1. 计费账户表（workspace 维度）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_billing_account (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES t_workspace(id) ON DELETE CASCADE,

    billing_mode VARCHAR(20) DEFAULT 'HYBRID' NOT NULL, -- PREPAID / SUBSCRIPTION / HYBRID
    status VARCHAR(20) DEFAULT 'ACTIVE' NOT NULL,       -- ACTIVE / SUSPENDED / CLOSED
    default_currency VARCHAR(10) DEFAULT 'USD' NOT NULL,
    timezone VARCHAR(64) DEFAULT 'UTC',

    auto_recharge_enabled BOOLEAN DEFAULT FALSE,
    auto_recharge_threshold BIGINT DEFAULT 0,
    auto_recharge_amount BIGINT DEFAULT 0,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0,

    UNIQUE (workspace_id),
    CHECK (billing_mode IN ('PREPAID', 'SUBSCRIPTION', 'HYBRID')),
    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

COMMENT ON TABLE t_billing_account IS '工作空间计费账户表';
COMMENT ON COLUMN t_billing_account.billing_mode IS '计费模式: PREPAID/SUBSCRIPTION/HYBRID';
COMMENT ON COLUMN t_billing_account.auto_recharge_threshold IS '自动充值阈值（最小货币单位）';

-- =====================================================
-- 2. Provider 客户映射表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_provider_customer (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID NOT NULL REFERENCES t_workspace(id) ON DELETE CASCADE,

    provider VARCHAR(32) NOT NULL,                        -- STRIPE / ALIPAY / WECHATPAY ...
    provider_customer_id VARCHAR(128) NOT NULL,
    provider_account_id VARCHAR(128),                     -- 子商户/子账户（可选）

    status VARCHAR(20) DEFAULT 'ACTIVE' NOT NULL,         -- ACTIVE / INACTIVE
    is_default BOOLEAN DEFAULT TRUE,
    meta JSONB DEFAULT '{}',

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (provider, provider_customer_id),
    UNIQUE (workspace_id, provider),
    CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

COMMENT ON TABLE t_provider_customer IS '支付渠道客户映射（workspace 对应 provider customer）';

-- =====================================================
-- 3. 支付订单表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_payment_order (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_no VARCHAR(64) NOT NULL UNIQUE,

    workspace_id UUID NOT NULL REFERENCES t_workspace(id) ON DELETE CASCADE,
    user_id UUID REFERENCES t_user(id),

    provider VARCHAR(32) NOT NULL,                       -- STRIPE / ALIPAY / WECHATPAY
    order_type VARCHAR(32) NOT NULL,                     -- TOPUP / SUBSCRIPTION_RENEW / SUBSCRIPTION_CHANGE / ADJUSTMENT
    biz_type VARCHAR(64),                                -- 业务分类（可选）

    amount_minor BIGINT NOT NULL,                        -- 订单金额（最小货币单位）
    currency VARCHAR(10) NOT NULL,
    points_amount BIGINT DEFAULT 0,                      -- 本订单对应积分（如充值）

    status VARCHAR(32) NOT NULL DEFAULT 'INIT',          -- INIT / PENDING / PAID / FAILED / CANCELED / EXPIRED / REFUNDED / PARTIALLY_REFUNDED

    provider_payment_id VARCHAR(128),                    -- payment_intent_id / trade_no 等
    provider_session_id VARCHAR(128),                    -- checkout_session / prepay_id
    provider_invoice_id VARCHAR(128),                    -- 订阅发票号

    fail_code VARCHAR(64),
    fail_message VARCHAR(512),

    paid_at TIMESTAMPTZ,
    expired_at TIMESTAMPTZ,

    meta JSONB DEFAULT '{}',

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INTEGER NOT NULL DEFAULT 0,

    CHECK (status IN ('INIT', 'PENDING', 'PAID', 'FAILED', 'CANCELED', 'EXPIRED', 'REFUNDED', 'PARTIALLY_REFUNDED'))
);

COMMENT ON TABLE t_payment_order IS '统一支付订单表';
COMMENT ON COLUMN t_payment_order.amount_minor IS '最小货币单位金额，例如分';
COMMENT ON COLUMN t_payment_order.points_amount IS '订单对应入账积分';

CREATE INDEX IF NOT EXISTS idx_t_payment_order_workspace_id ON t_payment_order(workspace_id);
CREATE INDEX IF NOT EXISTS idx_t_payment_order_user_id ON t_payment_order(user_id);
CREATE INDEX IF NOT EXISTS idx_t_payment_order_status ON t_payment_order(status);
CREATE INDEX IF NOT EXISTS idx_t_payment_order_provider ON t_payment_order(provider);
CREATE INDEX IF NOT EXISTS idx_t_payment_order_created_at ON t_payment_order(created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uk_t_payment_order_provider_payment_id
    ON t_payment_order(provider, provider_payment_id)
    WHERE provider_payment_id IS NOT NULL;

-- =====================================================
-- 4. 支付尝试表（每次调用 provider 记录）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_payment_attempt (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES t_payment_order(id) ON DELETE CASCADE,

    attempt_no INTEGER NOT NULL,
    provider VARCHAR(32) NOT NULL,

    idempotency_key VARCHAR(128),
    provider_request_id VARCHAR(128),

    request_payload JSONB,
    response_payload JSONB,

    status VARCHAR(20) NOT NULL DEFAULT 'SENT',          -- SENT / SUCCESS / FAILED
    error_code VARCHAR(64),
    error_message VARCHAR(512),

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (order_id, attempt_no),
    UNIQUE (provider, idempotency_key),
    CHECK (status IN ('SENT', 'SUCCESS', 'FAILED'))
);

COMMENT ON TABLE t_payment_attempt IS '支付调用尝试记录（用于重试和审计）';

CREATE INDEX IF NOT EXISTS idx_t_payment_attempt_order_id ON t_payment_attempt(order_id);
CREATE INDEX IF NOT EXISTS idx_t_payment_attempt_provider ON t_payment_attempt(provider);

-- =====================================================
-- 5. Provider 回调事件表（幂等去重核心）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_provider_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    provider VARCHAR(32) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(128),

    event_created_at TIMESTAMPTZ,

    signature_verified BOOLEAN DEFAULT FALSE,

    process_status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING / PROCESSED / IGNORED / FAILED
    process_result VARCHAR(512),
    processed_at TIMESTAMPTZ,

    payload_raw JSONB NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (provider, event_id),
    CHECK (process_status IN ('PENDING', 'PROCESSED', 'IGNORED', 'FAILED'))
);

COMMENT ON TABLE t_provider_event IS '支付渠道回调事件表（幂等去重）';

CREATE INDEX IF NOT EXISTS idx_t_provider_event_provider_type ON t_provider_event(provider, event_type);
CREATE INDEX IF NOT EXISTS idx_t_provider_event_process_status ON t_provider_event(process_status);
CREATE INDEX IF NOT EXISTS idx_t_provider_event_created_at ON t_provider_event(created_at DESC);

-- =====================================================
-- 6. 订阅合约表
-- =====================================================
CREATE TABLE IF NOT EXISTS t_subscription_contract (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    workspace_id UUID NOT NULL REFERENCES t_workspace(id) ON DELETE CASCADE,
    user_id UUID REFERENCES t_user(id),

    provider VARCHAR(32) NOT NULL,
    provider_customer_id VARCHAR(128),
    provider_subscription_id VARCHAR(128) NOT NULL,

    plan_code VARCHAR(64) NOT NULL,                      -- Free/Basic/Pro/Enterprise 等
    billing_cycle VARCHAR(20) NOT NULL,                  -- MONTHLY / YEARLY

    amount_minor BIGINT,
    currency VARCHAR(10),

    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',        -- TRIALING / ACTIVE / PAST_DUE / CANCELED / EXPIRED

    current_period_start TIMESTAMPTZ,
    current_period_end TIMESTAMPTZ,
    trial_end TIMESTAMPTZ,

    cancel_at_period_end BOOLEAN DEFAULT FALSE,
    canceled_at TIMESTAMPTZ,
    grace_period_end TIMESTAMPTZ,

    last_invoice_id VARCHAR(128),
    last_paid_at TIMESTAMPTZ,

    meta JSONB DEFAULT '{}',

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INTEGER NOT NULL DEFAULT 0,

    UNIQUE (provider, provider_subscription_id),
    CHECK (billing_cycle IN ('MONTHLY', 'YEARLY')),
    CHECK (status IN ('TRIALING', 'ACTIVE', 'PAST_DUE', 'CANCELED', 'EXPIRED'))
);

COMMENT ON TABLE t_subscription_contract IS '工作空间订阅合约表';

CREATE INDEX IF NOT EXISTS idx_t_subscription_contract_workspace_id ON t_subscription_contract(workspace_id);
CREATE INDEX IF NOT EXISTS idx_t_subscription_contract_status ON t_subscription_contract(status);
CREATE INDEX IF NOT EXISTS idx_t_subscription_contract_period_end ON t_subscription_contract(current_period_end);

-- 同一工作空间同一时刻仅允许一个活跃订阅（TRIALING/ACTIVE/PAST_DUE）
CREATE UNIQUE INDEX IF NOT EXISTS uk_t_subscription_contract_workspace_active
    ON t_subscription_contract(workspace_id)
    WHERE status IN ('TRIALING', 'ACTIVE', 'PAST_DUE');

-- =====================================================
-- 7. 权益发放流水表（赠送积分、周期权益）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_entitlement_grant (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    workspace_id UUID NOT NULL REFERENCES t_workspace(id) ON DELETE CASCADE,
    user_id UUID REFERENCES t_user(id),

    grant_type VARCHAR(32) NOT NULL,                     -- MONTHLY_POINTS / PROMOTION / MANUAL_ADJUST
    period_key VARCHAR(32),                              -- 例如 2026-03

    points_amount BIGINT DEFAULT 0,

    source_type VARCHAR(32) NOT NULL,                    -- SUBSCRIPTION / ORDER / CAMPAIGN / MANUAL
    source_ref VARCHAR(128) NOT NULL,                    -- provider invoice id / orderNo / campaignId

    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',       -- PENDING / APPLIED / REVOKED
    applied_txn_id UUID,                          -- 对应 t_point_transaction.id

    meta JSONB DEFAULT '{}',

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (workspace_id, grant_type, period_key, source_type, source_ref),
    CHECK (status IN ('PENDING', 'APPLIED', 'REVOKED'))
);

COMMENT ON TABLE t_entitlement_grant IS '订阅权益发放记录（幂等防重）';

CREATE INDEX IF NOT EXISTS idx_t_entitlement_grant_workspace_id ON t_entitlement_grant(workspace_id);
CREATE INDEX IF NOT EXISTS idx_t_entitlement_grant_status ON t_entitlement_grant(status);

-- =====================================================
-- 8. 支付路由规则表（多支付渠道扩展）
-- =====================================================
CREATE TABLE IF NOT EXISTS t_payment_route_rule (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    scene VARCHAR(32) NOT NULL,                          -- TOPUP / SUBSCRIPTION
    currency VARCHAR(10),                                -- 为空表示任意
    country VARCHAR(10),                                 -- ISO 国家码，可空
    payment_method VARCHAR(32),                          -- CARD / ALIPAY / WECHATPAY，可空

    provider VARCHAR(32) NOT NULL,
    priority INTEGER DEFAULT 100,
    enabled BOOLEAN DEFAULT TRUE,

    remark VARCHAR(255),

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE t_payment_route_rule IS '支付渠道路由规则';

CREATE INDEX IF NOT EXISTS idx_t_payment_route_rule_scene ON t_payment_route_rule(scene);
CREATE INDEX IF NOT EXISTS idx_t_payment_route_rule_enabled_priority ON t_payment_route_rule(enabled, priority);

-- =====================================================
-- 初始化建议（可选）
-- =====================================================
-- INSERT INTO t_payment_route_rule(id, scene, currency, provider, priority, enabled)
-- VALUES ('route-topup-usd-stripe', 'TOPUP', 'USD', 'STRIPE', 10, TRUE)
-- ON CONFLICT DO NOTHING;

-- =====================================================
-- 5. Billing Plan Catalog
-- =====================================================


CREATE TABLE IF NOT EXISTS t_billing_plan_price (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    provider VARCHAR(32) NOT NULL,                          -- STRIPE / ALIPAY / WECHATPAY
    plan_code VARCHAR(64) NOT NULL,                         -- FREE / PRO / TEAMM ...
    workspace_plan_type VARCHAR(64) NOT NULL,               -- Free / Basic / Pro / Enterprise
    billing_cycle VARCHAR(20) NOT NULL,                     -- MONTHLY / YEARLY
    currency VARCHAR(10) NOT NULL,                          -- USD / CNY ...
    amount_minor BIGINT NOT NULL,                           -- 最小货币单位

    is_metered BOOLEAN DEFAULT FALSE NOT NULL,
    usage_type VARCHAR(20) DEFAULT 'LICENSED' NOT NULL,     -- LICENSED / METERED

    stripe_product_id VARCHAR(128),
    stripe_price_id VARCHAR(128),

    status VARCHAR(20) DEFAULT 'ACTIVE' NOT NULL,           -- ACTIVE / INACTIVE
    meta JSONB DEFAULT '{}',

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0,

    CHECK (billing_cycle IN ('MONTHLY', 'YEARLY')),
    CHECK (usage_type IN ('LICENSED', 'METERED')),
    CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CHECK (deleted IN (0, 1))
);

COMMENT ON TABLE t_billing_plan_price IS '支付渠道套餐价格目录（支持多支付渠道扩展）';
COMMENT ON COLUMN t_billing_plan_price.workspace_plan_type IS '映射到 workspace 的计划类型';

CREATE UNIQUE INDEX IF NOT EXISTS uk_t_billing_plan_price_key
    ON t_billing_plan_price(provider, plan_code, workspace_plan_type, billing_cycle, currency, deleted);

CREATE UNIQUE INDEX IF NOT EXISTS uk_t_billing_plan_price_stripe_price
    ON t_billing_plan_price(stripe_price_id)
    WHERE stripe_price_id IS NOT NULL AND deleted = 0;

CREATE INDEX IF NOT EXISTS idx_t_billing_plan_price_provider_status
    ON t_billing_plan_price(provider, status)
    WHERE deleted = 0;


-- =====================================================
-- 6. Auth Security Schema
-- =====================================================


-- =====================================================
-- 1. 认证会话表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_auth_session (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES t_user(id) ON DELETE CASCADE,
    workspace_id UUID REFERENCES t_workspace(id) ON DELETE SET NULL,
    tenant_schema VARCHAR(63),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    perm_version INTEGER NOT NULL DEFAULT 1,
    device_id VARCHAR(128),
    user_agent VARCHAR(512),
    last_ip VARCHAR(64),
    last_active_at TIMESTAMPTZ DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0
);

COMMENT ON TABLE t_auth_session IS '认证会话表（SID）';
COMMENT ON COLUMN t_auth_session.status IS '会话状态: ACTIVE, REVOKED, EXPIRED';
COMMENT ON COLUMN t_auth_session.perm_version IS '权限版本号，用于权限变更后失效会话';
COMMENT ON COLUMN t_auth_session.tenant_schema IS '会话绑定租户Schema';

CREATE INDEX IF NOT EXISTS idx_t_auth_session_user_id ON t_auth_session(user_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_auth_session_workspace_id ON t_auth_session(workspace_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_auth_session_status ON t_auth_session(status) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_auth_session_expires_at ON t_auth_session(expires_at) WHERE deleted = 0;

-- =====================================================
-- 2. Refresh Token Rotation 表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_refresh_token (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES t_auth_session(id) ON DELETE CASCADE,
    token_jti VARCHAR(64) NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    family_id UUID NOT NULL,
    parent_token_jti VARCHAR(64),
    replaced_by_jti VARCHAR(64),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'USED', 'REVOKED', 'EXPIRED')),
    issued_at TIMESTAMPTZ DEFAULT now(),
    used_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    reuse_detected BOOLEAN DEFAULT FALSE,
    reason VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0
);

COMMENT ON TABLE t_refresh_token IS 'Refresh Token轮换表';
COMMENT ON COLUMN t_refresh_token.status IS 'Refresh状态: ACTIVE, USED, REVOKED, EXPIRED';
COMMENT ON COLUMN t_refresh_token.family_id IS '同一会话Refresh家族ID，用于重放后整组失效';
COMMENT ON COLUMN t_refresh_token.reuse_detected IS '是否检测到Refresh重放';

CREATE UNIQUE INDEX IF NOT EXISTS uk_t_refresh_token_jti ON t_refresh_token(token_jti) WHERE deleted = 0;
CREATE UNIQUE INDEX IF NOT EXISTS uk_t_refresh_token_hash ON t_refresh_token(token_hash) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_refresh_token_session_id ON t_refresh_token(session_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_refresh_token_family_id ON t_refresh_token(family_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_refresh_token_status ON t_refresh_token(status) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_refresh_token_expires_at ON t_refresh_token(expires_at) WHERE deleted = 0;

-- =====================================================
-- 3. 认证撤销事件表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_auth_revocation_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope VARCHAR(20) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    reason VARCHAR(255),
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0
);

COMMENT ON TABLE t_auth_revocation_event IS '认证撤销事件审计表';
COMMENT ON COLUMN t_auth_revocation_event.scope IS '撤销粒度: USER, SESSION, FAMILY, JTI';
COMMENT ON COLUMN t_auth_revocation_event.target_id IS '撤销目标ID（userId/sid/familyId/jti）';

CREATE INDEX IF NOT EXISTS idx_t_auth_revocation_scope_target
    ON t_auth_revocation_event(scope, target_id) WHERE deleted = 0;

-- =====================================================
-- Agent Mission 表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_agent_mission (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id     UUID NOT NULL,
    runtime_session_id VARCHAR(100) NOT NULL,
    creator_id       UUID NOT NULL,

    -- 目标
    title            VARCHAR(300) NOT NULL,
    goal             TEXT NOT NULL,
    plan             JSONB DEFAULT '{}',

    -- 状态
    status           VARCHAR(30) NOT NULL DEFAULT 'CREATED',
    current_step     INTEGER DEFAULT 0,
    progress         INTEGER DEFAULT 0,
    result_summary   TEXT,
    result_payload   JSONB DEFAULT '{}',
    failure_code     VARCHAR(100),

    -- 统计
    total_steps        INTEGER DEFAULT 0,
    total_credit_cost  BIGINT DEFAULT 0,

    -- 错误
    error_message    TEXT,

    -- 时间
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       UUID,
    updated_by       UUID,
    deleted          INTEGER NOT NULL DEFAULT 0,
    deleted_at       TIMESTAMPTZ,
    version          INTEGER NOT NULL DEFAULT 0
);

COMMENT ON TABLE t_agent_mission IS 'Agent Mission - 自主执行的长任务目标';
COMMENT ON COLUMN t_agent_mission.status IS '状态: CREATED, EXECUTING, WAITING, COMPLETED, FAILED, CANCELLED';
COMMENT ON COLUMN t_agent_mission.plan IS 'Agent 当前计划(可变)';

-- Mission 创建时快照的 Agent 上下文（agentType + skillNames），执行时继承
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 't_agent_mission'
          AND column_name = 'agent_type'
    ) THEN
        ALTER TABLE t_agent_mission ADD COLUMN agent_type VARCHAR(50);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 't_agent_mission'
          AND column_name = 'agent_skill_names'
    ) THEN
        ALTER TABLE t_agent_mission ADD COLUMN agent_skill_names JSONB DEFAULT '[]';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 't_agent_mission'
          AND column_name = 'tenant_schema'
    ) THEN
        ALTER TABLE t_agent_mission ADD COLUMN tenant_schema VARCHAR(63);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 't_agent_mission'
          AND column_name = 'skill_versions'
    ) THEN
        ALTER TABLE t_agent_mission ADD COLUMN skill_versions JSONB DEFAULT '{}';
    END IF;
END $$;
COMMENT ON COLUMN t_agent_mission.agent_type IS '创建时快照的 Agent 类型，执行时继承';
COMMENT ON COLUMN t_agent_mission.agent_skill_names IS '创建时快照的 Skill 名称列表，执行时继承';
COMMENT ON COLUMN t_agent_mission.tenant_schema IS '创建时快照的租户 Schema，执行时继承';
COMMENT ON COLUMN t_agent_mission.skill_versions IS '创建时快照的 Skill 版本（name → updatedAt epoch millis）';

CREATE INDEX IF NOT EXISTS idx_mission_workspace ON t_agent_mission(workspace_id, status) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_mission_runtime_session ON t_agent_mission(runtime_session_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_mission_status ON t_agent_mission(status) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_mission_creator ON t_agent_mission(creator_id) WHERE deleted = 0;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 't_agent_mission'
          AND column_name = 'session_id'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 't_agent_mission'
          AND column_name = 'runtime_session_id'
    ) THEN
        ALTER TABLE t_agent_mission RENAME COLUMN session_id TO runtime_session_id;
    END IF;
END $$;

-- =====================================================
-- Agent Mission Step 表
-- =====================================================

CREATE TABLE IF NOT EXISTS t_agent_mission_step (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mission_id       UUID NOT NULL REFERENCES t_agent_mission(id) ON DELETE CASCADE,
    step_number      INTEGER NOT NULL,

    -- 内容
    step_type        VARCHAR(30) NOT NULL,
    input_summary    TEXT,
    output_summary   TEXT,
    tool_calls       JSONB DEFAULT '[]',

    -- 状态
    status           VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    duration_ms      BIGINT,
    credit_cost      BIGINT DEFAULT 0,
    input_tokens     BIGINT DEFAULT 0,
    output_tokens    BIGINT DEFAULT 0,
    model_name       VARCHAR(100),
    decision_type    VARCHAR(30),
    decision_payload JSONB DEFAULT '{}',
    artifacts        JSONB DEFAULT '{}',

    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE t_agent_mission_step IS 'Agent Mission 执行步骤记录';
COMMENT ON COLUMN t_agent_mission_step.step_type IS '步骤类型: AGENT_INVOKE, WAIT_TASKS';
COMMENT ON COLUMN t_agent_mission_step.status IS '状态: PENDING, RUNNING, COMPLETED, FAILED';

CREATE INDEX IF NOT EXISTS idx_step_mission ON t_agent_mission_step(mission_id, step_number);

-- =====================================================
-- Agent Mission Task / Event / Trace
-- =====================================================

CREATE TABLE IF NOT EXISTS t_agent_mission_task (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mission_id       UUID NOT NULL REFERENCES t_agent_mission(id) ON DELETE CASCADE,
    mission_step_id  UUID REFERENCES t_agent_mission_step(id) ON DELETE SET NULL,
    task_kind        VARCHAR(50) NOT NULL,
    external_task_id VARCHAR(64),
    batch_job_id     VARCHAR(64),
    entity_type      VARCHAR(50),
    entity_id        VARCHAR(64),
    status           VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    request_payload  JSONB DEFAULT '{}',
    result_payload   JSONB DEFAULT '{}',
    failure_code     VARCHAR(100),
    failure_message  TEXT,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       UUID,
    updated_by       UUID,
    deleted          INTEGER NOT NULL DEFAULT 0,
    version          INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_mission_task_external_id
    ON t_agent_mission_task(external_task_id) WHERE deleted = 0 AND external_task_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_mission_task_mission
    ON t_agent_mission_task(mission_id, status) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_mission_task_batch_job
    ON t_agent_mission_task(batch_job_id) WHERE deleted = 0 AND batch_job_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS t_agent_mission_event (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mission_id       UUID NOT NULL REFERENCES t_agent_mission(id) ON DELETE CASCADE,
    event_type       VARCHAR(50) NOT NULL,
    message          TEXT,
    payload          JSONB DEFAULT '{}',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_mission_event_mission
    ON t_agent_mission_event(mission_id, created_at);

CREATE TABLE IF NOT EXISTS t_agent_mission_trace (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mission_id       UUID NOT NULL REFERENCES t_agent_mission(id) ON DELETE CASCADE,
    mission_step_id  UUID REFERENCES t_agent_mission_step(id) ON DELETE SET NULL,
    trace_type       VARCHAR(50) NOT NULL,
    payload          JSONB DEFAULT '{}',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_mission_trace_mission
    ON t_agent_mission_trace(mission_id, created_at);

-- 清理 Mission 旧版任务数组列（一步到位重构后废弃）
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 't_agent_mission'
          AND column_name = 'result_summary'
    ) THEN
        ALTER TABLE t_agent_mission ADD COLUMN result_summary TEXT;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 't_agent_mission'
          AND column_name = 'result_payload'
    ) THEN
        ALTER TABLE t_agent_mission ADD COLUMN result_payload JSONB DEFAULT '{}';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 't_agent_mission'
          AND column_name = 'failure_code'
    ) THEN
        ALTER TABLE t_agent_mission ADD COLUMN failure_code VARCHAR(100);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 't_agent_mission_step'
          AND column_name = 'input_tokens'
    ) THEN
        ALTER TABLE t_agent_mission_step ADD COLUMN input_tokens BIGINT DEFAULT 0;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 't_agent_mission_step'
          AND column_name = 'output_tokens'
    ) THEN
        ALTER TABLE t_agent_mission_step ADD COLUMN output_tokens BIGINT DEFAULT 0;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 't_agent_mission_step'
          AND column_name = 'model_name'
    ) THEN
        ALTER TABLE t_agent_mission_step ADD COLUMN model_name VARCHAR(100);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 't_agent_mission_step'
          AND column_name = 'decision_type'
    ) THEN
        ALTER TABLE t_agent_mission_step ADD COLUMN decision_type VARCHAR(30);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 't_agent_mission_step'
          AND column_name = 'decision_payload'
    ) THEN
        ALTER TABLE t_agent_mission_step ADD COLUMN decision_payload JSONB DEFAULT '{}';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 't_agent_mission_step'
          AND column_name = 'artifacts'
    ) THEN
        ALTER TABLE t_agent_mission_step ADD COLUMN artifacts JSONB DEFAULT '{}';
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 't_agent_mission_step'
          AND column_name = 'delegated_task_ids'
    ) THEN
        ALTER TABLE t_agent_mission_step DROP COLUMN delegated_task_ids;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 't_agent_mission'
          AND column_name = 'pending_task_ids'
    ) THEN
        ALTER TABLE t_agent_mission DROP COLUMN pending_task_ids;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 't_agent_mission'
          AND column_name = 'completed_task_ids'
    ) THEN
        ALTER TABLE t_agent_mission DROP COLUMN completed_task_ids;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 't_agent_mission'
          AND column_name = 'failed_task_ids'
    ) THEN
        ALTER TABLE t_agent_mission DROP COLUMN failed_task_ids;
    END IF;

    -- P2-1: Skill 版本快照（Mission 创建时记录，执行时比对漂移）
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 't_agent_mission'
          AND column_name = 'skill_versions'
    ) THEN
        ALTER TABLE t_agent_mission ADD COLUMN skill_versions JSONB DEFAULT '{}';
    END IF;
END $$;

-- =====================================================
-- 修复: t_agent_message.tool_call_id 列类型
-- LLM 返回的 tool_call_id 格式为 "call_xxx"（非 UUID），
-- 旧版本 schema 可能误建为 UUID 类型，此处纠正为 VARCHAR(128)。
-- =====================================================
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 't_agent_message'
          AND column_name = 'tool_call_id'
          AND data_type = 'uuid'
    ) THEN
        ALTER TABLE t_agent_message ALTER COLUMN tool_call_id TYPE VARCHAR(128) USING tool_call_id::TEXT;
        RAISE NOTICE 'Fixed t_agent_message.tool_call_id: uuid -> varchar(128)';
    END IF;
END $$;

-- =====================================================
-- 批量作业表 (t_batch_job)
-- 管理批量 AI 生成作业的编排和进度追踪
-- =====================================================

CREATE TABLE IF NOT EXISTS t_batch_job (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id     UUID NOT NULL,
    creator_id       UUID NOT NULL,

    -- 描述
    name             VARCHAR(500),
    description      TEXT,
    batch_type       VARCHAR(50) NOT NULL DEFAULT 'SIMPLE'
        CHECK (batch_type IN ('SIMPLE', 'PIPELINE', 'VARIATION', 'SCOPE', 'AB_TEST')),

    -- 作用域上下文
    script_id        UUID,
    scope_entity_type VARCHAR(50),
    scope_entity_id  UUID,

    -- 执行配置
    error_strategy   VARCHAR(30) NOT NULL DEFAULT 'CONTINUE'
        CHECK (error_strategy IN ('CONTINUE', 'STOP', 'RETRY_THEN_CONTINUE')),
    max_concurrency  INTEGER DEFAULT 5,
    priority         INTEGER DEFAULT 5,

    -- 共享参数模板（子任务可覆盖）
    shared_params    JSONB DEFAULT '{}',
    provider_id      VARCHAR(100),
    generation_type  VARCHAR(50),

    -- 状态追踪
    status           VARCHAR(20) NOT NULL DEFAULT 'CREATED'
        CHECK (status IN ('CREATED', 'RUNNING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED')),
    total_items      INTEGER DEFAULT 0,
    completed_items  INTEGER DEFAULT 0,
    failed_items     INTEGER DEFAULT 0,
    skipped_items    INTEGER DEFAULT 0,
    progress         INTEGER DEFAULT 0,

    -- 费用
    estimated_credits BIGINT DEFAULT 0,
    actual_credits   BIGINT DEFAULT 0,

    -- Agent Mission 集成
    mission_id       UUID,
    source           VARCHAR(30) DEFAULT 'API',

    -- 幂等键: 由调用方（Mission 委派工具）基于稳定输入哈希生成。
    -- 同一 mission 下同 key 命中即返回已有 BatchJob，防止 LLM 单步内重复调用导致 N 倍重复执行
    idempotency_key  VARCHAR(64),

    -- 时间戳 + 审计
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       UUID,
    updated_by       UUID,
    deleted          INTEGER NOT NULL DEFAULT 0,
    deleted_at       TIMESTAMPTZ,
    version          INTEGER NOT NULL DEFAULT 0
);

COMMENT ON TABLE t_batch_job IS '批量作业表';
COMMENT ON COLUMN t_batch_job.batch_type IS '批量类型: SIMPLE/PIPELINE/VARIATION/SCOPE/AB_TEST';
COMMENT ON COLUMN t_batch_job.error_strategy IS '错误策略: CONTINUE/STOP/RETRY_THEN_CONTINUE';
COMMENT ON COLUMN t_batch_job.source IS '来源: API/AGENT/SCHEDULED';
COMMENT ON COLUMN t_batch_job.idempotency_key IS '幂等键: SHA-256(missionId+stepId+type+...)，相同 key 在 mission 内只创建一次 BatchJob';

CREATE INDEX IF NOT EXISTS idx_batch_job_workspace ON t_batch_job(workspace_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_batch_job_status ON t_batch_job(status) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_batch_job_mission ON t_batch_job(mission_id) WHERE deleted = 0 AND mission_id IS NOT NULL;
-- 幂等保护：同一 mission 下同 idempotency_key 唯一（含已软删，避免被 deleted 旁路）
CREATE UNIQUE INDEX IF NOT EXISTS uk_batch_job_mission_idem
    ON t_batch_job(mission_id, idempotency_key)
    WHERE mission_id IS NOT NULL AND idempotency_key IS NOT NULL;

-- =====================================================
-- 批量作业子项表 (t_batch_job_item)
-- 每个子项 1:1 映射到一个 Task
-- =====================================================

CREATE TABLE IF NOT EXISTS t_batch_job_item (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_job_id     UUID NOT NULL,

    sequence_number  INTEGER NOT NULL,

    -- 实体上下文
    entity_type      VARCHAR(50),
    entity_id        UUID,
    entity_name      VARCHAR(500),

    -- 生成参数（与 batch shared_params 合并，item 优先）
    params           JSONB DEFAULT '{}',
    provider_id      VARCHAR(100),
    generation_type  VARCHAR(50),

    -- Pipeline 步骤引用
    pipeline_step_id UUID,

    -- 关联的 task/asset
    task_id          UUID,
    asset_id         UUID,
    relation_id      UUID,

    -- 条件跳过
    skip_condition   VARCHAR(50) DEFAULT 'NONE',
    skipped          BOOLEAN DEFAULT FALSE,
    skip_reason      VARCHAR(500),

    -- 变体支持
    variant_index    INTEGER DEFAULT 0,
    variant_seed     BIGINT,

    -- 状态
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SUBMITTED', 'RUNNING', 'COMPLETED', 'FAILED', 'SKIPPED', 'CANCELLED')),
    error_message    VARCHAR(2000),
    credit_cost      BIGINT DEFAULT 0,

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE t_batch_job_item IS '批量作业子项表';
COMMENT ON COLUMN t_batch_job_item.skip_condition IS '条件跳过: NONE/ASSET_EXISTS';
COMMENT ON COLUMN t_batch_job_item.status IS '状态: PENDING/SUBMITTED/RUNNING/COMPLETED/FAILED/SKIPPED/CANCELLED';

CREATE INDEX IF NOT EXISTS idx_batch_item_job ON t_batch_job_item(batch_job_id, sequence_number);
CREATE INDEX IF NOT EXISTS idx_batch_item_status ON t_batch_job_item(batch_job_id, status);
CREATE INDEX IF NOT EXISTS idx_batch_item_task ON t_batch_job_item(task_id) WHERE task_id IS NOT NULL;

-- =====================================================
-- Pipeline 定义表 (t_pipeline)
-- 多步 DAG 工作流定义
-- =====================================================

CREATE TABLE IF NOT EXISTS t_pipeline (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_job_id     UUID NOT NULL,
    name             VARCHAR(500),
    template_code    VARCHAR(100),
    status           VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    current_step     INTEGER DEFAULT 0,
    total_steps      INTEGER DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE t_pipeline IS 'Pipeline 工作流定义表';

CREATE INDEX IF NOT EXISTS idx_pipeline_batch ON t_pipeline(batch_job_id);

-- =====================================================
-- Pipeline 步骤表 (t_pipeline_step)
-- Pipeline 中的每个执行步骤
-- =====================================================

CREATE TABLE IF NOT EXISTS t_pipeline_step (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pipeline_id      UUID NOT NULL,
    step_number      INTEGER NOT NULL,
    name             VARCHAR(200),

    step_type        VARCHAR(50) NOT NULL,
    generation_type  VARCHAR(50),
    provider_id      VARCHAR(100),

    -- 参数模板（支持 {{steps[1].output.text}} 插值引用前序步骤输出）
    params_template  JSONB DEFAULT '{}',
    depends_on       JSONB DEFAULT '[]',

    fan_out_count    INTEGER DEFAULT 1,

    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE t_pipeline_step IS 'Pipeline 步骤表';
COMMENT ON COLUMN t_pipeline_step.step_type IS '步骤类型: GENERATE_TEXT/GENERATE_IMAGE/GENERATE_VIDEO/GENERATE_AUDIO/GENERATE_TTS/TRANSFORM/EXPAND';
COMMENT ON COLUMN t_pipeline_step.params_template IS '参数模板，支持 {{steps[N].output.xxx}} 插值';

CREATE INDEX IF NOT EXISTS idx_pipeline_step_pipeline ON t_pipeline_step(pipeline_id, step_number);

-- =====================================================
-- 扩展 t_task: 添加批量作业关联字段
-- =====================================================

ALTER TABLE t_task ADD COLUMN IF NOT EXISTS batch_job_id UUID;
ALTER TABLE t_task ADD COLUMN IF NOT EXISTS batch_item_id UUID;
CREATE INDEX IF NOT EXISTS idx_t_task_batch ON t_task(batch_job_id) WHERE batch_job_id IS NOT NULL;

-- =====================================================
-- 团队协作增强（原 04-collab-enhancement.sql 合并）
-- 新增: 评论附件、评论反应、实体关注、审核、通知偏好
-- =====================================================

-- =====================================================
-- Public Schema: 通知偏好表
-- =====================================================

CREATE TABLE IF NOT EXISTS public.t_notification_preference (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL,
    workspace_id      UUID,
    comment_mention   BOOLEAN DEFAULT TRUE,
    comment_reply     BOOLEAN DEFAULT TRUE,
    entity_change     BOOLEAN DEFAULT TRUE,
    review_request    BOOLEAN DEFAULT TRUE,
    review_result     BOOLEAN DEFAULT TRUE,
    task_completed    BOOLEAN DEFAULT TRUE,
    system_alert      BOOLEAN DEFAULT TRUE,
    quiet_start       TIME,
    quiet_end         TIME,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, workspace_id)
);

COMMENT ON TABLE public.t_notification_preference IS '用户通知偏好设置';

CREATE OR REPLACE FUNCTION migrate_collab_tenant_schema(p_schema_name VARCHAR)
RETURNS VOID AS $$
BEGIN
    -- 剧本权限表补充字段
    EXECUTE format(
        'ALTER TABLE %I.t_script_permission ADD COLUMN IF NOT EXISTS grant_source VARCHAR(50) NOT NULL DEFAULT ''WORKSPACE_ADMIN''',
        p_schema_name
    );

    -- ALTER t_comment: 新增字段
    EXECUTE format('ALTER TABLE %I.t_comment ADD COLUMN IF NOT EXISTS script_id UUID', p_schema_name);
    EXECUTE format('ALTER TABLE %I.t_comment ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT ''OPEN''', p_schema_name);
    EXECUTE format('ALTER TABLE %I.t_comment ADD COLUMN IF NOT EXISTS resolved_by UUID', p_schema_name);
    EXECUTE format('ALTER TABLE %I.t_comment ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMPTZ', p_schema_name);

    -- Add version column for optimistic locking (BaseEntity @Version)
    EXECUTE format('ALTER TABLE %I.t_comment ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 0', p_schema_name);

    -- Rename mention_users to mentions (idempotent check)
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = p_schema_name AND table_name = 't_comment' AND column_name = 'mention_users'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = p_schema_name AND table_name = 't_comment' AND column_name = 'mentions'
    ) THEN
        EXECUTE format('ALTER TABLE %I.t_comment RENAME COLUMN mention_users TO mentions', p_schema_name);
    END IF;

    -- New indexes on t_comment
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_comment_script ON %I.t_comment(script_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_comment_parent ON %I.t_comment(parent_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_comment_status ON %I.t_comment(status) WHERE deleted = 0', p_schema_name);

    -- 评论附件表
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_comment_attachment (
        id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        comment_id     UUID NOT NULL,
        asset_id       UUID NOT NULL,
        asset_type     VARCHAR(20),
        file_name      VARCHAR(500),
        file_url       VARCHAR(1000),
        thumbnail_url  VARCHAR(1000),
        file_size      BIGINT,
        mime_type      VARCHAR(100),
        meta_info      JSONB DEFAULT ''{}''::jsonb,
        sequence       INTEGER DEFAULT 0,
        created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
        UNIQUE (comment_id, asset_id)
    )', p_schema_name);

    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_comment_attachment_comment ON %I.t_comment_attachment(comment_id)', p_schema_name);

    -- 评论表情反应表
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_comment_reaction (
        id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        comment_id   UUID NOT NULL,
        emoji        VARCHAR(20) NOT NULL,
        created_by   UUID NOT NULL,
        created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
        UNIQUE (comment_id, created_by, emoji)
    )', p_schema_name);

    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_comment_reaction_comment ON %I.t_comment_reaction(comment_id)', p_schema_name);

    -- 实体关注表
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_entity_watch (
        id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id UUID NOT NULL,
        user_id      UUID NOT NULL,
        entity_type  VARCHAR(50) NOT NULL,
        entity_id    UUID NOT NULL,
        watch_type   VARCHAR(20) DEFAULT ''ALL'',
        created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
        UNIQUE (user_id, entity_type, entity_id)
    )', p_schema_name);

    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_entity_watch_entity ON %I.t_entity_watch(entity_type, entity_id)', p_schema_name);

    -- 审核表
    EXECUTE format('
    CREATE TABLE IF NOT EXISTS %I.t_review (
        id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        workspace_id   UUID NOT NULL,
        entity_type    VARCHAR(50) NOT NULL,
        entity_id      UUID NOT NULL,
        title          VARCHAR(500),
        description    TEXT,
        status         VARCHAR(20) NOT NULL DEFAULT ''PENDING'',
        requester_id   UUID NOT NULL,
        reviewer_id    UUID,
        reviewed_at    TIMESTAMPTZ,
        review_comment TEXT,
        version_number INTEGER,
        created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
        created_by     UUID,
        updated_by     UUID,
        deleted        INTEGER NOT NULL DEFAULT 0,
        deleted_at     TIMESTAMPTZ,
        version        INTEGER NOT NULL DEFAULT 0
    )', p_schema_name);

    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_review_entity ON %I.t_review(entity_type, entity_id) WHERE deleted = 0', p_schema_name);
    EXECUTE format('CREATE INDEX IF NOT EXISTS idx_t_review_reviewer ON %I.t_review(reviewer_id, status) WHERE deleted = 0', p_schema_name);

    RAISE NOTICE 'Collab enhancement migration for schema % completed', p_schema_name;
END;
$$ LANGUAGE plpgsql;

-- Apply collab migration to all existing tenant schemas
DO $$
DECLARE
    schema_rec RECORD;
BEGIN
    FOR schema_rec IN
        SELECT schema_name FROM information_schema.schemata
        WHERE schema_name LIKE 'tenant_%'
    LOOP
        PERFORM migrate_collab_tenant_schema(schema_rec.schema_name::VARCHAR);
    END LOOP;

    RAISE NOTICE 'Collab enhancement migration applied to all existing tenant schemas';
END $$;
