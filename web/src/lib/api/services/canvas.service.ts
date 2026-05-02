import { api } from "../client";

export interface CanvasDTO {
  id: string;
  name: string;
  description?: string;
  workspaceId: string;
  scriptId?: string;
  layoutStrategy?: string;
  locked?: boolean;
  viewport?: Record<string, unknown>;
  settings?: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

export type CanvasEntityType =
  | "SCRIPT"
  | "EPISODE"
  | "STORYBOARD"
  | "CHARACTER"
  | "SCENE"
  | "PROP"
  | "ASSET";

export interface CanvasNodeDTO {
  id: string;
  workspaceId?: string;
  canvasId: string;
  /** ENTITY (业务实体) | GROUP (容器节点) | STICKY_NOTE / SHAPE / IFRAME 等 */
  nodeType?: string;
  entityType?: string;
  entityId?: string;
  layer?: string;
  parentNodeId?: string;
  /** GROUP 节点是否收起；ENTITY 节点也可用于折叠子节点 */
  collapsed?: boolean;
  positionX: number;
  positionY: number;
  width?: number;
  height?: number;
  locked?: boolean;
  zIndex?: number;
  style?: Record<string, unknown>;
  /**
   * 节点内容元信息：
   * - GROUP 节点：{ groupType, label, presetGroup }
   * - 未来的 freeform 节点（STICKY_NOTE/SHAPE/IFRAME）会存放本体文本/形状参数
   * - 普通 ENTITY 节点通常为空（业务字段在 entityDetail）
   */
  content?: Record<string, unknown>;
  /** 实体快照：包含 name / coverUrl / status / relatedAssets 等渲染所需字段 */
  entityDetail?: Record<string, unknown>;
  /** 创建节点时附带创建的边（仅 createNode 响应可能携带） */
  createdEdge?: CanvasEdgeDTO;
  createdAt?: string;
  updatedAt?: string;
}

export interface CanvasEdgeDTO {
  id: string;
  canvasId: string;
  sourceType: string;
  sourceId: string;
  sourceNodeId?: string;
  sourceVersionId?: string;
  sourceHandle?: string;
  targetType: string;
  targetId: string;
  targetNodeId?: string;
  targetVersionId?: string;
  targetHandle?: string;
  relationType?: string;
  relationLabel?: string;
  description?: string;
  lineStyle?: Record<string, unknown>;
  pathType?: string;
  sequence?: number;
  extraInfo?: Record<string, unknown>;
  createdAt?: string;
  updatedAt?: string;
}

export interface CreateCanvasRequest {
  scriptId: string;
  name?: string;
  description?: string;
  layoutStrategy?: string;
  settings?: Record<string, unknown>;
}

export interface CreateNodeRequest {
  canvasId: string;
  entityType: string;
  /** 已有实体引用模式：传 entityId */
  entityId?: string;
  /** 新建实体模式：传 entityName + entityScope (+ scriptId/episodeId) */
  entityName?: string;
  entityDescription?: string;
  entityScope?: "WORKSPACE" | "SCRIPT";
  scriptId?: string;
  episodeId?: string;
  /** ASSET 子类型：IMAGE / VIDEO / AUDIO / TEXT */
  mediaType?: "IMAGE" | "VIDEO" | "AUDIO" | "TEXT";
  entityExtraData?: Record<string, unknown>;
  nodeType?: string;
  content?: Record<string, unknown>;
  layer?: string;
  parentNodeId?: string;
  positionX: number;
  positionY: number;
  width?: number;
  height?: number;
  collapsed?: boolean;
  locked?: boolean;
  zIndex?: number;
  style?: Record<string, unknown>;
  /** 同时创建源边的可选字段 */
  sourceNodeType?: string;
  sourceNodeId?: string;
  sourceHandle?: string;
  targetHandle?: string;
  relationType?: string;
  relationLabel?: string;
  edgeLineStyle?: Record<string, unknown>;
}

export interface UpdateNodeRequest {
  positionX?: number;
  positionY?: number;
  width?: number;
  height?: number;
  collapsed?: boolean;
  locked?: boolean;
  zIndex?: number;
  style?: Record<string, unknown>;
  content?: Record<string, unknown>;
}

export interface CreateEdgeRequest {
  canvasId: string;
  sourceType: string;
  sourceId: string;
  sourceHandle?: string;
  targetType: string;
  targetId: string;
  targetHandle?: string;
  relationType?: string;
  relationLabel?: string;
  description?: string;
  lineStyle?: Record<string, unknown>;
  pathType?: string;
}

export interface BatchPositionUpdate {
  nodeId: string;
  positionX: number;
  positionY: number;
}

export interface CanvasViewDTO {
  id: string;
  canvasId: string;
  viewKey: string;
  name: string;
  icon?: string;
  /** PRESET / CUSTOM */
  viewType: string;
  rootEntityType?: string;
  visibleEntityTypes?: string[];
  visibleLayers?: string[];
  filterConfig?: Record<string, unknown>;
  viewport?: Record<string, unknown>;
  layoutStrategy?: string;
  sequence?: number;
  isDefault?: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface CanvasFullResponse extends CanvasDTO {
  nodes: CanvasNodeDTO[];
  edges: CanvasEdgeDTO[];
}

export interface ViewDataRequest {
  canvasId: string;
  viewKey?: string;
  rootEntityType?: string;
  rootEntityId?: string;
  includeEntityDetail?: boolean;
}

export interface ViewDataResponse {
  canvasId: string;
  viewKey?: string;
  nodes: CanvasNodeDTO[];
  edges: CanvasEdgeDTO[];
}

export type CanvasOperationType =
  | "CREATE_NODE"
  | "UPDATE_NODE"
  | "DELETE_NODE"
  | "CREATE_EDGE"
  | "UPDATE_EDGE"
  | "DELETE_EDGE"
  | "BATCH_UPDATE";

export interface CanvasOperation {
  id: string;
  canvasId: string;
  userId?: string;
  type: CanvasOperationType;
  targetId?: string;
  beforeState?: Record<string, unknown>;
  afterState?: Record<string, unknown>;
  timestamp?: string;
}

class CanvasService {
  // Canvas CRUD
  async listCanvases() {
    return api.get<CanvasDTO[]>("/api/canvas");
  }

  async getCanvas(canvasId: string) {
    return api.get<CanvasDTO>(`/api/canvas/${canvasId}`);
  }

  /**
   * 后端模型：1 Script = 1 Canvas。从剧本进入画布时调此接口，
   * 已存在直接返回，不存在则自动创建并初始化预设视图与剧本节点。
   */
  async getOrCreateByScriptId(scriptId: string) {
    return api.get<CanvasDTO>(`/api/canvas/script/${scriptId}/ensure`);
  }

  async createCanvas(request: CreateCanvasRequest) {
    return api.post<CanvasDTO>("/api/canvas", request);
  }

  async deleteCanvas(canvasId: string) {
    return api.delete(`/api/canvas/${canvasId}`);
  }

  // Node CRUD
  async listNodes(canvasId: string) {
    return api.get<CanvasNodeDTO[]>(`/api/canvas/${canvasId}/nodes`);
  }

  async createNode(request: CreateNodeRequest) {
    return api.post<CanvasNodeDTO>("/api/canvas/nodes", request);
  }

  async updateNode(nodeId: string, request: UpdateNodeRequest) {
    return api.put<CanvasNodeDTO>(`/api/canvas/nodes/${nodeId}`, request);
  }

  async deleteNode(nodeId: string) {
    return api.delete(`/api/canvas/nodes/${nodeId}`);
  }

  // Node batch operations
  async batchUpdateNodes(nodeIds: string[], deltaX?: number, deltaY?: number) {
    return api.post("/api/canvas/nodes/batch/update", {
      nodeIds,
      deltaX,
      deltaY,
    });
  }

  async batchDeleteNodes(nodeIds: string[]) {
    return api.post("/api/canvas/nodes/batch/delete", nodeIds);
  }

  /**
   * 批量更新节点位置（拖拽多选时使用），后端 PUT /canvas/nodes/batch/positions
   * 接收 List<UpdateNodeRequest>，每条需带 nodeId
   */
  async batchUpdatePositions(updates: BatchPositionUpdate[]) {
    return api.put("/api/canvas/nodes/batch/positions", updates);
  }

  // Edge CRUD
  async listEdges(canvasId: string) {
    return api.get<CanvasEdgeDTO[]>(`/api/canvas/edges/canvas/${canvasId}`);
  }

  async createEdge(request: CreateEdgeRequest) {
    return api.post<CanvasEdgeDTO>("/api/canvas/edges", request);
  }

  async deleteEdge(edgeId: string) {
    return api.delete(`/api/canvas/edges/${edgeId}`);
  }

  // Full snapshot
  async getCanvasFull(canvasId: string) {
    return api.get<CanvasFullResponse>(`/api/canvas/${canvasId}/full`);
  }

  // Views
  async listViews(canvasId: string) {
    return api.get<CanvasViewDTO[]>(`/api/canvas/${canvasId}/views`);
  }

  async getViewByKey(canvasId: string, viewKey: string) {
    return api.get<CanvasViewDTO>(`/api/canvas/${canvasId}/views/key/${viewKey}`);
  }

  /** 按视图筛选拉数据（节点+边），支持包含 entityDetail */
  async getViewData(canvasId: string, viewKey: string, includeEntityDetail = true) {
    return api.get<ViewDataResponse>(
      `/api/canvas/${canvasId}/view/${viewKey}?includeEntityDetail=${includeEntityDetail}`
    );
  }

  // Auto layout
  async autoLayout(
    canvasId: string,
    strategy: "GRID" | "TREE" | "FORCE",
    viewKey?: string,
  ) {
    const params = new URLSearchParams({ strategy });
    if (viewKey) params.set("viewKey", viewKey);
    return api.post<CanvasFullResponse>(
      `/api/canvas/${canvasId}/auto-layout?${params.toString()}`,
    );
  }

  // History (Undo / Redo / list)
  async undo(canvasId: string) {
    return api.post(`/api/canvas/${canvasId}/undo`);
  }

  async redo(canvasId: string) {
    return api.post(`/api/canvas/${canvasId}/redo`);
  }

  async listHistory(canvasId: string, limit = 20) {
    return api.get<CanvasOperation[]>(`/api/canvas/${canvasId}/history?limit=${limit}`);
  }
}

export const canvasService = new CanvasService();
