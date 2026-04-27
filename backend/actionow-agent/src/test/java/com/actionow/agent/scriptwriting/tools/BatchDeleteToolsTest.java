package com.actionow.agent.scriptwriting.tools;

import com.actionow.agent.feign.ProjectFeignClient;
import com.actionow.agent.interaction.HitlConfirmationHelper;
import com.actionow.agent.interaction.HitlConfirmationHelper.ConfirmationResult;
import com.actionow.common.core.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * batch_delete_* 工具回归测试。
 *
 * <p>用 CharacterTools 作为代表（所有 7 个 batch_delete_* 共用 AbstractProjectTool.executeBatchDelete
 * 模板，行为对称），分别覆盖 HITL Confirmed / Declined / TimedOut / NoSession / 空 ID /
 * 部分失败场景。其它实体的 batch_delete_* 仅 entityName + Feign 方法引用不同，无需重复测试。
 */
@ExtendWith(MockitoExtension.class)
class BatchDeleteToolsTest {

    @Mock
    private ProjectFeignClient projectClient;

    @Mock
    private HitlConfirmationHelper hitl;

    private CharacterTools tools;

    @BeforeEach
    void setUp() {
        tools = new CharacterTools(projectClient, hitl);
    }

    // ---------------------- HITL Confirmed ----------------------

    @Test
    void confirmed_callsFeign_andReturnsDeletedList() {
        when(hitl.confirmDestructiveAction(anyString(), anyInt()))
                .thenReturn(new ConfirmationResult.Confirmed("ask-1"));
        when(projectClient.deleteCharacter("c1")).thenReturn(Result.success(null));
        when(projectClient.deleteCharacter("c2")).thenReturn(Result.success(null));

        Map<String, Object> result = tools.batchDeleteCharacters("[\"c1\",\"c2\"]");

        assertTrue((Boolean) result.get("success"));
        @SuppressWarnings("unchecked")
        List<String> deleted = (List<String>) result.get("deleted");
        assertEquals(List.of("c1", "c2"), deleted);
        @SuppressWarnings("unchecked")
        List<?> failed = (List<?>) result.get("failed");
        assertTrue(failed.isEmpty());
        assertFalse((Boolean) result.get("cancelled"));
        verify(projectClient, times(2)).deleteCharacter(anyString());
    }

    // ---------------------- HITL Declined ----------------------

    @Test
    void declined_doesNotCallFeign_andReturnsCancelled() {
        when(hitl.confirmDestructiveAction(anyString(), anyInt()))
                .thenReturn(new ConfirmationResult.Declined("ask-2", "USER_DECLINED"));

        Map<String, Object> result = tools.batchDeleteCharacters("[\"c1\"]");

        assertTrue((Boolean) result.get("success"));
        assertTrue((Boolean) result.get("cancelled"));
        assertEquals("USER_DECLINED", result.get("reason"));
        assertEquals("ask-2", result.get("askId"));
        verify(projectClient, never()).deleteCharacter(anyString());
    }

    // ---------------------- HITL TimedOut ----------------------

    @Test
    void timedOut_doesNotCallFeign_andMarksTimeout() {
        when(hitl.confirmDestructiveAction(anyString(), anyInt()))
                .thenReturn(new ConfirmationResult.TimedOut("ask-3"));

        Map<String, Object> result = tools.batchDeleteCharacters("[\"c1\",\"c2\"]");

        assertTrue((Boolean) result.get("cancelled"));
        assertEquals("TIMEOUT", result.get("reason"));
        assertEquals("ask-3", result.get("askId"));
        verify(projectClient, never()).deleteCharacter(anyString());
    }

    // ---------------------- HITL NoSession ----------------------

    @Test
    void noSession_returnsErrorWithoutCallingFeign() {
        when(hitl.confirmDestructiveAction(anyString(), anyInt()))
                .thenReturn(new ConfirmationResult.NoSession());

        Map<String, Object> result = tools.batchDeleteCharacters("[\"c1\"]");

        assertFalse((Boolean) result.get("success"));
        assertNotNull(result.get("message"));
        verify(projectClient, never()).deleteCharacter(anyString());
    }

    // ---------------------- 输入校验 ----------------------

    @Test
    void blankIdsJson_returnsValidationErrorBeforeHitl() {
        Map<String, Object> result = tools.batchDeleteCharacters("");

        assertFalse((Boolean) result.get("success"));
        verify(hitl, never()).confirmDestructiveAction(anyString(), anyInt());
        verify(projectClient, never()).deleteCharacter(anyString());
    }

    @Test
    void emptyArray_returnsErrorBeforeFeign() {
        Map<String, Object> result = tools.batchDeleteCharacters("[]");

        assertFalse((Boolean) result.get("success"));
        verify(hitl, never()).confirmDestructiveAction(anyString(), anyInt());
        verify(projectClient, never()).deleteCharacter(anyString());
    }

    @Test
    void invalidJson_returnsParseError() {
        Map<String, Object> result = tools.batchDeleteCharacters("not-a-json");

        assertFalse((Boolean) result.get("success"));
        verify(projectClient, never()).deleteCharacter(anyString());
    }

    // ---------------------- 部分失败 ----------------------

    @Test
    void partialFailure_collectsBothListsWithoutThrowing() {
        when(hitl.confirmDestructiveAction(anyString(), anyInt()))
                .thenReturn(new ConfirmationResult.Confirmed("ask-4"));
        when(projectClient.deleteCharacter("c1")).thenReturn(Result.success(null));
        when(projectClient.deleteCharacter("c2")).thenReturn(Result.fail("not found"));
        when(projectClient.deleteCharacter("c3")).thenThrow(new RuntimeException("boom"));

        Map<String, Object> result = tools.batchDeleteCharacters("[\"c1\",\"c2\",\"c3\"]");

        assertTrue((Boolean) result.get("success"));
        assertFalse((Boolean) result.get("cancelled"));
        @SuppressWarnings("unchecked")
        List<String> deleted = (List<String>) result.get("deleted");
        assertEquals(List.of("c1"), deleted);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failed = (List<Map<String, Object>>) result.get("failed");
        assertEquals(2, failed.size());
        assertEquals("c2", failed.get(0).get("id"));
        assertEquals("c3", failed.get(1).get("id"));
    }

    @Test
    void duplicateIds_areDedupedBeforeDelete() {
        when(hitl.confirmDestructiveAction(anyString(), anyInt()))
                .thenReturn(new ConfirmationResult.Confirmed("ask-5"));
        when(projectClient.deleteCharacter("c1")).thenReturn(Result.success(null));

        Map<String, Object> result = tools.batchDeleteCharacters("[\"c1\",\"c1\",\" c1 \"]");

        assertTrue((Boolean) result.get("success"));
        @SuppressWarnings("unchecked")
        List<String> deleted = (List<String>) result.get("deleted");
        assertEquals(List.of("c1"), deleted);
        verify(projectClient, times(1)).deleteCharacter("c1");
    }
}
