package com.actionow.task.consumer;

import com.actionow.common.core.context.UserContext;
import com.actionow.common.core.context.UserContextHolder;
import com.actionow.common.core.result.Result;
import com.actionow.common.mq.constant.MqConstants;
import com.actionow.common.mq.consumer.ConsumerRetryHelper;
import com.actionow.common.mq.message.MessageWrapper;
import com.actionow.common.security.workspace.WorkspaceInternalClient;
import com.actionow.task.constant.TaskConstants;
import com.actionow.task.dto.EntityGenerationRequest;
import com.actionow.task.dto.EntityGenerationResponse;
import com.actionow.task.entity.BatchJob;
import com.actionow.task.entity.BatchJobItem;
import com.actionow.task.entity.Task;
import com.actionow.task.feign.AssetFeignClient;
import com.actionow.task.mapper.BatchJobItemMapper;
import com.actionow.task.mapper.BatchJobMapper;
import com.actionow.task.mapper.TaskMapper;
import com.actionow.task.config.TaskRuntimeConfigService;
import com.actionow.task.service.AiGenerationFacade;
import com.actionow.task.service.BatchConcurrencyService;
import com.actionow.task.service.BatchJobMissionNotifier;
import com.actionow.task.service.BatchJobScanStateService;
import com.actionow.task.service.BatchJobSseService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 批量作业 MQ 消费者
 * 收到 batch.start 消息后，遍历 PENDING items 并逐项提交
 *
 * @author Actionow
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchJobConsumer {

    private final BatchJobMapper batchJobMapper;
    private final BatchJobItemMapper batchJobItemMapper;
    private final TaskMapper taskMapper;
    private final AiGenerationFacade aiGenerationFacade;
    private final BatchConcurrencyService concurrencyService;
    private final BatchJobScanStateService scanStateService;
    private final BatchJobSseService sseService;
    private final WorkspaceInternalClient workspaceInternalClient;
    private final AssetFeignClient assetFeignClient;
    private final TaskRuntimeConfigService runtimeConfig;
    private final ConsumerRetryHelper retryHelper;
    private final BatchJobMissionNotifier missionNotifier;

    /** 连续空闲扫描次数，用于自动降频（AtomicInteger 保证线程安全） */
    private final java.util.concurrent.atomic.AtomicInteger idleCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int IDLE_THRESHOLD = 6; // 连续 6 次空闲（30s）后降频
    private static final long WAITING_RESCAN_COOLDOWN_MS = 30000L;
    private static final long STALE_BATCH_TIMEOUT_MS = 600000L;

    /**
     * 处理 batch.start 消息
     */
    @RabbitListener(queues = MqConstants.BatchJob.QUEUE)
    public void handleBatchJobStart(MessageWrapper<Map<String, Object>> message, Channel channel,
                                     @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            Map<String, Object> payload = message.getPayload();
            String batchJobId = (String) payload.get("batchJobId");
            String workspaceId = (String) payload.get("workspaceId");

            log.info("收到批量作业启动消息: batchJobId={}", batchJobId);
            idleCount.set(0); // 收到消息，重置空闲计数
            scanStateService.markActive(batchJobId);

            BatchJob job = batchJobMapper.selectById(batchJobId);
            if (job == null) {
                log.error("批量作业不存在: {}", batchJobId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 只处理 RUNNING 状态
            if (!TaskConstants.BatchStatus.RUNNING.equals(job.getStatus())) {
                log.info("批量作业状态非 RUNNING，跳过: id={}, status={}", batchJobId, job.getStatus());
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 恢复上下文
            restoreContextForJob(job);

            // 提交尽可能多的 PENDING items
            submitPendingItems(job);

            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("处理批量作业启动消息失败", e);
            try {
                retryHelper.retryOrDlq(message, channel, deliveryTag, 3,
                        MqConstants.EXCHANGE_DIRECT, MqConstants.BatchJob.ROUTING_START);
            } catch (Exception ex) {
                log.error("消息重试失败", ex);
            }
        } finally {
            UserContextHolder.clear();
        }
    }

    /**
     * 定时扫描：5秒间隔，检查有没有因为并发限制而堆积的 PENDING items
     * 空闲时自动降频：连续无任务超过阈值后，每 6 轮只执行 1 次查询
     */
    @Scheduled(fixedDelayString = "${actionow.task.batch.scan-interval-ms:30000}")
    public void scanAndSubmitPendingItems() {
        // 空闲降频：连续空闲超过阈值后，每 6 轮才查一次 DB
        int currentIdle = idleCount.get();
        if (currentIdle > IDLE_THRESHOLD && currentIdle % IDLE_THRESHOLD != 0) {
            idleCount.incrementAndGet();
            return;
        }

        if (scanStateService.shouldSkipGlobalScan()) {
            return;
        }

        List<BatchJob> runningJobs = batchJobMapper.selectByStatus(TaskConstants.BatchStatus.RUNNING, 20);
        if (runningJobs.isEmpty()) {
            int idle = idleCount.incrementAndGet();
            if (idle == IDLE_THRESHOLD) {
                log.debug("批量作业扫描进入降频模式（无运行中作业）");
            }
            return;
        }

        // 有任务时重置空闲计数
        idleCount.set(0);
        int skippedJobs = 0;
        for (BatchJob job : runningJobs) {
            if (scanStateService.shouldSkip(job.getId())) {
                skippedJobs++;
                continue;
            }
            try {
                restoreContextForJob(job);
                submitPendingItems(job);
            } catch (Exception e) {
                log.error("定时扫描提交失败: batchJobId={}", job.getId(), e);
            } finally {
                UserContextHolder.clear();
            }
        }

        if (skippedJobs == runningJobs.size()) {
            long delayMs = scanStateService.getMinRemainingDelayMs(
                    runningJobs.stream().map(BatchJob::getId).toList());
            if (delayMs > 0L) {
                scanStateService.markGlobalWaiting(delayMs);
            }
        }
    }

    /**
     * 提交 PENDING items（受并发控制）
     */
    private void submitPendingItems(BatchJob job) {
        int maxConcurrency = job.getMaxConcurrency() != null ? job.getMaxConcurrency() : 5;
        List<BatchJobItem> pendingItems = batchJobItemMapper.selectPendingItems(job.getId(), maxConcurrency);

        if (pendingItems.isEmpty()) {
            log.debug("无待提交子项: batchJobId={}", job.getId());
            if (hasUnfinishedItems(job)) {
                if (isStaleBatch(job)) {
                    failStaleBatch(job, "批量作业长时间无进展，自动终止");
                    return;
                }
                scanStateService.markWaiting(job.getId(), WAITING_RESCAN_COOLDOWN_MS);
                return;
            }
            tryCompleteJobIfAllItemsDone(job.getId());
            return;
        }

        scanStateService.markActive(job.getId());

        // 预检查 batch 状态（避免循环内每次查 DB）
        BatchJob currentJob = batchJobMapper.selectById(job.getId());
        if (currentJob == null || !TaskConstants.BatchStatus.RUNNING.equals(currentJob.getStatus())) {
            scanStateService.clear(job.getId());
            log.info("批量作业状态非 RUNNING，跳过提交: id={}, status={}",
                    job.getId(), currentJob != null ? currentJob.getStatus() : "null");
            return;
        }

        // 批量预检查 asset 是否存在（避免 N+1 Feign 调用）
        Set<String> existingAssetKeys = preCheckAssetExistence(pendingItems, currentJob.getWorkspaceId());

        for (BatchJobItem item : pendingItems) {
            // 并发控制
            if (!concurrencyService.tryAcquire(currentJob)) {
                log.debug("并发限流，停止本轮提交: batchJobId={}", job.getId());
                break;
            }

            try {
                // 使用预检查结果判断是否跳过
                if (TaskConstants.SkipCondition.ASSET_EXISTS.equals(item.getSkipCondition())) {
                    String key = item.getEntityType() + ":" + item.getEntityId();
                    if (existingAssetKeys.contains(key)) {
                        handleItemSkipped(currentJob, item, "素材已存在");
                        concurrencyService.release(job.getId(), job.getWorkspaceId());
                        continue;
                    }
                }
                submitSingleItem(currentJob, item);
            } catch (Exception e) {
                log.error("提交子项失败: itemId={}, error={}", item.getId(), e.getMessage(), e);
                concurrencyService.release(job.getId(), job.getWorkspaceId());
                handleItemFailure(currentJob, item, e.getMessage());
            }
        }
    }

    /**
     * 批量预检查 asset 是否存在
     * 将所有 ASSET_EXISTS 条件跳过项汇总为一次批量查询，避免 N+1 Feign 调用
     *
     * @return 已存在 asset 的 key 集合（格式: "entityType:entityId"）
     */
    private Set<String> preCheckAssetExistence(List<BatchJobItem> items, String workspaceId) {
        Set<String> existingKeys = new HashSet<>();

        List<BatchJobItem> needCheck = items.stream()
                .filter(i -> TaskConstants.SkipCondition.ASSET_EXISTS.equals(i.getSkipCondition()))
                .filter(i -> StringUtils.hasText(i.getEntityType()) && StringUtils.hasText(i.getEntityId()))
                .toList();

        if (needCheck.isEmpty()) {
            return existingKeys;
        }

        // 先检查 item 自身是否已关联 assetId
        for (BatchJobItem item : needCheck) {
            if (StringUtils.hasText(item.getAssetId())) {
                existingKeys.add(item.getEntityType() + ":" + item.getEntityId());
            }
        }

        // 对剩余的做批量 Feign 查询
        List<Map<String, String>> queries = needCheck.stream()
                .filter(i -> !StringUtils.hasText(i.getAssetId()))
                .map(i -> Map.of("entityType", i.getEntityType(), "entityId", i.getEntityId()))
                .distinct()
                .toList();

        if (!queries.isEmpty()) {
            try {
                Result<Map<String, Boolean>> result =
                        assetFeignClient.batchCheckEntityAssets(workspaceId, queries);
                if (result.isSuccess() && result.getData() != null) {
                    result.getData().forEach((key, exists) -> {
                        if (Boolean.TRUE.equals(exists)) {
                            existingKeys.add(key);
                        }
                    });
                }
            } catch (Exception e) {
                log.warn("批量检查实体素材失败，将跳过条件检查: batchJobId items={}", needCheck.size(), e);
            }
        }

        return existingKeys;
    }

    /**
     * 提交单个 item（asset 预检查已在 submitPendingItems 中完成）
     */
    private void submitSingleItem(BatchJob job, BatchJobItem item) {
        // 构建 EntityGenerationRequest 并委托给已有的 submitEntityGeneration
        EntityGenerationRequest genRequest = EntityGenerationRequest.builder()
                .entityType(item.getEntityType())
                .entityId(item.getEntityId())
                .generationType(item.getGenerationType())
                .providerId(item.getProviderId())
                .params(item.getParams() != null ? item.getParams() : new HashMap<>())
                .scriptId(job.getScriptId())
                .build();

        // 如果有 variant seed，注入到 params
        if (item.getVariantSeed() != null) {
            genRequest.getParams().put("seed", item.getVariantSeed());
        }

        log.info("提交批量子项: itemId={}, entityType={}, entityId={}, generationType={}",
                item.getId(), item.getEntityType(), item.getEntityId(), item.getGenerationType());

        EntityGenerationResponse response = aiGenerationFacade.submitEntityGeneration(
                genRequest, job.getWorkspaceId(), job.getCreatorId());

        if (response != null && response.isSuccess()) {
            // 更新 item 状态
            item.setStatus(TaskConstants.BatchItemStatus.SUBMITTED);
            item.setTaskId(response.getTaskId());
            item.setAssetId(response.getAssetId());
            item.setRelationId(response.getRelationId());
            item.setUpdatedAt(LocalDateTime.now());
            batchJobItemMapper.updateById(item);

            // 更新 task 的 batch 关联
            updateTaskBatchFields(response.getTaskId(), job.getId(), item.getId());

            // BLOCKING 任务可能在 updateBatchFields 之前已完成：
            // 此时 sendBatchJobTaskCallback 因 batchJobId 为空而未发出，需在此补处理。
            // 若任务已终态，直接更新 item 状态并释放 permit；否则等待正常 MQ 回调。
            if (handleIfTaskAlreadyTerminated(job, item, response.getTaskId())) {
                return;
            }

            sseService.sendItemSubmitted(job.getWorkspaceId(), item);
            log.info("批量子项提交成功: itemId={}, taskId={}", item.getId(), response.getTaskId());
        } else {
            String errorMsg = response != null ? response.getErrorMessage() : "提交失败";
            concurrencyService.release(job.getId(), job.getWorkspaceId());
            handleItemFailure(job, item, errorMsg);
        }
    }

    /**
     * 处理子项失败
     */
    private void handleItemFailure(BatchJob job, BatchJobItem item, String errorMessage) {
        item.setStatus(TaskConstants.BatchItemStatus.FAILED);
        item.setErrorMessage(errorMessage);
        item.setUpdatedAt(LocalDateTime.now());
        batchJobItemMapper.updateById(item);

        batchJobMapper.incrementFailed(job.getId(), LocalDateTime.now());
        sseService.sendItemFailed(job.getWorkspaceId(), item);

        // 错误策略：STOP
        if (TaskConstants.ErrorStrategy.STOP.equals(job.getErrorStrategy())) {
            log.info("错误策略 STOP，标记批量作业失败: batchJobId={}", job.getId());
            job.setStatus(TaskConstants.BatchStatus.FAILED);
            job.setCompletedAt(LocalDateTime.now());
            batchJobMapper.updateById(job);

            scanStateService.clear(job.getId());
            // 取消所有 PENDING items
            batchJobItemMapper.batchUpdateStatus(job.getId(),
                    TaskConstants.BatchItemStatus.PENDING, TaskConstants.BatchItemStatus.CANCELLED);

            sseService.sendBatchFailed(job, "子项失败导致作业停止: " + errorMessage);
            missionNotifier.notifyMissionIfNeeded(job);
        }
    }

    /**
     * 处理子项跳过
     */
    private void handleItemSkipped(BatchJob job, BatchJobItem item, String reason) {
        item.setStatus(TaskConstants.BatchItemStatus.SKIPPED);
        item.setSkipped(true);
        item.setSkipReason(reason);
        item.setUpdatedAt(LocalDateTime.now());
        batchJobItemMapper.updateById(item);

        batchJobMapper.incrementSkipped(job.getId(), LocalDateTime.now());
        sseService.sendItemSkipped(job.getWorkspaceId(), item);
    }

    /**
     * 更新 task 的 batch 关联字段
     * 使用不含 version 的定向 UPDATE，与 executeTask 的乐观锁更新互不干扰。
     */
    private void updateTaskBatchFields(String taskId, String batchJobId, String batchItemId) {
        try {
            taskMapper.updateBatchFields(taskId, batchJobId, batchItemId, TaskConstants.TaskSource.BATCH);
        } catch (Exception e) {
            log.warn("更新 task batch 字段失败: taskId={}", taskId, e);
        }
    }

    /**
     * BLOCKING 模式补偿：若任务在 updateBatchFields 之前已完成，直接在此处理 item 终态。
     * 正常情况下 sendBatchJobTaskCallback 会发 MQ 回调，BatchJobTaskListener 处理；
     * 但 batchJobId 未设置时回调无法发出，item 会永久卡在 SUBMITTED。
     *
     * @return true 表示任务已终态且已处理，调用方可直接 return；false 表示任务仍在进行中
     */
    private boolean handleIfTaskAlreadyTerminated(BatchJob job, BatchJobItem item, String taskId) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) return false;

        if (TaskConstants.TaskStatus.COMPLETED.equals(task.getStatus())) {
            long creditCost = task.getCreditCost() != null ? task.getCreditCost() : 0L;

            // CAS 更新：仅当 item 仍为 SUBMITTED 时才更新为 COMPLETED，
            // 防止与 MQ 回调的 BatchJobTaskListener 竞态导致重复处理
            int updated = batchJobItemMapper.casUpdateStatus(
                    item.getId(), TaskConstants.BatchItemStatus.SUBMITTED,
                    TaskConstants.BatchItemStatus.COMPLETED, creditCost);
            if (updated == 0) {
                log.debug("批量子项已被其他线程处理（CAS 竞态）: itemId={}, taskId={}", item.getId(), taskId);
                return true; // 已被处理，直接返回
            }

            batchJobMapper.incrementCompleted(job.getId(), creditCost, LocalDateTime.now());
            concurrencyService.release(job.getId(), job.getWorkspaceId());
            scanStateService.markActive(job.getId());
            sseService.sendItemCompleted(job.getWorkspaceId(), item);
            log.info("BLOCKING 任务已完成，批量子项直接标记完成: itemId={}, taskId={}", item.getId(), taskId);

            tryCompleteJobIfAllItemsDone(job.getId());
            return true;
        }

        if (TaskConstants.TaskStatus.FAILED.equals(task.getStatus())) {
            // CAS 更新：防止与 MQ 回调竞态
            int updated = batchJobItemMapper.casUpdateStatus(
                    item.getId(), TaskConstants.BatchItemStatus.SUBMITTED,
                    TaskConstants.BatchItemStatus.FAILED, null);
            if (updated == 0) {
                log.debug("批量子项已被其他线程处理（CAS 竞态）: itemId={}, taskId={}", item.getId(), taskId);
                return true;
            }

            concurrencyService.release(job.getId(), job.getWorkspaceId());
            scanStateService.markActive(job.getId());
            handleItemFailure(job, item, task.getErrorMessage());
            log.info("BLOCKING 任务已失败，批量子项直接标记失败: itemId={}, taskId={}", item.getId(), taskId);
            return true;
        }

        return false;
    }

    /**
     * 安全网：无 PENDING 子项时检查作业是否已全部结束
     * 防止 MQ 回调丢失导致 BatchJob 永久卡在 RUNNING 状态
     */
    private void tryCompleteJobIfAllItemsDone(String batchJobId) {
        BatchJob currentJob = batchJobMapper.selectById(batchJobId);
        if (currentJob == null || !TaskConstants.BatchStatus.RUNNING.equals(currentJob.getStatus())) {
            return;
        }

        if (hasUnfinishedItems(currentJob)) {
            return;
        }

        currentJob.setStatus(TaskConstants.BatchStatus.COMPLETED);
        currentJob.setProgress(100);
        currentJob.setCompletedAt(LocalDateTime.now());
        batchJobMapper.updateById(currentJob);

        scanStateService.clear(currentJob.getId());
        concurrencyService.cleanup(currentJob.getId());
        sseService.sendBatchCompleted(currentJob);

        log.info("批量作业由扫描器完成（MQ 回调安全网）: id={}, completed={}, failed={}, skipped={}",
                currentJob.getId(), currentJob.getCompletedItems(),
                currentJob.getFailedItems(), currentJob.getSkippedItems());

        missionNotifier.notifyMissionIfNeeded(currentJob);
    }

    private boolean hasUnfinishedItems(BatchJob job) {
        int totalItems = job.getTotalItems() != null ? job.getTotalItems() : 0;
        long completed = job.getCompletedItems() != null ? job.getCompletedItems() : 0L;
        long failed = job.getFailedItems() != null ? job.getFailedItems() : 0L;
        long skipped = job.getSkippedItems() != null ? job.getSkippedItems() : 0L;
        return completed + failed + skipped < totalItems;
    }

    private boolean isStaleBatch(BatchJob job) {
        LocalDateTime lastProgressAt = job.getUpdatedAt() != null
                ? job.getUpdatedAt()
                : (job.getStartedAt() != null ? job.getStartedAt() : job.getCreatedAt());
        if (lastProgressAt == null) {
            return false;
        }
        long staleTimeoutMs = resolveBatchStaleTimeoutMs(job.getBatchType());
        return lastProgressAt.plusNanos(staleTimeoutMs * 1_000_000L).isBefore(LocalDateTime.now());
    }

    /**
     * 根据批量类型解析不同的超时阈值：
     * - PIPELINE / FULL_STORYBOARD: 30 分钟（多步骤管线，单步可能需要 5+ 分钟）
     * - SIMPLE / VARIATION / AB_TEST / SCOPE: 使用 RuntimeConfig 配置（默认 10 分钟）
     */
    private long resolveBatchStaleTimeoutMs(String batchType) {
        // Pipeline 类型需要更长的超时
        if (TaskConstants.BatchType.PIPELINE.equals(batchType)) {
            return 1800000L; // 30 分钟
        }

        try {
            long configured = runtimeConfig.getBatchStaleTimeoutMs();
            return configured > 0L ? configured : STALE_BATCH_TIMEOUT_MS;
        } catch (Exception e) {
            return STALE_BATCH_TIMEOUT_MS;
        }
    }

    private void failStaleBatch(BatchJob job, String reason) {
        BatchJob currentJob = batchJobMapper.selectById(job.getId());
        if (currentJob == null || !TaskConstants.BatchStatus.RUNNING.equals(currentJob.getStatus())) {
            return;
        }

        currentJob.setStatus(TaskConstants.BatchStatus.FAILED);
        currentJob.setCompletedAt(LocalDateTime.now());
        batchJobMapper.updateById(currentJob);

        // 取消所有未提交的 PENDING items，防止扫描器再次拾取
        batchJobItemMapper.batchUpdateStatus(currentJob.getId(),
                TaskConstants.BatchItemStatus.PENDING, TaskConstants.BatchItemStatus.CANCELLED);

        scanStateService.clear(currentJob.getId());
        concurrencyService.cleanup(currentJob.getId());
        sseService.sendBatchFailed(currentJob, reason);

        log.warn("批量作业长时间无进展，已标记失败: batchJobId={}, reason={}, updatedAt={}",
                currentJob.getId(), reason, currentJob.getUpdatedAt());

        // 必须通知 Mission，否则关联 Mission 会永久卡在 WAITING
        missionNotifier.notifyMissionIfNeeded(currentJob);
    }

    /**
     * 恢复上下文
     */
    private void restoreContextForJob(BatchJob job) {
        UserContext context = new UserContext();
        context.setUserId(job.getCreatorId());
        context.setWorkspaceId(job.getWorkspaceId());

        // 获取 tenant schema
        if (job.getWorkspaceId() != null) {
            try {
                Result<String> schemaResult = workspaceInternalClient.getTenantSchema(job.getWorkspaceId());
                if (schemaResult.isSuccess() && schemaResult.getData() != null) {
                    context.setTenantSchema(schemaResult.getData());
                }
            } catch (Exception e) {
                log.warn("获取租户Schema失败: workspaceId={}", job.getWorkspaceId());
            }
        }

        UserContextHolder.setContext(context);
    }
}
