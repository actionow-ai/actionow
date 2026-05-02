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
}
