package com.actionow.agent.scriptwriting.tools;

import com.actionow.agent.feign.ProjectFeignClient;
import com.actionow.agent.interaction.HitlConfirmationHelper;
import com.actionow.agent.tool.annotation.AgentToolOutput;
import com.actionow.agent.tool.annotation.AgentToolParamSpec;
import com.actionow.agent.tool.annotation.AgentToolSpec;
import com.actionow.agent.tool.annotation.ToolActionType;
import com.actionow.common.core.context.UserContext;
import com.actionow.common.core.context.UserContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分镜工具类（SAA v2）
 *
 * @author Actionow
 */
@Slf4j
@Component
public class StoryboardTools extends AbstractProjectTool {

    public StoryboardTools(ProjectFeignClient projectClient, HitlConfirmationHelper hitl) {
        super(projectClient, hitl);
    }

    @Tool(name = "get_storyboard", description = "获取分镜详细信息，包含关联的场景、角色、道具和对白")
    @AgentToolSpec(
            displayName = "获取分镜详情",
            summary = "按分镜 ID 获取完整分镜信息和关联实体。",
            purpose = "用于查看单个分镜的视觉、音频和关系上下文。",
            actionType = ToolActionType.READ,
            tags = {"storyboard", "detail"},
            usageNotes = {"已知 storyboardId 时优先使用本工具"},
            errorCases = {"storyboardId 为空时会返回校验错误"},
            exampleInput = "{\"storyboardId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\"}",
            exampleOutput = "{\"success\":true,\"storyboard\":{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"title\":\"开场镜头\"}}"
    )
    @AgentToolOutput(
            description = "返回单个分镜详情对象。",
            example = "{\"success\":true,\"storyboard\":{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"title\":\"开场镜头\",\"sceneId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\"}}"
    )
    public Map<String, Object> getStoryboard(
            @AgentToolParamSpec(example = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
            @ToolParam(description = "分镜ID（必填）") String storyboardId) {

        Map<String, Object> validationError = validateRequired(storyboardId, "分镜ID");
        if (validationError != null) return validationError;

        return execute("获取分镜", () ->
                handleResult(projectClient.getStoryboard(storyboardId), "获取分镜", data -> successData("storyboard", data)));
    }

    @Tool(name = "query_storyboards", description = "搜索分镜（可搜索列表）。支持按关键字模糊搜索标题、描述，支持按状态和剧集过滤，支持分页和排序。")
    @AgentToolSpec(
            displayName = "搜索分镜",
            summary = "按关键字、剧集、状态和分页条件搜索分镜。",
            purpose = "用于在某个剧本或剧集范围内定位目标分镜。",
            actionType = ToolActionType.SEARCH,
            tags = {"storyboard", "query", "search"},
            usageNotes = {"未提供 scriptId 时会尝试从上下文推断", "已知精确分镜 ID 时优先用 get_storyboard"},
            errorCases = {"分页参数非法时由下游接口返回错误"},
            exampleInput = "{\"episodeId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"status\":\"DRAFT\"}",
            exampleOutput = "{\"success\":true,\"data\":{\"records\":[]},\"message\":\"搜索分镜成功\"}"
    )
    @AgentToolOutput(
            description = "返回分页分镜搜索结果。",
            example = "{\"success\":true,\"data\":{\"records\":[{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"title\":\"开场镜头\"}]},\"message\":\"搜索分镜成功\"}"
    )
    public Map<String, Object> queryStoryboards(
            @ToolParam(description = "搜索关键字", required = false) String keyword,
            @AgentToolParamSpec(example = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
            @ToolParam(description = "剧本ID（默认使用当前会话剧本）", required = false) String scriptId,
            @AgentToolParamSpec(example = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
            @ToolParam(description = "剧集ID", required = false) String episodeId,
            @AgentToolParamSpec(enumValues = {"DRAFT", "GENERATING", "COMPLETED", "FAILED"})
            @ToolParam(description = "状态过滤: DRAFT/GENERATING/COMPLETED/FAILED", required = false) String status,
            @AgentToolParamSpec(defaultValue = "1", example = "1")
            @ToolParam(description = "页码，默认1", required = false) Integer pageNum,
            @AgentToolParamSpec(defaultValue = "20", example = "20")
            @ToolParam(description = "每页数量，默认20", required = false) Integer pageSize,
            @AgentToolParamSpec(defaultValue = "sequence", enumValues = {"sequence", "title", "created_at", "updated_at"})
            @ToolParam(description = "排序字段: sequence/title/created_at/updated_at，默认sequence", required = false) String orderBy,
            @AgentToolParamSpec(defaultValue = "asc", enumValues = {"asc", "desc"})
            @ToolParam(description = "排序方向: asc/desc，默认asc", required = false) String orderDir) {

        return execute("搜索分镜", () -> {
            String resolvedScriptId = resolveScriptId(scriptId);
            return handleResult(projectClient.queryStoryboards(
                    resolvedScriptId, episodeId, status, keyword,
                    pageNum, pageSize, orderBy, orderDir),
                    "搜索分镜", data -> {
                        Map<String, Object> response = new HashMap<>();
                        response.put("success", true);
                        response.put("data", data);
                        response.put("message", "搜索分镜成功");
                        return response;
                    });
        });
    }

    @Tool(name = "update_storyboard", description = "更新分镜信息。支持三种保存方式：OVERWRITE(覆盖当前版本)、NEW_VERSION(存为新版本，推荐)、NEW_ENTITY(另存为新实体)。" +
            "视觉描述、音频描述、场景覆盖、附加信息均为增量合并(merge)，不会覆盖未传的字段。角色、道具、场景、对白通过独立的关系字段管理。")
    @AgentToolSpec(
            displayName = "更新分镜",
            summary = "更新分镜文本、镜头语言、音频、场景覆盖与实体关系。",
            purpose = "用于将剧本内容逐步细化为可执行、可生成的分镜设计。",
            actionType = ToolActionType.WRITE,
            tags = {"storyboard", "update", "versioning"},
            usageNotes = {"视觉、音频、场景覆盖和附加信息均为 merge 语义", "角色、道具、对白关系可通过增量字段单独管理"},
            errorCases = {"storyboardId 为空时会返回校验错误", "sceneOverridePatchJson / extraInfoPatchJson 非法时会报错或忽略"},
            exampleInput = "{\"storyboardId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"title\":\"开场远景\",\"shotType\":\"EXTREME_LONG\",\"saveMode\":\"NEW_VERSION\"}",
            exampleOutput = "{\"success\":true,\"storyboardId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"versionNumber\":2}"
    )
    @AgentToolOutput(
            description = "返回更新后的分镜 ID、版本号和保存结果。",
            example = "{\"success\":true,\"storyboardId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"versionNumber\":2,\"message\":\"分镜已保存为新版本\"}"
    )
    public Map<String, Object> updateStoryboard(
            @AgentToolParamSpec(example = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
            @ToolParam(description = "分镜ID（必填）") String storyboardId,
            @ToolParam(description = "分镜标题", required = false) String title,
            @ToolParam(description = "分镜描述/画面内容", required = false) String synopsis,
            @ToolParam(description = "时长（秒）", required = false) Integer duration,
            @ToolParam(description = "景别: EXTREME_CLOSE_UP, CLOSE_UP, MEDIUM_CLOSE_UP, MEDIUM, MEDIUM_LONG, LONG, EXTREME_LONG", required = false) String shotType,
            @ToolParam(description = "机位角度: HIGH, LOW, EYE_LEVEL, DUTCH, BIRD_EYE, WORM_EYE", required = false) String cameraAngle,
            @ToolParam(description = "镜头运动: STATIC, PAN, TILT, ZOOM, DOLLY, TRACKING, CRANE", required = false) String cameraMovement,
            @ToolParam(description = "转场类型: CUT, FADE, DISSOLVE, WIPE, IRIS", required = false) String transitionType,
            @ToolParam(description = "视觉特效，逗号分隔，如: 雨滴,柔焦背景", required = false) String visualEffects,
            @ToolParam(description = "光线覆盖: natural, dramatic, dim, bright", required = false) String lighting,
            @ToolParam(description = "颜色分级: desaturated, high-contrast, warm-toned等", required = false) String colorGrading,
            @ToolParam(description = "音效描述", required = false) String soundEffectDesc,
            @ToolParam(description = "背景音乐情绪: happy, sad, tense, peaceful, epic, mysterious, romantic, melancholy", required = false) String bgmMood,
            @ToolParam(description = "背景音乐类型: orchestral, electronic, piano, guitar, ambient", required = false) String bgmGenre,
            @ToolParam(description = "状态: DRAFT(草稿), GENERATING(生成中), COMPLETED(已完成), FAILED(失败)", required = false) String status,
            @ToolParam(description = "场景ID（设置分镜发生的场景）", required = false) String sceneId,
            @ToolParam(description = "场景属性覆盖补丁JSON（merge语义），如: {\"timeOfDay\":\"NIGHT\",\"weather\":\"rainy\"}", required = false) String sceneOverridePatchJson,
            @ToolParam(description = "风格ID", required = false) String styleId,
            @ToolParam(description = "附加信息补丁(JSON)，与现有数据合并而非替换", required = false) String extraInfoPatchJson,
            @ToolParam(description = "角色列表JSON，如: [{\"characterId\":\"xxx\",\"position\":\"center\",\"action\":\"standing\",\"expression\":\"happy\"}]", required = false) String charactersJson,
            @ToolParam(description = "添加角色列表JSON（增量添加）", required = false) String addCharactersJson,
            @ToolParam(description = "移除角色ID列表，逗号分隔", required = false) String removeCharacterIds,
            @ToolParam(description = "道具列表JSON，如: [{\"propId\":\"xxx\",\"position\":\"foreground\",\"interaction\":\"held\"}]", required = false) String propsJson,
            @ToolParam(description = "添加道具列表JSON（增量添加）", required = false) String addPropsJson,
            @ToolParam(description = "移除道具ID列表，逗号分隔", required = false) String removePropIds,
            @ToolParam(description = "对白列表JSON，如: [{\"characterId\":\"xxx\",\"text\":\"你好\",\"emotion\":\"happy\",\"voiceStyle\":\"normal\"}]", required = false) String dialoguesJson,
            @ToolParam(description = "添加对白列表JSON（增量添加）", required = false) String addDialoguesJson,
            @ToolParam(description = "移除对白的角色ID列表，逗号分隔", required = false) String removeDialogueCharacterIds,
            @AgentToolParamSpec(defaultValue = "NEW_VERSION", enumValues = {"OVERWRITE", "NEW_VERSION", "NEW_ENTITY"})
            @ToolParam(description = "保存方式: OVERWRITE(覆盖), NEW_VERSION(新版本), NEW_ENTITY(新实体)，默认NEW_VERSION", required = false) String saveMode) {

        Map<String, Object> validationError = validateRequired(storyboardId, "分镜ID");
        if (validationError != null) return validationError;

        return execute("更新分镜", () -> {
            Map<String, Object> request = new HashMap<>();

            String mode = getDefaultSaveMode(saveMode);
            request.put("saveMode", mode);

            addIfNotBlank(request, "title", title);
            addIfNotBlank(request, "synopsis", synopsis);
            addIfNotNull(request, "duration", duration);
            addIfNotBlank(request, "status", status);

            // 构建视觉描述 Patch Map（merge 语义）
            Map<String, Object> visualDescPatch = new HashMap<>();
            addIfNotBlank(visualDescPatch, "shotType", shotType);
            addIfNotBlank(visualDescPatch, "cameraAngle", cameraAngle);
            addIfNotBlank(visualDescPatch, "cameraMovement", cameraMovement);
            addIfNotBlank(visualDescPatch, "lighting", lighting);
            addIfNotBlank(visualDescPatch, "colorGrading", colorGrading);
            if (visualEffects != null && !visualEffects.isBlank()) {
                visualDescPatch.put("visualEffects", List.of(visualEffects.split(",")));
            }
            if (transitionType != null && !transitionType.isBlank()) {
                Map<String, Object> transition = new HashMap<>();
                transition.put("type", transitionType);
                visualDescPatch.put("transition", transition);
            }
            if (!visualDescPatch.isEmpty()) {
                request.put("visualDescPatch", visualDescPatch);
            }

            // 构建音频描述 Patch Map（merge 语义）
            Map<String, Object> audioDescPatch = new HashMap<>();

            // 音效
            if (soundEffectDesc != null && !soundEffectDesc.isBlank()) {
                Map<String, Object> sfx = new HashMap<>();
                sfx.put("description", soundEffectDesc);
                sfx.put("type", "ambient");
                sfx.put("volume", "medium");
                audioDescPatch.put("soundEffects", List.of(sfx));
            }

            // 背景音乐
            if ((bgmMood != null && !bgmMood.isBlank()) || (bgmGenre != null && !bgmGenre.isBlank())) {
                Map<String, Object> bgm = new HashMap<>();
                addIfNotBlank(bgm, "mood", bgmMood);
                addIfNotBlank(bgm, "genre", bgmGenre);
                bgm.put("volume", "low");
                audioDescPatch.put("bgm", bgm);
            }

            if (!audioDescPatch.isEmpty()) {
                request.put("audioDescPatch", audioDescPatch);
            }

            // 附加信息补丁（merge 语义）
            if (extraInfoPatchJson != null && !extraInfoPatchJson.isBlank()) {
                try {
                    request.put("extraInfoPatch", parseJsonObject(extraInfoPatchJson));
                } catch (Exception e) {
                    return error("extraInfoPatch JSON 格式错误: " + e.getMessage());
                }
            }

            // ==================== 实体关系字段 ====================

            // 场景关系
            if (sceneId != null) {
                request.put("sceneId", sceneId);
            }
            if (sceneOverridePatchJson != null && !sceneOverridePatchJson.isBlank()) {
                try {
                    Map<String, Object> override = parseJsonObject(sceneOverridePatchJson);
                    if (!override.isEmpty()) {
                        request.put("sceneOverridePatch", override);
                    }
                } catch (Exception e) {
                    log.warn("解析 sceneOverridePatch JSON 失败: {}", sceneOverridePatchJson);
                }
            }

            // 风格关系
            if (styleId != null) {
                request.put("styleId", styleId);
            }

            // 角色关系
            if (charactersJson != null && !charactersJson.isBlank()) {
                try {
                    List<Map<String, Object>> characters = parseJsonArray(charactersJson);
                    request.put("characters", characters);
                } catch (Exception e) {
                    log.warn("解析 characters JSON 失败: {}", charactersJson);
                }
            }
            if (addCharactersJson != null && !addCharactersJson.isBlank()) {
                try {
                    List<Map<String, Object>> addCharacters = parseJsonArray(addCharactersJson);
                    request.put("addCharacters", addCharacters);
                } catch (Exception e) {
                    log.warn("解析 addCharacters JSON 失败: {}", addCharactersJson);
                }
            }
            if (removeCharacterIds != null && !removeCharacterIds.isBlank()) {
                request.put("removeCharacterIds", List.of(removeCharacterIds.split(",")));
            }

            // 道具关系
            if (propsJson != null && !propsJson.isBlank()) {
                try {
                    List<Map<String, Object>> props = parseJsonArray(propsJson);
                    request.put("props", props);
                } catch (Exception e) {
                    log.warn("解析 props JSON 失败: {}", propsJson);
                }
            }
            if (addPropsJson != null && !addPropsJson.isBlank()) {
                try {
                    List<Map<String, Object>> addProps = parseJsonArray(addPropsJson);
                    request.put("addProps", addProps);
                } catch (Exception e) {
                    log.warn("解析 addProps JSON 失败: {}", addPropsJson);
                }
            }
            if (removePropIds != null && !removePropIds.isBlank()) {
                request.put("removePropIds", List.of(removePropIds.split(",")));
            }

            // 对白关系
            if (dialoguesJson != null && !dialoguesJson.isBlank()) {
                try {
                    List<Map<String, Object>> dialogues = parseJsonArray(dialoguesJson);
                    request.put("dialogues", dialogues);
                } catch (Exception e) {
                    log.warn("解析 dialogues JSON 失败: {}", dialoguesJson);
                }
            }
            if (addDialoguesJson != null && !addDialoguesJson.isBlank()) {
                try {
                    List<Map<String, Object>> addDialogues = parseJsonArray(addDialoguesJson);
                    request.put("addDialogues", addDialogues);
                } catch (Exception e) {
                    log.warn("解析 addDialogues JSON 失败: {}", addDialoguesJson);
                }
            }
            if (removeDialogueCharacterIds != null && !removeDialogueCharacterIds.isBlank()) {
                request.put("removeDialogueCharacterIds", List.of(removeDialogueCharacterIds.split(",")));
            }

            return handleResult(projectClient.updateStoryboard(storyboardId, request), "更新分镜", data -> {
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("storyboardId", data.getOrDefault("id", storyboardId));
                response.put("versionNumber", data.getOrDefault("versionNumber", 1));
                response.put("message", buildVersionMessage("分镜", mode, data));
                return response;
            });
        });
    }

    @Tool(name = "batch_create_storyboards", description = "批量创建多个分镜。接受JSON数组，每个元素包含: episodeId(必填), synopsis(必填), title(可选), duration(可选), sequence(可选), visualDesc(可选,镜头属性), audioDesc(可选,音频属性), sceneId(可选), characters(可选), props(可选), dialogues(可选)。" +
            "【推荐工作流】批创分镜时应在每个元素中一次性带上 sceneId、characters、props、dialogues 等关系字段，" +
            "避免先创建再逐条调用 add_character_to_storyboard / set_storyboard_scene 等关联工具。这样可大幅减少调用次数并保证数据一致性。")
    @AgentToolSpec(
            displayName = "批量创建分镜",
            summary = "一次性创建多个分镜。",
            purpose = "用于把章节快速拆解为一组初始分镜。",
            actionType = ToolActionType.WRITE,
            tags = {"storyboard", "batch", "creation"},
            usageNotes = {"请求体为 JSON 数组字符串", "每个元素至少应包含 episodeId 和 synopsis",
                    "【关键】批创分镜时应在每个元素中一次性携带 sceneId、characters、props、dialogues，避免先创后关联",
                    "characters 数组元素结构: {characterId, position, action, expression}",
                    "props 数组元素结构: {propId, position, interaction}",
                    "dialogues 数组元素结构: {characterId, text, emotion, voiceStyle, sequence}"},
            errorCases = {"storyboardsJson 为空时会返回错误", "缺少工作空间上下文会返回错误"},
            exampleInput = "{\"storyboardsJson\":\"[{\\\"episodeId\\\":\\\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\\\",\\\"synopsis\\\":\\\"城市全貌远景，晨光中高楼林立\\\",\\\"duration\\\":5,\\\"visualDesc\\\":{\\\"shotType\\\":\\\"EXTREME_LONG\\\",\\\"cameraAngle\\\":\\\"HIGH\\\",\\\"cameraMovement\\\":\\\"PAN\\\",\\\"lighting\\\":\\\"natural morning\\\",\\\"colorGrading\\\":\\\"warm-toned\\\"},\\\"audioDesc\\\":{\\\"soundEffects\\\":[{\\\"description\\\":\\\"城市晨间环境音\\\",\\\"type\\\":\\\"ambient\\\"}],\\\"bgm\\\":{\\\"mood\\\":\\\"peaceful\\\",\\\"genre\\\":\\\"orchestral\\\"}},\\\"sceneId\\\":\\\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\\\",\\\"characters\\\":[{\\\"characterId\\\":\\\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\\\",\\\"position\\\":\\\"center\\\",\\\"action\\\":\\\"walking\\\",\\\"expression\\\":\\\"determined\\\"}],\\\"props\\\":[{\\\"propId\\\":\\\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\\\",\\\"position\\\":\\\"hand\\\",\\\"interaction\\\":\\\"held\\\"}],\\\"dialogues\\\":[{\\\"characterId\\\":\\\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\\\",\\\"text\\\":\\\"新的一天开始了\\\",\\\"emotion\\\":\\\"hopeful\\\"}]}]\"}",
            exampleOutput = "{\"success\":true,\"storyboards\":[{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"synopsis\":\"城市远景\"}],\"count\":1}"
    )
    @AgentToolOutput(
            description = "返回创建后的分镜列表。",
            example = "{\"success\":true,\"storyboards\":[{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"synopsis\":\"城市远景\"}],\"count\":1}"
    )
    public Map<String, Object> batchCreateStoryboards(
            @AgentToolParamSpec(example = "[{\"episodeId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"synopsis\":\"城市全貌远景\",\"duration\":5,\"visualDesc\":{\"shotType\":\"EXTREME_LONG\",\"cameraAngle\":\"HIGH\",\"cameraMovement\":\"PAN\",\"lighting\":\"natural morning\"},\"audioDesc\":{\"bgm\":{\"mood\":\"peaceful\",\"genre\":\"orchestral\"}},\"sceneId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"characters\":[{\"characterId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"position\":\"center\",\"action\":\"walking\"}],\"dialogues\":[{\"characterId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"text\":\"新的一天开始了\",\"emotion\":\"hopeful\"}]}]")
            @ToolParam(description = "分镜JSON数组，例如: [{\"episodeId\":\"xxx\",\"synopsis\":\"远景-城市全貌\",\"visualDesc\":{\"shotType\":\"EXTREME_LONG\"},\"sceneId\":\"scene-id\",\"characters\":[{\"characterId\":\"char-id\",\"position\":\"center\"}]}]") String storyboardsJson) {

        Map<String, Object> validationError = validateRequired(storyboardsJson, "分镜JSON数组");
        if (validationError != null) return validationError;

        return execute("批量创建分镜", () -> {
            UserContext userContext = UserContextHolder.getContext();
            String workspaceId = userContext != null ? userContext.getWorkspaceId() : null;
            String userId = userContext != null ? userContext.getUserId() : "system";
            if (workspaceId == null) return error("缺少工作空间上下文");

            List<Map<String, Object>> requests = parseJsonArray(storyboardsJson);
            if (requests.isEmpty()) return error("分镜列表不能为空");

            String episodeId = (String) requests.get(0).get("episodeId");
            if (episodeId == null || episodeId.isBlank()) return error("episodeId不能为空");

            var result = projectClient.batchCreateStoryboards(workspaceId, userId, episodeId, requests);
            if (result.isSuccess()) {
                return successList("storyboards", result.getData());
            }
            return error("批量创建分镜失败: " + result.getMessage());
        });
    }

    @Tool(name = "batch_delete_storyboards", description = "批量软删除分镜（删除入回收站，可恢复）。" +
            "工具内部强制弹 HITL 确认对话框，用户拒绝/超时则不执行任何删除并返回 cancelled=true。" +
            "建议先用 query_storyboards 核对待删除 ID。返回 {success, deleted, failed, cancelled}。")
    @AgentToolSpec(
            displayName = "批量删除分镜",
            summary = "对一组分镜 ID 执行软删除，删除前必须经用户在前端确认。",
            purpose = "在用户明确请求删除若干分镜时使用；底层走逻辑删除，可恢复。",
            actionType = ToolActionType.WRITE,
            tags = {"storyboard", "batch", "destructive"},
            usageNotes = {"破坏性操作，工具内部已强制 HITL 确认；不要再额外调用 ask_user_confirm",
                    "软删除可恢复（deleted=1）"},
            errorCases = {"storyboardIdsJson 解析失败 / 为空时返回错误",
                    "无活跃会话时返回错误",
                    "用户拒绝或超时返回 cancelled=true"},
            exampleInput = "{\"storyboardIdsJson\":\"[\\\"sb-1\\\",\\\"sb-2\\\"]\"}",
            exampleOutput = "{\"success\":true,\"deleted\":[\"sb-1\",\"sb-2\"],\"failed\":[],\"cancelled\":false}"
    )
    @AgentToolOutput(
            description = "返回成功/失败/取消的汇总。",
            example = "{\"success\":true,\"deleted\":[\"sb-1\"],\"failed\":[],\"cancelled\":false}"
    )
    public Map<String, Object> batchDeleteStoryboards(
            @ToolParam(description = "分镜ID JSON 数组，例: [\"id1\",\"id2\"]") String storyboardIdsJson) {
        return executeBatchDelete("分镜", storyboardIdsJson, projectClient::deleteStoryboard);
    }
}
