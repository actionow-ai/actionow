package com.actionow.canvas.feign;

import com.actionow.common.core.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

/**
 * 素材服务 Feign 客户端（Canvas 用）
 * 调用 Project 服务素材内部接口，用于 Canvas AI 生成回填
 *
 * @author Actionow
 */
@FeignClient(name = "actionow-project", contextId = "canvasAssetFeignClient",
        path = "/internal/assets", fallbackFactory = AssetFeignClientFallbackFactory.class)
public interface AssetFeignClient {

    /**
     * 获取素材详情
     */
    @GetMapping("/{assetId}/detail")
    Result<Map<String, Object>> getAssetDetail(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @PathVariable("assetId") String assetId);

    /**
     * 更新素材文件信息
     */
    @PutMapping("/{assetId}/file-info")
    Result<Void> updateFileInfo(
            @PathVariable("assetId") String assetId,
            @RequestBody Map<String, Object> fileInfo);
}
