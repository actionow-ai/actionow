"use client";

import { useState, useEffect, useRef, useCallback, useMemo } from "react";
import { toast } from "@heroui/react";
import { useTranslations, useLocale } from "next-intl";
import { getErrorFromException } from "@/lib/api";
import { aiService } from "@/lib/api/services/ai.service";
import { projectService } from "@/lib/api/services/project.service";
import { libraryService } from "@/lib/api/services/library.service";
import { inspirationService } from "@/lib/api/services/inspiration.service";
import { useInspirationStore } from "@/lib/stores/inspiration-store";
import { aiGenerationCache } from "@/lib/stores/ai-generation-cache";
import type { ProviderType, AvailableProviderDTO, InputSchemaDTO, InputParamType } from "@/lib/api/dto/ai.dto";
import type { StyleListDTO } from "@/lib/api/dto/project.dto";
import type { InspirationRecordDTO } from "@/lib/api/dto/inspiration.dto";

const FILE_PARAM_TYPES = new Set<InputParamType>([
  "IMAGE", "VIDEO", "AUDIO", "DOCUMENT",
  "IMAGE_LIST", "VIDEO_LIST", "AUDIO_LIST", "DOCUMENT_LIST",
]);

type MediaType = Exclude<ProviderType, "TEXT">;

const TEXT_PROMPT_PROVIDER_IDS: Record<MediaType, string> = {
  IMAGE: "00000000-0000-0000-0004-000000000001",
  VIDEO: "00000000-0000-0000-0004-000000000003",
  AUDIO: "00000000-0000-0000-0004-000000000005",
};

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
      // Extract asset IDs from FileValue objects
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

export function useInspirationGenerate(sessionId: string | null) {
  const t = useTranslations("workspace.inspiration");
  const locale = useLocale();
  const appendRecord = useInspirationStore((s) => s.appendRecord);
  const updateSession = useInspirationStore((s) => s.updateSession);

  // Generation type — initialize from cache
  const [generationType, setGenerationType] = useState<MediaType>(() => {
    const cached = aiGenerationCache.getLastGenerationType();
    return cached === "TEXT" ? "IMAGE" : (cached as MediaType);
  });

  // Provider state
  const [providers, setProviders] = useState<AvailableProviderDTO[]>([]);
  const [selectedProviderId, setSelectedProviderId] = useState<string | null>(null);
  const [isLoadingProviders, setIsLoadingProviders] = useState(false);

  // Form state
  const [prompt, setPrompt] = useState("");
  const [formValues, setFormValues] = useState<Record<string, unknown>>({});

  // Styles
  const [styles, setStyles] = useState<StyleListDTO[]>([]);
  const [isLoadingStyles, setIsLoadingStyles] = useState(false);

  // Prompt optimization
  const [textProviders, setTextProviders] = useState<AvailableProviderDTO[]>([]);
  const [isOptimizingPrompt, setIsOptimizingPrompt] = useState(false);
  const [hasGeneratedPrompt, setHasGeneratedPrompt] = useState(false);

  // Cost
  const [estimatedCost, setEstimatedCost] = useState<number | null>(null);

  // Generation
  const [isGenerating, setIsGenerating] = useState(false);

  // Refs
  const promptStreamRef = useRef<{ abort: () => void } | null>(null);

  const selectedProvider = providers.find((p) => p.id === selectedProviderId);

  const inputSchema: InputSchemaDTO | null = useMemo(() => {
    if (!selectedProvider) return null;
    return aiService.getInputSchema(selectedProvider);
  }, [selectedProvider]);

  // Basic params (inline selects with enums in the options bar)
  const basicParams = useMemo(() => {
    if (!inputSchema) return [];
    return inputSchema.params.filter(
      (p) => p.name !== "prompt" && p.group === "basic" && p.enum && p.enum.length > 0
    );
  }, [inputSchema]);

  // Non-enum basic params (number, text, boolean inlined)
  const basicNonEnumParams = useMemo(() => {
    if (!inputSchema) return [];
    return inputSchema.params.filter(
      (p) => p.name !== "prompt" && p.group === "basic" && !(p.enum && p.enum.length > 0)
    );
  }, [inputSchema]);

  // Advanced params flattened (everything except prompt, basic group, and file-type params)
  const advancedParams = useMemo(() => {
    if (!inputSchema) return [];
    return inputSchema.params.filter(
      (p) => p.name !== "prompt" && p.group !== "basic" && !FILE_PARAM_TYPES.has(p.type)
    );
  }, [inputSchema]);

  const promptProviderId = useMemo(
    () => TEXT_PROMPT_PROVIDER_IDS[generationType],
    [generationType]
  );

  const promptProvider = useMemo(
    () => textProviders.find((p) => p.id === promptProviderId) ?? null,
    [promptProviderId, textProviders]
  );

  // Load providers when generation type changes
  useEffect(() => {
    let ignore = false;
    const load = async () => {
      try {
        setIsLoadingProviders(true);
        const data = await aiService.getProvidersByType(generationType);
        if (ignore) return;
        setProviders(data);

        const cachedId = aiGenerationCache.getLastProvider(generationType);
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
  }, [generationType]);

  // Load TEXT providers for prompt optimization
  useEffect(() => {
    let ignore = false;
    aiService.getProvidersByType("TEXT").then((data) => {
      if (!ignore) setTextProviders(data);
    }).catch(() => {
      if (!ignore) setTextProviders([]);
    });
    return () => { ignore = true; };
  }, []);

  // Load styles (library published + workspace level)
  useEffect(() => {
    let ignore = false;
    const load = async () => {
      try {
        setIsLoadingStyles(true);
        // Fetch workspace-created styles and library published styles in parallel
        const [projectData, libraryPage] = await Promise.all([
          projectService.getStyles().catch(() => [] as StyleListDTO[]),
          libraryService.queryStyles({ pageSize: 100, orderBy: "publishedAt", orderDir: "desc" }).catch(() => null),
        ]);
        if (ignore) return;
        // Merge: library styles mapped to StyleListDTO shape, then workspace styles (deduped)
        const libraryStyles: StyleListDTO[] = (libraryPage?.records ?? []).map((s) => ({
          id: s.id,
          name: s.name,
          description: s.description,
          coverUrl: s.coverUrl,
        }) as unknown as StyleListDTO);
        const seen = new Set(libraryStyles.map((s) => s.id));
        const merged = [...libraryStyles];
        for (const s of projectData) {
          if (!seen.has(s.id)) merged.push(s);
        }
        setStyles(merged);
      } catch {
        if (!ignore) setStyles([]);
      } finally {
        if (!ignore) setIsLoadingStyles(false);
      }
    };
    void load();
    return () => { ignore = true; };
  }, []);

  // Estimate cost
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

  // Cache provider selection
  useEffect(() => {
    if (selectedProviderId) {
      aiGenerationCache.setLastProvider(generationType, selectedProviderId);
    }
  }, [selectedProviderId, generationType]);

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

  // Cache form values
  useEffect(() => {
    if (selectedProviderId && Object.keys(formValues).length > 0) {
      aiGenerationCache.setProviderParams(selectedProviderId, formValues);
    }
  }, [selectedProviderId, formValues]);

  // Handle generation type change
  const handleGenerationTypeChange = useCallback(
    (type: MediaType) => {
      setGenerationType(type);
      setHasGeneratedPrompt(false);
      aiGenerationCache.setGenerationType(type);
    },
    []
  );

  // Optimize prompt via TEXT provider streaming.
  // Mirrors ai-generation behavior: keep the mask visible for the whole
  // stream, only swap the prompt field once the final text is ready.
  const optimizePrompt = useCallback(() => {
    if (!promptProvider || !prompt.trim()) return;

    try {
      setIsOptimizingPrompt(true);
      let accumulated = "";

      const controller = aiService.executeStream(
        {
          providerId: promptProvider.id,
          generationType: "TEXT" as ProviderType,
          params: {
            customInput: prompt,
            i18n: "zh",
          },
          responseMode: "STREAMING",
        },
        {
          onStarted: () => {},
          onProgress: () => {},
          onTextChunk: (delta) => {
            accumulated += delta;
          },
          onFinished: (outputs) => {
            const finalText =
              (outputs && typeof outputs.textAccumulated === "string" && outputs.textAccumulated) ||
              accumulated;
            if (finalText) {
              setPrompt(finalText.trim());
              setHasGeneratedPrompt(true);
            }
            setIsOptimizingPrompt(false);
            promptStreamRef.current = null;
          },
          onError: (_code, message) => {
            console.error("Prompt optimization failed:", message);
            toast.danger(t("input.optimize") + " failed");
            setIsOptimizingPrompt(false);
            promptStreamRef.current = null;
          },
        }
      );

      promptStreamRef.current = controller;
    } catch (error) {
      console.error("Prompt optimization failed:", error);
      toast.danger(getErrorFromException(error, locale));
      setIsOptimizingPrompt(false);
      promptStreamRef.current = null;
    }
  }, [promptProvider, prompt, t, locale]);

  // Submit generation
  const submitGeneration = useCallback(async () => {
    if (!sessionId || !selectedProviderId || !prompt.trim()) return;

    try {
      setIsGenerating(true);

      const result = await inspirationService.submitGeneration({
        sessionId,
        generationType,
        prompt: prompt.trim(),
        providerId: selectedProviderId,
        params: inputSchema ? pickSchemaValues(formValues, inputSchema) : formValues,
      });

      if (result.success) {
        // Create an optimistic record in the store
        const record: InspirationRecordDTO = {
          id: result.recordId,
          sessionId,
          prompt: prompt.trim(),
          negativePrompt: null,
          generationType,
          providerId: selectedProviderId,
          providerName: selectedProvider?.name ?? null,
          providerIconUrl: selectedProvider?.iconUrl ?? null,
          params: formValues,
          status: "RUNNING",
          assets: [],
          refAssets: [],
          taskId: result.taskId,
          creditCost: result.creditCost,
          progress: 0,
          errorMessage: null,
          createdAt: new Date().toISOString(),
          completedAt: null,
        };
        appendRecord(record);

        // Update session metadata
        if (sessionId) {
          updateSession(sessionId, {
            lastActiveAt: new Date().toISOString(),
          });
        }

        // Clear prompt after successful submission
        setPrompt("");
        setHasGeneratedPrompt(false);
      } else {
        toast.danger(result.errorMessage || "Generation failed");
      }
    } catch (error) {
      console.error("Generation submission failed:", error);
      toast.danger("Generation failed");
    } finally {
      setIsGenerating(false);
    }
  }, [
    sessionId,
    selectedProviderId,
    prompt,
    generationType,
    formValues,
    inputSchema,
    selectedProvider,
    appendRecord,
    updateSession,
  ]);

  const canGenerate =
    !!sessionId && !!selectedProviderId && prompt.trim().length > 0 && !isGenerating;

  return {
    // Generation type
    generationType,
    setGenerationType: handleGenerationTypeChange,
    // Providers
    providers,
    selectedProviderId,
    setSelectedProviderId,
    selectedProvider,
    isLoadingProviders,
    // Form
    prompt,
    setPrompt,
    formValues,
    setFormValues,
    inputSchema,
    basicParams,
    basicNonEnumParams,
    advancedParams,
    // Styles
    styles,
    isLoadingStyles,
    // Prompt optimization
    isOptimizingPrompt,
    hasGeneratedPrompt,
    optimizePrompt,
    promptProvider,
    // Cost
    estimatedCost,
    // Generation
    isGenerating,
    submitGeneration,
    canGenerate,
  };
}
