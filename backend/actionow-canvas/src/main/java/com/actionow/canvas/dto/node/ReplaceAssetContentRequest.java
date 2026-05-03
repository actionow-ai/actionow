package com.actionow.canvas.dto.node;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 替换 ASSET 节点内容请求
 * 将 sourceAsset 的文件信息（fileUrl/thumbnailUrl/...）拷贝到节点关联的目标 asset
 *
 * @author Actionow
 */
@Data
public class ReplaceAssetContentRequest {

    /**
     * 提供生成内容的源 asset ID（通常是 inspiration 临时 asset）
     */
    @NotBlank(message = "sourceAssetId 不能为空")
    private String sourceAssetId;
}
