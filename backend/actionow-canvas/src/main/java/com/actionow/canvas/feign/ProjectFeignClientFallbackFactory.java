package com.actionow.canvas.feign;

import com.actionow.canvas.dto.feign.BatchEntityCreateRequest;
import com.actionow.canvas.dto.feign.BatchEntityCreateResponse;
import com.actionow.canvas.dto.feign.BatchEntityUpdateRequest;
import com.actionow.canvas.dto.feign.BatchEntityUpdateResponse;
import com.actionow.canvas.dto.feign.CanvasEntityCreateRequest;
import com.actionow.canvas.dto.feign.CanvasEntityCreateResponse;
import com.actionow.canvas.dto.feign.CanvasEntityUpdateRequest;
import com.actionow.canvas.dto.feign.CanvasEntityUpdateResponse;
import com.actionow.canvas.dto.feign.CreateEntityAssetRelationRequest;
import com.actionow.canvas.dto.feign.EntityAssetRelationResponse;
import com.actionow.canvas.dto.feign.EntityInfo;
import com.actionow.common.core.result.Result;
import com.actionow.common.core.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Project Feign 客户端降级工厂
 *
 * @author Actionow
 */
@Slf4j
@Component
public class ProjectFeignClientFallbackFactory implements FallbackFactory<ProjectFeignClient> {

    @Override
    public ProjectFeignClient create(Throwable cause) {
        log.error("调用Project服务失败: {}", cause.getMessage());

        return new ProjectFeignClient() {
            @Override
            public Result<CanvasEntityCreateResponse> createEntity(CanvasEntityCreateRequest request) {
                log.warn("创建实体降级: entityType={}, name={}", request.getEntityType(), request.getName());
                return Result.fail(ResultCode.INTERNAL_ERROR.getCode(), "Project服务不可用，无法创建实体");
            }

            @Override
            public Result<BatchEntityCreateResponse> batchCreateEntities(BatchEntityCreateRequest request) {
                log.warn("批量创建实体降级: count={}", request.getRequests() != null ? request.getRequests().size() : 0);
                return Result.fail(ResultCode.INTERNAL_ERROR.getCode(), "Project服务不可用，无法批量创建实体");
            }

            @Override
            public Result<CanvasEntityUpdateResponse> updateEntity(CanvasEntityUpdateRequest request) {
                log.warn("更新实体降级: entityType={}, entityId={}", request.getEntityType(), request.getEntityId());
                return Result.fail(ResultCode.INTERNAL_ERROR.getCode(), "Project服务不可用，无法更新实体");
            }

            @Override
            public Result<BatchEntityUpdateResponse> batchUpdateEntities(BatchEntityUpdateRequest request) {
                log.warn("批量更新实体降级: count={}", request.getRequests() != null ? request.getRequests().size() : 0);
                return Result.fail(ResultCode.INTERNAL_ERROR.getCode(), "Project服务不可用，无法批量更新实体");
            }

            @Override
            public Result<Void> deleteEntity(String entityType, String entityId) {
                log.warn("删除实体降级: entityType={}, entityId={}", entityType, entityId);
                return Result.fail(ResultCode.INTERNAL_ERROR.getCode(), "Project服务不可用，无法删除实体");
            }

            @Override
            public Result<List<EntityInfo>> batchGetScripts(List<String> ids) {
                log.warn("批量获取剧本降级: ids={}", ids);
                return Result.success(Collections.emptyList());
            }

            @Override
            public Result<List<EntityInfo>> batchGetEpisodes(List<String> ids) {
                log.warn("批量获取章节降级: ids={}", ids);
                return Result.success(Collections.emptyList());
            }

            @Override
            public Result<List<EntityInfo>> batchGetStoryboards(List<String> ids) {
                log.warn("批量获取分镜降级: ids={}", ids);
                return Result.success(Collections.emptyList());
            }

            @Override
            public Result<List<EntityInfo>> batchGetCharacters(List<String> ids) {
                log.warn("批量获取角色降级: ids={}", ids);
                return Result.success(Collections.emptyList());
            }

            @Override
            public Result<List<EntityInfo>> batchGetScenes(List<String> ids) {
                log.warn("批量获取场景降级: ids={}", ids);
                return Result.success(Collections.emptyList());
            }

            @Override
            public Result<List<EntityInfo>> batchGetProps(List<String> ids) {
                log.warn("批量获取道具降级: ids={}", ids);
                return Result.success(Collections.emptyList());
            }

            @Override
            public Result<List<EntityInfo>> batchGetAssets(List<String> ids) {
                log.warn("批量获取素材降级: ids={}", ids);
                return Result.success(Collections.emptyList());
            }

            @Override
            public Result<List<EntityInfo>> batchGetStyles(List<String> ids) {
                log.warn("批量获取风格降级: ids={}", ids);
                return Result.success(Collections.emptyList());
            }

            @Override
            public Result<List<EntityInfo>> getEntitiesByScript(String scriptId, List<String> entityTypes) {
                log.warn("获取剧本实体降级: scriptId={}, entityTypes={}", scriptId, entityTypes);
                return Result.success(Collections.emptyList());
            }

            @Override
            public Result<List<EntityInfo>> getEntitiesByEpisode(String episodeId, List<String> entityTypes) {
                log.warn("获取章节实体降级: episodeId={}, entityTypes={}", episodeId, entityTypes);
                return Result.success(Collections.emptyList());
            }

            @Override
            public Result<List<EntityInfo>> getAssetsByCharacter(String characterId) {
                log.warn("获取角色素材降级: characterId={}", characterId);
                return Result.success(Collections.emptyList());
            }

            @Override
            public Result<List<EntityInfo>> getAssetsByScene(String sceneId) {
                log.warn("获取场景素材降级: sceneId={}", sceneId);
                return Result.success(Collections.emptyList());
            }

            @Override
            public Result<List<EntityInfo>> getAssetsByProp(String propId) {
                log.warn("获取道具素材降级: propId={}", propId);
                return Result.success(Collections.emptyList());
            }

            @Override
            public Result<List<EntityInfo>> getAssetsByStoryboard(String storyboardId) {
                log.warn("获取分镜素材降级: storyboardId={}", storyboardId);
                return Result.success(Collections.emptyList());
            }

            @Override
            public Result<List<EntityInfo>> getRelatedAssets(String entityType, String entityId) {
                log.warn("获取关联素材降级: entityType={}, entityId={}", entityType, entityId);
                return Result.success(Collections.emptyList());
            }

            @Override
            public Result<EntityAssetRelationResponse> createEntityAssetRelation(CreateEntityAssetRelationRequest request) {
                log.warn("创建实体素材关联降级: entityType={}, entityId={}, assetId={}",
                        request.getEntityType(), request.getEntityId(), request.getAssetId());
                return Result.fail(ResultCode.INTERNAL_ERROR.getCode(), "Project服务不可用，无法创建实体素材关联");
            }

            @Override
            public Result<java.util.Map<String, java.util.List<EntityAssetRelationResponse>>>
                    batchGetRelatedAssets(java.util.List<com.actionow.canvas.dto.feign.EntityRef> refs) {
                log.warn("批量获取关联素材降级: count={}", refs != null ? refs.size() : 0);
                return Result.success(java.util.Collections.emptyMap());
            }
        };
    }
}
