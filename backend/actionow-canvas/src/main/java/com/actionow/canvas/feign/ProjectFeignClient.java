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
import com.actionow.canvas.dto.feign.EntityRef;
import com.actionow.common.core.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * Project 服务 Feign 客户端
 * Canvas 服务调用 Project 服务获取实体数据
 *
 * @author Actionow
 */
@FeignClient(name = "actionow-project", contextId = "canvasProjectFeignClient",
        path = "/internal/project", fallbackFactory = ProjectFeignClientFallbackFactory.class)
public interface ProjectFeignClient {

    // ==================== 实体创建接口 ====================

    /**
     * 从 Canvas 创建实体
     * 当在 Canvas 中创建节点时，调用此接口同步创建业务实体
     *
     * @param request 创建请求
     * @return 创建的实体信息
     */
    @PostMapping("/entities/create")
    Result<CanvasEntityCreateResponse> createEntity(@RequestBody CanvasEntityCreateRequest request);

    /**
     * 批量创建实体
     * Canvas 批量创建节点时调用，提高吞吐量
     *
     * @param request 批量创建请求
     * @return 批量创建结果
     */
    @PostMapping("/entities/batch-create")
    Result<BatchEntityCreateResponse> batchCreateEntities(@RequestBody BatchEntityCreateRequest request);

    /**
     * 从 Canvas 更新实体
     * 当在 Canvas 中更新节点时，调用此接口同步更新业务实体
     *
     * @param request 更新请求
     * @return 更新后的实体信息
     */
    @PutMapping("/entities/update")
    Result<CanvasEntityUpdateResponse> updateEntity(@RequestBody CanvasEntityUpdateRequest request);

    /**
     * 批量更新实体
     * Canvas 批量更新节点时调用，提高吞吐量
     *
     * @param request 批量更新请求
     * @return 批量更新结果
     */
    @PostMapping("/entities/batch-update")
    Result<BatchEntityUpdateResponse> batchUpdateEntities(@RequestBody BatchEntityUpdateRequest request);

    /**
     * 从 Canvas 删除实体
     * 当在 Canvas 中删除节点时，调用此接口同步删除业务实体
     *
     * @param entityType 实体类型
     * @param entityId   实体ID
     * @return 删除结果
     */
    @DeleteMapping("/entities/{entityType}/{entityId}")
    Result<Void> deleteEntity(@PathVariable("entityType") String entityType,
                              @PathVariable("entityId") String entityId);

    // ==================== 批量查询接口 ====================

    /**
     * 批量获取剧本信息
     *
     * @param ids 剧本ID列表
     * @return 剧本信息列表
     */
    @PostMapping("/scripts/batch-get")
    Result<List<EntityInfo>> batchGetScripts(@RequestBody List<String> ids);

    /**
     * 批量获取章节信息
     *
     * @param ids 章节ID列表
     * @return 章节信息列表
     */
    @PostMapping("/episodes/batch-get")
    Result<List<EntityInfo>> batchGetEpisodes(@RequestBody List<String> ids);

    /**
     * 批量获取分镜信息
     *
     * @param ids 分镜ID列表
     * @return 分镜信息列表
     */
    @PostMapping("/storyboards/batch-get")
    Result<List<EntityInfo>> batchGetStoryboards(@RequestBody List<String> ids);

    /**
     * 批量获取角色信息
     *
     * @param ids 角色ID列表
     * @return 角色信息列表
     */
    @PostMapping("/characters/batch-get")
    Result<List<EntityInfo>> batchGetCharacters(@RequestBody List<String> ids);

    /**
     * 批量获取场景信息
     *
     * @param ids 场景ID列表
     * @return 场景信息列表
     */
    @PostMapping("/scenes/batch-get")
    Result<List<EntityInfo>> batchGetScenes(@RequestBody List<String> ids);

    /**
     * 批量获取道具信息
     *
     * @param ids 道具ID列表
     * @return 道具信息列表
     */
    @PostMapping("/props/batch-get")
    Result<List<EntityInfo>> batchGetProps(@RequestBody List<String> ids);

    /**
     * 批量获取素材信息
     *
     * @param ids 素材ID列表
     * @return 素材信息列表
     */
    @PostMapping("/assets/batch-get")
    Result<List<EntityInfo>> batchGetAssets(@RequestBody List<String> ids);

    /**
     * 批量获取风格信息
     *
     * @param ids 风格ID列表
     * @return 风格信息列表
     */
    @PostMapping("/styles/batch-get")
    Result<List<EntityInfo>> batchGetStyles(@RequestBody List<String> ids);

    // ==================== 容器内容查询接口 ====================

    /**
     * 获取剧本下的所有实体
     *
     * @param scriptId    剧本ID
     * @param entityTypes 实体类型列表 (EPISODE, CHARACTER, SCENE, PROP, ASSET)
     * @return 实体信息列表
     */
    @PostMapping("/script/{scriptId}/entities")
    Result<List<EntityInfo>> getEntitiesByScript(
            @PathVariable("scriptId") String scriptId,
            @RequestBody List<String> entityTypes);

    /**
     * 获取章节下的所有实体
     *
     * @param episodeId   章节ID
     * @param entityTypes 实体类型列表 (STORYBOARD, CHARACTER, SCENE, PROP, ASSET)
     * @return 实体信息列表
     */
    @PostMapping("/episode/{episodeId}/entities")
    Result<List<EntityInfo>> getEntitiesByEpisode(
            @PathVariable("episodeId") String episodeId,
            @RequestBody List<String> entityTypes);

    // ==================== 素材关联查询接口 ====================

    /**
     * 获取角色关联的素材
     *
     * @param characterId 角色ID
     * @return 素材信息列表
     */
    @GetMapping("/character/{characterId}/assets")
    Result<List<EntityInfo>> getAssetsByCharacter(@PathVariable("characterId") String characterId);

    /**
     * 获取场景关联的素材
     *
     * @param sceneId 场景ID
     * @return 素材信息列表
     */
    @GetMapping("/scene/{sceneId}/assets")
    Result<List<EntityInfo>> getAssetsByScene(@PathVariable("sceneId") String sceneId);

    /**
     * 获取道具关联的素材
     *
     * @param propId 道具ID
     * @return 素材信息列表
     */
    @GetMapping("/prop/{propId}/assets")
    Result<List<EntityInfo>> getAssetsByProp(@PathVariable("propId") String propId);

    /**
     * 获取分镜关联的素材
     *
     * @param storyboardId 分镜ID
     * @return 素材信息列表
     */
    @GetMapping("/storyboard/{storyboardId}/assets")
    Result<List<EntityInfo>> getAssetsByStoryboard(@PathVariable("storyboardId") String storyboardId);

    /**
     * 根据实体类型和ID获取关联的素材
     *
     * @param entityType 实体类型
     * @param entityId   实体ID
     * @return 素材信息列表
     */
    @GetMapping("/entity/{entityType}/{entityId}/assets")
    Result<List<EntityInfo>> getRelatedAssets(
            @PathVariable("entityType") String entityType,
            @PathVariable("entityId") String entityId);

    // ==================== 实体素材关联管理接口 ====================

    /**
     * 创建实体-素材关联
     * 当在 Canvas 中创建 ASSET 类型边时，调用此接口同步创建关联记录
     *
     * @param request 创建关联请求
     * @return 创建的关联信息
     */
    @PostMapping("/entity-asset-relations/create")
    Result<EntityAssetRelationResponse> createEntityAssetRelation(
            @RequestBody CreateEntityAssetRelationRequest request);

    /**
     * 批量查询多个实体的关联素材
     * Canvas 画布渲染时 enrich N 个节点用，替代 N 次 single 调用。
     *
     * @param refs 实体引用列表 [{entityType, entityId}, ...]
     * @return key 格式 "{entityType}:{entityId}" → 关联素材列表
     */
    @PostMapping("/entity-assets/batch-get")
    Result<Map<String, List<EntityAssetRelationResponse>>> batchGetRelatedAssets(
            @RequestBody List<EntityRef> refs);
}
