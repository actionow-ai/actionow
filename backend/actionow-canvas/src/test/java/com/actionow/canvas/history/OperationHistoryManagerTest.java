package com.actionow.canvas.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4.3 测试：操作历史
 */
@ExtendWith(MockitoExtension.class)
class OperationHistoryManagerTest {

    @InjectMocks
    private OperationHistoryManager historyManager;

    @Test
    void testUndoRedo() {
        String canvasId = "canvas-1";

        Operation op1 = createOperation(canvasId, "node-1", Operation.OperationType.CREATE_NODE);
        historyManager.recordOperation(op1);

        Operation op2 = createOperation(canvasId, "node-2", Operation.OperationType.UPDATE_NODE);
        historyManager.recordOperation(op2);

        // 撤销
        var undone = historyManager.undo(canvasId);
        assertTrue(undone.isPresent());
        assertEquals("node-2", undone.get().getTargetId());

        // 重做
        var redone = historyManager.redo(canvasId);
        assertTrue(redone.isPresent());
        assertEquals("node-2", redone.get().getTargetId());
    }

    @Test
    void testHistoryLimit() {
        String canvasId = "canvas-2";

        for (int i = 0; i < 150; i++) {
            Operation op = createOperation(canvasId, "node-" + i, Operation.OperationType.CREATE_NODE);
            historyManager.recordOperation(op);
        }

        List<Operation> history = historyManager.getHistory(canvasId, 200);
        assertEquals(100, history.size());
    }

    private Operation createOperation(String canvasId, String targetId, Operation.OperationType type) {
        Operation op = new Operation();
        op.setId(java.util.UUID.randomUUID().toString());
        op.setCanvasId(canvasId);
        op.setUserId("user-1");
        op.setType(type);
        op.setTargetId(targetId);
        op.setAfterState(new HashMap<>(Map.of("test", "data")));
        op.setTimestamp(LocalDateTime.now());
        return op;
    }
}
