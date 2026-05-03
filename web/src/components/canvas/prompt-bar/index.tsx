"use client";

import { useEffect, useMemo, useState } from "react";
import { Button, Card } from "@heroui/react";
import { ArrowUp, Coins, Loader2, Mic } from "lucide-react";

import {
  MentionPromptEditor,
  type EntityMentionItem,
  type MentionItem,
} from "@/components/ui/mention-prompt-editor";
import { useCanvasGenerate, type CanvasMediaType } from "./use-canvas-generate";
import { useTaskUpdates } from "@/lib/websocket/provider";
import { ModelSelector } from "./model-selector";
import { InlineParams } from "./inline-params";
import { AdvancedParams } from "./advanced-params";
import { ParentSlots } from "./parent-slots";
import type { CanvasNodeDTO } from "@/lib/api/services/canvas.service";

interface PromptBarProps {
  nodeId: string;
  /** 节点关联的 asset id；BE 完成时直接更新这个 asset */
  assetId: string;
  /** 当前 canvas 所属剧本 id */
  scriptId?: string | null;
  mediaType: CanvasMediaType;
  /** 父节点（连入此节点的上游节点）— 显示在顶部 + 提供 @ 提及候选 */
  parents?: CanvasNodeDTO[];
  onGenerationStart?: (taskId: string) => void;
  onGenerationFinish?: () => void;
}

const PROMPT_BAR_WIDTH = 440;

const MEDIA_PLACEHOLDER: Record<CanvasMediaType, string> = {
  IMAGE: "描述任何你想要生成的内容（输入 @ 引用上游节点）",
  VIDEO: "描述任何你想要生成的内容（输入 @ 引用上游节点）",
  AUDIO: "输入文本将其转换为富有表现力的语音",
  TEXT: "描述要生成的文本（暂不支持）",
};

type ParentDetail = { name?: unknown; coverUrl?: unknown; thumbnailUrl?: unknown; fileUrl?: unknown; mediaType?: unknown; assetType?: unknown };

function buildMentionItems(parents: CanvasNodeDTO[] | undefined): {
  items: MentionItem[];
  map: Map<string, MentionItem>;
} {
  const items: EntityMentionItem[] = [];
  if (parents) {
    parents.forEach((p, idx) => {
      const d = (p.entityDetail ?? {}) as ParentDetail;
      const pick = (k: keyof ParentDetail) =>
        typeof d[k] === "string" ? (d[k] as string) : undefined;
      const name = pick("name") ?? `节点${idx + 1}`;
      const thumb = pick("coverUrl") ?? pick("thumbnailUrl") ?? pick("fileUrl");
      const mediaTypeRaw = pick("mediaType") ?? pick("assetType");
      const mt = mediaTypeRaw ? mediaTypeRaw.toUpperCase() : undefined;
      items.push({
        kind: "entity",
        name,
        entityId: p.entityId ?? p.id,
        category: p.entityType ?? "NODE",
        thumbnailUrl: thumb ?? null,
        thumbnailType:
          mt === "VIDEO" ? "video" : mt === "IMAGE" ? "image" : "icon",
        iconFallback: mt === "AUDIO" ? "♫" : undefined,
      });
    });
  }
  return {
    items,
    map: new Map(items.map((i) => [i.name, i])),
  };
}

/**
 * 选中 ASSET 节点时浮在节点下方的 AI 生成栏
 */
export function PromptBar({
  nodeId,
  assetId,
  scriptId,
  mediaType,
  parents,
  onGenerationStart,
  onGenerationFinish,
}: PromptBarProps) {
  const [pendingTaskId, setPendingTaskId] = useState<string | null>(null);

  const {
    providers,
    selectedProviderId,
    setSelectedProviderId,
    selectedProvider,
    isLoadingProviders,
    prompt,
    setPrompt,
    formValues,
    setFormValues,
    basicEnumParams,
    advancedParams,
    estimatedCost,
    isGenerating,
    canGenerate,
    submit,
  } = useCanvasGenerate({
    nodeId,
    assetId,
    scriptId,
    mediaType,
    parents,
    onSubmitted: ({ taskId }) => {
      setPendingTaskId(taskId);
      onGenerationStart?.(taskId);
    },
  });

  useTaskUpdates(
    (taskId, status) => {
      if (taskId !== pendingTaskId) return;

      // BE 已经在 task 完成时把 fileUrl/fileKey 写到 assetId 对应的 asset；
      // 上层 onGenerationFinish 负责重新拉 node 详情让 canvas 刷新画面。
      if (status === "TASK_COMPLETED" || status === "COMPLETED"
          || status === "TASK_FAILED" || status === "FAILED") {
        setPendingTaskId(null);
        onGenerationFinish?.();
      }
    },
    [pendingTaskId]
  );

  useEffect(() => {
    setPrompt("");
  }, [nodeId, setPrompt]);

  const placeholder = MEDIA_PLACEHOLDER[mediaType];
  const submitting = isGenerating || pendingTaskId !== null;

  const { items: mentionItems, map: mentionItemMap } = useMemo(
    () => buildMentionItems(parents),
    [parents]
  );

  return (
    <div
      style={{ width: PROMPT_BAR_WIDTH }}
      className="nopan nodrag nowheel"
      onDoubleClick={(e) => e.stopPropagation()}
    >
      <Card
        variant="default"
        className="flex flex-col gap-2 rounded-2xl bg-default-50 p-3 shadow-xl"
      >
        {/* 顶部：父节点略缩图（仅在有父节点时） */}
        {parents && parents.length > 0 && (
          <ParentSlots parents={parents} />
        )}

        {/* 中部：mention prompt editor */}
        <div className="min-h-[72px] rounded-md">
          <MentionPromptEditor
            value={prompt}
            onChange={setPrompt}
            onSubmit={() => {
              if (canGenerate && !submitting) void submit();
            }}
            canSubmit={canGenerate && !submitting}
            placeholder={placeholder}
            disabled={submitting}
            mentionItems={mentionItems}
            mentionItemMap={mentionItemMap}
            className="min-h-[72px] text-sm"
          />
        </div>

        {/* 底部行：左 模型+参数+高级，右 麦克风+1×+credit */}
        <div className="flex items-center gap-2">
          <div className="flex min-w-0 flex-1 items-center gap-1">
            <ModelSelector
              providers={providers}
              selectedProviderId={selectedProviderId}
              onSelect={setSelectedProviderId}
              isLoading={isLoadingProviders}
            />
            {basicEnumParams.length > 0 && (
              <>
                <span className="text-xs text-muted">·</span>
                <InlineParams
                  params={basicEnumParams}
                  values={formValues}
                  onChange={(name, value) =>
                    setFormValues((prev) => ({ ...prev, [name]: value }))
                  }
                />
              </>
            )}
            {advancedParams.length > 0 && (
              <AdvancedParams
                params={advancedParams}
                values={formValues}
                onChange={(name, value) =>
                  setFormValues((prev) => ({ ...prev, [name]: value }))
                }
                disabled={submitting}
              />
            )}
          </div>

          <div className="flex shrink-0 items-center gap-1">
            <Button
              isIconOnly
              variant="ghost"
              size="sm"
              aria-label="语音输入"
              isDisabled
              className="!size-7 !min-h-0 !min-w-0"
            >
              <Mic className="size-3.5" />
            </Button>
            <span className="text-[11px] text-muted">1×</span>
            <Button
              size="sm"
              variant="primary"
              className="!h-7 !min-h-0 gap-1 rounded-full px-2.5 text-xs"
              isDisabled={!canGenerate || submitting}
              onPress={() => void submit()}
              aria-label="生成"
            >
              {submitting ? (
                <Loader2 className="size-3.5 animate-spin" />
              ) : (
                <>
                  <Coins className="size-3" />
                  <span>{estimatedCost ?? selectedProvider?.creditCost ?? 0}</span>
                  <ArrowUp className="size-3.5" />
                </>
              )}
            </Button>
          </div>
        </div>
      </Card>
    </div>
  );
}

export type { CanvasMediaType };
