package com.actionow.task.service;

import com.actionow.common.core.id.UuidGenerator;
import com.actionow.common.mq.constant.MqConstants;
import com.actionow.common.mq.message.MessageWrapper;
import com.actionow.common.mq.producer.MessageProducer;
import com.actionow.task.entity.BatchJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * BatchJob 终态 → Mission 回调通知
 * 终态包括 COMPLETED / FAILED / CANCELLED；任意终态均必须发出，避免 Mission 永久 WAITING。
 *
 * @author Actionow
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchJobMissionNotifier {

    private final MessageProducer messageProducer;

    public void notifyMissionIfNeeded(BatchJob job) {
        if (job == null || !StringUtils.hasText(job.getMissionId())) {
            return;
        }

        Map<String, Object> payload = Map.of(
                "missionId", job.getMissionId(),
                "batchJobId", job.getId(),
                "status", job.getStatus(),
                "completedItems", job.getCompletedItems() != null ? job.getCompletedItems() : 0,
                "failedItems", job.getFailedItems() != null ? job.getFailedItems() : 0,
                "skippedItems", job.getSkippedItems() != null ? job.getSkippedItems() : 0,
                "actualCredits", job.getActualCredits() != null ? job.getActualCredits() : 0
        );

        MessageWrapper<Map<String, Object>> message = MessageWrapper.<Map<String, Object>>builder()
                .messageId(UuidGenerator.generateUuidV7())
                .messageType(MqConstants.BatchJob.MSG_COMPLETED)
                .payload(payload)
                .workspaceId(job.getWorkspaceId())
                .senderId(job.getCreatorId())
                .build();

        messageProducer.sendDirect(MqConstants.BatchJob.ROUTING_COMPLETED, message);
        log.info("批量作业终态通知已发送: missionId={}, batchJobId={}, status={}",
                job.getMissionId(), job.getId(), job.getStatus());
    }
}
