package com.actionow.task.service;

import com.actionow.common.core.exception.BusinessException;
import com.actionow.common.core.id.UuidGenerator;
import com.actionow.common.core.result.ResultCode;
import com.actionow.common.mq.constant.MqConstants;
import com.actionow.common.mq.message.MessageWrapper;
import com.actionow.common.mq.producer.MessageProducer;
import com.actionow.task.constant.TaskConstants;
import com.actionow.task.dto.*;
import com.actionow.task.entity.BatchJob;
import com.actionow.task.entity.BatchJobItem;
import com.actionow.task.entity.Pipeline;
import com.actionow.task.mapper.BatchJobItemMapper;
import com.actionow.task.mapper.BatchJobMapper;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 批量作业编排器
 * 负责：
 * 1. 创建 BatchJob + Items
 * 2. Variation 展开（1 实体 → N 变体 item）
 * 3. 条件跳过检查
 * 4. 费用预估
 * 5. 发送 batch.start MQ 消息
 *
 * @author Actionow
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchJobOrchestrator {

    private final BatchJobMapper batchJobMapper;
    private final BatchJobItemMapper batchJobItemMapper;
    private final AiGenerationFacade aiGenerationFacade;
    private final MessageProducer messageProducer;
    private final BatchConcurrencyService concurrencyService;
    private final BatchJobSseService sseService;
    private final ScopeExpansionService scopeExpansionService;
    private final BatchJobMissionNotifier missionNotifier;
    private final @Lazy PipelineEngine pipelineEngine;

    @Setter(onMethod_ = {@Autowired, @Lazy})
    private BatchJobOrchestrator self;

    /**
     * 创建并启动批量作业
     */
    public BatchJobResponse createAndStart(CreateBatchJobRequest request,
                                            String workspaceId, String userId) {
        // 验证
        validateRequest(request);

        // 幂等命中（在校验后、任何昂贵操作之前）：
        // 同一 mission 内同 idempotencyKey 已存在则直接返回，不重复创建/Scope 展开/积分预冻结。
        // 防御 LLM 单步内重复调用 delegate_*（典型现象：5 次调用 → 5 个 BatchJob → 25 倍重复执行）。
        if (StringUtils.hasText(request.getMissionId()) && StringUtils.hasText(request.getIdempotencyKey())) {
            BatchJob existing = batchJobMapper.selectByMissionAndIdempotencyKey(
                    request.getMissionId(), request.getIdempotencyKey());
            if (existing != null) {
                log.warn("BatchJob 幂等命中，返回已有作业: missionId={}, idempotencyKey={}, existingId={}",
                        request.getMissionId(), request.getIdempotencyKey(), existing.getId());
                return BatchJobResponse.fromEntity(existing);
            }
        }

        // SCOPE 类型先在事务外做 Feign 展开，避免长事务
        List<BatchJobItem> preExpandedItems = null;
        if (TaskConstants.BatchType.SCOPE.equals(request.getBatchType())) {
            BatchJob tempJob = buildBatchJob(request, workspaceId, userId);
            preExpandedItems = scopeExpansionService.expandScope(tempJob);
            if (preExpandedItems.isEmpty()) {
                throw new BusinessException(ResultCode.PARAM_INVALID, "Scope 展开后无可用实体");
            }
        }

        try {
            return self.doCreateAndStart(request, workspaceId, userId, preExpandedItems);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 唯一索引兜底：并发场景下两次预检查都未命中，但插入时被 uk_batch_job_mission_idem 拦下。
            BatchJob existing = batchJobMapper.selectByMissionAndIdempotencyKey(
                    request.getMissionId(), request.getIdempotencyKey());
            if (existing != null) {
                log.warn("BatchJob 幂等竞态命中（DB 唯一索引兜底）: missionId={}, idempotencyKey={}, existingId={}",
                        request.getMissionId(), request.getIdempotencyKey(), existing.getId());
                return BatchJobResponse.fromEntity(existing);
            }
            throw e;
        }
    }

    /**
     * 事务内执行创建和启动
     */
    @Transactional(rollbackFor = Exception.class)
    public BatchJobResponse doCreateAndStart(CreateBatchJobRequest request,
                                              String workspaceId, String userId,
                                              List<BatchJobItem> preExpandedItems) {
        // 1. 创建 BatchJob
        BatchJob job = buildBatchJob(request, workspaceId, userId);
        batchJobMapper.insert(job);
        log.info("批量作业已创建: id={}, type={}, workspaceId={}", job.getId(), job.getBatchType(), workspaceId);

        // 2. 分支：PIPELINE、SCOPE、AB_TEST 走各自流程，其他走标准展开
        if (TaskConstants.BatchType.PIPELINE.equals(job.getBatchType())) {
            return createAndStartPipeline(job, request);
        }
        if (TaskConstants.BatchType.SCOPE.equals(job.getBatchType())) {
            return createAndStartScope(job, request, preExpandedItems);
        }
        if (TaskConstants.BatchType.AB_TEST.equals(job.getBatchType())) {
            return createAndStartAbTest(job, request);
        }

        return createAndStartSimple(job, request);
    }

    /**
     * 标准（非 Pipeline）批量作业启动流程
     */
    private BatchJobResponse createAndStartSimple(BatchJob job, CreateBatchJobRequest request) {
        List<BatchJobItem> items = expandItems(job, request);
        return insertItemsAndStart(job, items);
    }

    /**
     * Pipeline 类型批量作业启动流程
     * items 中的条目作为 Pipeline 第一步的初始输入
     */
    private BatchJobResponse createAndStartPipeline(BatchJob job, CreateBatchJobRequest request) {
        // 转换自定义步骤配置
        List<PipelineEngine.PipelineStepConfig> customSteps = null;
        if (request.getPipelineSteps() != null && !request.getPipelineSteps().isEmpty()) {
            customSteps = request.getPipelineSteps().stream()
                    .map(s -> new PipelineEngine.PipelineStepConfig(
                            s.getName(), s.getStepType(), s.getGenerationType(),
                            s.getProviderId(), s.getParamsTemplate(),
                            s.getDependsOn(), s.getFanOutCount()))
                    .toList();
        }

        // 创建 Pipeline
        Pipeline pipeline = pipelineEngine.createPipeline(
                job, request.getPipelineTemplate(), customSteps,
                request.getStepProviderOverrides());

        // 准备初始输入（从 request.items 转换）
        List<Map<String, Object>> inputItems = new ArrayList<>();
        for (CreateBatchJobRequest.BatchJobItemRequest itemReq : request.getItems()) {
            Map<String, Object> input = new HashMap<>();
            input.put("entityType", itemReq.getEntityType());
            input.put("entityId", itemReq.getEntityId());
            input.put("entityName", itemReq.getEntityName());
            Map<String, Object> params = new HashMap<>();
            if (job.getSharedParams() != null) {
                params.putAll(job.getSharedParams());
            }
            if (itemReq.getParams() != null) {
                params.putAll(itemReq.getParams());
            }
            input.put("params", params);
            inputItems.add(input);
        }

        // 启动状态
        job.setStatus(TaskConstants.BatchStatus.RUNNING);
        job.setStartedAt(LocalDateTime.now());
        batchJobMapper.updateById(job);

        sseService.sendBatchStarted(job);

        // 启动 Pipeline（创建 step 1 items 并发送 MQ）
        pipelineEngine.startPipeline(pipeline, job, inputItems);

        // 刷新总数
        BatchJob refreshed = batchJobMapper.selectById(job.getId());
        log.info("Pipeline 批量作业已启动: id={}, pipelineId={}, totalItems={}",
                job.getId(), pipeline.getId(), refreshed.getTotalItems());

        return BatchJobResponse.fromEntity(refreshed);
    }

    /**
     * Scope 类型批量作业启动流程
     * items 已在事务外通过 ScopeExpansionService 预展开
     */
    private BatchJobResponse createAndStartScope(BatchJob job, CreateBatchJobRequest request,
                                                   List<BatchJobItem> preExpandedItems) {
        List<BatchJobItem> scopeItems = new ArrayList<>(preExpandedItems);

        // 应用条件跳过设置
        String skipCondition = StringUtils.hasText(request.getSkipCondition())
                ? request.getSkipCondition() : TaskConstants.SkipCondition.NONE;
        for (BatchJobItem item : scopeItems) {
            item.setSkipCondition(skipCondition);
        }

        // 如果请求中也有 items，追加到展开结果后面（手动补充）
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            List<BatchJobItem> manualItems = expandItems(job, request);
            scopeItems = new ArrayList<>(scopeItems);
            scopeItems.addAll(manualItems);
        }

        // 编号、入库、启动（复用标准流程）
        return insertItemsAndStart(job, scopeItems);
    }

    /**
     * A/B 对比类型批量作业启动流程
     * 每个实体 × 每个 Provider 生成一个 item
     */
    private BatchJobResponse createAndStartAbTest(BatchJob job, CreateBatchJobRequest request) {
        List<String> providerIds = request.getAbTestProviderIds();
        List<BatchJobItem> items = new ArrayList<>();

        for (CreateBatchJobRequest.BatchJobItemRequest itemReq : request.getItems()) {
            for (int p = 0; p < providerIds.size(); p++) {
                BatchJobItem item = new BatchJobItem();
                item.setEntityType(itemReq.getEntityType());
                item.setEntityId(itemReq.getEntityId());
                item.setEntityName(itemReq.getEntityName());

                // 合并参数
                Map<String, Object> mergedParams = new HashMap<>();
                if (job.getSharedParams() != null) {
                    mergedParams.putAll(job.getSharedParams());
                }
                if (itemReq.getParams() != null) {
                    mergedParams.putAll(itemReq.getParams());
                }
                item.setParams(mergedParams);

                // 每个变体使用不同的 provider
                item.setProviderId(providerIds.get(p));
                item.setGenerationType(StringUtils.hasText(itemReq.getGenerationType())
                        ? itemReq.getGenerationType() : job.getGenerationType());

                item.setSkipCondition(TaskConstants.SkipCondition.NONE);
                item.setSkipped(false);
                item.setVariantIndex(p);  // 用 variantIndex 区分不同 provider
                item.setCreditCost(0L);

                items.add(item);
            }
        }

        return insertItemsAndStart(job, items);
    }

    /**
     * 通用：插入 items 并启动批量作业
     */
    private BatchJobResponse insertItemsAndStart(BatchJob job, List<BatchJobItem> items) {
        int seq = 1;
        for (BatchJobItem item : items) {
            item.setBatchJobId(job.getId());
            item.setSequenceNumber(seq++);
            item.setStatus(TaskConstants.BatchItemStatus.PENDING);
            item.setCreatedAt(LocalDateTime.now());
            item.setUpdatedAt(LocalDateTime.now());
            batchJobItemMapper.insert(item);
        }

        job.setTotalItems(items.size());
        long estimatedCredits = estimateTotalCost(job, items);
        job.setEstimatedCredits(estimatedCredits);
        job.setStatus(TaskConstants.BatchStatus.RUNNING);
        job.setStartedAt(LocalDateTime.now());
        batchJobMapper.updateById(job);

        sendBatchStartMessage(job);
        sseService.sendBatchStarted(job);

        log.info("批量作业已启动: id={}, type={}, totalItems={}, estimatedCredits={}",
                job.getId(), job.getBatchType(), items.size(), estimatedCredits);

        return BatchJobResponse.fromEntity(job);
    }

    /**
     * 获取批量作业详情
     */
    public BatchJobResponse getById(String batchJobId) {
        BatchJob job = batchJobMapper.selectById(batchJobId);
        if (job == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "批量作业不存在");
        }
        return BatchJobResponse.fromEntity(job);
    }

    /**
     * 获取批量作业子项列表
     */
    public List<BatchJobItem> getItems(String batchJobId) {
        BatchJob job = batchJobMapper.selectById(batchJobId);
        if (job == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "批量作业不存在");
        }
        return batchJobItemMapper.selectByBatchJobId(batchJobId);
    }

    /**
     * 取消批量作业
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(String batchJobId, String userId) {
        BatchJob job = batchJobMapper.selectById(batchJobId);
        if (job == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "批量作业不存在");
        }

        String status = job.getStatus();
        if (TaskConstants.BatchStatus.COMPLETED.equals(status)
                || TaskConstants.BatchStatus.CANCELLED.equals(status)
                || TaskConstants.BatchStatus.FAILED.equals(status)) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "批量作业状态不允许取消: " + status);
        }

        // PENDING items → CANCELLED
        batchJobItemMapper.batchUpdateStatus(batchJobId,
                TaskConstants.BatchItemStatus.PENDING, TaskConstants.BatchItemStatus.CANCELLED);

        // 尝试取消 RUNNING tasks（best-effort）
        List<BatchJobItem> runningItems = batchJobItemMapper.selectByBatchJobIdAndStatus(
                batchJobId, TaskConstants.BatchItemStatus.RUNNING);
        for (BatchJobItem item : runningItems) {
            if (item.getTaskId() != null) {
                try {
                    aiGenerationFacade.cancelGeneration(item.getTaskId(), job.getWorkspaceId(), userId);
                } catch (Exception e) {
                    log.warn("取消子任务失败: taskId={}, error={}", item.getTaskId(), e.getMessage());
                }
            }
            item.setStatus(TaskConstants.BatchItemStatus.CANCELLED);
            item.setUpdatedAt(LocalDateTime.now());
            batchJobItemMapper.updateById(item);
        }

        // 更新 batch 状态
        job.setStatus(TaskConstants.BatchStatus.CANCELLED);
        job.setCompletedAt(LocalDateTime.now());
        batchJobMapper.updateById(job);

        concurrencyService.cleanup(batchJobId);
        log.info("批量作业已取消: id={}", batchJobId);

        // 通知 Mission，避免关联 Mission 永久 WAITING
        missionNotifier.notifyMissionIfNeeded(job);
    }

    /**
     * 暂停批量作业
     */
    public void pause(String batchJobId) {
        BatchJob job = batchJobMapper.selectById(batchJobId);
        if (job == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "批量作业不存在");
        }
        if (!TaskConstants.BatchStatus.RUNNING.equals(job.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "只能暂停运行中的批量作业");
        }
        job.setStatus(TaskConstants.BatchStatus.PAUSED);
        batchJobMapper.updateById(job);
        log.info("批量作业已暂停: id={}", batchJobId);
    }

    /**
     * 恢复批量作业
     */
    public void resume(String batchJobId) {
        BatchJob job = batchJobMapper.selectById(batchJobId);
        if (job == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "批量作业不存在");
        }
        if (!TaskConstants.BatchStatus.PAUSED.equals(job.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "只能恢复暂停中的批量作业");
        }
        job.setStatus(TaskConstants.BatchStatus.RUNNING);
        batchJobMapper.updateById(job);

        sendBatchStartMessage(job);
        log.info("批量作业已恢复: id={}", batchJobId);
    }

    /**
     * 重试所有失败项
     */
    @Transactional(rollbackFor = Exception.class)
    public void retryFailed(String batchJobId) {
        BatchJob job = batchJobMapper.selectById(batchJobId);
        if (job == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "批量作业不存在");
        }

        List<BatchJobItem> failedItems = batchJobItemMapper.selectByBatchJobIdAndStatus(
                batchJobId, TaskConstants.BatchItemStatus.FAILED);
        if (failedItems.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "没有失败的子项");
        }

        for (BatchJobItem item : failedItems) {
            item.setStatus(TaskConstants.BatchItemStatus.PENDING);
            item.setErrorMessage(null);
            item.setTaskId(null);
            item.setUpdatedAt(LocalDateTime.now());
            batchJobItemMapper.updateById(item);
        }

        // 重置失败计数
        job.setFailedItems(0);
        job.setStatus(TaskConstants.BatchStatus.RUNNING);
        batchJobMapper.updateById(job);

        sendBatchStartMessage(job);
        log.info("批量作业重试失败项: id={}, retryCount={}", batchJobId, failedItems.size());
    }

    // ==================== 内部方法 ====================

    private void validateRequest(CreateBatchJobRequest request) {
        // SCOPE 类型由系统自动展开，items 可以为空
        boolean isScopeType = TaskConstants.BatchType.SCOPE.equals(request.getBatchType());
        boolean isAbTestType = TaskConstants.BatchType.AB_TEST.equals(request.getBatchType());

        if (!isScopeType) {
            if (request.getItems() == null || request.getItems().isEmpty()) {
                throw new BusinessException(ResultCode.PARAM_INVALID, "子项列表不能为空");
            }
            if (request.getItems().size() > TaskConstants.BatchDefaults.MAX_ITEMS_PER_BATCH) {
                throw new BusinessException(ResultCode.PARAM_INVALID,
                        "单次最多提交 " + TaskConstants.BatchDefaults.MAX_ITEMS_PER_BATCH + " 个子项");
            }
        } else {
            // SCOPE 类型必须指定 scopeEntityType
            if (!StringUtils.hasText(request.getScopeEntityType())) {
                throw new BusinessException(ResultCode.PARAM_INVALID, "SCOPE 类型必须指定 scopeEntityType");
            }
            // EPISODE scope 必须指定 scopeEntityId
            String scopeType = request.getScopeEntityType();
            if (TaskConstants.EntityType.EPISODE.equals(scopeType)) {
                if (!StringUtils.hasText(request.getScopeEntityId())) {
                    throw new BusinessException(ResultCode.PARAM_INVALID, "EPISODE 级别展开必须指定 scopeEntityId");
                }
            }
            // CHARACTER/SCENE/PROP scope 必须指定 scriptId
            if (TaskConstants.EntityType.CHARACTER.equals(scopeType)
                    || TaskConstants.EntityType.SCENE.equals(scopeType)
                    || TaskConstants.EntityType.PROP.equals(scopeType)) {
                if (!StringUtils.hasText(request.getScriptId())) {
                    throw new BusinessException(ResultCode.PARAM_INVALID,
                            scopeType + " 级别展开必须指定 scriptId");
                }
            }
            // SCRIPT scope: 优先使用 scriptId, fallback 到 scopeEntityId
            if (TaskConstants.EntityType.SCRIPT.equals(scopeType)) {
                if (!StringUtils.hasText(request.getScriptId()) && !StringUtils.hasText(request.getScopeEntityId())) {
                    throw new BusinessException(ResultCode.PARAM_INVALID, "SCRIPT 级别展开必须指定 scriptId 或 scopeEntityId");
                }
            }
        }

        // AB_TEST 类型验证
        if (isAbTestType) {
            if (request.getAbTestProviderIds() == null || request.getAbTestProviderIds().size() < 2) {
                throw new BusinessException(ResultCode.PARAM_INVALID, "A/B 对比至少需要 2 个 Provider");
            }
            if (request.getItems() == null || request.getItems().isEmpty()) {
                throw new BusinessException(ResultCode.PARAM_INVALID, "A/B 对比必须指定子项列表");
            }
        }
    }

    private BatchJob buildBatchJob(CreateBatchJobRequest request, String workspaceId, String userId) {
        BatchJob job = new BatchJob();
        job.setWorkspaceId(workspaceId);
        job.setCreatorId(userId);
        job.setName(request.getName());
        job.setDescription(request.getDescription());
        job.setBatchType(StringUtils.hasText(request.getBatchType())
                ? request.getBatchType() : TaskConstants.BatchType.SIMPLE);
        job.setScriptId(request.getScriptId());
        job.setScopeEntityType(request.getScopeEntityType());
        job.setScopeEntityId(request.getScopeEntityId());
        job.setErrorStrategy(StringUtils.hasText(request.getErrorStrategy())
                ? request.getErrorStrategy() : TaskConstants.ErrorStrategy.CONTINUE);
        job.setMaxConcurrency(request.getMaxConcurrency() != null
                ? request.getMaxConcurrency() : TaskConstants.BatchDefaults.MAX_CONCURRENCY);
        job.setPriority(request.getPriority() != null
                ? request.getPriority() : TaskConstants.BatchDefaults.DEFAULT_PRIORITY);
        job.setSharedParams(request.getSharedParams());
        job.setProviderId(request.getProviderId());
        job.setGenerationType(request.getGenerationType());
        job.setStatus(TaskConstants.BatchStatus.CREATED);
        job.setTotalItems(0);
        job.setCompletedItems(0);
        job.setFailedItems(0);
        job.setSkippedItems(0);
        job.setProgress(0);
        job.setEstimatedCredits(0L);
        job.setActualCredits(0L);
        job.setMissionId(request.getMissionId());
        job.setSource(StringUtils.hasText(request.getSource())
                ? request.getSource() : TaskConstants.BatchSource.API);
        // 幂等键由 Mission 委派工具基于稳定输入哈希生成，仅在 missionId 存在时透传。
        if (StringUtils.hasText(request.getIdempotencyKey())) {
            job.setIdempotencyKey(request.getIdempotencyKey());
        }
        return job;
    }

    /**
     * 展开子项（含 Variation 展开）
     */
    private List<BatchJobItem> expandItems(BatchJob job, CreateBatchJobRequest request) {
        List<BatchJobItem> items = new ArrayList<>();

        for (CreateBatchJobRequest.BatchJobItemRequest itemReq : request.getItems()) {
            int variantCount = itemReq.getVariantCount() != null && itemReq.getVariantCount() > 1
                    ? itemReq.getVariantCount() : 1;

            for (int v = 0; v < variantCount; v++) {
                BatchJobItem item = new BatchJobItem();
                item.setEntityType(itemReq.getEntityType());
                item.setEntityId(itemReq.getEntityId());
                item.setEntityName(itemReq.getEntityName());

                // 合并参数：sharedParams + item params（item 优先）
                Map<String, Object> mergedParams = new HashMap<>();
                if (job.getSharedParams() != null) {
                    mergedParams.putAll(job.getSharedParams());
                }
                if (itemReq.getParams() != null) {
                    mergedParams.putAll(itemReq.getParams());
                }
                item.setParams(mergedParams);

                // Provider 和 generationType 取 item 级别，fallback 到 batch 级别
                item.setProviderId(StringUtils.hasText(itemReq.getProviderId())
                        ? itemReq.getProviderId() : job.getProviderId());
                item.setGenerationType(StringUtils.hasText(itemReq.getGenerationType())
                        ? itemReq.getGenerationType() : job.getGenerationType());

                // 条件跳过
                item.setSkipCondition(StringUtils.hasText(itemReq.getSkipCondition())
                        ? itemReq.getSkipCondition() : TaskConstants.SkipCondition.NONE);
                item.setSkipped(false);

                // 变体
                item.setVariantIndex(v);
                if (variantCount > 1) {
                    // 支持固定种子（确定性生成）和随机种子
                    if (itemReq.getSeed() != null) {
                        item.setVariantSeed(itemReq.getSeed() + v);
                    } else {
                        item.setVariantSeed(new Random().nextLong());
                    }
                }

                item.setCreditCost(0L);
                items.add(item);
            }
        }

        return items;
    }

    /**
     * 费用预估
     */
    private long estimateTotalCost(BatchJob job, List<BatchJobItem> items) {
        long total = 0;
        for (BatchJobItem item : items) {
            if (TaskConstants.SkipCondition.NONE.equals(item.getSkipCondition())) {
                try {
                    String providerId = item.getProviderId();
                    if (StringUtils.hasText(providerId)) {
                        Map<String, Object> estimate = aiGenerationFacade.estimateCost(providerId, item.getParams());
                        Object cost = estimate.get("finalCost");
                        if (cost instanceof Number) {
                            total += ((Number) cost).longValue();
                        }
                    }
                } catch (Exception e) {
                    log.debug("费用预估失败，跳过: itemEntityId={}", item.getEntityId());
                }
            }
        }
        return total;
    }

    private void sendBatchStartMessage(BatchJob job) {
        Map<String, Object> payload = Map.of(
                "batchJobId", job.getId(),
                "workspaceId", job.getWorkspaceId()
        );
        MessageWrapper<Map<String, Object>> message = MessageWrapper.<Map<String, Object>>builder()
                .messageId(UuidGenerator.generateUuidV7())
                .messageType(MqConstants.BatchJob.MSG_START)
                .payload(payload)
                .workspaceId(job.getWorkspaceId())
                .senderId(job.getCreatorId())
                .build();
        messageProducer.sendDirect(MqConstants.BatchJob.ROUTING_START, message);
    }
}
