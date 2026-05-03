"use client";

import { useTranslations } from "next-intl";
import {
  Users,
  MapPin,
  Package,
  FileBox,
  Palette,
  Copy,
  Pencil,
  CheckCircle2,
  XCircle,
} from "lucide-react";
import { EntityCard, EntityCardSkeleton, type EntityCardAction } from "@/components/ui/entity-card";
import { UserChip } from "@/components/ui/user-chip";
import type { EntityType } from "./material-sidebar";
import type { LibraryAssetType, SystemLibraryScope } from "@/lib/api/dto";

export interface MaterialItem {
  id: string;
  name: string;
  description: string | null;
  coverUrl: string | null;
  publishedAt: string | null;
  publishNote: string | null;
  createdAt?: string | null;
  createdBy?: string | null;
  createdByNickname?: string | null;
  createdByUsername?: string | null;
  // Asset-specific fields
  assetType?: LibraryAssetType;
  fileSize?: number | null;
  fileUrl?: string | null;
  mimeType?: string | null;
  // Admin-specific fields
  scope?: SystemLibraryScope;
}

interface MaterialCardProps {
  item: MaterialItem;
  entityType: EntityType;
  onPreview: (item: MaterialItem) => void;
  onCopy?: (item: MaterialItem) => void;
  isCopying?: boolean;
  isSystemAdmin?: boolean;
  onEdit?: (item: MaterialItem) => void;
  onPublish?: (item: MaterialItem) => void;
  onUnpublish?: (item: MaterialItem) => void;
}

const ENTITY_ICONS: Record<EntityType, typeof Users> = {
  characters: Users,
  scenes: MapPin,
  props: Package,
  assets: FileBox,
  styles: Palette,
};

function formatFileSize(bytes: number | null | undefined): string {
  if (!bytes) return "";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

const SCOPE_BADGE_CLASS: Record<string, string> = {
  SYSTEM: "bg-green-500/80",
  WORKSPACE: "bg-sky-400/80",
  SCRIPT: "bg-amber-500/80",
};

export function MaterialCard({
  item,
  entityType,
  onPreview,
  onCopy,
  isCopying,
  isSystemAdmin,
  onEdit,
  onPublish,
  onUnpublish,
}: MaterialCardProps) {
  const t = useTranslations("workspace.materialRoom");

  const Icon = ENTITY_ICONS[entityType];
  const isPublished = item.scope === "SYSTEM";
  const formatDate = (dateStr: string | null) => {
    if (!dateStr) return "";
    // Backend returns "YYYY-MM-DD HH:mm:ss" (not strict ISO) — normalize the
    // space separator to "T" so Safari parses it reliably.
    const normalized = dateStr.includes("T") ? dateStr : dateStr.replace(" ", "T");
    const d = new Date(normalized);
    return Number.isNaN(d.getTime()) ? "" : d.toLocaleDateString();
  };

  // Build the hover actions menu (card is already clickable to preview,
  // so we only surface the secondary actions in the dropdown)
  const actions: EntityCardAction[] = [];
  if (onEdit) {
    actions.push({
      id: "edit",
      label: t("card.edit"),
      icon: Pencil,
      onAction: () => onEdit(item),
    });
  }
  if (!isSystemAdmin && onCopy) {
    actions.push({
      id: "copy",
      label: t("card.copy"),
      icon: Copy,
      onAction: () => onCopy(item),
    });
  }
  if (isSystemAdmin) {
    if (isPublished && onUnpublish) {
      actions.push({
        id: "unpublish",
        label: t("admin.unpublish"),
        icon: XCircle,
        separatorBefore: true,
        onAction: () => onUnpublish(item),
      });
    } else if (!isPublished && onPublish) {
      actions.push({
        id: "publish",
        label: t("admin.publish"),
        icon: CheckCircle2,
        separatorBefore: true,
        onAction: () => onPublish(item),
      });
    }
  }

  // Top-left badge: scope (admin) / asset type (non-admin)
  let topLeftBadge = null;
  if (isSystemAdmin && item.scope) {
    topLeftBadge = (
      <span
        className={`rounded-md px-2 py-1 text-xs font-medium text-white shadow-sm ${SCOPE_BADGE_CLASS[item.scope]}`}
      >
        {t(`scope.${item.scope.toLowerCase()}`)}
      </span>
    );
  } else if (!isSystemAdmin && entityType === "assets" && item.assetType) {
    topLeftBadge = (
      <span className="rounded-md bg-sky-400/80 px-2 py-1 text-xs font-medium text-white shadow-sm">
        {t(`assetType.${item.assetType}`)}
      </span>
    );
  }

  // Top-right badge: publish status indicator (admin only)
  const topRightBadge = isSystemAdmin ? (
    <div className="flex items-center gap-1.5 rounded-md bg-black/50 px-2 py-1 backdrop-blur-sm">
      <span className={`inline-block size-2 rounded-full ${isPublished ? "bg-green-400" : "bg-gray-400"}`} />
      <span className="text-xs text-white">
        {isPublished ? t("admin.published") : t("admin.unpublished")}
      </span>
    </div>
  ) : null;

  // Video cover override (for video assets)
  const isVideoAsset =
    entityType === "assets" &&
    item.assetType === "VIDEO" &&
    item.mimeType?.startsWith("video/") &&
    item.coverUrl === item.fileUrl;

  const coverSlot =
    isVideoAsset && item.coverUrl ? (
      <video
        src={item.coverUrl}
        className="size-full object-cover transition-transform group-hover:scale-105"
        muted
        loop
        playsInline
        preload="metadata"
      />
    ) : undefined;

  // Footer — mirrors project card rhythm:
  //   left  = UserChip (author) OR file size for assets without author
  //   right = createdAt (fallback publishedAt)
  const hasAuthor = !!(item.createdByNickname || item.createdByUsername);
  const footerLeft = (() => {
    if (hasAuthor) {
      return (
        <UserChip
          userId={item.createdBy ?? undefined}
          nickname={item.createdByNickname}
          username={item.createdByUsername}
        />
      );
    }
    if (entityType === "assets" && item.fileSize) {
      return <span>{formatFileSize(item.fileSize)}</span>;
    }
    return null;
  })();
  const dateStr = item.createdAt || item.publishedAt;
  const footerRight = dateStr ? <span>{formatDate(dateStr)}</span> : null;

  return (
    <EntityCard
      title={item.name}
      description={item.description}
      descriptionFallback={t("card.noDescription")}
      coverUrl={item.coverUrl}
      coverSlot={coverSlot}
      fallbackIcon={<Icon className="size-12 text-muted/20" />}
      topLeftBadge={topLeftBadge}
      topRightBadge={topRightBadge}
      actions={actions}
      actionsLabel={t("card.more")}
      isActionPending={isCopying}
      footerLeft={footerLeft}
      footerRight={footerRight}
      onClick={() => onPreview(item)}
    />
  );
}

export { EntityCardSkeleton as SkeletonCard };
