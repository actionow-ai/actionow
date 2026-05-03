package com.actionow.canvas.service;

import com.actionow.canvas.dto.feign.EntityInfo;
import com.actionow.canvas.feign.ProjectFeignClient;
import com.actionow.common.core.result.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 2.2 测试：批量 Feign 调用
 */
@ExtendWith(MockitoExtension.class)
class EntityCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ProjectFeignClient projectFeignClient;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private EntityCacheService entityCacheService;

    @Test
    void testBatchGetEntities_shouldCallFeignOnce() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.multiGet(anyList())).thenReturn(List.of(null, null, null));

        EntityInfo entity1 = new EntityInfo();
        entity1.setId("1");
        EntityInfo entity2 = new EntityInfo();
        entity2.setId("2");

        when(projectFeignClient.batchGetScripts(anyList()))
            .thenReturn(Result.success(List.of(entity1, entity2)));

        Map<String, EntityInfo> result = entityCacheService.getEntities("SCRIPT", List.of("1", "2"));

        assertEquals(2, result.size());
        verify(projectFeignClient, times(1)).batchGetScripts(anyList());
    }

    @Test
    void evictCache_shouldDeleteRedisKey() {
        entityCacheService.evictCache("ASSET", "asset-123");
        verify(redisTemplate, times(1)).delete("canvas:entity:asset:asset-123");
    }

    @Test
    void cacheEntityFromPayload_shouldWriteFullPayloadIntoCache() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(objectMapper.writeValueAsString(any(EntityInfo.class))).thenReturn("{}");

        Map<String, Object> payload = Map.of(
            "id", "asset-1",
            "name", "图1",
            "fileUrl", "https://cdn.example.com/x.png",
            "fileKey", "tenant/x.png",
            "mimeType", "image/png",
            "generationStatus", "COMPLETED"
        );

        entityCacheService.cacheEntityFromPayload("ASSET", "asset-1", payload);

        // 验证 EntityInfo 被序列化（writeValueAsString 接收带 detail 的 EntityInfo）
        org.mockito.ArgumentCaptor<EntityInfo> captor = org.mockito.ArgumentCaptor.forClass(EntityInfo.class);
        verify(objectMapper).writeValueAsString(captor.capture());
        EntityInfo cached = captor.getValue();

        assertEquals("asset-1", cached.getId());
        assertEquals("图1", cached.getName());
        // ASSET 没 coverUrl 字段，应回退到 fileUrl 作为缩略图候选
        assertEquals("https://cdn.example.com/x.png", cached.getCoverUrl());
        // 完整 payload 必须落入 detail map（前端通过 toEntityDetailMap 拿到 fileUrl/fileKey/...）
        assertEquals("https://cdn.example.com/x.png", cached.getDetail().get("fileUrl"));
        assertEquals("tenant/x.png", cached.getDetail().get("fileKey"));
        assertEquals("COMPLETED", cached.getDetail().get("generationStatus"));
    }

    @Test
    void cacheEntityFromPayload_shouldNoOpOnNullPayload() {
        entityCacheService.cacheEntityFromPayload("ASSET", "x", null);
        entityCacheService.cacheEntityFromPayload("ASSET", "x", Map.of());
        verifyNoInteractions(objectMapper);
    }
}
