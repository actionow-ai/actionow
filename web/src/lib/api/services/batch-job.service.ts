/**
 * Batch Job Service
 * Handles Batch Job API endpoints and SSE progress streaming.
 * Aligned with backend API: /api/tasks/batch-jobs
 */

import { api } from "../client";
import { getAuthToken } from "@/lib/stores/auth-store";
import { debugLog } from "@/lib/utils/debug-log";
import type {
  BatchJobResponseDTO,
  BatchJobItemDTO,
  BatchJobPageDTO,
  BatchJobItemPageDTO,
  BatchJobSseEvent,
  CreateBatchJobRequestDTO,
  BatchJobListParams,
  BatchJobItemListParams,
} from "../dto/batch-job.dto";

const sseLog = debugLog("sse");

const BATCH_BASE = "/api/tasks/batch-jobs";

export const batchJobService = {
  // ============ REST ============

  /** Create a batch job — POST /batch-jobs */
  create: (data: CreateBatchJobRequestDTO) =>
    api.post<BatchJobResponseDTO>(BATCH_BASE, data),

  /** Expand a batch job (scope) — POST /batch-jobs/expand */
  expand: (data: CreateBatchJobRequestDTO) =>
    api.post<BatchJobResponseDTO>(`${BATCH_BASE}/expand`, data),

  /** Create an A/B test batch — POST /batch-jobs/ab-test */
  abTest: (data: CreateBatchJobRequestDTO) =>
    api.post<BatchJobResponseDTO>(`${BATCH_BASE}/ab-test`, data),

  /** Get batch job detail — GET /batch-jobs/{id} */
  getDetail: (id: string) =>
    api.get<BatchJobResponseDTO>(`${BATCH_BASE}/${id}`),

  /** Get batch job items — GET /batch-jobs/{id}/items */
  getItems: (id: string, params?: BatchJobItemListParams) =>
    api.get<BatchJobItemPageDTO>(`${BATCH_BASE}/${id}/items`, {
      params: params as Record<string, string | number | boolean | undefined>,
    }),

  /** List batch jobs (paginated) — GET /batch-jobs */
  list: (params?: BatchJobListParams) =>
    api.get<BatchJobPageDTO>(BATCH_BASE, {
      params: params as Record<string, string | number | boolean | undefined>,
    }),

  /** Cancel a batch job — POST /batch-jobs/{id}/cancel */
  cancel: (id: string) =>
    api.post<null>(`${BATCH_BASE}/${id}/cancel`),

  /** Pause a batch job — POST /batch-jobs/{id}/pause */
  pause: (id: string) =>
    api.post<null>(`${BATCH_BASE}/${id}/pause`),

  /** Resume a batch job — POST /batch-jobs/{id}/resume */
  resume: (id: string) =>
    api.post<null>(`${BATCH_BASE}/${id}/resume`),

  /** Retry failed items — POST /batch-jobs/{id}/retry-failed */
  retryFailed: (id: string) =>
    api.post<null>(`${BATCH_BASE}/${id}/retry-failed`),

  /** Retry a specific pipeline step — POST /batch-jobs/{id}/retry-step/{step} */
  retryStep: (id: string, step: number) =>
    api.post<null>(`${BATCH_BASE}/${id}/retry-step/${step}`),

  // ============ SSE ============

  /**
   * Stream batch job progress via SSE.
   * Pattern mirrors mission.service.ts:streamProgress.
   * GET /batch-jobs/{id}/progress/stream
   */
  streamProgress: (
    id: string,
    callbacks: {
      onEvent?: (event: BatchJobSseEvent) => void;
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
      "batch_completed",
      "batch_failed",
    ]);

    const timeoutMs = 30 * 60 * 1000; // 30 min
    const timeoutId = setTimeout(() => controller.abort("Stream timeout"), timeoutMs);

    fetch(`${BATCH_BASE}/${id}/progress/stream`, {
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
            const parsed = JSON.parse(jsonStr) as BatchJobSseEvent;
            const eventType = parsed.eventType || currentEventType;
            const event: BatchJobSseEvent = { ...parsed, eventType };
            callbacks.onEvent?.(event);

            if (TERMINAL_EVENTS.has(eventType)) {
              clearTimeout(timeoutId);
              controller.abort();
              callbacks.onClose?.();
            }
          } catch {
            if (jsonStr.length > 2) {
              sseLog.warn("[BatchJob SSE] JSON parse failed:", jsonStr.slice(0, 200));
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

export default batchJobService;
