package com.actionow.agent.mission.reconciler;

/**
 * 孤儿 BatchJob 扫描结果行。字段命名与 t_batch_job 列名对齐，
 * 复用 {@link com.actionow.task.service.BatchJobMissionNotifier} 发送的 payload 结构，
 * 让 {@link com.actionow.agent.service.impl.MissionTaskListener#handleBatchJobCompleted} 无差别处理。
 */
public record OrphanBatchJobRow(
        String batchJobId,
        String missionId,
        String status,
        String workspaceId,
        String creatorId,
        Integer completedItems,
        Integer failedItems,
        Integer skippedItems,
        Long actualCredits
) {
}
