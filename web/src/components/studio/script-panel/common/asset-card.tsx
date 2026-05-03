"use client";

/**
 * Asset Card Component
 * Reusable card for displaying assets in reference/related assets sections
 * Shows drag handle, delete, favorite/unfavorite, download buttons on hover
 * Clicking opens asset detail modal (Gallery style), edit button opens image editor
 */

import { useState, useMemo } from "react";
import dynamic from "next/dynamic";
import { Spinner, Button, toast } from "@heroui/react";
import {
  Trash2,
  Download,
  X,
  Image as ImageIcon,
  Play,
  Music,
  Edit3,
  BadgeCheck,
  CircleDashed,
  Loader2,
} from "lucide-react";
import { AssetPreviewModal } from "@/components/common/asset-preview-modal";
import type { AssetPreviewInfo, AssetPreviewRelation } from "@/components/common/asset-preview-modal";
import type { EntityAssetRelationDTO } from "@/lib/api/dto";
import { projectService } from "@/lib/api/services";
import { useLocale } from "next-intl";
import { getErrorFromException } from "@/lib/api";
import { UserChip } from "@/components/ui/user-chip";
import { EntityCard, type EntityCardAction } from "@/components/ui/entity-card";

// Dynamic import to avoid SSR issues with canvas dependency
const ImageEditorModal = dynamic(
  () => import("@/components/common/image-editor/image-editor-modal").then(mod => mod.ImageEditorModal),
  { ssr: false }
);

/**
 * Image with skeleton loader and fade-in. Falls back to a neutral icon
 * placeholder on error so we never show a broken image.
 */
function ImageWithSkeleton({ src, alt }: { src: string; alt: string }) {
  const [loaded, setLoaded] = useState(false);
  const [errored, setErrored] = useState(false);

  if (errored) {
    return (
      <div className="flex size-full items-center justify-center bg-muted/20">
        <ImageIcon className="size-6 text-muted/30" />
      </div>
    );
  }

  return (
    <div className="relative size-full">
      {!loaded && <div className="absolute inset-0 animate-pulse bg-surface-2" />}
      <img
        src={src}
        alt={alt}
        loading="lazy"
        decoding="async"
        onLoad={() => setLoaded(true)}
        onError={() => setErrored(true)}
        className={`size-full object-cover transition-opacity duration-200 ${loaded ? "opacity-100" : "opacity-0"}`}
      />
    </div>
  );
}

interface AssetCardProps {
  relation: EntityAssetRelationDTO;
  /** Script ID for loading assets in the editor */
  scriptId?: string;
  /** Workspace ID for API calls */
  workspaceId?: string;
  /** Entity type for context */
  entityType?: "CHARACTER" | "SCENE" | "PROP" | "STORYBOARD";
  /** Entity ID for context */
  entityId?: string;
  /** Reference images to show in the asset browser */
  refImages?: string[];
  /** All assets for finding next official asset when unfavoriting */
  allAssets?: EntityAssetRelationDTO[];
  /** Current entity cover asset ID */
  currentCoverAssetId?: string | null;
  /** Called when drag starts */
  onDragStart?: (e: React.DragEvent, relation: EntityAssetRelationDTO) => void;
  /** Called when drag ends */
  onDragEnd?: (e: React.DragEvent) => void;
  /** Called when delete button is clicked */
  onDelete?: (relationId: string) => void;
  /** Called when download button is clicked */
  onDownload?: (relation: EntityAssetRelationDTO) => void;
  /** Called when image is saved from editor */
  onSave?: (relation: EntityAssetRelationDTO, dataUrl: string) => Promise<void>;
  /** Called after publish status is toggled (to refresh data) */
  onPublishToggled?: () => void;
}

export function AssetCard({
  relation,
  scriptId,
  workspaceId,
  entityType,
  entityId,
  refImages = [],
  allAssets = [],
  currentCoverAssetId,
  onDragStart,
  onDragEnd,
  onDelete,
  onDownload,
  onSave,
  onPublishToggled,
}: AssetCardProps) {
  const locale = useLocale();
  const [isEditorOpen, setIsEditorOpen] = useState(false);
  const [isDetailOpen, setIsDetailOpen] = useState(false);
  const [isUpdating, setIsUpdating] = useState(false);

  // Early return if asset is null
  if (!relation.asset) {
    return null;
  }

  const isGenerating = relation.asset.generationStatus === "GENERATING";
  const isFailed = relation.asset.generationStatus === "FAILED";
  const isOfficial = relation.relationType === "OFFICIAL";
  const isCover = currentCoverAssetId === relation.asset.id;
  const assetType = relation.asset.assetType;

  /**
   * Set entity cover via API
   */
  const setCover = async (assetId: string) => {
    if (!workspaceId || !entityType || !entityId) return;

    try {
      switch (entityType) {
        case "CHARACTER":
          await projectService.setCharacterCover( entityId, assetId);
          break;
        case "SCENE":
          await projectService.setSceneCover( entityId, assetId);
          break;
        case "PROP":
          await projectService.setPropCover( entityId, assetId);
          break;
        case "STORYBOARD":
          await projectService.setStoryboardCover( entityId, assetId);
          break;
      }
    } catch (error) {
      console.error("Failed to set cover:", error);
      toast.danger(getErrorFromException(error, locale));
    }
  };

  /**
   * Handle toggling publish status with auto-cover logic:
   * - When setting to OFFICIAL: auto-set as cover (only for IMAGE type)
   * - When setting to DRAFT: find next official IMAGE asset as cover
   */
  const handleTogglePublish = async () => {
    if (!workspaceId || isUpdating) return;

    const newRelationType = isOfficial ? "DRAFT" : "OFFICIAL";
    const isImageAsset = assetType === "IMAGE";

    setIsUpdating(true);
    try {
      // 1. Update relation type via API
      await projectService.updateEntityAssetRelation( relation.id, {
        relationType: newRelationType,
      });

      // 2. Handle auto-cover logic (only for IMAGE assets)
      if (entityType && entityId && isImageAsset) {
        if (newRelationType === "OFFICIAL") {
          // Setting to OFFICIAL: auto-set as cover
          if (currentCoverAssetId !== relation.asset.id) {
            await setCover(relation.asset.id);
          }
        } else {
          // Setting to DRAFT: find another OFFICIAL IMAGE asset to be cover
          const nextOfficialImageAsset = allAssets.find(
            a => a.asset?.id !== relation.asset.id &&
                 a.relationType === "OFFICIAL" &&
                 a.asset?.assetType === "IMAGE"
          );
          if (nextOfficialImageAsset?.asset) {
            if (currentCoverAssetId !== nextOfficialImageAsset.asset.id) {
              await setCover(nextOfficialImageAsset.asset.id);
            }
          } else {
            // No official IMAGE asset found, use first available IMAGE asset
            const firstImageAsset = allAssets.find(
              a => a.asset?.id !== relation.asset.id && a.asset?.assetType === "IMAGE"
            );
            if (firstImageAsset?.asset && currentCoverAssetId !== firstImageAsset.asset.id) {
              await setCover(firstImageAsset.asset.id);
            }
          }
        }
      }

      // 3. Notify parent to refresh data
      onPublishToggled?.();
    } catch (error) {
      console.error("Failed to toggle publish status:", error);
      toast.danger(getErrorFromException(error, locale));
    } finally {
      setIsUpdating(false);
    }
  };

  const handleDownload = () => {
    if (!relation.asset.fileUrl) return;
    if (onDownload) {
      onDownload(relation);
      return;
    }
    // Default download behavior — pull the file via a temp anchor.
    const link = document.createElement("a");
    link.href = relation.asset.fileUrl;
    link.download = relation.asset.name || "download";
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
  };

  const handleSaveFromEditor = async (dataUrl: string) => {
    await onSave?.(relation, dataUrl);
  };

  // Build data for the shared AssetPreviewModal
  const assetPreviewInfo = useMemo((): AssetPreviewInfo => ({
    id: relation.asset.id,
    name: relation.asset.name,
    description: relation.asset.description,
    assetType: relation.asset.assetType,
    fileUrl: relation.asset.fileUrl,
    thumbnailUrl: relation.asset.thumbnailUrl,
    mimeType: relation.asset.mimeType,
    fileSize: relation.asset.fileSize,
    source: relation.asset.source,
    generationStatus: relation.asset.generationStatus,
    versionNumber: relation.asset.versionNumber,
    scope: relation.asset.scope,
    createdAt: relation.asset.createdAt,
    createdByUsername: relation.asset.createdByUsername,
    createdByNickname: relation.asset.createdByNickname,
    extraInfo: (relation.asset as { extraInfo?: Record<string, unknown> }).extraInfo ?? null,
  }), [relation.asset]);

  const assetRelationInfo = useMemo((): AssetPreviewRelation => ({
    relationType: relation.relationType,
    sequence: relation.sequence,
    extraInfo: relation.extraInfo,
  }), [relation.relationType, relation.sequence, relation.extraInfo]);

  // Render asset content based on type
  const renderAssetContent = () => {
    if (assetType === "IMAGE") {
      return relation.asset.fileUrl ? (
        <ImageWithSkeleton
          src={relation.asset.fileUrl}
          alt={relation.asset.name}
        />
      ) : (
        <div className="flex size-full items-center justify-center bg-muted/20">
          <ImageIcon className="size-6 text-muted/30" />
        </div>
      );
    }

    if (assetType === "VIDEO") {
      return relation.asset.fileUrl ? (
        <>
          <video
            src={relation.asset.fileUrl}
            className="size-full object-cover"
            muted
            preload="metadata"
          />
          <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
            <div className="flex size-8 items-center justify-center rounded-full bg-black/40 backdrop-blur-sm">
              <Play className="size-4 text-white" />
            </div>
          </div>
        </>
      ) : (
        <div className="flex size-full items-center justify-center bg-muted/20">
          <Play className="size-6 text-muted/30" />
        </div>
      );
    }

    if (assetType === "AUDIO") {
      return (
        <div className="flex size-full flex-col items-center justify-center bg-accent/5">
          <Music className="size-6 text-accent" />
        </div>
      );
    }

    // Default fallback
    return (
      <div className="flex size-full items-center justify-center bg-muted/20">
        <ImageIcon className="size-6 text-muted/30" />
      </div>
    );
  };

  // Cover slot — renders the underlying asset (image / video / audio /
  // fallback) plus the bottom-left creator chip overlay.
  const coverSlot = (
    <>
      {renderAssetContent()}
      {!isGenerating && !isFailed && (relation.asset.createdByNickname || relation.asset.createdByUsername) && (
        <div className="absolute bottom-2 left-2 z-10">
          <UserChip
            userId={relation.asset.createdBy ?? undefined}
            nickname={relation.asset.createdByNickname}
            username={relation.asset.createdByUsername}
            size="xs"
            showName
            className="text-white"
          />
        </div>
      )}
    </>
  );

  // Generating / failed full-cover overlay (replaces actions when active).
  const statusOverlay = isGenerating ? (
    <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 bg-black/60">
      <Spinner size="sm" className="text-white" />
      <span className="text-[10px] text-white">生成中...</span>
    </div>
  ) : isFailed ? (
    <div className="absolute inset-0 flex flex-col items-center justify-center gap-1.5 bg-danger/60">
      <X className="size-4 text-white" />
      <span className="text-[10px] text-white">生成失败</span>
    </div>
  ) : undefined;

  // Action toolbar — only shown in normal interactive state. Generating /
  // failed states keep just the delete action so the user can clean up.
  const actions: EntityCardAction[] = [];
  if (!isGenerating && !isFailed) {
    if (assetType === "IMAGE" && relation.asset.fileUrl) {
      actions.push({
        id: "edit",
        label: "编辑",
        icon: Edit3,
        onAction: () => setIsEditorOpen(true),
      });
    }
    actions.push({
      id: "togglePublish",
      label: isUpdating
        ? "更新中..."
        : isOfficial
          ? `设为草稿${isCover ? " (当前封面)" : ""}`
          : "设为正式",
      icon: isUpdating ? Loader2 : isOfficial ? BadgeCheck : CircleDashed,
      onAction: () => {
        if (!isUpdating) handleTogglePublish();
      },
    });
    if (relation.asset.fileUrl) {
      actions.push({
        id: "download",
        label: "下载",
        icon: Download,
        onAction: handleDownload,
      });
    }
  }
  if (onDelete) {
    actions.push({
      id: "delete",
      label: "删除",
      icon: Trash2,
      variant: "danger",
      separatorBefore: actions.length > 0,
      onAction: () => onDelete(relation.id),
    });
  }

  return (
    <>
      <EntityCard
        mediaOnly
        title={relation.asset.name}
        coverSlot={coverSlot}
        statusOverlay={statusOverlay}
        actions={actions.length > 0 ? actions : undefined}
        isActionPending={isUpdating}
        onClick={!isGenerating && !isFailed ? () => setIsDetailOpen(true) : undefined}
        onDragStart={
          onDragStart && !isGenerating
            ? (e) => onDragStart(e, relation)
            : undefined
        }
        onDragEnd={onDragEnd}
        // OFFICIAL gets an accent border tint; replaces the old
        // `ring-2 ring-accent` so it uses EntityCard's border slot.
        className={isOfficial ? "!border-accent/60" : ""}
      />

      {/* Full-screen Image Editor Modal */}
      <ImageEditorModal
        isOpen={isEditorOpen}
        onOpenChange={setIsEditorOpen}
        src={relation.asset.fileUrl || ""}
        refImages={refImages}
        scriptId={scriptId}
        workspaceId={workspaceId}
        entityType={entityType}
        entityId={entityId}
        title={`编辑图片 - ${relation.asset.name}`}
        onSave={onSave ? handleSaveFromEditor : undefined}
        onCancel={() => setIsEditorOpen(false)}
      />

      {/* Asset Detail Modal - Shared Component */}
      <AssetPreviewModal
        isOpen={isDetailOpen}
        onOpenChange={setIsDetailOpen}
        asset={assetPreviewInfo}
        relation={assetRelationInfo}
        actions={
          <>
            {assetType === "IMAGE" && relation.asset.fileUrl && (
              <Button
                size="sm"
                variant="secondary"
                onPress={() => {
                  setIsDetailOpen(false);
                  setIsEditorOpen(true);
                }}
              >
                <Edit3 className="size-4" />
                编辑图片
              </Button>
            )}
            <Button
              size="sm"
              variant={isOfficial ? "primary" : "secondary"}
              onPress={handleTogglePublish}
              isDisabled={isUpdating}
            >
              {isUpdating ? (
                <>
                  <Loader2 className="size-4 animate-spin" />
                  更新中...
                </>
              ) : isOfficial ? (
                <>
                  <CircleDashed className="size-4" />
                  设为草稿
                </>
              ) : (
                <>
                  <BadgeCheck className="size-4" />
                  设为正式
                </>
              )}
            </Button>
          </>
        }
      />
    </>
  );
}

export default AssetCard;
