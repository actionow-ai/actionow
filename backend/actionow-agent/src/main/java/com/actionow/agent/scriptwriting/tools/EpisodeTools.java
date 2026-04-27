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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 剧集工具类（SAA v2）
 *
 * @author Actionow
 */
@Slf4j
@Component
public class EpisodeTools extends AbstractProjectTool {

    public EpisodeTools(ProjectFeignClient projectClient, HitlConfirmationHelper hitl) {
        super(projectClient, hitl);
    }

    @Tool(name = "get_episode", description = "获取剧集详细信息，包括集号、标题、内容、状态等")
    @AgentToolSpec(
            displayName = "获取剧集详情",
            summary = "按剧集 ID 获取完整剧集信息。",
            purpose = "用于读取单个剧集的标题、内容、状态和上下文信息。",
            actionType = ToolActionType.READ,
            tags = {"episode", "detail"},
            usageNotes = {"已知 episodeId 时优先使用本工具"},
            errorCases = {"episodeId 为空时会返回校验错误"},
            exampleInput = "{\"episodeId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\"}",
            exampleOutput = "{\"success\":true,\"episode\":{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"title\":\"第一集\"}}"
    )
    @AgentToolOutput(
            description = "返回单个剧集详情对象。",
            example = "{\"success\":true,\"episode\":{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"title\":\"第一集\",\"status\":\"DRAFT\"}}"
    )
    public Map<String, Object> getEpisode(
            @AgentToolParamSpec(example = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
            @ToolParam(description = "剧集ID（必填）") String episodeId) {

        Map<String, Object> validationError = validateRequired(episodeId, "剧集ID");
        if (validationError != null) return validationError;

        return execute("获取剧集", () ->
                handleResult(projectClient.getEpisode(episodeId), "获取剧集", data -> successData("episode", data)));
    }

    @Tool(name = "query_episodes", description = "搜索剧集（可搜索列表）。支持按关键字模糊搜索标题、简介，支持按状态过滤，支持分页和排序。")
    @AgentToolSpec(
            displayName = "搜索剧集",
            summary = "按剧本、关键字和状态分页搜索剧集。",
            purpose = "用于在某个剧本下定位目标剧集，而不是直接读取单个剧集详情。",
            actionType = ToolActionType.SEARCH,
            tags = {"episode", "query", "search"},
            usageNotes = {"未提供 scriptId 时会尝试从当前会话剧本上下文推断", "已知 episodeId 时优先使用 get_episode"},
            errorCases = {"缺少 scriptId 且当前不在剧本会话时会返回错误"},
            exampleInput = "{\"scriptId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"keyword\":\"开端\",\"status\":\"DRAFT\"}",
            exampleOutput = "{\"success\":true,\"data\":{\"records\":[]},\"message\":\"搜索剧集成功\"}"
    )
    @AgentToolOutput(
            description = "返回分页剧集搜索结果。",
            example = "{\"success\":true,\"data\":{\"records\":[{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"title\":\"第一集\"}]},\"message\":\"搜索剧集成功\"}"
    )
    public Map<String, Object> queryEpisodes(
            @ToolParam(description = "搜索关键字", required = false) String keyword,
            @AgentToolParamSpec(example = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
            @ToolParam(description = "剧本ID（默认使用当前会话剧本）", required = false) String scriptId,
            @AgentToolParamSpec(enumValues = {"DRAFT", "IN_PROGRESS", "COMPLETED"})
            @ToolParam(description = "状态过滤: DRAFT/IN_PROGRESS/COMPLETED", required = false) String status,
            @AgentToolParamSpec(defaultValue = "1", example = "1")
            @ToolParam(description = "页码，默认1", required = false) Integer pageNum,
            @AgentToolParamSpec(defaultValue = "20", example = "20")
            @ToolParam(description = "每页数量，默认20", required = false) Integer pageSize,
            @AgentToolParamSpec(defaultValue = "sequence", enumValues = {"sequence", "title", "created_at", "updated_at"})
            @ToolParam(description = "排序字段: sequence/title/created_at/updated_at，默认sequence", required = false) String orderBy,
            @AgentToolParamSpec(defaultValue = "asc", enumValues = {"asc", "desc"})
            @ToolParam(description = "排序方向: asc/desc，默认asc", required = false) String orderDir) {

        return execute("搜索剧集", () -> {
            String resolvedScriptId = resolveScriptId(scriptId);
            if (resolvedScriptId == null || resolvedScriptId.isBlank()) {
                return error("scriptId不能为空，请指定剧本ID或在剧本会话中使用");
            }
            return handleResult(projectClient.queryEpisodes(
                    resolvedScriptId, status, keyword,
                    pageNum, pageSize, orderBy, orderDir),
                    "搜索剧集", data -> {
                        Map<String, Object> response = new HashMap<>();
                        response.put("success", true);
                        response.put("data", data);
                        response.put("message", "搜索剧集成功");
                        return response;
                    });
        });
    }

    @Tool(name = "update_episode", description = "更新剧集信息，可以修改标题、内容、简介等。附加信息补丁为增量合并(merge)，不会覆盖未传的字段。")
    @AgentToolSpec(
            displayName = "更新剧集",
            summary = "更新剧集标题、简介、正文、状态和附加信息补丁。",
            purpose = "用于持续完善单集内容，并在保存时控制是否覆盖或生成新版本。",
            actionType = ToolActionType.WRITE,
            tags = {"episode", "update", "versioning"},
            usageNotes = {"推荐保存模式使用 NEW_VERSION", "extraInfoPatchJson 为增量合并语义"},
            errorCases = {"episodeId 为空时会返回校验错误", "extraInfoPatchJson 非法时会返回 JSON 错误"},
            exampleInput = "{\"episodeId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"title\":\"第一集：启程\",\"saveMode\":\"NEW_VERSION\"}",
            exampleOutput = "{\"success\":true,\"episodeId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"message\":\"剧集更新成功\"}"
    )
    @AgentToolOutput(
            description = "返回更新后的剧集 ID 与成功消息。",
            example = "{\"success\":true,\"episodeId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"message\":\"剧集更新成功\"}"
    )
    public Map<String, Object> updateEpisode(
            @AgentToolParamSpec(example = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
            @ToolParam(description = "剧集ID（必填）") String episodeId,
            @ToolParam(description = "新标题", required = false) String title,
            @ToolParam(description = "新简介", required = false) String synopsis,
            @ToolParam(description = "新内容", required = false) String content,
            @AgentToolParamSpec(enumValues = {"DRAFT", "IN_PROGRESS", "COMPLETED"})
            @ToolParam(description = "状态: DRAFT(草稿), IN_PROGRESS(进行中), COMPLETED(已完成)", required = false) String status,
            @ToolParam(description = "附加信息补丁(JSON)，与现有数据合并而非替换", required = false) String extraInfoPatchJson,
            @AgentToolParamSpec(defaultValue = "NEW_VERSION", enumValues = {"OVERWRITE", "NEW_VERSION", "NEW_ENTITY"})
            @ToolParam(description = "保存模式: OVERWRITE(覆盖当前版本), NEW_VERSION(存为新版本，默认), NEW_ENTITY(另存为新实体)", required = false) String saveMode) {

        Map<String, Object> validationError = validateRequired(episodeId, "剧集ID");
        if (validationError != null) return validationError;

        return execute("更新剧集", () -> {
            Map<String, Object> request = new HashMap<>();
            addIfNotBlank(request, "title", title);
            addIfNotBlank(request, "synopsis", synopsis);
            addIfNotBlank(request, "content", content);
            addIfNotBlank(request, "status", status);
            addIfNotBlank(request, "saveMode", saveMode);

            // 附加信息补丁（merge 语义）
            if (extraInfoPatchJson != null && !extraInfoPatchJson.isBlank()) {
                try {
                    request.put("extraInfoPatch", parseJsonObject(extraInfoPatchJson));
                } catch (Exception e) {
                    return error("extraInfoPatch JSON 格式错误: " + e.getMessage());
                }
            }

            if (request.isEmpty()) {
                return error("没有需要更新的字段");
            }

            return handleResult(projectClient.updateEpisode(episodeId, request), "更新剧集", data ->
                    success("episodeId", episodeId, "剧集更新成功"));
        });
    }

    @Tool(name = "batch_create_episodes", description = "批量创建多个剧集。接受JSON数组，每个元素包含: scriptId(必填), title(必填), synopsis(可选), content(可选), sequence(可选,未指定时按数组顺序自动编号,从现有最大sequence+1开始)")
    @AgentToolSpec(
            displayName = "批量创建剧集",
            summary = "一次性创建多个剧集。",
            purpose = "用于根据大纲或拆分方案快速生成整批剧集。",
            actionType = ToolActionType.WRITE,
            tags = {"episode", "batch", "creation"},
            usageNotes = {"请求体为 JSON 数组字符串", "如果元素中未带 scriptId，会尝试从当前会话上下文推断",
                    "未指定 sequence 时，系统会自动从现有最大 sequence+1 开始按数组顺序编号"},
            errorCases = {"episodesJson 为空时会返回错误", "缺少 scriptId 且上下文无法推断时会返回错误"},
            exampleInput = "{\"episodesJson\":\"[{\\\"scriptId\\\":\\\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\\\",\\\"title\\\":\\\"第一集：启程\\\",\\\"synopsis\\\":\\\"主角收到神秘信件，踏上未知旅程\\\",\\\"content\\\":\\\"清晨的阳光洒进房间，林晓从噩梦中惊醒...\\\",\\\"sequence\\\":1},{\\\"scriptId\\\":\\\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\\\",\\\"title\\\":\\\"第二集：迷雾\\\",\\\"synopsis\\\":\\\"林晓抵达目的地，却发现一切与信中描述大相径庭\\\",\\\"sequence\\\":2}]\"}",
            exampleOutput = "{\"success\":true,\"episodes\":[{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"title\":\"第一集\"}]}"
    )
    @AgentToolOutput(
            description = "返回创建后的剧集列表。",
            example = "{\"success\":true,\"episodes\":[{\"id\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"title\":\"第一集\"}],\"count\":1}"
    )
    public Map<String, Object> batchCreateEpisodes(
            @AgentToolParamSpec(example = "[{\"scriptId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"title\":\"第一集：启程\",\"synopsis\":\"主角收到神秘信件，踏上未知旅程\",\"content\":\"清晨的阳光洒进房间...\",\"sequence\":1},{\"scriptId\":\"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx\",\"title\":\"第二集：迷雾\",\"synopsis\":\"林晓抵达目的地\",\"sequence\":2}]")
            @ToolParam(description = "剧集JSON数组，例如: [{\"scriptId\":\"xxx\",\"title\":\"第一集\"},{\"scriptId\":\"xxx\",\"title\":\"第二集\"}]") String episodesJson) {

        Map<String, Object> validationError = validateRequired(episodesJson, "剧集JSON数组");
        if (validationError != null) return validationError;

        return execute("批量创建剧集", () -> {
            UserContext userContext = UserContextHolder.getContext();
            String workspaceId = userContext != null ? userContext.getWorkspaceId() : null;
            String userId = userContext != null ? userContext.getUserId() : "system";
            if (workspaceId == null) return error("缺少工作空间上下文");

            List<Map<String, Object>> requests = new java.util.ArrayList<>(parseJsonArray(episodesJson));
            if (requests.isEmpty()) return error("剧集列表不能为空");

            String scriptId = (String) requests.get(0).get("scriptId");
            if (scriptId == null || scriptId.isBlank()) {
                scriptId = resolveScriptId(null);
            }
            if (scriptId == null || scriptId.isBlank()) return error("scriptId不能为空");

            // 自动编号：为未指定 sequence 的剧集分配序号
            boolean needsAutoSequence = requests.stream()
                    .anyMatch(r -> r.get("sequence") == null);
            if (needsAutoSequence) {
                int maxSequence = 0;
                try {
                    var existingResult = projectClient.listEpisodesByScript(scriptId, null, 1000);
                    if (existingResult.isSuccess() && existingResult.getData() != null) {
                        for (Map<String, Object> ep : existingResult.getData()) {
                            Object seq = ep.get("sequence");
                            if (seq instanceof Number) {
                                maxSequence = Math.max(maxSequence, ((Number) seq).intValue());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to query existing episodes for auto-sequence: {}", e.getMessage());
                }
                int nextSequence = maxSequence + 1;
                for (Map<String, Object> req : requests) {
                    if (req.get("sequence") == null) {
                        req.put("sequence", nextSequence++);
                    }
                }
            }

            var result = projectClient.batchCreateEpisodes(workspaceId, userId, scriptId, requests);
            if (result.isSuccess()) {
                return successList("episodes", result.getData());
            }
            return error("批量创建剧集失败: " + result.getMessage());
        });
    }

    @Tool(name = "batch_delete_episodes", description = "批量软删除剧集（删除入回收站，可恢复）。" +
            "工具内部强制弹 HITL 确认对话框，用户拒绝/超时则不执行任何删除并返回 cancelled=true。" +
            "建议先用 query_episodes 核对待删除 ID。返回 {success, deleted, failed, cancelled}。")
    @AgentToolSpec(
            displayName = "批量删除剧集",
            summary = "对一组剧集 ID 执行软删除，删除前必须经用户在前端确认。",
            purpose = "在用户明确请求删除若干剧集时使用；底层走逻辑删除，可恢复。",
            actionType = ToolActionType.WRITE,
            tags = {"episode", "batch", "destructive"},
            usageNotes = {"破坏性操作，工具内部已强制 HITL 确认；不要再额外调用 ask_user_confirm",
                    "软删除可恢复（deleted=1）"},
            errorCases = {"episodeIdsJson 解析失败 / 为空时返回错误",
                    "无活跃会话时返回错误",
                    "用户拒绝或超时返回 cancelled=true"},
            exampleInput = "{\"episodeIdsJson\":\"[\\\"ep-1\\\",\\\"ep-2\\\"]\"}",
            exampleOutput = "{\"success\":true,\"deleted\":[\"ep-1\",\"ep-2\"],\"failed\":[],\"cancelled\":false}"
    )
    @AgentToolOutput(
            description = "返回成功/失败/取消的汇总。",
            example = "{\"success\":true,\"deleted\":[\"ep-1\"],\"failed\":[],\"cancelled\":false}"
    )
    public Map<String, Object> batchDeleteEpisodes(
            @ToolParam(description = "剧集ID JSON 数组，例: [\"id1\",\"id2\"]") String episodeIdsJson) {
        return executeBatchDelete("剧集", episodeIdsJson, projectClient::deleteEpisode);
    }
}
