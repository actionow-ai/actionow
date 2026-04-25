-- =====================================================
-- 礼包码相关表
-- 仅平台管理员（系统租户 ADMIN+）可创建；任何工作空间成员可兑换
-- 同一用户跨工作空间也只能兑换同一礼包码一次
-- =====================================================

CREATE TABLE IF NOT EXISTS t_gift_code (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(64) NOT NULL,
    name VARCHAR(200),
    description TEXT,
    points BIGINT NOT NULL CHECK (points > 0),
    valid_from TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,
    max_redemptions INTEGER NOT NULL DEFAULT 1 CHECK (max_redemptions > 0),
    redeemed_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED', 'EXHAUSTED', 'EXPIRED')),
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_gift_code_code ON t_gift_code(code) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_gift_code_status ON t_gift_code(status) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_gift_code_created_at ON t_gift_code(created_at DESC) WHERE deleted = 0;

COMMENT ON TABLE t_gift_code IS '礼包码主表（平台级，仅系统租户管理员可创建）';
COMMENT ON COLUMN t_gift_code.code IS '兑换码（唯一）';
COMMENT ON COLUMN t_gift_code.points IS '面值积分';
COMMENT ON COLUMN t_gift_code.valid_from IS '生效时间，NULL=立即生效';
COMMENT ON COLUMN t_gift_code.valid_until IS '过期时间，NULL=永不过期';
COMMENT ON COLUMN t_gift_code.max_redemptions IS '总可兑换次数';
COMMENT ON COLUMN t_gift_code.redeemed_count IS '已兑换次数';
COMMENT ON COLUMN t_gift_code.status IS '状态: ACTIVE/DISABLED/EXHAUSTED/EXPIRED';
COMMENT ON COLUMN t_gift_code.created_by IS '创建者（必为系统租户管理员）';

CREATE TABLE IF NOT EXISTS t_gift_code_redemption (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    gift_code_id UUID NOT NULL REFERENCES t_gift_code(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    points BIGINT NOT NULL,
    transaction_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_gift_code_redemption_user
    ON t_gift_code_redemption(gift_code_id, user_id);
CREATE INDEX IF NOT EXISTS idx_gift_code_redemption_workspace
    ON t_gift_code_redemption(workspace_id);
CREATE INDEX IF NOT EXISTS idx_gift_code_redemption_created
    ON t_gift_code_redemption(created_at DESC);

COMMENT ON TABLE t_gift_code_redemption IS '礼包码兑换记录（同一用户跨工作空间也只能兑换同一码一次）';
COMMENT ON COLUMN t_gift_code_redemption.transaction_id IS '关联的积分流水ID';
