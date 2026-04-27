package com.actionow.task.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.actionow.task.entity.BatchJobItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 批量作业子项 Mapper
 *
 * @author Actionow
 */
@Mapper
public interface BatchJobItemMapper extends BaseMapper<BatchJobItem> {

    /**
     * 根据批量作业ID查询所有子项（按序号排序）
     */
    default List<BatchJobItem> selectByBatchJobId(String batchJobId) {
        return selectList(new LambdaQueryWrapper<BatchJobItem>()
                .eq(BatchJobItem::getBatchJobId, batchJobId)
                .orderByAsc(BatchJobItem::getSequenceNumber)
                .last("LIMIT 5000"));
    }

    /**
     * 根据批量作业ID分页查询
     */
    default IPage<BatchJobItem> selectPageByBatchJobId(Page<BatchJobItem> page, String batchJobId,
                                                        String status) {
        LambdaQueryWrapper<BatchJobItem> wrapper = new LambdaQueryWrapper<BatchJobItem>()
                .eq(BatchJobItem::getBatchJobId, batchJobId)
                .eq(status != null && !status.isEmpty(), BatchJobItem::getStatus, status)
                .orderByAsc(BatchJobItem::getSequenceNumber);
        return selectPage(page, wrapper);
    }

    /**
     * 根据批量作业ID和状态查询
     */
    default List<BatchJobItem> selectByBatchJobIdAndStatus(String batchJobId, String status) {
        return selectList(new LambdaQueryWrapper<BatchJobItem>()
                .eq(BatchJobItem::getBatchJobId, batchJobId)
                .eq(BatchJobItem::getStatus, status)
                .orderByAsc(BatchJobItem::getSequenceNumber)
                .last("LIMIT 5000"));
    }

    /**
     * 获取待提交的子项（受并发控制）
     */
    default List<BatchJobItem> selectPendingItems(String batchJobId, int limit) {
        return selectList(new LambdaQueryWrapper<BatchJobItem>()
                .eq(BatchJobItem::getBatchJobId, batchJobId)
                .eq(BatchJobItem::getStatus, "PENDING")
                .orderByAsc(BatchJobItem::getSequenceNumber)
                .last("LIMIT " + limit));
    }

    /**
     * 根据 Task ID 查询子项
     */
    default BatchJobItem selectByTaskId(String taskId) {
        return selectOne(new LambdaQueryWrapper<BatchJobItem>()
                .eq(BatchJobItem::getTaskId, taskId)
                .last("LIMIT 1"));
    }

    /**
     * 统计指定状态的子项数
     */
    @Select("SELECT COUNT(*) FROM t_batch_job_item WHERE batch_job_id = #{batchJobId} AND status = #{status}")
    int countByStatus(@Param("batchJobId") String batchJobId, @Param("status") String status);

    /**
     * 批量更新状态（用于取消）
     */
    @Update("UPDATE t_batch_job_item SET status = #{newStatus}, updated_at = NOW() " +
            "WHERE batch_job_id = #{batchJobId} AND status = #{oldStatus}")
    int batchUpdateStatus(@Param("batchJobId") String batchJobId,
                          @Param("oldStatus") String oldStatus,
                          @Param("newStatus") String newStatus);

    /**
     * CAS 更新子项状态（原子操作，防止竞态）
     * 仅当当前状态为 expectedStatus 时才更新为 newStatus。
     * 用于防止 BLOCKING 模式下 MQ 回调和 handleIfTaskAlreadyTerminated 同时更新的竞态条件。
     *
     * @return 更新的行数（0 表示竞态失败，其他线程已更新）
     */
    @Update("UPDATE t_batch_job_item SET status = #{newStatus}, " +
            "credit_cost = COALESCE(#{creditCost}, credit_cost), " +
            "updated_at = NOW() " +
            "WHERE id = #{itemId} AND status = #{expectedStatus}")
    int casUpdateStatus(@Param("itemId") String itemId,
                        @Param("expectedStatus") String expectedStatus,
                        @Param("newStatus") String newStatus,
                        @Param("creditCost") Long creditCost);

    /**
     * 记录 provider fallback 失败：追加到 failed_provider_ids，递增 retry_count，更新 last_error_at。
     * 使用 PostgreSQL JSONB 操作符避免读改写竞态。
     *
     * @return 更新行数（应为 1；为 0 表示 itemId 不存在）
     */
    @Update("UPDATE t_batch_job_item SET " +
            "  failed_provider_ids = COALESCE(failed_provider_ids, '[]'::jsonb) || to_jsonb(#{providerId}::text), " +
            "  retry_count = retry_count + 1, " +
            "  last_error_at = NOW(), " +
            "  error_message = #{errorMessage}, " +
            "  updated_at = NOW() " +
            "WHERE id = #{itemId}")
    int recordFallbackAttempt(@Param("itemId") String itemId,
                              @Param("providerId") String providerId,
                              @Param("errorMessage") String errorMessage);

    /**
     * 根据 pipeline_step_id 和状态统计子项数
     */
    @Select("SELECT COUNT(*) FROM t_batch_job_item WHERE batch_job_id = #{batchJobId} " +
            "AND status = #{status} AND pipeline_step_id = #{stepId}")
    int countByStatusAndStepId(@Param("batchJobId") String batchJobId,
                               @Param("status") String status,
                               @Param("stepId") String stepId);

    /**
     * 根据 pipeline_step_id 查询子项
     */
    default List<BatchJobItem> selectByStepId(String batchJobId, String stepId) {
        return selectList(new LambdaQueryWrapper<BatchJobItem>()
                .eq(BatchJobItem::getBatchJobId, batchJobId)
                .eq(BatchJobItem::getPipelineStepId, stepId)
                .orderByAsc(BatchJobItem::getSequenceNumber)
                .last("LIMIT 5000"));
    }
}
