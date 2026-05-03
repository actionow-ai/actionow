"use client";

import { useState } from "react";
import { Button, Tooltip, Modal, Dropdown, Spinner } from "@heroui/react";
import {
  Image as ImageIcon,
  Video,
  Music,
  Sparkles,
  Trash2,
  Download,
  Play,
  Eye,
  Loader2,
  XCircle,
  Clock,
  FileText,
  Box,
  Upload,
  MoreVertical,
  FileCheck,
  FileClock,
  GripVertical,
} from "lucide-react";
import { useTranslations } from "next-intl";
import type { AssetListDTO, AssetType, RelationType } from "@/lib/api/dto";
import { useDragDropActions, createAssetDragData, ASSET_DRAG_TYPE } from "@/lib/stores/drag-drop-store";

// Asset with relation info for status toggle
export interface AssetWithRelation extends AssetListDTO {
  relationId?: string;
  relationType?: RelationType;
}

// Asset card props
interface AssetCardProps {
  asset: AssetWithRelation;
  onPreview?: (asset: AssetWithRelation) => void;
  onDelete?: (asset: AssetWithRelation) => void;
  onDownload?: (asset: AssetWithRelation) => void;
  onStatusChange?: (asset: AssetWithRelation, relationType: RelationType) => void;
  size?: "sm" | "md" | "lg";
  showActions?: boolean;
  isDeleting?: boolean;
  draggable?: boolean;
}

// Asset card grid props
interface AssetCardGridProps {
  assets: AssetWithRelation[];
  onPreview?: (asset: AssetWithRelation) => void;
  onDelete?: (asset: AssetWithRelation) => void;
  onDownload?: (asset: AssetWithRelation) => void;
  onStatusChange?: (asset: AssetWithRelation, relationType: RelationType) => void;
  onUpload?: () => void;
  columns?: 2 | 3 | 4 | 5 | 6;
  size?: "sm" | "md" | "lg";
  showActions?: boolean;
  emptyText?: string;
  isDeleting?: boolean;
  draggable?: boolean;
}

// Get asset type info
function getAssetTypeInfo(type: AssetType): { icon: typeof ImageIcon; labelKey: string; color: string } {
  switch (type) {
    case "IMAGE":
      return { icon: ImageIcon, labelKey: "image", color: "bg-blue-500/80" };
    case "VIDEO":
      return { icon: Video, labelKey: "video", color: "bg-purple-500/80" };
    case "AUDIO":
      return { icon: Music, labelKey: "audio", color: "bg-green-500/80" };
    case "DOCUMENT":
      return { icon: FileText, labelKey: "document", color: "bg-orange-500/80" };
    default:
      return { icon: Box, labelKey: "other", color: "bg-gray-500/80" };
  }
}

/**
 * Thumbnail with skeleton + fade-in + onError fallback. Used inside
 * AssetThumbnail so list views don't show flashing broken images.
 */
function ThumbWithSkeleton({ src, alt }: { src: string; alt: string }) {
  const [loaded, setLoaded] = useState(false);
  const [errored, setErrored] = useState(false);

  if (errored) {
    return (
      <div className="flex size-full items-center justify-center bg-muted/20">
        <ImageIcon className="size-6 text-muted/40" />
      </div>
    );
  }

  return (
    <>
      {!loaded && <div className="absolute inset-0 animate-pulse bg-surface-2" />}
      <img
        src={src}
        alt={alt}
        loading="lazy"
        decoding="async"
        onLoad={() => setLoaded(true)}
        onError={() => setErrored(true)}
        className={`size-full object-cover transition-[transform,opacity] duration-200 group-hover:scale-105 ${loaded ? "opacity-100" : "opacity-0"}`}
      />
    </>
  );
}

// Asset thumbnail component
function AssetThumbnail({
  asset,
  size = "md",
  tGenStatus,
}: {
  asset: AssetListDTO;
  size?: "sm" | "md" | "lg";
  tGenStatus: (key: string) => string;
}) {
  const isGenerating = asset.generationStatus === "GENERATING";
  const isFailed = asset.generationStatus === "FAILED";
  const isDraft = asset.generationStatus === "DRAFT";

  const iconSizes = {
    sm: "size-5",
    md: "size-6",
    lg: "size-8",
  };

  return (
    <div className="relative size-full">
      {/* Thumbnail Image with skeleton + fade-in */}
      {asset.thumbnailUrl || asset.fileUrl ? (
        <ThumbWithSkeleton
          src={asset.thumbnailUrl || asset.fileUrl || ""}
          alt={asset.name}
        />
      ) : asset.assetType === "VIDEO" ? (
        <div className="flex size-full items-center justify-center bg-muted/20">
          <Video className={`${iconSizes[size]} text-muted/40`} />
        </div>
      ) : asset.assetType === "AUDIO" ? (
        <div className="flex size-full items-center justify-center bg-muted/20">
          <Music className={`${iconSizes[size]} text-muted/40`} />
        </div>
      ) : (
        <div className="flex size-full items-center justify-center bg-muted/20">
          <ImageIcon className={`${iconSizes[size]} text-muted/40`} />
        </div>
      )}

      {/* Video play icon */}
      {asset.assetType === "VIDEO" && asset.fileUrl && !isGenerating && !isFailed && !isDraft && (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
          <div className="rounded-full bg-black/50 p-2 transition-colors group-hover:bg-black/60">
            <Play className="size-4 text-white" fill="white" />
          </div>
        </div>
      )}

      {/* Generation status overlays */}
      {isGenerating && (
        <div className="absolute inset-0 flex flex-col items-center justify-center bg-black/60">
          <Loader2 className="size-6 animate-spin text-white" />
          <span className="mt-1.5 text-xs text-white/80">{tGenStatus("generating")}</span>
        </div>
      )}
      {isFailed && (
        <div className="absolute inset-0 flex flex-col items-center justify-center bg-black/60">
          <XCircle className="size-6 text-danger" />
          <span className="mt-1.5 text-xs text-white/80">{tGenStatus("failed")}</span>
        </div>
      )}
      {isDraft && (
        <div className="absolute inset-0 flex flex-col items-center justify-center bg-black/60">
          <Clock className="size-6 text-muted" />
          <span className="mt-1.5 text-xs text-white/80">{tGenStatus("draft")}</span>
        </div>
      )}

      {/* Hover gradient overlay */}
      <div className="absolute inset-0 bg-gradient-to-t from-black/60 via-transparent to-transparent opacity-0 transition-opacity group-hover:opacity-100" />
    </div>
  );
}

// Single asset card component
export function AssetCard({
  asset,
  onPreview,
  onDelete,
  onDownload,
  onStatusChange,
  size = "md",
  showActions = true,
  isDeleting,
  draggable = false,
}: AssetCardProps) {
  const { startDrag, endDrag } = useDragDropActions();
  const t = useTranslations("workspace.studio.asset.card");
  const tAssetType = useTranslations("workspace.studio.common.assetType");
  const tGenStatus = useTranslations("workspace.studio.asset.generationStatus");
  const typeInfo = getAssetTypeInfo(asset.assetType);
  const TypeIcon = typeInfo.icon;

  const handlePreview = () => {
    onPreview?.(asset);
  };

  const handleDelete = (e: React.MouseEvent) => {
    e.stopPropagation();
    onDelete?.(asset);
  };

  const handleDownload = () => {
    if (asset.fileUrl) {
      onDownload?.(asset);
    }
  };

  const handleRelationTypeChange = (relationType: RelationType) => {
    onStatusChange?.(asset, relationType);
  };

  const handleDragStart = (e: React.DragEvent) => {
    if (!draggable) return;

    const dragData = createAssetDragData({
      assetId: asset.id,
      url: asset.fileUrl || asset.thumbnailUrl,
      name: asset.name,
      mimeType: asset.mimeType,
      fileSize: asset.fileSize,
      assetType: asset.assetType,
    });

    // Set drag data
    e.dataTransfer.setData(ASSET_DRAG_TYPE, JSON.stringify(dragData));
    e.dataTransfer.effectAllowed = "copy";

    // Set drag image if available
    if (asset.thumbnailUrl || asset.fileUrl) {
      const img = new window.Image();
      img.src = asset.thumbnailUrl || asset.fileUrl || "";
      e.dataTransfer.setDragImage(img, 50, 50);
    }

    // Update store
    startDrag(dragData);
  };

  const handleDragEnd = () => {
    endDrag();
  };

  // Can change status only if asset has a relation ID and relationType is DRAFT or OFFICIAL
  const canChangeStatus = asset.relationId && (asset.relationType === "DRAFT" || asset.relationType === "OFFICIAL");

  return (
    <div className="group relative">
      <div
        className={`relative isolate cursor-pointer overflow-hidden rounded-2xl border border-white/10 bg-white/60 shadow-sm backdrop-blur-xl transition-all duration-200 hover:shadow-md dark:bg-white/5 ${
          draggable ? "cursor-grab active:cursor-grabbing" : ""
        }`}
        onClick={handlePreview}
        draggable={draggable}
        onDragStart={handleDragStart}
        onDragEnd={handleDragEnd}
      >
        {/* Drag handle indicator */}
        {draggable && (
          <div className="absolute left-1 top-1 z-10 flex size-5 items-center justify-center rounded bg-black/50 opacity-0 transition-opacity group-hover:opacity-100">
            <GripVertical className="size-3 text-white" />
          </div>
        )}
        {/* Thumbnail */}
        <div className="aspect-video overflow-hidden">
          <AssetThumbnail asset={asset} size={size} tGenStatus={tGenStatus} />
        </div>

        {/* Bottom info overlay */}
        <div className="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-black/80 to-transparent px-2 py-1.5">
          <div className="flex items-center justify-between gap-1">
            {/* Type badge */}
            <span className={`inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-xs text-white ${typeInfo.color}`}>
              <TypeIcon className="size-3" />
              {tAssetType(typeInfo.labelKey)}
            </span>

            {/* AI generated indicator */}
            {asset.source === "AI_GENERATED" && (
              <div className="flex size-5 items-center justify-center rounded bg-accent/90">
                <Sparkles className="size-3 text-white" />
              </div>
            )}
          </div>
        </div>

        {/* Hover action buttons */}
        {showActions && (
          <div className="absolute right-1.5 top-1.5 flex items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100">
            {/* Preview */}
            <Tooltip delay={0}>
              <Button
                variant="secondary"
                size="sm"
                isIconOnly
                className="size-7 min-w-0 border border-border/50 bg-background/90 shadow-sm backdrop-blur-sm"
                onPress={handlePreview}
              >
                <Eye className="size-3.5" />
              </Button>
              <Tooltip.Content>{t("preview")}</Tooltip.Content>
            </Tooltip>

            {/* Download */}
            {asset.fileUrl && (
              <Tooltip delay={0}>
                <Button
                  variant="secondary"
                  size="sm"
                  isIconOnly
                  className="size-7 min-w-0 border border-border/50 bg-background/90 shadow-sm backdrop-blur-sm"
                  onPress={() => handleDownload()}
                >
                  <Download className="size-3.5" />
                </Button>
                <Tooltip.Content>{t("download")}</Tooltip.Content>
              </Tooltip>
            )}

            {/* More actions dropdown */}
            {(onDelete || (onStatusChange && canChangeStatus)) && (
              <Dropdown>
                <Button
                  variant="secondary"
                  size="sm"
                  isIconOnly
                  className="size-7 min-w-0 border border-border/50 bg-background/90 shadow-sm backdrop-blur-sm"
                >
                  <MoreVertical className="size-3.5" />
                </Button>
                <Dropdown.Popover>
                  <Dropdown.Menu
                    onAction={(key) => {
                      if (key === "delete") {
                        onDelete?.(asset);
                      } else if (key === "draft") {
                        handleRelationTypeChange("DRAFT");
                      } else if (key === "official") {
                        handleRelationTypeChange("OFFICIAL");
                      }
                    }}
                  >
                    {onStatusChange && canChangeStatus && asset.relationType === "OFFICIAL" && (
                      <Dropdown.Item key="draft" textValue={t("setDraft")}>
                        <FileClock className="size-4" />
                        <span>{t("setDraft")}</span>
                      </Dropdown.Item>
                    )}
                    {onStatusChange && canChangeStatus && asset.relationType === "DRAFT" && (
                      <Dropdown.Item key="official" textValue={t("setOfficial")}>
                        <FileCheck className="size-4" />
                        <span>{t("setOfficial")}</span>
                      </Dropdown.Item>
                    )}
                    {onDelete && (
                      <Dropdown.Item key="delete" textValue={t("delete")} className="text-danger">
                        <Trash2 className="size-4" />
                        <span>{t("delete")}</span>
                      </Dropdown.Item>
                    )}
                  </Dropdown.Menu>
                </Dropdown.Popover>
              </Dropdown>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

// Asset card grid component
export function AssetCardGrid({
  assets,
  onPreview,
  onDelete,
  onDownload,
  onStatusChange,
  onUpload,
  columns = 6,
  size = "md",
  showActions = true,
  emptyText,
  isDeleting,
  draggable = false,
}: AssetCardGridProps) {
  const t = useTranslations("workspace.studio.asset.card");
  const [deleteConfirm, setDeleteConfirm] = useState<AssetWithRelation | null>(null);

  const gridCols = {
    2: "grid-cols-2",
    3: "grid-cols-3",
    4: "grid-cols-4",
    5: "grid-cols-5",
    6: "grid-cols-6",
  };

  const handleDeleteClick = (asset: AssetWithRelation) => {
    setDeleteConfirm(asset);
  };

  const handleConfirmDelete = () => {
    if (deleteConfirm) {
      onDelete?.(deleteConfirm);
      setDeleteConfirm(null);
    }
  };

  if (assets.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-8">
        <div className="mb-3 inline-flex rounded-full bg-muted/10 p-3">
          <ImageIcon className="size-6 text-muted/40" />
        </div>
        <p className="text-sm text-muted/60">{emptyText || t("noAssets")}</p>
        {onUpload && (
          <Button variant="ghost" size="sm" className="mt-3 gap-1.5" onPress={onUpload}>
            <Upload className="size-4" />
            {t("uploadAsset")}
          </Button>
        )}
      </div>
    );
  }

  return (
    <>
      <div className={`grid gap-2 ${gridCols[columns]}`}>
        {assets.map((asset) => (
          <AssetCard
            key={asset.id}
            asset={asset}
            onPreview={onPreview}
            onDelete={onDelete ? handleDeleteClick : undefined}
            onDownload={onDownload}
            onStatusChange={onStatusChange}
            size={size}
            showActions={showActions}
            isDeleting={isDeleting}
            draggable={draggable}
          />
        ))}
      </div>

      {/* Delete confirmation modal */}
      <Modal.Backdrop
        isOpen={!!deleteConfirm}
        onOpenChange={(open) => !open && setDeleteConfirm(null)}
      >
        <Modal.Container size="sm">
          <Modal.Dialog>
            <Modal.CloseTrigger />
            <Modal.Header>
              <Modal.Heading>{t("confirmDelete")}</Modal.Heading>
            </Modal.Header>
            <Modal.Body>
              <p className="text-sm text-muted">
                {t("confirmDeleteMessage", { name: deleteConfirm?.name ?? "" })}
              </p>
            </Modal.Body>
            <Modal.Footer>
              <Button variant="secondary" slot="close">
                {t("cancel")}
              </Button>
              <Button variant="danger" onPress={handleConfirmDelete} isPending={isDeleting}>
                {({isPending}) => (<>{isPending ? <Spinner color="current" size="sm" /> : null}{t("delete")}</>)}
              </Button>
            </Modal.Footer>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>
    </>
  );
}

export default AssetCard;
