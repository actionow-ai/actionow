package com.actionow.canvas.service;

import com.actionow.canvas.entity.CanvasNode;
import com.actionow.canvas.feign.ProjectFeignClient;
import com.actionow.canvas.mapper.CanvasNodeMapper;
import com.actionow.common.core.result.Result;
import com.actionow.common.core.context.UserContext;
import com.actionow.common.core.context.UserContextHolder;
import com.actionow.canvas.dto.feign.EntityInfo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 启动补偿服务
 * Canvas 服务启动时，主动同步最近变更的实体信息
 * 防止因服务不可用期间丢失的 MQ 消息导致数据不一致
 *
 * 注意：此服务需要正确的租户上下文才能工作。
 * 默认禁用，需要在配置中启用并指定租户。
 *
 * @author Actionow
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StartupCompensationService {

    private final CanvasNodeMapper canvasNodeMapper;
    private final ProjectFeignClient projectFeignClient;

    /**
     * 是否启用启动补偿
     * 默认禁用，因为需要租户上下文
     */
    @Value("${canvas.startup-compensation.enabled:false}")
    private boolean enabled;

    /**
     * 补偿时指定的租户Schema
     * 如果为空则跳过补偿
     */
    @Value("${canvas.startup-compensation.tenant-schema:}")
    private String tenantSchema;

    /**
     * 补偿时间窗口（分钟）
     * 同步服务启动前30分钟内变更的实体
     */
    private static final int COMPENSATION_WINDOW_MINUTES = 30;

    /**
     * 批量查询大小
     */
    private static final int BATCH_SIZE = 100;

    /**
     * 应用启动后执行补偿
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            log.info("Canvas 启动补偿已禁用，跳过数据同步");
            return;
        }

        if (tenantSchema == null || tenantSchema.isBlank()) {
            log.warn("Canvas 启动补偿已启用但未指定租户Schema (canvas.startup-compensation.tenant-schema)，跳过数据同步");
            return;
        }

        log.info("Canvas 服务启动，开始执行数据补偿... tenantSchema={}", tenantSchema);
        try {
            // 设置租户上下文
            UserContext context = new UserContext();
            context.setTenantSchema(tenantSchema);
            UserContextHolder.setContext(context);

            syncRecentChanges(Duration.ofMinutes(COMPENSATION_WINDOW_MINUTES));
            log.info("Canvas 数据补偿完成");
        } catch (Exception e) {
            log.error("Canvas 数据补偿失败: {}", e.getMessage(), e);
        } finally {
            UserContextHolder.clear();
        }
    }

    /**
     * 同步最近变更的实体
     *
     * @param duration 时间范围
     */
    public void syncRecentChanges(Duration duration) {
        LocalDateTime since = LocalDateTime.now().minus(duration);
        log.info("开始同步 {} 之后变更的实体", since);

        // 查询最近更新的节点
        LambdaQueryWrapper<CanvasNode> query = new LambdaQueryWrapper<>();
        query.eq(CanvasNode::getDeleted, 0)
                .isNotNull(CanvasNode::getEntityId)
                .ge(CanvasNode::getUpdatedAt, since);

        List<CanvasNode> recentNodes = canvasNodeMapper.selectList(query);

        if (CollectionUtils.isEmpty(recentNodes)) {
            log.info("没有需要补偿的节点");
            return;
        }

        log.info("发现 {} 个需要补偿的节点", recentNodes.size());

        // 按实体类型分组
        Map<String, List<CanvasNode>> nodesByType = recentNodes.stream()
                .filter(n -> n.getEntityType() != null && n.getEntityId() != null)
                .collect(Collectors.groupingBy(CanvasNode::getEntityType));

        // 分类型批量查询并更新
        for (Map.Entry<String, List<CanvasNode>> entry : nodesByType.entrySet()) {
            String entityType = entry.getKey();
            List<CanvasNode> nodes = entry.getValue();

            syncNodesByType(entityType, nodes);
        }
    }

    /**
     * 按类型同步节点
     */
    private void syncNodesByType(String entityType, List<CanvasNode> nodes) {
        List<String> entityIds = nodes.stream()
                .map(CanvasNode::getEntityId)
                .distinct()
                .toList();

        log.info("同步 {} 类型的 {} 个实体", entityType, entityIds.size());

        // 分批查询
        for (int i = 0; i < entityIds.size(); i += BATCH_SIZE) {
            List<String> batchIds = entityIds.subList(i, Math.min(i + BATCH_SIZE, entityIds.size()));
            syncBatch(entityType, batchIds, nodes);
        }
    }

    /**
     * 同步一批实体
     */
    private void syncBatch(String entityType, List<String> entityIds, List<CanvasNode> allNodes) {
        try {
            Result<List<EntityInfo>> result = fetchEntities(entityType, entityIds);

            if (result == null || !result.isSuccess() || result.getData() == null) {
                log.warn("获取 {} 类型实体失败: {}", entityType,
                        result != null ? result.getMessage() : "null response");
                return;
            }

            // 构建 entityId -> EntityInfo 映射
            Map<String, EntityInfo> entityMap = result.getData().stream()
                    .collect(Collectors.toMap(EntityInfo::getId, e -> e, (a, b) -> b));

            // 更新节点缓存信息
            int updatedCount = 0;
            for (CanvasNode node : allNodes) {
                if (!entityIds.contains(node.getEntityId())) {
                    continue;
                }

                EntityInfo entity = entityMap.get(node.getEntityId());
                if (entity == null) {
                    // 实体已删除，标记节点为删除
                    log.debug("实体已删除，标记节点删除: nodeId={}, entityId={}",
                            node.getId(), node.getEntityId());
                    node.setDeleted(1);
                    canvasNodeMapper.updateById(node);
                    updatedCount++;
                    continue;
                }

                // 缓存字段已删除，无需更新
            }

            log.debug("同步完成: entityType={}, checked={}, updated={}",
                    entityType, entityIds.size(), updatedCount);

        } catch (Exception e) {
            log.error("同步 {} 类型实体失败: {}", entityType, e.getMessage());
        }
    }

    /**
     * 根据类型调用对应的批量查询接口
     */
    private Result<List<EntityInfo>> fetchEntities(String entityType, List<String> ids) {
        return switch (entityType.toUpperCase()) {
            case "SCRIPT" -> projectFeignClient.batchGetScripts(ids);
            case "EPISODE" -> projectFeignClient.batchGetEpisodes(ids);
            case "STORYBOARD" -> projectFeignClient.batchGetStoryboards(ids);
            case "CHARACTER" -> projectFeignClient.batchGetCharacters(ids);
            case "SCENE" -> projectFeignClient.batchGetScenes(ids);
            case "PROP" -> projectFeignClient.batchGetProps(ids);
            case "ASSET" -> projectFeignClient.batchGetAssets(ids);
            default -> {
                log.warn("未知的实体类型: {}", entityType);
                yield Result.success(Collections.emptyList());
            }
        };
    }
}
