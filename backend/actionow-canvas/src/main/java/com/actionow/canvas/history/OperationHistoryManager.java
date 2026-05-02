package com.actionow.canvas.history;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 操作历史管理器（内存级别）
 */
@Slf4j
@Component
public class OperationHistoryManager {
    private static final int MAX_HISTORY_SIZE = 100;

    // canvasId -> 操作历史栈
    private final Map<String, Deque<Operation>> undoStacks = new ConcurrentHashMap<>();
    private final Map<String, Deque<Operation>> redoStacks = new ConcurrentHashMap<>();

    public void recordOperation(Operation operation) {
        String canvasId = operation.getCanvasId();
        Deque<Operation> undoStack = undoStacks.computeIfAbsent(canvasId, k -> new ArrayDeque<>());

        undoStack.push(operation);
        if (undoStack.size() > MAX_HISTORY_SIZE) {
            undoStack.removeLast();
        }

        // 新操作会清空 redo 栈
        redoStacks.getOrDefault(canvasId, new ArrayDeque<>()).clear();

        log.debug("记录操作: canvasId={}, type={}, targetId={}",
            canvasId, operation.getType(), operation.getTargetId());
    }

    public Optional<Operation> undo(String canvasId) {
        Deque<Operation> undoStack = undoStacks.get(canvasId);
        if (undoStack == null || undoStack.isEmpty()) {
            return Optional.empty();
        }

        Operation operation = undoStack.pop();
        redoStacks.computeIfAbsent(canvasId, k -> new ArrayDeque<>()).push(operation);

        return Optional.of(operation);
    }

    public Optional<Operation> redo(String canvasId) {
        Deque<Operation> redoStack = redoStacks.get(canvasId);
        if (redoStack == null || redoStack.isEmpty()) {
            return Optional.empty();
        }

        Operation operation = redoStack.pop();
        undoStacks.computeIfAbsent(canvasId, k -> new ArrayDeque<>()).push(operation);

        return Optional.of(operation);
    }

    public List<Operation> getHistory(String canvasId, int limit) {
        Deque<Operation> undoStack = undoStacks.get(canvasId);
        if (undoStack == null) {
            return List.of();
        }
        return undoStack.stream().limit(limit).toList();
    }

    public void clearHistory(String canvasId) {
        undoStacks.remove(canvasId);
        redoStacks.remove(canvasId);
    }
}
