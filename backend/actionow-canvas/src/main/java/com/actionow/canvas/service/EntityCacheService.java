package com.actionow.canvas.service;

import com.actionow.canvas.dto.feign.EntityInfo;
import com.actionow.canvas.feign.ProjectFeignClient;
import com.actionow.common.core.result.Result;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 实体信息缓存服务
 * 提供读写分离的实体信息缓存，减少对 Project 服务的调用
 *
 * @author Actionow
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntityCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ProjectFeignClient projectFeignClient;
    private final ObjectMapper objectMapper;

    /**
     * 缓存 Key 前缀
     */
    private static final String CACHE_KEY_PREFIX = "canvas:entity:";

    /**
     * 缓存过期时间（分钟）
     */
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    /**
     * 获取实体信息（优先从缓存读取）
     *
     * @param entityType 实体类型
     * @param entityId   实体ID
     * @return 实体信息
     */
    public EntityInfo getEntity(String entityType, String entityId) {
        String cacheKey = buildCacheKey(entityType, entityId);

        // 尝试从缓存获取
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.hasText(cached)) {
            try {
                return objectMapper.readValue(cached, EntityInfo.class);
            } catch (JsonProcessingException e) {
                log.warn("反序列化缓存数据失败: key={}, error={}", cacheKey, e.getMessage());
            }
        }

        // 缓存未命中，从 Project 服务获取
        EntityInfo entity = fetchFromProject(entityType, entityId);
        if (entity != null) {
            cacheEntity(entityType, entityId, entity);
        }

        return entity;
    }

    /**
     * 批量获取实体信息（优先从缓存读取）
     *
     * @param entityType 实体类型
     * @param entityIds  实体ID列表
     * @return 实体信息映射
     */
    public Map<String, EntityInfo> getEntities(String entityType, List<String> entityIds) {
        if (CollectionUtils.isEmpty(entityIds)) {
            return Collections.emptyMap();
        }

        Map<String, EntityInfo> result = new HashMap<>();
        List<String> missedIds = new ArrayList<>();

        // 批量从缓存获取
        List<String> cacheKeys = entityIds.stream()
                .map(id -> buildCacheKey(entityType, id))
                .toList();

        List<String> cachedValues = redisTemplate.opsForValue().multiGet(cacheKeys);

        if (cachedValues != null) {
            for (int i = 0; i < entityIds.size(); i++) {
                String entityId = entityIds.get(i);
                String cached = cachedValues.get(i);

                if (StringUtils.hasText(cached)) {
                    try {
                        EntityInfo entity = objectMapper.readValue(cached, EntityInfo.class);
                        result.put(entityId, entity);
                    } catch (JsonProcessingException e) {
                        missedIds.add(entityId);
                    }
                } else {
                    missedIds.add(entityId);
                }
            }
        } else {
            missedIds.addAll(entityIds);
        }

        // 批量获取缓存未命中的实体
        if (!missedIds.isEmpty()) {
            Map<String, EntityInfo> fetched = batchFetchFromProject(entityType, missedIds);
            result.putAll(fetched);

            // 缓存获取到的实体
            fetched.forEach((id, entity) -> cacheEntity(entityType, id, entity));
        }

        return result;
    }

    /**
     * 缓存实体信息
     *
     * @param entityType 实体类型
     * @param entityId   实体ID
     * @param entity     实体信息
     */
    public void cacheEntity(String entityType, String entityId, EntityInfo entity) {
        String cacheKey = buildCacheKey(entityType, entityId);
        try {
            String json = objectMapper.writeValueAsString(entity);
            redisTemplate.opsForValue().set(cacheKey, json, CACHE_TTL);
        } catch (JsonProcessingException e) {
            log.warn("序列化实体数据失败: entityType={}, entityId={}, error={}",
                    entityType, entityId, e.getMessage());
        }
    }

    /**
     * 更新缓存（MQ 消息触发）
     *
     * @param entityType   实体类型
     * @param entityId     实体ID
     * @param name         实体名称
     * @param thumbnailUrl 缩略图URL
     */
    public void updateCache(String entityType, String entityId, String name, String thumbnailUrl) {
        String cacheKey = buildCacheKey(entityType, entityId);

        // 获取现有缓存
        String cached = redisTemplate.opsForValue().get(cacheKey);
        EntityInfo entity;

        if (StringUtils.hasText(cached)) {
            try {
                entity = objectMapper.readValue(cached, EntityInfo.class);
            } catch (JsonProcessingException e) {
                entity = new EntityInfo();
                entity.setId(entityId);
                entity.setEntityType(entityType);
            }
        } else {
            entity = new EntityInfo();
            entity.setId(entityId);
            entity.setEntityType(entityType);
        }

        // 更新字段
        if (name != null) {
            entity.setName(name);
        }
        if (thumbnailUrl != null) {
            entity.setThumbnailUrl(thumbnailUrl);
        }

        // 写回缓存
        cacheEntity(entityType, entityId, entity);
    }

    /**
     * 失效缓存（实体删除时调用）
     *
     * @param entityType 实体类型
     * @param entityId   实体ID
     */
    public void evictCache(String entityType, String entityId) {
        String cacheKey = buildCacheKey(entityType, entityId);
        redisTemplate.delete(cacheKey);
        log.debug("缓存已失效: entityType={}, entityId={}", entityType, entityId);
    }

    /**
     * 批量失效缓存
     *
     * @param entityType 实体类型
     * @param entityIds  实体ID列表
     */
    public void evictCaches(String entityType, List<String> entityIds) {
        if (CollectionUtils.isEmpty(entityIds)) {
            return;
        }
        List<String> cacheKeys = entityIds.stream()
                .map(id -> buildCacheKey(entityType, id))
                .toList();
        redisTemplate.delete(cacheKeys);
        log.debug("批量缓存已失效: entityType={}, count={}", entityType, entityIds.size());
    }

    /**
     * 构建缓存 Key
     */
    private String buildCacheKey(String entityType, String entityId) {
        return CACHE_KEY_PREFIX + entityType.toLowerCase() + ":" + entityId;
    }

    /**
     * 从 Project 服务获取单个实体
     */
    private EntityInfo fetchFromProject(String entityType, String entityId) {
        Result<List<EntityInfo>> result = batchFetch(entityType, List.of(entityId));
        if (result != null && result.isSuccess() && !CollectionUtils.isEmpty(result.getData())) {
            return result.getData().get(0);
        }
        return null;
    }

    /**
     * 从 Project 服务批量获取实体
     */
    private Map<String, EntityInfo> batchFetchFromProject(String entityType, List<String> entityIds) {
        Result<List<EntityInfo>> result = batchFetch(entityType, entityIds);
        if (result != null && result.isSuccess() && !CollectionUtils.isEmpty(result.getData())) {
            return result.getData().stream()
                    .collect(Collectors.toMap(EntityInfo::getId, e -> e, (a, b) -> b));
        }
        return Collections.emptyMap();
    }

    /**
     * 调用 Project Feign 客户端批量获取
     */
    private Result<List<EntityInfo>> batchFetch(String entityType, List<String> ids) {
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
