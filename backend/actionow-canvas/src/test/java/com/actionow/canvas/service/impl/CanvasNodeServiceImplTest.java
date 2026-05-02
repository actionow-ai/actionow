package com.actionow.canvas.service.impl;

import com.actionow.canvas.constant.CanvasConstants;
import com.actionow.canvas.dto.feign.CanvasEntityCreateRequest;
import com.actionow.canvas.dto.feign.CanvasEntityCreateResponse;
import com.actionow.canvas.dto.node.CanvasNodeResponse;
import com.actionow.canvas.dto.node.CreateNodeRequest;
import com.actionow.canvas.dto.node.UpdateNodeRequest;
import com.actionow.canvas.entity.Canvas;
import com.actionow.canvas.entity.CanvasNode;
import com.actionow.canvas.feign.ProjectFeignClient;
import com.actionow.canvas.mapper.CanvasMapper;
import com.actionow.canvas.mapper.CanvasNodeMapper;
import com.actionow.common.core.constant.CommonConstants;
import com.actionow.common.core.exception.BusinessException;
import com.actionow.common.core.result.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CanvasNodeServiceImpl 单元测试
 *
 * @author Actionow
 */
@ExtendWith(MockitoExtension.class)
class CanvasNodeServiceImplTest {

    @Mock
    private CanvasNodeMapper nodeMapper;

    @Mock
    private CanvasMapper canvasMapper;

    @Mock
    private ProjectFeignClient projectFeignClient;

    @InjectMocks
    private CanvasNodeServiceImpl canvasNodeService;

    private static final String WORKSPACE_ID = "workspace-123";
    private static final String USER_ID = "user-123";
    private static final String CANVAS_ID = "canvas-123";
    private static final String ENTITY_ID = "entity-123";
    private static final String NODE_ID = "node-123";

    @Nested
    @DisplayName("创建节点测试")
    class CreateNodeTests {

        private Canvas mockCanvas;
        private CreateNodeRequest request;

        @BeforeEach
        void setUp() {
            mockCanvas = new Canvas();
            mockCanvas.setId(CANVAS_ID);
            mockCanvas.setScriptId("script-123");

            request = new CreateNodeRequest();
            request.setCanvasId(CANVAS_ID);
            request.setEntityType(CanvasConstants.EntityType.CHARACTER);
            request.setEntityId(ENTITY_ID);
            request.setPositionX(BigDecimal.valueOf(100));
            request.setPositionY(BigDecimal.valueOf(200));
        }

        @Test
        @DisplayName("已有实体模式 - 成功创建节点")
        void createNode_withExistingEntity_success() {
            // Given
            when(canvasMapper.selectById(CANVAS_ID)).thenReturn(mockCanvas);
            when(nodeMapper.selectByCanvasAndEntity(CANVAS_ID, CanvasConstants.EntityType.CHARACTER, ENTITY_ID))
                    .thenReturn(null);
            when(nodeMapper.selectMaxZIndex(CANVAS_ID)).thenReturn(5);
            when(nodeMapper.insert(any(CanvasNode.class))).thenReturn(1);

            // When
            CanvasNodeResponse response = canvasNodeService.createNode(request, WORKSPACE_ID, USER_ID);

            // Then
            assertNotNull(response);
            assertEquals(CANVAS_ID, response.getCanvasId());
            assertEquals(CanvasConstants.EntityType.CHARACTER, response.getEntityType());
            assertEquals(ENTITY_ID, response.getEntityId());
            assertEquals(BigDecimal.valueOf(100), response.getPositionX());
            assertEquals(BigDecimal.valueOf(200), response.getPositionY());

            // Verify no feign call made (existing entity mode)
            verify(projectFeignClient, never()).createEntity(any(CanvasEntityCreateRequest.class));

            // Verify insert called with correct zIndex
            ArgumentCaptor<CanvasNode> nodeCaptor = ArgumentCaptor.forClass(CanvasNode.class);
            verify(nodeMapper).insert(nodeCaptor.capture());
            assertEquals(6, nodeCaptor.getValue().getZIndex());
        }

        @Test
        @DisplayName("新建实体模式 - 成功创建节点和实体")
        void createNode_withNewEntity_success() {
            // Given
            request.setEntityId(null);  // No entity ID - trigger new entity mode
            request.setEntityName("Test Character");
            request.setEntityDescription("A test character");

            CanvasEntityCreateResponse createResponse = new CanvasEntityCreateResponse();
            createResponse.setEntityId("new-entity-123");
            createResponse.setName("Test Character");
            createResponse.setThumbnailUrl("http://example.com/thumb.jpg");

            when(canvasMapper.selectById(CANVAS_ID)).thenReturn(mockCanvas);
            when(projectFeignClient.createEntity(any(CanvasEntityCreateRequest.class)))
                    .thenReturn(Result.success(createResponse));
            when(nodeMapper.selectByCanvasAndEntity(eq(CANVAS_ID), eq(CanvasConstants.EntityType.CHARACTER), anyString()))
                    .thenReturn(null);
            when(nodeMapper.selectMaxZIndex(CANVAS_ID)).thenReturn(0);
            when(nodeMapper.insert(any(CanvasNode.class))).thenReturn(1);

            // When
            CanvasNodeResponse response = canvasNodeService.createNode(request, WORKSPACE_ID, USER_ID);

            // Then
            assertNotNull(response);
            assertEquals("new-entity-123", response.getEntityId());
            // Note: cachedName and cachedThumbnailUrl are stored in the entity but not exposed in response
            // The response uses entityDetail for enriched data

            // Verify feign call made
            verify(projectFeignClient).createEntity(any(CanvasEntityCreateRequest.class));
        }

        @Test
        @DisplayName("画布不存在 - 抛出异常")
        void createNode_canvasNotFound_throwsException() {
            // Given
            when(canvasMapper.selectById(CANVAS_ID)).thenReturn(null);

            // When & Then
            BusinessException exception = assertThrows(BusinessException.class, () ->
                    canvasNodeService.createNode(request, WORKSPACE_ID, USER_ID));

            assertTrue(exception.getMessage().contains("画布不存在"));
        }

        @Test
        @DisplayName("节点类型不允许 - 抛出异常")
        void createNode_invalidEntityType_throwsException() {
            // Given
            request.setEntityType(CanvasConstants.EntityType.STYLE); // STYLE not allowed in SCRIPT dimension

            when(canvasMapper.selectById(CANVAS_ID)).thenReturn(mockCanvas);

            // When & Then
            BusinessException exception = assertThrows(BusinessException.class, () ->
                    canvasNodeService.createNode(request, WORKSPACE_ID, USER_ID));

            assertTrue(exception.getMessage().contains("不允许添加"));
        }

        @Test
        @DisplayName("节点已存在 - 抛出异常")
        void createNode_nodeAlreadyExists_throwsException() {
            // Given
            CanvasNode existingNode = new CanvasNode();
            existingNode.setId(NODE_ID);

            when(canvasMapper.selectById(CANVAS_ID)).thenReturn(mockCanvas);
            when(nodeMapper.selectByCanvasAndEntity(CANVAS_ID, CanvasConstants.EntityType.CHARACTER, ENTITY_ID))
                    .thenReturn(existingNode);

            // When & Then
            BusinessException exception = assertThrows(BusinessException.class, () ->
                    canvasNodeService.createNode(request, WORKSPACE_ID, USER_ID));

            assertTrue(exception.getMessage().contains("节点已存在"));
        }

        @Test
        @DisplayName("新建实体模式缺少名称 - 抛出异常")
        void createNode_newEntityWithoutName_throwsException() {
            // Given
            request.setEntityId(null);  // No entity ID
            request.setEntityName(null);  // No entity name

            when(canvasMapper.selectById(CANVAS_ID)).thenReturn(mockCanvas);

            // When & Then
            BusinessException exception = assertThrows(BusinessException.class, () ->
                    canvasNodeService.createNode(request, WORKSPACE_ID, USER_ID));

            assertTrue(exception.getMessage().contains("entityId 或 entityName 必须提供其一"));
        }

        @Test
        @DisplayName("Project 服务不可用 - 抛出异常")
        void createNode_projectServiceUnavailable_throwsException() {
            // Given
            request.setEntityId(null);
            request.setEntityName("Test Character");

            when(canvasMapper.selectById(CANVAS_ID)).thenReturn(mockCanvas);
            when(projectFeignClient.createEntity(any(CanvasEntityCreateRequest.class)))
                    .thenReturn(Result.fail("500", "服务不可用"));

            // When & Then
            BusinessException exception = assertThrows(BusinessException.class, () ->
                    canvasNodeService.createNode(request, WORKSPACE_ID, USER_ID));

            assertTrue(exception.getMessage().contains("创建实体失败"));
        }
    }

    @Nested
    @DisplayName("更新节点测试")
    class UpdateNodeTests {

        private CanvasNode existingNode;
        private UpdateNodeRequest request;

        @BeforeEach
        void setUp() {
            existingNode = new CanvasNode();
            existingNode.setId(NODE_ID);
            existingNode.setCanvasId(CANVAS_ID);
            existingNode.setEntityType(CanvasConstants.EntityType.CHARACTER);
            existingNode.setEntityId(ENTITY_ID);
            existingNode.setPositionX(BigDecimal.valueOf(100));
            existingNode.setPositionY(BigDecimal.valueOf(200));
            existingNode.setDeleted(CommonConstants.NOT_DELETED);

            request = new UpdateNodeRequest();
        }

        @Test
        @DisplayName("更新位置 - 成功")
        void updateNode_updatePosition_success() {
            // Given
            request.setPositionX(BigDecimal.valueOf(300));
            request.setPositionY(BigDecimal.valueOf(400));

            when(nodeMapper.selectById(NODE_ID)).thenReturn(existingNode);
            when(nodeMapper.updateById(any(CanvasNode.class))).thenReturn(1);

            // When
            CanvasNodeResponse response = canvasNodeService.updateNode(NODE_ID, request, USER_ID);

            // Then
            assertNotNull(response);
            assertEquals(BigDecimal.valueOf(300), response.getPositionX());
            assertEquals(BigDecimal.valueOf(400), response.getPositionY());
        }

        @Test
        @DisplayName("部分更新 - 只更新提供的字段")
        void updateNode_partialUpdate_success() {
            // Given
            request.setWidth(BigDecimal.valueOf(250));
            // positionX, positionY not set

            when(nodeMapper.selectById(NODE_ID)).thenReturn(existingNode);
            when(nodeMapper.updateById(any(CanvasNode.class))).thenReturn(1);

            // When
            CanvasNodeResponse response = canvasNodeService.updateNode(NODE_ID, request, USER_ID);

            // Then
            assertEquals(BigDecimal.valueOf(250), response.getWidth());
            assertEquals(BigDecimal.valueOf(100), response.getPositionX()); // unchanged
            assertEquals(BigDecimal.valueOf(200), response.getPositionY()); // unchanged
        }

        @Test
        @DisplayName("节点不存在 - 抛出异常")
        void updateNode_nodeNotFound_throwsException() {
            // Given
            when(nodeMapper.selectById(NODE_ID)).thenReturn(null);

            // When & Then
            assertThrows(BusinessException.class, () ->
                    canvasNodeService.updateNode(NODE_ID, request, USER_ID));
        }

        @Test
        @DisplayName("节点已删除 - 抛出异常")
        void updateNode_nodeDeleted_throwsException() {
            // Given
            existingNode.setDeleted(CommonConstants.DELETED);
            when(nodeMapper.selectById(NODE_ID)).thenReturn(existingNode);

            // When & Then
            assertThrows(BusinessException.class, () ->
                    canvasNodeService.updateNode(NODE_ID, request, USER_ID));
        }
    }

    @Nested
    @DisplayName("删除节点测试")
    class DeleteNodeTests {

        @Test
        @DisplayName("删除存在的节点 - 成功")
        void deleteNode_existingNode_success() {
            // Given
            CanvasNode existingNode = new CanvasNode();
            existingNode.setId(NODE_ID);
            existingNode.setDeleted(CommonConstants.NOT_DELETED);

            when(nodeMapper.selectById(NODE_ID)).thenReturn(existingNode);
            when(nodeMapper.deleteById(NODE_ID)).thenReturn(1);

            // When & Then
            assertDoesNotThrow(() -> canvasNodeService.deleteNode(NODE_ID, USER_ID));
            verify(nodeMapper).deleteById(NODE_ID);
        }

        @Test
        @DisplayName("删除不存在的节点 - 抛出异常")
        void deleteNode_nodeNotFound_throwsException() {
            // Given
            when(nodeMapper.selectById(NODE_ID)).thenReturn(null);

            // When & Then
            assertThrows(BusinessException.class, () ->
                    canvasNodeService.deleteNode(NODE_ID, USER_ID));
        }
    }

    @Nested
    @DisplayName("批量操作测试")
    class BatchOperationTests {

        @Test
        @DisplayName("批量创建节点 - 部分成功")
        void batchCreateNodes_partialSuccess() {
            // Given
            Canvas mockCanvas = new Canvas();
            mockCanvas.setId(CANVAS_ID);
            mockCanvas.setScriptId("script-123");

            CreateNodeRequest request1 = new CreateNodeRequest();
            request1.setCanvasId(CANVAS_ID);
            request1.setEntityType(CanvasConstants.EntityType.CHARACTER);
            request1.setEntityId("entity-1");
            request1.setPositionX(BigDecimal.ZERO);
            request1.setPositionY(BigDecimal.ZERO);

            CreateNodeRequest request2 = new CreateNodeRequest();
            request2.setCanvasId(CANVAS_ID);
            request2.setEntityType(CanvasConstants.EntityType.CHARACTER);
            request2.setEntityId("entity-2");
            request2.setPositionX(BigDecimal.valueOf(100));
            request2.setPositionY(BigDecimal.valueOf(100));

            when(canvasMapper.selectById(CANVAS_ID)).thenReturn(mockCanvas);
            when(nodeMapper.selectByCanvasAndEntity(CANVAS_ID, CanvasConstants.EntityType.CHARACTER, "entity-1"))
                    .thenReturn(null);
            when(nodeMapper.selectByCanvasAndEntity(CANVAS_ID, CanvasConstants.EntityType.CHARACTER, "entity-2"))
                    .thenReturn(new CanvasNode()); // Already exists
            when(nodeMapper.selectMaxZIndex(CANVAS_ID)).thenReturn(0);
            when(nodeMapper.insert(any(CanvasNode.class))).thenReturn(1);

            // When
            List<CanvasNodeResponse> responses = canvasNodeService.batchCreateNodes(
                    Arrays.asList(request1, request2), WORKSPACE_ID, USER_ID);

            // Then
            assertEquals(1, responses.size()); // Only one succeeded
            assertEquals("entity-1", responses.get(0).getEntityId());
        }

        @Test
        @DisplayName("批量更新位置 - 成功")
        void batchUpdatePositions_success() {
            // Given
            CanvasNode node1 = new CanvasNode();
            node1.setId("node-1");
            node1.setPositionX(BigDecimal.ZERO);
            node1.setPositionY(BigDecimal.ZERO);
            node1.setDeleted(CommonConstants.NOT_DELETED);

            CanvasNode node2 = new CanvasNode();
            node2.setId("node-2");
            node2.setPositionX(BigDecimal.ZERO);
            node2.setPositionY(BigDecimal.ZERO);
            node2.setDeleted(CommonConstants.NOT_DELETED);

            UpdateNodeRequest update1 = new UpdateNodeRequest();
            update1.setNodeId("node-1");
            update1.setPositionX(BigDecimal.valueOf(100));
            update1.setPositionY(BigDecimal.valueOf(100));

            UpdateNodeRequest update2 = new UpdateNodeRequest();
            update2.setNodeId("node-2");
            update2.setPositionX(BigDecimal.valueOf(200));
            update2.setPositionY(BigDecimal.valueOf(200));

            when(nodeMapper.selectById("node-1")).thenReturn(node1);
            when(nodeMapper.selectById("node-2")).thenReturn(node2);
            when(nodeMapper.updateById(any(CanvasNode.class))).thenReturn(1);

            // When
            canvasNodeService.batchUpdatePositions(Arrays.asList(update1, update2), USER_ID);

            // Then
            verify(nodeMapper, times(2)).updateById(any(CanvasNode.class));
        }

        @Test
        @DisplayName("批量更新位置 - 空列表")
        void batchUpdatePositions_emptyList() {
            // When
            canvasNodeService.batchUpdatePositions(Collections.emptyList(), USER_ID);

            // Then
            verify(nodeMapper, never()).updateById(any(CanvasNode.class));
        }
    }

    @Nested
    @DisplayName("验证节点类型测试")
    class ValidateNodeTypeTests {

        @Test
        @DisplayName("SCRIPT 维度允许 CHARACTER")
        void validateNodeType_scriptDimension_characterAllowed() {
            // Given
            Canvas canvas = new Canvas();
            canvas.setScriptId("script-123");
            when(canvasMapper.selectById(CANVAS_ID)).thenReturn(canvas);

            // When
            boolean result = canvasNodeService.validateNodeType(CANVAS_ID, CanvasConstants.EntityType.CHARACTER);

            // Then
            assertTrue(result);
        }

        @Test
        @DisplayName("SCRIPT 维度不允许 STYLE")
        void validateNodeType_scriptDimension_styleNotAllowed() {
            // Given
            Canvas canvas = new Canvas();
            canvas.setScriptId("script-123");
            when(canvasMapper.selectById(CANVAS_ID)).thenReturn(canvas);

            // When
            boolean result = canvasNodeService.validateNodeType(CANVAS_ID, CanvasConstants.EntityType.STYLE);

            // Then
            assertFalse(result);
        }

        @Test
        @DisplayName("统一画布允许 STYLE 节点")
        void validateNodeType_unifiedCanvas_styleAllowed() {
            // Given
            Canvas canvas = new Canvas();
            canvas.setScriptId("script-123");
            when(canvasMapper.selectById(CANVAS_ID)).thenReturn(canvas);

            // When
            boolean result = canvasNodeService.validateNodeType(CANVAS_ID, CanvasConstants.EntityType.STYLE);

            // Then
            assertTrue(result);
        }

        @Test
        @DisplayName("画布不存在返回 false")
        void validateNodeType_canvasNotFound_returnsFalse() {
            // Given
            when(canvasMapper.selectById(CANVAS_ID)).thenReturn(null);

            // When
            boolean result = canvasNodeService.validateNodeType(CANVAS_ID, CanvasConstants.EntityType.CHARACTER);

            // Then
            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("查询节点测试")
    class QueryNodeTests {

        @Test
        @DisplayName("根据 ID 查询节点")
        void getById_success() {
            // Given
            CanvasNode node = new CanvasNode();
            node.setId(NODE_ID);
            node.setEntityType(CanvasConstants.EntityType.CHARACTER);
            node.setEntityId(ENTITY_ID);
            node.setDeleted(CommonConstants.NOT_DELETED);

            when(nodeMapper.selectById(NODE_ID)).thenReturn(node);

            // When
            CanvasNodeResponse response = canvasNodeService.getById(NODE_ID);

            // Then
            assertNotNull(response);
            assertEquals(NODE_ID, response.getId());
        }

        @Test
        @DisplayName("根据画布 ID 查询所有节点")
        void listByCanvasId_success() {
            // Given
            CanvasNode node1 = new CanvasNode();
            node1.setId("node-1");
            node1.setEntityType(CanvasConstants.EntityType.CHARACTER);

            CanvasNode node2 = new CanvasNode();
            node2.setId("node-2");
            node2.setEntityType(CanvasConstants.EntityType.SCENE);

            when(nodeMapper.selectByCanvasId(CANVAS_ID)).thenReturn(Arrays.asList(node1, node2));

            // When
            List<CanvasNodeResponse> responses = canvasNodeService.listByCanvasId(CANVAS_ID);

            // Then
            assertEquals(2, responses.size());
        }

        @Test
        @DisplayName("根据实体查询节点")
        void listByEntity_success() {
            // Given
            CanvasNode node = new CanvasNode();
            node.setId(NODE_ID);
            node.setEntityType(CanvasConstants.EntityType.CHARACTER);
            node.setEntityId(ENTITY_ID);

            when(nodeMapper.selectByEntity(CanvasConstants.EntityType.CHARACTER, ENTITY_ID))
                    .thenReturn(Collections.singletonList(node));

            // When
            List<CanvasNodeResponse> responses = canvasNodeService.listByEntity(
                    CanvasConstants.EntityType.CHARACTER, ENTITY_ID);

            // Then
            assertEquals(1, responses.size());
            assertEquals(NODE_ID, responses.get(0).getId());
        }
    }

    @Nested
    @DisplayName("更新缓存信息测试")
    class UpdateCachedInfoTests {

        @Test
        @DisplayName("更新实体缓存信息 - 成功")
        void updateCachedInfo_success() {
            // Given
            CanvasNode node1 = new CanvasNode();
            node1.setId("node-1");
            node1.setEntityType(CanvasConstants.EntityType.CHARACTER);
            node1.setEntityId(ENTITY_ID);

            CanvasNode node2 = new CanvasNode();
            node2.setId("node-2");
            node2.setEntityType(CanvasConstants.EntityType.CHARACTER);
            node2.setEntityId(ENTITY_ID);

            when(nodeMapper.selectByEntity(CanvasConstants.EntityType.CHARACTER, ENTITY_ID))
                    .thenReturn(Arrays.asList(node1, node2));
            when(nodeMapper.updateById(any(CanvasNode.class))).thenReturn(1);

            // When
            canvasNodeService.updateCachedInfo(
                    CanvasConstants.EntityType.CHARACTER, ENTITY_ID,
                    "Updated Name", "http://example.com/new-thumb.jpg");

            // Then
            verify(nodeMapper, times(2)).updateById(any(CanvasNode.class));

            ArgumentCaptor<CanvasNode> captor = ArgumentCaptor.forClass(CanvasNode.class);
            verify(nodeMapper, times(2)).updateById(captor.capture());

            List<CanvasNode> updatedNodes = captor.getAllValues();
            for (CanvasNode node : updatedNodes) {
            }
        }
    }
}
