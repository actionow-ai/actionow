package com.actionow.canvas.service;

import com.actionow.canvas.dto.canvas.*;
import com.actionow.canvas.dto.view.ViewDataRequest;
import com.actionow.canvas.dto.view.ViewDataResponse;
import com.actionow.canvas.entity.Canvas;

import java.util.List;
import java.util.Map;

/**
 * 画布服务接口
 * 统一主画布模型：1 Script = 1 Canvas
 *
 * @author Actionow
 */
public interface CanvasService {

    /**
     * 获取工作空间的画布列表
     */
    List<CanvasResponse> listByWorkspace(String workspaceId);

    /**
     * 创建画布（与剧本1:1关联）
     */
    CanvasResponse createCanvas(CreateCanvasRequest request, String workspaceId, String userId);

    /**
     * 获取画布
     */
    CanvasResponse getCanvas(String canvasId);

    /**
     * 根据剧本ID获取画布
     */
    CanvasResponse getCanvasByScriptId(String scriptId);

    /**
     * 根据剧本ID获取或创建画布
     */
    CanvasResponse getOrCreateByScriptId(String scriptId, String workspaceId, String userId);

    /**
     * 获取画布完整信息（包含所有节点和边）
     */
    CanvasFullResponse getCanvasFull(String canvasId);

    /**
     * 获取视图数据（按视图筛选节点和边）
     */
    ViewDataResponse getViewData(ViewDataRequest request);

    /**
     * 更新画布
     */
    CanvasResponse updateCanvas(String canvasId, UpdateCanvasRequest request, String userId);

    /**
     * 更新画布视口
     */
    void updateViewport(String canvasId, Map<String, Object> viewport, String userId);

    /**
     * 删除画布
     */
    void deleteCanvas(String canvasId, String userId);

    /**
     * 根据剧本ID删除画布
     */
    void deleteByScriptId(String scriptId, String userId);

    /**
     * 自动布局节点
     * @param viewKey 视图键（可选，不传则布局所有节点）
     */
    CanvasFullResponse autoLayout(String canvasId, String strategy, String viewKey, String userId);

    /**
     * 获取画布实体
     */
    Canvas getCanvasEntity(String canvasId);

    /**
     * 检查画布是否存在
     */
    boolean existsByScriptId(String scriptId);
}
