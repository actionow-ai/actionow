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
 * 场景工具类（SAA v2）
 *
 * @author Actionow
 */
@Slf4j
@Component
public class SceneTools extends AbstractProjectTool {

    public SceneTools(ProjectFeignClient projectClient, HitlConfirmationHelper hitl) {
        super(projectClient, hitl);
    }

    @Tool(name = "get_scene", description = "获取场景详细信息")
    @AgentToolSpec(
            displayName = "获取场景详情",
            summary = "按场景 ID 获取完整场景信息。",
            purpose = "用于读取场景的环境、视觉属性和附加设定。",
            actionType = ToolActionType.READ,
            tags = {"scene", "detail"},
            usageNotes = {"已知场景 ID 时优先使用本工具"},
            errorCases = {"sceneId 为空时会返回校验错误"},
            exampleInput = "{\"sceneId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\"}",
            exampleOutput = "{\"success\":true,\"scene\":{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"name\":\"咖啡馆\"}}"
    )
    @AgentToolOutput(
            description = "返回单个场景详情。",
            example = "{\"success\":true,\"scene\":{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"name\":\"咖啡馆\",\"sceneType\":\"INTERIOR\"}}"
    )
    public Map<String, Object> getScene(
            @AgentToolParamSpec(example = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
            @ToolParam(description = "场景ID（必填）") String sceneId) {

        Map<String, Object> validationError = validateRequired(sceneId, "场景ID");
        if (validationError != null) return validationError;

        return execute("获取场景", () ->
                handleResult(projectClient.getScene(sceneId), "获取场景", data -> successData("scene", data)));
    }

    @Tool(name = "query_scenes", description = "搜索场景（可搜索列表）。默认返回当前剧本可用的所有场景（含系统级/工作空间级/剧本级），支持按关键字模糊搜索名称、描述、固定描述、外观、附加信息，支持按场景类型(INTERIOR/EXTERIOR/MIXED)过滤，支持分页和排序。")
    @AgentToolSpec(
            displayName = "搜索场景",
            summary = "按关键字和分页条件搜索场景。",
            purpose = "用于在当前剧本可用场景范围内定位目标场景。",
            actionType = ToolActionType.SEARCH,
            tags = {"scene", "query", "search"},
            usageNotes = {"未提供 scriptId 时会尝试从上下文推断"},
            errorCases = {"分页参数非法时由下游接口返回错误"},
            exampleInput = "{\"keyword\":\"咖啡\",\"pageNum\":1}",
            exampleOutput = "{\"success\":true,\"data\":{\"records\":[]},\"message\":\"搜索场景成功\"}"
    )
    @AgentToolOutput(
            description = "返回分页场景搜索结果。",
            example = "{\"success\":true,\"data\":{\"records\":[{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"name\":\"咖啡馆\"}]},\"message\":\"搜索场景成功\"}"
    )
    public Map<String, Object> queryScenes(
            @ToolParam(description = "搜索关键字", required = false) String keyword,
            @AgentToolParamSpec(example = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
            @ToolParam(description = "剧本ID（默认使用当前会话剧本）", required = false) String scriptId,
            @AgentToolParamSpec(enumValues = {"INTERIOR", "EXTERIOR", "MIXED"})
            @ToolParam(description = "场景类型过滤: INTERIOR(室内)/EXTERIOR(室外)/MIXED(混合)", required = false) String sceneType,
            @AgentToolParamSpec(defaultValue = "1", example = "1")
            @ToolParam(description = "页码，默认1", required = false) Integer pageNum,
            @AgentToolParamSpec(defaultValue = "20", example = "20")
            @ToolParam(description = "每页数量，默认20", required = false) Integer pageSize,
            @AgentToolParamSpec(defaultValue = "created_at", enumValues = {"name", "created_at", "updated_at"})
            @ToolParam(description = "排序字段: name/created_at/updated_at，默认created_at", required = false) String orderBy,
            @AgentToolParamSpec(defaultValue = "desc", enumValues = {"asc", "desc"})
            @ToolParam(description = "排序方向: asc/desc，默认desc", required = false) String orderDir) {

        return execute("搜索场景", () -> {
            String resolvedScriptId = resolveScriptId(scriptId);
            return handleResult(projectClient.queryScenes(
                    null, resolvedScriptId, sceneType, keyword,
                    pageNum, pageSize, orderBy, orderDir),
                    "搜索场景", data -> {
                        Map<String, Object> response = new HashMap<>();
                        response.put("success", true);
                        response.put("data", data);
                        response.put("message", "搜索场景成功");
                        return response;
                    });
        });
    }

    @Tool(name = "update_scene", description = "更新场景信息。支持三种保存方式：OVERWRITE(覆盖当前版本)、NEW_VERSION(存为新版本，推荐)、NEW_ENTITY(另存为新实体)。" +
            "外观属性字段和附加信息补丁均为增量合并(merge)，不会覆盖未传的字段。")
    @AgentToolSpec(
            displayName = "更新场景",
            summary = "更新场景基础信息、环境和视觉属性。",
            purpose = "用于持续细化场景设计，并在保存时控制版本策略。",
            actionType = ToolActionType.WRITE,
            tags = {"scene", "update", "versioning"},
            usageNotes = {"appearanceDataPatch 和 extraInfoPatch 为 merge 语义", "推荐使用 NEW_VERSION"},
            errorCases = {"sceneId 为空时会返回校验错误", "extraInfoPatchJson 非法时会返回 JSON 错误"},
            exampleInput = "{\"sceneId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"name\":\"夜晚咖啡馆\",\"saveMode\":\"NEW_VERSION\"}",
            exampleOutput = "{\"success\":true,\"sceneId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"versionNumber\":2}"
    )
    @AgentToolOutput(
            description = "返回更新后的场景 ID、版本号和保存结果。",
            example = "{\"success\":true,\"sceneId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"versionNumber\":2,\"message\":\"场景已保存为新版本\"}"
    )
    public Map<String, Object> updateScene(
            @AgentToolParamSpec(example = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
            @ToolParam(description = "场景ID（必填）") String sceneId,
            @ToolParam(description = "场景名称", required = false) String name,
            @ToolParam(description = "场景详细描述", required = false) String description,
            @ToolParam(description = "固定描述词", required = false) String fixedDesc,
            @ToolParam(description = "场景类型: INTERIOR(室内), EXTERIOR(室外), MIXED(混合)", required = false) String sceneType,
            @ToolParam(description = "时间: DAWN, DAY, DUSK, NIGHT", required = false) String timeOfDay,
            @ToolParam(description = "天气: sunny, cloudy, rainy, snowy, foggy, stormy", required = false) String weather,
            @ToolParam(description = "季节: spring, summer, autumn, winter", required = false) String season,
            @ToolParam(description = "地点类型描述，如: urban street, forest clearing, office building", required = false) String location,
            @ToolParam(description = "光线: natural, artificial, dim, bright, dramatic", required = false) String lighting,
            @ToolParam(description = "色调: warm, cool, neutral, vibrant, muted", required = false) String colorTone,
            @ToolParam(description = "氛围: peaceful, tense, romantic, mysterious, chaotic", required = false) String mood,
            @ToolParam(description = "透视: eye-level, bird-eye, worm-eye", required = false) String perspective,
            @ToolParam(description = "纵深: shallow, medium, deep", required = false) String depth,
            @ToolParam(description = "场景关键元素，逗号分隔，如: 咖啡桌,落地窗,盆栽", required = false) String keyElements,
            @ToolParam(description = "绘画风格偏好", required = false) String artStyle,
            @ToolParam(description = "附加信息补丁(JSON)，与现有数据合并而非替换", required = false) String extraInfoPatchJson,
            @AgentToolParamSpec(defaultValue = "NEW_VERSION", enumValues = {"OVERWRITE", "NEW_VERSION", "NEW_ENTITY"})
            @ToolParam(description = "保存方式: OVERWRITE(覆盖), NEW_VERSION(新版本), NEW_ENTITY(新实体)，默认NEW_VERSION", required = false) String saveMode) {

        Map<String, Object> validationError = validateRequired(sceneId, "场景ID");
        if (validationError != null) return validationError;

        return execute("更新场景", () -> {
            Map<String, Object> request = new HashMap<>();

            String mode = getDefaultSaveMode(saveMode);
            request.put("saveMode", mode);

            addIfNotBlank(request, "name", name);
            addIfNotBlank(request, "description", description);
            addIfNotBlank(request, "fixedDesc", fixedDesc);
            addIfNotBlank(request, "sceneType", sceneType);

            // 构建外观数据 Patch Map（merge 语义）
            Map<String, Object> appearanceDataPatch = new HashMap<>();
            addIfNotBlank(appearanceDataPatch, "timeOfDay", timeOfDay);
            addIfNotBlank(appearanceDataPatch, "weather", weather);
            addIfNotBlank(appearanceDataPatch, "season", season);
            addIfNotBlank(appearanceDataPatch, "location", location);
            addIfNotBlank(appearanceDataPatch, "lighting", lighting);
            addIfNotBlank(appearanceDataPatch, "colorTone", colorTone);
            addIfNotBlank(appearanceDataPatch, "mood", mood);
            addIfNotBlank(appearanceDataPatch, "perspective", perspective);
            addIfNotBlank(appearanceDataPatch, "depth", depth);
            addIfNotBlank(appearanceDataPatch, "artStyle", artStyle);
            if (keyElements != null && !keyElements.isBlank()) {
                appearanceDataPatch.put("keyElements", List.of(keyElements.split(",")));
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

            return handleResult(projectClient.updateScene(sceneId, request), "更新场景", data -> {
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("sceneId", data.getOrDefault("id", sceneId));
                response.put("versionNumber", data.getOrDefault("versionNumber", 1));
                response.put("message", buildVersionMessage("场景", mode, data));
                return response;
            });
        });
    }

    @Tool(name = "batch_create_scenes", description = "批量创建多个场景（自动跳过已存在的同名场景）。接受JSON数组，每个元素包含: name(必填), description(必填), scriptId(可选), fixedDesc(可选), sceneType(可选:INTERIOR/EXTERIOR/MIXED), appearanceData(可选,含timeOfDay/weather/season/lighting/colorTone/mood/keyElements等)")
    @AgentToolSpec(
            displayName = "批量创建场景",
            summary = "一次性创建多个场景，并自动跳过同名已存在项。",
            purpose = "用于快速搭建剧本的场景库。",
            actionType = ToolActionType.WRITE,
            tags = {"scene", "batch", "creation"},
            usageNotes = {"请求体为 JSON 数组字符串", "若当前会话绑定 scriptId，会自动创建为 SCRIPT 级场景"},
            errorCases = {"scenesJson 为空时会返回错误", "缺少工作空间上下文会返回错误"},
            exampleInput = "{\"scenesJson\":\"[{\\\"name\\\":\\\"街角咖啡馆\\\",\\\"description\\\":\\\"温馨的法式街角咖啡馆，木质装潢，暖色灯光\\\",\\\"sceneType\\\":\\\"INTERIOR\\\",\\\"fixedDesc\\\":\\\"暖色调木质内饰，落地窗外是梧桐树街道\\\",\\\"appearanceData\\\":{\\\"timeOfDay\\\":\\\"afternoon\\\",\\\"weather\\\":\\\"sunny\\\",\\\"season\\\":\\\"autumn\\\",\\\"lighting\\\":\\\"warm ambient\\\",\\\"colorTone\\\":\\\"warm\\\",\\\"mood\\\":\\\"cozy\\\",\\\"keyElements\\\":[\\\"木质吧台\\\",\\\"落地窗\\\",\\\"咖啡机\\\"]}}]\"}",
            exampleOutput = "{\"success\":true,\"scenes\":[{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"name\":\"咖啡馆\"}],\"count\":1}"
    )
    @AgentToolOutput(
            description = "返回创建后的场景列表，以及已跳过项信息。",
            example = "{\"success\":true,\"scenes\":[{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"name\":\"咖啡馆\"}],\"created\":[\"咖啡馆\"]}"
    )
    public Map<String, Object> batchCreateScenes(
            @AgentToolParamSpec(example = "[{\"name\":\"街角咖啡馆\",\"description\":\"温馨的法式街角咖啡馆，木质装潢，暖色灯光\",\"sceneType\":\"INTERIOR\",\"fixedDesc\":\"暖色调木质内饰，落地窗外是梧桐树街道\",\"appearanceData\":{\"timeOfDay\":\"afternoon\",\"weather\":\"sunny\",\"season\":\"autumn\",\"lighting\":\"warm ambient\",\"colorTone\":\"warm\",\"mood\":\"cozy\",\"keyElements\":[\"木质吧台\",\"落地窗\",\"咖啡机\"]}}]")
            @ToolParam(description = "场景JSON数组，例如: [{\"name\":\"咖啡馆\",\"description\":\"温馨的街角咖啡馆\",\"sceneType\":\"INTERIOR\",\"appearanceData\":{\"lighting\":\"warm\",\"mood\":\"cozy\"}},{\"name\":\"公园\",\"description\":\"城市中心公园\",\"sceneType\":\"EXTERIOR\"}]") String scenesJson) {

        Map<String, Object> validationError = validateRequired(scenesJson, "场景JSON数组");
        if (validationError != null) return validationError;

        return execute("批量创建场景", () -> {
            UserContext userContext = UserContextHolder.getContext();
            String workspaceId = userContext != null ? userContext.getWorkspaceId() : null;
            String userId = userContext != null ? userContext.getUserId() : "system";
            if (workspaceId == null) return error("缺少工作空间上下文");

            List<Map<String, Object>> requests = parseJsonArray(scenesJson);
            if (requests.isEmpty()) return error("场景列表不能为空");

            // 获取当前脚本ID用于查询已存在的场景
            String scriptId = AgentContextHolder.getScriptId();

            // 幂等性检查：查询已存在的同名场景
            Set<String> existingNames = new HashSet<>();
            List<Map<String, Object>> existingScenes = new ArrayList<>();

            if (scriptId != null) {
                try {
                    List<String> requestedNames = requests.stream()
                            .map(r -> (String) r.get("name"))
                            .filter(name -> name != null && !name.isBlank())
                            .toList();

                    var existingResult = projectClient.listAvailableScenes(scriptId, null, 500);
                    if (existingResult.isSuccess() && existingResult.getData() != null) {
                        for (Map<String, Object> scene : existingResult.getData()) {
                            String name = (String) scene.get("name");
                            if (name != null && requestedNames.contains(name)) {
                                existingNames.add(name);
                                existingScenes.add(scene);
                                log.info("Scene already exists, will skip creation: name={}, id={}",
                                        name, scene.get("id"));
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to query existing scenes for idempotency check: {}", e.getMessage());
                }
            }

            // 过滤掉已存在的场景
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

            if (toCreate.isEmpty()) {
                log.info("All {} scenes already exist, skipping creation", existingNames.size());
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("scenes", existingScenes);
                response.put("count", existingScenes.size());
                response.put("message", "所有场景已存在，无需重复创建: " + String.join(", ", existingNames));
                response.put("skipped", existingNames);
                return response;
            }

            var result = projectClient.batchCreateScenes(workspaceId, userId, toCreate);
            if (result.isSuccess()) {
                List<Map<String, Object>> createdScenes = result.getData();
                List<String> createdNames = createdScenes.stream()
                        .map(c -> (String) c.get("name"))
                        .toList();

                List<Map<String, Object>> allScenes = new ArrayList<>(existingScenes);
                allScenes.addAll(createdScenes);

                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("scenes", allScenes);
                response.put("count", allScenes.size());

                if (!existingNames.isEmpty()) {
                    response.put("message", String.format("成功创建 %d 个场景: %s；跳过 %d 个已存在场景: %s",
                            createdScenes.size(), String.join(", ", createdNames),
                            existingNames.size(), String.join(", ", existingNames)));
                    response.put("created", createdNames);
                    response.put("skipped", existingNames);
                } else {
                    response.put("message", "成功创建 " + createdScenes.size() + " 个场景: " + String.join(", ", createdNames));
                    response.put("created", createdNames);
                }

                log.info("Batch create scenes completed: created={}, skipped={}",
                        createdScenes.size(), existingNames.size());
                return response;
            }
            return error("批量创建场景失败: " + result.getMessage());
        });
    }

    @Tool(name = "batch_delete_scenes", description = "批量软删除场景（删除入回收站，可恢复）。" +
            "工具内部强制弹 HITL 确认对话框，用户拒绝/超时则不执行任何删除并返回 cancelled=true。" +
            "建议先用 query_scenes 核对待删除 ID。返回 {success, deleted, failed, cancelled}。")
    @AgentToolSpec(
            displayName = "批量删除场景",
            summary = "对一组场景 ID 执行软删除，删除前必须经用户在前端确认。",
            purpose = "在用户明确请求删除若干场景时使用；底层走逻辑删除，可恢复。",
            actionType = ToolActionType.WRITE,
            tags = {"scene", "batch", "destructive"},
            usageNotes = {"破坏性操作，工具内部已强制 HITL 确认；不要再额外调用 ask_user_confirm",
                    "软删除可恢复（deleted=1）"},
            errorCases = {"sceneIdsJson 解析失败 / 为空时返回错误",
                    "无活跃会话时返回错误",
                    "用户拒绝或超时返回 cancelled=true"},
            exampleInput = "{\"sceneIdsJson\":\"[\\\"sc-1\\\",\\\"sc-2\\\"]\"}",
            exampleOutput = "{\"success\":true,\"deleted\":[\"sc-1\",\"sc-2\"],\"failed\":[],\"cancelled\":false}"
    )
    @AgentToolOutput(
            description = "返回成功/失败/取消的汇总。",
            example = "{\"success\":true,\"deleted\":[\"sc-1\"],\"failed\":[],\"cancelled\":false}"
    )
    public Map<String, Object> batchDeleteScenes(
            @ToolParam(description = "场景ID JSON 数组，例: [\"id1\",\"id2\"]") String sceneIdsJson) {
        return executeBatchDelete("场景", sceneIdsJson, projectClient::deleteScene);
    }
}
