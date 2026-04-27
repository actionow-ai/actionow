package com.actionow.agent.mapper;

import com.actionow.agent.mission.reconciler.OrphanBatchJobRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 孤儿 BatchJob 扫描 Mapper。
 *
 * <p>「孤儿」定义：BatchJob 已进入终态（COMPLETED/FAILED/CANCELLED），但其关联的
 * {@code t_agent_mission_task.status} 仍是 PENDING——意味着 BatchJob → Mission 的终态
 * 通知没能到达 {@link com.actionow.agent.service.impl.MissionTaskListener}（典型成因：
 * MQ routing key 配置错、消费者宕机、消息丢失），Mission 因此永久卡在 WAITING。
 *
 * <p>该查询跨 task / agent 两个模块的表（同库），为只读、补偿性扫描，与正常推进路径无冲突。
 *
 * <p>历史 trigger：mission {@code 019dcfa8-3248-780e-a8ab-3bb259e1717a} 因
 * {@code BatchJobMissionNotifier} 误用 {@code batch.job.completed} routing key
 * （无任何队列绑定），通知被 direct exchange 直接丢弃。
 *
 * @author Actionow
 */
@Mapper
public interface OrphanBatchJobMapper {

    /**
     * 扫描需要补发通知的孤儿 BatchJob。
     *
     * @param notBefore  BatchJob.completed_at 必须早于该时间（宽限期，避免与正常通知抢跑）
     * @param notAfter   BatchJob.completed_at 必须晚于该时间（避免无限回溯历史）
     * @param limit      单次扫描上限
     */
    @Select("""
            SELECT bj.id              AS batch_job_id,
                   bj.mission_id      AS mission_id,
                   bj.status          AS status,
                   bj.workspace_id    AS workspace_id,
                   bj.creator_id      AS creator_id,
                   bj.completed_items AS completed_items,
                   bj.failed_items    AS failed_items,
                   bj.skipped_items   AS skipped_items,
                   bj.actual_credits  AS actual_credits
              FROM t_batch_job bj
             INNER JOIN t_agent_mission_task mt
                     ON mt.batch_job_id = bj.id::varchar
                    AND mt.deleted = 0
                    AND mt.status = 'PENDING'
             WHERE bj.deleted = 0
               AND bj.mission_id IS NOT NULL
               AND bj.status IN ('COMPLETED', 'FAILED', 'CANCELLED')
               AND bj.completed_at < #{notBefore}
               AND bj.completed_at > #{notAfter}
             ORDER BY bj.completed_at ASC
             LIMIT #{limit}
            """)
    List<OrphanBatchJobRow> selectOrphans(@Param("notBefore") LocalDateTime notBefore,
                                          @Param("notAfter") LocalDateTime notAfter,
                                          @Param("limit") int limit);
}
