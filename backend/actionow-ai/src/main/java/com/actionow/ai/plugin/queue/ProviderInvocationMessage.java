package com.actionow.ai.plugin.queue;

import com.actionow.ai.plugin.model.PluginExecutionRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * Provider 调用队列消息载荷。
 * 序列化通过 RabbitMQ 的 Jackson2JsonMessageConverter 完成。
 *
 * 设计原则：
 * - 携带租户上下文（userId / workspaceId / tenantSchema），让 worker 重建 UserContext
 * - 携带 requestId 用于结果回收 + 幂等
 * - callbackUrl 可选；非空则 worker 完成后主动 POST，否则结果只写 Redis 等等待方拉
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderInvocationMessage implements Serializable {

    /** 调用请求唯一标识（UUID），结果 key 与幂等键均基于此 */
    private String requestId;

    /** 目标 provider id */
    private String providerId;

    /** 插件 id（pluginType 决定的具体插件，如 groovy） */
    private String pluginId;

    /** 业务请求 */
    private PluginExecutionRequest request;

    /** 用户上下文 */
    private String userId;
    private String workspaceId;
    private String tenantSchema;

    /** 完成后回调 URL，可选 */
    private String callbackUrl;

    /** 入队时间戳（用于排队耗时统计） */
    private Instant submittedAt;

    /** 业务侧自定义幂等键，可选 */
    private String idempotencyKey;
}
