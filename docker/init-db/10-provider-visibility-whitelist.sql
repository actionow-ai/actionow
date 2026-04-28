-- =====================================================
-- 10. Provider 可见性 + workspace 白名单
--
-- 背景：t_model_provider 是全局单表，所有 workspace 看到的列表完全一致，
--      无法支持新 provider 的灰度上线 / 内部测试 workspace 概念。
--
-- 设计：
--   - t_model_provider.visibility ∈ {PUBLIC, INTERNAL, WHITELIST}
--   - t_workspace.is_internal: 配合 INTERNAL 类型自动可见
--   - t_provider_workspace_whitelist: WHITELIST 类型的精确授权
--
-- 可见性规则（在 ai 模块 ModelProviderServiceImpl 实现）：
--   PUBLIC    → 所有 workspace
--   INTERNAL  → workspace.is_internal = true
--   WHITELIST → (provider_id, workspace_id) 在 t_provider_workspace_whitelist
--
-- 幂等：可重复执行。
-- =====================================================

-- 1. provider 加 visibility（默认 PUBLIC，回归向前兼容）
ALTER TABLE t_model_provider
    ADD COLUMN IF NOT EXISTS visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 't_model_provider_visibility_check'
    ) THEN
        ALTER TABLE t_model_provider
            ADD CONSTRAINT t_model_provider_visibility_check
            CHECK (visibility IN ('PUBLIC', 'INTERNAL', 'WHITELIST'));
    END IF;
END $$;

COMMENT ON COLUMN t_model_provider.visibility IS '可见性: PUBLIC=所有workspace可见, INTERNAL=仅内部workspace, WHITELIST=按白名单';

CREATE INDEX IF NOT EXISTS idx_t_model_provider_visibility
    ON t_model_provider(visibility) WHERE deleted = 0;

-- 2. workspace 加 is_internal
ALTER TABLE t_workspace
    ADD COLUMN IF NOT EXISTS is_internal BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN t_workspace.is_internal IS '是否为内部测试workspace, 用于看到 visibility=INTERNAL 的 provider';

CREATE INDEX IF NOT EXISTS idx_t_workspace_is_internal
    ON t_workspace(is_internal) WHERE deleted = 0 AND is_internal = true;

-- 3. 白名单关联表（仿 t_workspace_member 风格）
CREATE TABLE IF NOT EXISTS t_provider_workspace_whitelist (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id UUID NOT NULL REFERENCES t_model_provider(id) ON DELETE CASCADE,
    workspace_id UUID NOT NULL REFERENCES t_workspace(id) ON DELETE CASCADE,
    note VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE (provider_id, workspace_id)
);

COMMENT ON TABLE t_provider_workspace_whitelist IS 'provider 的 workspace 白名单 (visibility=WHITELIST 时生效)';
COMMENT ON COLUMN t_provider_workspace_whitelist.note IS '备注: 例如灰度批次说明';

CREATE INDEX IF NOT EXISTS idx_t_pww_provider
    ON t_provider_workspace_whitelist(provider_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_t_pww_workspace
    ON t_provider_workspace_whitelist(workspace_id) WHERE deleted = 0;

-- 4. 挂触发器（updated_at 自动维护）
DROP TRIGGER IF EXISTS trigger_t_provider_workspace_whitelist_updated_at ON t_provider_workspace_whitelist;
CREATE TRIGGER trigger_t_provider_workspace_whitelist_updated_at
    BEFORE UPDATE ON t_provider_workspace_whitelist
    FOR EACH ROW EXECUTE FUNCTION trigger_set_timestamp();
