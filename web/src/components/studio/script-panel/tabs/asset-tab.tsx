"use client";

/**
 * Asset Tab Component
 * Grid-only view for managing assets with category filtering and trash
 */

import { useState, useEffect, useCallback } from "react";
import {
  Button,
  ButtonGroup,
  ScrollShadow,
  Modal,
  Input,
  Tooltip,
  Spinner,
  Separator,
  SearchField,
  Surface,
  toast,
} from "@heroui/react";
import {
  Image as ImageIcon,
  LayoutGrid,
  X,
  RefreshCw,
  Loader2,
  FileVideo,
  FileAudio,
  File,
  RotateCcw,
  AlertTriangle,
  Clock,
  Archive,
  List,
  Trash2,
} from "lucide-react";
import { useTranslations, useLocale} from "next-intl";
import { projectService, getErrorFromException} from "@/lib/api";
import type { AssetListDTO, TrashAssetDTO, EntityAssetRelationDTO } from "@/lib/api/dto";
import { useWorkspace } from "@/components/providers/workspace-provider";
import { useDebouncedEntityChanges } from "@/lib/websocket";
import type { EntityItem } from "../types";
import {
  transformToEntityItem,
} from "../utils";
import { EntityGridView, AssetTabSkeleton, EntityTabSkeleton, SortButtons, sortItems, EmptyState, FormSelect, type SortKey } from "../common";
import { getProxiedImageUrl } from "@/lib/utils/image-proxy";
import { AssetPreviewModal } from "@/components/common/asset-preview-modal";
import type { AssetPreviewInfo } from "@/components/common/asset-preview-modal";

interface AssetTabProps {
  scriptId: string;
}

// Asset view mode: normal assets or trash
type AssetViewTab = "assets" | "trash";

// Category filter for entity association
type CategoryFilter = "ALL" | "STORYBOARD" | "CHARACTER" | "SCENE" | "PROP" | "UNATTACHED";

// Asset type icons mapping
const assetTypeIcons: Record<string, typeof ImageIcon> = {
  IMAGE: ImageIcon,
  VIDEO: FileVideo,
  AUDIO: FileAudio,
};

// Get asset type icon
function getAssetTypeIcon(assetType: string) {
  const IconComponent = assetTypeIcons[assetType] || File;
  return <IconComponent className="size-4" />;
}

// Format file size
function formatFileSize(bytes: number | null): string {
  if (!bytes) return "-";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`;
}

// Format relative time for trash (i18n)
function formatRelativeTime(dateStr: string, tRelTime: (key: string, values?: Record<string, string | number>) => string): string {
  const date = new Date(dateStr);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24));

  if (diffDays === 0) return tRelTime("today");
  if (diffDays === 1) return tRelTime("yesterday");
  if (diffDays < 7) return tRelTime("daysAgo", { days: diffDays });
  if (diffDays < 30) return tRelTime("weeksAgo", { weeks: Math.floor(diffDays / 7) });
  return tRelTime("monthsAgo", { months: Math.floor(diffDays / 30) });
}

// Calculate days until permanent deletion (30 days retention)
function getDaysUntilDeletion(deletedAt: string): number {
  const deleteDate = new Date(deletedAt);
  const expirationDate = new Date(deleteDate.getTime() + 30 * 24 * 60 * 60 * 1000);
  const now = new Date();
  const diffMs = expirationDate.getTime() - now.getTime();
  return Math.max(0, Math.ceil(diffMs / (1000 * 60 * 60 * 24)));
}

// Get trash image URL (replace fileKey with trashPath in fileUrl)
function getTrashImageUrl(asset: TrashAssetDTO): string | null {
  if (!asset.fileUrl || !asset.fileKey || !asset.trashPath) {
    return asset.thumbnailUrl || asset.fileUrl;
  }
  return asset.fileUrl.replace(asset.fileKey, asset.trashPath);
}

// Asset Tab Component
export function AssetTab({ scriptId }: AssetTabProps) {
  const locale = useLocale();
  const { currentWorkspaceId } = useWorkspace();
  const tTab = useTranslations("workspace.studio.asset.tab");
  const tTrash = useTranslations("workspace.studio.asset.trash");
  const tRelTime = useTranslations("workspace.studio.asset.relativeTime");
  const tAssetType = useTranslations("workspace.studio.common.assetType");
  const tCommon = useTranslations("common");
  const [assetViewTab, setAssetViewTab] = useState<AssetViewTab>("assets");
  const [isLoading, setIsLoading] = useState(true);
  const [items, setItems] = useState<EntityItem[]>([]);
  const [rawAssets, setRawAssets] = useState<AssetListDTO[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [searchKeyword, setSearchKeyword] = useState("");
  const [sortKeys, setSortKeys] = useState<SortKey[]>(["updatedAt_desc"]);
  const [typeFilter, setTypeFilter] = useState<"ALL" | "IMAGE" | "VIDEO" | "AUDIO">("ALL");

  // Category filter (replaces old sourceFilter)
  const [categoryFilter, setCategoryFilter] = useState<CategoryFilter>("ALL");
  const [unattachedAssetIds, setUnattachedAssetIds] = useState<Set<string>>(new Set());
  const [assetEntityMap, setAssetEntityMap] = useState<Map<string, Set<string>>>(new Map());
  const [isCategoryLoading, setIsCategoryLoading] = useState(false);

  // Asset preview modal
  const [previewAsset, setPreviewAsset] = useState<AssetPreviewInfo | null>(null);
  const [isPreviewOpen, setIsPreviewOpen] = useState(false);

  // Trash state
  const [trashAssets, setTrashAssets] = useState<TrashAssetDTO[]>([]);
  const [trashViewMode, setTrashViewMode] = useState<"grid" | "list">("grid");
  const [isTrashLoading, setIsTrashLoading] = useState(false);
  const [trashTotalItems, setTrashTotalItems] = useState(0);
  const [selectedTrashIds, setSelectedTrashIds] = useState<Set<string>>(new Set());
  const [isProcessing, setIsProcessing] = useState(false);
  const [showEmptyConfirm, setShowEmptyConfirm] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [deleteTargetId, setDeleteTargetId] = useState<string | null>(null);

  // Fetch assets
  const fetchAssets = useCallback(async () => {
    if (!currentWorkspaceId || !scriptId) return;

    try {
      setIsLoading(true);
      setError(null);

      const assets = await projectService.getAssetsByScript( scriptId);
      setRawAssets(assets);
      setItems(assets.map((item) => transformToEntityItem(item, "assets")));
    } catch (err) {
      console.error("Failed to fetch assets:", err);
      toast.danger(getErrorFromException(err, locale));
      setError(tTab("loadFailed"));
    } finally {
      setIsLoading(false);
    }
  }, [currentWorkspaceId, scriptId]);

  useEffect(() => {
    fetchAssets();
  }, [fetchAssets]);

  // Auto-refresh when assets are changed by other collaborators via WebSocket
  useDebouncedEntityChanges("asset", fetchAssets, undefined, [fetchAssets]);

  // Build asset-entity mapping for category filtering
  const buildAssetEntityMap = useCallback(async () => {
    if (!currentWorkspaceId || !scriptId) return;
    try {
      setIsCategoryLoading(true);
      // Fetch all entity lists + unattached in parallel
      const [characters, scenes, props, storyboards, unattachedResult] = await Promise.all([
        projectService.getCharactersByScript(scriptId),
        projectService.getScenesByScript(scriptId),
        projectService.getPropsByScript(scriptId),
        projectService.getStoryboardsByScript(scriptId),
        projectService.getUnattachedAssets(scriptId, { size: 200 }),
      ]);

      // Fetch asset relations for each entity in parallel
      const entityGroups: { type: string; ids: string[] }[] = [
        { type: "CHARACTER", ids: characters.map((c) => c.id) },
        { type: "SCENE", ids: scenes.map((s) => s.id) },
        { type: "PROP", ids: props.map((p) => p.id) },
        { type: "STORYBOARD", ids: storyboards.map((sb) => sb.id) },
      ];

      const relationPromises: Promise<{ entityType: string; relations: EntityAssetRelationDTO[] }>[] = [];
      for (const group of entityGroups) {
        for (const entityId of group.ids) {
          relationPromises.push(
            projectService.getEntityAssetRelations(group.type, entityId)
              .then((relations) => ({ entityType: group.type, relations }))
              .catch(() => ({ entityType: group.type, relations: [] as EntityAssetRelationDTO[] }))
          );
        }
      }

      const results = await Promise.all(relationPromises);
      const map = new Map<string, Set<string>>();
      for (const { entityType, relations } of results) {
        for (const rel of relations) {
          const assetId = rel.asset?.id;
          if (!assetId) continue;

          if (!map.has(assetId)) {
            map.set(assetId, new Set());
          }
          map.get(assetId)!.add(entityType);
        }
      }
      setAssetEntityMap(map);

      // Store unattached asset IDs
      const unattachedIds = new Set((unattachedResult.records || []).map((a) => a.id));
      setUnattachedAssetIds(unattachedIds);
    } catch (err) {
      console.error("Failed to build asset entity map:", err);
      toast.danger(getErrorFromException(err, locale));
    } finally {
      setIsCategoryLoading(false);
    }
  }, [currentWorkspaceId, scriptId]);

  // Build mapping on mount and when assets change
  useEffect(() => {
    buildAssetEntityMap();
  }, [buildAssetEntityMap, rawAssets.length]);

  // Auto-load trash count on mount
  useEffect(() => {
    if (!currentWorkspaceId) return;
    projectService.getTrashAssets({ pageNum: 1, pageSize: 1 })
      .then((response) => setTrashTotalItems(response.total || 0))
      .catch(() => {});
  }, [currentWorkspaceId]);

  // Auto-refresh when there are generating assets
  useEffect(() => {
    const hasGeneratingAssets = rawAssets.some((a) => a.generationStatus === "GENERATING");
    if (!hasGeneratingAssets || !currentWorkspaceId || !scriptId) return;

    const refreshInterval = setInterval(async () => {
      try {
        const assets = await projectService.getAssetsByScript( scriptId);
        setRawAssets(assets);
        setItems(assets.map((item) => transformToEntityItem(item, "assets")));
      } catch (err) {
        console.error("Failed to refresh assets:", err);
        toast.danger(getErrorFromException(err, locale));
      }
    }, 3000);

    return () => clearInterval(refreshInterval);
  }, [rawAssets, currentWorkspaceId, scriptId]);

  // Open asset preview modal
  const handleSelect = (id: string) => {
    const raw = rawAssets.find((a) => a.id === id);
    if (raw) {
      setPreviewAsset({
        id: raw.id,
        name: raw.name,
        description: raw.description,
        assetType: raw.assetType,
        fileUrl: raw.fileUrl,
        thumbnailUrl: raw.thumbnailUrl,
        mimeType: raw.mimeType,
        fileSize: raw.fileSize,
        source: raw.source,
        generationStatus: raw.generationStatus,
        versionNumber: raw.versionNumber,
        createdAt: raw.createdAt,
        createdByUsername: raw.createdByUsername,
        createdByNickname: raw.createdByNickname,
      });
      setIsPreviewOpen(true);
    }
  };

  // Filter items by type, category, and search
  const filteredItems = items.filter((item) => {
    // Type filter
    if (typeFilter !== "ALL") {
      const rawAsset = rawAssets.find((a) => a.id === item.id);
      if (rawAsset && rawAsset.assetType !== typeFilter) {
        return false;
      }
    }

    // Category filter
    if (categoryFilter === "UNATTACHED") {
      if (!unattachedAssetIds.has(item.id)) return false;
    } else if (categoryFilter !== "ALL") {
      const entityTypes = assetEntityMap.get(item.id);
      if (!entityTypes || !entityTypes.has(categoryFilter)) return false;
    }

    // Search keyword filter
    if (!searchKeyword.trim()) return true;
    const keyword = searchKeyword.toLowerCase();
    return (
      item.title.toLowerCase().includes(keyword) ||
      item.description?.toLowerCase().includes(keyword)
    );
  });

  const displayItems = sortItems(filteredItems, sortKeys);

  // Delete asset (moves to trash)
  const handleDelete = async (id: string) => {
    if (!currentWorkspaceId) return;

    try {
      await projectService.deleteAsset( id);
      await fetchAssets();
    } catch (err) {
      console.error("Failed to delete asset:", err);
      toast.danger(tTrash("deleteFailed"));
    }
  };

  // Clear all failed assets
  const failedAssets = rawAssets.filter((a) => a.generationStatus === "FAILED");

  const handleClearFailed = async () => {
    if (!currentWorkspaceId || failedAssets.length === 0) return;
    try {
      setIsProcessing(true);
      await Promise.all(
        failedAssets.map((a) => projectService.deleteAsset( a.id))
      );
      await fetchAssets();
    } catch (err) {
      console.error("Failed to clear failed assets:", err);
      toast.danger(tTrash("deleteFailed"));
    } finally {
      setIsProcessing(false);
    }
  };

  // ==================== Trash Functions ====================

  // Fetch trash assets
  const fetchTrashAssets = useCallback(async () => {
    if (!currentWorkspaceId) return;

    setIsTrashLoading(true);
    try {
      const params: Record<string, string | number> = { pageNum: 1, pageSize: 100 };
      if (typeFilter !== "ALL") {
        params.assetType = typeFilter;
      }
      const response = await projectService.getTrashAssets( params);
      setTrashAssets(response.records || []);
      setTrashTotalItems(response.total || 0);
    } catch (error) {
      console.error("Failed to load trash assets:", error);
      toast.danger(getErrorFromException(error, locale));
      setTrashAssets([]);
    } finally {
      setIsTrashLoading(false);
    }
  }, [currentWorkspaceId, typeFilter]);

  // Load trash when switching to trash view
  useEffect(() => {
    if (assetViewTab === "trash") {
      fetchTrashAssets();
    }
  }, [assetViewTab, fetchTrashAssets]);

  // Restore asset from trash
  const handleRestore = useCallback(async (assetId: string) => {
    if (!currentWorkspaceId) return;

    setIsProcessing(true);
    try {
      await projectService.restoreAsset( assetId);
      await fetchTrashAssets();
      await fetchAssets();
      setSelectedTrashIds((prev) => {
        const next = new Set(prev);
        next.delete(assetId);
        return next;
      });
      toast.success(tTrash("restoreSuccess"));
    } catch (error) {
      console.error("Failed to restore asset:", error);
      toast.danger(tTrash("restoreFailed"));
    } finally {
      setIsProcessing(false);
    }
  }, [currentWorkspaceId, fetchTrashAssets, fetchAssets, tTrash]);

  // Restore selected assets
  const handleRestoreSelected = useCallback(async () => {
    if (!currentWorkspaceId || selectedTrashIds.size === 0) return;

    setIsProcessing(true);
    try {
      for (const id of selectedTrashIds) {
        await projectService.restoreAsset( id);
      }
      await fetchTrashAssets();
      await fetchAssets();
      setSelectedTrashIds(new Set());
      toast.success(tTrash("restoreSuccess"));
    } catch (error) {
      console.error("Failed to restore assets:", error);
      toast.danger(tTrash("restoreFailed"));
    } finally {
      setIsProcessing(false);
    }
  }, [currentWorkspaceId, selectedTrashIds, fetchTrashAssets, fetchAssets, tTrash]);

  // Permanent delete asset
  const handlePermanentDelete = useCallback(async (assetId: string) => {
    if (!currentWorkspaceId) return;

    setIsProcessing(true);
    try {
      await projectService.permanentDeleteAsset( assetId);
      await fetchTrashAssets();
      setSelectedTrashIds((prev) => {
        const next = new Set(prev);
        next.delete(assetId);
        return next;
      });
      toast.success(tTrash("deleteSuccess"));
    } catch (error) {
      console.error("Failed to permanently delete asset:", error);
      toast.danger(tTrash("deleteFailed"));
    } finally {
      setIsProcessing(false);
      setShowDeleteConfirm(false);
      setDeleteTargetId(null);
    }
  }, [currentWorkspaceId, fetchTrashAssets]);

  // Empty trash
  const handleEmptyTrash = useCallback(async () => {
    if (!currentWorkspaceId) return;

    setIsProcessing(true);
    try {
      await projectService.emptyTrash();
      await fetchTrashAssets();
      setSelectedTrashIds(new Set());
      toast.success(tTrash("emptySuccess"));
    } catch (error) {
      console.error("Failed to empty trash:", error);
      toast.danger(tTrash("emptyFailed"));
    } finally {
      setIsProcessing(false);
      setShowEmptyConfirm(false);
    }
  }, [currentWorkspaceId, fetchTrashAssets, tTrash]);

  // Toggle trash selection
  const toggleTrashSelection = useCallback((id: string) => {
    setSelectedTrashIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }, []);

  // Filter trash by search and type
  const filteredTrashAssets = trashAssets.filter((asset) => {
    if (typeFilter !== "ALL" && asset.assetType !== typeFilter) {
      return false;
    }
    if (!searchKeyword.trim()) return true;
    const keyword = searchKeyword.toLowerCase();
    return (
      asset.name.toLowerCase().includes(keyword) ||
      asset.description?.toLowerCase().includes(keyword)
    );
  });

  // ==================== Loading & Error States ====================

  if (isLoading && assetViewTab === "assets" && items.length === 0) {
    return <AssetTabSkeleton />;
  }

  if (error && assetViewTab === "assets") {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-3 text-muted">
        <p>{error}</p>
        <Button variant="ghost" size="sm" onPress={fetchAssets}>
          {tTab("reload")}
        </Button>
      </div>
    );
  }

  // ==================== Render Trash Grid Item ====================
  const renderTrashGridItem = (asset: TrashAssetDTO) => {
    const isSelected = selectedTrashIds.has(asset.id);
    const daysLeft = getDaysUntilDeletion(asset.deletedAt);
    const trashImageUrl = getTrashImageUrl(asset);

    return (
      <div
        key={asset.id}
        className={`group relative aspect-square cursor-pointer overflow-hidden rounded-lg border bg-muted/20 transition-all hover:border-accent ${
          isSelected ? "border-accent ring-2 ring-accent/30" : "border-border"
        }`}
        onClick={() => toggleTrashSelection(asset.id)}
      >
        {/* Thumbnail */}
        {asset.assetType === "IMAGE" && trashImageUrl ? (
          <img
            src={getProxiedImageUrl(trashImageUrl)}
            alt={asset.name}
            className="size-full object-cover opacity-60"
          />
        ) : (
          <div className="flex size-full items-center justify-center text-muted">
            {getAssetTypeIcon(asset.assetType)}
          </div>
        )}

        {/* Selection indicator */}
        <div
          className={`absolute left-2 top-2 flex size-5 items-center justify-center rounded border transition-all ${
            isSelected
              ? "border-accent bg-accent text-white"
              : "border-border bg-background/80 opacity-0 group-hover:opacity-100"
          }`}
        >
          {isSelected && (
            <svg className="size-3" viewBox="0 0 12 12" fill="currentColor">
              <path d="M10.28 2.28L4 8.56 1.72 6.28a.75.75 0 00-1.06 1.06l3 3a.75.75 0 001.06 0l7-7a.75.75 0 00-1.06-1.06z" />
            </svg>
          )}
        </div>

        {/* Days left badge */}
        <div className="absolute right-2 top-2 flex items-center gap-1 rounded bg-black/60 px-1.5 py-0.5 text-xs text-white">
          <Clock className="size-3" />
          {tTrash("daysLeft", { days: daysLeft })}
        </div>

        {/* Overlay with info */}
        <div className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/80 via-black/40 to-transparent p-2 pt-8">
          <p className="truncate text-xs font-medium text-white">{asset.name}</p>
          <p className="text-xs text-white/70">{tTrash("deletedTime", { time: formatRelativeTime(asset.deletedAt, tRelTime) })}</p>
        </div>

        {/* Actions on hover */}
        <div
          className="absolute right-2 bottom-2 flex gap-1 opacity-0 transition-opacity group-hover:opacity-100"
          onClick={(e) => e.stopPropagation()}
        >
          <Button
            variant="secondary"
            size="sm"
            isIconOnly
            className="size-7 bg-white/90 hover:bg-white"
            onPress={() => handleRestore(asset.id)}
            isDisabled={isProcessing}
          >
            <RotateCcw className="size-3.5 text-success" />
          </Button>
          <Button
            variant="secondary"
            size="sm"
            isIconOnly
            className="size-7 bg-white/90 hover:bg-white"
            onPress={() => {
              setDeleteTargetId(asset.id);
              setShowDeleteConfirm(true);
            }}
            isDisabled={isProcessing}
          >
            <Trash2 className="size-3.5 text-danger" />
          </Button>
        </div>
      </div>
    );
  };

  // ==================== Main Render ====================
  return (
    <div className="flex h-full flex-col">
      {/* Toolbar */}
      <Surface variant="secondary" className="rounded-2xl mb-3 flex shrink-0 items-center justify-between gap-3 p-2">
        <div className="flex items-center gap-3">
          {/* Category Filter (replaces old view mode + unattached toggle) */}
          {assetViewTab === "assets" ? (
            <div className="flex items-center gap-1.5">
              <FormSelect
                label=""
                value={categoryFilter}
                onChange={(v) => setCategoryFilter(v as CategoryFilter)}
                options={[
                  { value: "ALL", label: "全部" },
                  { value: "STORYBOARD", label: "分镜" },
                  { value: "CHARACTER", label: "角色" },
                  { value: "SCENE", label: "场景" },
                  { value: "PROP", label: "道具" },
                  { value: "UNATTACHED", label: "未分类" },
                ]}
                className="w-28"
              />
              {isCategoryLoading && <Loader2 className="size-3 animate-spin text-muted" />}
            </div>
          ) : (
            <ButtonGroup size="sm" variant="ghost">
              <Button
                variant={trashViewMode === "grid" ? "primary" : undefined}
                isIconOnly
                aria-label="网格视图"
                onPress={() => setTrashViewMode("grid")}
              >
                <LayoutGrid className="size-4" />
              </Button>
              <Button
                variant={trashViewMode === "list" ? "primary" : undefined}
                isIconOnly
                aria-label="列表视图"
                onPress={() => setTrashViewMode("list")}
              >
                <List className="size-4" />
              </Button>
            </ButtonGroup>
          )}

          <Separator variant="secondary" orientation="vertical" className="h-5 self-center" />

          {/* Type Filter */}
          <FormSelect
            label=""
            value={typeFilter}
            onChange={(v) => setTypeFilter(v as typeof typeFilter)}
            options={[
              { value: "ALL", label: tTab("filterAll") },
              { value: "IMAGE", label: tAssetType("image") },
              { value: "VIDEO", label: tAssetType("video") },
              { value: "AUDIO", label: tAssetType("audio") },
            ]}
            className="w-24"
          />

          <Separator variant="secondary" orientation="vertical" className="h-5 self-center" />

          {/* Search */}
          <SearchField aria-label={tTab("searchPlaceholder")} value={searchKeyword} onChange={setSearchKeyword}>
            <SearchField.Group>
              <SearchField.SearchIcon />
              <SearchField.Input className="w-36" placeholder={tTab("searchPlaceholder")} />
              <SearchField.ClearButton />
            </SearchField.Group>
          </SearchField>

          {searchKeyword && (
            <span className="text-xs text-muted">
              {assetViewTab === "assets" ? `${displayItems.length} / ${items.length}` : `${filteredTrashAssets.length} / ${trashAssets.length}`}
            </span>
          )}

          <Separator variant="secondary" orientation="vertical" className="h-5 self-center" />

          {/* Sort */}
          <SortButtons value={sortKeys} onChange={setSortKeys} />
        </div>

        {/* Right Section */}
        <div className="flex items-center gap-1">
          {/* Trash bulk actions */}
          {assetViewTab === "trash" && selectedTrashIds.size > 0 && (
            <>
              <span className="text-xs text-muted">{tTab("selectedCount", { count: selectedTrashIds.size })}</span>
              <Button
                variant="ghost"
                size="sm"
                className="gap-1 text-xs text-success"
                onPress={handleRestoreSelected}
                isDisabled={isProcessing}
              >
                <RotateCcw className="size-3.5" />
                {tTab("restore")}
              </Button>
              <Separator orientation="vertical" className="mx-0.5 h-5 self-center" />
            </>
          )}

          {/* Empty trash button */}
          {assetViewTab === "trash" && trashAssets.length > 0 && (
            <Button
              variant="ghost"
              size="sm"
              className="gap-1 text-xs text-danger"
              onPress={() => setShowEmptyConfirm(true)}
              isDisabled={isProcessing}
            >
              <Trash2 className="size-3.5" />
              {tTab("emptyTrash")}
            </Button>
          )}

          {/* Clear failed assets button */}
          {assetViewTab === "assets" && failedAssets.length > 0 && (
            <Tooltip delay={0}>
              <Button
                variant="ghost"
                size="sm"
                className="gap-1 text-xs text-danger"
                onPress={handleClearFailed}
                isDisabled={isProcessing}
              >
                {isProcessing ? <Loader2 className="size-3.5 animate-spin" /> : <AlertTriangle className="size-3.5" />}
                {tTab("clearFailed", { count: failedAssets.length })}
              </Button>
              <Tooltip.Content>{tTab("clearFailedTooltip")}</Tooltip.Content>
            </Tooltip>
          )}

          <Button
            variant="ghost"
            size="sm"
            className="size-8 p-0 text-muted hover:text-foreground"
            onPress={assetViewTab === "assets" ? fetchAssets : fetchTrashAssets}
          >
            <RefreshCw className={`size-4 ${(assetViewTab === "assets" ? isLoading : isTrashLoading) ? "animate-spin" : ""}`} />
          </Button>

          {/* Assets/Trash Toggle - Archive icon with auto-loaded count */}
          <Tooltip delay={0}>
            <Button
              variant={assetViewTab === "trash" ? "primary" : "ghost"}
              size="sm"
              className="relative size-8 p-0"
              onPress={() => setAssetViewTab(assetViewTab === "assets" ? "trash" : "assets")}
            >
              <Archive className="size-4" />
              {trashTotalItems > 0 && (
                <span className="absolute -right-1 -top-1 flex size-4 items-center justify-center rounded-full bg-danger text-[10px] text-white">
                  {trashTotalItems > 9 ? "9+" : trashTotalItems}
                </span>
              )}
            </Button>
            <Tooltip.Content>{assetViewTab === "assets" ? tTab("trashLabel") : tTab("assetsLabel")}</Tooltip.Content>
          </Tooltip>
        </div>
      </Surface>

      {/* Content */}
      <div className="min-h-0 flex-1">
        {/* Assets View - Grid only */}
        {assetViewTab === "assets" && (
          <>
            {displayItems.length === 0 && !isCategoryLoading ? (
              <EmptyState
                icon={<ImageIcon className="size-16" />}
                title={categoryFilter === "UNATTACHED" ? tTab("noUnattached") : tTab("noAssets")}
                description={categoryFilter === "UNATTACHED" ? tTab("noUnattachedDesc") : undefined}
              />
            ) : isCategoryLoading && categoryFilter !== "ALL" ? (
              <AssetTabSkeleton />
            ) : (
              <ScrollShadow className="h-full" hideScrollBar>
                <EntityGridView
                  items={displayItems}
                  onSelect={handleSelect}
                  onDelete={handleDelete}
                  draggable
                  mediaOnly
                />
              </ScrollShadow>
            )}
          </>
        )}

        {/* Trash View */}
        {assetViewTab === "trash" && (
          <ScrollShadow className="h-full p-2" hideScrollBar>
            {isTrashLoading ? (
              <EntityTabSkeleton />
            ) : filteredTrashAssets.length === 0 ? (
              <EmptyState
                icon={<Trash2 className="size-16" />}
                title={tTrash("emptyTitle")}
                description={tTrash("emptyDescription")}
              />
            ) : trashViewMode === "grid" ? (
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6">
                {filteredTrashAssets.map(renderTrashGridItem)}
              </div>
            ) : (
              <div className="flex flex-col gap-2">
                {filteredTrashAssets.map((asset) => {
                  const isSelected = selectedTrashIds.has(asset.id);
                  const daysLeft = getDaysUntilDeletion(asset.deletedAt);

                  return (
                    <div
                      key={asset.id}
                      className={`group flex cursor-pointer items-center gap-3 rounded-lg border p-3 transition-all hover:border-accent ${
                        isSelected ? "border-accent bg-accent/5" : "border-border"
                      }`}
                      onClick={() => toggleTrashSelection(asset.id)}
                    >
                      {/* Checkbox */}
                      <div
                        className={`flex size-5 shrink-0 items-center justify-center rounded border transition-all ${
                          isSelected ? "border-accent bg-accent text-white" : "border-border"
                        }`}
                      >
                        {isSelected && (
                          <svg className="size-3" viewBox="0 0 12 12" fill="currentColor">
                            <path d="M10.28 2.28L4 8.56 1.72 6.28a.75.75 0 00-1.06 1.06l3 3a.75.75 0 001.06 0l7-7a.75.75 0 00-1.06-1.06z" />
                          </svg>
                        )}
                      </div>

                      {/* Thumbnail */}
                      <div className="size-12 shrink-0 overflow-hidden rounded border border-border bg-muted/20">
                        {asset.assetType === "IMAGE" && getTrashImageUrl(asset) ? (
                          <img
                            src={getProxiedImageUrl(getTrashImageUrl(asset)!)}
                            alt={asset.name}
                            className="size-full object-cover opacity-60"
                          />
                        ) : (
                          <div className="flex size-full items-center justify-center text-muted">
                            {getAssetTypeIcon(asset.assetType)}
                          </div>
                        )}
                      </div>

                      {/* Info */}
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-medium">{asset.name}</p>
                        <p className="text-xs text-muted">
                          {formatFileSize(asset.fileSize)} · {tTrash("deletedTime", { time: formatRelativeTime(asset.deletedAt, tRelTime) })}
                        </p>
                      </div>

                      {/* Days left */}
                      <div className="flex shrink-0 items-center gap-1 text-xs text-warning">
                        <Clock className="size-3.5" />
                        {tTrash("daysUntilDeletion", { days: daysLeft })}
                      </div>

                      {/* Actions */}
                      <div
                        className="flex shrink-0 gap-1 opacity-0 transition-opacity group-hover:opacity-100"
                        onClick={(e) => e.stopPropagation()}
                      >
                        <Button
                          variant="ghost"
                          size="sm"
                          isIconOnly
                          className="size-8"
                          onPress={() => handleRestore(asset.id)}
                          isDisabled={isProcessing}
                        >
                          <RotateCcw className="size-4 text-success" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          isIconOnly
                          className="size-8"
                          onPress={() => {
                            setDeleteTargetId(asset.id);
                            setShowDeleteConfirm(true);
                          }}
                          isDisabled={isProcessing}
                        >
                          <Trash2 className="size-4 text-danger" />
                        </Button>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </ScrollShadow>
        )}
      </div>

      {/* Empty Trash Confirmation Modal */}
      <Modal.Backdrop isOpen={showEmptyConfirm} onOpenChange={setShowEmptyConfirm}>
        <Modal.Container size="sm">
          <Modal.Dialog>
            <Modal.Header>
              <Modal.Heading className="flex items-center gap-2">
                <AlertTriangle className="size-5 text-danger" />
                {tTrash("emptyTrashTitle")}
              </Modal.Heading>
            </Modal.Header>
            <Modal.Body>
              <p className="text-sm text-muted">
                {tTrash("emptyTrashMessage", { count: trashTotalItems })}
              </p>
            </Modal.Body>
            <Modal.Footer>
              <Button
                variant="ghost"
                onPress={() => setShowEmptyConfirm(false)}
                isDisabled={isProcessing}
              >
                {tCommon("cancel")}
              </Button>
              <Button
                variant="danger"
                onPress={handleEmptyTrash}
                isPending={isProcessing}
              >
                {({isPending}) => (<>{isPending ? <Spinner color="current" size="sm" /> : null}{tTrash("confirmDelete")}</>)}
              </Button>
            </Modal.Footer>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>

      {/* Delete Single Item Confirmation Modal */}
      <Modal.Backdrop isOpen={showDeleteConfirm} onOpenChange={setShowDeleteConfirm}>
        <Modal.Container size="sm">
          <Modal.Dialog>
            <Modal.Header>
              <Modal.Heading className="flex items-center gap-2">
                <AlertTriangle className="size-5 text-danger" />
                {tTrash("permanentDeleteTitle")}
              </Modal.Heading>
            </Modal.Header>
            <Modal.Body>
              <p className="text-sm text-muted">
                {tTrash("permanentDeleteMessage")}
              </p>
            </Modal.Body>
            <Modal.Footer>
              <Button
                variant="ghost"
                onPress={() => {
                  setShowDeleteConfirm(false);
                  setDeleteTargetId(null);
                }}
                isDisabled={isProcessing}
              >
                {tCommon("cancel")}
              </Button>
              <Button
                variant="danger"
                onPress={() => deleteTargetId && handlePermanentDelete(deleteTargetId)}
                isPending={isProcessing}
              >
                {({isPending}) => (<>{isPending ? <Spinner color="current" size="sm" /> : null}{tTrash("confirmDelete")}</>)}
              </Button>
            </Modal.Footer>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>

      {/* Asset Preview Modal */}
      <AssetPreviewModal
        isOpen={isPreviewOpen}
        onOpenChange={setIsPreviewOpen}
        asset={previewAsset}
      />
    </div>
  );
}

export default AssetTab;
