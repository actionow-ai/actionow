package com.actionow.agent.feign;

import com.actionow.common.core.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Project 服务 Feign 客户端
 * 用于调用 actionow-project 模块的 API
 *
 * @author Actionow
 */
@FeignClient(name = "actionow-project", fallbackFactory = ProjectFeignClientFallback.class)
public interface ProjectFeignClient {

    // ==================== Script ====================

    @PostMapping("/scripts")
    Result<Map<String, Object>> createScript(@RequestBody Map<String, Object> request);

    @GetMapping("/scripts/{scriptId}")
    Result<Map<String, Object>> getScript(@PathVariable("scriptId") String scriptId);

    @PutMapping("/scripts/{scriptId}")
    Result<Map<String, Object>> updateScript(@PathVariable("scriptId") String scriptId,
                                              @RequestBody Map<String, Object> request);

    @GetMapping("/scripts")
    Result<List<Map<String, Object>>> listScripts();

    /**
     * 分页搜索剧本，支持按标题/简介/正文/附加信息模糊搜索
     */
    @GetMapping("/scripts/query")
    Result<Map<String, Object>> queryScripts(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "pageNum", required = false) Integer pageNum,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "orderBy", required = false) String orderBy,
            @RequestParam(value = "orderDir", required = false) String orderDir);

    // ==================== Episode ====================

    @PostMapping("/episodes")
    Result<Map<String, Object>> createEpisode(@RequestBody Map<String, Object> request);

    @GetMapping("/episodes/{episodeId}")
    Result<Map<String, Object>> getEpisode(@PathVariable("episodeId") String episodeId);

    @PutMapping("/episodes/{episodeId}")
    Result<Map<String, Object>> updateEpisode(@PathVariable("episodeId") String episodeId,
                                               @RequestBody Map<String, Object> request);

    @GetMapping("/episodes/script/{scriptId}")
    Result<List<Map<String, Object>>> listEpisodesByScript(
            @PathVariable("scriptId") String scriptId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "limit", required = false) Integer limit);

    /** 软删除剧集（deleted=1，可在回收站还原）。 */
    @DeleteMapping("/episodes/{episodeId}")
    Result<Void> deleteEpisode(@PathVariable("episodeId") String episodeId);

    /**
     * 分页搜索剧集
     */
    @GetMapping("/episodes/query")
    Result<Map<String, Object>> queryEpisodes(
            @RequestParam(value = "scriptId", required = false) String scriptId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "pageNum", required = false) Integer pageNum,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "orderBy", required = false) String orderBy,
            @RequestParam(value = "orderDir", required = false) String orderDir);

    // ==================== Storyboard ====================

    @PostMapping("/storyboards")
    Result<Map<String, Object>> createStoryboard(@RequestBody Map<String, Object> request);

    @GetMapping("/storyboards/{storyboardId}")
    Result<Map<String, Object>> getStoryboard(@PathVariable("storyboardId") String storyboardId);

    @PutMapping("/storyboards/{storyboardId}")
    Result<Map<String, Object>> updateStoryboard(@PathVariable("storyboardId") String storyboardId,
                                                  @RequestBody Map<String, Object> request);

    @GetMapping("/storyboards/episode/{episodeId}")
    Result<List<Map<String, Object>>> listStoryboardsByEpisode(
            @PathVariable("episodeId") String episodeId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "limit", required = false) Integer limit);

    /** 软删除分镜（deleted=1，可在回收站还原）。 */
    @DeleteMapping("/storyboards/{storyboardId}")
    Result<Void> deleteStoryboard(@PathVariable("storyboardId") String storyboardId);

    /**
     * 分页搜索分镜
     */
    @GetMapping("/storyboards/query")
    Result<Map<String, Object>> queryStoryboards(
            @RequestParam(value = "scriptId", required = false) String scriptId,
            @RequestParam(value = "episodeId", required = false) String episodeId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "pageNum", required = false) Integer pageNum,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "orderBy", required = false) String orderBy,
            @RequestParam(value = "orderDir", required = false) String orderDir);

    /**
     * 获取分镜的所有实体关系
     * 返回角色、场景、道具、对白的关系数据
     */
    @GetMapping("/entity-relations/storyboard/{storyboardId}/all")
    Result<Map<String, Object>> getStoryboardRelations(
            @PathVariable("storyboardId") String storyboardId);

    // ==================== Character ====================

    @PostMapping("/characters")
    Result<Map<String, Object>> createCharacter(@RequestBody Map<String, Object> request);

    @GetMapping("/characters/{characterId}")
    Result<Map<String, Object>> getCharacter(@PathVariable("characterId") String characterId);

    @PutMapping("/characters/{characterId}")
    Result<Map<String, Object>> updateCharacter(@PathVariable("characterId") String characterId,
                                                 @RequestBody Map<String, Object> request);

    @GetMapping("/characters/available/{scriptId}")
    Result<List<Map<String, Object>>> listAvailableCharacters(
            @PathVariable("scriptId") String scriptId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "limit", required = false) Integer limit);

    /** 软删除角色（deleted=1，可在回收站还原）。 */
    @DeleteMapping("/characters/{characterId}")
    Result<Void> deleteCharacter(@PathVariable("characterId") String characterId);

    /**
     * 分页搜索角色（跨 schema：SYSTEM + WORKSPACE + SCRIPT）
     */
    @GetMapping("/characters/query")
    Result<Map<String, Object>> queryCharacters(
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "scriptId", required = false) String scriptId,
            @RequestParam(value = "characterType", required = false) String characterType,
            @RequestParam(value = "gender", required = false) String gender,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "pageNum", required = false) Integer pageNum,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "orderBy", required = false) String orderBy,
            @RequestParam(value = "orderDir", required = false) String orderDir);

    // ==================== Scene ====================

    @PostMapping("/scenes")
    Result<Map<String, Object>> createScene(@RequestBody Map<String, Object> request);

    @GetMapping("/scenes/{sceneId}")
    Result<Map<String, Object>> getScene(@PathVariable("sceneId") String sceneId);

    @GetMapping("/scenes/available/{scriptId}")
    Result<List<Map<String, Object>>> listAvailableScenes(
            @PathVariable("scriptId") String scriptId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "limit", required = false) Integer limit);

    /**
     * 分页搜索场景（跨 schema：SYSTEM + WORKSPACE + SCRIPT）
     */
    @GetMapping("/scenes/query")
    Result<Map<String, Object>> queryScenes(
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "scriptId", required = false) String scriptId,
            @RequestParam(value = "sceneType", required = false) String sceneType,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "pageNum", required = false) Integer pageNum,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "orderBy", required = false) String orderBy,
            @RequestParam(value = "orderDir", required = false) String orderDir);

    @PutMapping("/scenes/{sceneId}")
    Result<Map<String, Object>> updateScene(@PathVariable("sceneId") String sceneId,
                                             @RequestBody Map<String, Object> request);

    /** 软删除场景（deleted=1，可在回收站还原）。 */
    @DeleteMapping("/scenes/{sceneId}")
    Result<Void> deleteScene(@PathVariable("sceneId") String sceneId);

    // ==================== Prop ====================

    @PostMapping("/props")
    Result<Map<String, Object>> createProp(@RequestBody Map<String, Object> request);

    @GetMapping("/props/{propId}")
    Result<Map<String, Object>> getProp(@PathVariable("propId") String propId);

    @GetMapping("/props/available/{scriptId}")
    Result<List<Map<String, Object>>> listAvailableProps(
            @PathVariable("scriptId") String scriptId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "limit", required = false) Integer limit);

    /**
     * 分页搜索道具（跨 schema：SYSTEM + WORKSPACE + SCRIPT）
     */
    @GetMapping("/props/query")
    Result<Map<String, Object>> queryProps(
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "scriptId", required = false) String scriptId,
            @RequestParam(value = "propType", required = false) String propType,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "pageNum", required = false) Integer pageNum,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "orderBy", required = false) String orderBy,
            @RequestParam(value = "orderDir", required = false) String orderDir);

    @PutMapping("/props/{propId}")
    Result<Map<String, Object>> updateProp(@PathVariable("propId") String propId,
                                            @RequestBody Map<String, Object> request);

    /** 软删除道具（deleted=1，可在回收站还原）。 */
    @DeleteMapping("/props/{propId}")
    Result<Void> deleteProp(@PathVariable("propId") String propId);

    // ==================== Style ====================

    @PostMapping("/styles")
    Result<Map<String, Object>> createStyle(@RequestBody Map<String, Object> request);

    @GetMapping("/styles/{styleId}")
    Result<Map<String, Object>> getStyle(@PathVariable("styleId") String styleId);

    @GetMapping("/styles/available/{scriptId}")
    Result<List<Map<String, Object>>> listAvailableStyles(
            @PathVariable("scriptId") String scriptId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "limit", required = false) Integer limit);

    /**
     * 分页搜索风格（跨 schema：SYSTEM + WORKSPACE + SCRIPT）
     */
    @GetMapping("/styles/query")
    Result<Map<String, Object>> queryStyles(
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "scriptId", required = false) String scriptId,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "pageNum", required = false) Integer pageNum,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "orderBy", required = false) String orderBy,
            @RequestParam(value = "orderDir", required = false) String orderDir);

    @PutMapping("/styles/{styleId}")
    Result<Map<String, Object>> updateStyle(@PathVariable("styleId") String styleId,
                                             @RequestBody Map<String, Object> request);

    /** 软删除风格（deleted=1，可在回收站还原）。 */
    @DeleteMapping("/styles/{styleId}")
    Result<Void> deleteStyle(@PathVariable("styleId") String styleId);

    // ==================== Batch Create ====================

    // ==================== Asset (Public API) ====================

    /**
     * 分页搜索素材，支持按名称/描述/标签/附加信息模糊搜索
     */
    @GetMapping("/assets/query")
    Result<Map<String, Object>> queryAssets(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "scriptId", required = false) String scriptId,
            @RequestParam(value = "assetType", required = false) String assetType,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "generationStatus", required = false) String generationStatus,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size);

    /**
     * 更新素材信息（名称/描述/附加信息）
     */
    @PutMapping("/assets/{assetId}")
    Result<Map<String, Object>> updateAsset(
            @PathVariable("assetId") String assetId,
            @RequestBody Map<String, Object> request);

    // ==================== Batch Create (Internal) ====================

    @PostMapping("/internal/project/episodes/batch-create")
    Result<List<Map<String, Object>>> batchCreateEpisodes(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Script-Id") String scriptId,
            @RequestBody List<Map<String, Object>> requests);

    @PostMapping("/internal/project/storyboards/batch-create")
    Result<List<Map<String, Object>>> batchCreateStoryboards(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-Episode-Id") String episodeId,
            @RequestBody List<Map<String, Object>> requests);

    @PostMapping("/internal/project/characters/batch-create")
    Result<List<Map<String, Object>>> batchCreateCharacters(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @RequestHeader("X-User-Id") String userId,
            @RequestBody List<Map<String, Object>> requests);

    @PostMapping("/internal/project/scenes/batch-create")
    Result<List<Map<String, Object>>> batchCreateScenes(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @RequestHeader("X-User-Id") String userId,
            @RequestBody List<Map<String, Object>> requests);

    @PostMapping("/internal/project/props/batch-create")
    Result<List<Map<String, Object>>> batchCreateProps(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @RequestHeader("X-User-Id") String userId,
            @RequestBody List<Map<String, Object>> requests);

    @PostMapping("/internal/project/styles/batch-create")
    Result<List<Map<String, Object>>> batchCreateStyles(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @RequestHeader("X-User-Id") String userId,
            @RequestBody List<Map<String, Object>> requests);

    // ==================== Unified Batch Query ====================

    /**
     * 统一批量查询多类型实体
     * 适用于获取分镜中引用的所有角色、场景、道具
     *
     * @param request Map 包含: characterIds, sceneIds, propIds, styleIds
     * @return 按类型分组的实体详情
     */
    @PostMapping("/internal/project/entities/batch-query")
    Result<Map<String, List<Map<String, Object>>>> batchQueryEntities(
            @RequestBody Map<String, List<String>> request);

    // ==================== Entity Relation ====================

    /**
     * 创建实体关系
     */
    @PostMapping("/entity-relations")
    Result<Map<String, Object>> createEntityRelation(@RequestBody Map<String, Object> request);

    /**
     * 批量创建实体关系
     */
    @PostMapping("/entity-relations/batch")
    Result<List<Map<String, Object>>> batchCreateEntityRelations(@RequestBody List<Map<String, Object>> requests);

    /**
     * 更新实体关系
     */
    @PutMapping("/entity-relations/{relationId}")
    Result<Map<String, Object>> updateEntityRelation(
            @PathVariable("relationId") String relationId,
            @RequestBody Map<String, Object> request);

    /**
     * 删除实体关系
     */
    @DeleteMapping("/entity-relations/{relationId}")
    Result<Void> deleteEntityRelation(@PathVariable("relationId") String relationId);

    /**
     * 查询源实体的所有关系
     */
    @GetMapping("/entity-relations/source/{sourceType}/{sourceId}")
    Result<List<Map<String, Object>>> listRelationsBySource(
            @PathVariable("sourceType") String sourceType,
            @PathVariable("sourceId") String sourceId);

    /**
     * 查询源实体指定类型的关系
     */
    @GetMapping("/entity-relations/source/{sourceType}/{sourceId}/type/{relationType}")
    Result<List<Map<String, Object>>> listRelationsBySourceAndType(
            @PathVariable("sourceType") String sourceType,
            @PathVariable("sourceId") String sourceId,
            @PathVariable("relationType") String relationType);

    /**
     * 查询目标实体的入向关系
     */
    @GetMapping("/entity-relations/target/{targetType}/{targetId}")
    Result<List<Map<String, Object>>> listRelationsByTarget(
            @PathVariable("targetType") String targetType,
            @PathVariable("targetId") String targetId);

    /**
     * 获取或创建关系（幂等操作）
     * 如果关系已存在则返回现有关系，否则创建新关系
     */
    @PostMapping("/entity-relations/get-or-create")
    Result<Map<String, Object>> getOrCreateEntityRelation(@RequestBody Map<String, Object> request);
}
