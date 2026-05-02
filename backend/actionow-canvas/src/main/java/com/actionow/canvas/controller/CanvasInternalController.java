package com.actionow.canvas.controller;

import com.actionow.canvas.constant.CanvasConstants;
import com.actionow.canvas.dto.canvas.CanvasResponse;
import com.actionow.canvas.dto.node.CanvasNodeResponse;
import com.actionow.canvas.dto.node.CreateNodeRequest;
import com.actionow.canvas.service.CanvasNodeService;
import com.actionow.canvas.service.CanvasService;
import com.actionow.common.core.result.Result;
import com.actionow.common.security.annotation.IgnoreAuth;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * Canvas 内部接口控制器
 * 供 Project 等服务 Feign 调用，不需要用户认证
 * 统一主画布模型：1 Script = 1 Canvas
 *
 * @author Actionow
 */
@Slf4j
@RestController
@RequestMapping("/internal/canvas")
@RequiredArgsConstructor
@IgnoreAuth
public class CanvasInternalController {

    private final CanvasService canvasService;
    private final CanvasNodeService nodeService;

    /**
     * 初始化剧本画布
     * 创建画布并添加默认剧本节点
     *
     * @param scriptId    剧本ID
     * @param workspaceId 工作空间ID
     * @param scriptName  剧本名称（可选，用于显示）
     * @return 画布信息
     */
    @PostMapping("/script/{scriptId}/init")
    public Result<CanvasResponse> initScriptCanvas(
            @PathVariable String scriptId,
            @RequestParam String workspaceId,
            @RequestParam(required = false) String scriptName) {

        log.info("初始化剧本画布: scriptId={}, workspaceId={}", scriptId, workspaceId);

        CanvasResponse canvas = canvasService.getOrCreateByScriptId(scriptId, workspaceId, null);

        var existingNodes = nodeService.listByEntity(CanvasConstants.EntityType.SCRIPT, scriptId);
        boolean hasScriptNode = existingNodes.stream()
                .anyMatch(node -> canvas.getId().equals(node.getCanvasId()));

        if (!hasScriptNode) {
            CreateNodeRequest nodeRequest = new CreateNodeRequest();
            nodeRequest.setCanvasId(canvas.getId());
            nodeRequest.setEntityType(CanvasConstants.EntityType.SCRIPT);
            nodeRequest.setEntityId(scriptId);
            nodeRequest.setLayer(CanvasConstants.Layer.SCRIPT);
            nodeRequest.setPositionX(BigDecimal.valueOf(300));
            nodeRequest.setPositionY(BigDecimal.valueOf(100));
            nodeRequest.setWidth(BigDecimal.valueOf(CanvasConstants.LayoutDefaults.NODE_WIDTH));
            nodeRequest.setHeight(BigDecimal.valueOf(CanvasConstants.LayoutDefaults.NODE_HEIGHT));
            nodeRequest.setCollapsed(false);
            nodeRequest.setLocked(false);
            nodeRequest.setZIndex(0);

            CanvasNodeResponse node = nodeService.createNode(nodeRequest, workspaceId, null);

            if (scriptName != null && !scriptName.isEmpty()) {
                nodeService.updateCachedInfo(CanvasConstants.EntityType.SCRIPT, scriptId, scriptName, null);
            }

            log.info("剧本节点创建成功: canvasId={}, nodeId={}, scriptId={}",
                    canvas.getId(), node.getId(), scriptId);
        }

        return Result.success(canvas);
    }

    /**
     * 获取或创建剧本画布
     *
     * @param scriptId    剧本ID
     * @param workspaceId 工作空间ID
     * @return 画布信息
     */
    @GetMapping("/script/{scriptId}")
    public Result<CanvasResponse> getOrCreateByScriptId(
            @PathVariable String scriptId,
            @RequestParam String workspaceId) {

        CanvasResponse canvas = canvasService.getOrCreateByScriptId(scriptId, workspaceId, null);
        return Result.success(canvas);
    }

    /**
     * 检查剧本画布是否存在
     *
     * @param scriptId 剧本ID
     * @return 是否存在
     */
    @GetMapping("/script/{scriptId}/exists")
    public Result<Boolean> existsByScriptId(@PathVariable String scriptId) {
        boolean exists = canvasService.existsByScriptId(scriptId);
        return Result.success(exists);
    }

    /**
     * 删除剧本画布
     *
     * @param scriptId 剧本ID
     * @return 操作结果
     */
    @DeleteMapping("/script/{scriptId}")
    public Result<Void> deleteByScriptId(
            @PathVariable String scriptId,
            @RequestParam(required = false) String userId) {
        canvasService.deleteByScriptId(scriptId, userId);
        return Result.success();
    }

    /**
     * 创建实体节点
     * 用于 MQ 同步时创建节点
     */
    @PostMapping("/node")
    public Result<CanvasNodeResponse> createNode(
            @RequestBody CreateNodeRequest request,
            @RequestParam String workspaceId) {

        // 如果没有提供 layer，根据 entityType 自动确定
        if (request.getLayer() == null || request.getLayer().isEmpty()) {
            request.setLayer(CanvasConstants.Layer.fromEntityType(request.getEntityType()));
        }

        CanvasNodeResponse node = nodeService.createNode(request, workspaceId, null);
        return Result.success(node);
    }
}
