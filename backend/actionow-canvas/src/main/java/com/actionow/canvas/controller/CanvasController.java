package com.actionow.canvas.controller;

import com.actionow.canvas.dto.canvas.*;
import com.actionow.canvas.dto.node.CanvasNodeResponse;
import com.actionow.canvas.dto.node.ViewportQueryRequest;
import com.actionow.canvas.dto.view.ViewDataRequest;
import com.actionow.canvas.dto.view.ViewDataResponse;
import com.actionow.canvas.service.CanvasNodeService;
import com.actionow.canvas.service.CanvasService;
import com.actionow.common.core.context.UserContextHolder;
import com.actionow.common.core.result.Result;
import com.actionow.common.security.annotation.RequireWorkspaceMember;
import com.actionow.common.security.annotation.RequireWorkspaceMember.WorkspaceRole;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 画布控制器
 * 统一主画布模型：1 Script = 1 Canvas
 * 管理画布的创建、查询、更新和删除
 *
 * @author Actionow
 */
@RestController
@RequestMapping("/canvas")
@RequiredArgsConstructor
public class CanvasController {

    private final CanvasService canvasService;
    private final CanvasNodeService canvasNodeService;

    /**
     * 获取工作空间的画布列表
     */
    @GetMapping
    @RequireWorkspaceMember
    public Result<java.util.List<CanvasResponse>> listCanvases() {
        String workspaceId = UserContextHolder.getWorkspaceId();
        java.util.List<CanvasResponse> canvases = canvasService.listByWorkspace(workspaceId);
        return Result.success(canvases);
    }

    /**
     * 创建画布
     */
    @PostMapping
    @RequireWorkspaceMember(minRole = WorkspaceRole.MEMBER)
    public Result<CanvasResponse> createCanvas(@RequestBody @Valid CreateCanvasRequest request) {
        String workspaceId = UserContextHolder.getWorkspaceId();
        String userId = UserContextHolder.getUserId();
        CanvasResponse response = canvasService.createCanvas(request, workspaceId, userId);
        return Result.success(response);
    }

    /**
     * 获取画布基本信息
     */
    @GetMapping("/{canvasId}")
    @RequireWorkspaceMember
    public Result<CanvasResponse> getCanvas(@PathVariable String canvasId) {
        CanvasResponse response = canvasService.getCanvas(canvasId);
        return Result.success(response);
    }

    /**
     * 根据剧本ID获取画布
     */
    @GetMapping("/script/{scriptId}")
    @RequireWorkspaceMember
    public Result<CanvasResponse> getCanvasByScriptId(@PathVariable String scriptId) {
        CanvasResponse response = canvasService.getCanvasByScriptId(scriptId);
        return Result.success(response);
    }

    /**
     * 根据剧本ID获取或创建画布
     */
    @GetMapping("/script/{scriptId}/ensure")
    @RequireWorkspaceMember
    public Result<CanvasResponse> getOrCreateByScriptId(@PathVariable String scriptId) {
        String workspaceId = UserContextHolder.getWorkspaceId();
        String userId = UserContextHolder.getUserId();
        CanvasResponse response = canvasService.getOrCreateByScriptId(scriptId, workspaceId, userId);
        return Result.success(response);
    }

    /**
     * 获取画布完整信息（包含所有节点和边）
     */
    /**
     * 获取画布中的所有节点
     */
    @GetMapping("/{canvasId}/nodes")
    @RequireWorkspaceMember
    public Result<List<CanvasNodeResponse>> listNodesByCanvasId(@PathVariable String canvasId) {
        return Result.success(canvasNodeService.listByCanvasId(canvasId));
    }

    /**
     * 视口查询（性能优化），仅返回指定视口范围内的节点
     */
    @PostMapping("/{canvasId}/nodes/viewport")
    @RequireWorkspaceMember
    public Result<List<CanvasNodeResponse>> getNodesByViewport(
            @PathVariable String canvasId,
            @RequestBody @Valid ViewportQueryRequest request) {
        return Result.success(canvasNodeService.getNodesByViewport(canvasId, request));
    }

    @GetMapping("/{canvasId}/full")
    @RequireWorkspaceMember
    public Result<CanvasFullResponse> getCanvasFull(@PathVariable String canvasId) {
        CanvasFullResponse response = canvasService.getCanvasFull(canvasId);
        return Result.success(response);
    }

    /**
     * 获取视图数据（按视图筛选节点和边）
     */
    @PostMapping("/{canvasId}/view-data")
    @RequireWorkspaceMember
    public Result<ViewDataResponse> getViewData(
            @PathVariable String canvasId,
            @RequestBody(required = false) ViewDataRequest request) {
        if (request == null) {
            request = new ViewDataRequest();
        }
        request.setCanvasId(canvasId);
        ViewDataResponse response = canvasService.getViewData(request);
        return Result.success(response);
    }

    /**
     * 快速获取视图数据（通过 URL 参数）
     */
    @GetMapping("/{canvasId}/view/{viewKey}")
    @RequireWorkspaceMember
    public Result<ViewDataResponse> getViewDataByKey(
            @PathVariable String canvasId,
            @PathVariable String viewKey,
            @RequestParam(defaultValue = "true") Boolean includeEntityDetail) {
        ViewDataRequest request = new ViewDataRequest();
        request.setCanvasId(canvasId);
        request.setViewKey(viewKey.toUpperCase());
        request.setIncludeEntityDetail(includeEntityDetail);
        ViewDataResponse response = canvasService.getViewData(request);
        return Result.success(response);
    }

    /**
     * 更新画布
     */
    @PutMapping("/{canvasId}")
    @RequireWorkspaceMember(minRole = WorkspaceRole.MEMBER)
    public Result<CanvasResponse> updateCanvas(
            @PathVariable String canvasId,
            @RequestBody @Valid UpdateCanvasRequest request) {
        String userId = UserContextHolder.getUserId();
        CanvasResponse response = canvasService.updateCanvas(canvasId, request, userId);
        return Result.success(response);
    }

    /**
     * 更新画布视口
     */
    @PatchMapping("/{canvasId}/viewport")
    @RequireWorkspaceMember(minRole = WorkspaceRole.MEMBER)
    public Result<Void> updateViewport(
            @PathVariable String canvasId,
            @RequestBody Map<String, Object> viewport) {
        String userId = UserContextHolder.getUserId();
        canvasService.updateViewport(canvasId, viewport, userId);
        return Result.success();
    }

    /**
     * 删除画布
     */
    @DeleteMapping("/{canvasId}")
    @RequireWorkspaceMember(minRole = WorkspaceRole.ADMIN)
    public Result<Void> deleteCanvas(@PathVariable String canvasId) {
        String userId = UserContextHolder.getUserId();
        canvasService.deleteCanvas(canvasId, userId);
        return Result.success();
    }

    /**
     * 根据剧本ID删除画布
     */
    @DeleteMapping("/script/{scriptId}")
    @RequireWorkspaceMember(minRole = WorkspaceRole.ADMIN)
    public Result<Void> deleteByScriptId(@PathVariable String scriptId) {
        String userId = UserContextHolder.getUserId();
        canvasService.deleteByScriptId(scriptId, userId);
        return Result.success();
    }

    /**
     * 自动布局
     * @param viewKey 视图键（可选，不传则布局所有节点）
     */
    @PostMapping("/{canvasId}/auto-layout")
    @RequireWorkspaceMember(minRole = WorkspaceRole.MEMBER)
    public Result<CanvasFullResponse> autoLayout(
            @PathVariable String canvasId,
            @RequestParam(required = false) String strategy,
            @RequestParam(required = false) String viewKey) {
        String userId = UserContextHolder.getUserId();
        CanvasFullResponse response = canvasService.autoLayout(canvasId, strategy, viewKey, userId);
        return Result.success(response);
    }

    /**
     * 检查画布是否存在
     */
    @GetMapping("/script/{scriptId}/exists")
    @RequireWorkspaceMember
    public Result<Boolean> existsByScriptId(@PathVariable String scriptId) {
        boolean exists = canvasService.existsByScriptId(scriptId);
        return Result.success(exists);
    }
}
