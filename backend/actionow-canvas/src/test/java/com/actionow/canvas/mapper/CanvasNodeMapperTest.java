package com.actionow.canvas.mapper;

import com.actionow.canvas.entity.CanvasNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2.1 测试：空间索引查询
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CanvasNodeMapperTest {

    @Autowired
    private CanvasNodeMapper nodeMapper;

    @Test
    void testSelectByViewport_shouldReturnNodesInRange() {
        String canvasId = "test-canvas";

        CanvasNode node1 = createNode(canvasId, BigDecimal.valueOf(100), BigDecimal.valueOf(100));
        CanvasNode node2 = createNode(canvasId, BigDecimal.valueOf(500), BigDecimal.valueOf(500));
        CanvasNode node3 = createNode(canvasId, BigDecimal.valueOf(2000), BigDecimal.valueOf(2000));

        nodeMapper.insert(node1);
        nodeMapper.insert(node2);
        nodeMapper.insert(node3);

        List<CanvasNode> result = nodeMapper.selectByViewport(
            canvasId,
            BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.valueOf(1000), BigDecimal.valueOf(1000),
            100
        );

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(n -> n.getId().equals(node1.getId())));
        assertTrue(result.stream().anyMatch(n -> n.getId().equals(node2.getId())));
    }

    private CanvasNode createNode(String canvasId, BigDecimal x, BigDecimal y) {
        CanvasNode node = new CanvasNode();
        node.setId("node-" + System.nanoTime());
        node.setCanvasId(canvasId);
        node.setEntityType("SCRIPT");
        node.setEntityId("entity-" + System.nanoTime());
        node.setPositionX(x);
        node.setPositionY(y);
        node.setZIndex(1);
        return node;
    }
}
