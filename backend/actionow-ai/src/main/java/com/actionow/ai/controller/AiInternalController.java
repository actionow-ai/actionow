package com.actionow.ai.controller;

import com.actionow.ai.dto.*;
import com.actionow.ai.dto.schema.InputParamDefinition;
import com.actionow.ai.entity.ModelProvider;
import com.actionow.ai.entity.ModelProviderExecution;
import com.actionow.ai.llm.dto.LlmCredentialsResponse;
import com.actionow.ai.llm.dto.LlmProviderResponse;
import com.actionow.ai.llm.dto.LlmTestRequest;
import com.actionow.ai.llm.dto.LlmTestResponse;
import com.actionow.ai.llm.service.LlmProviderService;
import com.actionow.ai.mapper.ModelProviderExecutionMapper;
import com.actionow.ai.plugin.PluginExecutor;
import com.actionow.ai.plugin.model.PluginConfig;
import com.actionow.ai.plugin.model.PluginExecutionRequest;
import com.actionow.ai.plugin.model.PluginExecutionResult;
import com.actionow.ai.plugin.model.PluginStreamEvent;
import com.actionow.ai.plugin.model.ResponseMode;
import com.actionow.ai.plugin.queue.ProviderInvocationFacade;
import com.actionow.ai.pricing.CreditCalculator;
import com.actionow.ai.service.AssetInputResolver;
import com.actionow.ai.service.ModelProviderService;
import com.actionow.common.api.ai.ProviderExecuteRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.actionow.common.core.context.UserContextHolder;
import com.actionow.common.core.exception.BusinessException;
import com.actionow.common.core.id.UuidGenerator;
import com.actionow.common.core.result.Result;
import com.actionow.common.core.result.ResultCode;
import com.actionow.common.security.annotation.IgnoreAuth;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 内部接口控制器
 * 供 Task 服务调用，不对外暴露
 *
 * @author Actionow
 */
@Slf4j
@RestController
@RequestMapping("/internal/ai")
@RequiredArgsConstructor
@IgnoreAuth
public class AiInternalController {

    private final ModelProviderService modelProviderService;
    private final LlmProviderService llmProviderService;
    private final PluginExecutor pluginExecutor;
    private final ProviderInvocationFacade invocationFacade;
    private final ModelProviderExecutionMapper executionMapper;
    private final AssetInputResolver assetInputResolver;
    private final ObjectMapper objectMapper;
    private final CreditCalculator creditCalculator;

    /**
     * 执行AI生成任务
     * Task 服务调用此接口执行 AI 生成
     * 支持 BLOCKING/CALLBACK/POLLING 三种模式
     *
     * @param request 执行请求
     * @return 执行结果
     */
    @PostMapping("/provider/execute")
    public Result<ProviderExecutionResultResponse> executeProvider(
            @RequestBody @Valid ProviderExecuteRequest request) {

        log.info("收到AI执行请求: providerId={}, taskId={}, responseMode={}",
                request.getProviderId(), request.getTaskId(), request.getResponseMode());

        ModelProviderExecution execution = null;
        try {
            // 获取提供商配置
            ModelProvider provider = modelProviderService.getById(request.getProviderId());
            if (provider == null) {
                throw new BusinessException(ResultCode.WORKFLOW_NOT_FOUND, "模型提供商不存在");
            }
            if (!provider.getEnabled()) {
                throw new BusinessException(ResultCode.WORKFLOW_DISABLED, "模型提供商已禁用");
            }

            // 转换为插件配置
            PluginConfig pluginConfig = modelProviderService.toPluginConfig(provider);

            // 构建输入参数 - params 已包含 prompt, negative_prompt 等参数
            Map<String, Object> inputs = request.getParams() != null
                    ? new HashMap<>(request.getParams())
                    : new HashMap<>();
            // 添加回调 URL
            inputs.put("_callback_url", request.getCallbackUrl());

            // 解析素材输入（将素材ID转换为URL或Base64）
            List<InputParamDefinition> inputSchema = convertInputSchema(provider.getInputSchema());
            if (!inputSchema.isEmpty()) {
                inputs = assetInputResolver.resolveAssetInputs(inputs, inputSchema);
            }

            // 确定响应模式
            ResponseMode responseMode = determineResponseMode(request.getResponseMode(), provider, pluginConfig);

            // 构建执行请求
            String executionId = UuidGenerator.generateUuidV7();
            PluginExecutionRequest execRequest = PluginExecutionRequest.builder()
                    .executionId(executionId)
                    .providerId(request.getProviderId())
                    .taskId(request.getTaskId())
                    .workspaceId(UserContextHolder.getWorkspaceId())
                    .userId(UserContextHolder.getUserId())
                    .inputs(inputs)
                    .responseMode(responseMode)
                    .callbackUrl(request.getCallbackUrl())
                    .build();

            // 创建执行记录
            execution = createExecutionRecord(executionId, provider, request, responseMode, inputs);

            // 执行 — IMAGE/VIDEO/AUDIO 阻塞型走 RabbitMQ 队列削峰，避免上千并发把
            // Tomcat 线程池打爆；其它（CALLBACK/POLLING/TEXT）继续直连
            PluginExecutionResult result;
            if (shouldRouteThroughQueue(provider, responseMode)) {
                log.info("[AI] routing through invocation queue: provider={} type={}",
                        provider.getId(), provider.getProviderType());
                result = invocationFacade.submitAndAwait(provider.getId(), execRequest, null);
            } else {
                result = pluginExecutor.execute(
                        provider.getPluginType().toLowerCase(), pluginConfig, execRequest);
            }

            // 更新执行记录
            updateExecutionRecord(execution, result);

            // 构建响应
            ProviderExecutionResultResponse response = buildExecutionResponse(result, responseMode);

            log.info("AI执行完成: taskId={}, executionId={}, status={}, mode={}",
                    request.getTaskId(), executionId, response.getStatus(), responseMode);

            return Result.success(response);

        } catch (Exception e) {
            log.error("AI执行失败: taskId={}, error={}", request.getTaskId(), e.getMessage(), e);
            // 更新执行记录为失败
            if (execution != null) {
                updateExecutionRecordFailed(execution, e.getMessage());
            }
            throw new BusinessException(ResultCode.FAIL.getCode(), "AI执行失败: " + e.getMessage());
        }
    }

    // ==================== 流式执行 API ====================

    /**
     * 流式执行 AI 生成任务（SSE）
     * 支持实时返回生成进度和结果
     *
     * @param request 执行请求
     * @return SSE 事件流
     */
    @PostMapping(value = "/provider/execute/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StreamEventDTO>> executeProviderStream(
            @RequestBody @Valid ProviderExecuteRequest request) {

        log.info("收到AI流式执行请求: providerId={}, taskId={}",
                request.getProviderId(), request.getTaskId());

        try {
            // 获取提供商配置
            ModelProvider provider = modelProviderService.getById(request.getProviderId());
            if (provider == null) {
                return Flux.just(ServerSentEvent.<StreamEventDTO>builder()
                        .event("error")
                        .data(StreamEventDTO.error(null, "PROVIDER_NOT_FOUND", "模型提供商不存在"))
                        .build());
            }
            if (!provider.getEnabled()) {
                return Flux.just(ServerSentEvent.<StreamEventDTO>builder()
                        .event("error")
                        .data(StreamEventDTO.error(null, "PROVIDER_DISABLED", "模型提供商已禁用"))
                        .build());
            }
            if (!Boolean.TRUE.equals(provider.getSupportsStreaming())) {
                return Flux.just(ServerSentEvent.<StreamEventDTO>builder()
                        .event("error")
                        .data(StreamEventDTO.error(null, "STREAMING_NOT_SUPPORTED", "该提供商不支持流式模式"))
                        .build());
            }

            // 转换为插件配置
            PluginConfig pluginConfig = modelProviderService.toPluginConfig(provider);

            // 构建输入参数 - params 已包含 prompt, negative_prompt 等参数
            Map<String, Object> inputs = request.getParams() != null
                    ? new HashMap<>(request.getParams())
                    : new HashMap<>();

            // 解析素材输入（将素材ID转换为URL或Base64）
            List<InputParamDefinition> inputSchema = convertInputSchema(provider.getInputSchema());
            if (!inputSchema.isEmpty()) {
                inputs = assetInputResolver.resolveAssetInputs(inputs, inputSchema);
            }

            // 构建执行请求
            String executionId = UuidGenerator.generateUuidV7();
            PluginExecutionRequest execRequest = PluginExecutionRequest.builder()
                    .executionId(executionId)
                    .providerId(request.getProviderId())
                    .taskId(request.getTaskId())
                    .workspaceId(UserContextHolder.getWorkspaceId())
                    .userId(UserContextHolder.getUserId())
                    .inputs(inputs)
                    .responseMode(ResponseMode.STREAMING)
                    .build();

            // 创建执行记录
            ModelProviderExecution execution = createExecutionRecord(
                    executionId, provider, request, ResponseMode.STREAMING, inputs);

            // 用于追踪流式执行结果
            final long startTime = System.currentTimeMillis();

            // 执行流式请求 - 使用 pluginType 查找插件
            return pluginExecutor.executeStream(provider.getPluginType().toLowerCase(), pluginConfig, execRequest)
                    .map(this::convertToServerSentEvent)
                    .doOnSubscribe(s -> {
                        log.info("流式执行开始: taskId={}, executionId={}", request.getTaskId(), executionId);
                        execution.setStatus("RUNNING");
                        execution.setStartedAt(LocalDateTime.now());
                        executionMapper.updateById(execution);
                    })
                    .doOnComplete(() -> {
                        log.info("流式执行完成: taskId={}, executionId={}", request.getTaskId(), executionId);
                        execution.setStatus("SUCCEEDED");
                        execution.setCompletedAt(LocalDateTime.now());
                        execution.setElapsedTime(System.currentTimeMillis() - startTime);
                        executionMapper.updateById(execution);
                    })
                    .doOnError(e -> {
                        log.error("流式执行失败: taskId={}, error={}", request.getTaskId(), e.getMessage());
                        execution.setStatus("FAILED");
                        execution.setErrorMessage(e.getMessage());
                        execution.setCompletedAt(LocalDateTime.now());
                        execution.setElapsedTime(System.currentTimeMillis() - startTime);
                        executionMapper.updateById(execution);
                    })
                    .onErrorResume(e -> Flux.just(ServerSentEvent.<StreamEventDTO>builder()
                            .event("error")
                            .data(StreamEventDTO.error(executionId, "STREAM_ERROR", e.getMessage()))
                            .build()));

        } catch (Exception e) {
            log.error("AI流式执行初始化失败: taskId={}, error={}", request.getTaskId(), e.getMessage(), e);
            return Flux.just(ServerSentEvent.<StreamEventDTO>builder()
                    .event("error")
                    .data(StreamEventDTO.error(null, "INIT_ERROR", e.getMessage()))
                    .build());
        }
    }

    /**
     * 转换 PluginStreamEvent 为 ServerSentEvent
     */
    private ServerSentEvent<StreamEventDTO> convertToServerSentEvent(PluginStreamEvent event) {
        StreamEventDTO dto = StreamEventDTO.builder()
                .eventType(event.getEventType().name())
                .executionId(event.getExecutionId())
                .externalTaskId(event.getExternalTaskId())
                .data(event.getData())
                .textDelta(event.getTextDelta())
                .textAccumulated(event.getTextAccumulated())
                .progress(event.getProgress())
                .currentStep(event.getCurrentStep())
                .errorCode(event.getErrorCode())
                .errorMessage(event.getErrorMessage())
                .timestamp(event.getTimestamp() != null ? event.getTimestamp().toString() : null)
                .build();

        String eventName = switch (event.getEventType()) {
            case WORKFLOW_STARTED -> "started";
            case NODE_STARTED, NODE_FINISHED -> "node";
            case TEXT_CHUNK -> "text";
            case PROGRESS -> "progress";
            case WORKFLOW_FINISHED -> "finished";
            case ERROR -> "error";
            case PING -> "ping";
            default -> "message";
        };

        return ServerSentEvent.<StreamEventDTO>builder()
                .event(eventName)
                .data(dto)
                .build();
    }

    /**
     * 确定响应模式
     * 优先使用请求指定的模式，否则根据提供商支持情况自动选择
     */
    private ResponseMode determineResponseMode(String requestedMode, ModelProvider provider, PluginConfig config) {
        // 如果请求指定了模式
        if (StringUtils.hasText(requestedMode)) {
            try {
                ResponseMode mode = ResponseMode.valueOf(requestedMode.toUpperCase());
                // 验证提供商是否支持该模式
                if (isModeSupported(mode, provider)) {
                    return mode;
                }
                log.warn("提供商 {} 不支持模式 {}，将自动选择", provider.getId(), mode);
            } catch (IllegalArgumentException e) {
                log.warn("无效的响应模式: {}，将自动选择", requestedMode);
            }
        }

        // 自动选择：优先 BLOCKING，然后 POLLING，最后 CALLBACK
        if (Boolean.TRUE.equals(provider.getSupportsBlocking())) {
            return ResponseMode.BLOCKING;
        } else if (Boolean.TRUE.equals(provider.getSupportsPolling())) {
            return ResponseMode.POLLING;
        } else if (Boolean.TRUE.equals(provider.getSupportsCallback())) {
            return ResponseMode.CALLBACK;
        }

        // 默认阻塞
        return ResponseMode.BLOCKING;
    }

    /**
     * 判断该次执行是否应该走 RabbitMQ 队列。
     * 仅对长耗时阻塞型（IMAGE/VIDEO/AUDIO + BLOCKING 模式）启用队列削峰；
     * 其它模式（POLLING/CALLBACK）本身已经异步，TEXT 类延迟低也不需要排队。
     */
    private boolean shouldRouteThroughQueue(ModelProvider provider, ResponseMode mode) {
        if (mode != ResponseMode.BLOCKING) {
            return false;
        }
        String type = provider.getProviderType();
        if (type == null) {
            return false;
        }
        String upper = type.toUpperCase();
        return "IMAGE".equals(upper) || "VIDEO".equals(upper) || "AUDIO".equals(upper);
    }

    /**
     * 检查提供商是否支持指定模式
     */
    private boolean isModeSupported(ResponseMode mode, ModelProvider provider) {
        return switch (mode) {
            case BLOCKING -> Boolean.TRUE.equals(provider.getSupportsBlocking());
            case STREAMING -> Boolean.TRUE.equals(provider.getSupportsStreaming());
            case CALLBACK -> Boolean.TRUE.equals(provider.getSupportsCallback());
            case POLLING -> Boolean.TRUE.equals(provider.getSupportsPolling());
        };
    }

    /**
     * 构建执行响应
     */
    private ProviderExecutionResultResponse buildExecutionResponse(PluginExecutionResult result, ResponseMode mode) {
        // 判断是否成功
        boolean isSuccess = result.getStatus() == PluginExecutionResult.ExecutionStatus.SUCCEEDED;
        boolean isPending = result.getStatus() == PluginExecutionResult.ExecutionStatus.PENDING
                || result.getStatus() == PluginExecutionResult.ExecutionStatus.RUNNING;

        ProviderExecutionResultResponse.ProviderExecutionResultResponseBuilder builder = ProviderExecutionResultResponse.builder()
                .executionId(result.getExecutionId())
                .externalRunId(result.getExternalRunId())
                .externalTaskId(result.getExternalTaskId())
                .status(result.getStatus() != null ? result.getStatus().name() : null)
                .responseMode(mode.name())
                .elapsedTimeMs(result.getElapsedTimeMs())
                .errorCode(result.getErrorCode())
                .errorMessage(result.getErrorMessage())
                // 对于 CALLBACK/POLLING 模式，PENDING 状态也算成功提交
                .success(isSuccess || (isPending && mode != ResponseMode.BLOCKING));

        log.debug("PluginExecutionResult: {}", result);

        if (isSuccess && result.getOutputs() != null) {
            builder.outputs(result.getOutputs())
                    .fileUrl((String) result.getOutputs().get("file_url"))
                    .fileKey((String) result.getOutputs().get("file_key"))
                    .thumbnailUrl((String) result.getOutputs().get("thumbnail_url"))
                    .mimeType((String) result.getOutputs().get("mime_type"));

            Object fileSizeObj = result.getOutputs().get("file_size");
            if (fileSizeObj instanceof Number) {
                builder.fileSize(((Number) fileSizeObj).longValue());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> metaInfo = (Map<String, Object>) result.getOutputs().get("meta_info");
            builder.metaInfo(metaInfo);
        }

        return builder.build();
    }

    /**
     * 获取可用的 AI 提供商列表
     *
     * @param providerType 提供商类型（IMAGE/VIDEO/AUDIO/TEXT）
     * @return 提供商列表
     */
    @GetMapping("/provider/available")
    public Result<List<AvailableProviderResponse>> getAvailableProviders(
            @RequestParam("providerType") String providerType) {
        // workspaceId 由 UserContextFilter 从 internal JWT claims 解出后写入 ThreadLocal
        String workspaceId = UserContextHolder.getWorkspaceId();
        List<ModelProvider> providers = modelProviderService.findVisibleEnabledByType(providerType, workspaceId);
        List<AvailableProviderResponse> responses = providers.stream()
                .map(this::convertToAvailableProvider)
                .collect(Collectors.toList());
        return Result.success(responses);
    }

    /**
     * 获取提供商详情
     *
     * @param providerId 提供商 ID
     * @return 提供商详情
     */
    @GetMapping("/provider/detail")
    public Result<AvailableProviderResponse> getProviderDetail(
            @RequestParam("providerId") String providerId) {
        ModelProvider provider = modelProviderService.findById(providerId).orElse(null);
        if (provider == null) {
            return Result.fail(ResultCode.NOT_FOUND.getCode(), "提供商不存在");
        }
        return Result.success(convertToAvailableProvider(provider));
    }

    /**
     * 转换为 AvailableProviderResponse
     */
    private AvailableProviderResponse convertToAvailableProvider(ModelProvider provider) {
        return AvailableProviderResponse.builder()
                .id(provider.getId())
                .name(provider.getName())
                .description(provider.getDescription())
                .providerType(provider.getProviderType())
                .iconUrl(provider.getIconUrl())
                .creditCost(provider.getCreditCost())
                .pricingRules(provider.getPricingRules())
                .pricingScript(provider.getPricingScript())
                .timeout(provider.getTimeout())
                .priority(provider.getPriority())
                .supportsStreaming(provider.getSupportsStreaming())
                .supportsBlocking(provider.getSupportsBlocking())
                .supportsCallback(provider.getSupportsCallback())
                .supportsPolling(provider.getSupportsPolling())
                .inputSchema(provider.getInputSchema())
                .inputGroups(provider.getInputGroups())
                .exclusiveGroups(provider.getExclusiveGroups())
                .build();
    }

    // ==================== 积分预估 API ====================

    /**
     * 预估积分消耗
     * 根据模型提供商的定价规则和用户参数计算积分
     *
     * @param providerId 提供商 ID
     * @param params     用户输入参数
     * @return 积分预估结果
     */
    @PostMapping("/provider/estimate-cost")
    public Result<Map<String, Object>> estimateCost(
            @RequestParam("providerId") String providerId,
            @RequestBody(required = false) Map<String, Object> params) {
        ModelProvider provider = modelProviderService.findById(providerId).orElse(null);
        if (provider == null) {
            return Result.fail(ResultCode.NOT_FOUND.getCode(), "提供商不存在");
        }

        var estimate = creditCalculator.calculate(provider, params != null ? params : Map.of());

        Map<String, Object> result = new HashMap<>();
        result.put("baseCost", estimate.getBaseCost());
        result.put("discountRate", estimate.getDiscountRate());
        result.put("discountDescription", estimate.getDiscountDescription());
        result.put("finalCost", estimate.getFinalCost());
        result.put("source", estimate.getSource());
        result.put("breakdown", estimate.getBreakdown());
        return Result.success(result);
    }

    // ==================== 轮询状态查询 API ====================

    /**
     * 查询执行状态（非阻塞）
     * 用于 POLLING 模式下查询任务状态
     *
     * @param executionId 执行ID
     * @return 当前状态
     */
    @GetMapping("/execution/{executionId}/status")
    public Result<ExecutionStatusResponse> getExecutionStatus(@PathVariable String executionId) {
        try {
            // 尝试从轮询任务中获取状态
            boolean isPolling = pluginExecutor.isPollingActive(executionId);

            ExecutionStatusResponse.ExecutionStatusResponseBuilder builder = ExecutionStatusResponse.builder()
                    .executionId(executionId);

            if (isPolling) {
                builder.status("RUNNING").message("任务正在执行中");
            } else {
                // 检查是否有已完成的结果
                PluginExecutionResult result = pluginExecutor.getPollingResultIfReady(executionId);
                if (result != null) {
                    builder.status(result.getStatus().name())
                            .completed(result.getStatus().isTerminal());
                    if (result.getStatus().isTerminal()) {
                        builder.message("任务已完成");
                    }
                } else {
                    builder.status("NOT_FOUND").message("未找到执行任务");
                }
            }

            return Result.success(builder.build());
        } catch (Exception e) {
            log.error("查询执行状态失败: executionId={}, error={}", executionId, e.getMessage());
            return Result.fail(ResultCode.FAIL.getCode(), "查询状态失败: " + e.getMessage());
        }
    }

    /**
     * 获取执行结果（阻塞等待）
     * 用于 POLLING 模式下获取最终结果
     *
     * @param executionId 执行ID
     * @param timeout     超时时间（秒），默认300秒
     * @return 执行结果
     */
    @GetMapping("/execution/{executionId}/result")
    public Result<ProviderExecutionResultResponse> getExecutionResult(
            @PathVariable String executionId,
            @RequestParam(defaultValue = "300") long timeout) {
        try {
            PluginExecutionResult result = pluginExecutor.awaitPollingResult(
                    executionId, timeout, java.util.concurrent.TimeUnit.SECONDS);

            ProviderExecutionResultResponse response = buildExecutionResponse(result, ResponseMode.POLLING);
            return Result.success(response);

        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("等待执行结果超时: executionId={}, timeout={}s", executionId, timeout);
            return Result.fail(ResultCode.TIMEOUT.getCode(), "等待超时，任务仍在执行中");
        } catch (Exception e) {
            log.error("获取执行结果失败: executionId={}, error={}", executionId, e.getMessage());
            return Result.fail(ResultCode.FAIL.getCode(), "获取结果失败: " + e.getMessage());
        }
    }

    /**
     * 取消执行
     *
     * @param executionId 执行ID
     * @return 取消结果
     */
    @PostMapping("/execution/{executionId}/cancel")
    public Result<Void> cancelExecution(
            @PathVariable String executionId,
            @RequestParam(required = false) String pluginId,
            @RequestParam(required = false) String externalTaskId,
            @RequestParam(required = false) String userId) {
        try {
            boolean cancelled = pluginExecutor.cancel(pluginId, executionId, externalTaskId, userId, null);
            if (cancelled) {
                return Result.success();
            } else {
                return Result.fail(ResultCode.FAIL.getCode(), "取消失败或任务已完成");
            }
        } catch (Exception e) {
            log.error("取消执行失败: executionId={}, error={}", executionId, e.getMessage());
            return Result.fail(ResultCode.FAIL.getCode(), "取消失败: " + e.getMessage());
        }
    }

    // ==================== 执行记录管理 ====================

    /**
     * 创建执行记录
     */
    private ModelProviderExecution createExecutionRecord(String executionId, ModelProvider provider,
                                                          ProviderExecuteRequest request, ResponseMode responseMode,
                                                          Map<String, Object> inputs) {
        ModelProviderExecution execution = new ModelProviderExecution();
        execution.setId(executionId);
        execution.setProviderId(provider.getId());
        execution.setPluginId(provider.getPluginId());
        execution.setProviderName(provider.getName());
        execution.setTaskId(request.getTaskId());
        execution.setWorkspaceId(UserContextHolder.getWorkspaceId());
        execution.setUserId(UserContextHolder.getUserId());
        execution.setResponseMode(responseMode.name());
        execution.setStatus("PENDING");
        execution.setInputData(inputs);
        execution.setCreditCost(provider.getCreditCost());
        execution.setSubmittedAt(LocalDateTime.now());
        execution.setCallbackReceived(false);
        execution.setPollCount(0);

        executionMapper.insert(execution);
        log.debug("创建执行记录: executionId={}, providerId={}, responseMode={}",
                executionId, provider.getId(), responseMode);

        return execution;
    }

    /**
     * 更新执行记录（根据执行结果）
     */
    private void updateExecutionRecord(ModelProviderExecution execution, PluginExecutionResult result) {
        // 更新状态
        if (result.getStatus() != null) {
            execution.setStatus(result.getStatus().name());
        }

        // 更新外部ID
        if (StringUtils.hasText(result.getExternalTaskId())) {
            execution.setExternalTaskId(result.getExternalTaskId());
        }
        if (StringUtils.hasText(result.getExternalRunId())) {
            execution.setExternalRunId(result.getExternalRunId());
        }

        // 更新输出数据
        if (result.getOutputs() != null) {
            execution.setOutputData(result.getOutputs());
        }

        // 更新错误信息
        if (StringUtils.hasText(result.getErrorCode())) {
            execution.setErrorCode(result.getErrorCode());
        }
        if (StringUtils.hasText(result.getErrorMessage())) {
            execution.setErrorMessage(result.getErrorMessage());
        }

        // 更新时间信息
        if (result.getElapsedTimeMs() != null && result.getElapsedTimeMs() > 0) {
            execution.setElapsedTime(result.getElapsedTimeMs());
        }
        if (result.getStartedAt() != null) {
            execution.setStartedAt(result.getStartedAt());
        }

        // 如果是终态，设置完成时间
        if (result.getStatus() != null && result.getStatus().isTerminal()) {
            execution.setCompletedAt(LocalDateTime.now());
        } else if (result.getStatus() == PluginExecutionResult.ExecutionStatus.RUNNING) {
            // 异步模式进入运行状态
            execution.setStartedAt(LocalDateTime.now());
        }

        executionMapper.updateById(execution);
        log.debug("更新执行记录: executionId={}, status={}", execution.getId(), execution.getStatus());
    }

    /**
     * 更新执行记录为失败状态
     */
    private void updateExecutionRecordFailed(ModelProviderExecution execution, String errorMessage) {
        execution.setStatus("FAILED");
        execution.setErrorMessage(errorMessage);
        execution.setCompletedAt(LocalDateTime.now());
        executionMapper.updateById(execution);
        log.debug("执行记录标记为失败: executionId={}, error={}", execution.getId(), errorMessage);
    }

    /**
     * 转换输入参数Schema（Map列表转换为InputParamDefinition列表）
     *
     * @param schemaList Map格式的参数定义列表
     * @return InputParamDefinition列表
     */
    private List<InputParamDefinition> convertInputSchema(List<Map<String, Object>> schemaList) {
        if (schemaList == null || schemaList.isEmpty()) {
            return List.of();
        }
        return schemaList.stream()
                .map(map -> objectMapper.convertValue(map, InputParamDefinition.class))
                .toList();
    }

    // ==================== LLM Provider API ====================

    /**
     * 根据 ID 获取 LLM Provider 配置
     * 供 Agent 服务调用获取模型配置
     *
     * @param id LLM Provider ID
     * @return LLM Provider 配置
     */
    @GetMapping("/llm-provider/{id}")
    public Result<LlmProviderResponse> getLlmProviderById(@PathVariable String id) {
        try {
            return llmProviderService.findById(id)
                    .map(Result::success)
                    .orElse(Result.fail(ResultCode.NOT_FOUND.getCode(), "LLM Provider 不存在: " + id));
        } catch (Exception e) {
            log.error("获取 LLM Provider 失败: id={}, error={}", id, e.getMessage());
            return Result.fail(ResultCode.FAIL.getCode(), "获取 LLM Provider 失败: " + e.getMessage());
        }
    }

    /**
     * 获取 LLM Provider 凭证（含解析后的 API Key）
     * 供 Agent 服务创建 LLM 客户端使用
     *
     * @param id LLM Provider ID
     * @return LLM 凭证信息
     */
    @GetMapping("/llm-provider/{id}/credentials")
    public Result<LlmCredentialsResponse> getLlmCredentials(@PathVariable String id) {
        try {
            return llmProviderService.getCredentials(id)
                    .map(Result::success)
                    .orElse(Result.fail(ResultCode.NOT_FOUND.getCode(), "LLM Provider 不存在: " + id));
        } catch (Exception e) {
            log.error("获取 LLM 凭证失败: id={}, error={}", id, e.getMessage());
            return Result.fail(ResultCode.FAIL.getCode(), "获取 LLM 凭证失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有启用的 LLM Provider
     *
     * @return LLM Provider 列表
     */
    @GetMapping("/llm-provider/enabled")
    public Result<List<LlmProviderResponse>> getEnabledLlmProviders() {
        try {
            List<LlmProviderResponse> providers = llmProviderService.findAllEnabled();
            return Result.success(providers);
        } catch (Exception e) {
            log.error("获取启用的 LLM Provider 列表失败: error={}", e.getMessage());
            return Result.fail(ResultCode.FAIL.getCode(), "获取 LLM Provider 列表失败: " + e.getMessage());
        }
    }

    /**
     * 根据提供商获取 LLM Provider 列表
     *
     * @param provider 提供商名称
     * @return LLM Provider 列表
     */
    @GetMapping("/llm-provider/by-provider")
    public Result<List<LlmProviderResponse>> getLlmProvidersByProvider(@RequestParam String provider) {
        try {
            List<LlmProviderResponse> providers = llmProviderService.findEnabledByProvider(provider.toUpperCase());
            return Result.success(providers);
        } catch (Exception e) {
            log.error("根据提供商获取 LLM Provider 失败: provider={}, error={}", provider, e.getMessage());
            return Result.fail(ResultCode.FAIL.getCode(), "获取 LLM Provider 失败: " + e.getMessage());
        }
    }

    /**
     * 测试 LLM Provider 可用性
     * 供内部服务调用测试模型是否正常工作
     *
     * @param id      LLM Provider ID
     * @param request 测试请求（可选）
     * @return 测试结果
     */
    @PostMapping("/llm-provider/{id}/test")
    public Result<LlmTestResponse> testLlmProvider(
            @PathVariable String id,
            @RequestBody(required = false) LlmTestRequest request) {
        try {
            LlmTestResponse result = llmProviderService.testLlm(id, request);
            return Result.success(result);
        } catch (Exception e) {
            log.error("测试 LLM Provider 失败: id={}, error={}", id, e.getMessage());
            return Result.fail(ResultCode.FAIL.getCode(), "测试 LLM Provider 失败: " + e.getMessage());
        }
    }

    /**
     * 批量测试所有启用的 LLM Provider
     *
     * @param request 测试请求（可选）
     * @return 测试结果列表
     */
    @PostMapping("/llm-provider/test-all")
    public Result<List<LlmTestResponse>> testAllLlmProviders(
            @RequestBody(required = false) LlmTestRequest request) {
        try {
            List<LlmTestResponse> results = llmProviderService.testAllEnabled(request);
            return Result.success(results);
        } catch (Exception e) {
            log.error("批量测试 LLM Provider 失败: error={}", e.getMessage());
            return Result.fail(ResultCode.FAIL.getCode(), "批量测试 LLM Provider 失败: " + e.getMessage());
        }
    }
}
