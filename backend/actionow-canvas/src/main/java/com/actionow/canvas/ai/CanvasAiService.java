package com.actionow.canvas.ai;

import com.actionow.canvas.dto.node.CanvasNodeResponse;
import com.actionow.canvas.dto.node.CreateNodeRequest;
import com.actionow.canvas.service.CanvasNodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Canvas AI 集成服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CanvasAiService {
    private final CanvasNodeService canvasNodeService;

    /**
     * 创建 AI 生成的便签节点
     */
    public CanvasNodeResponse createAiStickyNote(
            String canvasId,
            String content,
            BigDecimal positionX,
            BigDecimal positionY,
            String workspaceId,
            String userId) {

        CreateNodeRequest request = new CreateNodeRequest();
        request.setCanvasId(canvasId);
        request.setNodeType("STICKY_NOTE");
        request.setContent(Map.of(
                "text", content,
                "color", "#FFEB3B",
                "fontSize", 14,
                "aiGenerated", true
        ));
        request.setPositionX(positionX);
        request.setPositionY(positionY);

        return canvasNodeService.createNode(request, workspaceId, userId);
    }
}
