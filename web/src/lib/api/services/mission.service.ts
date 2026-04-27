/**
 * Mission Service
 * Handles Agent Mission API endpoints and SSE progress streaming.
 */

import { api } from "../client";
import { getAuthToken } from "@/lib/stores/auth-store";
import { debugLog } from "@/lib/utils/debug-log";
import type {
  MissionResponseDTO,
  MissionProgressDTO,
  MissionStepDTO,
  MissionPageDTO,
  MissionSseEvent,
  MissionListParams,
} from "../dto/mission.dto";

const sseLog = debugLog("sse");

const MISSION_BASE = "/api/agent/missions";

export const missionService = {
  // ============ REST ============

  /** Get mission detail — GET /missions/{id} */
  getMission: (id: string) =>
    api.get<MissionResponseDTO>(`${MISSION_BASE}/${id}`),

  /** Query missions (paginated) — GET /missions */
  queryMissions: (params?: MissionListParams) =>
    api.get<MissionPageDTO>(MISSION_BASE, {
      params: params as Record<string, string | number | boolean | undefined>,
    }),

  /** Get mission progress — GET /missions/{id}/progress */
  getProgress: (id: string) =>
    api.get<MissionProgressDTO>(`${MISSION_BASE}/${id}/progress`),

  /** Get mission steps — GET /missions/{id}/steps */
  getSteps: (id: string) =>
    api.get<MissionStepDTO[]>(`${MISSION_BASE}/${id}/steps`),

  /** Cancel mission — POST /missions/{id}/cancel */
  cancelMission: (id: string) =>
    api.post<null>(`${MISSION_BASE}/${id}/cancel`),

  // ============ SSE ============

  /**
   * Stream mission progress via SSE.
   * Pattern mirrors agent.service.ts sendMessageStream.
   * GET /missions/{id}/progress/stream
   */
  streamProgress: (
    id: string,
    callbacks: {
      onEvent?: (event: MissionSseEvent) => void;
      onError?: (error: string) => void;
      onClose?: () => void;
    }
  ): { abort: () => void } => {
    const controller = new AbortController();
    const token = getAuthToken();

    const headers: Record<string, string> = {
      Accept: "text/event-stream",
    };
    if (token) headers["Authorization"] = `Bearer ${token}`;

    const TERMINAL_EVENTS = new Set([
      "mission_completed",
      "mission_failed",
      "mission_cancelled",
    ]);

    const timeoutMs = 10 * 60 * 1000; // 10 min
    const timeoutId = setTimeout(() => controller.abort("Stream timeout"), timeoutMs);

    fetch(`${MISSION_BASE}/${id}/progress/stream`, {
      method: "GET",
      headers,
      signal: controller.signal,
    })
      .then(async (response) => {
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

        const processLine = (line: string) => {
          const trimmedLine = line.trim();
          if (!trimmedLine) return;

          // Heartbeat
          if (trimmedLine.startsWith(":")) return;

          // Standard SSE "event:" line
          if (trimmedLine.startsWith("event:")) {
            currentEventType = trimmedLine.slice(6).trim();
            return;
          }

          // Ignore "id:" lines
          if (trimmedLine.startsWith("id:")) return;

          // Extract JSON data
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
            const parsed = JSON.parse(jsonStr) as MissionSseEvent;
            const eventType = parsed.eventType || currentEventType;
            const event: MissionSseEvent = { ...parsed, eventType };
            callbacks.onEvent?.(event);

            if (TERMINAL_EVENTS.has(eventType)) {
              clearTimeout(timeoutId);
              controller.abort();
              callbacks.onClose?.();
            }
          } catch {
            if (jsonStr.length > 2) {
              sseLog.warn("[Mission SSE] JSON parse failed:", jsonStr.slice(0, 200));
            }
          }
        };

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split("\n");
          buffer = lines.pop() || "";

          for (const line of lines) {
            processLine(line);
          }
        }

        // Flush remaining buffer
        if (buffer.trim()) {
          buffer.split("\n").forEach(processLine);
        }

        clearTimeout(timeoutId);
        callbacks.onClose?.();
      })
      .catch((error) => {
        clearTimeout(timeoutId);
        if (error.name !== "AbortError") {
          callbacks.onError?.(error.message || "Stream error");
        }
      });

    return {
      abort: () => {
        clearTimeout(timeoutId);
        controller.abort();
      },
    };
  },
};

export default missionService;
