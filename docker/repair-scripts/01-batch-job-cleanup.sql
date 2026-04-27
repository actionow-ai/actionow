-- =====================================================
-- Repair Script: Mission 重复 BatchJob + 时区 stale 误判清理
-- =====================================================
-- 目的：
--   1. 清理 Mission 单步 LLM 重复调用 delegate_* 产生的重复 BatchJob
--      （影响范围: 06-batch-job-idempotency.sql 上线之前创建的数据）
--   2. 识别因 BatchJob 时区 bug（incrementXxx 用 SQL NOW() vs isStaleBatch
--      用 LocalDateTime.now() 偏 8h）被误判为 stale 卡住的 BatchJob
--   3. 列出受影响的 Mission，供人工评估是否需要回滚状态
--
-- 安全性：
--   * 默认整体包在 BEGIN ... ROLLBACK 中，所有 UPDATE 不会生效
--   * 评审通过后将末尾 ROLLBACK 改为 COMMIT 再执行
--   * 不放在 docker/init-db/，避免容器启动时自动执行
--
-- 执行方式：
--   docker exec -i actionow-postgres psql -U actionow -d actionow \
--     < docker/repair-scripts/01-batch-job-cleanup.sql
--
-- 已知 trigger case：
--   mission 019dc989-6b71-750c-9b8f-540ea310d066 在单 LLM step 内 5x
--   delegate_batch_generation，生成 5 个 BatchJob、25 条冗余图像生成
-- =====================================================

\timing on
\set ON_ERROR_STOP on

BEGIN;

-- ----------------------------------------------------------------
-- Section A: 预览重复 BatchJob（按 mission_id + idempotency_key 分组）
-- 显示 completed_items 让评审者判断「保留哪一条」
-- ----------------------------------------------------------------
\echo '====== A. 预览重复 BatchJob 分组 ======'

WITH dup_groups AS (
    SELECT mission_id,
           idempotency_key,
           COUNT(*)                                          AS dup_count,
           array_agg(id              ORDER BY completed_items DESC, created_at ASC) AS ids_by_progress,
           array_agg(status          ORDER BY completed_items DESC, created_at ASC) AS statuses_by_progress,
           array_agg(completed_items ORDER BY completed_items DESC, created_at ASC) AS completed_by_progress,
           array_agg(created_at      ORDER BY completed_items DESC, created_at ASC) AS created_by_progress,
           MIN(created_at)                                   AS earliest,
           MAX(created_at)                                   AS latest
      FROM t_batch_job
     WHERE mission_id      IS NOT NULL
       AND idempotency_key IS NOT NULL
       AND deleted = 0
     GROUP BY mission_id, idempotency_key
    HAVING COUNT(*) > 1
)
SELECT mission_id,
       idempotency_key,
       dup_count,
       statuses_by_progress,
       completed_by_progress,
       created_by_progress,
       EXTRACT(EPOCH FROM (latest - earliest))::INT         AS span_seconds,
       ids_by_progress
  FROM dup_groups
 ORDER BY dup_count DESC, earliest DESC;

-- ----------------------------------------------------------------
-- Section B: 软删除重复 BatchJob
-- 排序键：(completed_items DESC, created_at ASC) —— 优先保留有进度的，
-- 同进度退化为保留最早一条。避免「最早其实是空 stub、后续 BatchJob 才真正完成 25 个 item」
-- 这种被误丢真实进度的场景。
-- ----------------------------------------------------------------
\echo '====== B. 软删除重复 BatchJob (保留进度最大者；同进度保留最早) ======'

WITH dup_ranked AS (
    SELECT id,
           mission_id,
           idempotency_key,
           status,
           completed_items,
           created_at,
           ROW_NUMBER() OVER (
               PARTITION BY mission_id, idempotency_key
               ORDER BY completed_items DESC, created_at ASC, id ASC
           ) AS rn
      FROM t_batch_job
     WHERE mission_id      IS NOT NULL
       AND idempotency_key IS NOT NULL
       AND deleted = 0
),
to_delete AS (
    SELECT id FROM dup_ranked WHERE rn > 1
)
UPDATE t_batch_job bj
   SET deleted    = 1,
       deleted_at = NOW(),
       updated_at = NOW()
  FROM to_delete td
 WHERE bj.id = td.id
RETURNING bj.id, bj.mission_id, bj.idempotency_key, bj.status, bj.completed_items, bj.created_at;

-- 同步软删该 BatchJob 下尚未结算的子项（避免 worker 误捞）
\echo '====== B.1 软删除被弃 BatchJob 下未完成的 BatchJobItem ======'

WITH dup_ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY mission_id, idempotency_key
               ORDER BY completed_items DESC, created_at ASC, id ASC
           ) AS rn
      FROM t_batch_job
     WHERE mission_id      IS NOT NULL
       AND idempotency_key IS NOT NULL
),
abandoned AS (
    SELECT id AS batch_job_id FROM dup_ranked WHERE rn > 1
)
UPDATE t_batch_job_item it
   SET deleted    = 1,
       deleted_at = NOW(),
       updated_at = NOW()
  FROM abandoned a
 WHERE it.batch_job_id = a.batch_job_id
   AND it.deleted = 0
   AND it.status IN ('PENDING', 'RUNNING')
RETURNING it.id, it.batch_job_id, it.task_id, it.status;

-- ----------------------------------------------------------------
-- Section B.2: 列出受影响的 task_id（钱包冻结款待解冻清单）
-- 软删 BatchJobItem 不会自动调用 wallet 解冻 API。这里只输出待处理列表，
-- 由运维拿到列表后逐一调用 wallet 解冻接口（或写一次性脚本）。
-- 注意：只列已被 B 段软删的 BatchJob 下的 task_id；status COMPLETED 的不算
-- （那些是真正完成、钱已正常扣的）。
-- ----------------------------------------------------------------
\echo '====== B.2 待人工解冻的 task_id 清单（wallet 不会自动退） ======'

WITH dup_ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY mission_id, idempotency_key
               ORDER BY completed_items DESC, created_at ASC, id ASC
           ) AS rn
      FROM t_batch_job
     WHERE mission_id      IS NOT NULL
       AND idempotency_key IS NOT NULL
),
abandoned AS (
    SELECT id AS batch_job_id FROM dup_ranked WHERE rn > 1
)
SELECT it.task_id,
       it.batch_job_id,
       it.status                                              AS item_status,
       t.workspace_id,
       t.creator_id,
       t.status                                               AS task_status,
       t.created_at                                           AS task_created_at
  FROM t_batch_job_item it
  JOIN abandoned a ON it.batch_job_id = a.batch_job_id
  LEFT JOIN t_task t ON t.id = it.task_id
 WHERE it.deleted = 0
   AND it.status IN ('PENDING', 'RUNNING')
   AND it.task_id IS NOT NULL
 ORDER BY t.workspace_id, t.created_at;

-- ----------------------------------------------------------------
-- Section C: 预览时区 bug 影响 — 长时间停在 RUNNING/CREATED
-- ----------------------------------------------------------------
-- 注：这里的 14 天阈值远大于真实业务边界，覆盖时区偏 8h 仅是必要不充分条件。
-- 列表中的每条 BatchJob 都需人工核对其 BatchJobItem 实际进度后再决策。
\echo '====== C. 长时间停滞的 BatchJob (疑似时区 stale 误判) ======'

SELECT bj.id,
       bj.mission_id,
       bj.idempotency_key,
       bj.status,
       bj.total_items,
       bj.completed_items,
       bj.failed_items,
       bj.created_at,
       bj.updated_at,
       NOW() - bj.updated_at                                    AS idle_age,
       (SELECT COUNT(*) FROM t_batch_job_item it
         WHERE it.batch_job_id = bj.id
           AND it.deleted = 0
           AND it.status IN ('PENDING', 'RUNNING'))             AS open_items,
       (SELECT COUNT(*) FROM t_batch_job_item it
         WHERE it.batch_job_id = bj.id
           AND it.deleted = 0
           AND it.status = 'COMPLETED')                         AS done_items
  FROM t_batch_job bj
 WHERE bj.deleted = 0
   AND bj.status IN ('CREATED', 'RUNNING')
   AND bj.updated_at < NOW() - INTERVAL '14 days'
 ORDER BY bj.updated_at ASC;

-- ----------------------------------------------------------------
-- Section D: 列出受影响的 Mission（供人工评估状态回滚）
-- ----------------------------------------------------------------
\echo '====== D. 受影响 Mission 总览（待人工评估是否回滚状态） ======'

WITH affected_missions AS (
    -- 来自重复 BatchJob 的 mission
    SELECT DISTINCT mission_id
      FROM t_batch_job
     WHERE mission_id      IS NOT NULL
       AND idempotency_key IS NOT NULL
     GROUP BY mission_id, idempotency_key
    HAVING COUNT(*) > 1

    UNION

    -- 来自时区 stale 误判嫌疑 BatchJob 的 mission
    SELECT DISTINCT mission_id
      FROM t_batch_job
     WHERE deleted = 0
       AND mission_id IS NOT NULL
       AND status IN ('CREATED', 'RUNNING')
       AND updated_at < NOW() - INTERVAL '14 days'
)
SELECT m.id                  AS mission_id,
       m.status              AS mission_status,
       m.created_at,
       m.updated_at,
       (SELECT COUNT(*) FROM t_agent_mission_step s
         WHERE s.mission_id = m.id)                              AS step_count,
       (SELECT MAX(step_number) FROM t_agent_mission_step s
         WHERE s.mission_id = m.id)                              AS last_step_number,
       (SELECT COUNT(*) FROM t_batch_job bj
         WHERE bj.mission_id = m.id AND bj.deleted = 0)          AS active_batch_jobs,
       (SELECT COUNT(*) FROM t_batch_job bj
         WHERE bj.mission_id = m.id AND bj.deleted = 1)          AS soft_deleted_batch_jobs
  FROM t_agent_mission m
 WHERE m.id IN (SELECT mission_id FROM affected_missions)
 ORDER BY m.updated_at DESC;

-- ----------------------------------------------------------------
-- ⚠️ 评审完毕后将下一行 ROLLBACK 改为 COMMIT 再执行
-- ----------------------------------------------------------------
ROLLBACK;
-- COMMIT;
