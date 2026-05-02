"use client";

import { useCallback, useState, useEffect, useRef, useMemo } from "react";
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  Panel,
  useNodesState,
  useEdgesState,
  type Connection,
  type Edge,
  type Node,
  type NodeChange,
  type ReactFlowInstance,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { Button, Card, toast } from "@heroui/react";
import {
  User,
  Undo2,
  Redo2,
  LayoutGrid,
  Network,
  Sparkles,
} from "lucide-react";
import Image from "next/image";
import { useRouter } from "next/navigation";
import { useTheme } from "@/components/providers/theme-provider";
import {
  canvasService,
  type CanvasNodeDTO,
  type CanvasEdgeDTO,
} from "@/lib/api/services/canvas.service";
import { useLocale } from "next-intl";
import { getErrorFromException } from "@/lib/api";
import { useCanvasRealtime } from "./use-canvas-realtime";
import { EntityNode } from "./entity-node";
import { InlineMediaPicker, type MediaPickerType } from "./inline-media-picker";

type LayoutStrategy = "GRID" | "TREE" | "FORCE";
const LAYOUT_BUTTONS: Array<{ key: LayoutStrategy; label: string; icon: typeof User }> = [
  { key: "GRID", label: "网格", icon: LayoutGrid },
  { key: "TREE", label: "树状", icon: Network },
  { key: "FORCE", label: "力导向", icon: Sparkles },
];

const NODE_TYPES = { entity: EntityNode };

const MEDIA_LABEL_ZH: Record<MediaPickerType, string> = {
  IMAGE: "图片",
  VIDEO: "视频",
  AUDIO: "音频",
  TEXT: "文本",
};

function readNodeLabel(node: CanvasNodeDTO): string {
  const detail = node.entityDetail;
  if (detail && typeof detail === "object") {
    const name = (detail as { name?: unknown }).name;
    if (typeof name === "string" && name) return name;
  }
  if (node.entityType) return node.entityType;
  return "节点";
}

function nodeToReactFlow(node: CanvasNodeDTO): Node {
  return {
    id: node.id,
    type: "entity",
    position: { x: node.positionX, y: node.positionY },
    data: {
      label: readNodeLabel(node),
      entityType: node.entityType,
      nodeData: node,
    },
  };
}

function edgeToReactFlow(
  edge: CanvasEdgeDTO,
  entityToNodeId?: Map<string, string>
): Edge {
  const lookup = (type?: string, id?: string): string | undefined => {
    if (!type || !id || !entityToNodeId) return undefined;
    return entityToNodeId.get(`${type}:${id}`);
  };
  // 优先：后端返回的 sourceNodeId/targetNodeId；
  // 其次：从本地 entity→node id 反查（防御后端某些路径未填）；
  // 兜底：用 entity id（React Flow 会找不到节点而不渲染，但至少不崩）。
  const source = edge.sourceNodeId || lookup(edge.sourceType, edge.sourceId) || edge.sourceId;
  const target = edge.targetNodeId || lookup(edge.targetType, edge.targetId) || edge.targetId;
  return {
    id: edge.id,
    source,
    target,
    sourceHandle: edge.sourceHandle,
    targetHandle: edge.targetHandle,
    label: edge.relationLabel,
    style: edge.lineStyle as React.CSSProperties | undefined,
  };
}

export function CanvasEditorPage({ canvasId }: { canvasId: string }) {
  const { resolvedTheme } = useTheme();
  const locale = useLocale();
  const router = useRouter();
  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [scriptId, setScriptId] = useState<string | null>(null);
  const [scriptName, setScriptName] = useState<string>("");
  const [loading, setLoading] = useState(true);
  const [busyAction, setBusyAction] = useState<"undo" | "redo" | "layout" | null>(null);
  const [picker, setPicker] = useState<{
    screenX: number;
    screenY: number;
    flowX: number;
    flowY: number;
    /** 拖线触发时携带的源节点 id；双击空白触发时为 null */
    fromNodeId: string | null;
  } | null>(null);
  const [hasNonScriptNodes, setHasNonScriptNodes] = useState(false);
  const flowRef = useRef<ReactFlowInstance | null>(null);
  const nodeMetaRef = useRef<Map<string, { entityType: string; entityId: string }>>(new Map());
  /** entity→nodeId 反查（用于 edgeToReactFlow fallback） */
  const entityToNodeIdRef = useRef<Map<string, string>>(new Map());
  const nodesDtoRef = useRef<CanvasNodeDTO[]>([]);
  const edgesDtoRef = useRef<CanvasEdgeDTO[]>([]);
  /** 拖线 in-flight 上下文，onConnectStart 写入，onConnectEnd 读取后清空 */
  const connectingFromRef = useRef<string | null>(null);

  const updateEmptyState = useCallback(() => {
    const hasOther = nodesDtoRef.current.some(
      (n) => n.nodeType !== "GROUP" && n.entityType !== "SCRIPT"
    );
    setHasNonScriptNodes(hasOther);
  }, []);

  const buildReactFlowNodes = useCallback((dtoList: CanvasNodeDTO[]): Node[] => {
    return dtoList
      .filter((n) => n.nodeType !== "GROUP")
      .map(nodeToReactFlow);
  }, []);

  const ingestNodes = useCallback(
    (nodeList: CanvasNodeDTO[]) => {
      nodeMetaRef.current.clear();
      entityToNodeIdRef.current.clear();
      let detectedScriptName = "";
      for (const n of nodeList) {
        if (n.entityType && n.entityId) {
          nodeMetaRef.current.set(n.id, { entityType: n.entityType, entityId: n.entityId });
          entityToNodeIdRef.current.set(`${n.entityType}:${n.entityId}`, n.id);
        }
        if (n.entityType === "SCRIPT" && n.entityDetail) {
          const detail = n.entityDetail as { name?: unknown };
          if (typeof detail.name === "string") detectedScriptName = detail.name;
        }
      }
      nodesDtoRef.current = nodeList;
      setNodes(buildReactFlowNodes(nodeList));
      // edges 也要重建（entityToNodeId 变化会影响 fallback）
      setEdges(edgesDtoRef.current.map((e) => edgeToReactFlow(e, entityToNodeIdRef.current)));
      if (detectedScriptName) setScriptName(detectedScriptName);
      updateEmptyState();
    },
    [buildReactFlowNodes, setEdges, setNodes, updateEmptyState]
  );

  const ingestEdges = useCallback(
    (edgeList: CanvasEdgeDTO[]) => {
      edgesDtoRef.current = edgeList;
      setEdges(edgeList.map((e) => edgeToReactFlow(e, entityToNodeIdRef.current)));
    },
    [setEdges]
  );

  const reloadCanvas = useCallback(async () => {
    const [nodeList, edgeList] = await Promise.all([
      canvasService.listNodes(canvasId),
      canvasService.listEdges(canvasId),
    ]);
    ingestNodes(nodeList);
    ingestEdges(edgeList);
  }, [canvasId, ingestEdges, ingestNodes]);

  // 初始化加载
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [canvas, nodeList, edgeList] = await Promise.all([
          canvasService.getCanvas(canvasId),
          canvasService.listNodes(canvasId),
          canvasService.listEdges(canvasId),
        ]);
        if (cancelled) return;
        setScriptId(canvas.scriptId ?? null);
        setScriptName(canvas.name); // 先用画布名兜底
        ingestNodes(nodeList);
        ingestEdges(edgeList);
      } catch (error) {
        console.error("Failed to load canvas:", error);
        toast.danger(getErrorFromException(error, locale));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [canvasId, ingestEdges, ingestNodes, locale]);

  // 节点变化处理：实时把 position 同步回 dtoRef（避免后续 setNodes 用旧位置覆盖）；
  // 拖拽结束时持久化到后端
  const handleNodesChange = useCallback(
    (changes: NodeChange[]) => {
      onNodesChange(changes);

      // 1) 任何 position 变化（含拖拽中）都同步 dto ref
      let dtoChanged = false;
      const dtoMap = new Map(nodesDtoRef.current.map((n) => [n.id, n]));
      for (const change of changes) {
        if (change.type === "position" && change.position) {
          const dto = dtoMap.get(change.id);
          if (dto) {
            dto.positionX = change.position.x;
            dto.positionY = change.position.y;
            dtoChanged = true;
          }
        }
      }
      if (dtoChanged) {
        nodesDtoRef.current = Array.from(dtoMap.values());
      }

      // 2) 拖拽结束时（dragging=false）持久化
      const finished: Array<{ nodeId: string; positionX: number; positionY: number }> = [];
      for (const change of changes) {
        if (change.type === "position" && !change.dragging && change.position) {
          finished.push({
            nodeId: change.id,
            positionX: change.position.x,
            positionY: change.position.y,
          });
        }
      }
      if (finished.length === 0) return;
      (async () => {
        try {
          if (finished.length === 1) {
            const f = finished[0];
            await canvasService.updateNode(f.nodeId, {
              positionX: f.positionX,
              positionY: f.positionY,
            });
          } else {
            await canvasService.batchUpdatePositions(finished);
          }
        } catch (error) {
          console.error("Failed to persist node positions:", error);
          toast.danger(getErrorFromException(error, locale));
        }
      })();
    },
    [locale, onNodesChange]
  );

  // 连线（节点 → 节点都已存在）
  const onConnect = useCallback(
    async (params: Connection) => {
      if (!params.source || !params.target) return;
      const sourceMeta = nodeMetaRef.current.get(params.source);
      const targetMeta = nodeMetaRef.current.get(params.target);
      if (!sourceMeta || !targetMeta) return;

      try {
        const edge = await canvasService.createEdge({
          canvasId,
          sourceType: sourceMeta.entityType,
          sourceId: sourceMeta.entityId,
          sourceHandle: params.sourceHandle ?? undefined,
          targetType: targetMeta.entityType,
          targetId: targetMeta.entityId,
          targetHandle: params.targetHandle ?? undefined,
        });
        edgesDtoRef.current = [...edgesDtoRef.current, edge];
        setEdges((eds) => [...eds, edgeToReactFlow(edge, entityToNodeIdRef.current)]);
      } catch (error) {
        console.error("Failed to create edge:", error);
        toast.danger(getErrorFromException(error, locale));
      }
    },
    [canvasId, locale, setEdges]
  );

  // 拖线开始 — 记录源节点 id，等 onConnectEnd 时如果松开在 pane 上就弹 picker
  const onConnectStart = useCallback(
    (_: unknown, { nodeId }: { nodeId: string | null }) => {
      connectingFromRef.current = nodeId;
    },
    []
  );

  // 拖线结束 — React Flow v12 第二个参数 connectionState 含 isValid / fromNode 等
  const onConnectEnd = useCallback(
    (
      event: MouseEvent | TouchEvent,
      connectionState?: {
        isValid?: boolean | null;
        fromNode?: { id: string } | null;
      }
    ) => {
      const fromNodeId = connectionState?.fromNode?.id ?? connectingFromRef.current;
      connectingFromRef.current = null;
      if (!fromNodeId) return;

      // 1) 显式 valid → 已成功连接两个节点，onConnect 会处理
      if (connectionState?.isValid === true) return;

      // 2) 显式 invalid 或 null → 松开在空白
      // 3) connectionState 不存在（保守 fallback）→ 用 target 元素判定
      if (connectionState === undefined) {
        const target = event.target as HTMLElement | null;
        if (target?.closest(".react-flow__node, .react-flow__handle")) return;
      }

      const flowInstance = flowRef.current;
      if (!flowInstance) return;
      let clientX = 0;
      let clientY = 0;
      if ("clientX" in event) {
        clientX = event.clientX;
        clientY = event.clientY;
      } else if (event.changedTouches?.length) {
        clientX = event.changedTouches[0].clientX;
        clientY = event.changedTouches[0].clientY;
      }
      const flowPos = flowInstance.screenToFlowPosition({ x: clientX, y: clientY });

      setPicker({
        screenX: clientX,
        screenY: clientY,
        flowX: flowPos.x,
        flowY: flowPos.y,
        fromNodeId,
      });
    },
    []
  );

  const handleNodesDelete = useCallback(
    async (deleted: Node[]) => {
      const ids = deleted.map((n) => n.id);
      try {
        if (ids.length === 1) {
          await canvasService.deleteNode(ids[0]);
        } else if (ids.length > 1) {
          await canvasService.batchDeleteNodes(ids);
        }
        for (const id of ids) nodeMetaRef.current.delete(id);
        nodesDtoRef.current = nodesDtoRef.current.filter((n) => !ids.includes(n.id));
        updateEmptyState();
      } catch (error) {
        console.error("Failed to delete nodes:", error);
        toast.danger(getErrorFromException(error, locale));
        try {
          await reloadCanvas();
        } catch {
          // ignore
        }
      }
    },
    [locale, reloadCanvas, updateEmptyState]
  );

  const handleEdgesDelete = useCallback(
    async (deleted: Edge[]) => {
      try {
        for (const e of deleted) {
          await canvasService.deleteEdge(e.id);
        }
        const deletedIds = new Set(deleted.map((e) => e.id));
        edgesDtoRef.current = edgesDtoRef.current.filter((e) => !deletedIds.has(e.id));
      } catch (error) {
        console.error("Failed to delete edges:", error);
        toast.danger(getErrorFromException(error, locale));
      }
    },
    [locale]
  );

  // 双击空白 → InlineMediaPicker
  const handlePaneDoubleClick = useCallback((e: React.MouseEvent) => {
    const flowInstance = flowRef.current;
    if (!flowInstance) return;
    const flowPos = flowInstance.screenToFlowPosition({ x: e.clientX, y: e.clientY });
    setPicker({
      screenX: e.clientX,
      screenY: e.clientY,
      flowX: flowPos.x,
      flowY: flowPos.y,
      fromNodeId: null,
    });
  }, []);

  const handlePickMedia = useCallback(
    async (mediaType: MediaPickerType) => {
      if (!picker) return;
      const { flowX, flowY, fromNodeId } = picker;
      setPicker(null);

      if (!scriptId) {
        toast.danger("当前画布无关联剧本，无法创建节点");
        return;
      }

      const sameTypeCount = nodesDtoRef.current.filter((n) => {
        if (n.entityType !== "ASSET") return false;
        const detail = (n.entityDetail ?? {}) as { mediaType?: unknown };
        return typeof detail.mediaType === "string"
          && detail.mediaType.toUpperCase() === mediaType;
      }).length;
      const entityName = `新${MEDIA_LABEL_ZH[mediaType]} ${sameTypeCount + 1}`;

      try {
        const node = await canvasService.createNode({
          canvasId,
          entityType: "ASSET",
          entityName,
          entityScope: "SCRIPT",
          scriptId,
          mediaType,
          positionX: flowX,
          positionY: flowY,
        });
        if (node.entityType && node.entityId) {
          nodeMetaRef.current.set(node.id, {
            entityType: node.entityType,
            entityId: node.entityId,
          });
          entityToNodeIdRef.current.set(`${node.entityType}:${node.entityId}`, node.id);
        }
        nodesDtoRef.current = [...nodesDtoRef.current, node];
        // 增量追加：保留其他节点的 React Flow 当前 position
        setNodes((prev) => [...prev, nodeToReactFlow(node)]);
        updateEmptyState();

        // 拖线生节点：创建一条边连接 fromNodeId → 新节点
        if (fromNodeId && node.entityType && node.entityId) {
          const sourceMeta = nodeMetaRef.current.get(fromNodeId);
          if (sourceMeta) {
            try {
              const edge = await canvasService.createEdge({
                canvasId,
                sourceType: sourceMeta.entityType,
                sourceId: sourceMeta.entityId,
                targetType: node.entityType,
                targetId: node.entityId,
              });
              edgesDtoRef.current = [...edgesDtoRef.current, edge];
              setEdges((eds) => [...eds, edgeToReactFlow(edge, entityToNodeIdRef.current)]);
            } catch (edgeErr) {
              console.error("Failed to create edge after node:", edgeErr);
            }
          }
        }
      } catch (error) {
        console.error("Failed to create asset node:", error);
        toast.danger(getErrorFromException(error, locale));
      }
    },
    [buildReactFlowNodes, canvasId, locale, picker, scriptId, setEdges, setNodes, updateEmptyState]
  );

  // 实时同步：增量 patch 单个节点，不全量重建（保留其他节点的 React Flow 当前位置）
  const upsertNodeFromRealtime = useCallback(
    (incoming: CanvasNodeDTO) => {
      if (incoming.nodeType === "GROUP") return;
      if (incoming.entityType && incoming.entityId) {
        nodeMetaRef.current.set(incoming.id, {
          entityType: incoming.entityType,
          entityId: incoming.entityId,
        });
        entityToNodeIdRef.current.set(`${incoming.entityType}:${incoming.entityId}`, incoming.id);
      }
      const list = nodesDtoRef.current;
      const idx = list.findIndex((n) => n.id === incoming.id);
      const isNew = idx === -1;
      if (isNew) {
        nodesDtoRef.current = [...list, incoming];
      } else {
        const existing = list[idx];
        const merged: CanvasNodeDTO = {
          ...existing,
          ...incoming,
          entityDetail: incoming.entityDetail ?? existing.entityDetail,
        };
        const copy = list.slice();
        copy[idx] = merged;
        nodesDtoRef.current = copy;
      }
      setNodes((prev) => {
        const i = prev.findIndex((n) => n.id === incoming.id);
        const next = nodeToReactFlow(nodesDtoRef.current[isNew ? nodesDtoRef.current.length - 1 : idx]);
        if (i === -1) return [...prev, next];
        const copy = prev.slice();
        // 保留 React Flow 当前 position（避免 ws update 把用户拖动中的位置抢回）
        copy[i] = { ...next, position: copy[i].position };
        return copy;
      });
      // 节点变化可能让之前 fallback 失败的边能找到 source/target —— 重建一次
      setEdges(edgesDtoRef.current.map((e) => edgeToReactFlow(e, entityToNodeIdRef.current)));
      updateEmptyState();
    },
    [setEdges, setNodes, updateEmptyState]
  );

  const upsertEdgeFromRealtime = useCallback(
    (incoming: CanvasEdgeDTO) => {
      // 同步到 dto ref
      const list = edgesDtoRef.current;
      const idx = list.findIndex((e) => e.id === incoming.id);
      if (idx === -1) {
        edgesDtoRef.current = [...list, incoming];
      } else {
        const copy = list.slice();
        copy[idx] = incoming;
        edgesDtoRef.current = copy;
      }
      const next = edgeToReactFlow(incoming, entityToNodeIdRef.current);
      setEdges((eds) => {
        const i = eds.findIndex((e) => e.id === incoming.id);
        if (i === -1) return [...eds, next];
        const copy = eds.slice();
        copy[i] = next;
        return copy;
      });
    },
    [setEdges]
  );

  useCanvasRealtime(canvasId, {
    onNodeCreated: upsertNodeFromRealtime,
    onNodeUpdated: upsertNodeFromRealtime,
    onNodeDeleted: (nodeId) => {
      nodeMetaRef.current.delete(nodeId);
      nodesDtoRef.current = nodesDtoRef.current.filter((n) => n.id !== nodeId);
      setNodes(buildReactFlowNodes(nodesDtoRef.current));
      setEdges((eds) => eds.filter((e) => e.source !== nodeId && e.target !== nodeId));
      updateEmptyState();
    },
    onBatchNodesUpdated: (incomingList) => {
      for (const incoming of incomingList) upsertNodeFromRealtime(incoming);
    },
    onEdgeCreated: upsertEdgeFromRealtime,
    onEdgeUpdated: upsertEdgeFromRealtime,
    onEdgeDeleted: (edgeId) => {
      edgesDtoRef.current = edgesDtoRef.current.filter((e) => e.id !== edgeId);
      setEdges((eds) => eds.filter((e) => e.id !== edgeId));
    },
  });

  const handleAutoLayout = useCallback(
    async (strategy: LayoutStrategy) => {
      setBusyAction("layout");
      try {
        const full = await canvasService.autoLayout(canvasId, strategy);
        ingestNodes(full.nodes ?? []);
        ingestEdges(full.edges ?? []);
      } catch (error) {
        console.error("Failed to auto-layout:", error);
        toast.danger(getErrorFromException(error, locale));
      } finally {
        setBusyAction(null);
      }
    },
    [canvasId, ingestEdges, ingestNodes, locale]
  );

  const handleUndo = useCallback(async () => {
    setBusyAction("undo");
    try {
      await canvasService.undo(canvasId);
      await reloadCanvas();
    } catch (error) {
      console.error("Failed to undo:", error);
      toast.danger(getErrorFromException(error, locale));
    } finally {
      setBusyAction(null);
    }
  }, [canvasId, locale, reloadCanvas]);

  const handleRedo = useCallback(async () => {
    setBusyAction("redo");
    try {
      await canvasService.redo(canvasId);
      await reloadCanvas();
    } catch (error) {
      console.error("Failed to redo:", error);
      toast.danger(getErrorFromException(error, locale));
    } finally {
      setBusyAction(null);
    }
  }, [canvasId, locale, reloadCanvas]);

  const handleBackToScript = useCallback(() => {
    if (!scriptId) return;
    router.push(`/${locale}/workspace/projects/${scriptId}`);
  }, [locale, router, scriptId]);

  const colorMode = useMemo(
    () => (resolvedTheme === "dark" ? "dark" : "light"),
    [resolvedTheme]
  );

  if (loading) {
    return (
      <div className="fixed inset-0 z-50 flex items-center justify-center bg-background">
        <p className="text-muted">加载中...</p>
      </div>
    );
  }

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-background">
      <div className="relative flex-1">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={NODE_TYPES}
          onInit={(inst) => {
            flowRef.current = inst;
          }}
          onNodesChange={handleNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          onNodesDelete={handleNodesDelete}
          onEdgesDelete={handleEdgesDelete}
          onConnectStart={onConnectStart}
          onConnectEnd={onConnectEnd}
          onPaneClick={() => setPicker(null)}
          onDoubleClick={handlePaneDoubleClick}
          deleteKeyCode={["Delete", "Backspace"]}
          zoomOnDoubleClick={false}
          fitView
          colorMode={colorMode}
        >
          <Background />
          <Controls />
          <MiniMap />

          {/* 左上角：logo + 剧本名 + 工具按钮 */}
          <Panel position="top-left" className="m-4">
            <Card variant="default" className="flex flex-row items-center gap-2 px-3 py-1.5">
              <button
                type="button"
                onClick={handleBackToScript}
                aria-label="返回剧本管理"
                className="flex items-center gap-2 rounded-md px-1 transition-opacity hover:opacity-70"
              >
                <Image src="/logo.png" alt="logo" width={20} height={20} />
                <span className="text-sm font-medium">{scriptName || "画布"}</span>
              </button>

              <span className="h-4 w-px bg-default-300" />

              <Button
                size="sm"
                variant="ghost"
                isIconOnly
                aria-label="撤销"
                onPress={handleUndo}
                isPending={busyAction === "undo"}
              >
                <Undo2 className="size-4" />
              </Button>
              <Button
                size="sm"
                variant="ghost"
                isIconOnly
                aria-label="重做"
                onPress={handleRedo}
                isPending={busyAction === "redo"}
              >
                <Redo2 className="size-4" />
              </Button>

              <span className="h-4 w-px bg-default-300" />

              {LAYOUT_BUTTONS.map((btn) => (
                <Button
                  key={btn.key}
                  size="sm"
                  variant="ghost"
                  isIconOnly
                  aria-label={btn.label}
                  onPress={() => handleAutoLayout(btn.key)}
                  isPending={busyAction === "layout"}
                >
                  <btn.icon className="size-4" />
                </Button>
              ))}
            </Card>
          </Panel>

        </ReactFlow>

        {/* 空画布提示 - 居中绝对定位（不占交互） */}
        {!hasNonScriptNodes && (
          <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
            <p className="text-sm text-muted">双击画布创建节点</p>
          </div>
        )}
      </div>

      {picker && (
        <InlineMediaPicker
          screenX={picker.screenX}
          screenY={picker.screenY}
          title={picker.fromNodeId ? "引用该节点生成" : "添加节点"}
          onSelect={handlePickMedia}
          onDismiss={() => setPicker(null)}
        />
      )}
    </div>
  );
}
