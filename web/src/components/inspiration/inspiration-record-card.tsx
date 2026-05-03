"use client";

import { useState, useMemo } from "react";
import { useTranslations } from "next-intl";
import { Button, Chip, Spinner, Tooltip, ProgressBar, Popover } from "@heroui/react";
import { Trash2, RefreshCw, Zap, Bot, Clock, Settings2, Music, Image, Video, Paperclip, ImageIcon, VideoIcon, AudioLines } from "lucide-react";
import type { InspirationRecordDTO, InspirationAssetDTO } from "@/lib/api/dto/inspiration.dto";
import {
  AssetPreviewModal,
  type AssetPreviewInfo,
} from "@/components/common/asset-preview-modal";
import { EntityCard, type EntityCardAction } from "@/components/ui/entity-card";

// ── Shared utilities ──

function formatTime(dateStr: string): string {
  const date = new Date(dateStr);
  const now = new Date();
  const isToday =
    date.getFullYear() === now.getFullYear() &&
    date.getMonth() === now.getMonth() &&
    date.getDate() === now.getDate();

  if (isToday) {
    return date.toLocaleTimeString(undefined, {
      hour: "2-digit",
      minute: "2-digit",
    });
  }
  return date.toLocaleDateString(undefined, {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function toPreviewInfo(asset: InspirationAssetDTO): AssetPreviewInfo {
  return {
    id: asset.id,
    assetType: asset.assetType,
    fileUrl: asset.url,
    thumbnailUrl: asset.thumbnailUrl,
    mimeType: asset.mimeType,
    fileSize: asset.fileSize,
  };
}

// ── Drag data format ──
export const DRAG_MIME = "application/x-inspiration-asset";

function handleDragStart(asset: InspirationAssetDTO, e: React.DragEvent) {
  e.dataTransfer.effectAllowed = "copy";
  e.dataTransfer.setData(
    DRAG_MIME,
    JSON.stringify({
      assetId: asset.id,
      url: asset.url,
      name: asset.mimeType ?? asset.assetType,
      mimeType: asset.mimeType ?? "",
      fileSize: asset.fileSize ?? 0,
      assetType: asset.assetType,
    })
  );
}

// ── Gallery item type ──
export interface GalleryItem {
  asset: InspirationAssetDTO;
  record: InspirationRecordDTO;
}

// ── Helpers ──

/** Keys to skip when displaying record params */
const SKIP_PARAM_KEYS = new Set(["prompt", "negativePrompt", "negative_prompt", "styleId", "image"]);

function getDisplayParams(params: Record<string, unknown> | undefined) {
  if (!params) return [];
  return Object.entries(params)
    .filter(([k, v]) => !SKIP_PARAM_KEYS.has(k) && v != null && v !== "" && !Array.isArray(v))
    .slice(0, 6);
}

function RefAssetMiniIcon({ type }: { type: string }) {
  if (type === "VIDEO") return <VideoIcon className="size-full p-0.5 text-muted" />;
  if (type === "AUDIO") return <Music className="size-full p-0.5 text-muted" />;
  return <Paperclip className="size-full p-0.5 text-muted" />;
}

function GenTypeIcon({ type }: { type: string }) {
  if (type === "IMAGE") return <ImageIcon className="size-3" />;
  if (type === "VIDEO") return <VideoIcon className="size-3" />;
  if (type === "AUDIO") return <AudioLines className="size-3" />;
  return <Bot className="size-3" />;
}

// ══════════════════════════════════════════════════════════════════════════════
// MasonryAssetCard — per-asset card for masonry gallery
// ══════════════════════════════════════════════════════════════════════════════

interface MasonryAssetCardProps {
  item: GalleryItem;
  onRetry?: (recordId: string) => void;
  onDelete?: (recordId: string) => void;
}

// ── Shared building blocks ──

function GenTypeBadge({ type }: { type: string }) {
  return (
    <div className="flex items-center gap-1 rounded-md bg-black/40 px-1.5 py-0.5 text-[10px] text-white backdrop-blur-sm">
      <GenTypeIcon type={type} />
    </div>
  );
}

function ProviderTimeMeta({ record }: { record: InspirationRecordDTO }) {
  return (
    <>
      {record.providerIconUrl ? (
        <img src={record.providerIconUrl} alt="" className="size-3 rounded" />
      ) : (
        <Bot className="size-3" />
      )}
      <span className="max-w-[90px] truncate">{record.providerName}</span>
      <span className="text-border">·</span>
      <Clock className="size-2.5" />
      <span>{formatTime(record.createdAt)}</span>
      {record.creditCost > 0 && (
        <>
          <span className="text-border">·</span>
          <span className="flex items-center gap-0.5 text-warning">
            <Zap className="size-2.5" />
            {record.creditCost}
          </span>
        </>
      )}
    </>
  );
}

function ParamsAndRefAssets({
  record,
  displayParams,
}: {
  record: InspirationRecordDTO;
  displayParams: Array<[string, unknown]>;
}) {
  if (displayParams.length === 0 && (!record.refAssets || record.refAssets.length === 0)) {
    return null;
  }
  return (
    <>
      {displayParams.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {displayParams.map(([k, v]) => (
            <span
              key={k}
              className="inline-flex rounded-md bg-surface-2 px-1.5 py-0.5 text-[10px] text-muted"
            >
              {k.replace(/_/g, " ")}: {String(v)}
            </span>
          ))}
        </div>
      )}
      {record.refAssets?.length > 0 && (
        <div className="mt-1 flex items-center gap-1">
          <Paperclip className="size-2.5 text-muted" />
          {record.refAssets.slice(0, 4).map((ref) => (
            <Tooltip key={ref.id} delay={200}>
              <Tooltip.Trigger>
                <div className="size-5 shrink-0 overflow-hidden border border-border/50">
                  {ref.assetType === "IMAGE" ? (
                    <img
                      src={ref.thumbnailUrl || ref.url}
                      alt=""
                      className="size-full object-cover"
                      loading="lazy"
                    />
                  ) : (
                    <RefAssetMiniIcon type={ref.assetType} />
                  )}
                </div>
              </Tooltip.Trigger>
              <Tooltip.Content className="max-w-none overflow-hidden border-none bg-transparent p-0 shadow-none">
                {ref.assetType === "IMAGE" ? (
                  <img
                    src={ref.url}
                    alt=""
                    className="block max-h-52 max-w-52 object-contain"
                  />
                ) : (
                  <div className="px-3 py-2 text-xs">
                    {ref.mimeType || ref.assetType}
                  </div>
                )}
              </Tooltip.Content>
            </Tooltip>
          ))}
          {record.refAssets.length > 4 && (
            <span className="text-[10px] text-muted">
              +{record.refAssets.length - 4}
            </span>
          )}
        </div>
      )}
    </>
  );
}

export function MasonryAssetCard({ item, onRetry, onDelete }: MasonryAssetCardProps) {
  const t = useTranslations("workspace.inspiration.record");
  const { asset, record } = item;
  const [previewAsset, setPreviewAsset] = useState<AssetPreviewInfo | null>(null);

  const isTerminal = record.status === "COMPLETED" || record.status === "FAILED";
  const displayParams = useMemo(() => getDisplayParams(record.params), [record.params]);

  // Compute aspect ratio from asset dimensions to prevent layout shift
  // (InspirationAssetDTO already exposes width/height on the backend).
  const aspectRatio =
    asset.width && asset.height ? asset.width / asset.height : undefined;

  // For video / audio we use a custom coverSlot; image uses EntityCard's
  // built-in cover (with skeleton + fade-in).
  const coverSlot =
    asset.assetType === "VIDEO" ? (
      <div className="relative size-full">
        <video
          src={asset.url}
          className="block size-full object-cover"
          preload="metadata"
          muted
        />
        <div className="absolute inset-0 flex items-center justify-center">
          <div className="flex size-10 items-center justify-center rounded-full bg-black/50 text-white">
            <svg className="size-5 fill-current" viewBox="0 0 24 24">
              <path d="M8 5v14l11-7z" />
            </svg>
          </div>
        </div>
      </div>
    ) : asset.assetType === "AUDIO" ? (
      <div className="flex size-full items-center gap-3 p-5">
        <Music className="size-8 shrink-0 text-accent" />
        <div className="min-w-0 flex-1">
          <p className="truncate text-xs text-foreground-2">
            {asset.mimeType || "Audio"}
          </p>
          {asset.duration != null && (
            <p className="text-[10px] text-muted">
              {Math.round(asset.duration)}s
            </p>
          )}
        </div>
      </div>
    ) : undefined;

  const actions: EntityCardAction[] = [];
  if (record.status === "FAILED" && onRetry) {
    actions.push({
      id: "retry",
      label: t("retry"),
      icon: RefreshCw,
      onAction: () => onRetry(record.id),
    });
  }
  if (isTerminal && onDelete) {
    actions.push({
      id: "delete",
      label: t("delete"),
      icon: Trash2,
      variant: "danger",
      separatorBefore: actions.length > 0,
      onAction: () => onDelete(record.id),
    });
  }

  return (
    <>
      <EntityCard
        title={record.prompt}
        titleLines={2}
        coverUrl={asset.assetType === "IMAGE" ? asset.thumbnailUrl || asset.url : undefined}
        coverSlot={coverSlot}
        variant="masonry"
        aspectRatio={aspectRatio}
        topLeftBadge={<GenTypeBadge type={record.generationType} />}
        meta={<ProviderTimeMeta record={record} />}
        expandableDetails={
          <ParamsAndRefAssets record={record} displayParams={displayParams} />
        }
        actions={actions.length > 0 ? actions : undefined}
        onClick={() => setPreviewAsset(toPreviewInfo(asset))}
        onDragStart={(e) => handleDragStart(asset, e)}
      />
      <AssetPreviewModal
        isOpen={!!previewAsset}
        onOpenChange={(open) => { if (!open) setPreviewAsset(null); }}
        asset={previewAsset}
      />
    </>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
// MasonryStatusCard — for pending/running/failed records without assets
// ══════════════════════════════════════════════════════════════════════════════

interface MasonryStatusCardProps {
  record: InspirationRecordDTO;
  onRetry?: (recordId: string) => void;
  onDelete?: (recordId: string) => void;
}

export function MasonryStatusCard({ record, onRetry, onDelete }: MasonryStatusCardProps) {
  const t = useTranslations("workspace.inspiration.record");
  const isPending = record.status === "PENDING" || record.status === "RUNNING";
  const displayParams = useMemo(() => getDisplayParams(record.params), [record.params]);

  return (
    <div className="pointer-events-auto group overflow-hidden rounded-3xl border border-white/10 bg-white/60 shadow-sm backdrop-blur-xl transition-shadow hover:shadow-md dark:bg-white/5">
      <div className="p-3">
        {/* Header: generation type badge + prompt */}
        <div className="flex items-start gap-2">
          <div className="mt-0.5 flex shrink-0 items-center gap-1 rounded-md bg-surface-2 px-1.5 py-0.5 text-[10px] text-muted">
            <GenTypeIcon type={record.generationType} />
          </div>
          <p className="line-clamp-2 text-xs leading-relaxed text-foreground">
            {record.prompt}
          </p>
        </div>

        {/* Status area */}
        {isPending && (
          <div className="mt-2.5 flex items-center gap-2">
            <Spinner size="sm" />
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-2 text-[11px] text-muted">
                <span>
                  {record.status === "PENDING" ? t("pending") : t("running")}
                </span>
                {record.progress > 0 && (
                  <span className="text-foreground-2">{Math.round(record.progress)}%</span>
                )}
              </div>
              {record.progress > 0 && (
                <ProgressBar
                  value={record.progress}
                  className="mt-1"
                  size="sm"
                  color="accent"
                />
              )}
            </div>
          </div>
        )}

        {record.status === "FAILED" && (
          <div className="mt-2 space-y-1.5">
            <Chip size="sm" variant="soft" color="danger" className="h-4 text-[10px]">
              {t("failed")}
            </Chip>
            {record.errorMessage && (
              <p className="line-clamp-2 text-[11px] text-danger">
                {record.errorMessage}
              </p>
            )}
          </div>
        )}

        {/* Params tags */}
        {displayParams.length > 0 && (
          <div className="mt-2 flex flex-wrap gap-1">
            {displayParams.map(([k, v]) => (
              <span
                key={k}
                className="inline-flex rounded-md bg-surface-2 px-1.5 py-0.5 text-[10px] text-muted"
              >
                {k.replace(/_/g, " ")}: {String(v)}
              </span>
            ))}
          </div>
        )}

        {/* Ref asset thumbnails — hover to preview large */}
        {record.refAssets?.length > 0 && (
          <div className="mt-1.5 flex items-center gap-1">
            <Paperclip className="size-2.5 text-muted" />
            {record.refAssets.slice(0, 4).map((ref) => (
              <Tooltip key={ref.id} delay={200}>
                <Tooltip.Trigger>
                  <div className="size-5 shrink-0 overflow-hidden rounded border border-border/50 bg-surface-2">
                    {ref.assetType === "IMAGE" ? (
                      <img
                        src={ref.thumbnailUrl || ref.url}
                        alt=""
                        className="size-full object-cover"
                        loading="lazy"
                      />
                    ) : (
                      <RefAssetMiniIcon type={ref.assetType} />
                    )}
                  </div>
                </Tooltip.Trigger>
                <Tooltip.Content className="p-0">
                  {ref.assetType === "IMAGE" ? (
                    <img
                      src={ref.thumbnailUrl || ref.url}
                      alt=""
                      className="max-h-48 max-w-48 rounded-lg object-contain"
                    />
                  ) : (
                    <div className="px-3 py-2 text-xs">
                      {ref.mimeType || ref.assetType}
                    </div>
                  )}
                </Tooltip.Content>
              </Tooltip>
            ))}
            {record.refAssets.length > 4 && (
              <span className="text-[10px] text-muted">+{record.refAssets.length - 4}</span>
            )}
          </div>
        )}

        {/* Bottom row: provider + time + credits + actions */}
        <div className="mt-2 flex items-center gap-1.5 text-[10px] text-muted">
          {record.providerIconUrl ? (
            <img src={record.providerIconUrl} alt="" className="size-3 rounded" />
          ) : (
            <Bot className="size-2.5" />
          )}
          <span className="max-w-[80px] truncate">{record.providerName}</span>
          <span className="text-border">·</span>
          <Clock className="size-2.5" />
          <span>{formatTime(record.createdAt)}</span>
          {record.creditCost > 0 && (
            <>
              <span className="text-border">·</span>
              <span className="flex items-center gap-0.5 text-warning">
                <Zap className="size-2.5" />
                {record.creditCost}
              </span>
            </>
          )}

          <div className="ml-auto flex shrink-0 items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100">
            {record.status === "FAILED" && onRetry && (
              <Button
                isIconOnly
                variant="ghost"
                size="sm"
                className="size-5"
                onPress={() => onRetry(record.id)}
              >
                <RefreshCw className="size-2.5" />
              </Button>
            )}
            {(record.status === "COMPLETED" || record.status === "FAILED") && onDelete && (
              <Button
                isIconOnly
                variant="ghost"
                size="sm"
                className="size-5"
                onPress={() => onDelete(record.id)}
              >
                <Trash2 className="size-2.5" />
              </Button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
// MasonrySkeletonCard — skeleton placeholder for PENDING/RUNNING records
// ══════════════════════════════════════════════════════════════════════════════

interface MasonrySkeletonCardProps {
  record: InspirationRecordDTO;
}

export function MasonrySkeletonCard({ record }: MasonrySkeletonCardProps) {
  return (
    <div className="pointer-events-auto overflow-hidden rounded-3xl border border-white/10 bg-white/60 shadow-sm backdrop-blur-xl dark:bg-white/5">
      {/* Shimmer media placeholder */}
      <div className="relative aspect-[4/3] overflow-hidden bg-surface-2">
        <div className="absolute inset-0 animate-pulse bg-gradient-to-r from-transparent via-white/20 to-transparent dark:via-white/5" />
        <div
          className="absolute inset-0 animate-[shimmer_2s_ease-in-out_infinite] bg-gradient-to-r from-transparent via-white/40 to-transparent dark:via-white/10"
          style={{
            backgroundSize: "200% 100%",
          }}
        />
        {/* Centered gen type icon */}
        <div className="absolute inset-0 flex items-center justify-center">
          <div className="flex items-center gap-2 rounded-full bg-black/20 px-3 py-1.5 backdrop-blur-sm">
            <div className="size-3 animate-spin rounded-full border-2 border-white/60 border-t-transparent" />
            <span className="text-[11px] font-medium text-white/80">
              {record.status === "PENDING" ? "排队中" : "生成中"}
              {record.progress > 0 && ` ${Math.round(record.progress)}%`}
            </span>
          </div>
        </div>
        {/* Top-left type badge */}
        <div className="absolute left-1.5 top-1.5">
          <div className="flex items-center gap-1 rounded-md bg-black/40 px-1.5 py-0.5 text-[10px] text-white backdrop-blur-sm">
            <GenTypeIcon type={record.generationType} />
          </div>
        </div>
        {/* Progress bar at bottom */}
        {record.progress > 0 && (
          <div className="absolute inset-x-0 bottom-0 h-0.5 bg-black/10">
            <div
              className="h-full bg-accent transition-all duration-500"
              style={{ width: `${record.progress}%` }}
            />
          </div>
        )}
      </div>
      {/* Card footer skeleton */}
      <div className="space-y-1.5 p-2.5">
        <p className="line-clamp-2 text-xs leading-relaxed text-foreground/60">
          {record.prompt}
        </p>
        <div className="flex items-center gap-1.5 text-[10px] text-muted/50">
          {record.providerIconUrl ? (
            <img src={record.providerIconUrl} alt="" className="size-3 rounded opacity-50" />
          ) : (
            <Bot className="size-3 opacity-50" />
          )}
          <span className="max-w-[90px] truncate">{record.providerName}</span>
        </div>
      </div>
    </div>
  );
}

// ══════════════════════════════════════════════════════════════════════════════
// Legacy InspirationRecordCard — kept for backward compatibility
// ══════════════════════════════════════════════════════════════════════════════

// ── Ref asset kind icon ──
function RefAssetIcon({ type }: { type: string }) {
  if (type === "IMAGE") return <Image className="size-full p-2 text-muted" />;
  if (type === "VIDEO") return <Video className="size-full p-2 text-muted" />;
  if (type === "AUDIO") return <Music className="size-full p-2 text-muted" />;
  return <Paperclip className="size-full p-2 text-muted" />;
}

// ── Asset thumbnail ──
function AssetThumb({
  asset,
  onClick,
}: {
  asset: InspirationAssetDTO;
  onClick: () => void;
}) {
  if (asset.assetType === "IMAGE") {
    return (
      <button
        type="button"
        draggable
        onDragStart={(e) => handleDragStart(asset, e)}
        className="shrink-0 cursor-pointer overflow-hidden rounded-lg bg-surface-2"
        onClick={onClick}
      >
        <img
          src={asset.thumbnailUrl || asset.url}
          alt=""
          className="block h-52 w-auto rounded-lg object-contain"
          loading="lazy"
        />
      </button>
    );
  }

  if (asset.assetType === "VIDEO") {
    return (
      <button
        type="button"
        draggable
        onDragStart={(e) => handleDragStart(asset, e)}
        className="relative shrink-0 cursor-pointer overflow-hidden rounded-lg bg-surface-2"
        onClick={onClick}
      >
        <video
          src={asset.url}
          className="block h-52 w-auto rounded-lg object-contain"
          preload="metadata"
          muted
        />
        <div className="absolute inset-0 flex items-center justify-center bg-black/10">
          <div className="flex size-8 items-center justify-center rounded-full bg-black/50 text-white">
            <svg className="size-4 fill-current" viewBox="0 0 24 24"><path d="M8 5v14l11-7z" /></svg>
          </div>
        </div>
      </button>
    );
  }

  if (asset.assetType === "AUDIO") {
    return (
      <button
        type="button"
        draggable
        onDragStart={(e) => handleDragStart(asset, e)}
        className="flex shrink-0 cursor-pointer items-center gap-2 rounded-lg bg-surface-2 px-4 py-3 transition-colors hover:bg-surface-2/80"
        onClick={onClick}
      >
        <Music className="size-5 shrink-0 text-accent" />
        <span className="whitespace-nowrap text-xs text-foreground-2">
          {asset.mimeType || "Audio"}
        </span>
      </button>
    );
  }

  return null;
}

// ── Stacked ref assets (collapsed → click to expand) ──
const STACK_ROTATIONS = [-6, 3, -2, 5, -4];

function RefAssetsStack({
  assets,
  onPreview,
}: {
  assets: InspirationAssetDTO[];
  onPreview: (asset: InspirationAssetDTO) => void;
}) {
  if (assets.length === 0) return null;

  return (
    <Popover>
      <Tooltip delay={300}>
        <Popover.Trigger>
          <button type="button" className="relative size-12 shrink-0 cursor-pointer">
            {assets.slice(0, 5).map((asset, idx) => {
              const rot = STACK_ROTATIONS[idx % STACK_ROTATIONS.length];
              const isTop = idx === Math.min(assets.length, 5) - 1;
              return (
                <div
                  key={asset.id}
                  className="absolute inset-0 overflow-hidden rounded-lg border border-border/50 bg-surface-2 shadow-sm transition-transform"
                  style={{
                    transform: `rotate(${rot}deg)`,
                    zIndex: idx,
                    opacity: isTop ? 1 : 0.8,
                  }}
                >
                  {asset.assetType === "IMAGE" ? (
                    <img
                      src={asset.thumbnailUrl || asset.url}
                      alt=""
                      className="size-full object-cover"
                      loading="lazy"
                    />
                  ) : (
                    <RefAssetIcon type={asset.assetType} />
                  )}
                </div>
              );
            })}
            {assets.length > 1 && (
              <div className="absolute -right-1 -top-1 z-10 flex size-4 items-center justify-center rounded-full bg-accent text-[9px] font-bold text-white shadow">
                {assets.length}
              </div>
            )}
          </button>
        </Popover.Trigger>
        <Tooltip.Content>参考素材 ({assets.length})</Tooltip.Content>
      </Tooltip>
      <Popover.Content placement="right" className="w-56 p-0">
        <Popover.Dialog className="p-0">
          <div className="p-2">
            <p className="mb-2 px-1 text-xs font-medium text-foreground">
              参考素材
              <span className="ml-1 text-muted">({assets.length})</span>
            </p>
            <div className="grid grid-cols-3 gap-1.5">
              {assets.map((asset) => (
                <button
                  key={asset.id}
                  type="button"
                  className="group/ref relative aspect-square cursor-pointer overflow-hidden rounded-md border border-border bg-surface-2"
                  onClick={() => onPreview(asset)}
                >
                  {asset.assetType === "IMAGE" ? (
                    <img
                      src={asset.thumbnailUrl || asset.url}
                      alt=""
                      className="size-full object-cover transition-transform group-hover/ref:scale-105"
                      loading="lazy"
                    />
                  ) : (
                    <RefAssetIcon type={asset.assetType} />
                  )}
                </button>
              ))}
            </div>
          </div>
        </Popover.Dialog>
      </Popover.Content>
    </Popover>
  );
}

interface InspirationRecordCardProps {
  record: InspirationRecordDTO;
  onRetry?: (recordId: string) => void;
  onDelete?: (recordId: string) => void;
}

export function InspirationRecordCard({
  record,
  onRetry,
  onDelete,
}: InspirationRecordCardProps) {
  const t = useTranslations("workspace.inspiration.record");
  const isTerminal = record.status === "COMPLETED" || record.status === "FAILED";
  const isPending = record.status === "PENDING" || record.status === "RUNNING";

  const [previewAsset, setPreviewAsset] = useState<AssetPreviewInfo | null>(null);

  // Collect non-prompt param keys for display
  const displayParams = useMemo(() => {
    if (!record.params) return [];
    const skip = new Set(["prompt", "negativePrompt", "negative_prompt", "styleId"]);
    return Object.entries(record.params)
      .filter(([k, v]) => !skip.has(k) && v != null && v !== "")
      .slice(0, 4); // show at most 4
  }, [record.params]);

  return (
    <>
      <div className="group space-y-2 rounded-xl border border-white/10 bg-white/60 p-4 shadow-sm backdrop-blur-xl dark:bg-white/5">
        {/* Prompt — max 2 lines, tooltip shows full */}
        <Tooltip delay={500} isDisabled={record.prompt.length < 100}>
          <Tooltip.Trigger>
            <p className="line-clamp-2 text-sm leading-relaxed text-foreground">
              {record.prompt}
            </p>
          </Tooltip.Trigger>
          <Tooltip.Content className="max-w-md">
            <p className="whitespace-pre-wrap text-xs">{record.prompt}</p>
          </Tooltip.Content>
        </Tooltip>

        {/* Generating state */}
        {isPending && record.assets.length === 0 && (
          <div className="flex items-center gap-3 py-4">
            <Spinner size="sm" />
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-2 text-xs text-muted">
                <span>
                  {record.status === "PENDING" ? t("pending") : t("running")}
                </span>
                {record.progress > 0 && (
                  <span className="text-foreground-2">{Math.round(record.progress)}%</span>
                )}
              </div>
              {record.progress > 0 && (
                <ProgressBar
                  value={record.progress}
                  className="mt-1"
                  size="sm"
                  color="accent"
                />
              )}
            </div>
          </div>
        )}

        {/* Error message */}
        {record.status === "FAILED" && record.errorMessage && (
          <p className="text-xs text-danger">{record.errorMessage}</p>
        )}

        {/* Ref assets + Generated assets row */}
        {(record.refAssets?.length > 0 || record.assets.length > 0) && (
          <div className="-mx-1 flex items-start gap-2 overflow-x-auto px-1 pb-1">
            {/* Stacked ref assets */}
            {record.refAssets?.length > 0 && (
              <RefAssetsStack
                assets={record.refAssets}
                onPreview={(asset) => setPreviewAsset(toPreviewInfo(asset))}
              />
            )}

            {/* Generated assets */}
            {record.assets.map((asset) => (
              <AssetThumb
                key={asset.id}
                asset={asset}
                onClick={() => setPreviewAsset(toPreviewInfo(asset))}
              />
            ))}
          </div>
        )}

        {/* Bottom metadata row */}
        <div className="flex items-center gap-1.5 text-[11px] text-muted">
          {/* Time */}
          <span className="flex items-center gap-0.5">
            <Clock className="size-3" />
            {formatTime(record.createdAt)}
          </span>

          <span className="text-border">·</span>

          {/* Model */}
          {record.providerName && (
            <>
              <span className="flex items-center gap-0.5">
                {record.providerIconUrl ? (
                  <img src={record.providerIconUrl} alt="" className="size-3 rounded" />
                ) : (
                  <Bot className="size-3" />
                )}
                <span className="max-w-[120px] truncate">{record.providerName}</span>
              </span>
              <span className="text-border">·</span>
            </>
          )}

          {/* Params */}
          {displayParams.length > 0 && (
            <>
              <Tooltip delay={300}>
                <Tooltip.Trigger>
                  <span className="flex cursor-default items-center gap-0.5">
                    <Settings2 className="size-3" />
                    <span>{displayParams.map(([k, v]) => `${k}: ${v}`).join(", ")}</span>
                  </span>
                </Tooltip.Trigger>
                <Tooltip.Content className="max-w-xs">
                  <div className="space-y-0.5 text-xs">
                    {Object.entries(record.params)
                      .filter(([k, v]) => k !== "prompt" && k !== "negativePrompt" && k !== "negative_prompt" && v != null)
                      .map(([k, v]) => (
                        <div key={k}>
                          <span className="text-muted">{k}:</span>{" "}
                          <span className="text-foreground">{String(v)}</span>
                        </div>
                      ))}
                  </div>
                </Tooltip.Content>
              </Tooltip>
              <span className="text-border">·</span>
            </>
          )}

          {/* Credits */}
          {record.creditCost > 0 && (
            <span className="flex items-center gap-0.5 text-warning">
              <Zap className="size-3" />
              {record.creditCost}
            </span>
          )}

          {/* Status chip for non-completed */}
          {record.status === "FAILED" && (
            <>
              <span className="text-border">·</span>
              <Chip size="sm" variant="soft" color="danger" className="h-4 text-[10px]">
                {t("failed")}
              </Chip>
            </>
          )}

          {/* Spacer → action buttons */}
          <div className="ml-auto flex shrink-0 items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100">
            {record.status === "FAILED" && onRetry && (
              <Tooltip>
                <Tooltip.Trigger>
                  <Button
                    isIconOnly
                    variant="ghost"
                    size="sm"
                    className="size-6"
                    onPress={() => onRetry(record.id)}
                  >
                    <RefreshCw className="size-3" />
                  </Button>
                </Tooltip.Trigger>
                <Tooltip.Content>{t("retry")}</Tooltip.Content>
              </Tooltip>
            )}
            {isTerminal && onDelete && (
              <Tooltip>
                <Tooltip.Trigger>
                  <Button
                    isIconOnly
                    variant="ghost"
                    size="sm"
                    className="size-6"
                    onPress={() => onDelete(record.id)}
                  >
                    <Trash2 className="size-3" />
                  </Button>
                </Tooltip.Trigger>
                <Tooltip.Content>{t("delete")}</Tooltip.Content>
              </Tooltip>
            )}
          </div>
        </div>
      </div>

      {/* Asset detail preview modal */}
      <AssetPreviewModal
        isOpen={!!previewAsset}
        onOpenChange={(open) => { if (!open) setPreviewAsset(null); }}
        asset={previewAsset}
      />
    </>
  );
}
