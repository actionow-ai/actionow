package com.actionow.project.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.actionow.common.core.result.Result;
import com.actionow.common.core.context.UserContextHolder;
import com.actionow.common.security.annotation.IgnoreAuth;
import com.actionow.project.dto.*;
import com.actionow.project.dto.asset.AssetResponse;
import com.actionow.project.dto.asset.CreateAssetRequest;
import com.actionow.project.dto.relation.EntityAssetRelationResponse;
import com.actionow.project.entity.*;
import com.actionow.project.entity.Character;
import com.actionow.project.mapper.*;
import com.actionow.project.security.InternalRateLimit;
import com.actionow.project.service.*;
import com.actionow.project.service.AssetService;
import com.actionow.project.service.EntityAssetRelationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 实体查询内部接口控制器
 * 供 Canvas、AI 等服务 Feign 调用，提供批量查询和容器内容查询
 *
 * @author Actionow
 */
@Slf4j
@RestController
@RequestMapping("/internal/project")
@RequiredArgsConstructor
@IgnoreAuth
@InternalRateLimit(permits = 300, intervalSeconds = 1, keyBy = InternalRateLimit.KeyBy.WORKSPACE)
public class EntityQueryInternalController {

    private final CharacterMapper characterMapper;
    private final SceneMapper sceneMapper;
    private final PropMapper propMapper;
    private final StyleMapper styleMapper;
    private final EpisodeMapper episodeMapper;
    private final StoryboardMapper storyboardMapper;
    private final ScriptMapper scriptMapper;
    private final AssetMapper assetMapper;
    private final AssetService assetService;
    private final EntityAssetRelationService entityAssetRelationService;
    private final EpisodeService episodeService;
    private final StoryboardService storyboardService;
    private final CharacterService characterService;
    private final SceneService sceneService;
    private final PropService propService;
    private final StyleService styleService;
    private final ObjectMapper objectMapper;

    // ==================== 批量查询接口 ====================

    /**
     * 批量获取角色信息
     */
    @PostMapping("/characters/batch-get")
    public Result<List<EntityInfoResponse>> batchGetCharacters(@RequestBody List<String> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Result.success(Collections.emptyList());
        }

        List<Character> characters = characterMapper.selectBatchIds(ids);
        Map<String, String> coverUrlMap = buildCoverUrlMap(
                characters.stream().map(Character::getCoverAssetId).collect(Collectors.toList())
        );
        List<EntityInfoResponse> result = characters.stream()
                .map(c -> EntityInfoResponse.fromCharacter(c, coverUrlMap.get(c.getCoverAssetId())))
                .collect(Collectors.toList());

        return Result.success(result);
    }

    /**
     * 批量获取场景信息
     */
    @PostMapping("/scenes/batch-get")
    public Result<List<EntityInfoResponse>> batchGetScenes(@RequestBody List<String> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Result.success(Collections.emptyList());
        }

        List<Scene> scenes = sceneMapper.selectBatchIds(ids);
        Map<String, String> coverUrlMap = buildCoverUrlMap(
                scenes.stream().map(Scene::getCoverAssetId).collect(Collectors.toList())
        );
        List<EntityInfoResponse> result = scenes.stream()
                .map(s -> EntityInfoResponse.fromScene(s, coverUrlMap.get(s.getCoverAssetId())))
                .collect(Collectors.toList());

        return Result.success(result);
    }

    /**
     * 批量获取道具信息
     */
    @PostMapping("/props/batch-get")
    public Result<List<EntityInfoResponse>> batchGetProps(@RequestBody List<String> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Result.success(Collections.emptyList());
        }

        List<Prop> props = propMapper.selectBatchIds(ids);
        Map<String, String> coverUrlMap = buildCoverUrlMap(
                props.stream().map(Prop::getCoverAssetId).collect(Collectors.toList())
        );
        List<EntityInfoResponse> result = props.stream()
                .map(p -> EntityInfoResponse.fromProp(p, coverUrlMap.get(p.getCoverAssetId())))
                .collect(Collectors.toList());

        return Result.success(result);
    }

    /**
     * 批量获取风格信息
     */
    @PostMapping("/styles/batch-get")
    public Result<List<EntityInfoResponse>> batchGetStyles(@RequestBody List<String> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Result.success(Collections.emptyList());
        }

        List<Style> styles = styleMapper.selectBatchIds(ids);
        Map<String, String> coverUrlMap = buildCoverUrlMap(
                styles.stream().map(Style::getCoverAssetId).collect(Collectors.toList())
        );
        List<EntityInfoResponse> result = styles.stream()
                .map(s -> EntityInfoResponse.fromStyle(s, coverUrlMap.get(s.getCoverAssetId())))
                .collect(Collectors.toList());

        return Result.success(result);
    }

    /**
     * 批量获取剧集信息
     */
    @PostMapping("/episodes/batch-get")
    public Result<List<EntityInfoResponse>> batchGetEpisodes(@RequestBody List<String> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Result.success(Collections.emptyList());
        }

        List<Episode> episodes = episodeMapper.selectBatchIds(ids);
        Map<String, String> coverUrlMap = buildCoverUrlMap(
                episodes.stream().map(Episode::getCoverAssetId).collect(Collectors.toList())
        );
        List<EntityInfoResponse> result = episodes.stream()
                .map(e -> EntityInfoResponse.fromEpisode(e, coverUrlMap.get(e.getCoverAssetId())))
                .collect(Collectors.toList());

        return Result.success(result);
    }

    /**
     * 批量获取分镜信息
     */
    @PostMapping("/storyboards/batch-get")
    public Result<List<EntityInfoResponse>> batchGetStoryboards(@RequestBody List<String> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Result.success(Collections.emptyList());
        }

        List<Storyboard> storyboards = storyboardMapper.selectBatchIds(ids);
        Map<String, String> coverUrlMap = buildCoverUrlMap(
                storyboards.stream().map(Storyboard::getCoverAssetId).collect(Collectors.toList())
        );
        List<EntityInfoResponse> result = storyboards.stream()
                .map(s -> EntityInfoResponse.fromStoryboard(s, coverUrlMap.get(s.getCoverAssetId())))
                .collect(Collectors.toList());

        return Result.success(result);
    }

    /**
     * 批量获取剧本信息
     */
    @PostMapping("/scripts/batch-get")
    public Result<List<EntityInfoResponse>> batchGetScripts(@RequestBody List<String> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Result.success(Collections.emptyList());
        }

        List<Script> scripts = scriptMapper.selectBatchIds(ids);
        Map<String, String> coverUrlMap = buildCoverUrlMap(
                scripts.stream().map(Script::getCoverAssetId).collect(Collectors.toList())
        );
        List<EntityInfoResponse> result = scripts.stream()
                .map(s -> EntityInfoResponse.fromScript(s, coverUrlMap.get(s.getCoverAssetId())))
                .collect(Collectors.toList());

        return Result.success(result);
    }

    /**
     * 批量获取素材详情（完整 AssetResponse，含 url/mimeType/fileSize 等）
     * 供 Agent 模块获取消息附件的完整信息
     */
    @PostMapping("/assets/batch")
    public Result<List<AssetResponse>> batchGetAssetDetails(@RequestBody List<String> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Result.success(Collections.emptyList());
        }
        List<AssetResponse> responses = assetService.batchGet(ids);
        return Result.success(responses);
    }

    /**
     * 批量获取素材信息
     * 返回带预签名 URL 的素材数据
     */
    @PostMapping("/assets/batch-get")
    public Result<List<EntityInfoResponse>> batchGetAssets(@RequestBody List<String> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Result.success(Collections.emptyList());
        }

        log.info("批量获取素材: ids={}, tenantSchema={}, workspaceId={}",
                ids, UserContextHolder.getTenantSchema(), UserContextHolder.getWorkspaceId());

        // 使用 AssetService.batchGet 获取带预签名 URL 的素材
        List<AssetResponse> assetResponses = assetService.batchGet(ids);

        if (assetResponses.size() < ids.size()) {
            log.warn("批量获取素材数量不匹配: 请求={}, 返回={}, tenantSchema={}, workspaceId={}",
                    ids.size(), assetResponses.size(),
                    UserContextHolder.getTenantSchema(), UserContextHolder.getWorkspaceId());
        }

        List<EntityInfoResponse> result = assetResponses.stream()
                .map(EntityInfoResponse::fromAssetResponse)
                .collect(Collectors.toList());

        return Result.success(result);
    }

    // ==================== 单个实体查询接口（AI 模块 DbBinding 使用） ====================

    /**
     * 获取单个角色详情
     */
    @GetMapping("/characters/{characterId}")
    public Result<Map<String, Object>> getCharacter(@PathVariable("characterId") String characterId) {
        log.debug("内部接口获取角色: {}", characterId);
        Character character = characterMapper.selectById(characterId);
        if (character == null || character.getDeleted() != 0) {
            return Result.success(Collections.emptyMap());
        }
        return Result.success(entityToMap(character));
    }

    /**
     * 获取单个场景详情
     */
    @GetMapping("/scenes/{sceneId}")
    public Result<Map<String, Object>> getScene(@PathVariable("sceneId") String sceneId) {
        log.debug("内部接口获取场景: {}", sceneId);
        Scene scene = sceneMapper.selectById(sceneId);
        if (scene == null || scene.getDeleted() != 0) {
            return Result.success(Collections.emptyMap());
        }
        return Result.success(entityToMap(scene));
    }

    /**
     * 获取单个道具详情
     */
    @GetMapping("/props/{propId}")
    public Result<Map<String, Object>> getProp(@PathVariable("propId") String propId) {
        log.debug("内部接口获取道具: {}", propId);
        Prop prop = propMapper.selectById(propId);
        if (prop == null || prop.getDeleted() != 0) {
            return Result.success(Collections.emptyMap());
        }
        return Result.success(entityToMap(prop));
    }

    /**
     * 获取单个风格详情
     */
    @GetMapping("/styles/{styleId}")
    public Result<Map<String, Object>> getStyle(@PathVariable("styleId") String styleId) {
        log.debug("内部接口获取风格: {}", styleId);
        Style style = styleMapper.selectById(styleId);
        if (style == null || style.getDeleted() != 0) {
            return Result.success(Collections.emptyMap());
        }
        return Result.success(entityToMap(style));
    }

    /**
     * 获取单个分镜详情（含关联实体ID）
     */
    @GetMapping("/storyboards/{storyboardId}")
    public Result<Map<String, Object>> getStoryboardDetail(@PathVariable("storyboardId") String storyboardId) {
        log.debug("内部接口获取分镜: {}", storyboardId);
        try {
            StoryboardDetailResponse detail = storyboardService.getById(storyboardId);
            Map<String, Object> result = entityToMap(detail);
            // 提取关联实体ID到顶层字段，方便 Groovy 脚本访问
            if (detail.getScene() != null) {
                result.put("sceneId", detail.getScene().getSceneId());
            }
            if (detail.getCharacters() != null) {
                result.put("characterIds", detail.getCharacters().stream()
                        .map(c -> c.getCharacterId()).collect(Collectors.toList()));
            }
            if (detail.getProps() != null) {
                result.put("propIds", detail.getProps().stream()
                        .map(p -> p.getPropId()).collect(Collectors.toList()));
            }
            return Result.success(result);
        } catch (Exception e) {
            log.warn("获取分镜详情失败: {}", e.getMessage());
            return Result.success(Collections.emptyMap());
        }
    }

    /**
     * 获取剧本下的角色列表
     */
    @GetMapping("/scripts/{scriptId}/characters")
    public Result<List<Map<String, Object>>> getCharactersForScript(@PathVariable("scriptId") String scriptId) {
        log.debug("内部接口获取剧本角色: {}", scriptId);
        List<EntityInfoResponse> entities = getCharactersByScript(scriptId);
        return Result.success(entities.stream().map(this::entityToMap).collect(Collectors.toList()));
    }

    /**
     * 获取剧本下的场景列表
     */
    @GetMapping("/scripts/{scriptId}/scenes")
    public Result<List<Map<String, Object>>> getScenesForScript(@PathVariable("scriptId") String scriptId) {
        log.debug("内部接口获取剧本场景: {}", scriptId);
        List<EntityInfoResponse> entities = getScenesByScript(scriptId);
        return Result.success(entities.stream().map(this::entityToMap).collect(Collectors.toList()));
    }

    /**
     * 获取剧本下的道具列表
     */
    @GetMapping("/scripts/{scriptId}/props")
    public Result<List<Map<String, Object>>> getPropsForScript(@PathVariable("scriptId") String scriptId) {
        log.debug("内部接口获取剧本道具: {}", scriptId);
        List<EntityInfoResponse> entities = getPropsByScript(scriptId);
        return Result.success(entities.stream().map(this::entityToMap).collect(Collectors.toList()));
    }

    /**
     * 获取剧本下的风格列表
     */
    @GetMapping("/scripts/{scriptId}/styles")
    public Result<List<Map<String, Object>>> getStylesForScript(@PathVariable("scriptId") String scriptId) {
        log.debug("内部接口获取剧本风格: {}", scriptId);
        List<EntityInfoResponse> entities = getStylesByScript(scriptId);
        return Result.success(entities.stream().map(this::entityToMap).collect(Collectors.toList()));
    }

    /**
     * 获取剧集下的分镜列表
     */
    @GetMapping("/episodes/{episodeId}/storyboards")
    public Result<List<Map<String, Object>>> getStoryboardsForEpisode(@PathVariable("episodeId") String episodeId) {
        log.debug("内部接口获取剧集分镜: {}", episodeId);
        List<EntityInfoResponse> entities = getStoryboardsByEpisode(episodeId);
        return Result.success(entities.stream().map(this::entityToMap).collect(Collectors.toList()));
    }

    // ==================== 素材创建和查询接口（Agent 模块使用） ====================

    /**
     * 创建素材
     * 供 Agent 模块在发起 AI 生成任务前创建素材记录
     *
     * @param workspaceId 工作空间ID（从请求头获取）
     * @param userId      用户ID（从请求头获取）
     * @param request     创建请求
     * @return 创建的素材信息
     */
    @PostMapping("/assets")
    public Result<AssetResponse> createAsset(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId,
            @RequestBody @Valid CreateAssetRequest request) {

        log.info("内部接口创建素材: workspaceId={}, name={}, assetType={}, source={}",
                workspaceId, request.getName(), request.getAssetType(), request.getSource());

        AssetResponse response = assetService.create(request, workspaceId, userId);
        return Result.success(response);
    }

    /**
     * 获取素材详情
     * 供 Agent 模块查询素材信息（包含预签名URL）
     *
     * @param workspaceId 工作空间ID
     * @param assetId     素材ID
     * @return 素材详细信息
     */
    @GetMapping("/assets/{assetId}")
    public Result<AssetResponse> getAsset(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @PathVariable("assetId") String assetId) {

        log.debug("内部接口获取素材详情: workspaceId={}, assetId={}", workspaceId, assetId);

        return assetService.findById(assetId)
                .map(asset -> Result.success(AssetResponse.fromEntity(asset)))
                .orElse(Result.fail("素材不存在: " + assetId));
    }

    /**
     * 软删除素材（入回收站，可还原）
     * 供 Agent 模块的 batch_delete_assets 工具调用；走 {@link AssetService#delete} 逻辑删除。
     * 不暴露永久删除 / 清空回收站入口给 Agent，避免 LLM 误删后无法恢复。
     */
    @DeleteMapping("/assets/{assetId}")
    public Result<Void> deleteAsset(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId,
            @PathVariable("assetId") String assetId) {

        log.info("内部接口软删除素材: workspaceId={}, userId={}, assetId={}", workspaceId, userId, assetId);
        assetService.delete(assetId, userId);
        return Result.success();
    }

    // ==================== 实体-素材关联查询接口（Agent 模块使用） ====================

    /**
     * 查询实体关联的素材列表
     * 供 Agent 模块查询角色、场景等实体关联的素材，用于后续 AI 生成（如视频生成）
     *
     * @param workspaceId 工作空间ID
     * @param entityType  实体类型 (CHARACTER, SCENE, PROP, STYLE, STORYBOARD)
     * @param entityId    实体ID
     * @return 关联的素材列表
     */
    @GetMapping("/entity-assets")
    public Result<List<EntityAssetRelationResponse>> getEntityAssets(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @RequestParam("entityType") String entityType,
            @RequestParam("entityId") String entityId) {

        log.info("查询实体关联素材: workspaceId={}, entityType={}, entityId={}",
                workspaceId, entityType, entityId);

        List<EntityAssetRelationResponse> relations = entityAssetRelationService.listEntityAssets(
                entityType.toUpperCase(), entityId, workspaceId);
        return Result.success(relations);
    }

    /**
     * 批量查询多个实体的关联素材
     * Canvas 模块画布渲染时一次性 enrich N 个节点，替代 N 次单条查询。
     *
     * @param workspaceId 工作空间ID
     * @param refs        实体引用列表（[{entityType, entityId}, ...]）
     * @return key 格式 "{entityType}:{entityId}" → 关联素材列表
     */
    @PostMapping("/entity-assets/batch-get")
    public Result<java.util.Map<String, List<EntityAssetRelationResponse>>> batchGetEntityAssets(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @RequestBody List<com.actionow.project.dto.relation.EntityRef> refs) {

        log.info("批量查询实体关联素材: workspaceId={}, count={}",
                workspaceId, refs != null ? refs.size() : 0);

        return Result.success(
                entityAssetRelationService.batchListEntityAssets(refs, workspaceId));
    }

    /**
     * 根据关联类型查询实体关联的素材
     *
     * @param workspaceId  工作空间ID
     * @param entityType   实体类型
     * @param entityId     实体ID
     * @param relationType 关联类型 (REFERENCE, OFFICIAL, DRAFT)
     * @return 关联的素材列表
     */
    @GetMapping("/entity-assets/by-type")
    public Result<List<EntityAssetRelationResponse>> getEntityAssetsByType(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @RequestParam("entityType") String entityType,
            @RequestParam("entityId") String entityId,
            @RequestParam("relationType") String relationType) {

        log.info("按类型查询实体关联素材: workspaceId={}, entityType={}, entityId={}, relationType={}",
                workspaceId, entityType, entityId, relationType);

        List<EntityAssetRelationResponse> relations = entityAssetRelationService.listByRelationType(
                entityType.toUpperCase(), entityId, relationType.toUpperCase(), workspaceId);
        return Result.success(relations);
    }

    /**
     * 创建实体-素材关联
     * 供 Agent 模块将生成的素材关联到实体
     *
     * @param workspaceId 工作空间ID
     * @param userId      用户ID
     * @param request     关联请求（entityType, entityId, assetId, relationType）
     * @return 创建的关联信息
     */
    @PostMapping("/entity-assets/relations")
    public Result<EntityAssetRelationResponse> createEntityAssetRelation(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId,
            @RequestBody Map<String, Object> request) {

        String entityType = (String) request.get("entityType");
        String entityId = (String) request.get("entityId");
        String assetId = (String) request.get("assetId");
        String relationType = (String) request.getOrDefault("relationType", "DRAFT");
        String description = (String) request.get("description");
        Integer sequence = request.get("sequence") != null ? ((Number) request.get("sequence")).intValue() : null;

        log.info("内部接口创建实体素材关联: workspaceId={}, entityType={}, entityId={}, assetId={}, relationType={}",
                workspaceId, entityType, entityId, assetId, relationType);

        // 构建请求对象
        var relationRequest = com.actionow.project.dto.relation.CreateEntityAssetRelationRequest.builder()
                .entityType(entityType)
                .entityId(entityId)
                .assetId(assetId)
                .relationType(relationType)
                .description(description)
                .sequence(sequence)
                .build();

        EntityAssetRelationResponse response = entityAssetRelationService.createRelation(relationRequest, workspaceId, userId);
        return Result.success(response);
    }

    /**
     * 批量创建素材
     * 供 Agent 模块一次性创建多个素材记录
     */
    @PostMapping("/assets/batch-create")
    @InternalRateLimit(permits = 30, intervalSeconds = 1, keyBy = InternalRateLimit.KeyBy.WORKSPACE, name = "assets-batch-create")
    public Result<List<AssetResponse>> batchCreateAssets(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId,
            @RequestBody @Valid List<CreateAssetRequest> requests) {

        log.info("批量创建素材: workspaceId={}, count={}", workspaceId, requests.size());

        List<AssetResponse> responses = new java.util.ArrayList<>();
        for (CreateAssetRequest request : requests) {
            responses.add(assetService.create(request, workspaceId, userId));
        }
        return Result.success(responses);
    }

    /**
     * 批量创建实体-素材关联
     * 供 Agent 模块一次性建立多个关联
     */
    @PostMapping("/entity-assets/relations/batch-create")
    @InternalRateLimit(permits = 30, intervalSeconds = 1, keyBy = InternalRateLimit.KeyBy.WORKSPACE, name = "entity-assets-relations-batch-create")
    public Result<List<EntityAssetRelationResponse>> batchCreateEntityAssetRelations(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId,
            @RequestBody List<Map<String, Object>> requests) {

        log.info("批量创建实体素材关联: workspaceId={}, count={}", workspaceId, requests.size());

        List<EntityAssetRelationResponse> responses = new java.util.ArrayList<>();
        for (Map<String, Object> req : requests) {
            var relationRequest = com.actionow.project.dto.relation.CreateEntityAssetRelationRequest.builder()
                    .entityType((String) req.get("entityType"))
                    .entityId((String) req.get("entityId"))
                    .assetId((String) req.get("assetId"))
                    .relationType((String) req.getOrDefault("relationType", "DRAFT"))
                    .description((String) req.get("description"))
                    .sequence(req.get("sequence") != null ? ((Number) req.get("sequence")).intValue() : null)
                    .build();
            responses.add(entityAssetRelationService.createRelation(relationRequest, workspaceId, userId));
        }
        return Result.success(responses);
    }

    // ==================== 批量创建接口（Agent 模块使用） ====================

    @PostMapping("/episodes/batch-create")
    @InternalRateLimit(permits = 20, intervalSeconds = 1, keyBy = InternalRateLimit.KeyBy.WORKSPACE, name = "episodes-batch-create")
    public Result<List<EpisodeDetailResponse>> batchCreateEpisodes(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId,
            @RequestHeader("X-Script-Id") String scriptId,
            @RequestBody @Valid List<CreateEpisodeRequest> requests) {
        log.info("批量创建剧集: scriptId={}, count={}", scriptId, requests.size());
        return Result.success(episodeService.batchCreate(scriptId, requests, userId));
    }

    @PostMapping("/storyboards/batch-create")
    @InternalRateLimit(permits = 20, intervalSeconds = 1, keyBy = InternalRateLimit.KeyBy.WORKSPACE, name = "storyboards-batch-create")
    public Result<List<StoryboardDetailResponse>> batchCreateStoryboards(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId,
            @RequestHeader("X-Episode-Id") String episodeId,
            @RequestBody @Valid List<CreateStoryboardRequest> requests) {
        log.info("批量创建分镜: episodeId={}, count={}", episodeId, requests.size());
        return Result.success(storyboardService.batchCreate(episodeId, requests, userId));
    }

    @PostMapping("/characters/batch-create")
    @InternalRateLimit(permits = 20, intervalSeconds = 1, keyBy = InternalRateLimit.KeyBy.WORKSPACE, name = "characters-batch-create")
    public Result<List<CharacterDetailResponse>> batchCreateCharacters(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId,
            @RequestBody @Valid List<CreateCharacterRequest> requests) {
        log.info("批量创建角色: count={}", requests.size());
        return Result.success(characterService.batchCreate(requests, workspaceId, userId));
    }

    @PostMapping("/scenes/batch-create")
    @InternalRateLimit(permits = 20, intervalSeconds = 1, keyBy = InternalRateLimit.KeyBy.WORKSPACE, name = "scenes-batch-create")
    public Result<List<SceneDetailResponse>> batchCreateScenes(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId,
            @RequestBody @Valid List<CreateSceneRequest> requests) {
        log.info("批量创建场景: count={}", requests.size());
        return Result.success(sceneService.batchCreate(requests, workspaceId, userId));
    }

    @PostMapping("/props/batch-create")
    @InternalRateLimit(permits = 20, intervalSeconds = 1, keyBy = InternalRateLimit.KeyBy.WORKSPACE, name = "props-batch-create")
    public Result<List<PropDetailResponse>> batchCreateProps(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId,
            @RequestBody @Valid List<CreatePropRequest> requests) {
        log.info("批量创建道具: count={}", requests.size());
        return Result.success(propService.batchCreate(requests, workspaceId, userId));
    }

    @PostMapping("/styles/batch-create")
    @InternalRateLimit(permits = 20, intervalSeconds = 1, keyBy = InternalRateLimit.KeyBy.WORKSPACE, name = "styles-batch-create")
    public Result<List<StyleDetailResponse>> batchCreateStyles(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String userId,
            @RequestBody @Valid List<CreateStyleRequest> requests) {
        log.info("批量创建风格: count={}", requests.size());
        return Result.success(styleService.batchCreate(requests, workspaceId, userId));
    }

    // ==================== 容器内容查询接口 ====================

    /**
     * 获取剧本下的所有实体
     * 返回剧本可用的：角色、场景、道具、风格、剧集
     */
    @PostMapping("/script/{scriptId}/entities")
    public Result<List<EntityInfoResponse>> getEntitiesByScript(
            @PathVariable("scriptId") String scriptId,
            @RequestBody List<String> entityTypes) {

        log.info("获取剧本实体, scriptId={}, entityTypes={}", scriptId, entityTypes);

        if (CollectionUtils.isEmpty(entityTypes)) {
            return Result.success(Collections.emptyList());
        }

        List<EntityInfoResponse> result = new ArrayList<>();

        for (String entityType : entityTypes) {
            switch (entityType.toUpperCase()) {
                case "CHARACTER" -> result.addAll(getCharactersByScript(scriptId));
                case "SCENE" -> result.addAll(getScenesByScript(scriptId));
                case "PROP" -> result.addAll(getPropsByScript(scriptId));
                case "STYLE" -> result.addAll(getStylesByScript(scriptId));
                case "EPISODE" -> result.addAll(getEpisodesByScript(scriptId));
                default -> log.warn("未知的实体类型: {}", entityType);
            }
        }

        return Result.success(result);
    }

    /**
     * 获取剧集下的所有实体
     * 返回：分镜
     */
    @PostMapping("/episode/{episodeId}/entities")
    public Result<List<EntityInfoResponse>> getEntitiesByEpisode(
            @PathVariable("episodeId") String episodeId,
            @RequestBody List<String> entityTypes) {

        log.info("获取剧集实体, episodeId={}, entityTypes={}", episodeId, entityTypes);

        if (CollectionUtils.isEmpty(entityTypes)) {
            return Result.success(Collections.emptyList());
        }

        List<EntityInfoResponse> result = new ArrayList<>();

        for (String entityType : entityTypes) {
            if ("STORYBOARD".equalsIgnoreCase(entityType)) {
                result.addAll(getStoryboardsByEpisode(episodeId));
            } else {
                log.warn("剧集画布不支持的实体类型: {}", entityType);
            }
        }

        return Result.success(result);
    }

    // ==================== 私有方法 ====================

    /**
     * 构建 coverAssetId -> coverUrl 的映射
     *
     * @param coverAssetIds 封面素材 ID 列表（可包含 null）
     * @return coverAssetId 到 coverUrl 的映射
     */
    private Map<String, String> buildCoverUrlMap(List<String> coverAssetIds) {
        Set<String> validIds = coverAssetIds.stream()
                .filter(id -> id != null && !id.isEmpty())
                .collect(Collectors.toSet());

        if (validIds.isEmpty()) {
            return Collections.emptyMap();
        }

        try {
            List<AssetResponse> assets = assetService.batchGet(new ArrayList<>(validIds));
            return assets.stream()
                    .collect(Collectors.toMap(
                            AssetResponse::getId,
                            a -> a.getThumbnailUrl() != null ? a.getThumbnailUrl() : a.getFileUrl(),
                            (a, b) -> a
                    ));
        } catch (Exception e) {
            log.warn("批量获取封面素材失败", e);
            return Collections.emptyMap();
        }
    }

    private List<EntityInfoResponse> getCharactersByScript(String scriptId) {
        Episode episode = episodeMapper.selectOne(
                new LambdaQueryWrapper<Episode>()
                        .eq(Episode::getScriptId, scriptId)
                        .eq(Episode::getDeleted, 0)
                        .last("LIMIT 1")
        );

        String workspaceId = episode != null ? episode.getWorkspaceId() : null;

        LambdaQueryWrapper<Character> query = new LambdaQueryWrapper<>();
        query.eq(Character::getDeleted, 0);

        if (workspaceId != null) {
            query.and(w -> w
                    .eq(Character::getWorkspaceId, workspaceId).eq(Character::getScope, "WORKSPACE")
                    .or()
                    .eq(Character::getScriptId, scriptId).eq(Character::getScope, "SCRIPT")
            );
        } else {
            query.eq(Character::getScriptId, scriptId).eq(Character::getScope, "SCRIPT");
        }

        List<Character> characters = characterMapper.selectList(query);
        Map<String, String> coverUrlMap = buildCoverUrlMap(
                characters.stream().map(Character::getCoverAssetId).collect(Collectors.toList())
        );
        return characters.stream()
                .map(c -> EntityInfoResponse.fromCharacter(c, coverUrlMap.get(c.getCoverAssetId())))
                .collect(Collectors.toList());
    }

    private List<EntityInfoResponse> getScenesByScript(String scriptId) {
        Episode episode = episodeMapper.selectOne(
                new LambdaQueryWrapper<Episode>()
                        .eq(Episode::getScriptId, scriptId)
                        .eq(Episode::getDeleted, 0)
                        .last("LIMIT 1")
        );

        String workspaceId = episode != null ? episode.getWorkspaceId() : null;

        LambdaQueryWrapper<Scene> query = new LambdaQueryWrapper<>();
        query.eq(Scene::getDeleted, 0);

        if (workspaceId != null) {
            query.and(w -> w
                    .eq(Scene::getWorkspaceId, workspaceId).eq(Scene::getScope, "WORKSPACE")
                    .or()
                    .eq(Scene::getScriptId, scriptId).eq(Scene::getScope, "SCRIPT")
            );
        } else {
            query.eq(Scene::getScriptId, scriptId).eq(Scene::getScope, "SCRIPT");
        }

        List<Scene> scenes = sceneMapper.selectList(query);
        Map<String, String> coverUrlMap = buildCoverUrlMap(
                scenes.stream().map(Scene::getCoverAssetId).collect(Collectors.toList())
        );
        return scenes.stream()
                .map(s -> EntityInfoResponse.fromScene(s, coverUrlMap.get(s.getCoverAssetId())))
                .collect(Collectors.toList());
    }

    private List<EntityInfoResponse> getPropsByScript(String scriptId) {
        Episode episode = episodeMapper.selectOne(
                new LambdaQueryWrapper<Episode>()
                        .eq(Episode::getScriptId, scriptId)
                        .eq(Episode::getDeleted, 0)
                        .last("LIMIT 1")
        );

        String workspaceId = episode != null ? episode.getWorkspaceId() : null;

        LambdaQueryWrapper<Prop> query = new LambdaQueryWrapper<>();
        query.eq(Prop::getDeleted, 0);

        if (workspaceId != null) {
            query.and(w -> w
                    .eq(Prop::getWorkspaceId, workspaceId).eq(Prop::getScope, "WORKSPACE")
                    .or()
                    .eq(Prop::getScriptId, scriptId).eq(Prop::getScope, "SCRIPT")
            );
        } else {
            query.eq(Prop::getScriptId, scriptId).eq(Prop::getScope, "SCRIPT");
        }

        List<Prop> props = propMapper.selectList(query);
        Map<String, String> coverUrlMap = buildCoverUrlMap(
                props.stream().map(Prop::getCoverAssetId).collect(Collectors.toList())
        );
        return props.stream()
                .map(p -> EntityInfoResponse.fromProp(p, coverUrlMap.get(p.getCoverAssetId())))
                .collect(Collectors.toList());
    }

    private List<EntityInfoResponse> getStylesByScript(String scriptId) {
        Episode episode = episodeMapper.selectOne(
                new LambdaQueryWrapper<Episode>()
                        .eq(Episode::getScriptId, scriptId)
                        .eq(Episode::getDeleted, 0)
                        .last("LIMIT 1")
        );

        String workspaceId = episode != null ? episode.getWorkspaceId() : null;

        LambdaQueryWrapper<Style> query = new LambdaQueryWrapper<>();
        query.eq(Style::getDeleted, 0);

        if (workspaceId != null) {
            query.and(w -> w
                    .eq(Style::getWorkspaceId, workspaceId).eq(Style::getScope, "WORKSPACE")
                    .or()
                    .eq(Style::getScriptId, scriptId).eq(Style::getScope, "SCRIPT")
            );
        } else {
            query.eq(Style::getScriptId, scriptId).eq(Style::getScope, "SCRIPT");
        }

        List<Style> styles = styleMapper.selectList(query);
        Map<String, String> coverUrlMap = buildCoverUrlMap(
                styles.stream().map(Style::getCoverAssetId).collect(Collectors.toList())
        );
        return styles.stream()
                .map(s -> EntityInfoResponse.fromStyle(s, coverUrlMap.get(s.getCoverAssetId())))
                .collect(Collectors.toList());
    }

    private List<EntityInfoResponse> getEpisodesByScript(String scriptId) {
        List<Episode> episodes = episodeMapper.selectByScriptId(scriptId);
        Map<String, String> coverUrlMap = buildCoverUrlMap(
                episodes.stream().map(Episode::getCoverAssetId).collect(Collectors.toList())
        );
        return episodes.stream()
                .map(e -> EntityInfoResponse.fromEpisode(e, coverUrlMap.get(e.getCoverAssetId())))
                .collect(Collectors.toList());
    }

    private List<EntityInfoResponse> getStoryboardsByEpisode(String episodeId) {
        List<Storyboard> storyboards = storyboardMapper.selectByEpisodeId(episodeId);
        Map<String, String> coverUrlMap = buildCoverUrlMap(
                storyboards.stream().map(Storyboard::getCoverAssetId).collect(Collectors.toList())
        );
        return storyboards.stream()
                .map(s -> EntityInfoResponse.fromStoryboard(s, coverUrlMap.get(s.getCoverAssetId())))
                .collect(Collectors.toList());
    }

    /**
     * 将对象转为 Map（内部 Feign 接口返回格式）
     * 使用 Jackson ObjectMapper 进行转换，保持字段名一致
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> entityToMap(Object entity) {
        try {
            return objectMapper.convertValue(entity, Map.class);
        } catch (Exception e) {
            log.warn("实体转 Map 失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ==================== 统一批量查询接口（Agent 模块使用） ====================

    /**
     * 统一批量查询多类型实体
     * 适用于获取分镜中引用的所有角色、场景、道具
     * 一次调用即可获取多种类型的实体详情
     *
     * @param request 包含各类型实体ID列表的请求
     * @return 按类型分组的实体详情
     */
    @PostMapping("/entities/batch-query")
    public Result<Map<String, List<EntityInfoResponse>>> batchQueryEntities(
            @RequestBody Map<String, List<String>> request) {

        log.info("统一批量查询实体: {}", request.keySet());

        Map<String, List<EntityInfoResponse>> result = new HashMap<>();

        // 批量获取角色
        List<String> characterIds = request.get("characterIds");
        if (!CollectionUtils.isEmpty(characterIds)) {
            List<Character> characters = characterMapper.selectBatchIds(characterIds);
            Map<String, String> coverUrlMap = buildCoverUrlMap(
                    characters.stream().map(Character::getCoverAssetId).collect(Collectors.toList())
            );
            result.put("characters", characters.stream()
                    .map(c -> EntityInfoResponse.fromCharacter(c, coverUrlMap.get(c.getCoverAssetId())))
                    .collect(Collectors.toList()));
        }

        // 批量获取场景
        List<String> sceneIds = request.get("sceneIds");
        if (!CollectionUtils.isEmpty(sceneIds)) {
            List<Scene> scenes = sceneMapper.selectBatchIds(sceneIds);
            Map<String, String> coverUrlMap = buildCoverUrlMap(
                    scenes.stream().map(Scene::getCoverAssetId).collect(Collectors.toList())
            );
            result.put("scenes", scenes.stream()
                    .map(s -> EntityInfoResponse.fromScene(s, coverUrlMap.get(s.getCoverAssetId())))
                    .collect(Collectors.toList()));
        }

        // 批量获取道具
        List<String> propIds = request.get("propIds");
        if (!CollectionUtils.isEmpty(propIds)) {
            List<Prop> props = propMapper.selectBatchIds(propIds);
            Map<String, String> coverUrlMap = buildCoverUrlMap(
                    props.stream().map(Prop::getCoverAssetId).collect(Collectors.toList())
            );
            result.put("props", props.stream()
                    .map(p -> EntityInfoResponse.fromProp(p, coverUrlMap.get(p.getCoverAssetId())))
                    .collect(Collectors.toList()));
        }

        // 批量获取风格
        List<String> styleIds = request.get("styleIds");
        if (!CollectionUtils.isEmpty(styleIds)) {
            List<Style> styles = styleMapper.selectBatchIds(styleIds);
            Map<String, String> coverUrlMap = buildCoverUrlMap(
                    styles.stream().map(Style::getCoverAssetId).collect(Collectors.toList())
            );
            result.put("styles", styles.stream()
                    .map(s -> EntityInfoResponse.fromStyle(s, coverUrlMap.get(s.getCoverAssetId())))
                    .collect(Collectors.toList()));
        }

        // 批量获取分镜
        List<String> storyboardIds = request.get("storyboardIds");
        if (!CollectionUtils.isEmpty(storyboardIds)) {
            List<Storyboard> storyboards = storyboardMapper.selectBatchIds(storyboardIds);
            Map<String, String> coverUrlMap = buildCoverUrlMap(
                    storyboards.stream().map(Storyboard::getCoverAssetId).collect(Collectors.toList())
            );
            result.put("storyboards", storyboards.stream()
                    .map(s -> EntityInfoResponse.fromStoryboard(s, coverUrlMap.get(s.getCoverAssetId())))
                    .collect(Collectors.toList()));
        }

        return Result.success(result);
    }

    // ==================== 素材扩展信息和状态更新接口（Agent 模块使用） ====================

    /**
     * 更新素材扩展信息
     * 供 Agent 模块存储 AI 生成参数、重试次数、错误信息等
     *
     * @param workspaceId 工作空间ID
     * @param assetId     素材ID
     * @param extraInfo   扩展信息
     * @return 操作结果
     */
    @PutMapping("/assets/{assetId}/extra-info")
    public Result<Void> updateAssetExtraInfo(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @PathVariable("assetId") String assetId,
            @RequestBody Map<String, Object> extraInfo) {

        log.info("更新素材扩展信息: workspaceId={}, assetId={}", workspaceId, assetId);

        assetService.findById(assetId).ifPresentOrElse(
                asset -> {
                    // 合并扩展信息（不完全覆盖，而是合并）
                    Map<String, Object> existingExtraInfo = asset.getExtraInfo();
                    if (existingExtraInfo == null) {
                        existingExtraInfo = new HashMap<>();
                    }
                    existingExtraInfo.putAll(extraInfo);

                    // 使用 update 方法更新
                    var updateRequest = com.actionow.project.dto.asset.UpdateAssetRequest.builder()
                            .extraInfo(existingExtraInfo)
                            .build();
                    assetService.update(assetId, updateRequest, UserContextHolder.getUserId());
                },
                () -> log.warn("素材不存在: assetId={}", assetId)
        );

        return Result.success();
    }

    /**
     * 更新素材生成状态
     * 供 Agent 模块更新 AI 生成任务状态
     *
     * @param workspaceId 工作空间ID
     * @param assetId     素材ID
     * @param status      生成状态 (DRAFT, GENERATING, COMPLETED, FAILED)
     * @return 操作结果
     */
    @PutMapping("/assets/{assetId}/generation-status")
    public Result<Void> updateGenerationStatus(
            @RequestHeader("X-Workspace-Id") String workspaceId,
            @PathVariable("assetId") String assetId,
            @RequestParam("status") String status) {

        log.info("更新素材生成状态: workspaceId={}, assetId={}, status={}", workspaceId, assetId, status);

        assetService.updateGenerationStatus(assetId, status, UserContextHolder.getUserId());
        return Result.success();
    }
}
