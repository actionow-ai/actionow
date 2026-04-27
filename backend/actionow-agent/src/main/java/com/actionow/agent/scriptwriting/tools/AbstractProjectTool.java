package com.actionow.agent.scriptwriting.tools;

import com.actionow.agent.core.scope.ScopeAccessException;
import com.actionow.agent.core.scope.ScopeValidator;
import com.actionow.agent.feign.ProjectFeignClient;
import com.actionow.agent.interaction.HitlConfirmationHelper;
import com.actionow.agent.tool.response.ToolResponse;
import com.actionow.common.core.result.Result;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 项目工具抽象基类
 * 提供通用的验证、响应构建、异常处理方法
 *
 * 使用 ToolResponse 统一响应格式，确保 LLM 能够一致地解析工具执行结果
 *
 * @author Actionow
 */
@Slf4j
public abstract class AbstractProjectTool {

    protected final ProjectFeignClient projectClient;
    protected final ScopeValidator scopeValidator;
    /** 仅 batch_delete_* 类工具用到；其他场景注入 null。 */
    protected final HitlConfirmationHelper hitl;

    protected AbstractProjectTool(ProjectFeignClient projectClient, ScopeValidator scopeValidator) {
        this.projectClient = projectClient;
        this.scopeValidator = scopeValidator;
        this.hitl = null;
    }

    protected AbstractProjectTool(ProjectFeignClient projectClient) {
        this.projectClient = projectClient;
        this.scopeValidator = null;
        this.hitl = null;
    }

    /** 给需要 batch_delete_* 的子类用的构造器。 */
    protected AbstractProjectTool(ProjectFeignClient projectClient, HitlConfirmationHelper hitl) {
        this.projectClient = projectClient;
        this.scopeValidator = null;
        this.hitl = hitl;
    }

    // ==================== 验证方法 ====================

    /**
     * 验证必填字符串参数
     * @return 错误响应，如果验证通过返回 null
     */
    @Nullable
    protected Map<String, Object> validateRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            return ToolResponse.validationError(fieldName).toMap();
        }
        return null;
    }

    /**
     * 验证必填参数（非空检查）
     * @return 错误响应，如果验证通过返回 null
     */
    @Nullable
    protected Map<String, Object> validateNotNull(Object value, String fieldName) {
        if (value == null) {
            return ToolResponse.validationError(fieldName).toMap();
        }
        return null;
    }

    // ==================== 响应构建方法 ====================

    /**
     * 构建错误响应
     */
    protected Map<String, Object> error(String message) {
        return ToolResponse.error(message).toMap();
    }

    /**
     * 构建错误响应（带错误码）
     */
    protected Map<String, Object> error(String errorCode, String message) {
        return ToolResponse.error(errorCode, message).toMap();
    }

    /**
     * 构建成功响应
     */
    protected Map<String, Object> success(String key, Object value, String message) {
        return ToolResponse.success(message, key, value).toMap();
    }

    /**
     * 构建成功响应（包含数据）
     */
    protected Map<String, Object> success(Map<String, Object> data) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.putAll(data);
        return response;
    }

    /**
     * 构建成功响应（带数据键）
     */
    protected Map<String, Object> successData(String key, Object data) {
        return ToolResponse.successData(key, data).toMap();
    }

    /**
     * 构建成功响应（带数据键和计数）
     */
    protected Map<String, Object> successList(String key, java.util.List<?> data) {
        return ToolResponse.successWithCount(key, data, data.size()).toMap();
    }

    // ==================== Result 处理方法 ====================

    /**
     * 处理 Feign 返回的 Result 对象
     * @param result Feign 调用返回的结果
     * @param action 操作名称（用于错误消息）
     * @param successHandler 成功时的数据处理器
     * @return 响应 Map
     */
    protected Map<String, Object> handleResult(Result<Map<String, Object>> result, String action,
                                               java.util.function.Function<Map<String, Object>, Map<String, Object>> successHandler) {
        if (result.isSuccess()) {
            return successHandler.apply(result.getData());
        } else {
            return ToolResponse.error("FEIGN_ERROR", action + "失败: " + result.getMessage()).toMap();
        }
    }

    /**
     * 处理 Feign 返回的列表 Result 对象
     */
    protected Map<String, Object> handleListResult(Result<java.util.List<Map<String, Object>>> result,
                                                   String action, String dataKey) {
        if (result.isSuccess()) {
            return successList(dataKey, result.getData());
        } else {
            return ToolResponse.error("FEIGN_ERROR", action + "失败: " + result.getMessage()).toMap();
        }
    }

    // ==================== 异常处理方法 ====================

    /**
     * 统一异常处理并返回错误响应
     */
    protected Map<String, Object> handleException(Exception e, String action) {
        log.error("{} failed", action, e);
        return ToolResponse.error("EXECUTION_ERROR", action + "异常: " + e.getMessage()).toMap();
    }

    /**
     * 处理作用域访问异常
     */
    protected Map<String, Object> handleScopeException(ScopeAccessException e) {
        if (scopeValidator != null) {
            return scopeValidator.buildScopeErrorResponse(e);
        }
        return ToolResponse.permissionError("作用域访问受限: " + e.getMessage()).toMap();
    }

    // ==================== 执行包装方法 ====================

    /**
     * 包装执行逻辑，统一处理异常
     * @param action 操作名称（用于日志和错误消息）
     * @param execution 执行逻辑
     * @return 响应 Map
     */
    protected Map<String, Object> execute(String action, Supplier<Map<String, Object>> execution) {
        try {
            return execution.get();
        } catch (ScopeAccessException e) {
            return handleScopeException(e);
        } catch (Exception e) {
            return handleException(e, action);
        }
    }

    /**
     * 类型安全的执行包装：内部使用 {@link ToolResponse} 构建结果，
     * 自动转换为 {@code Map<String, Object>} 以兼容 Spring AI @Tool 返回值。
     * <p>
     * 推荐新编写的工具方法使用此重载，以获得编译期类型检查。
     *
     * @param action    操作名称
     * @param execution 返回 ToolResponse 的执行逻辑
     * @return 响应 Map
     */
    protected Map<String, Object> executeTyped(String action, Supplier<ToolResponse> execution) {
        try {
            return execution.get().toMap();
        } catch (ScopeAccessException e) {
            return handleScopeException(e);
        } catch (Exception e) {
            return handleException(e, action);
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 添加可选字段到请求 Map
     */
    protected void addIfNotBlank(Map<String, Object> request, String key, String value) {
        if (value != null && !value.isBlank()) {
            request.put(key, value);
        }
    }

    /**
     * 添加可选字段到请求 Map（非空对象）
     */
    protected void addIfNotNull(Map<String, Object> request, String key, Object value) {
        if (value != null) {
            request.put(key, value);
        }
    }

    /**
     * 获取当前脚本ID（从 AgentContext 获取）
     * @return 脚本ID，如果不在脚本作用域内返回 null
     */
    @Nullable
    protected String getCurrentScriptId() {
        var ctx = com.actionow.agent.core.scope.AgentContextHolder.getContext();
        return ctx != null ? ctx.getScriptId() : null;
    }

    /**
     * 解析脚本ID：如果未指定则使用当前作用域的脚本ID
     * @param scriptId 传入的脚本ID
     * @return 解析后的脚本ID，可能为 null
     */
    protected String resolveScriptId(String scriptId) {
        if (scriptId != null && !scriptId.isBlank()) {
            return scriptId;
        }
        return getCurrentScriptId();
    }

    /**
     * 构建版本保存的成功消息
     */
    protected String buildVersionMessage(String entityName, String saveMode, Map<String, Object> data) {
        return switch (saveMode) {
            case "OVERWRITE" -> entityName + "已覆盖更新";
            case "NEW_ENTITY" -> "已基于该" + entityName + "创建新实体";
            default -> entityName + "已存为新版本 V" + data.getOrDefault("versionNumber", "?");
        };
    }

    /**
     * 获取默认保存模式
     */
    protected String getDefaultSaveMode(String saveMode) {
        return (saveMode != null && !saveMode.isBlank()) ? saveMode : "NEW_VERSION";
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 解析 JSON 数组字符串为 List<Map<String, Object>>
     */
    protected List<Map<String, Object>> parseJsonArray(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 数组解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 JSON 对象字符串为 Map<String, Object>
     */
    protected Map<String, Object> parseJsonObject(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 对象解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 ID JSON 数组字符串为 {@code List<String>}（去重去空）。
     * 与 {@link #parseJsonArray} 不同，本方法仅接受字符串数组（如 {@code ["id1","id2"]}），
     * 用于 batch_delete_* 工具的 ID 列表入参。
     */
    protected List<String> parseIdArray(String json) {
        try {
            List<String> raw = OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {});
            List<String> out = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String id : raw) {
                if (id == null || id.isBlank()) continue;
                String trimmed = id.trim();
                if (seen.add(trimmed)) out.add(trimmed);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalArgumentException("ID 数组解析失败（期望 [\"id1\",\"id2\"]）: " + e.getMessage(), e);
        }
    }

    /**
     * batch_delete_* 类工具的统一执行模板：参数校验 → 强制 HITL 确认 → 逐项删除 → 标准 ToolResponse。
     * <p>
     * 调用前必须在子类构造器里注入 {@link HitlConfirmationHelper}（即用 3 参构造器）。
     * 用户拒绝 / 超时 / 无活跃会话 → 返回 {@code cancelled=true}，<b>不</b>执行任何删除。
     *
     * @param entityName 实体中文名（"角色" / "场景" 等），用于面向用户的确认提示
     * @param idsJson    工具入参 JSON 字符串数组，如 {@code ["id1","id2"]}
     * @param deleter    单项删除函数，通常是 Feign 方法引用，如 {@code projectClient::deleteCharacter}
     */
    protected Map<String, Object> executeBatchDelete(String entityName, String idsJson,
                                                     Function<String, Result<Void>> deleter) {
        Map<String, Object> validationError = validateRequired(idsJson, entityName + "ID列表");
        if (validationError != null) return validationError;
        if (hitl == null) {
            return error("CONFIG_ERROR",
                    "工具未注入 HitlConfirmationHelper，请检查子类构造器是否使用 3 参重载");
        }
        return execute("批量删除" + entityName, () -> {
            List<String> ids;
            try {
                ids = parseIdArray(idsJson);
            } catch (IllegalArgumentException iae) {
                return error(iae.getMessage());
            }
            if (ids.isEmpty()) return error(entityName + "ID列表不能为空");

            String question = "确认删除 " + ids.size() + " 个" + entityName + "？此操作可在回收站恢复。";
            HitlConfirmationHelper.ConfirmationResult cr = hitl.confirmDestructiveAction(question, 120);
            return switch (cr) {
                case HitlConfirmationHelper.ConfirmationResult.Confirmed c -> performDelete(ids, deleter);
                case HitlConfirmationHelper.ConfirmationResult.Declined d -> cancelled(d.askId(), d.reason());
                case HitlConfirmationHelper.ConfirmationResult.TimedOut t -> cancelled(t.askId(), "TIMEOUT");
                case HitlConfirmationHelper.ConfirmationResult.NoSession n -> error(
                        "批量删除必须在 chat / mission 上下文内调用（无活跃会话无法弹 HITL）");
                case HitlConfirmationHelper.ConfirmationResult.Failed f -> error("HITL 确认失败: " + f.error());
            };
        });
    }

    private Map<String, Object> cancelled(String askId, String reason) {
        Map<String, Object> data = new HashMap<>();
        data.put("cancelled", true);
        data.put("reason", reason);
        if (askId != null) data.put("askId", askId);
        data.put("deleted", List.of());
        data.put("failed", List.of());
        return ToolResponse.success("用户取消批量删除（" + reason + "），未执行任何删除", data).toMap();
    }

    /**
     * 批量逐项调用 deleter 执行删除，汇总成功 / 失败列表返回给 LLM。
     * <p>
     * 单项失败不抛异常，仅计入 {@code failed:[{id, reason}]}，让 LLM 看到完整结果决定是否重试。
     * 调用方负责在调用本方法前完成 HITL 确认与参数校验。
     *
     * @param ids     待删除 ID 列表（已去重去空）
     * @param deleter 单项删除函数，返回 Feign {@link Result}
     * @return 标准 ToolResponse Map，含 {@code deleted}（成功列表）+ {@code failed}（失败明细）
     */
    protected Map<String, Object> performDelete(List<String> ids, Function<String, Result<Void>> deleter) {
        List<String> deleted = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (String id : ids) {
            try {
                Result<Void> r = deleter.apply(id);
                if (r != null && r.isSuccess()) {
                    deleted.add(id);
                } else {
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("id", id);
                    entry.put("reason", r != null ? r.getMessage() : "Feign 返回 null");
                    failed.add(entry);
                }
            } catch (Exception e) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("id", id);
                entry.put("reason", e.getMessage());
                failed.add(entry);
                log.warn("批量删除单项失败: id={}", id, e);
            }
        }
        Map<String, Object> data = new HashMap<>();
        data.put("deleted", deleted);
        data.put("failed", failed);
        data.put("cancelled", false);
        String msg = "已删除 " + deleted.size() + " / " + ids.size()
                + (failed.isEmpty() ? "" : "，失败 " + failed.size() + " 项");
        return ToolResponse.success(msg, data).toMap();
    }
}
