package com.actionow.agent.scriptwriting.tools;

import com.actionow.agent.core.scope.AgentContextHolder;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色工具类（SAA v2）
 *
 * @author Actionow
 */
@Slf4j
@Component
public class CharacterTools extends AbstractProjectTool {

    public CharacterTools(ProjectFeignClient projectClient, HitlConfirmationHelper hitl) {
        super(projectClient, hitl);
    }

    @Tool(name = "batch_create_characters", description = "批量创建角色（自动跳过已存在的同名角色）。接受JSON数组，每个元素包含: name(必填), description(必填), scriptId(可选), fixedDesc(可选), age(可选), gender(可选:MALE/FEMALE/OTHER), characterType(可选:PROTAGONIST/ANTAGONIST/SUPPORTING/BACKGROUND), appearanceData(可选,含bodyType/height/skinTone/hairColor/hairStyle/eyeColor等)")
    @AgentToolSpec(
            displayName = "批量创建角色",
            summary = "一次性创建多个角色，并自动跳过同名已存在角色。",
            purpose = "用于从角色清单、大纲或设定表快速建立角色资产。",
            actionType = ToolActionType.WRITE,
            tags = {"character", "batch", "creation"},
            usageNotes = {"请求体是 JSON 数组字符串", "若当前会话绑定 scriptId，会自动将角色创建在 SCRIPT 范围"},
            errorCases = {"charactersJson 为空时会返回错误", "缺少工作空间上下文会返回错误"},
            exampleInput = "{\"charactersJson\":\"[{\\\"name\\\":\\\"林晓\\\",\\\"description\\\":\\\"性格坚韧的女大学生，热爱天文学\\\",\\\"characterType\\\":\\\"PROTAGONIST\\\",\\\"gender\\\":\\\"FEMALE\\\",\\\"age\\\":22,\\\"fixedDesc\\\":\\\"蓝色短发，琥珀色眼睛，左手腕戴银色手链\\\",\\\"appearanceData\\\":{\\\"bodyType\\\":\\\"slim\\\",\\\"height\\\":\\\"average\\\",\\\"skinTone\\\":\\\"light\\\",\\\"faceShape\\\":\\\"oval\\\",\\\"eyeColor\\\":\\\"amber\\\",\\\"eyeShape\\\":\\\"almond\\\",\\\"hairColor\\\":\\\"blue\\\",\\\"hairStyle\\\":\\\"bob cut\\\",\\\"hairLength\\\":\\\"short\\\",\\\"distinguishingFeatures\\\":[\\\"左手腕银色手链\\\"],\\\"artStyle\\\":\\\"anime\\\"}}]\"}",
            exampleOutput = "{\"success\":true,\"characters\":[{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"name\":\"林晓\"}],\"count\":1}"
    )
    @AgentToolOutput(
            description = "返回新创建与已跳过的角色列表。",
            example = "{\"success\":true,\"characters\":[{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"name\":\"林晓\"}],\"created\":[\"林晓\"]}"
    )
    public Map<String, Object> batchCreateCharacters(
            @AgentToolParamSpec(example = "[{\"name\":\"林晓\",\"description\":\"性格坚韧的女大学生，热爱天文学\",\"characterType\":\"PROTAGONIST\",\"gender\":\"FEMALE\",\"age\":22,\"fixedDesc\":\"蓝色短发，琥珀色眼睛，左手腕戴银色手链\",\"appearanceData\":{\"bodyType\":\"slim\",\"height\":\"average\",\"skinTone\":\"light\",\"faceShape\":\"oval\",\"eyeColor\":\"amber\",\"eyeShape\":\"almond\",\"hairColor\":\"blue\",\"hairStyle\":\"bob cut\",\"hairLength\":\"short\",\"distinguishingFeatures\":[\"左手腕银色手链\"],\"artStyle\":\"anime\"}}]")
            @ToolParam(description = "角色JSON数组，例如: [{\"name\":\"张三\",\"description\":\"主角，勇敢正直\",\"characterType\":\"PROTAGONIST\",\"appearanceData\":{\"hairColor\":\"black\",\"height\":\"tall\"}},{\"name\":\"李四\",\"description\":\"反派\",\"characterType\":\"ANTAGONIST\"}]") String charactersJson) {

        Map<String, Object> validationError = validateRequired(charactersJson, "角色JSON数组");
        if (validationError != null) return validationError;

        return execute("批量创建角色", () -> {
            UserContext userContext = UserContextHolder.getContext();
            String workspaceId = userContext != null ? userContext.getWorkspaceId() : null;
            String userId = userContext != null ? userContext.getUserId() : "system";
            if (workspaceId == null) return error("缺少工作空间上下文");

            List<Map<String, Object>> requests = parseJsonArray(charactersJson);
            if (requests.isEmpty()) return error("角色列表不能为空");

            // 获取当前脚本ID用于查询已存在的角色
            String scriptId = AgentContextHolder.getScriptId();

            // 幂等性检查：查询已存在的同名角色
            Set<String> existingNames = new HashSet<>();
            List<Map<String, Object>> existingCharacters = new ArrayList<>();

            if (scriptId != null) {
                try {
                    // 获取请求中的角色名称列表
                    List<String> requestedNames = requests.stream()
                            .map(r -> (String) r.get("name"))
                            .filter(name -> name != null && !name.isBlank())
                            .toList();

                    // 查询已存在的角色（使用较大的 limit 确保覆盖所有可能的匹配）
                    var existingResult = projectClient.listAvailableCharacters(scriptId, null, 500);
                    if (existingResult.isSuccess() && existingResult.getData() != null) {
                        for (Map<String, Object> character : existingResult.getData()) {
                            String name = (String) character.get("name");
                            if (name != null && requestedNames.contains(name)) {
                                existingNames.add(name);
                                existingCharacters.add(character);
                                log.info("Character already exists, will skip creation: name={}, id={}",
                                        name, character.get("id"));
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to query existing characters for idempotency check: {}", e.getMessage());
                    // 查询失败不阻止创建，继续执行
                }
            }

            // 过滤掉已存在的角色
            List<Map<String, Object>> toCreate = requests.stream()
                    .filter(r -> {
                        String name = (String) r.get("name");
                        return name == null || !existingNames.contains(name);
                    })
                    .toList();

            // 注入 scope 和 scriptId（确保创建在剧本级别而不是工作空间级别）
            if (scriptId != null) {
                toCreate = toCreate.stream()
                        .map(r -> {
                            Map<String, Object> enriched = new HashMap<>(r);
                            enriched.put("scope", "SCRIPT");
                            enriched.put("scriptId", scriptId);
                            return enriched;
                        })
                        .toList();
            }

            // 如果所有角色都已存在，返回已存在的信息
            if (toCreate.isEmpty()) {
                log.info("All {} characters already exist, skipping creation", existingNames.size());
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("characters", existingCharacters);
                response.put("count", existingCharacters.size());
                response.put("message", "所有角色已存在，无需重复创建: " + String.join(", ", existingNames));
                response.put("skipped", existingNames);
                return response;
            }

            // 创建不存在的角色
            var result = projectClient.batchCreateCharacters(workspaceId, userId, toCreate);
            if (result.isSuccess()) {
                List<Map<String, Object>> createdCharacters = result.getData();
                List<String> createdNames = createdCharacters.stream()
                        .map(c -> (String) c.get("name"))
                        .toList();

                // 合并已存在和新创建的角色
                List<Map<String, Object>> allCharacters = new ArrayList<>(existingCharacters);
                allCharacters.addAll(createdCharacters);

                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("characters", allCharacters);
                response.put("count", allCharacters.size());

                if (!existingNames.isEmpty()) {
                    response.put("message", String.format("成功创建 %d 个角色: %s；跳过 %d 个已存在角色: %s",
                            createdCharacters.size(), String.join(", ", createdNames),
                            existingNames.size(), String.join(", ", existingNames)));
                    response.put("created", createdNames);
                    response.put("skipped", existingNames);
                } else {
                    response.put("message", "成功创建 " + createdCharacters.size() + " 个角色: " + String.join(", ", createdNames));
                    response.put("created", createdNames);
                }

                log.info("Batch create characters completed: created={}, skipped={}",
                        createdCharacters.size(), existingNames.size());
                return response;
            }
            return error("批量创建角色失败: " + result.getMessage());
        });
    }

    @Tool(name = "query_characters", description = "搜索角色（可搜索列表）。默认返回当前剧本可用的所有角色（含系统级/工作空间级/剧本级），支持按关键字模糊搜索名称、描述、固定描述、外观、附加信息，支持分页和排序。")
    @AgentToolSpec(
            displayName = "搜索角色",
            summary = "按关键字、类型、性别和分页条件搜索角色。",
            purpose = "用于在系统级、工作空间级和剧本级角色中定位目标角色。",
            actionType = ToolActionType.SEARCH,
            tags = {"character", "query", "search"},
            usageNotes = {"未提供 scriptId 时会尝试从当前会话剧本推断", "已知精确角色 ID 时优先用 get_character"},
            errorCases = {"分页参数或过滤参数非法时由下游接口返回错误"},
            exampleInput = "{\"keyword\":\"林\",\"characterType\":\"PROTAGONIST\",\"pageNum\":1}",
            exampleOutput = "{\"success\":true,\"data\":{\"records\":[]},\"message\":\"搜索角色成功\"}"
    )
    @AgentToolOutput(
            description = "返回分页角色搜索结果。",
            example = "{\"success\":true,\"data\":{\"records\":[{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"name\":\"林晓\"}]},\"message\":\"搜索角色成功\"}"
    )
    public Map<String, Object> queryCharacters(
            @ToolParam(description = "搜索关键字", required = false) String keyword,
            @AgentToolParamSpec(example = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
            @ToolParam(description = "剧本ID（默认使用当前会话剧本）", required = false) String scriptId,
            @AgentToolParamSpec(enumValues = {"PROTAGONIST", "ANTAGONIST", "SUPPORTING", "BACKGROUND"})
            @ToolParam(description = "角色类型: PROTAGONIST/ANTAGONIST/SUPPORTING/BACKGROUND", required = false) String characterType,
            @AgentToolParamSpec(enumValues = {"MALE", "FEMALE", "OTHER"})
            @ToolParam(description = "性别: MALE/FEMALE/OTHER", required = false) String gender,
            @AgentToolParamSpec(defaultValue = "1", example = "1")
            @ToolParam(description = "页码，默认1", required = false) Integer pageNum,
            @AgentToolParamSpec(defaultValue = "20", example = "20")
            @ToolParam(description = "每页数量，默认20", required = false) Integer pageSize,
            @AgentToolParamSpec(defaultValue = "created_at", enumValues = {"name", "created_at", "updated_at"})
            @ToolParam(description = "排序字段: name/created_at/updated_at，默认created_at", required = false) String orderBy,
            @AgentToolParamSpec(defaultValue = "desc", enumValues = {"asc", "desc"})
            @ToolParam(description = "排序方向: asc/desc，默认desc", required = false) String orderDir) {

        return execute("搜索角色", () -> {
            String resolvedScriptId = resolveScriptId(scriptId);
            return handleResult(projectClient.queryCharacters(
                    null, resolvedScriptId, characterType, gender, keyword,
                    pageNum, pageSize, orderBy, orderDir),
                    "搜索角色", data -> {
                        Map<String, Object> response = new HashMap<>();
                        response.put("success", true);
                        response.put("data", data);
                        response.put("message", "搜索角色成功");
                        return response;
                    });
        });
    }

    @Tool(name = "get_character", description = "获取角色详细信息")
    @AgentToolSpec(
            displayName = "获取角色详情",
            summary = "按角色 ID 获取完整角色详情。",
            purpose = "用于读取角色的基本信息、外观设定和附加数据。",
            actionType = ToolActionType.READ,
            tags = {"character", "detail"},
            usageNotes = {"已知角色 ID 时优先使用本工具"},
            errorCases = {"characterId 为空时会返回校验错误"},
            exampleInput = "{\"characterId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\"}",
            exampleOutput = "{\"success\":true,\"character\":{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"name\":\"林晓\"}}"
    )
    @AgentToolOutput(
            description = "返回单个角色详情。",
            example = "{\"success\":true,\"character\":{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"name\":\"林晓\",\"characterType\":\"PROTAGONIST\"}}"
    )
    public Map<String, Object> getCharacter(
            @AgentToolParamSpec(example = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
            @ToolParam(description = "角色ID（必填）") String characterId) {

        Map<String, Object> validationError = validateRequired(characterId, "角色ID");
        if (validationError != null) return validationError;

        return execute("获取角色", () ->
                handleResult(projectClient.getCharacter(characterId), "获取角色", data -> successData("character", data)));
    }

    @Tool(name = "update_character", description = "更新角色信息。支持三种保存方式：OVERWRITE(覆盖当前版本)、NEW_VERSION(存为新版本，推荐)、NEW_ENTITY(另存为新实体)。" +
            "外貌属性字段和附加信息补丁均为增量合并(merge)，不会覆盖未传的字段。")
    @AgentToolSpec(
            displayName = "更新角色",
            summary = "更新角色基本信息、外貌属性和附加信息。",
            purpose = "用于持续细化角色设定，并通过版本化保存保留历史。",
            actionType = ToolActionType.WRITE,
            tags = {"character", "update", "versioning"},
            usageNotes = {"appearanceDataPatch 和 extraInfoPatch 为 merge 语义", "推荐保存模式使用 NEW_VERSION"},
            errorCases = {"characterId 为空时会返回校验错误", "extraInfoPatchJson 非法时会返回 JSON 错误"},
            exampleInput = "{\"characterId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"name\":\"林晓\",\"gender\":\"FEMALE\",\"saveMode\":\"NEW_VERSION\"}",
            exampleOutput = "{\"success\":true,\"characterId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"versionNumber\":2}"
    )
    @AgentToolOutput(
            description = "返回更新后的角色 ID、版本号和保存结果。",
            example = "{\"success\":true,\"characterId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"versionNumber\":2,\"message\":\"角色已保存为新版本\"}"
    )
    public Map<String, Object> updateCharacter(
            @AgentToolParamSpec(example = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
            @ToolParam(description = "角色ID（必填）") String characterId,
            @ToolParam(description = "角色名称", required = false) String name,
            @ToolParam(description = "角色详细描述", required = false) String description,
            @ToolParam(description = "固定描述词", required = false) String fixedDesc,
            @ToolParam(description = "角色年龄", required = false) Integer age,
            @AgentToolParamSpec(enumValues = {"MALE", "FEMALE", "OTHER"})
            @ToolParam(description = "性别: MALE, FEMALE, OTHER", required = false) String gender,
            @AgentToolParamSpec(enumValues = {"PROTAGONIST", "ANTAGONIST", "SUPPORTING", "BACKGROUND"})
            @ToolParam(description = "角色类型: PROTAGONIST, ANTAGONIST, SUPPORTING, BACKGROUND", required = false) String characterType,
            @ToolParam(description = "语音种子ID，用于TTS语音合成", required = false) String voiceSeedId,
            @ToolParam(description = "体型: slim, average, athletic, muscular, chubby", required = false) String bodyType,
            @ToolParam(description = "身高: short, average, tall", required = false) String height,
            @ToolParam(description = "肤色: fair, light, medium, tan, dark", required = false) String skinTone,
            @ToolParam(description = "脸型: oval, round, square, heart, long", required = false) String faceShape,
            @ToolParam(description = "眼睛颜色", required = false) String eyeColor,
            @ToolParam(description = "眼型: almond, round, monolid, hooded", required = false) String eyeShape,
            @ToolParam(description = "发色", required = false) String hairColor,
            @ToolParam(description = "发型描述", required = false) String hairStyle,
            @ToolParam(description = "发长: short, medium, long", required = false) String hairLength,
            @ToolParam(description = "显著特征，逗号分隔，如: 左脸疤痕,戴眼镜", required = false) String distinguishingFeatures,
            @ToolParam(description = "绘画风格: anime, realistic, cartoon, chibi", required = false) String artStyle,
            @ToolParam(description = "附加信息补丁(JSON)，与现有数据合并而非替换", required = false) String extraInfoPatchJson,
            @AgentToolParamSpec(defaultValue = "NEW_VERSION", enumValues = {"OVERWRITE", "NEW_VERSION", "NEW_ENTITY"})
            @ToolParam(description = "保存方式: OVERWRITE(覆盖), NEW_VERSION(新版本), NEW_ENTITY(新实体)，默认NEW_VERSION", required = false) String saveMode) {

        Map<String, Object> validationError = validateRequired(characterId, "角色ID");
        if (validationError != null) return validationError;

        return execute("更新角色", () -> {
            Map<String, Object> request = new HashMap<>();

            String mode = getDefaultSaveMode(saveMode);
            request.put("saveMode", mode);

            // 可选更新字段
            addIfNotBlank(request, "name", name);
            addIfNotBlank(request, "description", description);
            addIfNotBlank(request, "fixedDesc", fixedDesc);
            addIfNotNull(request, "age", age);
            addIfNotBlank(request, "gender", gender);
            addIfNotBlank(request, "characterType", characterType);
            addIfNotBlank(request, "voiceSeedId", voiceSeedId);

            // 构建外貌数据 Patch Map（merge 语义）
            Map<String, Object> appearanceDataPatch = new HashMap<>();
            addIfNotBlank(appearanceDataPatch, "bodyType", bodyType);
            addIfNotBlank(appearanceDataPatch, "height", height);
            addIfNotBlank(appearanceDataPatch, "skinTone", skinTone);
            addIfNotBlank(appearanceDataPatch, "faceShape", faceShape);
            addIfNotBlank(appearanceDataPatch, "eyeColor", eyeColor);
            addIfNotBlank(appearanceDataPatch, "eyeShape", eyeShape);
            addIfNotBlank(appearanceDataPatch, "hairColor", hairColor);
            addIfNotBlank(appearanceDataPatch, "hairStyle", hairStyle);
            addIfNotBlank(appearanceDataPatch, "hairLength", hairLength);
            addIfNotBlank(appearanceDataPatch, "artStyle", artStyle);
            if (distinguishingFeatures != null && !distinguishingFeatures.isBlank()) {
                appearanceDataPatch.put("distinguishingFeatures", List.of(distinguishingFeatures.split(",")));
            }
            if (!appearanceDataPatch.isEmpty()) {
                request.put("appearanceDataPatch", appearanceDataPatch);
            }

            // 附加信息补丁（merge 语义）
            if (extraInfoPatchJson != null && !extraInfoPatchJson.isBlank()) {
                try {
                    request.put("extraInfoPatch", parseJsonObject(extraInfoPatchJson));
                } catch (Exception e) {
                    return error("extraInfoPatch JSON 格式错误: " + e.getMessage());
                }
            }

            return handleResult(projectClient.updateCharacter(characterId, request), "更新角色", data -> {
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("characterId", data.getOrDefault("id", characterId));
                response.put("versionNumber", data.getOrDefault("versionNumber", 1));
                response.put("message", buildVersionMessage("角色", mode, data));
                return response;
            });
        });
    }

    @Tool(name = "batch_delete_characters", description = "批量软删除角色（删除入回收站，可恢复）。" +
            "工具内部强制弹 HITL 确认对话框，用户拒绝/超时则不执行任何删除并返回 cancelled=true。" +
            "建议先用 query_characters 核对待删除 ID。返回 {success, deleted:[ids], failed:[{id,reason}], cancelled:bool}。")
    @AgentToolSpec(
            displayName = "批量删除角色",
            summary = "对一组角色 ID 执行软删除，删除前必须经用户在前端确认。",
            purpose = "在用户明确请求删除若干角色时使用；底层走逻辑删除（deleted=1），可恢复。",
            actionType = ToolActionType.WRITE,
            tags = {"character", "batch", "destructive"},
            usageNotes = {"破坏性操作，工具内部已强制 HITL 确认；不要再额外调用 ask_user_confirm",
                    "软删除可恢复（deleted=1，回收站可还原）",
                    "若需永久删除请走运维通道，不在 LLM 工具范围内"},
            errorCases = {"characterIdsJson 解析失败 / 为空时返回错误",
                    "无活跃会话时返回错误（HITL 必须有 chat/mission 上下文）",
                    "用户拒绝或超时返回 success=true 且 cancelled=true"},
            exampleInput = "{\"characterIdsJson\":\"[\\\"id-1\\\",\\\"id-2\\\"]\"}",
            exampleOutput = "{\"success\":true,\"deleted\":[\"id-1\",\"id-2\"],\"failed\":[],\"cancelled\":false}"
    )
    @AgentToolOutput(
            description = "返回成功/失败/取消的汇总。cancelled=true 时 deleted 为空数组。",
            example = "{\"success\":true,\"deleted\":[\"id-1\"],\"failed\":[{\"id\":\"id-2\",\"reason\":\"角色不存在\"}],\"cancelled\":false}"
    )
    public Map<String, Object> batchDeleteCharacters(
            @ToolParam(description = "角色ID JSON 数组，例: [\"id1\",\"id2\"]") String characterIdsJson) {
        return executeBatchDelete("角色", characterIdsJson, projectClient::deleteCharacter);
    }
}
