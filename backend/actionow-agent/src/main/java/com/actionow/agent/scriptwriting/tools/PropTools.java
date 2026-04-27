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
 * 道具工具类（SAA v2）
 *
 * @author Actionow
 */
@Slf4j
@Component
public class PropTools extends AbstractProjectTool {

    public PropTools(ProjectFeignClient projectClient, HitlConfirmationHelper hitl) {
        super(projectClient, hitl);
    }

    @Tool(name = "get_prop", description = "获取道具详细信息")
    @AgentToolSpec(
            displayName = "获取道具详情",
            summary = "按道具 ID 获取完整道具信息。",
            purpose = "用于读取单个道具的基础属性和外观设定。",
            actionType = ToolActionType.READ,
            tags = {"prop", "detail"},
            usageNotes = {"已知 propId 时优先使用本工具"},
            errorCases = {"propId 为空时会返回校验错误"},
            exampleInput = "{\"propId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\"}",
            exampleOutput = "{\"success\":true,\"prop\":{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"name\":\"魔法杖\"}}"
    )
    @AgentToolOutput(
            description = "返回单个道具详情。",
            example = "{\"success\":true,\"prop\":{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"name\":\"魔法杖\",\"propType\":\"WEAPON\"}}"
    )
    public Map<String, Object> getProp(
            @AgentToolParamSpec(example = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
            @ToolParam(description = "道具ID（必填）") String propId) {

        Map<String, Object> validationError = validateRequired(propId, "道具ID");
        if (validationError != null) return validationError;

        return execute("获取道具", () ->
                handleResult(projectClient.getProp(propId), "获取道具", data -> successData("prop", data)));
    }

    @Tool(name = "query_props", description = "搜索道具（可搜索列表）。默认返回当前剧本可用的所有道具（含系统级/工作空间级/剧本级），支持按关键字模糊搜索名称、描述、固定描述、外观、附加信息，支持分页和排序。")
    @AgentToolSpec(
            displayName = "搜索道具",
            summary = "按关键字、类型和分页条件搜索道具。",
            purpose = "用于在当前剧本可见道具范围内定位目标道具。",
            actionType = ToolActionType.SEARCH,
            tags = {"prop", "query", "search"},
            usageNotes = {"未提供 scriptId 时会尝试从会话上下文推断"},
            errorCases = {"分页参数非法时由下游接口返回错误"},
            exampleInput = "{\"keyword\":\"宝剑\",\"propType\":\"WEAPON\"}",
            exampleOutput = "{\"success\":true,\"data\":{\"records\":[]},\"message\":\"搜索道具成功\"}"
    )
    @AgentToolOutput(
            description = "返回分页道具搜索结果。",
            example = "{\"success\":true,\"data\":{\"records\":[{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"name\":\"宝剑\"}]},\"message\":\"搜索道具成功\"}"
    )
    public Map<String, Object> queryProps(
            @ToolParam(description = "搜索关键字", required = false) String keyword,
            @AgentToolParamSpec(example = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
            @ToolParam(description = "剧本ID（默认使用当前会话剧本）", required = false) String scriptId,
            @AgentToolParamSpec(enumValues = {"FURNITURE", "VEHICLE", "WEAPON", "FOOD", "CLOTHING", "ELECTRONIC", "OTHER"})
            @ToolParam(description = "道具类型: FURNITURE/VEHICLE/WEAPON/FOOD/CLOTHING/ELECTRONIC/OTHER", required = false) String propType,
            @AgentToolParamSpec(defaultValue = "1", example = "1")
            @ToolParam(description = "页码，默认1", required = false) Integer pageNum,
            @AgentToolParamSpec(defaultValue = "20", example = "20")
            @ToolParam(description = "每页数量，默认20", required = false) Integer pageSize,
            @AgentToolParamSpec(defaultValue = "created_at", enumValues = {"name", "created_at", "updated_at"})
            @ToolParam(description = "排序字段: name/created_at/updated_at，默认created_at", required = false) String orderBy,
            @AgentToolParamSpec(defaultValue = "desc", enumValues = {"asc", "desc"})
            @ToolParam(description = "排序方向: asc/desc，默认desc", required = false) String orderDir) {

        return execute("搜索道具", () -> {
            String resolvedScriptId = resolveScriptId(scriptId);
            return handleResult(projectClient.queryProps(
                    null, resolvedScriptId, propType, keyword,
                    pageNum, pageSize, orderBy, orderDir),
                    "搜索道具", data -> {
                        Map<String, Object> response = new HashMap<>();
                        response.put("success", true);
                        response.put("data", data);
                        response.put("message", "搜索道具成功");
                        return response;
                    });
        });
    }

    @Tool(name = "update_prop", description = "更新道具信息。支持三种保存方式：OVERWRITE(覆盖当前版本)、NEW_VERSION(存为新版本，推荐)、NEW_ENTITY(另存为新实体)。" +
            "外观属性字段和附加信息补丁均为增量合并(merge)，不会覆盖未传的字段。")
    @AgentToolSpec(
            displayName = "更新道具",
            summary = "更新道具基础信息、外观属性和附加信息。",
            purpose = "用于持续细化道具设定，并支持版本化保存。",
            actionType = ToolActionType.WRITE,
            tags = {"prop", "update", "versioning"},
            usageNotes = {"appearanceDataPatch 和 extraInfoPatch 为 merge 语义", "推荐使用 NEW_VERSION"},
            errorCases = {"propId 为空时会返回校验错误", "extraInfoPatchJson 非法时会返回 JSON 错误"},
            exampleInput = "{\"propId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"name\":\"古老魔法杖\",\"saveMode\":\"NEW_VERSION\"}",
            exampleOutput = "{\"success\":true,\"propId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"versionNumber\":2}"
    )
    @AgentToolOutput(
            description = "返回更新后的道具 ID、版本号和保存结果。",
            example = "{\"success\":true,\"propId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"versionNumber\":2,\"message\":\"道具已保存为新版本\"}"
    )
    public Map<String, Object> updateProp(
            @AgentToolParamSpec(example = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
            @ToolParam(description = "道具ID（必填）") String propId,
            @ToolParam(description = "道具名称", required = false) String name,
            @ToolParam(description = "道具详细描述", required = false) String description,
            @ToolParam(description = "固定描述词", required = false) String fixedDesc,
            @ToolParam(description = "道具类型: FURNITURE, VEHICLE, WEAPON, FOOD, CLOTHING, ELECTRONIC, OTHER", required = false) String propType,
            @ToolParam(description = "材质: metal, wood, glass, plastic, fabric, stone", required = false) String material,
            @ToolParam(description = "质感: smooth, rough, glossy, matte", required = false) String texture,
            @ToolParam(description = "主色", required = false) String color,
            @ToolParam(description = "次要色", required = false) String secondaryColor,
            @ToolParam(description = "尺寸: tiny, small, handheld, medium, large, huge", required = false) String size,
            @ToolParam(description = "形状描述，如: rectangular, curved, cylindrical", required = false) String shape,
            @ToolParam(description = "状态: new, used, worn, damaged, antique", required = false) String condition,
            @ToolParam(description = "特殊特征，逗号分隔，如: 雕刻花纹,发光符文", required = false) String distinguishingFeatures,
            @ToolParam(description = "是否可交互: true, false", required = false) Boolean functional,
            @ToolParam(description = "特效描述（如发光、冒烟）", required = false) String specialEffects,
            @ToolParam(description = "绘画风格偏好", required = false) String artStyle,
            @ToolParam(description = "附加信息补丁(JSON)，与现有数据合并而非替换", required = false) String extraInfoPatchJson,
            @AgentToolParamSpec(defaultValue = "NEW_VERSION", enumValues = {"OVERWRITE", "NEW_VERSION", "NEW_ENTITY"})
            @ToolParam(description = "保存方式: OVERWRITE(覆盖), NEW_VERSION(新版本), NEW_ENTITY(新实体)，默认NEW_VERSION", required = false) String saveMode) {

        Map<String, Object> validationError = validateRequired(propId, "道具ID");
        if (validationError != null) return validationError;

        return execute("更新道具", () -> {
            Map<String, Object> request = new HashMap<>();

            String mode = getDefaultSaveMode(saveMode);
            request.put("saveMode", mode);

            // 可选更新字段
            addIfNotBlank(request, "name", name);
            addIfNotBlank(request, "description", description);
            addIfNotBlank(request, "fixedDesc", fixedDesc);
            addIfNotBlank(request, "propType", propType);

            // 构建外观数据 Patch Map（merge 语义）
            Map<String, Object> appearanceDataPatch = new HashMap<>();
            addIfNotBlank(appearanceDataPatch, "material", material);
            addIfNotBlank(appearanceDataPatch, "texture", texture);
            addIfNotBlank(appearanceDataPatch, "color", color);
            addIfNotBlank(appearanceDataPatch, "secondaryColor", secondaryColor);
            addIfNotBlank(appearanceDataPatch, "size", size);
            addIfNotBlank(appearanceDataPatch, "shape", shape);
            addIfNotBlank(appearanceDataPatch, "condition", condition);
            addIfNotNull(appearanceDataPatch, "functional", functional);
            addIfNotBlank(appearanceDataPatch, "specialEffects", specialEffects);
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

            return handleResult(projectClient.updateProp(propId, request), "更新道具", data -> {
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("propId", data.getOrDefault("id", propId));
                response.put("versionNumber", data.getOrDefault("versionNumber", 1));
                response.put("message", buildVersionMessage("道具", mode, data));
                return response;
            });
        });
    }

    @Tool(name = "batch_create_props", description = "批量创建多个道具（自动跳过已存在的同名道具）。接受JSON数组，每个元素包含: name(必填), description(必填), scriptId(可选), fixedDesc(可选), propType(可选), appearanceData(可选,含material/texture/color/size/condition等)")
    @AgentToolSpec(
            displayName = "批量创建道具",
            summary = "一次性创建多个道具，并自动跳过同名已存在项。",
            purpose = "用于快速搭建剧本道具库。",
            actionType = ToolActionType.WRITE,
            tags = {"prop", "batch", "creation"},
            usageNotes = {"请求体为 JSON 数组字符串", "若当前会话绑定 scriptId，会自动创建为 SCRIPT 级道具"},
            errorCases = {"propsJson 为空时会返回错误", "缺少工作空间上下文会返回错误"},
            exampleInput = "{\"propsJson\":\"[{\\\"name\\\":\\\"星辰魔法杖\\\",\\\"description\\\":\\\"古老的橡木魔法杖，杖头镶嵌蓝色水晶\\\",\\\"propType\\\":\\\"WEAPON\\\",\\\"fixedDesc\\\":\\\"深棕色橡木杖身，杖头蓝色水晶散发微光，杖身刻有符文\\\",\\\"appearanceData\\\":{\\\"material\\\":\\\"wood\\\",\\\"texture\\\":\\\"rough\\\",\\\"color\\\":\\\"dark brown\\\",\\\"secondaryColor\\\":\\\"blue\\\",\\\"size\\\":\\\"medium\\\",\\\"shape\\\":\\\"cylindrical with crystal tip\\\",\\\"condition\\\":\\\"antique\\\",\\\"distinguishingFeatures\\\":[\\\"杖头蓝色水晶\\\",\\\"符文雕刻\\\"],\\\"specialEffects\\\":\\\"微光闪烁\\\",\\\"artStyle\\\":\\\"fantasy\\\"}}]\"}",
            exampleOutput = "{\"success\":true,\"props\":[{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"name\":\"魔法杖\"}],\"count\":1}"
    )
    @AgentToolOutput(
            description = "返回创建后的道具列表，以及已跳过项信息。",
            example = "{\"success\":true,\"props\":[{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"name\":\"魔法杖\"}],\"created\":[\"魔法杖\"]}"
    )
    public Map<String, Object> batchCreateProps(
            @AgentToolParamSpec(example = "[{\"name\":\"星辰魔法杖\",\"description\":\"古老的橡木魔法杖，杖头镶嵌蓝色水晶\",\"propType\":\"WEAPON\",\"fixedDesc\":\"深棕色橡木杖身，杖头蓝色水晶散发微光\",\"appearanceData\":{\"material\":\"wood\",\"texture\":\"rough\",\"color\":\"dark brown\",\"secondaryColor\":\"blue\",\"size\":\"medium\",\"shape\":\"cylindrical with crystal tip\",\"condition\":\"antique\",\"distinguishingFeatures\":[\"杖头蓝色水晶\",\"符文雕刻\"],\"specialEffects\":\"微光闪烁\",\"artStyle\":\"fantasy\"}}]")
            @ToolParam(description = "道具JSON数组，例如: [{\"name\":\"魔法杖\",\"description\":\"古老的木质魔法杖\",\"appearanceData\":{\"material\":\"wood\",\"condition\":\"antique\"}},{\"name\":\"宝剑\",\"description\":\"锋利的长剑\",\"appearanceData\":{\"material\":\"metal\"}}]") String propsJson) {

        Map<String, Object> validationError = validateRequired(propsJson, "道具JSON数组");
        if (validationError != null) return validationError;

        return execute("批量创建道具", () -> {
            UserContext userContext = UserContextHolder.getContext();
            String workspaceId = userContext != null ? userContext.getWorkspaceId() : null;
            String userId = userContext != null ? userContext.getUserId() : "system";
            if (workspaceId == null) return error("缺少工作空间上下文");

            List<Map<String, Object>> requests = parseJsonArray(propsJson);
            if (requests.isEmpty()) return error("道具列表不能为空");

            // 获取当前脚本ID用于查询已存在的道具
            String scriptId = AgentContextHolder.getScriptId();

            // 幂等性检查：查询已存在的同名道具
            Set<String> existingNames = new HashSet<>();
            List<Map<String, Object>> existingProps = new ArrayList<>();

            if (scriptId != null) {
                try {
                    List<String> requestedNames = requests.stream()
                            .map(r -> (String) r.get("name"))
                            .filter(name -> name != null && !name.isBlank())
                            .toList();

                    var existingResult = projectClient.listAvailableProps(scriptId, null, 500);
                    if (existingResult.isSuccess() && existingResult.getData() != null) {
                        for (Map<String, Object> prop : existingResult.getData()) {
                            String name = (String) prop.get("name");
                            if (name != null && requestedNames.contains(name)) {
                                existingNames.add(name);
                                existingProps.add(prop);
                                log.info("Prop already exists, will skip creation: name={}, id={}",
                                        name, prop.get("id"));
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to query existing props for idempotency check: {}", e.getMessage());
                }
            }

            // 过滤掉已存在的道具
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
                log.info("All {} props already exist, skipping creation", existingNames.size());
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("props", existingProps);
                response.put("count", existingProps.size());
                response.put("message", "所有道具已存在，无需重复创建: " + String.join(", ", existingNames));
                response.put("skipped", existingNames);
                return response;
            }

            var result = projectClient.batchCreateProps(workspaceId, userId, toCreate);
            if (result.isSuccess()) {
                List<Map<String, Object>> createdProps = result.getData();
                List<String> createdNames = createdProps.stream()
                        .map(c -> (String) c.get("name"))
                        .toList();

                List<Map<String, Object>> allProps = new ArrayList<>(existingProps);
                allProps.addAll(createdProps);

                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("props", allProps);
                response.put("count", allProps.size());

                if (!existingNames.isEmpty()) {
                    response.put("message", String.format("成功创建 %d 个道具: %s；跳过 %d 个已存在道具: %s",
                            createdProps.size(), String.join(", ", createdNames),
                            existingNames.size(), String.join(", ", existingNames)));
                    response.put("created", createdNames);
                    response.put("skipped", existingNames);
                } else {
                    response.put("message", "成功创建 " + createdProps.size() + " 个道具: " + String.join(", ", createdNames));
                    response.put("created", createdNames);
                }

                log.info("Batch create props completed: created={}, skipped={}",
                        createdProps.size(), existingNames.size());
                return response;
            }
            return error("批量创建道具失败: " + result.getMessage());
        });
    }

    @Tool(name = "batch_delete_props", description = "批量软删除道具（删除入回收站，可恢复）。" +
            "工具内部强制弹 HITL 确认对话框，用户拒绝/超时则不执行任何删除并返回 cancelled=true。" +
            "建议先用 query_props 核对待删除 ID。返回 {success, deleted, failed, cancelled}。")
    @AgentToolSpec(
            displayName = "批量删除道具",
            summary = "对一组道具 ID 执行软删除，删除前必须经用户在前端确认。",
            purpose = "在用户明确请求删除若干道具时使用；底层走逻辑删除，可恢复。",
            actionType = ToolActionType.WRITE,
            tags = {"prop", "batch", "destructive"},
            usageNotes = {"破坏性操作，工具内部已强制 HITL 确认；不要再额外调用 ask_user_confirm",
                    "软删除可恢复（deleted=1）"},
            errorCases = {"propIdsJson 解析失败 / 为空时返回错误",
                    "无活跃会话时返回错误",
                    "用户拒绝或超时返回 cancelled=true"},
            exampleInput = "{\"propIdsJson\":\"[\\\"prop-1\\\",\\\"prop-2\\\"]\"}",
            exampleOutput = "{\"success\":true,\"deleted\":[\"prop-1\",\"prop-2\"],\"failed\":[],\"cancelled\":false}"
    )
    @AgentToolOutput(
            description = "返回成功/失败/取消的汇总。",
            example = "{\"success\":true,\"deleted\":[\"prop-1\"],\"failed\":[],\"cancelled\":false}"
    )
    public Map<String, Object> batchDeleteProps(
            @ToolParam(description = "道具ID JSON 数组，例: [\"id1\",\"id2\"]") String propIdsJson) {
        return executeBatchDelete("道具", propIdsJson, projectClient::deleteProp);
    }
}
