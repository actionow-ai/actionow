package com.actionow.agent.mission.reconciler;

import com.actionow.agent.mapper.OrphanBatchJobMapper;
import com.actionow.agent.metrics.AgentMetrics;
import com.actionow.common.core.id.UuidGenerator;
import com.actionow.common.mq.constant.MqConstants;
import com.actionow.common.mq.message.MessageWrapper;
import com.actionow.common.mq.producer.MessageProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mission 状态对账兜底（不变量 5：BatchJob 终态 ⇒ mission_task 终态）。
 *
 * <p>周期性扫描「BatchJob 终态 + mission_task PENDING」的孤儿，重发 BatchJob 完成事件
 * 到 {@link MqConstants.Mission#ROUTING_TASK_CALLBACK}，由 {@code MissionTaskListener}
 * 处理。重放路径与正常路径完全一致，依赖 listener 自带的「mission_task 已非 PENDING 即跳过」
 * 幂等检查避免重复处理。
 *
 * <p>这是 ADR-0001 之后补的「双通道」第二通道：主通道（{@code BatchJobMissionNotifier} 即时
 * MQ 推送）一旦失败（routing key typo / 消费者宕机 / 消息丢失），由本组件补救。
 *
 * <p>常态下 {@code actionow.agent.mission.reconciler.recovered.total} 应为 0；持续 &gt; 0
 * 表示主通道存在系统性问题，需要排查。
 *
 * @author Actionow
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MissionReconciler {

    /** 默认每 2 分钟扫描一次，给主通道足够时间处理大多数情况。 */
    private static final String DEFAULT_INTERVAL_MS = "120000";

    /** 宽限期：BatchJob 完成不足 5 分钟时不补救，避免与正常通知抢跑。 */
    static final Duration GRACE = Duration.ofMinutes(5);

    /** 最远回溯期：超过 7 天的孤儿不再自动补救，避免周期性扫历史脏数据。 */
    static final Duration MAX_LOOKBACK = Duration.ofDays(7);

    /** 单次扫描上限：限制单次补发的消息洪峰。 */
    static final int SCAN_LIMIT = 50;

    private final OrphanBatchJobMapper orphanMapper;
    private final MessageProducer messageProducer;
    private final AgentMetrics metrics;

    @Scheduled(fixedDelayString = "${actionow.agent.mission.reconciler.interval-ms:" + DEFAULT_INTERVAL_MS + "}")
    public void reconcile() {
        LocalDateTime now = LocalDateTime.now();
        List<OrphanBatchJobRow> orphans;
        try {
            orphans = orphanMapper.selectOrphans(now.minus(GRACE), now.minus(MAX_LOOKBACK), SCAN_LIMIT);
        } catch (Exception e) {
            log.error("MissionReconciler 扫描孤儿失败", e);
            return;
        }

        if (orphans.isEmpty()) {
            return;
        }

        log.warn("MissionReconciler 发现 {} 条孤儿 BatchJob，开始补发终态通知", orphans.size());
        for (OrphanBatchJobRow row : orphans) {
            try {
                republish(row);
                metrics.recordMissionReconcilerRecovered();
            } catch (Exception e) {
                log.error("补发 BatchJob 终态通知失败: batchJobId={}, missionId={}",
                        row.batchJobId(), row.missionId(), e);
            }
        }
    }

    private void republish(OrphanBatchJobRow row) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("missionId", row.missionId());
        payload.put("batchJobId", row.batchJobId());
        payload.put("status", row.status());
        payload.put("completedItems", row.completedItems() != null ? row.completedItems() : 0);
        payload.put("failedItems", row.failedItems() != null ? row.failedItems() : 0);
        payload.put("skippedItems", row.skippedItems() != null ? row.skippedItems() : 0);
        payload.put("actualCredits", row.actualCredits() != null ? row.actualCredits() : 0L);
        payload.put("reconciled", Boolean.TRUE);

        MessageWrapper<Map<String, Object>> message = MessageWrapper.<Map<String, Object>>builder()
                .messageId(UuidGenerator.generateUuidV7())
                .messageType(MqConstants.BatchJob.MSG_COMPLETED)
                .payload(payload)
                .workspaceId(row.workspaceId())
                .senderId(row.creatorId())
                .build();

        messageProducer.sendDirect(MqConstants.Mission.ROUTING_TASK_CALLBACK, message);
        log.warn("已补发 BatchJob 终态通知: missionId={}, batchJobId={}, status={}",
                row.missionId(), row.batchJobId(), row.status());
    }
}
