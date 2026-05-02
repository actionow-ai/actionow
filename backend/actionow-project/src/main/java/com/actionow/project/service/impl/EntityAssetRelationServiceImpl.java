package com.actionow.project.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.actionow.common.core.constant.CommonConstants;
import com.actionow.common.core.exception.BusinessException;
import com.actionow.common.core.id.UuidGenerator;
import com.actionow.common.core.result.ResultCode;
import com.actionow.project.constant.ProjectConstants;
import com.actionow.project.dto.asset.AssetResponse;
import com.actionow.project.dto.relation.*;
import com.actionow.project.entity.EntityAssetRelation;
import com.actionow.project.entity.*;
import com.actionow.project.feign.UserBasicInfo;
import com.actionow.project.mapper.*;
import com.actionow.project.publisher.EntityChangeEventPublisher;
import com.actionow.project.service.*;
import com.actionow.common.mq.publisher.CanvasMessagePublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 实体-素材关联服务实现
 *
 * @author Actionow
 */
@Slf4j
@Service
public class EntityAssetRelationServiceImpl implements EntityAssetRelationService {

    private final EntityAssetRelationMapper entityAssetRelationMapper;
    private final AssetService assetService;
    private final CanvasMessagePublisher canvasMessagePublisher;
    private final EntityChangeEventPublisher entityChangeEventPublisher;
    private final UserInfoHelper userInfoHelper;
    private final CharacterMapper characterMapper;
    private final SceneMapper sceneMapper;
    private final PropMapper propMapper;
    private final StyleMapper styleMapper;
    private final EpisodeMapper episodeMapper;
    private final StoryboardMapper storyboardMapper;
    private final ScriptMapper scriptMapper;

    public EntityAssetRelationServiceImpl(
            EntityAssetRelationMapper entityAssetRelationMapper,
            AssetService assetService,
            CanvasMessagePublisher canvasMessagePublisher,
            EntityChangeEventPublisher entityChangeEventPublisher,
            UserInfoHelper userInfoHelper,
            CharacterMapper characterMapper,
            SceneMapper sceneMapper,
            PropMapper propMapper,
            StyleMapper styleMapper,
            EpisodeMapper episodeMapper,
            StoryboardMapper storyboardMapper,
            ScriptMapper scriptMapper) {
        this.entityAssetRelationMapper = entityAssetRelationMapper;
        this.assetService = assetService;
        this.canvasMessagePublisher = canvasMessagePublisher;
        this.entityChangeEventPublisher = entityChangeEventPublisher;
        this.userInfoHelper = userInfoHelper;
        this.characterMapper = characterMapper;
        this.sceneMapper = sceneMapper;
        this.propMapper = propMapper;
        this.styleMapper = styleMapper;
        this.episodeMapper = episodeMapper;
        this.storyboardMapper = storyboardMapper;
        this.scriptMapper = scriptMapper;
    }

    // ==================== 关联管理 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EntityAssetRelationResponse createRelation(CreateEntityAssetRelationRequest request, String workspaceId, String userId) {
        return createRelation(request, workspaceId, userId, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EntityAssetRelationResponse createRelation(CreateEntityAssetRelationRequest request, String workspaceId, String userId, boolean skipCanvasSync) {
        // 默认关联类型为 DRAFT
        String relationType = StringUtils.hasText(request.getRelationType())
                ? request.getRelationType()
                : ProjectConstants.AssetRelationType.DRAFT;

        // 检查关联是否已存在
        if (existsRelation(request.getEntityType(), request.getEntityId(), request.getAssetId(), relationType)) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "该关联已存在");
        }

        // 验证素材存在
        AssetResponse asset = assetService.getById(request.getAssetId());

        // 创建关联记录
        EntityAssetRelation relation = new EntityAssetRelation();
        relation.setId(UuidGenerator.generateUuidV7());
        relation.setWorkspaceId(workspaceId);
        relation.setEntityType(request.getEntityType());
        relation.setEntityId(request.getEntityId());
        relation.setAssetId(request.getAssetId());
        relation.setRelationType(relationType);
        relation.setDescription(request.getDescription());
        relation.setSequence(request.getSequence() != null ? request.getSequence() : 0);
        relation.setExtraInfo(request.getExtraInfo());
        relation.setCreatedBy(userId);

        entityAssetRelationMapper.insert(relation);

        log.info("创建实体-素材关联成功: relationId={}, entityType={}, entityId={}, assetId={}",
                relation.getId(), request.getEntityType(), request.getEntityId(), request.getAssetId());

        publishAssetRelationChange(request.getEntityType(), request.getEntityId());

        // 获取实体的 scriptId 用于 Canvas 同步
        String scriptId = getEntityScriptId(request.getEntityType(), request.getEntityId());

        // 发布 Canvas 同步消息（创建素材节点和边）
        if (!skipCanvasSync && scriptId != null) {
            publishAssetToCanvas(asset, scriptId, workspaceId, request.getEntityType(), request.getEntityId());
        }

        // 如果是图片素材，且实体封面为空，自动设置封面
        if (ProjectConstants.AssetType.IMAGE.equals(asset.getAssetType())) {
            trySetEntityCover(request.getEntityType(), request.getEntityId(), request.getAssetId(), userId);
        }

        return enrichWithUserInfo(EntityAssetRelationResponse.fromEntity(relation, asset));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<EntityAssetRelationResponse> batchCreateRelations(BatchCreateEntityAssetRelationRequest request, String workspaceId, String userId) {
        List<EntityAssetRelationResponse> results = new ArrayList<>();

        for (BatchCreateEntityAssetRelationRequest.AssetRelationItem item : request.getAssets()) {
            // 默认关联类型为 DRAFT
            String relationType = StringUtils.hasText(item.getRelationType())
                    ? item.getRelationType()
                    : ProjectConstants.AssetRelationType.DRAFT;

            // 检查是否已存在
            if (existsRelation(request.getEntityType(), request.getEntityId(), item.getAssetId(), relationType)) {
                log.warn("跳过已存在的关联: entityType={}, entityId={}, assetId={}, relationType={}",
                        request.getEntityType(), request.getEntityId(), item.getAssetId(), relationType);
                continue;
            }

            // 验证素材存在
            AssetResponse asset;
            try {
                asset = assetService.getById(item.getAssetId());
            } catch (BusinessException e) {
                log.warn("素材不存在，跳过: assetId={}", item.getAssetId());
                continue;
            }

            // 创建关联记录
            EntityAssetRelation relation = new EntityAssetRelation();
            relation.setId(UuidGenerator.generateUuidV7());
            relation.setWorkspaceId(workspaceId);
            relation.setEntityType(request.getEntityType());
            relation.setEntityId(request.getEntityId());
            relation.setAssetId(item.getAssetId());
            relation.setRelationType(relationType);
            relation.setDescription(item.getDescription());
            relation.setSequence(item.getSequence() != null ? item.getSequence() : 0);
            relation.setCreatedBy(userId);

            entityAssetRelationMapper.insert(relation);

            results.add(EntityAssetRelationResponse.fromEntity(relation, asset));
        }

        log.info("批量创建实体-素材关联成功: entityType={}, entityId={}, count={}",
                request.getEntityType(), request.getEntityId(), results.size());

        return results;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRelation(String relationId, String userId) {
        EntityAssetRelation relation = entityAssetRelationMapper.selectById(relationId);
        if (relation == null || relation.getDeleted() == CommonConstants.DELETED) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "关联不存在");
        }

        // 使用 LambdaUpdateWrapper 显式设置 deleted 字段（避免被逻辑删除配置忽略）
        LambdaUpdateWrapper<EntityAssetRelation> deleteWrapper = new LambdaUpdateWrapper<>();
        deleteWrapper.eq(EntityAssetRelation::getId, relationId)
                .eq(EntityAssetRelation::getDeleted, CommonConstants.NOT_DELETED)
                .set(EntityAssetRelation::getDeleted, CommonConstants.DELETED);
        entityAssetRelationMapper.update(null, deleteWrapper);

        log.info("删除实体-素材关联成功: relationId={}", relationId);

        publishAssetRelationChange(relation.getEntityType(), relation.getEntityId());

        // 如果删除的是封面素材，自动更新封面
        tryUpdateEntityCoverAfterDelete(relation.getEntityType(), relation.getEntityId(), relation.getAssetId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRelation(String entityType, String entityId, String assetId, String relationType, String userId) {
        LambdaUpdateWrapper<EntityAssetRelation> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(EntityAssetRelation::getEntityType, entityType)
                .eq(EntityAssetRelation::getEntityId, entityId)
                .eq(EntityAssetRelation::getAssetId, assetId)
                .eq(EntityAssetRelation::getRelationType, relationType)
                .eq(EntityAssetRelation::getDeleted, CommonConstants.NOT_DELETED)
                .set(EntityAssetRelation::getDeleted, CommonConstants.DELETED);

        int affected = entityAssetRelationMapper.update(null, wrapper);
        if (affected == 0) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "关联不存在");
        }

        log.info("删除实体-素材关联成功: entityType={}, entityId={}, assetId={}, relationType={}",
                entityType, entityId, assetId, relationType);

        publishAssetRelationChange(entityType, entityId);

        // 如果删除的是封面素材，自动更新封面
        tryUpdateEntityCoverAfterDelete(entityType, entityId, assetId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EntityAssetRelationResponse updateRelationType(String relationId, String relationType, String userId) {
        // 查询关联记录
        EntityAssetRelation relation = entityAssetRelationMapper.selectById(relationId);
        if (relation == null || relation.getDeleted() == CommonConstants.DELETED) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "关联不存在");
        }

        // 更新关联类型
        relation.setRelationType(relationType);
        entityAssetRelationMapper.updateById(relation);

        log.info("更新实体-素材关联类型成功: relationId={}, newRelationType={}", relationId, relationType);

        publishAssetRelationChange(relation.getEntityType(), relation.getEntityId());

        // 获取素材详情
        AssetResponse asset = null;
        try {
            asset = assetService.getById(relation.getAssetId());
        } catch (BusinessException e) {
            log.warn("素材不存在: assetId={}", relation.getAssetId());
        }

        return enrichWithUserInfo(EntityAssetRelationResponse.fromEntity(relation, asset));
    }

    // ==================== 查询接口 ====================

    @Override
    public Page<EntityAssetRelationResponse> queryEntityAssets(EntityAssetQueryRequest request, String workspaceId) {
        int pageNum = request.getPage() != null ? request.getPage() : 1;
        int pageSize = request.getSize() != null ? request.getSize() : 20;
        Page<EntityAssetRelation> page = new Page<>(pageNum, pageSize);

        // 构建查询条件
        LambdaQueryWrapper<EntityAssetRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EntityAssetRelation::getWorkspaceId, workspaceId)
                .eq(EntityAssetRelation::getEntityType, request.getEntityType())
                .eq(EntityAssetRelation::getEntityId, request.getEntityId())
                .eq(EntityAssetRelation::getDeleted, CommonConstants.NOT_DELETED);

        // 关联类型过滤
        if (request.getRelationTypes() != null && !request.getRelationTypes().isEmpty()) {
            wrapper.in(EntityAssetRelation::getRelationType, request.getRelationTypes());
        }

        // 排序
        wrapper.orderByAsc(EntityAssetRelation::getSequence)
                .orderByDesc(EntityAssetRelation::getCreatedAt);

        // 执行分页查询
        Page<EntityAssetRelation> relationPage = entityAssetRelationMapper.selectPage(page, wrapper);

        // 获取素材ID列表
        List<String> assetIds = relationPage.getRecords().stream()
                .map(EntityAssetRelation::getAssetId)
                .distinct()
                .collect(Collectors.toList());

        // 批量获取素材详情
        Map<String, AssetResponse> assetMap = Collections.emptyMap();
        if (!assetIds.isEmpty()) {
            List<AssetResponse> assets = assetService.batchGet(assetIds);
            assetMap = assets.stream()
                    .collect(Collectors.toMap(AssetResponse::getId, Function.identity(), (a, b) -> a));
        }

        // 素材类型过滤和关键词过滤
        final Map<String, AssetResponse> finalAssetMap = assetMap;
        List<EntityAssetRelationResponse> filteredRecords = relationPage.getRecords().stream()
                .map(relation -> {
                    AssetResponse asset = finalAssetMap.get(relation.getAssetId());
                    return EntityAssetRelationResponse.fromEntity(relation, asset);
                })
                .filter(response -> {
                    if (response.getAsset() == null) {
                        return false;
                    }
                    // 素材类型过滤
                    if (request.getAssetTypes() != null && !request.getAssetTypes().isEmpty()) {
                        if (!request.getAssetTypes().contains(response.getAsset().getAssetType())) {
                            return false;
                        }
                    }
                    // 关键词过滤
                    if (StringUtils.hasText(request.getKeyword())) {
                        String keyword = request.getKeyword().toLowerCase();
                        String name = response.getAsset().getName();
                        String description = response.getAsset().getDescription();
                        boolean nameMatch = name != null && name.toLowerCase().contains(keyword);
                        boolean descMatch = description != null && description.toLowerCase().contains(keyword);
                        return nameMatch || descMatch;
                    }
                    return true;
                })
                .collect(Collectors.toList());

        // 填充用户信息
        enrichWithUserInfo(filteredRecords);

        // 构建响应
        Page<EntityAssetRelationResponse> responsePage = new Page<>(relationPage.getCurrent(), relationPage.getSize(), relationPage.getTotal());
        responsePage.setRecords(filteredRecords);

        return responsePage;
    }

    @Override
    public List<EntityAssetRelationResponse> listEntityAssets(String entityType, String entityId, String workspaceId) {
        List<EntityAssetRelation> relations = entityAssetRelationMapper.selectByEntity(entityType, entityId);

        if (relations.isEmpty()) {
            return Collections.emptyList();
        }

        // 批量获取素材详情
        List<String> assetIds = relations.stream()
                .map(EntityAssetRelation::getAssetId)
                .distinct()
                .collect(Collectors.toList());
        List<AssetResponse> assets = assetService.batchGet(assetIds);
        Map<String, AssetResponse> assetMap = assets.stream()
                .collect(Collectors.toMap(AssetResponse::getId, Function.identity(), (a, b) -> a));

        List<EntityAssetRelationResponse> responses = relations.stream()
                .map(relation -> EntityAssetRelationResponse.fromEntity(relation, assetMap.get(relation.getAssetId())))
                .collect(Collectors.toList());
        return enrichWithUserInfo(responses);
    }

    @Override
    public Map<String, List<EntityAssetRelationResponse>> batchListEntityAssets(
            List<EntityRef> refs, String workspaceId) {
        if (refs == null || refs.isEmpty()) {
            return Collections.emptyMap();
        }

        // 用 LambdaQueryWrapper 拼 OR (eq AND eq) 一次性拉取所有 (entityType, entityId) 的关联
        LambdaQueryWrapper<EntityAssetRelation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EntityAssetRelation::getDeleted, CommonConstants.NOT_DELETED);
        wrapper.and(outer -> {
            for (int i = 0; i < refs.size(); i++) {
                EntityRef ref = refs.get(i);
                if (i == 0) {
                    outer.and(c -> c
                            .eq(EntityAssetRelation::getEntityType, ref.getEntityType())
                            .eq(EntityAssetRelation::getEntityId, ref.getEntityId()));
                } else {
                    outer.or(c -> c
                            .eq(EntityAssetRelation::getEntityType, ref.getEntityType())
                            .eq(EntityAssetRelation::getEntityId, ref.getEntityId()));
                }
            }
        });
        List<EntityAssetRelation> relations = entityAssetRelationMapper.selectList(wrapper);

        if (relations.isEmpty()) {
            return Collections.emptyMap();
        }

        // 一次性拉所有 asset 详情
        List<String> assetIds = relations.stream()
                .map(EntityAssetRelation::getAssetId)
                .distinct()
                .collect(Collectors.toList());
        Map<String, AssetResponse> assetMap = assetService.batchGet(assetIds).stream()
                .collect(Collectors.toMap(AssetResponse::getId, Function.identity(), (a, b) -> a));

        // 按 (entityType, entityId) 分组
        Map<String, List<EntityAssetRelationResponse>> result = new HashMap<>();
        for (EntityAssetRelation r : relations) {
            String key = r.getEntityType() + ":" + r.getEntityId();
            result.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(EntityAssetRelationResponse.fromEntity(r, assetMap.get(r.getAssetId())));
        }
        return result;
    }

    @Override
    public List<EntityAssetRelationResponse> listByRelationType(String entityType, String entityId, String relationType, String workspaceId) {
        List<EntityAssetRelation> relations = entityAssetRelationMapper.selectByEntityAndRelationType(entityType, entityId, relationType);

        if (relations.isEmpty()) {
            return Collections.emptyList();
        }

        // 批量获取素材详情
        List<String> assetIds = relations.stream()
                .map(EntityAssetRelation::getAssetId)
                .distinct()
                .collect(Collectors.toList());
        List<AssetResponse> assets = assetService.batchGet(assetIds);
        Map<String, AssetResponse> assetMap = assets.stream()
                .collect(Collectors.toMap(AssetResponse::getId, Function.identity(), (a, b) -> a));

        List<EntityAssetRelationResponse> responses = relations.stream()
                .map(relation -> EntityAssetRelationResponse.fromEntity(relation, assetMap.get(relation.getAssetId())))
                .collect(Collectors.toList());
        return enrichWithUserInfo(responses);
    }

    @Override
    public List<EntityAssetRelationResponse> listAssetRelations(String assetId, String workspaceId) {
        List<EntityAssetRelation> relations = entityAssetRelationMapper.selectByAssetId(assetId);

        if (relations.isEmpty()) {
            return Collections.emptyList();
        }

        // 获取素材详情
        AssetResponse asset = null;
        try {
            asset = assetService.getById(assetId);
        } catch (BusinessException e) {
            log.warn("素材不存在: assetId={}", assetId);
        }

        final AssetResponse finalAsset = asset;
        List<EntityAssetRelationResponse> responses = relations.stream()
                .map(relation -> EntityAssetRelationResponse.fromEntity(relation, finalAsset))
                .collect(Collectors.toList());
        return enrichWithUserInfo(responses);
    }

    // ==================== 统计接口 ====================

    @Override
    public EntityAssetSummaryResponse getEntityAssetSummary(String entityType, String entityId, String workspaceId) {
        List<EntityAssetRelation> relations = entityAssetRelationMapper.selectByEntity(entityType, entityId);

        if (relations.isEmpty()) {
            return EntityAssetSummaryResponse.builder()
                    .entityType(entityType)
                    .entityId(entityId)
                    .totalCount(0)
                    .countByAssetType(Collections.emptyMap())
                    .countByRelationType(Collections.emptyMap())
                    .build();
        }

        // 获取素材详情用于按类型统计
        List<String> assetIds = relations.stream()
                .map(EntityAssetRelation::getAssetId)
                .distinct()
                .collect(Collectors.toList());
        List<AssetResponse> assets = assetService.batchGet(assetIds);
        Map<String, AssetResponse> assetMap = assets.stream()
                .collect(Collectors.toMap(AssetResponse::getId, Function.identity(), (a, b) -> a));

        // 按素材类型统计
        Map<String, Integer> countByAssetType = new HashMap<>();
        for (EntityAssetRelation relation : relations) {
            AssetResponse asset = assetMap.get(relation.getAssetId());
            if (asset != null && asset.getAssetType() != null) {
                countByAssetType.merge(asset.getAssetType(), 1, Integer::sum);
            }
        }

        // 按关联类型统计
        Map<String, Integer> countByRelationType = relations.stream()
                .collect(Collectors.groupingBy(
                        EntityAssetRelation::getRelationType,
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
                ));

        return EntityAssetSummaryResponse.builder()
                .entityType(entityType)
                .entityId(entityId)
                .totalCount(relations.size())
                .countByAssetType(countByAssetType)
                .countByRelationType(countByRelationType)
                .build();
    }

    // ==================== 便捷方法 ====================

    @Override
    public boolean existsRelation(String entityType, String entityId, String assetId, String relationType) {
        int count = entityAssetRelationMapper.existsRelation(entityType, entityId, assetId, relationType);
        return count > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setAsOfficial(String entityType, String entityId, String assetId, String userId) {
        // 将该实体的其他同类型素材设为草稿
        LambdaUpdateWrapper<EntityAssetRelation> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(EntityAssetRelation::getEntityType, entityType)
                .eq(EntityAssetRelation::getEntityId, entityId)
                .eq(EntityAssetRelation::getRelationType, ProjectConstants.AssetRelationType.OFFICIAL)
                .eq(EntityAssetRelation::getDeleted, CommonConstants.NOT_DELETED)
                .set(EntityAssetRelation::getRelationType, ProjectConstants.AssetRelationType.DRAFT);
        entityAssetRelationMapper.update(null, wrapper);

        // 将指定素材设为正式
        LambdaUpdateWrapper<EntityAssetRelation> setOfficialWrapper = new LambdaUpdateWrapper<>();
        setOfficialWrapper.eq(EntityAssetRelation::getEntityType, entityType)
                .eq(EntityAssetRelation::getEntityId, entityId)
                .eq(EntityAssetRelation::getAssetId, assetId)
                .eq(EntityAssetRelation::getDeleted, CommonConstants.NOT_DELETED)
                .set(EntityAssetRelation::getRelationType, ProjectConstants.AssetRelationType.OFFICIAL);
        int affected = entityAssetRelationMapper.update(null, setOfficialWrapper);

        if (affected == 0) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "关联不存在");
        }

        log.info("设置素材为正式素材成功: entityType={}, entityId={}, assetId={}",
                entityType, entityId, assetId);

        publishAssetRelationChange(entityType, entityId);
    }

    // ==================== 挂载/解挂 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EntityAssetRelationResponse mountAsset(MountAssetRequest request, String workspaceId, String userId) {
        CreateEntityAssetRelationRequest createRequest = CreateEntityAssetRelationRequest.builder()
                .entityType(request.getEntityType())
                .entityId(request.getEntityId())
                .assetId(request.getAssetId())
                .relationType(request.getRelationType())
                .description(request.getDescription())
                .sequence(request.getSequence())
                .build();
        return createRelation(createRequest, workspaceId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unmountAsset(UnmountAssetRequest request, String userId) {
        if (StringUtils.hasText(request.getRelationType())) {
            // 按指定关联类型删除
            deleteRelation(request.getEntityType(), request.getEntityId(),
                    request.getAssetId(), request.getRelationType(), userId);
        } else {
            // 删除该组合下所有关联
            LambdaUpdateWrapper<EntityAssetRelation> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(EntityAssetRelation::getEntityType, request.getEntityType())
                    .eq(EntityAssetRelation::getEntityId, request.getEntityId())
                    .eq(EntityAssetRelation::getAssetId, request.getAssetId())
                    .eq(EntityAssetRelation::getDeleted, CommonConstants.NOT_DELETED)
                    .set(EntityAssetRelation::getDeleted, CommonConstants.DELETED);

            int affected = entityAssetRelationMapper.update(null, wrapper);
            if (affected == 0) {
                throw new BusinessException(ResultCode.PARAM_INVALID, "关联不存在");
            }

            log.info("解挂素材成功: entityType={}, entityId={}, assetId={}, affectedCount={}",
                    request.getEntityType(), request.getEntityId(), request.getAssetId(), affected);

            // 如果删除的是封面素材，自动更新封面
            tryUpdateEntityCoverAfterDelete(request.getEntityType(), request.getEntityId(), request.getAssetId());
        }
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 对关联实体发布 UPDATED 事件（changedFields=["assets"]）
     */
    private void publishAssetRelationChange(String entityType, String entityId) {
        try {
            String scriptId = getEntityScriptId(entityType, entityId);
            entityChangeEventPublisher.publishEntityUpdated(
                    entityType, entityId, scriptId, List.of("assets"), null);
        } catch (Exception e) {
            log.warn("发布素材关联变更通知失败: entityType={}, entityId={}", entityType, entityId, e);
        }
    }

    /**
     * 根据实体类型和ID获取所属剧本ID
     */
    private String getEntityScriptId(String entityType, String entityId) {
        try {
            switch (entityType) {
                case ProjectConstants.EntityType.SCRIPT -> {
                    return entityId; // Script 本身就是 scriptId
                }
                case ProjectConstants.EntityType.EPISODE -> {
                    Episode episode = episodeMapper.selectById(entityId);
                    return episode != null ? episode.getScriptId() : null;
                }
                case ProjectConstants.EntityType.STORYBOARD -> {
                    Storyboard storyboard = storyboardMapper.selectById(entityId);
                    return storyboard != null ? storyboard.getScriptId() : null;
                }
                case ProjectConstants.EntityType.CHARACTER -> {
                    com.actionow.project.entity.Character character = characterMapper.selectById(entityId);
                    return character != null ? character.getScriptId() : null;
                }
                case ProjectConstants.EntityType.SCENE -> {
                    Scene scene = sceneMapper.selectById(entityId);
                    return scene != null ? scene.getScriptId() : null;
                }
                case ProjectConstants.EntityType.PROP -> {
                    Prop prop = propMapper.selectById(entityId);
                    return prop != null ? prop.getScriptId() : null;
                }
                case ProjectConstants.EntityType.STYLE -> {
                    Style style = styleMapper.selectById(entityId);
                    return style != null ? style.getScriptId() : null;
                }
                default -> {
                    log.warn("未知实体类型，无法获取scriptId: entityType={}", entityType);
                    return null;
                }
            }
        } catch (Exception e) {
            log.error("获取实体scriptId失败: entityType={}, entityId={}", entityType, entityId, e);
            return null;
        }
    }

    /**
     * 发布素材创建消息到 Canvas，创建节点和边
     */
    private void publishAssetToCanvas(AssetResponse asset, String scriptId, String workspaceId,
                                       String relatedEntityType, String relatedEntityId) {
        try {
            // 构建素材实体数据
            Map<String, Object> entityData = new HashMap<>();
            entityData.put("name", asset.getName());
            entityData.put("description", asset.getDescription());
            entityData.put("assetType", asset.getAssetType());
            entityData.put("fileUrl", asset.getFileUrl());
            entityData.put("thumbnailUrl", asset.getThumbnailUrl());

            // 构建关联实体（用于创建边）
            List<CanvasMessagePublisher.RelatedEntity> relatedEntities = List.of(
                    CanvasMessagePublisher.RelatedEntity.builder()
                            .entityType(relatedEntityType)
                            .entityId(relatedEntityId)
                            .relationType("has_asset")
                            .build()
            );

            // 发布消息
            canvasMessagePublisher.publishEntityChange(
                    ProjectConstants.EntityType.ASSET,
                    asset.getId(),
                    scriptId,
                    relatedEntityType,  // 父实体类型为关联的实体
                    relatedEntityId,    // 父实体ID
                    workspaceId,
                    ProjectConstants.ChangeType.CREATED,
                    entityData,
                    relatedEntities,
                    false
            );

            log.info("发布素材Canvas同步消息成功: assetId={}, scriptId={}, relatedEntity={}:{}",
                    asset.getId(), scriptId, relatedEntityType, relatedEntityId);
        } catch (Exception e) {
            // Canvas 同步失败不影响主流程
            log.error("发布素材Canvas同步消息失败: assetId={}, scriptId={}", asset.getId(), scriptId, e);
        }
    }

    /**
     * 尝试设置实体封面（如果当前封面为空）
     */
    private void trySetEntityCover(String entityType, String entityId, String assetId, String userId) {
        try {
            switch (entityType) {
                case ProjectConstants.EntityType.CHARACTER -> {
                    com.actionow.project.entity.Character character = characterMapper.selectById(entityId);
                    if (character != null && character.getCoverAssetId() == null) {
                        character.setCoverAssetId(assetId);
                        characterMapper.updateById(character);
                        log.info("自动设置角色封面: characterId={}, assetId={}", entityId, assetId);
                    }
                }
                case ProjectConstants.EntityType.SCENE -> {
                    Scene scene = sceneMapper.selectById(entityId);
                    if (scene != null && scene.getCoverAssetId() == null) {
                        scene.setCoverAssetId(assetId);
                        sceneMapper.updateById(scene);
                        log.info("自动设置场景封面: sceneId={}, assetId={}", entityId, assetId);
                    }
                }
                case ProjectConstants.EntityType.PROP -> {
                    Prop prop = propMapper.selectById(entityId);
                    if (prop != null && prop.getCoverAssetId() == null) {
                        prop.setCoverAssetId(assetId);
                        propMapper.updateById(prop);
                        log.info("自动设置道具封面: propId={}, assetId={}", entityId, assetId);
                    }
                }
                case ProjectConstants.EntityType.STYLE -> {
                    Style style = styleMapper.selectById(entityId);
                    if (style != null && style.getCoverAssetId() == null) {
                        style.setCoverAssetId(assetId);
                        styleMapper.updateById(style);
                        log.info("自动设置风格封面: styleId={}, assetId={}", entityId, assetId);
                    }
                }
                case ProjectConstants.EntityType.EPISODE -> {
                    Episode episode = episodeMapper.selectById(entityId);
                    if (episode != null && episode.getCoverAssetId() == null) {
                        episode.setCoverAssetId(assetId);
                        episodeMapper.updateById(episode);
                        log.info("自动设置章节封面: episodeId={}, assetId={}", entityId, assetId);
                    }
                }
                case ProjectConstants.EntityType.SCRIPT -> {
                    Script script = scriptMapper.selectById(entityId);
                    if (script != null && script.getCoverAssetId() == null) {
                        script.setCoverAssetId(assetId);
                        scriptMapper.updateById(script);
                        log.info("自动设置剧本封面: scriptId={}, assetId={}", entityId, assetId);
                    }
                }
                case ProjectConstants.EntityType.STORYBOARD -> {
                    // 分镜暂不设置封面，因为分镜通常有多个生成的图片
                    log.debug("跳过分镜封面设置: storyboardId={}", entityId);
                }
                default -> log.debug("未知实体类型，跳过封面设置: entityType={}", entityType);
            }
        } catch (Exception e) {
            // 封面设置失败不影响主流程
            log.warn("自动设置实体封面失败: entityType={}, entityId={}, assetId={}", entityType, entityId, assetId, e);
        }
    }

    /**
     * 删除关联后尝试更新实体封面
     * 如果被删除的素材是实体的封面，则更新为下一个图片素材，如果没有则置空
     */
    private void tryUpdateEntityCoverAfterDelete(String entityType, String entityId, String deletedAssetId) {
        try {
            // 获取实体当前的封面 assetId
            String currentCoverAssetId = getEntityCoverAssetId(entityType, entityId);

            // 如果删除的不是封面素材，无需处理
            if (currentCoverAssetId == null || !currentCoverAssetId.equals(deletedAssetId)) {
                return;
            }

            // 查找下一个图片素材作为新封面
            String newCoverAssetId = findNextImageAsset(entityType, entityId);

            // 更新实体封面
            updateEntityCover(entityType, entityId, newCoverAssetId);

            if (newCoverAssetId != null) {
                log.info("删除封面素材后自动更新封面: entityType={}, entityId={}, newCoverAssetId={}",
                        entityType, entityId, newCoverAssetId);
            } else {
                log.info("删除封面素材后置空封面: entityType={}, entityId={}", entityType, entityId);
            }
        } catch (Exception e) {
            // 封面更新失败不影响主流程
            log.warn("删除关联后更新封面失败: entityType={}, entityId={}, deletedAssetId={}",
                    entityType, entityId, deletedAssetId, e);
        }
    }

    /**
     * 获取实体当前的封面素材ID
     */
    private String getEntityCoverAssetId(String entityType, String entityId) {
        return switch (entityType) {
            case ProjectConstants.EntityType.CHARACTER -> {
                com.actionow.project.entity.Character character = characterMapper.selectById(entityId);
                yield character != null ? character.getCoverAssetId() : null;
            }
            case ProjectConstants.EntityType.SCENE -> {
                Scene scene = sceneMapper.selectById(entityId);
                yield scene != null ? scene.getCoverAssetId() : null;
            }
            case ProjectConstants.EntityType.PROP -> {
                Prop prop = propMapper.selectById(entityId);
                yield prop != null ? prop.getCoverAssetId() : null;
            }
            case ProjectConstants.EntityType.STYLE -> {
                Style style = styleMapper.selectById(entityId);
                yield style != null ? style.getCoverAssetId() : null;
            }
            case ProjectConstants.EntityType.EPISODE -> {
                Episode episode = episodeMapper.selectById(entityId);
                yield episode != null ? episode.getCoverAssetId() : null;
            }
            case ProjectConstants.EntityType.SCRIPT -> {
                Script script = scriptMapper.selectById(entityId);
                yield script != null ? script.getCoverAssetId() : null;
            }
            case ProjectConstants.EntityType.STORYBOARD -> {
                Storyboard storyboard = storyboardMapper.selectById(entityId);
                yield storyboard != null ? storyboard.getCoverAssetId() : null;
            }
            default -> null;
        };
    }

    /**
     * 查找实体关联的下一个图片素材
     */
    private String findNextImageAsset(String entityType, String entityId) {
        // 查询该实体的所有未删除关联（按 sequence 排序）
        List<EntityAssetRelation> relations = entityAssetRelationMapper.selectByEntity(entityType, entityId);
        if (relations.isEmpty()) {
            return null;
        }

        // 获取所有关联的素材ID
        List<String> assetIds = relations.stream()
                .map(EntityAssetRelation::getAssetId)
                .distinct()
                .collect(Collectors.toList());

        // 批量获取素材信息
        List<AssetResponse> assets = assetService.batchGet(assetIds);
        Map<String, AssetResponse> assetMap = assets.stream()
                .collect(Collectors.toMap(AssetResponse::getId, a -> a, (a, b) -> a));

        // 按 sequence 顺序找到第一个图片素材
        for (EntityAssetRelation relation : relations) {
            AssetResponse asset = assetMap.get(relation.getAssetId());
            if (asset != null && ProjectConstants.AssetType.IMAGE.equals(asset.getAssetType())) {
                return asset.getId();
            }
        }

        return null;
    }

    /**
     * 更新实体封面
     */
    private void updateEntityCover(String entityType, String entityId, String coverAssetId) {
        switch (entityType) {
            case ProjectConstants.EntityType.CHARACTER -> {
                com.actionow.project.entity.Character character = characterMapper.selectById(entityId);
                if (character != null) {
                    character.setCoverAssetId(coverAssetId);
                    characterMapper.updateById(character);
                }
            }
            case ProjectConstants.EntityType.SCENE -> {
                Scene scene = sceneMapper.selectById(entityId);
                if (scene != null) {
                    scene.setCoverAssetId(coverAssetId);
                    sceneMapper.updateById(scene);
                }
            }
            case ProjectConstants.EntityType.PROP -> {
                Prop prop = propMapper.selectById(entityId);
                if (prop != null) {
                    prop.setCoverAssetId(coverAssetId);
                    propMapper.updateById(prop);
                }
            }
            case ProjectConstants.EntityType.STYLE -> {
                Style style = styleMapper.selectById(entityId);
                if (style != null) {
                    style.setCoverAssetId(coverAssetId);
                    styleMapper.updateById(style);
                }
            }
            case ProjectConstants.EntityType.EPISODE -> {
                Episode episode = episodeMapper.selectById(entityId);
                if (episode != null) {
                    episode.setCoverAssetId(coverAssetId);
                    episodeMapper.updateById(episode);
                }
            }
            case ProjectConstants.EntityType.SCRIPT -> {
                Script script = scriptMapper.selectById(entityId);
                if (script != null) {
                    script.setCoverAssetId(coverAssetId);
                    scriptMapper.updateById(script);
                }
            }
            case ProjectConstants.EntityType.STORYBOARD -> {
                Storyboard storyboard = storyboardMapper.selectById(entityId);
                if (storyboard != null) {
                    storyboard.setCoverAssetId(coverAssetId);
                    storyboardMapper.updateById(storyboard);
                }
            }
            default -> log.debug("未知实体类型，跳过封面更新: entityType={}", entityType);
        }
    }

    // ==================== 用户信息填充 ====================

    /**
     * 为单个关联响应填充用户信息
     */
    private EntityAssetRelationResponse enrichWithUserInfo(EntityAssetRelationResponse response) {
        if (response == null || !StringUtils.hasText(response.getCreatedBy())) {
            return response;
        }

        UserBasicInfo userInfo = userInfoHelper.getUserInfo(response.getCreatedBy());
        if (userInfo != null) {
            response.setCreatedByNickname(userInfo.getNickname());
            response.setCreatedByUsername(userInfo.getUsername());
        }

        return response;
    }

    /**
     * 批量为关联响应列表填充用户信息
     */
    private List<EntityAssetRelationResponse> enrichWithUserInfo(List<EntityAssetRelationResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return responses;
        }

        // 批量获取用户信息
        Set<String> userIds = responses.stream()
                .map(EntityAssetRelationResponse::getCreatedBy)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        Map<String, UserBasicInfo> userInfoMap = userInfoHelper.batchGetUserInfo(userIds);

        // 填充用户信息
        for (EntityAssetRelationResponse response : responses) {
            if (StringUtils.hasText(response.getCreatedBy())) {
                UserBasicInfo userInfo = userInfoMap.get(response.getCreatedBy());
                if (userInfo != null) {
                    response.setCreatedByNickname(userInfo.getNickname());
                    response.setCreatedByUsername(userInfo.getUsername());
                }
            }
        }

        return responses;
    }
}
