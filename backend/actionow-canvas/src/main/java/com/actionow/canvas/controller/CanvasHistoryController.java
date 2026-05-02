package com.actionow.canvas.controller;

import com.actionow.canvas.history.Operation;
import com.actionow.canvas.history.OperationHistoryService;
import com.actionow.common.core.result.Result;
import com.actionow.common.security.annotation.RequireWorkspaceMember;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 画布历史控制器
 */
@RestController
@RequestMapping("/canvas")
@RequiredArgsConstructor
public class CanvasHistoryController {
    private final OperationHistoryService historyService;

    @PostMapping("/{canvasId}/undo")
    @RequireWorkspaceMember(minRole = RequireWorkspaceMember.WorkspaceRole.MEMBER)
    public Result<Void> undo(@PathVariable String canvasId) {
        historyService.undo(canvasId);
        return Result.success();
    }

    @PostMapping("/{canvasId}/redo")
    @RequireWorkspaceMember(minRole = RequireWorkspaceMember.WorkspaceRole.MEMBER)
    public Result<Void> redo(@PathVariable String canvasId) {
        historyService.redo(canvasId);
        return Result.success();
    }

    @GetMapping("/{canvasId}/history")
    @RequireWorkspaceMember
    public Result<List<Operation>> getHistory(
            @PathVariable String canvasId,
            @RequestParam(defaultValue = "20") int limit) {
        List<Operation> history = historyService.getHistory(canvasId, limit);
        return Result.success(history);
    }
}
