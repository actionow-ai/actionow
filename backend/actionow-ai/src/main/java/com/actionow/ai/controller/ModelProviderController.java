package com.actionow.ai.controller;

import com.actionow.ai.dto.AddProviderWhitelistRequest;
import com.actionow.ai.dto.CopyModelProviderRequest;
import com.actionow.ai.dto.CreateModelProviderRequest;
import com.actionow.ai.dto.ModelProviderResponse;
import com.actionow.ai.dto.ProviderWhitelistResponse;
import com.actionow.ai.dto.SchemaValidationResponse;
import com.actionow.ai.dto.TestExecutionRequest;
import com.actionow.ai.dto.UpdateModelProviderRequest;
import com.actionow.ai.dto.UpdateSchemaRequest;
import com.actionow.ai.entity.ModelProvider;
import com.actionow.ai.service.ModelProviderService;
import com.actionow.ai.service.ProviderWhitelistService;
import com.actionow.ai.service.schema.InputSchemaService;
import com.actionow.ai.service.schema.SchemaValidator;
import com.actionow.common.core.result.PageResult;
import com.actionow.common.core.result.Result;
import com.actionow.common.security.annotation.RequireSystemTenant;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 模型提供商管理控制器
 *
 * @author Actionow
 */
@Slf4j
@RestController
@RequestMapping("/model-providers")
@RequiredArgsConstructor
@Tag(name = "模型提供商管理", description = "AI模型提供商的增删改查和Schema管理")
@RequireSystemTenant(minRole = "ADMIN")
public class ModelProviderController {

    private final ModelProviderService providerService;
    private final InputSchemaService inputSchemaService;
    private final ObjectMapper objectMapper;
    private final ProviderWhitelistService whitelistService;

    /**
     * 创建提供商
     */
    @PostMapping
    public Result<ModelProviderResponse> create(@Valid @RequestBody CreateModelProviderRequest request) {
        ModelProvider provider = new ModelProvider();
        BeanUtils.copyProperties(request, provider);

        ModelProvider created = providerService.create(provider);
        return Result.success(ModelProviderResponse.fromEntity(created));
    }

    /**
     * 更新提供商
     */
    @PutMapping("/{id}")
    public Result<ModelProviderResponse> update(@PathVariable String id,
                                                 @Valid @RequestBody UpdateModelProviderRequest request) {
        ModelProvider provider = new ModelProvider();
        provider.setId(id);
        BeanUtils.copyProperties(request, provider);

        ModelProvider updated = providerService.update(provider);
        return Result.success(ModelProviderResponse.fromEntity(updated));
    }

    /**
     * 获取提供商详情
     */
    @GetMapping("/{id}")
    public Result<ModelProviderResponse> getById(@PathVariable String id) {
        ModelProvider provider = providerService.getById(id);
        return Result.success(ModelProviderResponse.fromEntity(provider));
    }

    /**
     * 删除提供商
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        providerService.delete(id);
        return Result.success();
    }

    /**
     * 分页查询提供商列表
     *
     * @param pageNum      页码（从1开始）
     * @param pageSize     每页大小
     * @param providerType 提供商类型（可选）
     * @param enabled      是否启用（可选）
     * @param name         名称模糊搜索（可选）
     */
    @GetMapping
    public Result<PageResult<ModelProviderResponse>> listPage(
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "20") Long pageSize,
            @RequestParam(required = false) String providerType,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String name) {
        PageResult<ModelProvider> pageResult = providerService.findPage(pageNum, pageSize, providerType, enabled, name);

        List<ModelProviderResponse> responses = pageResult.getRecords().stream()
            .map(ModelProviderResponse::fromEntity)
            .toList();

        return Result.success(PageResult.of(
            pageResult.getCurrent(),
            pageResult.getSize(),
            pageResult.getTotal(),
            responses
        ));
    }

    /**
     * 获取所有启用的提供商（全量，无分页）
     */
    @GetMapping("/all")
    public Result<List<ModelProviderResponse>> listAllEnabled() {
        List<ModelProvider> providers = providerService.findAllEnabled();
        List<ModelProviderResponse> responses = providers.stream()
            .map(ModelProviderResponse::fromEntity)
            .toList();
        return Result.success(responses);
    }

    /**
     * 根据类型获取提供商
     */
    @GetMapping("/type/{type}")
    public Result<List<ModelProviderResponse>> listByType(@PathVariable String type) {
        List<ModelProvider> providers = providerService.findEnabledByType(type.toUpperCase());
        List<ModelProviderResponse> responses = providers.stream()
            .map(ModelProviderResponse::fromEntity)
            .toList();
        return Result.success(responses);
    }

    /**
     * 启用提供商
     */
    @PostMapping("/{id}/enable")
    public Result<Void> enable(@PathVariable String id) {
        providerService.enable(id);
        return Result.success();
    }

    /**
     * 禁用提供商
     */
    @PostMapping("/{id}/disable")
    public Result<Void> disable(@PathVariable String id) {
        providerService.disable(id);
        return Result.success();
    }

    // ========== 灰度白名单管理 ==========

    /**
     * 添加 workspace 到 provider 白名单（visibility=WHITELIST 时生效）
     */
    @Operation(summary = "添加 provider 白名单")
    @PostMapping("/{id}/whitelist")
    public Result<ProviderWhitelistResponse> addWhitelist(@PathVariable("id") String providerId,
                                                          @Valid @RequestBody AddProviderWhitelistRequest request) {
        return Result.success(ProviderWhitelistResponse.fromEntity(
                whitelistService.add(providerId, request.getWorkspaceId(), request.getNote())));
    }

    /**
     * 从白名单移除某 workspace
     */
    @Operation(summary = "移除 provider 白名单")
    @DeleteMapping("/{id}/whitelist/{workspaceId}")
    public Result<Void> removeWhitelist(@PathVariable("id") String providerId,
                                        @PathVariable("workspaceId") String workspaceId) {
        whitelistService.remove(providerId, workspaceId);
        return Result.success();
    }

    /**
     * 查询 provider 当前白名单
     */
    @Operation(summary = "查询 provider 白名单")
    @GetMapping("/{id}/whitelist")
    public Result<List<ProviderWhitelistResponse>> listWhitelist(@PathVariable("id") String providerId) {
        return Result.success(whitelistService.listByProvider(providerId).stream()
                .map(ProviderWhitelistResponse::fromEntity)
                .toList());
    }

    /**
     * 测试连接
     */
    @PostMapping("/{id}/test")
    public Result<Map<String, Object>> testConnection(@PathVariable String id) {
        ModelProviderService.TestConnectionResult result = providerService.testConnection(id);

        return Result.success(Map.of(
            "connected", result.connected(),
            "message", result.message() != null ? result.message() : "",
            "latencyMs", result.latencyMs() != null ? result.latencyMs() : 0
        ));
    }

    /**
     * 测试执行模型
     * 使用提供的输入参数实际调用模型，返回执行结果
     */
    @PostMapping("/{id}/test-execution")
    public Result<Map<String, Object>> testExecution(
            @PathVariable String id,
            @Valid @RequestBody TestExecutionRequest request) {
        ModelProviderService.TestExecutionResult result = providerService.testExecution(
                id,
                request.getInputs(),
                request.getResponseMode(),
                request.getTimeoutOverride()
        );

        Map<String, Object> response = new java.util.HashMap<>();
        response.put("success", result.success());
        response.put("executionId", result.executionId());
        response.put("status", result.status());
        response.put("outputs", result.outputs());
        response.put("assets", result.assets());
        response.put("errorCode", result.errorCode());
        response.put("errorMessage", result.errorMessage());
        response.put("elapsedTimeMs", result.elapsedTimeMs());
        response.put("rawResponse", result.rawResponse());

        return Result.success(response);
    }

    /**
     * 复制模型提供商
     * 基于现有提供商创建新的提供商
     */
    @PostMapping("/{id}/copy")
    public Result<ModelProviderResponse> copy(
            @PathVariable String id,
            @Valid @RequestBody CopyModelProviderRequest request) {
        ModelProvider copied = providerService.copy(
                id,
                request.getNewName(),
                request.getNewDescription(),
                request.getEnabled()
        );
        return Result.success(ModelProviderResponse.fromEntity(copied));
    }

    /**
     * 同步提供商配置
     */
    @PostMapping("/{id}/sync")
    public Result<Void> sync(@PathVariable String id) {
        providerService.sync(id);
        return Result.success();
    }

    // ==================== Schema相关接口 ====================

    /**
     * 获取提供商的输入Schema
     */
    @Operation(summary = "获取输入Schema", description = "获取模型提供商的输入参数定义")
    @GetMapping("/{id}/input-schema")
    public Result<InputSchemaService.FormSchema> getInputSchema(@PathVariable String id) {
        InputSchemaService.FormSchema schema = inputSchemaService.getFormSchema(id);
        if (schema == null) {
            return Result.fail("模型提供商不存在");
        }
        return Result.success(schema);
    }

    /**
     * 更新提供商的输入Schema
     */
    @Operation(summary = "更新输入Schema", description = "更新模型提供商的输入参数定义")
    @PutMapping("/{id}/input-schema")
    public Result<Void> updateInputSchema(
            @PathVariable String id,
            @RequestBody UpdateSchemaRequest request) {
        ModelProvider provider = providerService.getById(id);
        if (provider == null) {
            return Result.fail("模型提供商不存在");
        }

        // 转换并更新
        provider.setInputSchema(convertToMapList(request.getParams()));
        provider.setInputGroups(convertToMapList(request.getGroups()));
        provider.setExclusiveGroups(convertToMapList(request.getExclusiveGroups()));

        providerService.update(provider);
        return Result.success();
    }

    /**
     * 验证输入数据是否符合Schema
     */
    @Operation(summary = "验证输入数据", description = "验证用户输入是否符合Schema定义")
    @PostMapping("/{id}/validate-input")
    public Result<SchemaValidationResponse> validateInput(
            @PathVariable String id,
            @RequestBody Map<String, Object> input) {
        SchemaValidator.ValidationResult result = inputSchemaService.validateInput(id, input);
        return Result.success(SchemaValidationResponse.from(result));
    }

    /**
     * 获取必填参数列表
     */
    @Operation(summary = "获取必填参数", description = "获取模型提供商的必填参数名称列表")
    @GetMapping("/{id}/required-params")
    public Result<List<String>> getRequiredParams(@PathVariable String id) {
        List<String> requiredParams = inputSchemaService.getRequiredParams(id);
        return Result.success(requiredParams);
    }

    /**
     * 合并输入与默认值
     */
    @Operation(summary = "合并默认值", description = "将用户输入与Schema默认值合并")
    @PostMapping("/{id}/merge-defaults")
    public Result<Map<String, Object>> mergeDefaults(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> input) {
        Map<String, Object> merged = inputSchemaService.mergeWithDefaults(id, input);
        return Result.success(merged);
    }

    // ==================== 内部类 ====================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> convertToMapList(List<?> list) {
        if (list == null) {
            return java.util.Collections.emptyList();
        }
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Object obj : list) {
            Map<String, Object> map = objectMapper.convertValue(obj, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            result.add(map);
        }
        return result;
    }
}
