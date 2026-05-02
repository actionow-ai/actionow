package com.actionow.canvas.conflict;

import com.actionow.canvas.entity.CanvasNode;
import com.actionow.canvas.mapper.CanvasNodeMapper;
import com.actionow.common.core.exception.BusinessException;
import com.actionow.common.core.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 冲突检测服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConflictDetectionService {
    private final CanvasNodeMapper nodeMapper;

    public void checkConflict(String nodeId, Integer expectedVersion) {
        CanvasNode node = nodeMapper.selectById(nodeId);
        if (node == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "节点不存在");
        }

        if (!node.getVersion().equals(expectedVersion)) {
            ConflictInfo conflict = new ConflictInfo();
            conflict.setNodeId(nodeId);
            conflict.setOperation("UPDATE");
            conflict.setTimestamp(LocalDateTime.now());
            conflict.setExpectedVersion(expectedVersion);
            conflict.setActualVersion(node.getVersion());

            log.warn("检测到冲突: nodeId={}, expected={}, actual={}",
                nodeId, expectedVersion, node.getVersion());

            throw new BusinessException(ResultCode.CONFLICT, "节点已被其他用户修改，请刷新后重试");
        }
    }
}
