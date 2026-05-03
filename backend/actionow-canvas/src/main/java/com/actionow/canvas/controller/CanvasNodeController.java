package com.actionow.canvas.controller;

import com.actionow.canvas.constant.CanvasConstants;
import com.actionow.canvas.dto.node.*;
import com.actionow.canvas.service.CanvasNodeService;
import com.actionow.common.core.context.UserContextHolder;
import com.actionow.common.core.result.Result;
import com.actionow.common.security.annotation.RequireWorkspaceMember;
import com.actionow.common.security.annotation.RequireWorkspaceMember.WorkspaceRole;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 画布节点控制器
 * 处理画布中节点的创建、更新、删除和查询
 *
 * @author Actionow
 */
@RestController
@RequestMapping("/canvas/nodes")
@RequiredArgsConstructor
public class CanvasNodeController {

    private final CanvasNodeService nodeService;

    /**
     * 创建节点
     * 双模式：传 entityId 引用已有实体；传 entityName + entityScope 等字段同时新建实体
     */
    @PostMapping
    @RequireWorkspaceMember(minRole = WorkspaceRole.MEMBER)
    public Result<CanvasNodeResponse> createNode(@RequestBody @Valid CreateNodeRequest request) {
        String workspaceId = UserContextHolder.getWorkspaceId();
        String userId = UserContextHolder.getUserId();
        CanvasNodeResponse response = nodeService.createNode(request, workspaceId, userId);
        return Result.success(response);
    }

    /**
     * 批量创建节点
     */
    @PostMapping("/batch")
    @RequireWorkspaceMember(minRole = WorkspaceRole.MEMBER)
    public Result<List<CanvasNodeResponse>> batchCreateNodes(@RequestBody @Valid List<CreateNodeRequest> requests) {
        String workspaceId = UserContextHolder.getWorkspaceId();
        String userId = UserContextHolder.getUserId();
        List<CanvasNodeResponse> responses = nodeService.batchCreateNodes(requests, workspaceId, userId);
        return Result.success(responses);
    }

    /**
     * 获取节点详情
     */
    @GetMapping("/{nodeId}")
    @RequireWorkspaceMember
    public Result<CanvasNodeResponse> getById(@PathVariable String nodeId) {
        CanvasNodeResponse response = nodeService.getById(nodeId);
        return Result.success(response);
    }

    /**
     * 创建分组
     */
    @PostMapping("/groups")
    @RequireWorkspaceMember(minRole = WorkspaceRole.MEMBER)
    public Result<CanvasNodeResponse> createGroup(@RequestBody @Valid CreateGroupRequest request) {
        String workspaceId = UserContextHolder.getWorkspaceId();
        String userId = UserContextHolder.getUserId();
        CanvasNodeResponse response = nodeService.createGroup(request, workspaceId, userId);
        return Result.success(response);
    }

    /**
     * 整组移动
     */
    @PutMapping("/groups/{groupId}/move")
    @RequireWorkspaceMember(minRole = WorkspaceRole.MEMBER)
    public Result<Void> moveGroup(
            @PathVariable String groupId,
            @RequestParam("deltaX") BigDecimal deltaX,
            @RequestParam("deltaY") BigDecimal deltaY) {
        nodeService.moveGroup(groupId, deltaX, deltaY);
        return Result.success();
    }

    @PostMapping("/batch/update")
    @RequireWorkspaceMember(minRole = WorkspaceRole.MEMBER)
    public Result<Void> batchUpdate(@RequestBody BatchUpdateRequest request) {
        String userId = UserContextHolder.getUserId();
        nodeService.batchUpdate(request, userId);
        return Result.success();
    }

    @PostMapping("/batch/delete")
    @RequireWorkspaceMember(minRole = WorkspaceRole.MEMBER)
    public Result<Void> batchDelete(@RequestBody List<String> nodeIds) {
        String userId = UserContextHolder.getUserId();
        nodeService.batchDelete(nodeIds, userId);
        return Result.success();
    }

    /**
     * 获取实体在所有画布中的节点
     */
    @GetMapping("/entity/{entityType}/{entityId}")
    @RequireWorkspaceMember
    public Result<List<CanvasNodeResponse>> listByEntity(
            @PathVariable String entityType,
            @PathVariable String entityId) {
        List<CanvasNodeResponse> nodes = nodeService.listByEntity(
                entityType.toUpperCase(), entityId);
        return Result.success(nodes);
    }

    /**
     * 更新节点
     */
    @PutMapping("/{nodeId}")
    @RequireWorkspaceMember(minRole = WorkspaceRole.MEMBER)
    public Result<CanvasNodeResponse> updateNode(
            @PathVariable String nodeId,
            @RequestBody @Valid UpdateNodeRequest request) {
        String userId = UserContextHolder.getUserId();
        CanvasNodeResponse response = nodeService.updateNode(nodeId, request, userId);
        return Result.success(response);
    }

    /**
     * 批量更新节点位置
     */
    @PutMapping("/batch/positions")
    @RequireWorkspaceMember(minRole = WorkspaceRole.MEMBER)
    public Result<Void> batchUpdatePositions(@RequestBody @Valid List<UpdateNodeRequest> updates) {
        String userId = UserContextHolder.getUserId();
        nodeService.batchUpdatePositions(updates, userId);
        return Result.success();
    }

    /**
     * 更新节点并同步实体信息到 Project 服务
     * 当需要同时更新节点布局和业务实体信息时使用
     */
    @PutMapping("/{nodeId}/with-entity")
    @RequireWorkspaceMember(minRole = WorkspaceRole.MEMBER)
    public Result<CanvasNodeResponse> updateNodeWithEntity(
            @PathVariable String nodeId,
            @RequestBody @Valid UpdateNodeWithEntityRequest request) {
        String workspaceId = UserContextHolder.getWorkspaceId();
        String userId = UserContextHolder.getUserId();
        CanvasNodeResponse response = nodeService.updateNodeWithEntity(nodeId, request, workspaceId, userId);
        return Result.success(response);
    }

    /**
     * 批量更新节点并同步实体信息到 Project 服务
     */
    @PutMapping("/batch/with-entity")
    @RequireWorkspaceMember(minRole = WorkspaceRole.MEMBER)
    public Result<List<CanvasNodeResponse>> batchUpdateNodesWithEntity(
            @RequestBody @Valid List<UpdateNodeWithEntityRequest> requests) {
        String workspaceId = UserContextHolder.getWorkspaceId();
        String userId = UserContextHolder.getUserId();
        List<CanvasNodeResponse> responses = nodeService.batchUpdateNodesWithEntity(requests, workspaceId, userId);
        return Result.success(responses);
    }

    /**
     * 删除节点
     *
     * @param nodeId        节点ID
     * @param syncToProject 是否同步删除 Project 中的实体（默认 false，只删除画布节点）
     */
    @DeleteMapping("/{nodeId}")
    @RequireWorkspaceMember(minRole = WorkspaceRole.MEMBER)
    public Result<Void> deleteNode(
            @PathVariable String nodeId,
            @RequestParam(required = false, defaultValue = "false") boolean syncToProject) {
        String userId = UserContextHolder.getUserId();
        nodeService.deleteNode(nodeId, userId, syncToProject);
        return Result.success();
    }

    /**
     * 用源 asset 的文件信息替换节点关联 asset 内容
     * 用于 AI 生成完成后的回填
     */
    @PostMapping("/{nodeId}/replace-asset-content")
    @RequireWorkspaceMember(minRole = WorkspaceRole.MEMBER)
    public Result<CanvasNodeResponse> replaceAssetContent(
            @PathVariable String nodeId,
            @RequestBody @Valid ReplaceAssetContentRequest request) {
        String userId = UserContextHolder.getUserId();
        CanvasNodeResponse response = nodeService.replaceAssetContent(nodeId, request.getSourceAssetId(), userId);
        return Result.success(response);
    }

    /**
     * 验证节点类型是否允许
     */
    @GetMapping("/validate")
    @RequireWorkspaceMember
    public Result<Boolean> validateNodeType(
            @RequestParam String canvasId,
            @RequestParam String entityType) {
        // 素材子类型保持小写，其他类型转大写
        String normalizedType = CanvasConstants.EntityType.isAssetSubType(entityType)
                ? entityType
                : entityType.toUpperCase();
        boolean valid = nodeService.validateNodeType(canvasId, normalizedType);
        return Result.success(valid);
    }
}
