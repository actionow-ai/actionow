package com.actionow.task.consumer;

import com.actionow.common.core.context.UserContext;
import com.actionow.common.core.context.UserContextHolder;
import com.actionow.common.core.id.UuidGenerator;
import com.actionow.common.mq.constant.MqConstants;
import com.actionow.common.mq.consumer.ConsumerRetryHelper;
import com.actionow.common.mq.message.MessageWrapper;
import com.actionow.common.mq.producer.MessageProducer;
import com.actionow.task.constant.TaskConstants;
import com.actionow.task.entity.BatchJob;
import com.actionow.task.entity.BatchJobItem;
import com.actionow.task.entity.Task;
import com.actionow.task.mapper.BatchJobItemMapper;
import com.actionow.task.mapper.BatchJobMapper;
import com.actionow.task.mapper.TaskMapper;
import com.actionow.task.service.BatchConcurrencyService;
import com.actionow.task.service.BatchJobMissionNotifier;
import com.actionow.task.service.BatchJobScanStateService;
import com.actionow.task.service.BatchJobSseService;
import com.actionow.task.service.PipelineEngine;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 批量作业任务回调监听器
 * 收到 task 完成/失败消息后更新 batch 状态
 *
 * @author Actionow
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchJobTaskListener {

    private final BatchJobMapper batchJobMapper;
    private final BatchJobItemMapper batchJobItemMapper;
    private final TaskMapper taskMapper;
    private final BatchConcurrencyService concurrencyService;
    private final BatchJobScanStateService scanStateService;
    private final BatchJobSseService sseService;
    private final MessageProducer messageProducer;
    private final PipelineEngine pipelineEngine;
    private final ConsumerRetryHelper retryHelper;
    private final BatchJobMissionNotifier missionNotifier;

    /**
     * 处理批量作业内 task 完成/失败回调
     */
    @RabbitListener(queues = MqConstants.BatchJob.QUEUE_TASK_CALLBACK)
    public void handleTaskCallback(MessageWrapper<Map<String, Object>> message, Channel channel,
                                    @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            Map<String, Object> payload = message.getPayload();
            String taskId = (String) payload.get("taskId");
            String taskStatus = (String) payload.get("status");
            String batchJobId = (String) payload.get("batchJobId");
            Number creditCostNum = (Number) payload.get("creditCost");
            long creditCost = creditCostNum != null ? creditCostNum.longValue() : 0L;

            log.info("收到批量作业任务回调: taskId={}, status={}, batchJobId={}",
                    taskId, taskStatus, batchJobId);

            // 查找对应的 batch item
            BatchJobItem item = batchJobItemMapper.selectByTaskId(taskId);
            if (item == null) {
                log.warn("未找到 task 对应的 batch item: taskId={}", taskId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 使用 item 的 batchJobId 如果消息中没有传
            if (!StringUtils.hasText(batchJobId)) {
                batchJobId = item.getBatchJobId();
            }

            BatchJob job = batchJobMapper.selectById(batchJobId);
            if (job == null) {
                log.warn("批量作业不存在: batchJobId={}", batchJobId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 更新 item 状态
            if ("COMPLETED".equals(taskStatus)) {
                handleItemCompleted(job, item, creditCost);
            } else if ("FAILED".equals(taskStatus)) {
                String errorMessage = (String) payload.get("errorMessage");
                handleItemFailed(job, item, errorMessage);
            }

            // 释放并发 permit
            concurrencyService.release(job.getId(), job.getWorkspaceId());
            scanStateService.markActive(job.getId());

            // 推送进度
            BatchJob updatedJob = batchJobMapper.selectById(job.getId());
            if (updatedJob != null) {
                sseService.sendProgress(updatedJob);
            }

            // 检查是否全部完成
            checkBatchCompletion(job.getId());

            // Pipeline: 检查是否需要推进到下一步
            if (TaskConstants.BatchType.PIPELINE.equals(job.getBatchType())) {
                try {
                    pipelineEngine.checkAndAdvanceStep(job.getId());
                } catch (Exception e) {
                    log.error("Pipeline 步骤推进失败: batchJobId={}", job.getId(), e);
                }
            }

            triggerBatchContinuation(job.getId());

            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("处理批量作业任务回调失败", e);
            try {
                retryHelper.retryOrDlq(message, channel, deliveryTag, 3,
                        MqConstants.EXCHANGE_DIRECT, MqConstants.BatchJob.ROUTING_TASK_CALLBACK);
            } catch (Exception ex) {
                log.error("消息重试失败", ex);
            }
        }
    }

    /**
     * 处理 item 完成
     */
    private void handleItemCompleted(BatchJob job, BatchJobItem item, long creditCost) {
        // CAS 更新：仅当 item 仍为 SUBMITTED 时才更新为 COMPLETED，
        // 防止与 BatchJobConsumer.handleIfTaskAlreadyTerminated 的竞态
        int updated = batchJobItemMapper.casUpdateStatus(
                item.getId(), TaskConstants.BatchItemStatus.SUBMITTED,
                TaskConstants.BatchItemStatus.COMPLETED, creditCost);
        if (updated == 0) {
            log.debug("批量子项已被其他线程处理（CAS 竞态）: itemId={}, taskId={}", item.getId(), item.getTaskId());
            return;
        }

        // 同步内存对象状态（CAS 已在 DB 中更新，这里同步 Java 对象以防后续 updateById 覆盖）
        item.setStatus(TaskConstants.BatchItemStatus.COMPLETED);
        item.setCreditCost(creditCost);

        // Pipeline: 写回 task 输出到 item.params._output，供下游步骤插值引用
        if (item.getPipelineStepId() != null && item.getTaskId() != null) {
            storeTaskOutputToItem(item);
            batchJobItemMapper.updateById(item); // 更新 params 字段（status 已与 DB 一致）
        }

        batchJobMapper.incrementCompleted(job.getId(), creditCost, LocalDateTime.now());
        sseService.sendItemCompleted(job.getWorkspaceId(), item);

        log.info("批量子项完成: itemId={}, taskId={}, creditCost={}",
                item.getId(), item.getTaskId(), creditCost);
    }

    /**
     * 将 task 执行结果写回 item.params._output
     * 供 PipelineStepResolver 读取前序步骤输出
     */
    @SuppressWarnings("unchecked")
    private void storeTaskOutputToItem(BatchJobItem item) {
        try {
            Map<String, Object> output = new HashMap<>();

            // 保留原有 asset_id / relation_id 写入
            if (item.getAssetId() != null) {
                output.put("asset_id", item.getAssetId());
            }
            if (item.getRelationId() != null) {
                output.put("relation_id", item.getRelationId());
            }

            // 从 Task.outputResult 提取关键输出字段
            Task task = taskMapper.selectById(item.getTaskId());
            if (task != null && task.getOutputResult() != null) {
                Map<String, Object> result = task.getOutputResult();

                // 文件类输出
                if (result.get("fileUrl") != null) {
                    output.put("file_url", result.get("fileUrl"));
                }
                if (result.get("thumbnailUrl") != null) {
                    output.put("thumbnail_url", result.get("thumbnailUrl"));
                }
                if (result.get("mimeType") != null) {
                    output.put("mime_type", result.get("mimeType"));
                }
                if (result.get("fileSize") != null) {
                    output.put("file_size", result.get("fileSize"));
                }

                // 文本类输出: outputs.text / outputs.content / outputs.result → text
                Object outputs = result.get("outputs");
                if (outputs instanceof Map<?, ?> outputsMap) {
                    Object text = outputsMap.get("text");
                    if (text == null) text = outputsMap.get("content");
                    if (text == null) text = outputsMap.get("result");
                    if (text != null) {
                        output.put("text", text);
                    }
                }
                // fallback: 顶层 text 字段
                if (!output.containsKey("text") && result.get("text") != null) {
                    output.put("text", result.get("text"));
                }
            }

            // 将 output 写入 item params
            Map<String, Object> params = item.getParams() != null ? new HashMap<>(item.getParams()) : new HashMap<>();
            params.put("_output", output);
            item.setParams(params);
        } catch (Exception e) {
            log.warn("写回 task 输出到 item 失败: itemId={}", item.getId(), e);
        }
    }

    /**
     * 处理 item 失败
     */
    private void handleItemFailed(BatchJob job, BatchJobItem item, String errorMessage) {
        // CAS 更新：防止与 BatchJobConsumer.handleIfTaskAlreadyTerminated 的竞态
        int updated = batchJobItemMapper.casUpdateStatus(
                item.getId(), TaskConstants.BatchItemStatus.SUBMITTED,
                TaskConstants.BatchItemStatus.FAILED, null);
        if (updated == 0) {
            log.debug("批量子项已被其他线程处理（CAS 竞态）: itemId={}, taskId={}", item.getId(), item.getTaskId());
            return;
        }
        // 同步内存对象状态后再 updateById，避免旧 status 覆盖 CAS 结果
        item.setStatus(TaskConstants.BatchItemStatus.FAILED);
        item.setErrorMessage(errorMessage);
        item.setUpdatedAt(LocalDateTime.now());
        batchJobItemMapper.updateById(item);

        batchJobMapper.incrementFailed(job.getId(), LocalDateTime.now());
        sseService.sendItemFailed(job.getWorkspaceId(), item);

        log.info("批量子项失败: itemId={}, taskId={}, error={}",
                item.getId(), item.getTaskId(), errorMessage);

        // 错误策略 STOP
        if (TaskConstants.ErrorStrategy.STOP.equals(job.getErrorStrategy())) {
            log.info("错误策略 STOP，标记批量作业失败: batchJobId={}", job.getId());
            BatchJob currentJob = batchJobMapper.selectById(job.getId());
            if (currentJob != null && TaskConstants.BatchStatus.RUNNING.equals(currentJob.getStatus())) {
                currentJob.setStatus(TaskConstants.BatchStatus.FAILED);
                currentJob.setCompletedAt(LocalDateTime.now());
                batchJobMapper.updateById(currentJob);

                scanStateService.clear(job.getId());
                batchJobItemMapper.batchUpdateStatus(job.getId(),
                        TaskConstants.BatchItemStatus.PENDING, TaskConstants.BatchItemStatus.CANCELLED);

                sseService.sendBatchFailed(currentJob, "子项失败导致作业停止: " + errorMessage);
                missionNotifier.notifyMissionIfNeeded(currentJob);
            }
        }
    }

    /**
     * 检查批量作业是否全部完成
     */
    private void checkBatchCompletion(String batchJobId) {
        BatchJob job = batchJobMapper.selectById(batchJobId);
        if (job == null || !TaskConstants.BatchStatus.RUNNING.equals(job.getStatus())) {
            return;
        }

        long completed = job.getCompletedItems() != null ? job.getCompletedItems() : 0L;
        long failed = job.getFailedItems() != null ? job.getFailedItems() : 0L;
        long skipped = job.getSkippedItems() != null ? job.getSkippedItems() : 0L;
        int totalItems = job.getTotalItems() != null ? job.getTotalItems() : 0;
        if (completed + failed + skipped < totalItems) {
            return;
        }

        job.setStatus(TaskConstants.BatchStatus.COMPLETED);
        job.setProgress(100);
        job.setCompletedAt(LocalDateTime.now());
        batchJobMapper.updateById(job);

        scanStateService.clear(batchJobId);
        concurrencyService.cleanup(batchJobId);
        sseService.sendBatchCompleted(job);

        log.info("批量作业全部完成: id={}, completed={}, failed={}, skipped={}",
                job.getId(), job.getCompletedItems(), job.getFailedItems(), job.getSkippedItems());

        // 通知 Mission
        missionNotifier.notifyMissionIfNeeded(job);
    }

    private void triggerBatchContinuation(String batchJobId) {
        BatchJob currentJob = batchJobMapper.selectById(batchJobId);
        if (currentJob == null || !TaskConstants.BatchStatus.RUNNING.equals(currentJob.getStatus())) {
            return;
        }

        Map<String, Object> payload = Map.of(
                "batchJobId", currentJob.getId(),
                "workspaceId", currentJob.getWorkspaceId()
        );
        MessageWrapper<Map<String, Object>> message = MessageWrapper.<Map<String, Object>>builder()
                .messageId(UuidGenerator.generateUuidV7())
                .messageType(MqConstants.BatchJob.MSG_START)
                .payload(payload)
                .workspaceId(currentJob.getWorkspaceId())
                .senderId(currentJob.getCreatorId())
                .build();
        messageProducer.sendDirect(MqConstants.BatchJob.ROUTING_START, message);
    }
}
