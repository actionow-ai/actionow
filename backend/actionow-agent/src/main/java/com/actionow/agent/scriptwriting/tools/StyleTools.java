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
 * 风格工具类（SAA v2）
 * 用于管理画风、视觉风格和 AI 绘图参数
 *
 * @author Actionow
 */
@Slf4j
@Component
public class StyleTools extends AbstractProjectTool {

    public StyleTools(ProjectFeignClient projectClient, HitlConfirmationHelper hitl) {
        super(projectClient, hitl);
    }

    @Tool(name = "list_styles", description = "查询剧本可用的风格列表（含工作空间级和剧本级），支持按名称/描述模糊搜索")
    @AgentToolSpec(
            displayName = "轻量列出风格",
            summary = "按剧本快速列出可用风格。",
            purpose = "用于轻量浏览剧本下可用风格，而不是进行复杂分页搜索。",
            actionType = ToolActionType.SEARCH,
            tags = {"style", "list"},
            usageNotes = {"scriptId 为必填", "复杂搜索优先使用 query_styles"},
            errorCases = {"scriptId 为空时会返回校验错误"},
            exampleInput = "{\"scriptId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"keyword\":\"赛博\"}",
            exampleOutput = "{\"success\":true,\"styles\":[{\"id\":\"sty_xxx\",\"name\":\"赛博朋克\"}]}"
    )
    @AgentToolOutput(
            description = "返回当前剧本可用的风格列表。",
            example = "{\"success\":true,\"styles\":[{\"id\":\"sty_xxx\",\"name\":\"赛博朋克\"}],\"count\":1}"
    )
    public Map<String, Object> listStyles(
            @AgentToolParamSpec(example = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
            @ToolParam(description = "剧本ID（必填）") String scriptId,
            @ToolParam(description = "搜索关键词（匹配名称、描述）", required = false) String keyword,
            @AgentToolParamSpec(defaultValue = "50", example = "20")
            @ToolParam(description = "返回数量限制，默认50", required = false) Integer limit) {

        Map<String, Object> validationError = validateRequired(scriptId, "剧本ID");
        if (validationError != null) return validationError;

        return execute("查询风格", () ->
                handleListResult(projectClient.listAvailableStyles(scriptId, keyword, limit), "查询风格", "styles"));
    }

    @Tool(name = "query_styles", description = "搜索风格（可搜索列表）。默认返回当前剧本可用的所有风格（含系统级/工作空间级/剧本级），支持按关键字模糊搜索名称、描述、固定描述词、绘图参数配置、附加信息，支持分页和排序。")
    @AgentToolSpec(
            displayName = "搜索风格",
            summary = "按关键字和分页条件搜索风格。",
            purpose = "当风格数量较多、需要过滤或排序时使用。",
            actionType = ToolActionType.SEARCH,
            tags = {"style", "query", "search"},
            usageNotes = {"未提供 scriptId 时会尝试从上下文推断", "已知精确 styleId 时优先使用 get_style"},
            errorCases = {"分页和排序参数非法时由下游接口返回错误"},
            exampleInput = "{\"keyword\":\"赛博\",\"scriptId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"pageNum\":1}",
            exampleOutput = "{\"success\":true,\"data\":{\"records\":[]},\"message\":\"搜索风格成功\"}"
    )
    @AgentToolOutput(
            description = "返回分页风格搜索结果。",
            example = "{\"success\":true,\"data\":{\"records\":[{\"id\":\"sty_xxx\",\"name\":\"赛博朋克\"}]},\"message\":\"搜索风格成功\"}"
    )
    public Map<String, Object> queryStyles(
            @ToolParam(description = "搜索关键字", required = false) String keyword,
            @AgentToolParamSpec(example = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
            @ToolParam(description = "剧本ID（默认使用当前会话剧本）", required = false) String scriptId,
            @AgentToolParamSpec(defaultValue = "1", example = "1")
            @ToolParam(description = "页码，默认1", required = false) Integer pageNum,
            @AgentToolParamSpec(defaultValue = "20", example = "20")
            @ToolParam(description = "每页数量，默认20", required = false) Integer pageSize,
            @AgentToolParamSpec(defaultValue = "created_at", enumValues = {"name", "created_at", "updated_at"})
            @ToolParam(description = "排序字段: name/created_at/updated_at，默认created_at", required = false) String orderBy,
            @AgentToolParamSpec(defaultValue = "desc", enumValues = {"asc", "desc"})
            @ToolParam(description = "排序方向: asc/desc，默认desc", required = false) String orderDir) {

        return execute("搜索风格", () -> {
            String resolvedScriptId = resolveScriptId(scriptId);
            return handleResult(projectClient.queryStyles(
                    null, resolvedScriptId, keyword,
                    pageNum, pageSize, orderBy, orderDir),
                    "搜索风格", data -> {
                        Map<String, Object> response = new HashMap<>();
                        response.put("success", true);
                        response.put("data", data);
                        response.put("message", "搜索风格成功");
                        return response;
                    });
        });
    }

    @Tool(name = "get_style", description = "获取风格详细信息，包括AI绘图参数配置")
    @AgentToolSpec(
            displayName = "获取风格详情",
            summary = "按风格 ID 获取完整风格信息。",
            purpose = "用于读取风格描述、固定提示词和 AI 参数配置。",
            actionType = ToolActionType.READ,
            tags = {"style", "detail"},
            usageNotes = {"已知 styleId 时优先使用本工具"},
            errorCases = {"styleId 为空时会返回校验错误"},
            exampleInput = "{\"styleId\":\"sty_xxx\"}",
            exampleOutput = "{\"success\":true,\"style\":{\"id\":\"sty_xxx\",\"name\":\"赛博朋克\"}}"
    )
    @AgentToolOutput(
            description = "返回单个风格详情。",
            example = "{\"success\":true,\"style\":{\"id\":\"sty_xxx\",\"name\":\"赛博朋克\",\"styleType\":\"CYBERPUNK\"}}"
    )
    public Map<String, Object> getStyle(
            @AgentToolParamSpec(example = "sty_xxx")
            @ToolParam(description = "风格ID（必填）") String styleId) {

        Map<String, Object> validationError = validateRequired(styleId, "风格ID");
        if (validationError != null) return validationError;

        return execute("获取风格", () ->
                handleResult(projectClient.getStyle(styleId), "获取风格", data -> successData("style", data)));
    }

    @Tool(name = "update_style", description = "更新风格信息。支持三种保存方式：OVERWRITE(覆盖当前版本)、NEW_VERSION(存为新版本，推荐)、NEW_ENTITY(另存为新实体)")
    @AgentToolSpec(
            displayName = "更新风格",
            summary = "更新风格名称、描述、固定提示词和 AI 参数。",
            purpose = "用于维护项目视觉风格的一致性，并通过版本化保存保留历史。",
            actionType = ToolActionType.WRITE,
            tags = {"style", "update", "versioning"},
            usageNotes = {"推荐使用 NEW_VERSION", "styleParams 建议传 JSON 字符串"},
            errorCases = {"styleId 为空时会返回校验错误"},
            exampleInput = "{\"styleId\":\"sty_xxx\",\"name\":\"赛博朋克2.0\",\"saveMode\":\"NEW_VERSION\"}",
            exampleOutput = "{\"success\":true,\"styleId\":\"sty_xxx\",\"message\":\"风格更新成功\"}"
    )
    @AgentToolOutput(
            description = "返回更新后的风格 ID、版本号和成功消息。",
            example = "{\"success\":true,\"styleId\":\"sty_xxx\",\"versionNumber\":2,\"message\":\"风格已保存为新版本\"}"
    )
    public Map<String, Object> updateStyle(
            @AgentToolParamSpec(example = "sty_xxx")
            @ToolParam(description = "风格ID（必填）") String styleId,
            @ToolParam(description = "风格名称", required = false) String name,
            @ToolParam(description = "风格详细描述", required = false) String description,
            @ToolParam(description = "固定描述词", required = false) String fixedDesc,
            @AgentToolParamSpec(enumValues = {"REALISTIC", "ANIME", "COMIC", "CYBERPUNK", "INK_WASH", "PIXEL", "CUSTOM"})
            @ToolParam(description = "风格类型: REALISTIC, ANIME, COMIC, CYBERPUNK, INK_WASH, PIXEL, CUSTOM", required = false) String styleType,
            @ToolParam(description = "AI绘图参数配置，JSON格式。常用字段: " +
                    "baseStyle(基础风格如anime/realistic/comic), " +
                    "colorPalette(配色数组如[\"neon blue\",\"dark gray\"]), " +
                    "lighting(光照如neon/natural/dramatic), " +
                    "contrast(对比度如high/low/normal), " +
                    "mood(氛围如dystopian/peaceful/romantic), " +
                    "renderEngine(渲染引擎如stable-diffusion/midjourney), " +
                    "cfgScale(提示词权重如7.5), " +
                    "steps(推理步数如30), " +
                    "sampler(采样器如euler_a/dpm++)", required = false) String styleParams,
            @AgentToolParamSpec(defaultValue = "NEW_VERSION", enumValues = {"OVERWRITE", "NEW_VERSION", "NEW_ENTITY"})
            @ToolParam(description = "保存方式: OVERWRITE(覆盖), NEW_VERSION(新版本), NEW_ENTITY(新实体)，默认NEW_VERSION", required = false) String saveMode) {

        Map<String, Object> validationError = validateRequired(styleId, "风格ID");
        if (validationError != null) return validationError;

        return execute("更新风格", () -> {
            Map<String, Object> request = new HashMap<>();

            String mode = getDefaultSaveMode(saveMode);
            request.put("saveMode", mode);

            addIfNotBlank(request, "name", name);
            addIfNotBlank(request, "description", description);
            addIfNotBlank(request, "fixedDesc", fixedDesc);
            addIfNotBlank(request, "styleType", styleType);
            addIfNotBlank(request, "styleParams", styleParams);

            return handleResult(projectClient.updateStyle(styleId, request), "更新风格", data -> {
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("styleId", data.getOrDefault("id", styleId));
                response.put("versionNumber", data.getOrDefault("versionNumber", 1));
                response.put("message", buildVersionMessage("风格", mode, data));
                return response;
            });
        });
    }

    @Tool(name = "batch_create_styles", description = "批量创建多个风格（自动跳过已存在的同名风格）。接受JSON数组，每个元素包含: name(必填), description(必填), scriptId(可选), fixedDesc(可选), styleType(可选), styleParams(可选)")
    @AgentToolSpec(
            displayName = "批量创建风格",
            summary = "一次性创建多个风格，并自动跳过同名已存在项。",
            purpose = "用于快速搭建项目的风格体系或导入一组候选视觉风格。",
            actionType = ToolActionType.WRITE,
            tags = {"style", "batch", "creation"},
            usageNotes = {"请求体为 JSON 数组字符串", "若当前会话绑定 scriptId，会自动注入为 SCRIPT 级风格"},
            errorCases = {"stylesJson 为空时会返回错误"},
            exampleInput = "{\"stylesJson\":\"[{\\\"name\\\":\\\"赛博朋克-霓虹\\\",\\\"description\\\":\\\"以霓虹灯光和暗色调为主的赛博朋克视觉风格\\\",\\\"styleType\\\":\\\"CYBERPUNK\\\",\\\"fixedDesc\\\":\\\"neon lights, dark atmosphere, cyberpunk city, rain-soaked streets, holographic signs\\\",\\\"styleParams\\\":{\\\"baseStyle\\\":\\\"cyberpunk\\\",\\\"colorPalette\\\":[\\\"neon blue\\\",\\\"neon pink\\\",\\\"dark gray\\\"],\\\"lighting\\\":\\\"neon\\\",\\\"contrast\\\":\\\"high\\\",\\\"mood\\\":\\\"dystopian\\\",\\\"renderEngine\\\":\\\"stable-diffusion\\\",\\\"cfgScale\\\":7.5,\\\"steps\\\":30}}]\"}",
            exampleOutput = "{\"success\":true,\"styles\":[{\"id\":\"sty_xxx\",\"name\":\"赛博朋克\"}],\"count\":1}"
    )
    @AgentToolOutput(
            description = "返回创建后的风格列表，以及已跳过项的信息。",
            example = "{\"success\":true,\"styles\":[{\"id\":\"sty_xxx\",\"name\":\"赛博朋克\"}],\"created\":[\"赛博朋克\"]}"
    )
    public Map<String, Object> batchCreateStyles(
            @AgentToolParamSpec(example = "[{\"name\":\"赛博朋克-霓虹\",\"description\":\"以霓虹灯光和暗色调为主的赛博朋克视觉风格\",\"styleType\":\"CYBERPUNK\",\"fixedDesc\":\"neon lights, dark atmosphere, cyberpunk city\",\"styleParams\":{\"baseStyle\":\"cyberpunk\",\"colorPalette\":[\"neon blue\",\"neon pink\"],\"lighting\":\"neon\",\"contrast\":\"high\",\"mood\":\"dystopian\",\"cfgScale\":7.5,\"steps\":30}}]")
            @ToolParam(description = "风格JSON数组，例如: [{\"name\":\"赛博朋克\",\"description\":\"霓虹灯光与暗色调\"},{\"name\":\"水墨国风\",\"description\":\"中国传统水墨画风格\"}]") String stylesJson) {

        Map<String, Object> validationError = validateRequired(stylesJson, "风格JSON数组");
        if (validationError != null) return validationError;

        return execute("批量创建风格", () -> {
            UserContext userContext = UserContextHolder.getContext();
            String workspaceId = userContext != null ? userContext.getWorkspaceId() : null;
            String userId = userContext != null ? userContext.getUserId() : "system";
            if (workspaceId == null) return error("缺少工作空间上下文");

            List<Map<String, Object>> requests = parseJsonArray(stylesJson);
            if (requests.isEmpty()) return error("风格列表不能为空");

            // 获取当前脚本ID用于查询已存在的风格
            String scriptId = AgentContextHolder.getScriptId();

            // 幂等性检查：查询已存在的同名风格
            Set<String> existingNames = new HashSet<>();
            List<Map<String, Object>> existingStyles = new ArrayList<>();

            if (scriptId != null) {
                try {
                    List<String> requestedNames = requests.stream()
                            .map(r -> (String) r.get("name"))
                            .filter(name -> name != null && !name.isBlank())
                            .toList();

                    var existingResult = projectClient.listAvailableStyles(scriptId, null, 500);
                    if (existingResult.isSuccess() && existingResult.getData() != null) {
                        for (Map<String, Object> style : existingResult.getData()) {
                            String name = (String) style.get("name");
                            if (name != null && requestedNames.contains(name)) {
                                existingNames.add(name);
                                existingStyles.add(style);
                                log.info("Style already exists, will skip creation: name={}, id={}",
                                        name, style.get("id"));
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to query existing styles for idempotency check: {}", e.getMessage());
                }
            }

            // 过滤掉已存在的风格
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
                log.info("All {} styles already exist, skipping creation", existingNames.size());
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("styles", existingStyles);
                response.put("count", existingStyles.size());
                response.put("message", "所有风格已存在，无需重复创建: " + String.join(", ", existingNames));
                response.put("skipped", existingNames);
                return response;
            }

            var result = projectClient.batchCreateStyles(workspaceId, userId, toCreate);
            if (result.isSuccess()) {
                List<Map<String, Object>> createdStyles = result.getData();
                List<String> createdNames = createdStyles.stream()
                        .map(c -> (String) c.get("name"))
                        .toList();

                List<Map<String, Object>> allStyles = new ArrayList<>(existingStyles);
                allStyles.addAll(createdStyles);

                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("styles", allStyles);
                response.put("count", allStyles.size());

                if (!existingNames.isEmpty()) {
                    response.put("message", String.format("成功创建 %d 个风格: %s；跳过 %d 个已存在风格: %s",
                            createdStyles.size(), String.join(", ", createdNames),
                            existingNames.size(), String.join(", ", existingNames)));
                    response.put("created", createdNames);
                    response.put("skipped", existingNames);
                } else {
                    response.put("message", "成功创建 " + createdStyles.size() + " 个风格: " + String.join(", ", createdNames));
                    response.put("created", createdNames);
                }

                log.info("Batch create styles completed: created={}, skipped={}",
                        createdStyles.size(), existingNames.size());
                return response;
            }
            return error("批量创建风格失败: " + result.getMessage());
        });
    }

    @Tool(name = "batch_delete_styles", description = "批量软删除风格（删除入回收站，可恢复）。" +
            "工具内部强制弹 HITL 确认对话框，用户拒绝/超时则不执行任何删除并返回 cancelled=true。" +
            "建议先用 list_styles 核对待删除 ID。返回 {success, deleted, failed, cancelled}。")
    @AgentToolSpec(
            displayName = "批量删除风格",
            summary = "对一组风格 ID 执行软删除，删除前必须经用户在前端确认。",
            purpose = "在用户明确请求删除若干风格时使用；底层走逻辑删除，可恢复。",
            actionType = ToolActionType.WRITE,
            tags = {"style", "batch", "destructive"},
            usageNotes = {"破坏性操作，工具内部已强制 HITL 确认；不要再额外调用 ask_user_confirm",
                    "软删除可恢复（deleted=1）"},
            errorCases = {"styleIdsJson 解析失败 / 为空时返回错误",
                    "无活跃会话时返回错误",
                    "用户拒绝或超时返回 cancelled=true"},
            exampleInput = "{\"styleIdsJson\":\"[\\\"st-1\\\",\\\"st-2\\\"]\"}",
            exampleOutput = "{\"success\":true,\"deleted\":[\"st-1\",\"st-2\"],\"failed\":[],\"cancelled\":false}"
    )
    @AgentToolOutput(
            description = "返回成功/失败/取消的汇总。",
            example = "{\"success\":true,\"deleted\":[\"st-1\"],\"failed\":[],\"cancelled\":false}"
    )
    public Map<String, Object> batchDeleteStyles(
            @ToolParam(description = "风格ID JSON 数组，例: [\"id1\",\"id2\"]") String styleIdsJson) {
        return executeBatchDelete("风格", styleIdsJson, projectClient::deleteStyle);
    }
}
