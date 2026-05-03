package com.actionow.canvas.feign;

import com.actionow.common.core.result.Result;
import com.actionow.common.core.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Asset Feign 客户端降级工厂
 *
 * @author Actionow
 */
@Slf4j
@Component
public class AssetFeignClientFallbackFactory implements FallbackFactory<AssetFeignClient> {

    @Override
    public AssetFeignClient create(Throwable cause) {
        log.error("调用 Project 素材服务失败: {}", cause.getMessage());

        return new AssetFeignClient() {
            @Override
            public Result<Map<String, Object>> getAssetDetail(String workspaceId, String assetId) {
                log.warn("获取素材详情降级: assetId={}", assetId);
                return Result.fail(ResultCode.INTERNAL_ERROR.getCode(), "Project 服务不可用，无法获取素材详情");
            }

            @Override
            public Result<Void> updateFileInfo(String assetId, Map<String, Object> fileInfo) {
                log.warn("更新素材文件信息降级: assetId={}", assetId);
                return Result.fail(ResultCode.INTERNAL_ERROR.getCode(), "Project 服务不可用，无法更新素材文件信息");
            }
        };
    }
}
