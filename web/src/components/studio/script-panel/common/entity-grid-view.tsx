"use client";

/**
 * Entity Grid View Component
 * Renders entities as cards using the shared EntityCard component,
 * preserving drag-and-drop and generating/failed state overlays.
 */

import { useState } from "react";
import { Spinner, Tooltip } from "@heroui/react";
import {
  Film,
  Edit3,
  Copy,
  Trash2,
  Star,
  Download,
  RefreshCw,
  X,
  Image as ImageIcon,
  FileVideo,
  FileAudio,
  File,
  FileText,
  MessageSquare,
} from "lucide-react";
import NextImage from "@/components/ui/content-image";
import { EntityCard, type EntityCardAction } from "@/components/ui/entity-card";
import { UserChip } from "@/components/ui/user-chip";
import { useTranslations } from "next-intl";
import type { EntityItem } from "../types";
import { DeleteConfirmModal } from "./delete-confirm-modal";
import { EmptyState } from "./empty-state";
import { useDragDropActions, createAssetDragData, ASSET_DRAG_TYPE } from "@/lib/stores/drag-drop-store";
import { downloadFile } from "@/lib/utils/download";

function getAssetTypeIcon(assetType?: string) {
  switch (assetType) {
    case "IMAGE":
      return <ImageIcon className="size-3" />;
    case "VIDEO":
      return <FileVideo className="size-3" />;
    case "AUDIO":
      return <FileAudio className="size-3" />;
    default:
      return <File className="size-3" />;
  }
}

function getAssetTypeLabelKey(assetType?: string): string {
  switch (assetType) {
    case "IMAGE":
      return "assetType.image";
    case "VIDEO":
      return "assetType.video";
    case "AUDIO":
      return "assetType.audio";
    case "DOCUMENT":
      return "assetType.document";
    default:
      return "assetType.other";
  }
}

// Render media thumbnail based on asset type
function renderMediaThumbnail(item: EntityItem) {
  const { assetType, coverUrl, fileUrl } = item;

  if (assetType === "VIDEO") {
    const videoSrc = fileUrl || coverUrl;
    if (videoSrc) {
      return (
        <video
          src={videoSrc}
          className="size-full object-cover transition-transform group-hover:scale-105"
          muted
          playsInline
          preload="metadata"
        />
      );
    }
    return (
      <div className="flex size-full items-center justify-center bg-muted/20">
        <FileVideo className="size-10 text-muted/50" />
      </div>
    );
  }

  if (assetType === "AUDIO") {
    return (
      <div className="flex size-full items-center justify-center bg-gradient-to-br from-accent/10 to-accent/5">
        <FileAudio className="size-10 text-accent/50" />
      </div>
    );
  }

  if (assetType === "DOCUMENT") {
    return (
      <div className="flex size-full items-center justify-center bg-gradient-to-br from-muted/20 to-muted/10">
        <FileText className="size-10 text-muted/50" />
      </div>
    );
  }

  if (coverUrl) {
    return (
      <NextImage
        src={coverUrl}
        alt=""
        fill
        className="object-cover transition-transform group-hover:scale-105"
        sizes="(min-width: 768px) 33vw, 50vw"
      />
    );
  }

  return (
    <div className="flex size-full items-center justify-center">
      <Film className="size-10 text-muted/20" />
    </div>
  );
}

interface EntityGridViewProps {
  items: EntityItem[];
  onSelect: (id: string) => void;
  onEdit?: (id: string) => void;
  onCopy?: (id: string) => void;
  onDelete?: (id: string) => void;
  onFavorite?: (item: EntityItem) => void;
  onDownload?: (item: EntityItem) => void;
  draggable?: boolean;
  /** Render cards in cover-only mode (no title, description, footer). */
  mediaOnly?: boolean;
}

export function EntityGridView({
  items,
  onSelect,
  onEdit,
  onCopy,
  onDelete,
  onFavorite,
  onDownload,
  draggable = false,
  mediaOnly = false,
}: EntityGridViewProps) {
  const [deleteConfirm, setDeleteConfirm] = useState<EntityItem | null>(null);
  const t = useTranslations("workspace.studio.common");
  const { startDrag, endDrag } = useDragDropActions();

  const handleConfirmDelete = () => {
    if (deleteConfirm) {
      onDelete?.(deleteConfirm.id);
      setDeleteConfirm(null);
    }
  };

  const handleDragStart = (e: React.DragEvent, item: EntityItem) => {
    if (!draggable || !item.assetType) return;
    const dragData = createAssetDragData({
      assetId: item.id,
      url: item.coverUrl,
      name: item.title,
      mimeType: item.mimeType,
      fileSize: item.fileSize,
      assetType: item.assetType,
    });
    e.dataTransfer.setData(ASSET_DRAG_TYPE, JSON.stringify(dragData));
    e.dataTransfer.effectAllowed = "copy";
    startDrag(dragData);
  };

  if (items.length === 0) {
    return <EmptyState title={t("noData")} />;
  }

  return (
    <>
      <div className="grid auto-rows-fr grid-cols-[repeat(auto-fill,minmax(200px,1fr))] gap-4">
        {items.map((item) => {
          const isGenerating = item.generationStatus === "GENERATING";
          const isFailed = item.generationStatus === "FAILED";
          const isInteractive = !isGenerating && !isFailed;

          // Build actions — only visible when item is in a normal state
          const actions: EntityCardAction[] = [];
          if (isInteractive) {
            if (draggable) {
              if (onFavorite) {
                actions.push({
                  id: "favorite",
                  label: t("favorite"),
                  icon: Star,
                  onAction: () => onFavorite(item),
                });
              }
              if ((item.fileUrl || item.coverUrl) && (onDownload || true)) {
                actions.push({
                  id: "download",
                  label: t("download"),
                  icon: Download,
                  onAction: () => {
                    if (onDownload) {
                      onDownload(item);
                    } else {
                      const url = item.fileUrl || item.coverUrl;
                      if (url) downloadFile(url, item.title || "download");
                    }
                  },
                });
              }
              if (onDelete) {
                actions.push({
                  id: "delete",
                  label: t("delete"),
                  icon: Trash2,
                  variant: "danger",
                  onAction: () => setDeleteConfirm(item),
                });
              }
            } else {
              if (onEdit) {
                actions.push({ id: "edit", label: t("edit"), icon: Edit3, onAction: () => onEdit(item.id) });
              }
              if (onCopy) {
                actions.push({ id: "copy", label: t("copy"), icon: Copy, onAction: () => onCopy(item.id) });
              }
              if (onDelete) {
                actions.push({
                  id: "delete",
                  label: t("delete"),
                  icon: Trash2,
                  variant: "danger",
                  separatorBefore: true,
                  onAction: () => setDeleteConfirm(item),
                });
              }
            }
          }

          // Cover content: thumbnail + overlays for generating/failed states
          const coverSlot = (
            <>
              {renderMediaThumbnail(item)}

              {isGenerating && (
                <div className="absolute inset-0 z-20 flex flex-col items-center justify-center gap-2 bg-black/60">
                  <Spinner size="sm" className="text-white" />
                  <span className="text-xs text-white">{t("generating")}</span>
                  {onDelete && (
                    <Tooltip delay={0}>
                      <Tooltip.Trigger>
                        <div
                          className="flex size-6 cursor-pointer items-center justify-center rounded-md bg-black/40 text-white backdrop-blur-sm transition-colors hover:bg-danger/80"
                          onClick={(e) => {
                            e.stopPropagation();
                            setDeleteConfirm(item);
                          }}
                        >
                          <Trash2 className="size-3.5" />
                        </div>
                      </Tooltip.Trigger>
                      <Tooltip.Content placement="top">{t("delete")}</Tooltip.Content>
                    </Tooltip>
                  )}
                </div>
              )}

              {isFailed && (
                <div className="absolute inset-0 z-20 flex flex-col items-center justify-center gap-2 bg-danger/60">
                  <X className="size-4 text-white" />
                  <span className="text-[10px] text-white">{t("generateFailed")}</span>
                  <div className="flex items-center gap-1.5">
                    <Tooltip delay={0}>
                      <Tooltip.Trigger>
                        <div className="flex h-6 cursor-not-allowed items-center gap-1 rounded-md bg-black/40 px-2 text-white/50 backdrop-blur-sm">
                          <RefreshCw className="size-3" />
                          <span className="text-[10px]">{t("regenerate")}</span>
                        </div>
                      </Tooltip.Trigger>
                      <Tooltip.Content placement="top">{t("unavailable")}</Tooltip.Content>
                    </Tooltip>
                    {onDelete && (
                      <Tooltip delay={0}>
                        <Tooltip.Trigger>
                          <div
                            className="flex size-6 cursor-pointer items-center justify-center rounded-md bg-black/40 text-white backdrop-blur-sm transition-colors hover:bg-danger/80"
                            onClick={(e) => {
                              e.stopPropagation();
                              setDeleteConfirm(item);
                            }}
                          >
                            <Trash2 className="size-3.5" />
                          </div>
                        </Tooltip.Trigger>
                        <Tooltip.Content placement="top">{t("delete")}</Tooltip.Content>
                      </Tooltip>
                    )}
                  </div>
                </div>
              )}
            </>
          );

          // Top-left badge: asset type chip
          const topLeftBadge = item.assetType ? (
            <span className="inline-flex items-center gap-0.5 rounded bg-black/50 px-1.5 py-0.5 text-[10px] font-medium text-white shadow-sm backdrop-blur-sm">
              {getAssetTypeIcon(item.assetType)}
              {t(getAssetTypeLabelKey(item.assetType))}
            </span>
          ) : null;

          // Source-based card tint — AI_GENERATED cards get an accent
          // outline so user-uploaded vs. AI-produced assets are
          // visually distinguishable without a separate badge.
          const sourceClass =
            item.source === "AI_GENERATED"
              ? "!border-accent/50"
              : item.source === "EXTERNAL"
              ? "!border-warning/40"
              : "";

          // Footer
          const hasAuthor = !!(item.createdByNickname || item.createdByUsername);
          const footerLeft = hasAuthor ? (
            <UserChip
              userId={item.createdBy ?? undefined}
              nickname={item.createdByNickname}
              username={item.createdByUsername}
            />
          ) : null;
          const footerRight =
            item.commentCount != null && item.commentCount > 0 ? (
              <span className="flex items-center gap-0.5">
                <MessageSquare className="size-3" />
                {item.commentCount}
              </span>
            ) : null;

          const isGrabbable = draggable && !!item.assetType && isInteractive;

          return (
            <div
              key={item.id}
              draggable={isGrabbable}
              onDragStart={(e) => isGrabbable && handleDragStart(e, item)}
              onDragEnd={endDrag}
              className={
                isGrabbable
                  ? "cursor-grab active:cursor-grabbing"
                  : isGenerating
                  ? "cursor-default"
                  : undefined
              }
            >
              <EntityCard
                title={item.title}
                description={item.description}
                coverSlot={coverSlot}
                fallbackIcon={<Film className="size-12 text-muted/20" />}
                topLeftBadge={topLeftBadge}
                actions={actions}
                footerLeft={footerLeft}
                footerRight={footerRight}
                onClick={() => isInteractive && onSelect(item.id)}
                mediaOnly={mediaOnly}
                className={sourceClass}
              />
            </div>
          );
        })}
      </div>

      <DeleteConfirmModal
        title={deleteConfirm?.title ?? null}
        isOpen={!!deleteConfirm}
        onClose={() => setDeleteConfirm(null)}
        onConfirm={handleConfirmDelete}
      />
    </>
  );
}

export default EntityGridView;
