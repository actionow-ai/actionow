-- =====================================================
-- BatchJob 幂等键迁移（增量）
-- 修复 Mission 单步内 LLM 重复调用 delegate_* 工具导致 N 倍重复执行的问题
-- 同一 mission 下相同 idempotency_key 只允许存在一条 BatchJob，
-- 重复请求直接返回已有作业。
-- =====================================================

ALTER TABLE t_batch_job
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);

COMMENT ON COLUMN t_batch_job.idempotency_key IS
    '幂等键: SHA-256(missionId+stepId+type+...)，相同 key 在 mission 内只创建一次 BatchJob';

-- 软删的旧数据也参与唯一约束，防止"删除再创建"绕过幂等
CREATE UNIQUE INDEX IF NOT EXISTS uk_batch_job_mission_idem
    ON t_batch_job(mission_id, idempotency_key)
    WHERE mission_id IS NOT NULL AND idempotency_key IS NOT NULL;
