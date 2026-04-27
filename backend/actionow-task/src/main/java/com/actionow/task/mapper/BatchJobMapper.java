package com.actionow.task.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.actionow.task.entity.BatchJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量作业 Mapper
 *
 * @author Actionow
 */
@Mapper
public interface BatchJobMapper extends BaseMapper<BatchJob> {

    /**
     * 根据工作空间ID分页查询
     */
    default IPage<BatchJob> selectPageByWorkspaceId(Page<BatchJob> page, String workspaceId,
                                                     String status, String batchType) {
        LambdaQueryWrapper<BatchJob> wrapper = new LambdaQueryWrapper<BatchJob>()
                .eq(BatchJob::getWorkspaceId, workspaceId)
                .eq(status != null && !status.isEmpty(), BatchJob::getStatus, status)
                .eq(batchType != null && !batchType.isEmpty(), BatchJob::getBatchType, batchType)
                .orderByDesc(BatchJob::getCreatedAt);
        return selectPage(page, wrapper);
    }

    /**
     * 根据 Mission ID 查询
     */
    default BatchJob selectByMissionId(String missionId) {
        return selectOne(new LambdaQueryWrapper<BatchJob>()
                .eq(BatchJob::getMissionId, missionId)
                .last("LIMIT 1"));
    }

    /**
     * 根据 (missionId, idempotencyKey) 查询已存在 BatchJob，用于幂等命中检查。
     * 含已软删的记录，与 uk_batch_job_mission_idem 唯一索引语义保持一致，
     * 防止"软删后再创建同 key"绕过幂等保护。
     */
    default BatchJob selectByMissionAndIdempotencyKey(String missionId, String idempotencyKey) {
        if (missionId == null || idempotencyKey == null) {
            return null;
        }
        return selectOne(new LambdaQueryWrapper<BatchJob>()
                .eq(BatchJob::getMissionId, missionId)
                .eq(BatchJob::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"));
    }

    /**
     * 根据状态查询（用于定时扫描）
     */
    default List<BatchJob> selectByStatus(String status, int limit) {
        return selectList(new LambdaQueryWrapper<BatchJob>()
                .eq(BatchJob::getStatus, status)
                .orderByAsc(BatchJob::getCreatedAt)
                .last("LIMIT " + limit));
    }

    /**
     * 原子更新计数器（完成项+1）
     * updated_at 由调用方传入 LocalDateTime.now()，与 BaseEntity 自动填充保持同一时区语义，
     * 避免与 SQL NOW() 混用导致 isStaleBatch 出现 8 小时偏差。
     */
    @Update("UPDATE t_batch_job SET completed_items = completed_items + 1, " +
            "actual_credits = actual_credits + #{creditCost}, " +
            "progress = CASE WHEN total_items > 0 THEN " +
            "  ((completed_items + 1 + failed_items + skipped_items) * 100 / total_items) ELSE 0 END, " +
            "updated_at = #{updatedAt} WHERE id = #{batchJobId} AND deleted = 0")
    int incrementCompleted(@Param("batchJobId") String batchJobId,
                           @Param("creditCost") long creditCost,
                           @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 原子更新计数器（失败项+1）
     */
    @Update("UPDATE t_batch_job SET failed_items = failed_items + 1, " +
            "progress = CASE WHEN total_items > 0 THEN " +
            "  ((completed_items + failed_items + 1 + skipped_items) * 100 / total_items) ELSE 0 END, " +
            "updated_at = #{updatedAt} WHERE id = #{batchJobId} AND deleted = 0")
    int incrementFailed(@Param("batchJobId") String batchJobId,
                        @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 原子更新计数器（跳过项+1）
     */
    @Update("UPDATE t_batch_job SET skipped_items = skipped_items + 1, " +
            "progress = CASE WHEN total_items > 0 THEN " +
            "  ((completed_items + failed_items + skipped_items + 1) * 100 / total_items) ELSE 0 END, " +
            "updated_at = #{updatedAt} WHERE id = #{batchJobId} AND deleted = 0")
    int incrementSkipped(@Param("batchJobId") String batchJobId,
                         @Param("updatedAt") LocalDateTime updatedAt);
}
