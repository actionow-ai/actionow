"use client";

import { useState, useEffect, useCallback, useRef, useMemo } from "react";
import { useTranslations, useLocale} from "next-intl";
import { useSearchParams } from "next/navigation";
import { toast } from "@heroui/react";
import {
  Users,
  MapPin,
  Package,
  FileBox,
  Palette,
  Loader2,
} from "lucide-react";
import { libraryService } from "@/lib/api/services/library.service";
import { projectService } from "@/lib/api/services/project.service";
import { useWorkspace } from "@/components/providers/workspace-provider";
import type {
  LibraryCharacterDTO,
  LibrarySceneDTO,
  LibraryPropDTO,
  LibraryAssetDTO,
  LibraryStyleDTO,
  SystemCharacterDTO,
  SystemSceneDTO,
  SystemPropDTO,
  SystemAssetDTO,
  SystemStyleDTO,
  CharacterListDTO,
  SceneListDTO,
  PropListDTO,
  AssetListDTO,
  StyleListDTO,
} from "@/lib/api/dto";
import {
  MaterialHeader,
  MaterialCard,
  SkeletonCard,
  LibraryPreviewModal,
  CreateEntityModal,
  MaterialDetailModal,
} from "@/components/material-room";
import type { EntityType, SourceMode, SortField, SortDir, MaterialItem } from "@/components/material-room";
import { SpotlightOverlay } from "@/components/onboarding/spotlight-overlay";
import { useOnboardingStore } from "@/lib/stores/onboarding-store";
import { getErrorFromException } from "@/lib/api";

const PAGE_SIZE = 20;

const ENTITY_ICONS: Record<EntityType, typeof Users> = {
  characters: Users,
  scenes: MapPin,
  props: Package,
  assets: FileBox,
  styles: Palette,
};

// Asset cover fallback: coverUrl → thumbnailUrl → fileUrl
function resolveAssetCover(entity: { coverUrl?: string | null; thumbnailUrl?: string | null; fileUrl?: string | null }): string | null {
  return entity.coverUrl || entity.thumbnailUrl || entity.fileUrl || null;
}

// Normalize public library DTOs to a common MaterialItem shape
function toMaterialItem(
  entity: LibraryCharacterDTO | LibrarySceneDTO | LibraryPropDTO | LibraryAssetDTO | LibraryStyleDTO,
): MaterialItem {
  const isAsset = "assetType" in entity;
  const base: MaterialItem = {
    id: entity.id,
    name: entity.name,
    description: entity.description,
    coverUrl: isAsset ? resolveAssetCover(entity as LibraryAssetDTO) : entity.coverUrl,
    publishedAt: entity.publishedAt,
    publishNote: entity.publishNote,
    createdAt: entity.createdAt,
  };
  if (isAsset) {
    const asset = entity as LibraryAssetDTO;
    base.assetType = asset.assetType;
    base.fileSize = asset.fileSize;
    base.fileUrl = asset.fileUrl;
    base.mimeType = asset.mimeType;
  }
  return base;
}

// Normalize system admin DTOs to MaterialItem with admin fields
function toSystemMaterialItem(
  entity: SystemCharacterDTO | SystemSceneDTO | SystemPropDTO | SystemAssetDTO | SystemStyleDTO,
): MaterialItem {
  const isAsset = "assetType" in entity;
  const base: MaterialItem = {
    id: entity.id,
    name: entity.name,
    description: entity.description,
    coverUrl: isAsset ? resolveAssetCover(entity as SystemAssetDTO) : entity.coverUrl,
    publishedAt: entity.publishedAt,
    publishNote: entity.publishNote,
    createdAt: entity.createdAt,
    scope: entity.scope,
  };
  if (isAsset) {
    const asset = entity as SystemAssetDTO;
    base.assetType = asset.assetType;
    base.fileSize = asset.fileSize;
    base.fileUrl = asset.fileUrl;
    base.mimeType = asset.mimeType;
  }
  return base;
}

// Normalize project DTOs (workspace/script mode) to MaterialItem
function toProjectMaterialItem(
  entity: CharacterListDTO | SceneListDTO | PropListDTO | AssetListDTO | StyleListDTO,
): MaterialItem {
  const isAsset = "assetType" in entity;
  const base: MaterialItem = {
    id: entity.id,
    name: entity.name,
    description: entity.description,
    coverUrl: isAsset
      ? resolveAssetCover(entity as AssetListDTO)
      : "coverUrl" in entity ? entity.coverUrl : null,
    publishedAt: null,
    publishNote: null,
    createdAt: entity.createdAt,
    createdBy: entity.createdBy ?? null,
    createdByNickname: entity.createdByNickname ?? null,
    createdByUsername: entity.createdByUsername ?? null,
    scope: entity.scope,
  };
  if (isAsset) {
    const asset = entity as AssetListDTO;
    base.assetType = asset.assetType;
    base.fileSize = asset.fileSize;
    base.fileUrl = asset.fileUrl;
    base.mimeType = asset.mimeType;
  }
  return base;
}

export default function MaterialRoomPage() {
  const locale = useLocale();
  const t = useTranslations("workspace.materialRoom");
  const searchParams = useSearchParams();

  const entityType = (searchParams.get("type") as EntityType) || "characters";
  const { currentWorkspaceId, currentWorkspace } = useWorkspace();

  // Derive admin mode
  const isSystemAdmin = useMemo(
    () =>
      !!currentWorkspace?.isSystem &&
      ["CREATOR", "ADMIN"].includes(currentWorkspace.myRole),
    [currentWorkspace],
  );

  // Onboarding spotlight
  const createButtonRef = useRef<HTMLDivElement>(null);
  const sourceSelectorRef = useRef<HTMLDivElement>(null);
  const hasCompletedOnboarding = useOnboardingStore((s) => s.hasCompletedOnboarding);
  const dismissedTips = useOnboardingStore((s) => s.dismissedTips);
  const dismissTip = useOnboardingStore((s) => s.dismissTip);

  // Source mode
  const [sourceMode, setSourceMode] = useState<SourceMode>("all");
  const [scripts, setScripts] = useState<{ id: string; title: string }[]>([]);
  const [selectedScriptId, setSelectedScriptId] = useState<string | null>(null);

  // Data
  const [items, setItems] = useState<MaterialItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [isLoadingMore, setIsLoadingMore] = useState(false);

  // Filters / Sort
  const [searchKeyword, setSearchKeyword] = useState("");
  const [sortField, setSortField] = useState<SortField>("publishedAt");
  const [sortDir, setSortDir] = useState<SortDir>("desc");

  // Server-side pagination
  const [currentPage, setCurrentPage] = useState(1);
  const [hasMore, setHasMore] = useState(true);
  const [totalRecords, setTotalRecords] = useState(0);
  const loadMoreRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  // Preview modal
  const [previewTarget, setPreviewTarget] = useState<MaterialItem | null>(null);
  const [previewDetail, setPreviewDetail] = useState<Record<string, unknown> | null | undefined>(undefined);

  // Copy state
  const [copyingId, setCopyingId] = useState<string | null>(null);

  // Admin: Create modal
  const [showCreateModal, setShowCreateModal] = useState(false);

  // Admin: Edit modal
  const [editTarget, setEditTarget] = useState<MaterialItem | null>(null);

  // Load scripts for script mode
  useEffect(() => {
    if (!currentWorkspaceId) return;
    projectService.getScripts().then((list) => {
      setScripts(list.map((s) => ({ id: s.id, title: s.title })));
    }).catch(() => {
      setScripts([]);
    });
  }, [currentWorkspaceId]);

  // Load data with pagination
  const loadData = useCallback(
    async (page: number, append: boolean = false) => {
      if (!currentWorkspaceId && sourceMode !== "public" && sourceMode !== "all") return;
      // For script mode, don't load until a script is selected
      if (sourceMode === "script" && !selectedScriptId) {
        setItems([]);
        setIsLoading(false);
        setHasMore(false);
        return;
      }

      try {
        if (append) {
          setIsLoadingMore(true);
        }

        // Map sortField for workspace/script modes: publishedAt → createdAt
        const isPublicLike = sourceMode === "public" || sourceMode === "all";
        const effectiveOrderBy = !isPublicLike && sortField === "publishedAt" ? "createdAt" : sortField;

        const params: Record<string, string | number | boolean | undefined> = {
          pageNum: page,
          pageSize: PAGE_SIZE,
          orderBy: effectiveOrderBy,
          orderDir: sortDir,
        };

        if (searchKeyword.trim()) {
          params.keyword = searchKeyword.trim();
        }

        let newItems: MaterialItem[] = [];
        let total = 0;
        let current = 1;
        let pages = 0;

        if (sourceMode === "all") {
          // "All" mode: merge public + workspace data in parallel
          const publicParams = { ...params, orderBy: sortField };
          const workspaceParams: Record<string, string | number | boolean | undefined> = {
            ...params,
            orderBy: sortField === "publishedAt" ? "createdAt" : sortField,
            scope: "WORKSPACE",
          };

          const loadPublic = async (): Promise<MaterialItem[]> => {
            try {
              if (isSystemAdmin) {
                switch (entityType) {
                  case "characters": return (await libraryService.systemQueryCharacters(publicParams)).records.map(toSystemMaterialItem);
                  case "scenes": return (await libraryService.systemQueryScenes(publicParams)).records.map(toSystemMaterialItem);
                  case "props": return (await libraryService.systemQueryProps(publicParams)).records.map(toSystemMaterialItem);
                  case "assets": return (await libraryService.systemQueryAssets(publicParams)).records.map(toSystemMaterialItem);
                  case "styles": return (await libraryService.systemQueryStyles(publicParams)).records.map(toSystemMaterialItem);
                }
              } else {
                switch (entityType) {
                  case "characters": return (await libraryService.queryCharacters(publicParams)).records.map(toMaterialItem);
                  case "scenes": return (await libraryService.queryScenes(publicParams)).records.map(toMaterialItem);
                  case "props": return (await libraryService.queryProps(publicParams)).records.map(toMaterialItem);
                  case "assets": return (await libraryService.queryAssets(publicParams)).records.map(toMaterialItem);
                  case "styles": return (await libraryService.queryStyles(publicParams)).records.map(toMaterialItem);
                }
              }
            } catch { /* ignore */ }
            return [];
          };

          const loadWorkspace = async (): Promise<MaterialItem[]> => {
            if (!currentWorkspaceId) return [];
            try {
              switch (entityType) {
                case "characters": return (await projectService.queryCharacters(workspaceParams)).records.map(toProjectMaterialItem);
                case "scenes": return (await projectService.queryScenes(workspaceParams)).records.map(toProjectMaterialItem);
                case "props": return (await projectService.queryProps(workspaceParams)).records.map(toProjectMaterialItem);
                case "styles": return (await projectService.queryStyles(workspaceParams)).records.map(toProjectMaterialItem);
                case "assets": {
                  let allAssets = await projectService.getAssets();
                  allAssets = allAssets.filter((a) => a.scope === "WORKSPACE");
                  if (searchKeyword.trim()) {
                    const kw = searchKeyword.trim().toLowerCase();
                    allAssets = allAssets.filter((a) => a.name.toLowerCase().includes(kw));
                  }
                  return allAssets.map(toProjectMaterialItem);
                }
              }
            } catch { /* ignore */ }
            return [];
          };

          const [publicItems, workspaceItems] = await Promise.all([loadPublic(), loadWorkspace()]);

          // Merge: workspace first, then public, deduplicate by id
          const merged = [...workspaceItems, ...publicItems];
          const seen = new Set<string>();
          newItems = merged.filter((item) => {
            if (seen.has(item.id)) return false;
            seen.add(item.id);
            return true;
          });

          total = newItems.length;
          current = 1;
          pages = 1; // "all" mode doesn't support infinite scroll
        } else if (sourceMode === "workspace" || sourceMode === "script") {
          // Workspace / Script mode — use projectService
          const scopeValue = sourceMode === "workspace" ? "WORKSPACE" : "SCRIPT";
          const projectParams: Record<string, string | number | boolean | undefined> = {
            ...params,
            scope: scopeValue,
          };
          if (sourceMode === "script" && selectedScriptId) {
            projectParams.scriptId = selectedScriptId;
          }

          switch (entityType) {
            case "characters": {
              const res = await projectService.queryCharacters( projectParams);
              newItems = res.records.map(toProjectMaterialItem);
              total = res.total;
              current = res.current;
              pages = res.pages;
              break;
            }
            case "scenes": {
              const res = await projectService.queryScenes( projectParams);
              newItems = res.records.map(toProjectMaterialItem);
              total = res.total;
              current = res.current;
              pages = res.pages;
              break;
            }
            case "props": {
              const res = await projectService.queryProps( projectParams);
              newItems = res.records.map(toProjectMaterialItem);
              total = res.total;
              current = res.current;
              pages = res.pages;
              break;
            }
            case "styles": {
              const res = await projectService.queryStyles( projectParams);
              newItems = res.records.map(toProjectMaterialItem);
              total = res.total;
              current = res.current;
              pages = res.pages;
              break;
            }
            case "assets": {
              // Assets use non-paginated APIs
              let allAssets: AssetListDTO[];
              if (sourceMode === "script" && selectedScriptId) {
                allAssets = await projectService.getAssetsByScript( selectedScriptId);
              } else {
                allAssets = await projectService.getAssets();
                // Filter to WORKSPACE scope only
                allAssets = allAssets.filter((a) => a.scope === "WORKSPACE");
              }

              // Client-side keyword filter
              if (searchKeyword.trim()) {
                const kw = searchKeyword.trim().toLowerCase();
                allAssets = allAssets.filter((a) => a.name.toLowerCase().includes(kw));
              }

              // Client-side sort
              allAssets.sort((a, b) => {
                let cmp = 0;
                if (effectiveOrderBy === "createdAt") {
                  cmp = new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime();
                } else {
                  cmp = a.name.localeCompare(b.name);
                }
                return sortDir === "desc" ? -cmp : cmp;
              });

              newItems = allAssets.map(toProjectMaterialItem);
              total = newItems.length;
              current = 1;
              pages = 1;
              break;
            }
          }
        } else if (isSystemAdmin) {
          // System admin APIs (public mode)
          switch (entityType) {
            case "characters": {
              const res = await libraryService.systemQueryCharacters(params);
              newItems = res.records.map(toSystemMaterialItem);
              total = res.total;
              current = res.current;
              pages = res.pages;
              break;
            }
            case "scenes": {
              const res = await libraryService.systemQueryScenes(params);
              newItems = res.records.map(toSystemMaterialItem);
              total = res.total;
              current = res.current;
              pages = res.pages;
              break;
            }
            case "props": {
              const res = await libraryService.systemQueryProps(params);
              newItems = res.records.map(toSystemMaterialItem);
              total = res.total;
              current = res.current;
              pages = res.pages;
              break;
            }
            case "assets": {
              const res = await libraryService.systemQueryAssets(params);
              newItems = res.records.map(toSystemMaterialItem);
              total = res.total;
              current = res.current;
              pages = res.pages;
              break;
            }
            case "styles": {
              const res = await libraryService.systemQueryStyles(params);
              newItems = res.records.map(toSystemMaterialItem);
              total = res.total;
              current = res.current;
              pages = res.pages;
              break;
            }
          }
        } else {
          // Public browse APIs
          switch (entityType) {
            case "characters": {
              const res = await libraryService.queryCharacters(params);
              newItems = res.records.map(toMaterialItem);
              total = res.total;
              current = res.current;
              pages = res.pages;
              break;
            }
            case "scenes": {
              const res = await libraryService.queryScenes(params);
              newItems = res.records.map(toMaterialItem);
              total = res.total;
              current = res.current;
              pages = res.pages;
              break;
            }
            case "props": {
              const res = await libraryService.queryProps(params);
              newItems = res.records.map(toMaterialItem);
              total = res.total;
              current = res.current;
              pages = res.pages;
              break;
            }
            case "assets": {
              const res = await libraryService.queryAssets(params);
              newItems = res.records.map(toMaterialItem);
              total = res.total;
              current = res.current;
              pages = res.pages;
              break;
            }
            case "styles": {
              const res = await libraryService.queryStyles(params);
              newItems = res.records.map(toMaterialItem);
              total = res.total;
              current = res.current;
              pages = res.pages;
              break;
            }
          }
        }

        if (append) {
          setItems((prev) => {
            const existingIds = new Set(prev.map((item) => item.id));
            const uniqueNew = newItems.filter((item) => !existingIds.has(item.id));
            return [...prev, ...uniqueNew];
          });
        } else {
          setItems(newItems);
        }

        setTotalRecords(total);
        setCurrentPage(current);
        setHasMore(current < pages);
      } catch (error) {
        console.error("Failed to load data:", error);
        toast.danger(getErrorFromException(error, locale));
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
        setIsLoadingMore(false);
      }
    },
    [entityType, searchKeyword, sortField, sortDir, isSystemAdmin, sourceMode, selectedScriptId, currentWorkspaceId],
  );

  // Reset and load from first page
  const resetAndLoad = useCallback(() => {
    setItems([]);
    setCurrentPage(1);
    setHasMore(true);
    setIsLoading(true);
    loadData(1, false);
  }, [loadData]);

  // Load more (next page)
  const loadMore = useCallback(() => {
    if (!isLoadingMore && hasMore && !isLoading) {
      loadData(currentPage + 1, true);
    }
  }, [isLoadingMore, hasMore, isLoading, currentPage, loadData]);

  // Initial load & reload on type/sort change
  useEffect(() => {
    resetAndLoad();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [entityType, sortField, sortDir]);

  // Reload on sourceMode or selectedScriptId change
  useEffect(() => {
    resetAndLoad();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sourceMode, selectedScriptId]);

  // Reset search when entity type changes
  useEffect(() => {
    setSearchKeyword("");
  }, [entityType]);

  // Debounced search
  useEffect(() => {
    const timer = setTimeout(() => {
      if (!isLoading) {
        resetAndLoad();
      }
    }, 300);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchKeyword]);

  // Intersection Observer for infinite scroll
  useEffect(() => {
    const observer = new IntersectionObserver(
      (entries) => {
        const [entry] = entries;
        if (entry.isIntersecting && hasMore && !isLoadingMore && !isLoading) {
          loadMore();
        }
      },
      {
        root: containerRef.current,
        rootMargin: "100px",
        threshold: 0.1,
      },
    );

    const currentRef = loadMoreRef.current;
    if (currentRef) {
      observer.observe(currentRef);
    }

    return () => {
      if (currentRef) {
        observer.unobserve(currentRef);
      }
    };
  }, [hasMore, isLoadingMore, isLoading, loadMore]);

  const handleRefresh = () => {
    setIsRefreshing(true);
    resetAndLoad();
  };

  const handleSortChange = (field: SortField, dir: SortDir) => {
    setSortField(field);
    setSortDir(dir);
  };

  const handleSourceModeChange = (mode: SourceMode) => {
    setSourceMode(mode);
    if (mode !== "script") {
      setSelectedScriptId(null);
    }
  };

  // Determine if we're in a project (workspace/script) mode for card actions
  const isProjectMode = sourceMode === "workspace" || sourceMode === "script";

  const handleScriptChange = (scriptId: string) => {
    setSelectedScriptId(scriptId);
  };

  // Copy to workspace (public mode only)
  const handleCopy = async (item: MaterialItem) => {
    if (!currentWorkspaceId || copyingId) return;
    try {
      setCopyingId(item.id);
      switch (entityType) {
        case "characters":
          await libraryService.copyCharacter( item.id);
          break;
        case "scenes":
          await libraryService.copyScene( item.id);
          break;
        case "props":
          await libraryService.copyProp( item.id);
          break;
        case "assets":
          await libraryService.copyAsset( item.id);
          break;
        case "styles":
          await libraryService.copyStyle( item.id);
          break;
      }
      toast.success(t("copySuccess"));
    } catch (error) {
      console.error("Copy failed:", error);
      toast.danger(t("copyFailed"));
    } finally {
      setCopyingId(null);
    }
  };

  // Preview: load detail then open modal
  const handlePreview = async (item: MaterialItem) => {
    setPreviewTarget(item);
    setPreviewDetail(undefined); // undefined = loading
    try {
      let detail: Record<string, unknown> | null = null;
      if (sourceMode === "workspace" || sourceMode === "script") {
        // Use projectService for workspace/script mode
        if (!currentWorkspaceId) return;
        switch (entityType) {
          case "characters":
            detail = await projectService.getCharacter( item.id) as unknown as Record<string, unknown>;
            break;
          case "scenes":
            detail = await projectService.getScene( item.id) as unknown as Record<string, unknown>;
            break;
          case "props":
            detail = await projectService.getProp( item.id) as unknown as Record<string, unknown>;
            break;
          case "assets":
            detail = await projectService.getAsset( item.id) as unknown as Record<string, unknown>;
            break;
          case "styles":
            // No getStyle detail endpoint for project service
            detail = null;
            break;
        }
      } else {
        // Public / admin mode — use libraryService
        switch (entityType) {
          case "characters":
            detail = await libraryService.getCharacter(item.id) as unknown as Record<string, unknown>;
            break;
          case "scenes":
            detail = await libraryService.getScene(item.id) as unknown as Record<string, unknown>;
            break;
          case "props":
            detail = await libraryService.getProp(item.id) as unknown as Record<string, unknown>;
            break;
          case "assets":
            detail = await libraryService.getAsset(item.id) as unknown as Record<string, unknown>;
            break;
          case "styles":
            detail = await libraryService.getStyle(item.id) as unknown as Record<string, unknown>;
            break;
        }
      }
      // Guard against race condition: only update if this item is still the preview target
      setPreviewTarget((current) => {
        if (current?.id === item.id) {
          setPreviewDetail(detail);
        }
        return current;
      });
    } catch {
      setPreviewTarget((current) => {
        if (current?.id === item.id) {
          setPreviewDetail(null);
        }
        return current;
      });
    }
  };

  // Admin: Publish
  const handlePublish = async (item: MaterialItem) => {
    try {
      switch (entityType) {
        case "characters":
          await libraryService.publishCharacter(item.id);
          break;
        case "scenes":
          await libraryService.publishScene(item.id);
          break;
        case "props":
          await libraryService.publishProp(item.id);
          break;
        case "assets":
          await libraryService.publishAsset(item.id);
          break;
        case "styles":
          await libraryService.publishStyle(item.id);
          break;
      }
      toast.success(t("admin.publishSuccess"));
      // Update item in-place for immediate feedback: scope → SYSTEM means published
      setItems((prev) =>
        prev.map((i) => (i.id === item.id ? { ...i, scope: "SYSTEM" as const, publishedAt: new Date().toISOString() } : i)),
      );
      // Also update preview target if it's the same item
      setPreviewTarget((current) =>
        current?.id === item.id ? { ...current, scope: "SYSTEM" as const, publishedAt: new Date().toISOString() } : current,
      );
    } catch (error) {
      console.error("Publish failed:", error);
      toast.danger(t("admin.publishFailed"));
    }
  };

  // Admin: Unpublish
  const handleUnpublish = async (item: MaterialItem) => {
    try {
      switch (entityType) {
        case "characters":
          await libraryService.unpublishCharacter(item.id);
          break;
        case "scenes":
          await libraryService.unpublishScene(item.id);
          break;
        case "props":
          await libraryService.unpublishProp(item.id);
          break;
        case "assets":
          await libraryService.unpublishAsset(item.id);
          break;
        case "styles":
          await libraryService.unpublishStyle(item.id);
          break;
      }
      toast.success(t("admin.unpublishSuccess"));
      // scope → WORKSPACE means unpublished (reverts from SYSTEM)
      setItems((prev) =>
        prev.map((i) => (i.id === item.id ? { ...i, scope: "WORKSPACE" as const, publishedAt: null } : i)),
      );
      setPreviewTarget((current) =>
        current?.id === item.id ? { ...current, scope: "WORKSPACE" as const, publishedAt: null } : current,
      );
    } catch (error) {
      console.error("Unpublish failed:", error);
      toast.danger(t("admin.unpublishFailed"));
    }
  };

  // Edit handler (used in both admin and workspace/script modes)
  const handleEdit = (item: MaterialItem) => {
    setEditTarget(item);
  };

  const Icon = ENTITY_ICONS[entityType];

  const BROWSE_TIP_ID = "material-room-browse";
  const CREATE_TIP_ID = "material-room-create";
  const isEmpty = !isLoading && items.length === 0;
  const showBrowseSpotlight = isEmpty && !isSystemAdmin && hasCompletedOnboarding && !dismissedTips.includes(BROWSE_TIP_ID);
  const showCreateSpotlight = isEmpty && isSystemAdmin && hasCompletedOnboarding && !dismissedTips.includes(CREATE_TIP_ID);
  const anySpotlightActive = showBrowseSpotlight || showCreateSpotlight;

  return (
    <>
      <div className="flex h-full flex-col">
        {/* Header */}
        <MaterialHeader
          searchKeyword={searchKeyword}
          onSearchChange={setSearchKeyword}
          isRefreshing={isRefreshing}
          onRefresh={handleRefresh}
          sortField={sortField}
          sortDir={sortDir}
          onSortChange={handleSortChange}
          isSystemAdmin={isSystemAdmin}
          entityType={entityType}
          onCreate={() => {
            if (showCreateSpotlight) dismissTip(CREATE_TIP_ID);
            setShowCreateModal(true);
          }}
          sourceMode={sourceMode}
          onSourceModeChange={handleSourceModeChange}
          scripts={scripts}
          selectedScriptId={selectedScriptId}
          onScriptChange={handleScriptChange}
          createButtonRef={showCreateSpotlight ? createButtonRef : undefined}
          sourceSelectorRef={showBrowseSpotlight ? sourceSelectorRef : undefined}
          spotlightActive={anySpotlightActive}
        />

        {/* Content */}
        <div ref={containerRef} className="min-h-0 flex-1 overflow-auto">
          {isLoading && items.length === 0 ? (
            // Skeleton loading
            <div className="grid grid-cols-2 gap-5 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
              {Array.from({ length: 10 }).map((_, i) => (
                <SkeletonCard key={i} />
              ))}
            </div>
          ) : items.length === 0 && !isLoading ? (
            // Empty state
            <div className="flex min-h-full flex-col items-center justify-center text-center">
              <div className="flex size-20 items-center justify-center rounded-full bg-muted/10">
                <Icon className="size-10 text-muted/40" />
              </div>
              <p className="mt-5 text-lg font-semibold text-foreground">{t("empty")}</p>
              <p className="mt-1.5 text-sm text-muted">{t("emptyHint")}</p>
            </div>
          ) : (
            // Card grid + load more
            <div>
              <div className="grid grid-cols-2 gap-5 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
                {items.map((item) => (
                  <MaterialCard
                    key={item.id}
                    item={item}
                    entityType={entityType}
                    onPreview={handlePreview}
                    onCopy={isProjectMode ? undefined : handleCopy}
                    isCopying={copyingId === item.id}
                    isSystemAdmin={isSystemAdmin && sourceMode === "public"}
                    onEdit={isSystemAdmin || isProjectMode ? handleEdit : undefined}
                    onPublish={isSystemAdmin && sourceMode === "public" ? handlePublish : undefined}
                    onUnpublish={isSystemAdmin && sourceMode === "public" ? handleUnpublish : undefined}
                  />
                ))}
              </div>

              {/* Load more sentinel */}
              <div ref={loadMoreRef} className="mt-6 flex items-center justify-center py-4">
                {isLoadingMore ? (
                  <div className="flex items-center gap-2 text-muted">
                    <Loader2 className="size-5 animate-spin" />
                    <span className="text-sm">{t("loading")}</span>
                  </div>
                ) : hasMore ? (
                  <span className="text-sm text-muted/50">{t("loadMore")}</span>
                ) : items.length > 0 ? (
                  <span className="text-sm text-muted/50">
                    {t("loadedAll", { count: totalRecords })}
                  </span>
                ) : null}
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Spotlight onboarding guide — regular users: source selector */}
      {showBrowseSpotlight && (
        <SpotlightOverlay
          tipId={BROWSE_TIP_ID}
          targetRef={sourceSelectorRef}
          title={t("tips.browseLibrary.title")}
          description={t("tips.browseLibrary.spotlightDescription")}
          dismissLabel={t("tips.browseLibrary.gotIt")}
        />
      )}

      {/* Spotlight onboarding guide — admin: create button */}
      {showCreateSpotlight && (
        <SpotlightOverlay
          tipId={CREATE_TIP_ID}
          targetRef={createButtonRef}
          title={t("tips.browseLibrary.title")}
          description={t("tips.browseLibrary.spotlightDescriptionAdmin")}
          dismissLabel={t("tips.browseLibrary.gotIt")}
        />
      )}

      {/* Preview Modal */}
      <LibraryPreviewModal
        isOpen={!!previewTarget}
        onOpenChange={(open) => {
          if (!open) {
            setPreviewTarget(null);
            setPreviewDetail(undefined);
          }
        }}
        item={previewTarget}
        entityType={entityType}
        onCopy={handleCopy}
        isCopying={copyingId === previewTarget?.id}
        detail={previewDetail}
        isSystemAdmin={isSystemAdmin && sourceMode === "public"}
        onPublish={isSystemAdmin && sourceMode === "public" ? handlePublish : undefined}
        onUnpublish={isSystemAdmin && sourceMode === "public" ? handleUnpublish : undefined}
        onEdit={isSystemAdmin || isProjectMode ? (item) => { handleEdit(item); } : undefined}
      />

      {/* Create Modal */}
      {currentWorkspaceId && (
        <CreateEntityModal
          isOpen={showCreateModal}
          onOpenChange={setShowCreateModal}
          entityType={entityType}
          workspaceId={currentWorkspaceId}
          onCreated={resetAndLoad}
          sourceMode={sourceMode}
          selectedScriptId={selectedScriptId}
        />
      )}

      {/* Edit Modal */}
      {currentWorkspaceId && editTarget && (
        <MaterialDetailModal
          isOpen={!!editTarget}
          onOpenChange={(open) => {
            if (!open) setEditTarget(null);
          }}
          entityType={entityType}
          entityId={editTarget.id}
          entityName={editTarget.name}
          workspaceId={currentWorkspaceId}
          onUpdated={resetAndLoad}
        />
      )}
    </>
  );
}
