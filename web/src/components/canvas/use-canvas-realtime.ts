"use client";

import { useEffect, useMemo, useRef } from "react";
import useWebSocketLib, { ReadyState } from "react-use-websocket";

import { useAuthStore } from "@/lib/stores/auth-store";
import type { CanvasNodeDTO, CanvasEdgeDTO } from "@/lib/api/services/canvas.service";

type CanvasMessageType =
  | "CONNECTED"
  | "DISCONNECTED"
  | "PING"
  | "PONG"
  | "NODE_CREATED"
  | "NODE_UPDATED"
  | "NODE_DELETED"
  | "EDGE_CREATED"
  | "EDGE_UPDATED"
  | "EDGE_DELETED"
  | "BATCH_NODES_UPDATED"
  | "CANVAS_UPDATED"
  | "LAYOUT_CHANGED"
  | "USER_JOINED"
  | "USER_LEFT";

interface CanvasMessage {
  type: CanvasMessageType;
  data?: Record<string, unknown>;
  timestamp?: number;
  eventId?: string;
}

export interface CanvasRealtimeHandlers {
  onNodeCreated?: (node: CanvasNodeDTO) => void;
  onNodeUpdated?: (node: CanvasNodeDTO) => void;
  onNodeDeleted?: (nodeId: string) => void;
  onBatchNodesUpdated?: (nodes: CanvasNodeDTO[]) => void;
  onEdgeCreated?: (edge: CanvasEdgeDTO) => void;
  onEdgeUpdated?: (edge: CanvasEdgeDTO) => void;
  onEdgeDeleted?: (edgeId: string) => void;
}

const MAX_PROCESSED_EVENTS = 1000;

function getWsOrigin(): string {
  const explicitWsUrl = process.env.NEXT_PUBLIC_WS_URL;
  if (explicitWsUrl) {
    try {
      const url = new URL(explicitWsUrl);
      return `${url.protocol}//${url.host}`;
    } catch {
      return explicitWsUrl.replace(/\/+$/, "");
    }
  }
  const apiBase = process.env.NEXT_PUBLIC_API_BASE_URL;
  const fallback =
    process.env.NODE_ENV === "production"
      ? "https://api.actionow.ai"
      : "http://127.0.0.1:8080";
  try {
    const url = new URL(apiBase || fallback);
    const wsProto = url.protocol === "https:" ? "wss:" : "ws:";
    return `${wsProto}//${url.host}`;
  } catch {
    return process.env.NODE_ENV === "production"
      ? "wss://api.actionow.ai"
      : "ws://127.0.0.1:8080";
  }
}

/**
 * 订阅指定画布的 WebSocket 实时事件。
 * 后端已在 broadcast 时排除操作者（broadcastToOthersExcludeUser），
 * 因此前端不需要再过滤回环消息。
 */
export function useCanvasRealtime(canvasId: string | null, handlers: CanvasRealtimeHandlers) {
  const accessToken = useAuthStore((s) => s.tokenBundle?.accessToken ?? null);
  const workspaceId = useAuthStore((s) => s.tokenBundle?.workspaceId ?? null);
  const handlersRef = useRef(handlers);
  handlersRef.current = handlers;
  const processedRef = useRef<string[]>([]);

  const socketUrl = useMemo(() => {
    if (!canvasId || !accessToken || !workspaceId) return null;
    const url = new URL(`${getWsOrigin()}/ws/canvas/${canvasId}`);
    url.searchParams.set("token", accessToken);
    url.searchParams.set("workspaceId", workspaceId);
    return url.toString();
  }, [accessToken, canvasId, workspaceId]);

  const { lastJsonMessage, readyState } = useWebSocketLib<CanvasMessage>(socketUrl, {
    shouldReconnect: () => true,
    reconnectAttempts: 10,
    reconnectInterval: 2000,
    share: false,
  }, !!socketUrl);

  useEffect(() => {
    if (!lastJsonMessage) return;
    const msg = lastJsonMessage;

    if (msg.eventId) {
      if (processedRef.current.includes(msg.eventId)) return;
      processedRef.current.push(msg.eventId);
      if (processedRef.current.length > MAX_PROCESSED_EVENTS) {
        processedRef.current = processedRef.current.slice(-500);
      }
    }

    const data = msg.data ?? {};
    const h = handlersRef.current;

    switch (msg.type) {
      case "NODE_CREATED":
        h.onNodeCreated?.(data as unknown as CanvasNodeDTO);
        break;
      case "NODE_UPDATED":
        h.onNodeUpdated?.(data as unknown as CanvasNodeDTO);
        break;
      case "NODE_DELETED": {
        const nodeId = (data as { nodeId?: string }).nodeId;
        if (nodeId) h.onNodeDeleted?.(nodeId);
        break;
      }
      case "BATCH_NODES_UPDATED": {
        const nodes = (data as { nodes?: CanvasNodeDTO[] }).nodes;
        if (Array.isArray(nodes) && nodes.length > 0) {
          h.onBatchNodesUpdated?.(nodes);
        }
        break;
      }
      case "EDGE_CREATED":
        h.onEdgeCreated?.(data as unknown as CanvasEdgeDTO);
        break;
      case "EDGE_UPDATED":
        h.onEdgeUpdated?.(data as unknown as CanvasEdgeDTO);
        break;
      case "EDGE_DELETED": {
        const edgeId = (data as { edgeId?: string }).edgeId;
        if (edgeId) h.onEdgeDeleted?.(edgeId);
        break;
      }
      default:
        break;
    }
  }, [lastJsonMessage]);

  return {
    connected: readyState === ReadyState.OPEN,
  };
}
