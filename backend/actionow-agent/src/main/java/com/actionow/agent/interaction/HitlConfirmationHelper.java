package com.actionow.agent.interaction;

import com.actionow.agent.core.agent.AgentStreamEvent;
import com.actionow.agent.core.context.SessionContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 工具内部强制 HITL 确认 helper（破坏性操作专用）。
 *
 * <p>与 {@link AskUserTools#askUserConfirm(String, Integer)} 同源（共用 SSE 推送 + 阻塞等待
 * 机制），但面向 Java 内部调用：返回类型化的 {@link ConfirmationResult}，调用方用 switch
 * 模式匹配处理 5 种结果（Confirmed / Declined / TimedOut / NoSession / Failed），不需要
 * 自己解析 Map 字段。
 *
 * <p>使用场景：批量删除工具（{@code batch_delete_*}）等"删除/覆盖类工具"在调用 Feign 真正
 * 删除之前必须先调本 helper。LLM 不能跳过——helper 由工具方法内部 hard-coded 调用，不暴露
 * 给 LLM。
 *
 * <p>没有活跃会话（如 cron / 后台任务里执行）→ {@link ConfirmationResult.NoSession}：调用方
 * 应当拒绝执行破坏性动作，而不是降级为"无确认直接删"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HitlConfirmationHelper {

    private static final int DEFAULT_TIMEOUT_SEC = 120;
    private static final int MAX_TIMEOUT_SEC = 600;

    private static final List<Map<String, Object>> YES_NO_CHOICES = List.of(
            Map.of("id", "yes", "label", "是"),
            Map.of("id", "no", "label", "否"));

    private final UserInteractionService interaction;
    private final AgentStreamBridge streamBridge;
    private final AgentAskHistoryService askHistoryService;

    public sealed interface ConfirmationResult {
        record Confirmed(String askId) implements ConfirmationResult {}
        record Declined(String askId, String reason) implements ConfirmationResult {}
        record NoSession() implements ConfirmationResult {}
        record TimedOut(String askId) implements ConfirmationResult {}
        record Failed(String error) implements ConfirmationResult {}
    }

    /**
     * 同步阻塞等待用户对破坏性操作给出 yes/no 决定。
     *
     * @param question   面向用户的问题文本
     * @param timeoutSec 超时秒数，&lt;=0 取默认 120s，上限 600s
     */
    public ConfirmationResult confirmDestructiveAction(String question, int timeoutSec) {
        if (question == null || question.isBlank()) {
            return new ConfirmationResult.Failed("question 不能为空");
        }
        String sessionId = SessionContextHolder.getCurrentSessionId();
        if (sessionId == null) {
            return new ConfirmationResult.NoSession();
        }
        int effectiveTimeout = timeoutSec <= 0 ? DEFAULT_TIMEOUT_SEC : Math.min(timeoutSec, MAX_TIMEOUT_SEC);
        String askId = interaction.newAskId();
        long deadlineMs = effectiveTimeout * 1000L;

        MDC.put("askId", askId);
        MDC.put("sessionId", sessionId);
        try {
            askHistoryService.recordPending(sessionId, askId, question, "confirm",
                    YES_NO_CHOICES, null, effectiveTimeout);

            AgentStreamEvent askEvent = AgentStreamEvent.askUser(askId, question, YES_NO_CHOICES,
                    "confirm", deadlineMs);
            streamBridge.publish(sessionId, askEvent);
            log.info("HitlConfirmationHelper 推送破坏性确认 ask: timeoutSec={}", effectiveTimeout);

            try {
                UserAnswer ans = interaction.awaitAnswer(sessionId, askId, Duration.ofSeconds(effectiveTimeout));
                if (Boolean.TRUE.equals(ans.getRejected())) {
                    return new ConfirmationResult.Declined(askId, "REJECTED");
                }
                String answer = ans.getAnswer();
                if ("yes".equalsIgnoreCase(answer)) {
                    return new ConfirmationResult.Confirmed(askId);
                }
                return new ConfirmationResult.Declined(askId, "USER_DECLINED");
            } catch (TimeoutException te) {
                return new ConfirmationResult.TimedOut(askId);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return new ConfirmationResult.Failed("等待被中断");
            } catch (Exception e) {
                log.warn("HitlConfirmationHelper 等待答复异常: {}", e.getMessage());
                return new ConfirmationResult.Failed(e.getMessage());
            }
        } finally {
            MDC.remove("askId");
            MDC.remove("sessionId");
        }
    }
}
