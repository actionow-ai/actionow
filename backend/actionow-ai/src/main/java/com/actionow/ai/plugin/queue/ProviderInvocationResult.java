package com.actionow.ai.plugin.queue;

import com.actionow.ai.plugin.model.PluginExecutionResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * 队列调用结果（写入 Redis 供等待方拉取）。
 *
 * 三态：PENDING（已入队未完成）/ SUCCESS / FAILED。
 * 调用方通过 ProviderInvocationFacade.awaitBlocking 阻塞等待，或轮询 getStatus。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderInvocationResult implements Serializable {

    public enum Status { PENDING, SUCCESS, FAILED }

    private String requestId;
    private String providerId;
    private Status status;

    /** 成功时的执行结果（PluginExecutionResult 序列化为 JSON） */
    private PluginExecutionResult executionResult;

    /** 失败时的错误码与消息 */
    private String errorCode;
    private String errorMessage;

    private Instant submittedAt;
    private Instant startedAt;
    private Instant finishedAt;

    public static ProviderInvocationResult pending(String requestId, String providerId, Instant submittedAt) {
        return ProviderInvocationResult.builder()
            .requestId(requestId)
            .providerId(providerId)
            .status(Status.PENDING)
            .submittedAt(submittedAt)
            .build();
    }

    public boolean isTerminal() {
        return status == Status.SUCCESS || status == Status.FAILED;
    }
}
