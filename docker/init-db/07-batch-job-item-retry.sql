-- =====================================================
-- BatchJobItem 重试跟踪字段（增量）
-- 为 Phase 3.2 Provider auto-fallback 提供持久化基础：
--   retry_count          —— 已重试次数（限制无限循环）
--   failed_provider_ids  —— 已失败的 provider ID 列表（避免重选）
--   last_error_at        —— 最近一次失败时间（用于退避策略）
-- =====================================================

ALTER TABLE t_batch_job_item
    ADD COLUMN IF NOT EXISTS retry_count          INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS failed_provider_ids  JSONB       DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS last_error_at        TIMESTAMPTZ NULL;

COMMENT ON COLUMN t_batch_job_item.retry_count IS
    '当前 item 的 provider fallback 重试次数（不含同 provider 内 Resilience4j 重试）';
COMMENT ON COLUMN t_batch_job_item.failed_provider_ids IS
    '已失败的 provider ID JSON 数组，fallback 时排除';
COMMENT ON COLUMN t_batch_job_item.last_error_at IS
    '最近一次失败时间，用于 fallback 退避';
