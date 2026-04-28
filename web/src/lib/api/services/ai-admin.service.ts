/**
 * AI Admin Service
 * Endpoints for model provider / llm provider / groovy template management.
 */

import { api } from "../client";
import type {
  AiPageResponseDTO,
  AiProviderType,
  GroovyTemplateDTO,
  GroovyTemplateType,
  LlmProviderDTO,
  ModelProviderDTO,
  NormalizedPageDTO,
  ProviderWhitelistEntryDTO,
  SaveGroovyTemplateRequestDTO,
  SaveLlmProviderRequestDTO,
  SaveModelProviderRequestDTO,
  ScriptTestResultDTO,
  ScriptValidationDTO,
} from "../dto/ai-admin.dto";

const AI_ADMIN_BASE = "/api/ai";

function normalizePageResponse<T>(
  raw: AiPageResponseDTO<T> | T[] | null | undefined,
  fallbackCurrent: number,
  fallbackSize: number
): NormalizedPageDTO<T> {
  if (!raw) {
    return {
      records: [],
      total: 0,
      current: fallbackCurrent,
      size: fallbackSize,
      pages: 0,
    };
  }

  if (Array.isArray(raw)) {
    const pages = raw.length === 0 ? 0 : Math.ceil(raw.length / fallbackSize);
    return {
      records: raw,
      total: raw.length,
      current: fallbackCurrent,
      size: fallbackSize,
      pages,
    };
  }

  const records = raw.records ?? raw.list ?? raw.items ?? [];
  const total = Number(raw.total ?? records.length);
  const current = Number(raw.current ?? raw.pageNum ?? raw.page ?? fallbackCurrent);
  const size = Number(raw.size ?? raw.pageSize ?? fallbackSize);
  const pages = Number(raw.pages ?? (size > 0 ? Math.ceil(total / size) : 0));

  return {
    records,
    total,
    current,
    size,
    pages,
  };
}

export const aiAdminService = {
  // ============ Model Provider ============
  getModelProviderPage: async (params?: {
    pageNum?: number;
    pageSize?: number;
    providerType?: AiProviderType;
    enabled?: boolean;
    name?: string;
  }): Promise<NormalizedPageDTO<ModelProviderDTO>> => {
    const current = params?.pageNum ?? 1;
    const size = params?.pageSize ?? 20;

    const raw = await api.get<AiPageResponseDTO<ModelProviderDTO> | ModelProviderDTO[]>(
      `${AI_ADMIN_BASE}/model-providers`,
      {
        params: {
          pageNum: current,
          pageSize: size,
          providerType: params?.providerType,
          enabled: params?.enabled,
          name: params?.name,
        },
      }
    );

    return normalizePageResponse(raw, current, size);
  },

  getModelProviderById: (id: string) =>
    api.get<ModelProviderDTO>(`${AI_ADMIN_BASE}/model-providers/${id}`),

  createModelProvider: (data: SaveModelProviderRequestDTO) =>
    api.post<ModelProviderDTO>(`${AI_ADMIN_BASE}/model-providers`, data),

  updateModelProvider: (id: string, data: SaveModelProviderRequestDTO) =>
    api.put<ModelProviderDTO>(`${AI_ADMIN_BASE}/model-providers/${id}`, data),

  deleteModelProvider: (id: string) =>
    api.delete<null>(`${AI_ADMIN_BASE}/model-providers/${id}`),

  enableModelProvider: (id: string) =>
    api.post<null>(`${AI_ADMIN_BASE}/model-providers/${id}/enable`),

  disableModelProvider: (id: string) =>
    api.post<null>(`${AI_ADMIN_BASE}/model-providers/${id}/disable`),

  testModelProviderConnection: (id: string) =>
    api.post<{ connected?: boolean; message?: string; latencyMs?: number }>(
      `${AI_ADMIN_BASE}/model-providers/${id}/test`
    ),

  // ============ Provider 灰度白名单 ============
  /**
   * 添加 workspace 到 provider 白名单（visibility=WHITELIST 时生效）
   */
  addProviderWhitelist: (
    providerId: string,
    data: { workspaceId: string; note?: string }
  ) =>
    api.post<ProviderWhitelistEntryDTO>(
      `${AI_ADMIN_BASE}/model-providers/${providerId}/whitelist`,
      data
    ),

  /**
   * 从 provider 白名单移除 workspace
   */
  removeProviderWhitelist: (providerId: string, workspaceId: string) =>
    api.delete<null>(
      `${AI_ADMIN_BASE}/model-providers/${providerId}/whitelist/${workspaceId}`
    ),

  /**
   * 查询 provider 白名单
   */
  listProviderWhitelist: (providerId: string) =>
    api.get<ProviderWhitelistEntryDTO[]>(
      `${AI_ADMIN_BASE}/model-providers/${providerId}/whitelist`
    ),

  // ============ LLM Provider ============
  getLlmProviderPage: async (params?: {
    current?: number;
    size?: number;
    provider?: string;
    enabled?: boolean;
    modelName?: string;
  }): Promise<NormalizedPageDTO<LlmProviderDTO>> => {
    const current = params?.current ?? 1;
    const size = params?.size ?? 10;

    const raw = await api.get<AiPageResponseDTO<LlmProviderDTO> | LlmProviderDTO[]>(
      `${AI_ADMIN_BASE}/llm-providers`,
      {
        params: {
          current,
          size,
          provider: params?.provider,
          enabled: params?.enabled,
          modelName: params?.modelName,
        },
      }
    );

    return normalizePageResponse(raw, current, size);
  },

  getLlmProviderById: (id: string) =>
    api.get<LlmProviderDTO>(`${AI_ADMIN_BASE}/llm-providers/${id}`),

  createLlmProvider: (data: SaveLlmProviderRequestDTO) =>
    api.post<LlmProviderDTO>(`${AI_ADMIN_BASE}/llm-providers`, data),

  updateLlmProvider: (id: string, data: SaveLlmProviderRequestDTO) =>
    api.put<LlmProviderDTO>(`${AI_ADMIN_BASE}/llm-providers/${id}`, data),

  deleteLlmProvider: (id: string) =>
    api.delete<null>(`${AI_ADMIN_BASE}/llm-providers/${id}`),

  toggleLlmProvider: (id: string, enabled: boolean) =>
    api.put<null>(`${AI_ADMIN_BASE}/llm-providers/${id}/toggle`, undefined, {
      params: { enabled },
    }),

  refreshLlmProviderCache: () =>
    api.post<null>(`${AI_ADMIN_BASE}/llm-providers/cache/refresh`),

  refreshLlmProviderCacheById: (id: string) =>
    api.post<null>(`${AI_ADMIN_BASE}/llm-providers/${id}/cache/refresh`),

  // ============ Groovy Template ============
  getGroovyTemplatePage: async (params?: {
    pageNum?: number;
    pageSize?: number;
    templateType?: GroovyTemplateType | string;
    generationType?: string;
    isSystem?: boolean;
    name?: string;
  }): Promise<NormalizedPageDTO<GroovyTemplateDTO>> => {
    const current = params?.pageNum ?? 1;
    const size = params?.pageSize ?? 20;

    const raw = await api.get<AiPageResponseDTO<GroovyTemplateDTO> | GroovyTemplateDTO[]>(
      `${AI_ADMIN_BASE}/groovy-templates`,
      {
        params: {
          pageNum: current,
          pageSize: size,
          templateType: params?.templateType,
          generationType: params?.generationType,
          isSystem: params?.isSystem,
          name: params?.name,
        },
      }
    );

    return normalizePageResponse(raw, current, size);
  },

  getGroovyTemplateById: (id: string) =>
    api.get<GroovyTemplateDTO>(`${AI_ADMIN_BASE}/groovy-templates/${id}`),

  createGroovyTemplate: (data: SaveGroovyTemplateRequestDTO) =>
    api.post<GroovyTemplateDTO>(`${AI_ADMIN_BASE}/groovy-templates`, data),

  updateGroovyTemplate: (id: string, data: SaveGroovyTemplateRequestDTO) =>
    api.put<GroovyTemplateDTO>(`${AI_ADMIN_BASE}/groovy-templates/${id}`, data),

  deleteGroovyTemplate: (id: string) =>
    api.delete<null>(`${AI_ADMIN_BASE}/groovy-templates/${id}`),

  toggleGroovyTemplate: (id: string, enabled: boolean) =>
    api.put<null>(`${AI_ADMIN_BASE}/groovy-templates/${id}/toggle`, undefined, {
      params: { enabled },
    }),

  validateGroovyScript: (scriptContent: string) =>
    api.post<ScriptValidationDTO>(`${AI_ADMIN_BASE}/groovy-templates/validate`, {
      scriptContent,
    }),

  testGroovyScript: (data: {
    scriptContent: string;
    templateType: GroovyTemplateType | string;
    inputs?: Record<string, unknown>;
    config?: Record<string, unknown>;
  }) =>
    api.post<ScriptTestResultDTO>(`${AI_ADMIN_BASE}/groovy-templates/test`, data),
};

export default aiAdminService;

