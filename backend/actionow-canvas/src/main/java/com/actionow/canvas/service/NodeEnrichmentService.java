package com.actionow.canvas.service;

import com.actionow.canvas.constant.CanvasConstants;
import com.actionow.canvas.dto.feign.EntityAssetRelationResponse;
import com.actionow.canvas.dto.feign.EntityInfo;
import com.actionow.canvas.dto.feign.EntityRef;
import com.actionow.canvas.dto.node.CanvasNodeResponse;
import com.actionow.canvas.feign.ProjectFeignClient;
import com.actionow.common.core.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 节点增强服务
 * 给 CanvasNodeResponse 列表填充 entityDetail（实体快照）和 relatedAssets（关联素材）
 *
 * @author Actionow
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NodeEnrichmentService {

    private static final int MAX_RELATED_ASSETS = 12;

    private final ProjectFeignClient projectFeignClient;

    /**
     * 同时填充 entityDetail 和 relatedAssets
     */
    public void enrich(List<CanvasNodeResponse> nodeResponses) {
        if (nodeResponses == null || nodeResponses.isEmpty()) {
            return;
        }
        enrichEntityDetail(nodeResponses);
        enrichRelatedAssets(nodeResponses);
    }

    /**
     * 仅填充 entityDetail（业务实体快照：name/coverUrl/status 等）
     */
    public void enrichEntityDetail(List<CanvasNodeResponse> nodeResponses) {
        if (nodeResponses == null || nodeResponses.isEmpty()) {
            return;
        }

        Map<String, List<String>> idsByType = new HashMap<>();
        for (CanvasNodeResponse node : nodeResponses) {
            if (node.getEntityType() == null || node.getEntityId() == null) {
                continue; // GROUP 节点跳过
            }
            idsByType.computeIfAbsent(node.getEntityType(), k -> new ArrayList<>())
                    .add(node.getEntityId());
        }

        Map<String, EntityInfo> entityInfoMap = new ConcurrentHashMap<>();
        idsByType.entrySet().parallelStream().forEach(entry -> {
            String entityType = entry.getKey();
            List<String> ids = entry.getValue();
            try {
                List<EntityInfo> infos = fetchEntitiesByType(entityType, ids);
                if (infos != null) {
                    for (EntityInfo info : infos) {
                        entityInfoMap.put(entityType + ":" + info.getId(), info);
                    }
                }
            } catch (Exception e) {
                log.warn("批量获取实体信息失败: entityType={}, error={}", entityType, e.getMessage());
            }
        });

        for (CanvasNodeResponse node : nodeResponses) {
            if (node.getEntityId() == null) continue;
            EntityInfo info = entityInfoMap.get(node.getEntityType() + ":" + node.getEntityId());
            if (info != null) {
                node.setEntityDetail(info.toEntityDetailMap());
            }
        }
    }

    /**
     * 填充 entityDetail.relatedAssets（关联素材列表）
     * - 跳过 GROUP 节点（entityId 为空）
     * - 跳过 ASSET 节点（避免素材关联素材的递归）
     * - 每个实体限 {@link #MAX_RELATED_ASSETS} 个素材，超过的等前端 hover 时再实时查
     *
     * 实施：单次 batch Feign 调用，O(N+1) 改为 O(1)
     */
    public void enrichRelatedAssets(List<CanvasNodeResponse> nodeResponses) {
        if (nodeResponses == null || nodeResponses.isEmpty()) {
            return;
        }

        // 收集需要查询的 (entityType, entityId) 引用
        List<EntityRef> refs = nodeResponses.stream()
                .filter(n -> n.getEntityId() != null) // 跳过 GROUP
                .filter(n -> {
                    String type = CanvasConstants.EntityType.normalize(n.getEntityType());
                    return !CanvasConstants.EntityType.ASSET.equals(type); // 跳过 ASSET
                })
                .map(n -> new EntityRef(n.getEntityType(), n.getEntityId()))
                .collect(Collectors.toList());

        if (refs.isEmpty()) return;

        Map<String, List<EntityAssetRelationResponse>> assetsByEntity;
        try {
            Result<Map<String, List<EntityAssetRelationResponse>>> result =
                    projectFeignClient.batchGetRelatedAssets(refs);
            if (result == null || !result.isSuccess() || result.getData() == null) return;
            assetsByEntity = result.getData();
        } catch (Exception e) {
            log.warn("批量获取关联素材失败: count={}, error={}", refs.size(), e.getMessage());
            return;
        }

        for (CanvasNodeResponse node : nodeResponses) {
            if (node.getEntityId() == null) continue;
            String key = node.getEntityType() + ":" + node.getEntityId();
            List<EntityAssetRelationResponse> relations = assetsByEntity.get(key);
            if (relations == null || relations.isEmpty()) continue;

            int totalCount = relations.size();
            List<Map<String, Object>> assets = relations.stream()
                    .limit(MAX_RELATED_ASSETS)
                    .map(rel -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("assetId", rel.getAssetId());
                        m.put("relationType", rel.getRelationType());
                        // 从 rel.asset (AssetResponse 透传) 取渲染字段
                        Map<String, Object> assetSnap = rel.getAsset();
                        if (assetSnap != null) {
                            m.put("name", assetSnap.get("name"));
                            m.put("coverUrl", assetSnap.get("coverUrl"));
                            m.put("status", assetSnap.get("status"));
                        }
                        return m;
                    })
                    .collect(Collectors.toList());

            if (node.getEntityDetail() == null) {
                node.setEntityDetail(new HashMap<>());
            }
            node.getEntityDetail().put("relatedAssets", assets);
            node.getEntityDetail().put("relatedAssetCount", totalCount);
        }
    }

    private List<EntityInfo> fetchEntitiesByType(String entityType, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        String normalizedType = CanvasConstants.EntityType.normalize(entityType);

        Result<List<EntityInfo>> result = switch (normalizedType) {
            case CanvasConstants.EntityType.SCRIPT -> projectFeignClient.batchGetScripts(ids);
            case CanvasConstants.EntityType.EPISODE -> projectFeignClient.batchGetEpisodes(ids);
            case CanvasConstants.EntityType.STORYBOARD -> projectFeignClient.batchGetStoryboards(ids);
            case CanvasConstants.EntityType.CHARACTER -> projectFeignClient.batchGetCharacters(ids);
            case CanvasConstants.EntityType.SCENE -> projectFeignClient.batchGetScenes(ids);
            case CanvasConstants.EntityType.PROP -> projectFeignClient.batchGetProps(ids);
            case CanvasConstants.EntityType.ASSET -> projectFeignClient.batchGetAssets(ids);
            default -> null;
        };

        if (result != null && result.isSuccess() && result.getData() != null) {
            return result.getData();
        }
        return Collections.emptyList();
    }
}
