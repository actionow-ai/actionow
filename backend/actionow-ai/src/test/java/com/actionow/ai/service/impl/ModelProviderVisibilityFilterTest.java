package com.actionow.ai.service.impl;

import com.actionow.ai.entity.ModelProvider;
import com.actionow.ai.mapper.ProviderWorkspaceWhitelistMapper;
import com.actionow.common.core.result.Result;
import com.actionow.common.security.workspace.WorkspaceInternalClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;

/**
 * ModelProviderServiceImpl#filterByVisibility 单元测试。
 *
 * <p>不启动 Spring 上下文；仅 mock {@link ProviderWorkspaceWhitelistMapper} 与
 * {@link WorkspaceInternalClient}，其他依赖为 null（filterByVisibility 不会用到）。
 */
class ModelProviderVisibilityFilterTest {

    private static final String WS = "ws-123";

    private ProviderWorkspaceWhitelistMapper whitelistMapper;
    private WorkspaceInternalClient workspaceInternalClient;
    private ModelProviderServiceImpl service;

    @BeforeEach
    void setUp() {
        whitelistMapper = Mockito.mock(ProviderWorkspaceWhitelistMapper.class);
        workspaceInternalClient = Mockito.mock(WorkspaceInternalClient.class);
        service = new ModelProviderServiceImpl(
                null, null, null, null, null, null, null, null,
                whitelistMapper, workspaceInternalClient, null);
    }

    private ModelProvider provider(String id, String visibility) {
        ModelProvider p = new ModelProvider();
        p.setId(id);
        p.setVisibility(visibility);
        return p;
    }

    @Nested
    @DisplayName("PUBLIC visibility")
    class PublicTests {

        @Test
        @DisplayName("PUBLIC 在任何 workspace 都可见")
        void publicAlwaysVisible() {
            List<ModelProvider> result = service.filterByVisibility(
                    List.of(provider("p1", "PUBLIC")), WS);
            assertEquals(1, result.size());
            assertEquals("p1", result.get(0).getId());
        }

        @Test
        @DisplayName("visibility=null 视为 PUBLIC（向前兼容）")
        void nullVisibilityTreatedAsPublic() {
            List<ModelProvider> result = service.filterByVisibility(
                    List.of(provider("p1", null)), WS);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("workspaceId=null 时仍可见 PUBLIC")
        void publicVisibleWithNullWorkspace() {
            List<ModelProvider> result = service.filterByVisibility(
                    List.of(provider("p1", "PUBLIC")), null);
            assertEquals(1, result.size());
        }
    }

    @Nested
    @DisplayName("INTERNAL visibility")
    class InternalTests {

        @Test
        @DisplayName("workspace.is_internal=true 时可见 INTERNAL")
        void internalVisibleForInternalWorkspace() {
            Mockito.when(workspaceInternalClient.isInternal(WS))
                    .thenReturn(Result.success(Boolean.TRUE));

            List<ModelProvider> result = service.filterByVisibility(
                    List.of(provider("p1", "INTERNAL")), WS);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("workspace.is_internal=false 时隐藏 INTERNAL")
        void internalHiddenForNormalWorkspace() {
            Mockito.when(workspaceInternalClient.isInternal(WS))
                    .thenReturn(Result.success(Boolean.FALSE));

            List<ModelProvider> result = service.filterByVisibility(
                    List.of(provider("p1", "INTERNAL")), WS);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("workspaceId=null 时隐藏 INTERNAL")
        void internalHiddenWithoutWorkspace() {
            List<ModelProvider> result = service.filterByVisibility(
                    List.of(provider("p1", "INTERNAL")), null);
            assertTrue(result.isEmpty());
            // 应短路：不调用 workspaceInternalClient
            Mockito.verifyNoInteractions(workspaceInternalClient);
        }

        @Test
        @DisplayName("workspaceInternalClient 抛异常时按非内部处理（隐藏 INTERNAL）")
        void internalHiddenWhenClientThrows() {
            Mockito.when(workspaceInternalClient.isInternal(WS))
                    .thenThrow(new RuntimeException("network error"));

            List<ModelProvider> result = service.filterByVisibility(
                    List.of(provider("p1", "INTERNAL")), WS);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Result 失败时按非内部处理")
        void internalHiddenWhenResultFails() {
            Mockito.when(workspaceInternalClient.isInternal(WS))
                    .thenReturn(Result.fail("downstream error"));

            List<ModelProvider> result = service.filterByVisibility(
                    List.of(provider("p1", "INTERNAL")), WS);
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("WHITELIST visibility")
    class WhitelistTests {

        @Test
        @DisplayName("workspace 在白名单内时可见 WHITELIST")
        void whitelistedVisible() {
            Mockito.when(whitelistMapper.listProviderIdsByWorkspace(eq(WS)))
                    .thenReturn(List.of("p1", "px"));

            List<ModelProvider> result = service.filterByVisibility(
                    List.of(provider("p1", "WHITELIST")), WS);
            assertEquals(1, result.size());
            assertEquals("p1", result.get(0).getId());
        }

        @Test
        @DisplayName("workspace 不在白名单内时隐藏 WHITELIST")
        void notWhitelistedHidden() {
            Mockito.when(whitelistMapper.listProviderIdsByWorkspace(eq(WS)))
                    .thenReturn(List.of("px"));

            List<ModelProvider> result = service.filterByVisibility(
                    List.of(provider("p1", "WHITELIST")), WS);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("workspaceId=null 时隐藏 WHITELIST")
        void whitelistHiddenWithoutWorkspace() {
            List<ModelProvider> result = service.filterByVisibility(
                    List.of(provider("p1", "WHITELIST")), null);
            assertTrue(result.isEmpty());
            Mockito.verifyNoInteractions(whitelistMapper);
        }

        @Test
        @DisplayName("白名单查询抛异常时按空白名单处理")
        void whitelistHiddenWhenMapperThrows() {
            Mockito.when(whitelistMapper.listProviderIdsByWorkspace(eq(WS)))
                    .thenThrow(new RuntimeException("db error"));

            List<ModelProvider> result = service.filterByVisibility(
                    List.of(provider("p1", "WHITELIST")), WS);
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("混合 visibility 场景")
    class MixedTests {

        @Test
        @DisplayName("PUBLIC + INTERNAL + WHITELIST 同时存在，按各自规则过滤")
        void mixedFilteredCorrectly() {
            Mockito.when(workspaceInternalClient.isInternal(WS))
                    .thenReturn(Result.success(Boolean.TRUE));
            Mockito.when(whitelistMapper.listProviderIdsByWorkspace(eq(WS)))
                    .thenReturn(List.of("p3"));

            List<ModelProvider> all = List.of(
                    provider("p1", "PUBLIC"),
                    provider("p2", "INTERNAL"),
                    provider("p3", "WHITELIST"),
                    provider("p4", "WHITELIST"));   // 不在白名单
            List<ModelProvider> result = service.filterByVisibility(all, WS);

            Set<String> ids = result.stream().map(ModelProvider::getId)
                    .collect(java.util.stream.Collectors.toSet());
            assertEquals(Set.of("p1", "p2", "p3"), ids);
        }

        @Test
        @DisplayName("非内部 workspace 看到 PUBLIC,看不到 INTERNAL,但白名单内的 WHITELIST 仍可见")
        void normalWorkspaceMixed() {
            Mockito.when(workspaceInternalClient.isInternal(WS))
                    .thenReturn(Result.success(Boolean.FALSE));
            Mockito.when(whitelistMapper.listProviderIdsByWorkspace(eq(WS)))
                    .thenReturn(List.of("p3"));

            List<ModelProvider> all = List.of(
                    provider("p1", "PUBLIC"),
                    provider("p2", "INTERNAL"),
                    provider("p3", "WHITELIST"));
            List<ModelProvider> result = service.filterByVisibility(all, WS);

            Set<String> ids = result.stream().map(ModelProvider::getId)
                    .collect(java.util.stream.Collectors.toSet());
            assertEquals(Set.of("p1", "p3"), ids);
        }

        @Test
        @DisplayName("空输入返回空")
        void emptyInputReturnsEmpty() {
            List<ModelProvider> result = service.filterByVisibility(List.of(), WS);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("仅有 PUBLIC 时不查 workspace.is_internal 也不查白名单（性能）")
        void allPublicSkipsLookups() {
            List<ModelProvider> result = service.filterByVisibility(
                    List.of(provider("p1", "PUBLIC"), provider("p2", "PUBLIC")), WS);
            assertEquals(2, result.size());
            Mockito.verifyNoInteractions(workspaceInternalClient);
            Mockito.verifyNoInteractions(whitelistMapper);
        }
    }
}
