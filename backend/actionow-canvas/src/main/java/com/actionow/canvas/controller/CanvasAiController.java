package com.actionow.canvas.controller;

import com.actionow.canvas.ai.CanvasAiService;
import com.actionow.canvas.dto.node.CanvasNodeResponse;
import com.actionow.common.core.context.UserContextHolder;
import com.actionow.common.core.result.Result;
import com.actionow.common.security.annotation.RequireWorkspaceMember;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * Canvas AI 控制器
 */
@RestController
@RequestMapping("/canvas/ai")
@RequiredArgsConstructor
public class CanvasAiController {
    private final CanvasAiService aiService;

    /**
     * 创建 AI 生成的便签
     */
    @PostMapping("/create-ai-sticky-note")
    @RequireWorkspaceMember(minRole = RequireWorkspaceMember.WorkspaceRole.MEMBER)
    public Result<CanvasNodeResponse> createAiStickyNote(@RequestBody CreateAiStickyNoteRequest request) {
        String workspaceId = UserContextHolder.getWorkspaceId();
        String userId = UserContextHolder.getUserId();

        CanvasNodeResponse response = aiService.createAiStickyNote(
                request.getCanvasId(),
                request.getContent(),
                request.getPositionX(),
                request.getPositionY(),
                workspaceId,
                userId
        );

        return Result.success(response);
    }

    @Data
    public static class CreateAiStickyNoteRequest {
        private String canvasId;
        private String content;
        private BigDecimal positionX;
        private BigDecimal positionY;
    }
}
