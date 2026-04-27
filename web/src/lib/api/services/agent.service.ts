/**
 * Agent Service
 * Handles agent session and message API calls
 */

import { api } from "../client";
import { getAuthToken } from "@/lib/stores/auth-store";
import { debugLog } from "@/lib/utils/debug-log";
import type {
  AgentCatalogItemDTO,
  CreateSessionRequestDTO,
  SendMessageRequestDTO,
  SessionResponseDTO,
  AgentResponseDTO,
  AgentMessagesResponseDTO,
  MessageResponseDTO,
  CancelResponseDTO,
  SSEEvent,
  SessionQueryParams,
  SessionPageResult,
  ResolvedAgentProfileDTO,
  ResolvedSkillInfoDTO,
  StatusEventMetadata,
  StructuredDataMetadata,
  AskUserMetadata,
  UserAnswerDTO,
  HeartbeatMetadata,
  ResyncRequiredMetadata,
  SessionStateResponseDTO,
} from "../dto/agent.dto";
import type { AgentToolCatalogDTO } from "../dto/admin.dto";

const sseLog = debugLog("sse");

// v2: gateway path includes /agent sub-path
const AGENT_BASE = "/api/agent";

function withSkillNames(endpoint: string, skillNames?: string[]): string {
  if (!skillNames || skillNames.length === 0) return endpoint;
  const query = new URLSearchParams();
  skillNames.forEach((skillName) => query.append("skillNames", skillName));
  return `${endpoint}?${query.toString()}`;
}

// ============ SSE stream plumbing ============

export interface StreamCallbacks {
  onToolCall?: (toolName: string, args: Record<string, unknown>, toolCallId: string) => void;
  onToolResult?: (toolName: string, result: Record<string, unknown>, toolCallId: string) => void;
  onThinking?: (content: string, iteration?: number) => void;
  onMessage?: (content: string) => void;
  onError?: (error: string) => void;
  onDone?: (stats: { content?: string; elapsedMs: number; totalToolCalls: number; tokenUsage?: unknown }) => void;
  onCancelled?: (content: string) => void;
  onStatus?: (meta: StatusEventMetadata, rawContent?: string) => void;
  onStructuredData?: (meta: StructuredDataMetadata) => void;
  onAskUser?: (meta: AskUserMetadata, rawContent: string) => void;
  onHeartbeat?: (eventId: number | undefined, meta?: HeartbeatMetadata) => void;
  onResyncRequired?: (meta: ResyncRequiredMetadata | undefined) => void;
  /** Fired for every parsed event (including heartbeat) so consumers can track Last-Event-ID */
  onEventId?: (eventId: number) => void;
}

/**
 * Drain an SSE Response stream and dispatch events through StreamCallbacks.
 * Handles standard SSE (`event:`/`id:`/`data:`) plus legacy raw-JSON framing.
 */
async function drainSseResponse(response: Response, callbacks: StreamCallbacks): Promise<void> {
  if (!response.ok) {
    callbacks.onError?.(`HTTP ${response.status}: ${response.statusText}`);
    return;
  }

  const reader = response.body?.getReader();
  if (!reader) {
    callbacks.onError?.("No response body");
    return;
  }

  const decoder = new TextDecoder();
  let buffer = "";
  let currentEventType = "";
  let currentEventId: number | undefined;

  const processEventData = (event: SSEEvent) => {
    const eventType = (event.eventType || currentEventType) as string;
    const eventId = event.eventId ?? currentEventId;
    if (typeof eventId === "number") callbacks.onEventId?.(eventId);

    switch (eventType) {
      case "connect":
        break;
      case "thinking":
        if ("content" in event) {
          const e = event as { content: string; iteration?: number };
          callbacks.onThinking?.(e.content, e.iteration);
        }
        break;
      case "tool_call":
        if ("toolName" in event && "toolArguments" in event && "toolCallId" in event) {
          const e = event as { toolName: string; toolArguments: Record<string, unknown>; toolCallId: string };
          callbacks.onToolCall?.(e.toolName, e.toolArguments, e.toolCallId);
        }
        break;
      case "tool_result":
        if ("toolName" in event && "toolResult" in event && "toolCallId" in event) {
          const e = event as { toolName: string; toolResult: Record<string, unknown>; toolCallId: string };
          callbacks.onToolResult?.(e.toolName, e.toolResult, e.toolCallId);
        }
        break;
      case "message":
        if ("content" in event) callbacks.onMessage?.((event as { content: string }).content);
        break;
      case "error":
        if ("errorMessage" in event) callbacks.onError?.((event as { errorMessage: string }).errorMessage);
        else if ("content" in event) callbacks.onError?.((event as { content: string }).content);
        break;
      case "done":
        callbacks.onDone?.({
          elapsedMs: "elapsedMs" in event ? (event as { elapsedMs: number }).elapsedMs : 0,
          totalToolCalls: "totalToolCalls" in event ? (event as { totalToolCalls: number }).totalToolCalls : 0,
        });
        break;
      case "cancelled":
        if ("content" in event) callbacks.onCancelled?.((event as { content: string }).content);
        break;
      case "status": {
        const e = event as { metadata?: StatusEventMetadata; content?: string };
        if (e.metadata && e.metadata.phase) callbacks.onStatus?.(e.metadata, e.content);
        break;
      }
      case "structured_data": {
        const e = event as { metadata?: StructuredDataMetadata };
        if (e.metadata && e.metadata.schemaRef) callbacks.onStructuredData?.(e.metadata);
        break;
      }
      case "ask_user": {
        const e = event as { metadata?: AskUserMetadata; content?: string };
        if (e.metadata && e.metadata.askId && e.metadata.inputType) {
          callbacks.onAskUser?.(e.metadata, e.content ?? e.metadata.question ?? "");
        }
        break;
      }
      case "heartbeat": {
        const e = event as { metadata?: HeartbeatMetadata };
        callbacks.onHeartbeat?.(eventId, e.metadata);
        break;
      }
      case "resync_required": {
        const e = event as { metadata?: ResyncRequiredMetadata };
        callbacks.onResyncRequired?.(e.metadata);
        break;
      }
    }
  };

  const processLine = (line: string) => {
    const trimmedLine = line.trim();
    if (!trimmedLine) return;

    if (trimmedLine.startsWith("event:")) {
      currentEventType = trimmedLine.slice(6).trim();
      return;
    }
    if (trimmedLine.startsWith("id:")) {
      const parsed = Number(trimmedLine.slice(3).trim());
      if (Number.isFinite(parsed)) currentEventId = parsed;
      return;
    }

    let jsonStr = "";
    if (trimmedLine.startsWith("data:")) {
      jsonStr = trimmedLine.slice(5).trim();
    } else if (trimmedLine.startsWith("{") && trimmedLine.endsWith("}")) {
      jsonStr = trimmedLine;
    } else {
      const jsonMatch = trimmedLine.match(/\{.*\}$/);
      if (jsonMatch) jsonStr = jsonMatch[0];
    }
    if (!jsonStr) return;

    try {
      const event = JSON.parse(jsonStr) as SSEEvent;
      processEventData(event);
      currentEventId = undefined;
      currentEventType = "";
    } catch {
      if (jsonStr.length > 2) sseLog.warn("[SSE] JSON parse failed:", jsonStr.slice(0, 200));
    }
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split("\n");
    buffer = lines.pop() || "";
    for (const line of lines) processLine(line);
  }

  if (buffer.trim()) {
    const remainingLines = buffer.split("\n");
    for (const line of remainingLines) processLine(line);
  }
}

export const agentService = {
  // ============ Agent Catalog / Resolution ============

  getAvailableAgents: () =>
    api.get<AgentCatalogItemDTO[]>(`${AGENT_BASE}/agents/available`),

  getStandaloneAgents: () =>
    api.get<AgentCatalogItemDTO[]>(`${AGENT_BASE}/agents/standalone`),

  getCoordinatorAgents: () =>
    api.get<AgentCatalogItemDTO[]>(`${AGENT_BASE}/agents/coordinators`),

  getResolvedAgent: (agentType: string, skillNames?: string[]) =>
    api.get<ResolvedAgentProfileDTO>(withSkillNames(`${AGENT_BASE}/agents/${agentType}/resolved`, skillNames)),

  getResolvedAgentSkills: (agentType: string, skillNames?: string[]) =>
    api.get<ResolvedSkillInfoDTO[]>(withSkillNames(`${AGENT_BASE}/agents/${agentType}/skills`, skillNames)),

  getResolvedAgentTools: (agentType: string, skillNames?: string[]) =>
    api.get<AgentToolCatalogDTO[]>(withSkillNames(`${AGENT_BASE}/agents/${agentType}/tools`, skillNames)),

  // ============ Session Management ============

  /** Create a new session */
  createSession: (data: CreateSessionRequestDTO) =>
    api.post<SessionResponseDTO>(`${AGENT_BASE}/sessions`, data),

  /** Get session detail */
  getSession: (sessionId: string) =>
    api.get<SessionResponseDTO>(`${AGENT_BASE}/sessions/${sessionId}`),

  /** Authoritative session state probe — call on page entry or before SSE reconnect (§2.3) */
  getSessionState: (sessionId: string) =>
    api.get<SessionStateResponseDTO>(`${AGENT_BASE}/sessions/${sessionId}/state`),

  /** Get active sessions */
  getSessions: (limit: number = 20) =>
    api.get<SessionResponseDTO[]>(`${AGENT_BASE}/sessions/active`, {
      params: { limit },
    }),

  /** Get active sessions */
  getActiveSessions: (limit: number = 20) =>
    api.get<SessionResponseDTO[]>(`${AGENT_BASE}/sessions/active`, {
      params: { limit },
    }),

  /** Get archived sessions */
  getArchivedSessions: (limit: number = 50) =>
    api.get<SessionResponseDTO[]>(`${AGENT_BASE}/sessions/archived`, {
      params: { limit },
    }),

  /** Query sessions with filters and pagination */
  querySessions: (params: SessionQueryParams = {}) =>
    api.get<SessionPageResult>(`${AGENT_BASE}/sessions`, {
      params: {
        page: params.page ?? 1,
        size: params.size ?? 20,
        standalone: params.standalone,
        scriptId: params.scriptId,
      },
    }),

  /** End session */
  endSession: (sessionId: string) =>
    api.post<null>(`${AGENT_BASE}/sessions/${sessionId}/end`),

  /** Archive session */
  archiveSession: (sessionId: string) =>
    api.post<null>(`${AGENT_BASE}/sessions/${sessionId}/archive`),

  /** Resume archived session */
  resumeSession: (sessionId: string) =>
    api.post<null>(`${AGENT_BASE}/sessions/${sessionId}/resume`),

  /** Delete session */
  deleteSession: (sessionId: string) =>
    api.delete<null>(`${AGENT_BASE}/sessions/${sessionId}`),

  // ============ Message Management ============

  /** Get message history */
  getMessages: (sessionId: string) =>
    api.get<MessageResponseDTO[]>(`${AGENT_BASE}/sessions/${sessionId}/messages`),

  /** Send message (sync) */
  sendMessage: (sessionId: string, data: SendMessageRequestDTO, options?: { timeout?: number }) =>
    api.post<AgentResponseDTO>(`${AGENT_BASE}/sessions/${sessionId}/messages`, {
      ...data,
      stream: false,
    }, { timeout: options?.timeout }),

  /** Send message (sync) - returns messages array with tool results */
  sendMessageWithMessages: (sessionId: string, data: SendMessageRequestDTO, options?: { timeout?: number }) =>
    api.post<AgentMessagesResponseDTO>(`${AGENT_BASE}/sessions/${sessionId}/messages`, {
      ...data,
      stream: false,
    }, { timeout: options?.timeout }),

  /** Send message (stream SSE) — POST /messages/stream, returns abort handle */
  sendMessageStream: (
    sessionId: string,
    data: SendMessageRequestDTO,
    callbacks: StreamCallbacks,
  ): { abort: () => void } => {
    const controller = new AbortController();
    const token = getAuthToken();
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      Accept: "text/event-stream",
    };
    if (token) headers["Authorization"] = `Bearer ${token}`;

    const timeoutMs = 5 * 60 * 1000;
    const timeoutId = setTimeout(() => controller.abort("Stream timeout"), timeoutMs);

    fetch(`${AGENT_BASE}/sessions/${sessionId}/messages/stream`, {
      method: "POST",
      headers,
      body: JSON.stringify(data),
      signal: controller.signal,
    })
      .then((response) => drainSseResponse(response, callbacks))
      .catch((error) => {
        if (error.name !== "AbortError") {
          callbacks.onError?.(error.message || "Stream error");
        }
      })
      .finally(() => clearTimeout(timeoutId));

    return {
      abort: () => {
        clearTimeout(timeoutId);
        controller.abort();
      },
    };
  },

  /**
   * Resume an in-flight SSE stream — GET /stream with Last-Event-ID (§2.4).
   * Pure subscriber: does not trigger new LLM calls or consume credits.
   * Omit `lastEventId` to subscribe from the start of the buffer.
   */
  resumeMessageStream: (
    sessionId: string,
    lastEventId: number | null | undefined,
    callbacks: StreamCallbacks,
  ): { abort: () => void } => {
    const controller = new AbortController();
    const token = getAuthToken();
    const headers: Record<string, string> = {
      Accept: "text/event-stream",
    };
    if (token) headers["Authorization"] = `Bearer ${token}`;
    if (lastEventId != null && Number.isFinite(lastEventId)) {
      headers["Last-Event-ID"] = String(lastEventId);
    }

    // Query-param fallback covers non-browser / debug clients per §2.4
    const qs = lastEventId != null && Number.isFinite(lastEventId)
      ? `?lastEventId=${lastEventId}`
      : "";

    const timeoutMs = 5 * 60 * 1000;
    const timeoutId = setTimeout(() => controller.abort("Stream timeout"), timeoutMs);

    fetch(`${AGENT_BASE}/sessions/${sessionId}/stream${qs}`, {
      method: "GET",
      headers,
      signal: controller.signal,
    })
      .then((response) => drainSseResponse(response, callbacks))
      .catch((error) => {
        if (error.name !== "AbortError") {
          callbacks.onError?.(error.message || "Stream error");
        }
      })
      .finally(() => clearTimeout(timeoutId));

    return {
      abort: () => {
        clearTimeout(timeoutId);
        controller.abort();
      },
    };
  },

  /** Cancel generation */
  cancelGeneration: (sessionId: string) =>
    api.post<CancelResponseDTO>(`${AGENT_BASE}/sessions/${sessionId}/cancel`),

  // ============ HITL (ask_user / answer) ============

  /** Submit HITL answer — POST /agent/sessions/{sessionId}/ask/{askId}/answer */
  answerAsk: (sessionId: string, askId: string, answer: UserAnswerDTO) =>
    api.post<null>(`${AGENT_BASE}/sessions/${sessionId}/ask/${askId}/answer`, answer),

  /** Cancel HITL ask — DELETE /agent/sessions/{sessionId}/ask/{askId} */
  cancelAsk: (sessionId: string, askId: string, reason?: string) =>
    api.delete<null>(
      `${AGENT_BASE}/sessions/${sessionId}/ask/${askId}${reason ? `?reason=${encodeURIComponent(reason)}` : ""}`,
    ),

  /** Get agent system status */
  getAgentStatus: () =>
    api.get<{
      lastBuildTime: number;
      framework: string;
      cachedSupervisorExists: boolean;
      expertAgentsCount: number;
      expertAgents: string[];
    }>(`${AGENT_BASE}/status`),
};

export default agentService;
