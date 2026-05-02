package com.actionow.canvas.history;

import com.actionow.canvas.entity.CanvasNode;
import com.actionow.canvas.mapper.CanvasNodeMapper;
import com.actionow.common.core.exception.BusinessException;
import com.actionow.common.core.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作历史服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationHistoryService {
    private final OperationHistoryManager historyManager;
    private final CanvasNodeMapper nodeMapper;

    public void recordNodeCreate(CanvasNode node, String userId) {
        Operation op = new Operation();
        op.setId(java.util.UUID.randomUUID().toString());
        op.setCanvasId(node.getCanvasId());
        op.setUserId(userId);
        op.setType(Operation.OperationType.CREATE_NODE);
        op.setTargetId(node.getId());
        op.setAfterState(nodeToMap(node));
        op.setTimestamp(LocalDateTime.now());
        historyManager.recordOperation(op);
    }

    public void recordNodeUpdate(CanvasNode before, CanvasNode after, String userId) {
        Operation op = new Operation();
        op.setId(java.util.UUID.randomUUID().toString());
        op.setCanvasId(after.getCanvasId());
        op.setUserId(userId);
        op.setType(Operation.OperationType.UPDATE_NODE);
        op.setTargetId(after.getId());
        op.setBeforeState(nodeToMap(before));
        op.setAfterState(nodeToMap(after));
        op.setTimestamp(LocalDateTime.now());
        historyManager.recordOperation(op);
    }

    public void recordNodeDelete(CanvasNode node, String userId) {
        Operation op = new Operation();
        op.setId(java.util.UUID.randomUUID().toString());
        op.setCanvasId(node.getCanvasId());
        op.setUserId(userId);
        op.setType(Operation.OperationType.DELETE_NODE);
        op.setTargetId(node.getId());
        op.setBeforeState(nodeToMap(node));
        op.setTimestamp(LocalDateTime.now());
        historyManager.recordOperation(op);
    }

    @Transactional(rollbackFor = Exception.class)
    public void undo(String canvasId) {
        Operation op = historyManager.undo(canvasId)
            .orElseThrow(() -> new BusinessException(ResultCode.PARAM_INVALID, "无可撤销操作"));

        switch (op.getType()) {
            case CREATE_NODE -> nodeMapper.deleteById(op.getTargetId());
            case UPDATE_NODE -> restoreNode(op.getTargetId(), op.getBeforeState());
            case DELETE_NODE -> restoreNode(op.getTargetId(), op.getBeforeState());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void redo(String canvasId) {
        Operation op = historyManager.redo(canvasId)
            .orElseThrow(() -> new BusinessException(ResultCode.PARAM_INVALID, "无可重做操作"));

        switch (op.getType()) {
            case CREATE_NODE -> restoreNode(op.getTargetId(), op.getAfterState());
            case UPDATE_NODE -> restoreNode(op.getTargetId(), op.getAfterState());
            case DELETE_NODE -> nodeMapper.deleteById(op.getTargetId());
        }
    }

    public List<Operation> getHistory(String canvasId, int limit) {
        return historyManager.getHistory(canvasId, limit);
    }

    private Map<String, Object> nodeToMap(CanvasNode node) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", node.getId());
        map.put("positionX", node.getPositionX());
        map.put("positionY", node.getPositionY());
        map.put("width", node.getWidth());
        map.put("height", node.getHeight());
        map.put("zIndex", node.getZIndex());
        return map;
    }

    private void restoreNode(String nodeId, Map<String, Object> state) {
        CanvasNode node = nodeMapper.selectById(nodeId);
        if (node == null) return;

        if (state.containsKey("positionX")) node.setPositionX((java.math.BigDecimal) state.get("positionX"));
        if (state.containsKey("positionY")) node.setPositionY((java.math.BigDecimal) state.get("positionY"));
        if (state.containsKey("width")) node.setWidth((java.math.BigDecimal) state.get("width"));
        if (state.containsKey("height")) node.setHeight((java.math.BigDecimal) state.get("height"));
        if (state.containsKey("zIndex")) node.setZIndex((Integer) state.get("zIndex"));

        nodeMapper.updateById(node);
    }
}
