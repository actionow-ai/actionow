"use client";

import { useState, useEffect, useCallback, useMemo } from "react";
import { toast } from "@heroui/react";
import { useLocale } from "next-intl";
import { getErrorFromException } from "@/lib/api";
import { aiService } from "@/lib/api/services/ai.service";
import { aiGenerationCache } from "@/lib/stores/ai-generation-cache";
import type {
  ProviderType,
  AvailableProviderDTO,
  InputSchemaDTO,
  InputParamType,
  InputParamDefinition,
} from "@/lib/api/dto/ai.dto";
import type { CanvasNodeDTO } from "@/lib/api/services/canvas.service";

const FILE_PARAM_TYPES = new Set<InputParamType>([
  "IMAGE", "VIDEO", "AUDIO", "DOCUMENT",
  "IMAGE_LIST", "VIDEO_LIST", "AUDIO_LIST", "DOCUMENT_LIST",
]);

export type CanvasMediaType = "IMAGE" | "VIDEO" | "AUDIO" | "TEXT";

function getSchemaDefaults(schema: InputSchemaDTO | null): Record<string, unknown> {
  if (!schema) return {};
  return schema.params.reduce<Record<string, unknown>>((defaults, param) => {
    const defaultValue = param.defaultValue ?? param.default;
    if (defaultValue !== undefined) {
      defaults[param.name] = defaultValue;
    }
    return defaults;
  }, {});
}

function pickSchemaValues(
  values: Record<string, unknown>,
  schema: InputSchemaDTO | null
): Record<string, unknown> {
  if (!schema) return {};
  const paramMap = new Map(schema.params.map((p) => [p.name, p.type]));
  const result: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(values)) {
    const paramType = paramMap.get(key);
    if (!paramType) continue;
    if (FILE_PARAM_TYPES.has(paramType)) {
      if (Array.isArray(value)) {
        result[key] = value.map((v) =>
          typeof v === "object" && v !== null && "assetId" in v ? v.assetId : v
        );
      } else if (typeof value === "object" && value !== null && "assetId" in value) {
        result[key] = (value as { assetId: string }).assetId;
      } else {
        result[key] = value;
      }
    } else {
      result[key] = value;
    }
  }
  return result;
}

interface UseCanvasGenerateOptions {
  /** Canvas 节点 id（仅 UI 跟踪用） */
  nodeId: string;
  /** 节点关联的 asset id；BE 完成时会把生成结果直接写到这个 asset */
  assetId: string;
  /** 当前 canvas 所属剧本 id；带上让任务关联到剧本 */
  scriptId?: string | null;
  mediaType: CanvasMediaType;
  /** 父节点（图生图/图生视频/图编辑：自动填到对应 file 类型 input 参数） */
  parents?: CanvasNodeDTO[];
  /** submit 成功后回调（taskId 给上游用于跟踪 ws 完成事件） */
  onSubmitted?: (info: { taskId: string; assetId: string }) => void;
}

/**
 * 从父节点 entityDetail 提取 asset 引用（assetId + fileUrl + mediaType）。
 */
function extractAssetRefFromParent(parent: CanvasNodeDTO): {
  assetId: string;
  fileUrl?: string;
  mediaType?: string;
} | null {
  if (parent.entityType !== "ASSET") return null;
  const id = parent.entityId;
  if (!id) return null;
  const detail = (parent.entityDetail ?? {}) as Record<string, unknown>;
  const fileUrl = typeof detail.fileUrl === "string" ? detail.fileUrl : undefined;
  const rawType = (detail.assetType ?? detail.mediaType) as unknown;
  const mediaType = typeof rawType === "string" ? rawType.toUpperCase() : undefined;
  return { assetId: id, fileUrl, mediaType };
}

const SINGLE_FILE_TYPES = new Set<InputParamType>(["IMAGE", "VIDEO", "AUDIO", "DOCUMENT"]);
const LIST_FILE_TYPES = new Set<InputParamType>([
  "IMAGE_LIST", "VIDEO_LIST", "AUDIO_LIST", "DOCUMENT_LIST",
]);

/**
 * 把父节点按 mediaType 自动填到 schema 中匹配类型的 file 参数。
 *
 * 规则：
 *  - 同类型多个父节点 → 优先填到 _LIST 参数；没有 _LIST 才填到单值参数（取第一个）
 *  - 单个父节点 → 优先填到单值参数；没有才填到 _LIST 第一项
 *  - 父节点没有 fileUrl/assetId 跳过
 *  - 已经被用户手动填了的字段不覆盖
 */
function computeParentAutoFill(
  schema: InputSchemaDTO,
  parents: CanvasNodeDTO[] | undefined,
  existing: Record<string, unknown>,
): Record<string, unknown> {
  if (!parents || parents.length === 0 || !schema.params) return {};

  // 按 mediaType 分组父节点
  const byType: Record<string, Array<{ assetId: string; fileUrl?: string }>> = {};
  for (const p of parents) {
    const ref = extractAssetRefFromParent(p);
    if (!ref || !ref.mediaType) continue;
    (byType[ref.mediaType] ??= []).push({ assetId: ref.assetId, fileUrl: ref.fileUrl });
  }

  const result: Record<string, unknown> = {};
  const usedParams = new Set<string>();

  for (const [mediaType, refs] of Object.entries(byType)) {
    // schema 里匹配此 mediaType 的单值 / 列表参数
    const singleParams = schema.params.filter(
      (p: InputParamDefinition) => p.type === mediaType && SINGLE_FILE_TYPES.has(p.type),
    );
    const listParams = schema.params.filter(
      (p: InputParamDefinition) => p.type === `${mediaType}_LIST` && LIST_FILE_TYPES.has(p.type),
    );

    const fillSingle = (paramName: string, ref: { assetId: string; fileUrl?: string }) => {
      if (existing[paramName] !== undefined && existing[paramName] !== "" && existing[paramName] !== null) return false;
      if (usedParams.has(paramName)) return false;
      result[paramName] = ref.fileUrl
        ? { assetId: ref.assetId, fileUrl: ref.fileUrl }
        : { assetId: ref.assetId };
      usedParams.add(paramName);
      return true;
    };
    const fillList = (paramName: string, items: typeof refs) => {
      if (existing[paramName] !== undefined && Array.isArray(existing[paramName]) &&
          (existing[paramName] as unknown[]).length > 0) return false;
      if (usedParams.has(paramName)) return false;
      result[paramName] = items.map((r) =>
        r.fileUrl ? { assetId: r.assetId, fileUrl: r.fileUrl } : { assetId: r.assetId });
      usedParams.add(paramName);
      return true;
    };

    if (refs.length > 1 && listParams.length > 0) {
      // 多个父节点：优先 LIST
      fillList(listParams[0].name, refs);
    } else if (refs.length === 1 && singleParams.length > 0) {
      // 单个父节点：优先 single
      fillSingle(singleParams[0].name, refs[0]);
    } else if (singleParams.length > 0) {
      // 多父节点 + 没 LIST：取第一个填 single
      fillSingle(singleParams[0].name, refs[0]);
    } else if (listParams.length > 0) {
      // 单父节点 + 只有 LIST
      fillList(listParams[0].name, refs);
    }
  }

  return result;
}

export function useCanvasGenerate({
  nodeId,
  assetId,
  scriptId,
  mediaType,
  parents,
  onSubmitted,
}: UseCanvasGenerateOptions) {
  const locale = useLocale();

  const [providers, setProviders] = useState<AvailableProviderDTO[]>([]);
  const [selectedProviderId, setSelectedProviderId] = useState<string | null>(null);
  const [isLoadingProviders, setIsLoadingProviders] = useState(false);

  const [prompt, setPrompt] = useState("");
  const [formValues, setFormValues] = useState<Record<string, unknown>>({});

  const [estimatedCost, setEstimatedCost] = useState<number | null>(null);
  const [isGenerating, setIsGenerating] = useState(false);

  const selectedProvider = providers.find((p) => p.id === selectedProviderId);

  const inputSchema: InputSchemaDTO | null = useMemo(() => {
    if (!selectedProvider) return null;
    return aiService.getInputSchema(selectedProvider);
  }, [selectedProvider]);

  // 父节点 → 对应类型的 file input 参数 自动填充
  // 切 provider / parents 变化时重新计算；只填用户没动过的字段
  useEffect(() => {
    if (!inputSchema) return;
    const autoFills = computeParentAutoFill(inputSchema, parents, formValues);
    if (Object.keys(autoFills).length === 0) return;
    setFormValues((prev) => ({ ...prev, ...autoFills }));
    // formValues 不进 deps：autoFills 内部已经检查了 existing[paramName]，避免无限循环
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [inputSchema, parents]);

  const basicEnumParams = useMemo(() => {
    if (!inputSchema) return [];
    return inputSchema.params.filter(
      (p) => p.name !== "prompt" && p.group === "basic" && p.enum && p.enum.length > 0
    );
  }, [inputSchema]);

  // 高级参数（非 basic、非 prompt、非文件类型）
  const advancedParams = useMemo(() => {
    if (!inputSchema) return [];
    return inputSchema.params.filter(
      (p) =>
        p.name !== "prompt"
        && p.group !== "basic"
        && !FILE_PARAM_TYPES.has(p.type)
    );
  }, [inputSchema]);

  // Load providers when mediaType changes
  useEffect(() => {
    let ignore = false;
    const load = async () => {
      try {
        setIsLoadingProviders(true);
        const data = await aiService.getProvidersByType(mediaType as ProviderType);
        if (ignore) return;
        setProviders(data);
        const cachedId = aiGenerationCache.getLastProvider(mediaType as ProviderType);
        const cached = data.find((p) => p.id === cachedId);
        setSelectedProviderId(cached ? cachedId : data[0]?.id ?? null);
      } catch (error) {
        if (ignore) return;
        console.error("Failed to load providers:", error);
        toast.danger(getErrorFromException(error, locale));
      } finally {
        if (!ignore) setIsLoadingProviders(false);
      }
    };
    void load();
    return () => { ignore = true; };
  }, [mediaType, locale]);

  // Initialize form values when provider changes
  useEffect(() => {
    if (!inputSchema || !selectedProviderId) {
      setFormValues({});
      return;
    }
    const defaults = getSchemaDefaults(inputSchema);
    const cached = aiGenerationCache.getProviderParams(selectedProviderId) ?? {};
    const validNames = new Set(inputSchema.params.map((p) => p.name));
    const merged: Record<string, unknown> = { ...defaults };
    for (const [key, value] of Object.entries(cached)) {
      if (validNames.has(key)) {
        merged[key] = value;
      }
    }
    setFormValues(merged);
  }, [inputSchema, selectedProviderId]);

  // Cache provider + params
  useEffect(() => {
    if (selectedProviderId) {
      aiGenerationCache.setLastProvider(mediaType as ProviderType, selectedProviderId);
    }
  }, [selectedProviderId, mediaType]);

  useEffect(() => {
    if (selectedProviderId && Object.keys(formValues).length > 0) {
      aiGenerationCache.setProviderParams(selectedProviderId, formValues);
    }
  }, [selectedProviderId, formValues]);

  // Estimate cost (debounced)
  useEffect(() => {
    if (!selectedProviderId) {
      setEstimatedCost(null);
      return;
    }
    const timer = setTimeout(async () => {
      try {
        const result = await aiService.estimateCost(selectedProviderId, formValues);
        setEstimatedCost(result.finalCost);
      } catch {
        setEstimatedCost(selectedProvider?.creditCost ?? null);
      }
    }, 300);
    return () => clearTimeout(timer);
  }, [selectedProviderId, formValues, selectedProvider]);

  const submit = useCallback(async () => {
    if (!selectedProviderId || !prompt.trim() || mediaType === "TEXT") {
      // TEXT 模式当前不支持（inspiration submitGeneration 不接受 TEXT）
      if (mediaType === "TEXT") {
        toast.danger("文本生成暂未支持");
      }
      return;
    }

    try {
      setIsGenerating(true);

      // 走通用 /tasks/ai/generate：传 assetId 让 BE 完成后直接更新 canvas 节点关联的 asset
      // （task.entityType=ASSET, entityId=assetId → AssetLifecycleService.handleSuccessfulAssetUpdate
      //  会写 fileUrl/fileKey/thumbnailUrl/mimeType + status=COMPLETED）
      const params = inputSchema ? pickSchemaValues(formValues, inputSchema) : formValues;
      const enrichedParams = prompt.trim() ? { ...params, prompt: prompt.trim() } : params;

      const result = await aiService.submitGenerate({
        providerId: selectedProviderId,
        generationType: mediaType,
        params: enrichedParams,
        assetId,
        ...(scriptId ? { scriptId } : {}),
      });

      onSubmitted?.({ taskId: result.taskId, assetId });
      setPrompt("");
    } catch (error) {
      console.error("Canvas generate submit failed:", error);
      toast.danger(getErrorFromException(error, locale));
    } finally {
      setIsGenerating(false);
    }
  }, [selectedProviderId, prompt, mediaType, inputSchema, formValues, assetId, scriptId, onSubmitted, locale]);

  const canGenerate =
    !!selectedProviderId && prompt.trim().length > 0 && !isGenerating && mediaType !== "TEXT";

  return {
    providers,
    selectedProviderId,
    setSelectedProviderId,
    selectedProvider,
    isLoadingProviders,
    prompt,
    setPrompt,
    formValues,
    setFormValues,
    inputSchema,
    basicEnumParams,
    advancedParams,
    estimatedCost,
    isGenerating,
    canGenerate,
    submit,
  };
}
