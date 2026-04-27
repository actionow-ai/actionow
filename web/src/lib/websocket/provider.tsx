/**
 * WebSocket Provider - 基于 react-use-websocket v4
 * 遵循 websocket-api.md v1.1 规范
 */

"use client";

import {
  createContext,
  useContext,
  useCallback,
  useRef,
  useState,
  useEffect,
  type ReactNode,
} from "react";
import useWebSocketLib, { ReadyState } from "react-use-websocket";
import type {
  WebSocketMessage,
  WebSocketContextValue,
  MessageHandler,
  ConnectionStatus,
  CollabTab,
  CollabUser,
  ScriptCollabState,
  ConnectedData,
  ScriptCollaborationData,
  UserJoinedData,
  UserLeftData,
  UserLocationChangedData,
  ClientMessage,
  NotificationMessageData,
  NotificationCountData,
  WalletBalanceChangedData,
  CommentCreatedData,
  CommentUpdatedData,
  CommentDeletedData,
  CommentResolvedData,
  CommentReopenedData,
  CommentReactionData,
} from "./types";
import { HEARTBEAT_INTERVAL, MAX_PROCESSED_EVENTS } from "./types";
import { useAuthStore } from "@/lib/stores/auth-store";
import { getNotificationStore } from "@/lib/stores/notification-store";
import { getWalletStore } from "@/lib/stores/wallet-store";
import { debugLog } from "@/lib/utils/debug-log";

const wsLog = debugLog("ws");

// =============================================================================
// Constants
// =============================================================================

const DEFAULT_LOCAL_API_BASE_URL = "http://127.0.0.1:8080";
const DEFAULT_PRODUCTION_API_BASE_URL = "https://api.actionow.ai";

const getDefaultApiBaseUrl = (): string => {
  return process.env.NODE_ENV === "production"
    ? DEFAULT_PRODUCTION_API_BASE_URL
    : DEFAULT_LOCAL_API_BASE_URL;
};

const getDefaultWsOrigin = (): string => {
  return process.env.NODE_ENV === "production"
    ? "wss://api.actionow.ai"
    : "ws://127.0.0.1:8080";
};

const getWsOrigin = (): string => {
  const explicitWsUrl = process.env.NEXT_PUBLIC_WS_URL;
  if (explicitWsUrl) {
    try {
      const url = new URL(explicitWsUrl);
      return `${url.protocol}//${url.host}`;
    } catch {
      return explicitWsUrl.replace(/\/+$/, "");
    }
  }

  const legacyApiBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL;
  const derivedBaseUrl = legacyApiBaseUrl || getDefaultApiBaseUrl();

  try {
    const url = new URL(derivedBaseUrl);
    const wsProtocol = url.protocol === "https:" ? "wss:" : "ws:";
    return `${wsProtocol}//${url.host}`;
  } catch {
    return getDefaultWsOrigin();
  }
};

const getConfiguredWsPath = (): string => {
  const explicitWsUrl = process.env.NEXT_PUBLIC_WS_URL;
  if (!explicitWsUrl) {
    return "/ws";
  }

  try {
    const url = new URL(explicitWsUrl);
    return url.pathname || "/ws";
  } catch {
    const match = explicitWsUrl.match(/^[a-z]+:\/\/[^/]+(\/[^?]*)/i);
    return match?.[1] || "/ws";
  }
};

// =============================================================================
// Context
// =============================================================================

const WebSocketContext = createContext<WebSocketContextValue | null>(null);

// =============================================================================
// Provider Props
// =============================================================================

interface WebSocketProviderProps {
  children: ReactNode;
  workspaceId: string | null;
  autoConnect?: boolean;
}

// =============================================================================
// Helper Functions
// =============================================================================

function readyStateToStatus(readyState: ReadyState): ConnectionStatus {
  switch (readyState) {
    case ReadyState.CONNECTING:
      return "connecting";
    case ReadyState.OPEN:
      return "connected";
    case ReadyState.CLOSING:
    case ReadyState.CLOSED:
      return "disconnected";
    default:
      return "disconnected";
  }
}

// =============================================================================
// Provider Component
// =============================================================================

export function WebSocketProvider({
  children,
  workspaceId,
  autoConnect = true,
}: WebSocketProviderProps) {
  void workspaceId;
  const accessToken = useAuthStore((state) => state.tokenBundle?.accessToken ?? null);
  const tokenWorkspaceId = useAuthStore((state) => state.tokenBundle?.workspaceId ?? null);
  const effectiveWorkspaceId = tokenWorkspaceId;

  // Session state
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [reconnectCount, setReconnectCount] = useState(0);
  const [currentScriptId, setCurrentScriptId] = useState<string | null>(null);
  const [scriptCollabs, setScriptCollabs] = useState<Map<string, ScriptCollabState>>(new Map());
  const hasConnectedOnceRef = useRef(false);

  // Refs for stable callbacks and state
  const handlersRef = useRef<Set<MessageHandler>>(new Set());
  const processedEventsRef = useRef<string[]>([]);
  const heartbeatIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const workspaceIdRef = useRef(effectiveWorkspaceId);

  // Update ref when workspaceId changes
  useEffect(() => {
    workspaceIdRef.current = effectiveWorkspaceId;
  }, [effectiveWorkspaceId]);

  // Check if we should connect
  const shouldConnect = autoConnect && !!effectiveWorkspaceId && !!accessToken;

  // Build WebSocket URL
  const getSocketUrl = useCallback((): string => {
    const url = new URL(getConfiguredWsPath(), `${getWsOrigin()}/`);
    url.searchParams.set("token", accessToken || "");
    url.searchParams.set("workspaceId", effectiveWorkspaceId || "");
    return url.toString();
  }, [accessToken, effectiveWorkspaceId]);

  // Process incoming messages
  const processMessage = useCallback((message: WebSocketMessage) => {
    // Idempotency check
    if (message.eventId) {
      if (processedEventsRef.current.includes(message.eventId)) {
        return; // Skip duplicate
      }
      processedEventsRef.current.push(message.eventId);
      // FIFO cleanup
      if (processedEventsRef.current.length > MAX_PROCESSED_EVENTS) {
        processedEventsRef.current = processedEventsRef.current.slice(-500);
      }
    }

    // Handle collaboration state messages internally
    switch (message.type) {
      case "CONNECTED": {
        const data = message.data as ConnectedData;
        setSessionId(data.sessionId);
        wsLog.log("[WS] Connected, sessionId:", data.sessionId);
        break;
      }

      case "SCRIPT_COLLABORATION": {
        const data = message.data as ScriptCollaborationData;
        setScriptCollabs((prev) => {
          const next = new Map(prev);
          next.set(data.scriptId, {
            scriptId: data.scriptId,
            users: data.users,
            totalUsers: data.totalUsers,
            tabUserCounts: data.tabUserCounts,
          });
          return next;
        });
        break;
      }

      case "USER_JOINED": {
        const data = message.data as UserJoinedData;
        setScriptCollabs((prev) => {
          const next = new Map(prev);
          const existing = next.get(data.scriptId);
          if (existing) {
            const userExists = existing.users.some((u) => u.userId === data.user.userId);
            if (!userExists) {
              next.set(data.scriptId, {
                ...existing,
                users: [...existing.users, data.user],
                totalUsers: existing.totalUsers + 1,
              });
            }
          } else {
            next.set(data.scriptId, {
              scriptId: data.scriptId,
              users: [data.user],
              totalUsers: 1,
              tabUserCounts: {},
            });
          }
          return next;
        });
        break;
      }

      case "USER_LEFT": {
        const data = message.data as UserLeftData;
        setScriptCollabs((prev) => {
          const next = new Map(prev);
          const existing = next.get(data.scriptId);
          if (existing) {
            next.set(data.scriptId, {
              ...existing,
              users: existing.users.filter((u) => u.userId !== data.userId),
              totalUsers: Math.max(0, existing.totalUsers - 1),
            });
          }
          return next;
        });
        break;
      }

      case "USER_LOCATION_CHANGED": {
        const data = message.data as UserLocationChangedData;
        setScriptCollabs((prev) => {
          const next = new Map(prev);
          const existing = next.get(data.scriptId);
          if (existing) {
            next.set(data.scriptId, {
              ...existing,
              users: existing.users.map((u) =>
                u.userId === data.user.userId ? data.user : u
              ),
            });
          }
          return next;
        });
        break;
      }

      case "PONG":
        // Heartbeat response - no action needed
        return;

      // ── Wallet domain ──────────────────────────────────────────────────────

      case "WALLET_BALANCE_CHANGED": {
        const data = message.data as WalletBalanceChangedData;
        getWalletStore().applyBalanceChanged(data.balance, data.frozen, data.delta, data.transactionType);
        break;
      }

      // ── Notification domain ────────────────────────────────────────────────

      case "NOTIFICATION": {
        // Directed push to the current user (mention / reply)
        const data = message.data as NotificationMessageData;
        getNotificationStore().addNotification({
          id: data.id,
          type: data.type,
          title: data.title,
          sender: data.sender,
          payload: data.payload,
          receivedAt: new Date().toISOString(),
          read: false,
        });
        // Increment optimistically; NOTIFICATION_COUNT will sync the authoritative total
        getNotificationStore().incrementUnread(1);
        break;
      }

      case "NOTIFICATION_COUNT": {
        // Authoritative unread count from server (on reconnect / after bulk read)
        const data = message.data as NotificationCountData;
        getNotificationStore().setUnreadCount(data.total);
        break;
      }
    }

    // Notify all subscribers
    handlersRef.current.forEach((handler) => {
      try {
        handler(message);
      } catch (e) {
        wsLog.error("[WS] Handler error:", e);
      }
    });
  }, []);

  // Use WebSocket hook
  const { sendMessage, readyState, getWebSocket } = useWebSocketLib(
    getSocketUrl,
    {
      onOpen: () => {
        wsLog.log("[WS] Connection opened:", getSocketUrl());
        // Track reconnects (skip the first connect)
        if (hasConnectedOnceRef.current) {
          setReconnectCount((c) => c + 1);
        } else {
          hasConnectedOnceRef.current = true;
        }
        // Start heartbeat using getWebSocket to avoid stale closure
        if (heartbeatIntervalRef.current) {
          clearInterval(heartbeatIntervalRef.current);
        }
        heartbeatIntervalRef.current = setInterval(() => {
          const ws = getWebSocket();
          if (ws && "send" in ws && ws.readyState === WebSocket.OPEN) {
            (ws as WebSocket).send(JSON.stringify({ type: "PING" }));
          }
        }, HEARTBEAT_INTERVAL);
      },
      onClose: (event: CloseEvent) => {
        wsLog.log("[WS] Connection closed:", {
          code: event.code,
          reason: event.reason,
          wasClean: event.wasClean,
          url: getSocketUrl(),
        });
        setSessionId(null);
        // Stop heartbeat
        if (heartbeatIntervalRef.current) {
          clearInterval(heartbeatIntervalRef.current);
          heartbeatIntervalRef.current = null;
        }
        // Reset stores only on clean close (e.g., workspace switch, logout).
        // Skip on abnormal close (network blip) to avoid brief "0" flash during auto-reconnect.
        if (event.wasClean) {
          getNotificationStore().reset();
          getWalletStore().reset();
        }
      },
      onError: (event: Event) => {
        const socket = event.target as WebSocket | null;
        wsLog.error("[WS] Connection error:", {
          url: getSocketUrl(),
          readyState: socket?.readyState,
          event,
        });
      },
      onMessage: (event: MessageEvent) => {
        try {
          const message: WebSocketMessage = JSON.parse(event.data);
          processMessage(message);
        } catch (e) {
          wsLog.error("[WS] Failed to parse message:", e);
        }
      },
      shouldReconnect: () => true,
      reconnectAttempts: 10,
      reconnectInterval: (attemptNumber: number) =>
        Math.min(1000 * Math.pow(2, attemptNumber), 30000),
      share: false,
      retryOnError: true,
    },
    shouldConnect
  );

  const status = readyStateToStatus(readyState);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (heartbeatIntervalRef.current) {
        clearInterval(heartbeatIntervalRef.current);
        heartbeatIntervalRef.current = null;
      }
    };
  }, []);

  // =============================================================================
  // Send Message Helper
  // =============================================================================

  const send = useCallback(
    (message: ClientMessage) => {
      if (readyState === ReadyState.OPEN) {
        sendMessage(JSON.stringify(message));
      } else {
        wsLog.warn("[WS] Cannot send - not connected, readyState:", readyState);
      }
    },
    [readyState, sendMessage]
  );

  // =============================================================================
  // Public Methods
  // =============================================================================

  const connect = useCallback(() => {
    // react-use-websocket handles connection automatically
    // This is a no-op if already connected
    wsLog.log("[WS] Manual connect requested");
  }, []);

  const disconnect = useCallback(() => {
    const ws = getWebSocket();
    if (ws) {
      ws.close();
    }
    setSessionId(null);
    setCurrentScriptId(null);
  }, [getWebSocket]);

  const enterWorkspace = useCallback(
    (wsId: string) => {
      send({ type: "ENTER_WORKSPACE", workspaceId: wsId });
    },
    [send]
  );

  const leaveWorkspace = useCallback(
    (wsId: string) => {
      send({ type: "LEAVE_WORKSPACE", workspaceId: wsId });
    },
    [send]
  );

  const enterScript = useCallback(
    (scriptId: string, tab: CollabTab = "DETAIL") => {
      setCurrentScriptId(scriptId);
      send({ type: "ENTER_SCRIPT", scriptId, tab });
    },
    [send]
  );

  const leaveScript = useCallback(() => {
    if (currentScriptId) {
      send({ type: "LEAVE_SCRIPT" });
      setCurrentScriptId(null);
    }
  }, [currentScriptId, send]);

  const switchTab = useCallback(
    (tab: CollabTab) => {
      send({ type: "SWITCH_TAB", tab });
    },
    [send]
  );

  const focusEntity = useCallback(
    (entityType: string, entityId: string) => {
      send({ type: "FOCUS_ENTITY", entityType, entityId });
    },
    [send]
  );

  const blurEntity = useCallback(
    (entityType: string, entityId: string) => {
      send({ type: "BLUR_ENTITY", entityType, entityId });
    },
    [send]
  );

  const startEditing = useCallback(
    (entityType: string, entityId: string) => {
      send({ type: "START_EDITING", entityType, entityId });
    },
    [send]
  );

  const stopEditing = useCallback(
    (entityType: string, entityId: string) => {
      send({ type: "STOP_EDITING", entityType, entityId });
    },
    [send]
  );

  const subscribe = useCallback((handler: MessageHandler) => {
    handlersRef.current.add(handler);
    return () => {
      handlersRef.current.delete(handler);
    };
  }, []);

  const getScriptUsers = useCallback(
    (scriptId: string): CollabUser[] => {
      return scriptCollabs.get(scriptId)?.users || [];
    },
    [scriptCollabs]
  );

  // =============================================================================
  // Context Value
  // =============================================================================

  const value: WebSocketContextValue = {
    status,
    connected: status === "connected",
    sessionId,
    reconnectCount,
    currentScriptId,
    scriptCollabs,
    connect,
    disconnect,
    enterWorkspace,
    leaveWorkspace,
    enterScript,
    leaveScript,
    switchTab,
    focusEntity,
    blurEntity,
    startEditing,
    stopEditing,
    subscribe,
    getScriptUsers,
  };

  return (
    <WebSocketContext.Provider value={value}>
      {children}
    </WebSocketContext.Provider>
  );
}

// =============================================================================
// Hooks
// =============================================================================

// Default context value for SSR
const defaultContextValue: WebSocketContextValue = {
  status: "disconnected",
  connected: false,
  sessionId: null,
  reconnectCount: 0,
  currentScriptId: null,
  scriptCollabs: new Map(),
  connect: () => {},
  disconnect: () => {},
  enterWorkspace: () => {},
  leaveWorkspace: () => {},
  enterScript: () => {},
  leaveScript: () => {},
  switchTab: () => {},
  focusEntity: () => {},
  blurEntity: () => {},
  startEditing: () => {},
  stopEditing: () => {},
  subscribe: () => () => {},
  getScriptUsers: () => [],
};

/**
 * Use WebSocket context
 */
export function useWebSocketContext() {
  const context = useContext(WebSocketContext);
  return context || defaultContextValue;
}

// Alias for backward compatibility
export const useWebSocket = useWebSocketContext;

/**
 * Subscribe to WebSocket messages
 */
export function useWebSocketMessage(
  handler: MessageHandler,
  deps: React.DependencyList = []
) {
  const { subscribe } = useWebSocketContext();

  useEffect(() => {
    return subscribe(handler);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [subscribe, ...deps]);
}

/**
 * Subscribe to entity change messages
 */
export function useEntityChanges(
  onEntityChanged: (
    action: string,
    entityType: string,
    entityId: string,
    data: Record<string, unknown>
  ) => void,
  deps: React.DependencyList = []
) {
  useWebSocketMessage((message) => {
    if (
      (message.type === "ENTITY_CHANGED" || message.type === "ENTITY_UPDATED") &&
      message.action &&
      message.entityType &&
      message.entityId
    ) {
      onEntityChanged(
        message.action,
        message.entityType,
        message.entityId,
        (message.data || {}) as Record<string, unknown>
      );
    }
  }, deps);
}

/**
 * Subscribe to entity changes for a specific entity type with built-in debounce.
 * Designed for tab-level list refresh — deduplicates rapid successive changes
 * (e.g., batch operations) into a single fetch.
 */
export function useDebouncedEntityChanges(
  targetEntityType: string,
  onRefresh: () => void,
  options?: {
    onDeleted?: (entityId: string) => void;
    /** Filter to a specific entity id (e.g., for detail views) */
    entityId?: string;
    /** Debounce delay in ms (default 300) */
    delay?: number;
  },
  deps: React.DependencyList = []
) {
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const normalizedType = targetEntityType.toLowerCase();

  useEntityChanges(
    (action, entityType, entityId) => {
      if (entityType.toLowerCase() !== normalizedType) return;
      if (options?.entityId && entityId !== options.entityId) return;
      if (action === "DELETED") {
        options?.onDeleted?.(entityId);
      }
      // Debounce: collapse rapid successive changes into one refresh
      if (timerRef.current) clearTimeout(timerRef.current);
      timerRef.current = setTimeout(() => {
        onRefresh();
        timerRef.current = null;
      }, options?.delay ?? 300);
    },
    deps
  );

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, []);
}

/**
 * Subscribe to task updates
 */
export function useTaskUpdates(
  onTaskUpdate: (
    taskId: string,
    status: string,
    data: Record<string, unknown>
  ) => void,
  deps: React.DependencyList = []
) {
  useWebSocketMessage((message) => {
    if (
      (message.type === "TASK_STATUS_CHANGED" ||
        message.type === "TASK_PROGRESS" ||
        message.type === "TASK_COMPLETED" ||
        message.type === "TASK_FAILED") &&
      message.entityId
    ) {
      const status =
        (message.data as { status?: string })?.status || message.type;
      onTaskUpdate(
        message.entityId,
        status,
        (message.data || {}) as Record<string, unknown>
      );
    }
  }, deps);
}

/**
 * Subscribe to editing lock events
 */
export function useEditingLock(
  onLocked: (entityType: string, entityId: string, lockedBy: { userId: string; nickname: string }) => void,
  onUnlocked: (entityType: string, entityId: string) => void,
  deps: React.DependencyList = []
) {
  useWebSocketMessage((message) => {
    if (message.type === "EDITING_LOCKED" && message.data) {
      const data = message.data as {
        entityType: string;
        entityId: string;
        lockedBy: { userId: string; nickname: string };
      };
      onLocked(data.entityType, data.entityId, data.lockedBy);
    } else if (message.type === "EDITING_UNLOCKED" && message.data) {
      const data = message.data as { entityType: string; entityId: string };
      onUnlocked(data.entityType, data.entityId);
    }
  }, deps);
}

// =============================================================================
// Comment Domain Hook
// =============================================================================

/**
 * Subscribe to comment-domain WebSocket events with typed per-event handlers.
 * All handlers are optional — provide only what you need.
 *
 * Usage:
 *   useCommentMessages({
 *     onCreated: (data) => { ... },
 *     onDeleted: (data) => { ... },
 *   }, [scriptId]);
 */
export function useCommentMessages(
  handlers: {
    onCreated?: (data: CommentCreatedData) => void;
    onUpdated?: (data: CommentUpdatedData) => void;
    onDeleted?: (data: CommentDeletedData) => void;
    onResolved?: (data: CommentResolvedData) => void;
    onReopened?: (data: CommentReopenedData) => void;
    onReaction?: (data: CommentReactionData) => void;
  },
  deps: React.DependencyList = []
) {
  useWebSocketMessage((message) => {
    switch (message.type) {
      case "COMMENT_CREATED":
        handlers.onCreated?.(message.data as CommentCreatedData);
        break;
      case "COMMENT_UPDATED":
        handlers.onUpdated?.(message.data as CommentUpdatedData);
        break;
      case "COMMENT_DELETED":
        handlers.onDeleted?.(message.data as CommentDeletedData);
        break;
      case "COMMENT_RESOLVED":
        handlers.onResolved?.(message.data as CommentResolvedData);
        break;
      case "COMMENT_REOPENED":
        handlers.onReopened?.(message.data as CommentReopenedData);
        break;
      case "COMMENT_REACTION":
        handlers.onReaction?.(message.data as CommentReactionData);
        break;
    }
  }, deps);
}

// =============================================================================
// Wallet Domain Hook
// =============================================================================

/**
 * Subscribe to WALLET_BALANCE_CHANGED WebSocket events.
 * The store is updated automatically by the provider; this hook is for
 * additional side-effects (e.g. toast notifications, transaction list refresh).
 */
export function useWalletUpdates(
  onBalanceChanged: (data: WalletBalanceChangedData) => void,
  deps: React.DependencyList = []
) {
  useWebSocketMessage((message) => {
    if (message.type === "WALLET_BALANCE_CHANGED" && message.data) {
      onBalanceChanged(message.data as WalletBalanceChangedData);
    }
  }, deps);
}
