"use client";

/**
 * Storyboard Tab Component
 * Dedicated tab component for managing storyboards with list/grid/detail views
 */

import { useState, useEffect, useCallback, useRef } from "react";
import {
  Button,
  ButtonGroup,
  ScrollShadow,
  Tooltip,
  Modal,
  Input,
  Label,
  TextArea,
  Spinner,
  Separator,
  SearchField,
  Surface,
  toast,
} from "@heroui/react";
import {
  Film,
  LayoutGrid,
  List,
  PanelLeft,
  PanelLeftClose,
  Plus,
  X,
  RefreshCw,
  Loader2,
  Camera,
  Clock,
  MapPin,
  Users,
  Package,
  MessageSquare,
  Sparkles,
  Volume2,
  Eye,
  ChevronDown,
  ChevronUp,
  History,
  Edit3,
  Trash2,
  Music,
  Lightbulb,
  Move,
  Video,
  Palette,
  Wand2,
  Image,
  Play,
  Upload,
  Filter,
  GripVertical,
  Clapperboard,
} from "lucide-react";
import NextImage from "@/components/ui/content-image";
import { projectService, getErrorFromException} from "@/lib/api";
import { commentService } from "@/lib/api/services/comment.service";
import type { StoryboardListDTO, StoryboardDetailDTO, EpisodeListDTO, CharacterListDTO, SceneListDTO, PropListDTO, VersionInfoDTO, EntityAssetRelationDTO, AssetListDTO } from "@/lib/api/dto";
import { useWorkspace } from "@/components/providers/workspace-provider";
import { useEntityChanges, useDebouncedEntityChanges } from "@/lib/websocket";
import { useAIGeneration, type AssociatedCover } from "@/components/providers/ai-generation-provider";
import type { ViewMode, EntityItem } from "../types";
import {
  transformToEntityItem,
  getStoredViewMode,
  getStoredSidebarCollapsed,
  setViewMode as saveViewMode,
  setSidebarCollapsed as saveSidebarCollapsed,
  formatDate,
} from "../utils";
import { StatusBadge, EntityListView, EntityGridView, CoverImageCard, DetailSidebar, AssetCard, EntityTabSkeleton, DetailPaneSkeleton, AssetsSectionSkeleton, VersionListSkeleton, SortButtons, sortItems, EmptyState, type SortKey } from "../common";
import { useDragDropActions, createAssetDragData, ASSET_DRAG_TYPE } from "@/lib/stores/drag-drop-store";
import { useCommentPanelStore } from "@/lib/stores/comment-panel-store";
import { EditingOverlay } from "../components";
import { useCollaboration } from "../hooks/use-collaboration";
import { useStoryboardEpisodeStore } from "../hooks/use-storyboard-episode-store";
import { useLocale } from "next-intl";

interface StoryboardTabProps {
  scriptId: string;
}

interface CoverEntityRef {
  type: "SCENE" | "CHARACTER" | "PROP";
  id: string;
  name: string;
  coverUrl: string;
}

// Visual description field config
const VISUAL_DESC_FIELDS = [
  { key: "shotType", label: "镜头类型", icon: Video, placeholder: "如: 特写、中景、远景" },
  { key: "cameraAngle", label: "机位角度", icon: Eye, placeholder: "如: 平视、俯拍、仰拍" },
  { key: "cameraMovement", label: "镜头运动", icon: Move, placeholder: "如: 推进、拉远、横摇" },
  { key: "lighting", label: "光线", icon: Lightbulb, placeholder: "如: 自然光、逆光、柔光" },
  { key: "colorGrading", label: "调色风格", icon: Palette, placeholder: "如: 暖色调、冷色调" },
  { key: "visualEffects", label: "视觉特效", icon: Wand2, placeholder: "如: 慢动作、叠加" },
];

// Audio description field config
const AUDIO_DESC_FIELDS = {
  bgm: [
    { key: "mood", label: "情绪", placeholder: "如: 紧张、欢快、悲伤" },
    { key: "genre", label: "风格", placeholder: "如: 管弦乐、电子音乐" },
    { key: "tempo", label: "节奏", placeholder: "如: 快速、中速、缓慢" },
  ],
};

// ============================================================
// Storyboard Detail Content - Optimized Default Layout
// ============================================================
function StoryboardDetailContent({
  data,
  scriptId,
  onUpdate,
  onStartEditing,
  onStopEditing,
  isReadOnly = false,
  getCachedCoverAssetId,
}: {
  data: StoryboardDetailDTO;
  scriptId?: string;
  onUpdate?: () => void;
  onStartEditing?: () => void;
  onStopEditing?: () => void;
  isReadOnly?: boolean;
  getCachedCoverAssetId: (entityId: string) => string | null;
}) {
  const locale = useLocale();
  const { currentWorkspaceId } = useWorkspace();
  const [isHeaderCollapsed, setIsHeaderCollapsed] = useState(false);
  const [isEditingInfo, setIsEditingInfo] = useState(false);
  const [isEditingVisual, setIsEditingVisual] = useState(false);
  const [isEditingAudio, setIsEditingAudio] = useState(false);
  const [isVisualCollapsed, setIsVisualCollapsed] = useState(true); // Collapsed by default
  const [isAudioCollapsed, setIsAudioCollapsed] = useState(true); // Collapsed by default
  const [isSaving, setIsSaving] = useState(false);
  const [isUploading, setIsUploading] = useState(false);
  const [isUploadingAsset, setIsUploadingAsset] = useState(false);
  const [showVersionPanel, setShowVersionPanel] = useState(false);
  const [showAddCharacterModal, setShowAddCharacterModal] = useState(false);
  const [showAddSceneModal, setShowAddSceneModal] = useState(false);
  const [showAddPropModal, setShowAddPropModal] = useState(false);

  // Available entities for adding
  const [availableCharacters, setAvailableCharacters] = useState<CharacterListDTO[]>([]);
  const [availableScenes, setAvailableScenes] = useState<SceneListDTO[]>([]);
  const [availableProps, setAvailableProps] = useState<PropListDTO[]>([]);

  // Version history
  const [versions, setVersions] = useState<VersionInfoDTO[]>([]);
  const [isLoadingVersions, setIsLoadingVersions] = useState(false);

  // Related assets
  const [relatedAssets, setRelatedAssets] = useState<EntityAssetRelationDTO[]>([]);
  const [isLoadingAssets, setIsLoadingAssets] = useState(false);
  const [assetFilter, setAssetFilter] = useState<"ALL" | "IMAGE" | "VIDEO" | "AUDIO">("ALL");

  // Edit form state
  const [editForm, setEditForm] = useState({
    title: data.title || "",
    synopsis: data.synopsis || "",
    duration: data.duration?.toString() || "",
  });

  // Visual/Audio edit state
  const [visualEdit, setVisualEdit] = useState({
    shotType: data.visualDesc?.shotType || "",
    cameraAngle: data.visualDesc?.cameraAngle || "",
    cameraMovement: data.visualDesc?.cameraMovement || "",
    lighting: data.visualDesc?.lighting || "",
    colorGrading: data.visualDesc?.colorGrading || "",
    visualEffects: data.visualDesc?.visualEffects || "",
  });

  const [audioEdit, setAudioEdit] = useState({
    bgmMood: data.audioDesc?.bgm?.mood || "",
    bgmGenre: data.audioDesc?.bgm?.genre || "",
    bgmTempo: data.audioDesc?.bgm?.tempo || "",
    narration: data.audioDesc?.narration || "",
  });

  // Sync with data changes
  useEffect(() => {
    setEditForm({
      title: data.title || "",
      synopsis: data.synopsis || "",
      duration: data.duration?.toString() || "",
    });
    setVisualEdit({
      shotType: data.visualDesc?.shotType || "",
      cameraAngle: data.visualDesc?.cameraAngle || "",
      cameraMovement: data.visualDesc?.cameraMovement || "",
      lighting: data.visualDesc?.lighting || "",
      colorGrading: data.visualDesc?.colorGrading || "",
      visualEffects: data.visualDesc?.visualEffects || "",
    });
    setAudioEdit({
      bgmMood: data.audioDesc?.bgm?.mood || "",
      bgmGenre: data.audioDesc?.bgm?.genre || "",
      bgmTempo: data.audioDesc?.bgm?.tempo || "",
      narration: data.audioDesc?.narration || "",
    });
  }, [data]);

  // Fetch related assets when data changes
  useEffect(() => {
    const fetchRelatedAssets = async () => {
      if (!currentWorkspaceId || !data.id) return;
      try {
        setIsLoadingAssets(true);
        const assets = await projectService.getEntityAssetRelations( "STORYBOARD", data.id);
        setRelatedAssets(assets);
      } catch (err) {
        console.error("Failed to fetch related assets:", err);
        toast.danger(getErrorFromException(err, locale));
      } finally {
        setIsLoadingAssets(false);
      }
    };
    fetchRelatedAssets();
  }, [currentWorkspaceId, data.id]);

  // Handle asset changes via WebSocket
  useEntityChanges(
    (action, entityType, entityId, wsData) => {
      if (entityType !== "ASSET") return;

      const entity = (wsData as { entity?: Partial<AssetListDTO> }).entity;

      switch (action) {
        case "CREATED": {
          if (!currentWorkspaceId || !data.id) return;
          projectService
            .getEntityAssetRelations("STORYBOARD", data.id)
            .then(setRelatedAssets)
            .catch((err) => { console.error("Failed to refresh assets after WS CREATED:", err); toast.danger(getErrorFromException(err, locale)); });
          break;
        }
        case "UPDATED": {
          if (!entity) return;
          setRelatedAssets((prev) => {
            const idx = prev.findIndex((r) => r.asset?.id === entityId);
            if (idx === -1) return prev;
            const updated = [...prev];
            updated[idx] = {
              ...updated[idx],
              asset: { ...updated[idx].asset, ...entity } as AssetListDTO,
            };
            return updated;
          });
          break;
        }
        case "DELETED": {
          setRelatedAssets((prev) => prev.filter((r) => r.asset?.id !== entityId));
          break;
        }
      }
    },
    [currentWorkspaceId, data.id]
  );

  // Fetch available entities when modals open
  useEffect(() => {
    const fetchAvailableEntities = async () => {
      if (!currentWorkspaceId || !scriptId) return;
      try {
        if (showAddCharacterModal && availableCharacters.length === 0) {
          const chars = await projectService.getCharactersByScript( scriptId);
          setAvailableCharacters(chars);
        }
        if (showAddSceneModal && availableScenes.length === 0) {
          const scenes = await projectService.getScenesByScript( scriptId);
          setAvailableScenes(scenes);
        }
        if (showAddPropModal && availableProps.length === 0) {
          const props = await projectService.getPropsByScript( scriptId);
          setAvailableProps(props);
        }
      } catch (err) {
        console.error("Failed to fetch available entities:", err);
        toast.danger(getErrorFromException(err, locale));
      }
    };
    fetchAvailableEntities();
  }, [currentWorkspaceId, scriptId, showAddCharacterModal, showAddSceneModal, showAddPropModal, availableCharacters.length, availableScenes.length, availableProps.length]);

  // Fetch versions
  const fetchVersions = async () => {
    if (!currentWorkspaceId) return;
    try {
      setIsLoadingVersions(true);
      const vers = await projectService.getStoryboardVersions( data.id);
      setVersions(vers);
    } catch (err) {
      console.error("Failed to fetch versions:", err);
      toast.danger(getErrorFromException(err, locale));
    } finally {
      setIsLoadingVersions(false);
    }
  };

  // Save basic info
  const handleSaveInfo = async () => {
    if (!currentWorkspaceId) return;
    try {
      setIsSaving(true);
      await projectService.updateStoryboard( data.id, {
        title: editForm.title || undefined,
        synopsis: editForm.synopsis || undefined,
        duration: editForm.duration ? parseInt(editForm.duration) : undefined,
      });
      setIsEditingInfo(false);
      onStopEditing?.();
      onUpdate?.();
    } catch (err) {
      console.error("Failed to update storyboard:", err);
      toast.danger(getErrorFromException(err, locale));
    } finally {
      setIsSaving(false);
    }
  };

  // Save visual description
  const handleSaveVisual = async () => {
    if (!currentWorkspaceId) return;
    try {
      setIsSaving(true);
      await projectService.updateStoryboard( data.id, {
        visualDesc: {
          shotType: visualEdit.shotType || null,
          cameraAngle: visualEdit.cameraAngle || null,
          cameraMovement: visualEdit.cameraMovement || null,
          lighting: visualEdit.lighting || null,
          colorGrading: visualEdit.colorGrading || null,
          visualEffects: visualEdit.visualEffects || null,
          transition: data.visualDesc?.transition || null,
        },
      });
      setIsEditingVisual(false);
      onStopEditing?.();
      onUpdate?.();
    } catch (err) {
      console.error("Failed to update visual desc:", err);
      toast.danger(getErrorFromException(err, locale));
    } finally {
      setIsSaving(false);
    }
  };

  // Save audio description
  const handleSaveAudio = async () => {
    if (!currentWorkspaceId) return;
    try {
      setIsSaving(true);
      await projectService.updateStoryboard( data.id, {
        audioDesc: {
          bgm: {
            mood: audioEdit.bgmMood || null,
            genre: audioEdit.bgmGenre || null,
            tempo: audioEdit.bgmTempo || null,
          },
          narration: audioEdit.narration || null,
          soundEffects: data.audioDesc?.soundEffects || [],
        },
      });
      setIsEditingAudio(false);
      onStopEditing?.();
      onUpdate?.();
    } catch (err) {
      console.error("Failed to update audio desc:", err);
      toast.danger(getErrorFromException(err, locale));
    } finally {
      setIsSaving(false);
    }
  };

  // Add character
  const handleAddCharacter = async (characterId: string) => {
    if (!currentWorkspaceId) return;
    try {
      await projectService.addStoryboardCharacter( data.id, { characterId });
      setShowAddCharacterModal(false);
      onUpdate?.();
    } catch (err) {
      console.error("Failed to add character:", err);
      toast.danger(getErrorFromException(err, locale));
    }
  };

  // Remove character
  const handleRemoveCharacter = async (relationId: string) => {
    if (!currentWorkspaceId) return;
    try {
      await projectService.removeStoryboardCharacter( data.id, relationId);
      onUpdate?.();
    } catch (err) {
      console.error("Failed to remove character:", err);
      toast.danger(getErrorFromException(err, locale));
    }
  };

  // Set scene
  const handleSetScene = async (sceneId: string) => {
    if (!currentWorkspaceId) return;
    try {
      await projectService.setStoryboardScene( data.id, { sceneId });
      setShowAddSceneModal(false);
      onUpdate?.();
    } catch (err) {
      console.error("Failed to set scene:", err);
      toast.danger(getErrorFromException(err, locale));
    }
  };

  // Remove scene
  const handleRemoveScene = async () => {
    if (!currentWorkspaceId || !data.scene?.relationId) return;
    try {
      await projectService.removeStoryboardScene( data.id, data.scene.relationId);
      onUpdate?.();
    } catch (err) {
      console.error("Failed to remove scene:", err);
      toast.danger(getErrorFromException(err, locale));
    }
  };

  // Add prop
  const handleAddProp = async (propId: string) => {
    if (!currentWorkspaceId) return;
    try {
      await projectService.addStoryboardProp( data.id, { propId });
      setShowAddPropModal(false);
      onUpdate?.();
    } catch (err) {
      console.error("Failed to add prop:", err);
      toast.danger(getErrorFromException(err, locale));
    }
  };

  // Remove prop
  const handleRemoveProp = async (relationId: string) => {
    if (!currentWorkspaceId) return;
    try {
      await projectService.removeStoryboardProp( data.id, relationId);
      onUpdate?.();
    } catch (err) {
      console.error("Failed to remove prop:", err);
      toast.danger(getErrorFromException(err, locale));
    }
  };

  // Cover upload
  const handleCoverUpload = async (file: File) => {
    if (!currentWorkspaceId) return;
    try {
      setIsUploading(true);
      const initResponse = await projectService.initAssetUpload( {
        name: file.name,
        fileName: file.name,
        mimeType: file.type,
        fileSize: file.size,
        scope: "SCRIPT",
        scriptId: scriptId,
        description: "分镜封面图",
      });
      await fetch(initResponse.uploadUrl, {
        method: initResponse.method,
        headers: initResponse.headers,
        body: file,
      });
      await projectService.confirmAssetUpload( initResponse.assetId, {
        fileKey: initResponse.fileKey,
        actualFileSize: file.size,
      });
      await projectService.updateStoryboard( data.id, {
        coverAssetId: initResponse.assetId,
      });
      onUpdate?.();
    } catch (err) {
      console.error("Failed to upload cover:", err);
      toast.danger(getErrorFromException(err, locale));
    } finally {
      setIsUploading(false);
    }
  };

  const handleCoverDelete = async () => {
    if (!currentWorkspaceId) return;
    try {
      await projectService.updateStoryboard( data.id, {
        coverAssetId: null,
      });
      onUpdate?.();
    } catch (err) {
      console.error("Failed to delete cover:", err);
      toast.danger(getErrorFromException(err, locale));
    }
  };

  // Asset upload ref
  const assetFileInputRef = useRef<HTMLInputElement>(null);

  // Upload asset and create relation (supports batch)
  const handleAssetUpload = async (files: FileList | File[]) => {
    if (!currentWorkspaceId || !scriptId) return;
    const fileArray = Array.from(files);
    if (fileArray.length === 0) return;

    try {
      setIsUploadingAsset(true);

      // Upload all files in parallel
      await Promise.all(
        fileArray.map(async (file) => {
          // Determine asset type from mime type
          let assetType: "IMAGE" | "VIDEO" | "AUDIO" = "IMAGE";
          if (file.type.startsWith("video/")) {
            assetType = "VIDEO";
          } else if (file.type.startsWith("audio/")) {
            assetType = "AUDIO";
          }

          // 1. Init upload
          const initResponse = await projectService.initAssetUpload( {
            name: file.name,
            fileName: file.name,
            mimeType: file.type,
            fileSize: file.size,
            scope: "SCRIPT",
            scriptId: scriptId,
            description: `分镜素材 - ${assetType}`,
          });

          // 2. Upload to storage
          const uploadResponse = await fetch(initResponse.uploadUrl, {
            method: initResponse.method,
            headers: initResponse.headers,
            body: file,
          });

          if (!uploadResponse.ok) {
            throw new Error(`Upload failed with status: ${uploadResponse.status}`);
          }

          // 3. Confirm upload
          await projectService.confirmAssetUpload( initResponse.assetId, {
            fileKey: initResponse.fileKey,
            actualFileSize: file.size,
          });

          // 4. Create entity-asset relation
          await projectService.createEntityAssetRelation( {
            entityType: "STORYBOARD",
            entityId: data.id,
            assetId: initResponse.assetId,
            relationType: "DRAFT",
          });
        })
      );

      // Refresh assets
      const assets = await projectService.getEntityAssetRelations( "STORYBOARD", data.id);
      setRelatedAssets(assets);
    } catch (err) {
      console.error("Failed to upload asset:", err);
      toast.danger(getErrorFromException(err, locale));
    } finally {
      setIsUploadingAsset(false);
      // Reset file input
      if (assetFileInputRef.current) {
        assetFileInputRef.current.value = "";
      }
    }
  };

  // Delete asset relation (also delete failed assets)
  const handleDeleteAssetRelation = async (relationId: string) => {
    if (!currentWorkspaceId) return;
    try {
      const relation = relatedAssets.find((r) => r.id === relationId);
      await projectService.deleteEntityAssetRelation( relationId);
      if (relation?.asset?.generationStatus === "FAILED") {
        await projectService.deleteAsset( relation.asset.id);
      }
      setRelatedAssets((prev) => prev.filter((r) => r.id !== relationId));
    } catch (err) {
      console.error("Failed to delete asset relation:", err);
      toast.danger(getErrorFromException(err, locale));
    }
  };

  // Save edited image from image editor
  const handleSaveEditedImage = async (relation: EntityAssetRelationDTO, dataUrl: string) => {
    if (!currentWorkspaceId || !scriptId) return;
    try {
      // Convert dataUrl to Blob
      const response = await fetch(dataUrl);
      const blob = await response.blob();
      const file = new File([blob], `edited_${relation.asset.name || "image"}.png`, { type: "image/png" });

      // Upload using the same flow as handleAssetUpload
      const initResponse = await projectService.initAssetUpload( {
        name: file.name,
        fileName: file.name,
        mimeType: file.type,
        fileSize: file.size,
        scope: "SCRIPT",
        scriptId: scriptId,
        description: "编辑后的分镜素材",
      });

      await fetch(initResponse.uploadUrl, {
        method: initResponse.method,
        headers: initResponse.headers,
        body: file,
      });

      await projectService.confirmAssetUpload( initResponse.assetId, {
        fileKey: initResponse.fileKey,
        actualFileSize: file.size,
      });

      await projectService.createEntityAssetRelation( {
        entityType: "STORYBOARD",
        entityId: data.id,
        assetId: initResponse.assetId,
        relationType: "DRAFT",
      });

      // Refresh assets
      const assets = await projectService.getEntityAssetRelations( "STORYBOARD", data.id);
      setRelatedAssets(assets);
    } catch (err) {
      console.error("Failed to save edited image:", err);
      toast.danger(getErrorFromException(err, locale));
    }
  };

  // Filter assets
  const filteredAssets = relatedAssets.filter((r) => {
    if (!r.asset) return false;
    if (assetFilter === "ALL") return true;
    return r.asset.assetType === assetFilter;
  });

  // Drag handlers for assets
  const { startDrag, endDrag } = useDragDropActions();

  const handleAssetDragStart = (e: React.DragEvent, relation: EntityAssetRelationDTO) => {
    const dragData = createAssetDragData({
      assetId: relation.asset.id,
      url: relation.asset.fileUrl,
      name: relation.asset.name,
      mimeType: relation.asset.mimeType,
      fileSize: relation.asset.fileSize,
      assetType: relation.asset.assetType,
    });
    e.dataTransfer.setData(ASSET_DRAG_TYPE, JSON.stringify(dragData));
    e.dataTransfer.effectAllowed = "copy";
    startDrag(dragData);
  };

  const handleAssetDragEnd = () => {
    endDrag();
  };

  // Drag handler for scene cover
  const handleSceneDragStart = (e: React.DragEvent) => {
    if (!data.scene?.coverUrl) return;
    const coverAssetId = getCachedCoverAssetId(data.scene.sceneId);
    if (!coverAssetId) {
      e.preventDefault();
      return;
    }
    const dragData = createAssetDragData({
      assetId: coverAssetId,
      url: data.scene.coverUrl,
      name: data.scene.sceneName,
      mimeType: "image/*",
      fileSize: 0,
      assetType: "IMAGE",
    });
    e.dataTransfer.setData(ASSET_DRAG_TYPE, JSON.stringify(dragData));
    e.dataTransfer.effectAllowed = "copy";
    startDrag(dragData);
  };

  // Drag handler for character cover
  const handleCharacterDragStart = (e: React.DragEvent, char: typeof data.characters[0]) => {
    if (!char.coverUrl) return;
    const coverAssetId = getCachedCoverAssetId(char.characterId);
    if (!coverAssetId) {
      e.preventDefault();
      return;
    }
    const dragData = createAssetDragData({
      assetId: coverAssetId,
      url: char.coverUrl,
      name: char.characterName,
      mimeType: "image/*",
      fileSize: 0,
      assetType: "IMAGE",
    });
    e.dataTransfer.setData(ASSET_DRAG_TYPE, JSON.stringify(dragData));
    e.dataTransfer.effectAllowed = "copy";
    startDrag(dragData);
  };

  // Drag handler for prop cover
  const handlePropDragStart = (e: React.DragEvent, prop: typeof data.props[0]) => {
    if (!prop.coverUrl) return;
    const coverAssetId = getCachedCoverAssetId(prop.propId);
    if (!coverAssetId) {
      e.preventDefault();
      return;
    }
    const dragData = createAssetDragData({
      assetId: coverAssetId,
      url: prop.coverUrl,
      name: prop.propName,
      mimeType: "image/*",
      fileSize: 0,
      assetType: "IMAGE",
    });
    e.dataTransfer.setData(ASSET_DRAG_TYPE, JSON.stringify(dragData));
    e.dataTransfer.effectAllowed = "copy";
    startDrag(dragData);
  };

  // Restore version
  const [restoringVersion, setRestoringVersion] = useState<number | null>(null);
  const handleRestoreVersion = async (versionNumber: number) => {
    if (!currentWorkspaceId) return;
    setRestoringVersion(versionNumber);
    try {
      await projectService.restoreStoryboardVersion( data.id, {
        versionNumber,
        reason: `恢复到版本 ${versionNumber}`,
      });
      setShowVersionPanel(false);
      onUpdate?.();
    } catch (err) {
      console.error("Failed to restore version:", err);
      toast.danger(getErrorFromException(err, locale));
    } finally {
      setRestoringVersion(null);
    }
  };

  // Already added IDs
  const addedCharacterIds = new Set(data.characters.map((c) => c.characterId));
  const addedPropIds = new Set(data.props.map((p) => p.propId));

  return (
    <div className="relative flex h-full flex-col gap-3">
      {/* Header Card - Collapsible (similar to Episode) */}
      <div className="shrink-0 rounded-2xl bg-muted/5 transition-all duration-300">
        {/* Collapsed Header Bar */}
        <div
          className={`flex items-center justify-between gap-3 px-4 py-3 transition-all ${
            isHeaderCollapsed ? "" : "border-b border-muted/10"
          }`}
        >
          <div
            className="flex min-w-0 flex-1 cursor-pointer items-center gap-3"
            onClick={() => !isEditingInfo && setIsHeaderCollapsed(!isHeaderCollapsed)}
          >
            {isHeaderCollapsed && data.coverUrl && (
              <div className="relative h-8 w-14 shrink-0 overflow-hidden rounded-lg">
                <NextImage src={data.coverUrl} alt="封面" fill className="object-cover" sizes="120px" />
              </div>
            )}
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-2">
                <span className="flex size-5 shrink-0 items-center justify-center rounded bg-accent/10 text-[10px] font-bold text-accent">
                  {data.sequence}
                </span>
                <h2 className="truncate text-sm font-semibold">{data.title || `分镜 ${data.sequence}`}</h2>
                <StatusBadge status={data.status} />
              </div>
              {isHeaderCollapsed && (
                <p className="mt-0.5 truncate text-xs text-muted">
                  {[
                    data.duration && `${data.duration}秒`,
                    data.scene?.sceneName,
                    data.characters.length > 0 && `${data.characters.length}个角色`,
                  ]
                    .filter(Boolean)
                    .join(" · ")}
                </p>
              )}
            </div>
          </div>
          <div className="flex shrink-0 items-center gap-1">
            {!isHeaderCollapsed && (
              <>
                <Tooltip delay={0}>
                  <Button
                    variant="ghost"
                    size="sm"
                    isIconOnly
                    className="size-7"
                    onPress={() => onUpdate?.()}
                  >
                    <RefreshCw className="size-3.5" />
                  </Button>
                  <Tooltip.Content>刷新数据</Tooltip.Content>
                </Tooltip>
                <Tooltip delay={0}>
                  <Button
                    variant="ghost"
                    size="sm"
                    isIconOnly
                    className="size-7"
                    onPress={() => {
                      setShowVersionPanel(true);
                      fetchVersions();
                    }}
                  >
                    <History className="size-3.5" />
                  </Button>
                  <Tooltip.Content>版本历史</Tooltip.Content>
                </Tooltip>
                <Tooltip delay={0}>
                  <Button
                    variant="ghost"
                    size="sm"
                    isIconOnly
                    className="size-7"
                    isDisabled={isReadOnly}
                    onPress={() => {
                      setIsEditingInfo(true);
                      onStartEditing?.();
                    }}
                  >
                    <Edit3 className="size-3.5" />
                  </Button>
                  <Tooltip.Content>{isReadOnly ? "只读模式" : "编辑信息"}</Tooltip.Content>
                </Tooltip>
              </>
            )}
            <Button
              variant="ghost"
              size="sm"
              isIconOnly
              className="size-7"
              isDisabled={isEditingInfo}
              onPress={() => setIsHeaderCollapsed(!isHeaderCollapsed)}
            >
              {isHeaderCollapsed ? <ChevronDown className="size-4" /> : <ChevronUp className="size-4" />}
            </Button>
          </div>
        </div>

        {/* Expandable Content */}
        {!isHeaderCollapsed && (
          <div className="flex gap-4 p-4 pt-3">
            <CoverImageCard
              coverUrl={data.coverUrl}
              onUpload={handleCoverUpload}
              onDelete={handleCoverDelete}
              isEditing={isEditingInfo}
              isUploading={isUploading}
            />

            <div className="min-w-0 flex-1">
              {isEditingInfo ? (
                <div className="space-y-3">
                  <div className="space-y-1">
                    <Label className="text-xs text-muted">标题</Label>
                    <Input
                      variant="secondary"
                      value={editForm.title}
                      onChange={(e) => setEditForm((prev) => ({ ...prev, title: e.target.value }))}
                      className="w-full"
                      placeholder="输入分镜标题"
                    />
                  </div>
                  <div className="flex gap-3">
                    <div className="flex-1 space-y-1">
                      <Label className="text-xs text-muted">简介</Label>
                      <Input
                        variant="secondary"
                        value={editForm.synopsis}
                        onChange={(e) => setEditForm((prev) => ({ ...prev, synopsis: e.target.value }))}
                        className="w-full"
                        placeholder="输入分镜简介"
                      />
                    </div>
                    <div className="w-24 space-y-1">
                      <Label className="text-xs text-muted">时长(秒)</Label>
                      <Input
                        variant="secondary"
                        type="number"
                        value={editForm.duration}
                        onChange={(e) => setEditForm((prev) => ({ ...prev, duration: e.target.value }))}
                        className="w-full"
                        placeholder="秒"
                        min="0"
                      />
                    </div>
                  </div>
                  <div className="flex justify-end gap-2">
                    <Button variant="ghost" size="sm" onPress={() => {
                      setIsEditingInfo(false);
                      onStopEditing?.();
                    }} isDisabled={isSaving}>
                      取消
                    </Button>
                    <Button variant="tertiary" size="sm" className="gap-1.5" onPress={handleSaveInfo} isDisabled={isSaving}>
                      {isSaving ? <Loader2 className="size-3.5 animate-spin" /> : null}
                      保存
                    </Button>
                  </div>
                </div>
              ) : (
                <>
                  <div className="flex flex-wrap gap-2 text-xs">
                    {data.duration && (
                      <span className="inline-flex items-center gap-1.5 rounded-full bg-muted/10 px-2.5 py-1">
                        <Clock className="size-3 text-accent" />
                        {data.duration} 秒
                      </span>
                    )}
                    {data.styleName && (
                      <span className="inline-flex items-center gap-1.5 rounded-full bg-accent/10 px-2.5 py-1 text-accent">
                        <Sparkles className="size-3" />
                        {data.styleName}
                      </span>
                    )}
                  </div>
                  {data.synopsis && <p className="mt-2 text-sm text-muted">{data.synopsis}</p>}
                  <div className="mt-auto flex flex-wrap items-center gap-x-4 gap-y-1 pt-3 text-[11px] text-muted/70">
                    <span>创建: {formatDate(data.createdAt)}</span>
                    <span>更新: {formatDate(data.updatedAt)}</span>
                    <span>版本: v{data.versionNumber}</span>
                  </div>
                </>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Scrollable Content */}
      <ScrollShadow className="min-h-0 flex-1" hideScrollBar>
        <div className="space-y-4">
          {/* Scene / Characters / Props Row - 16:9 Cards */}
          <div className="grid grid-cols-3 gap-3">
            {/* Scene Card */}
            <div className="rounded-xl bg-muted/5 p-3">
              <div className="mb-2 flex items-center justify-between">
                <h4 className="flex items-center gap-1.5 text-xs font-semibold uppercase text-muted">
                  <MapPin className="size-3.5 text-accent" />
                  场景
                </h4>
                {data.scene ? (
                  <Button variant="ghost" size="sm" isIconOnly className="size-6" onPress={handleRemoveScene}>
                    <Trash2 className="size-3 text-danger" />
                  </Button>
                ) : (
                  <Button variant="ghost" size="sm" isIconOnly className="size-6" onPress={() => setShowAddSceneModal(true)}>
                    <Plus className="size-3" />
                  </Button>
                )}
              </div>
              <div className="aspect-video overflow-hidden rounded-lg bg-muted/10">
                {data.scene ? (
                  <div
                    className={`relative size-full ${data.scene.coverUrl ? "cursor-grab active:cursor-grabbing" : ""}`}
                    draggable={!!data.scene.coverUrl}
                    onDragStart={handleSceneDragStart}
                    onDragEnd={handleAssetDragEnd}
                  >
                    {data.scene.coverUrl ? (
                      <>
                        <NextImage src={data.scene.coverUrl} alt="" fill className="object-cover" sizes="(min-width: 768px) 33vw, 50vw" />
                        {/* Drag handle indicator */}
                        <div className="absolute left-1 top-1 z-10 rounded bg-black/50 p-0.5 opacity-0 transition-opacity group-hover:opacity-100">
                          <GripVertical className="size-3 text-white" />
                        </div>
                      </>
                    ) : (
                      <div className="flex size-full items-center justify-center bg-gradient-to-br from-emerald-500/10 to-teal-500/10">
                        <MapPin className="size-8 text-emerald-500/30" />
                      </div>
                    )}
                    <div className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/70 to-transparent p-2 pt-4">
                      <p className="truncate text-xs font-medium text-white">{data.scene.sceneName}</p>
                    </div>
                  </div>
                ) : (
                  <button
                    className="flex size-full flex-col items-center justify-center gap-1 text-muted/40 transition-colors hover:bg-muted/20 hover:text-muted"
                    onClick={() => setShowAddSceneModal(true)}
                  >
                    <Plus className="size-5" />
                    <span className="text-[10px]">添加场景</span>
                  </button>
                )}
              </div>
            </div>

            {/* Characters Card */}
            <div className="rounded-xl bg-muted/5 p-3">
              <div className="mb-2 flex items-center justify-between">
                <h4 className="flex items-center gap-1.5 text-xs font-semibold uppercase text-muted">
                  <Users className="size-3.5 text-accent" />
                  角色 ({data.characters.length})
                </h4>
                <Button variant="ghost" size="sm" isIconOnly className="size-6" onPress={() => setShowAddCharacterModal(true)}>
                  <Plus className="size-3" />
                </Button>
              </div>
              <div className="aspect-video overflow-hidden rounded-lg bg-muted/10">
                {data.characters.length > 0 ? (
                  <div className="relative size-full">
                    {/* Grid of character avatars */}
                    <div className="grid size-full grid-cols-3 gap-0.5 p-1">
                      {data.characters.slice(0, 6).map((char) => (
                        <div
                          key={char.relationId}
                          className={`group relative aspect-square overflow-hidden rounded ${char.coverUrl ? "cursor-grab active:cursor-grabbing" : ""}`}
                          draggable={!!char.coverUrl}
                          onDragStart={(e) => handleCharacterDragStart(e, char)}
                          onDragEnd={handleAssetDragEnd}
                        >
                          {char.coverUrl ? (
                            <>
                              <NextImage src={char.coverUrl} alt="" fill className="object-cover" sizes="120px" />
                              {/* Drag handle indicator */}
                              <div className="absolute left-0.5 top-0.5 z-10 rounded bg-black/50 p-0.5 opacity-0 transition-opacity group-hover:opacity-100">
                                <GripVertical className="size-2 text-white" />
                              </div>
                            </>
                          ) : (
                            <div className="flex size-full items-center justify-center bg-accent/10 text-xs font-medium text-accent">
                              {char.characterName?.charAt(0) || "?"}
                            </div>
                          )}
                          <button
                            className="absolute inset-0 flex items-center justify-center bg-black/50 opacity-0 transition-opacity group-hover:opacity-100"
                            onClick={() => handleRemoveCharacter(char.relationId)}
                          >
                            <Trash2 className="size-3 text-white" />
                          </button>
                        </div>
                      ))}
                    </div>
                    {data.characters.length > 6 && (
                      <div className="absolute bottom-1 right-1 rounded bg-black/60 px-1.5 py-0.5 text-[10px] text-white">
                        +{data.characters.length - 6}
                      </div>
                    )}
                  </div>
                ) : (
                  <button
                    className="flex size-full flex-col items-center justify-center gap-1 text-muted/40 transition-colors hover:bg-muted/20 hover:text-muted"
                    onClick={() => setShowAddCharacterModal(true)}
                  >
                    <Plus className="size-5" />
                    <span className="text-[10px]">添加角色</span>
                  </button>
                )}
              </div>
            </div>

            {/* Props Card */}
            <div className="rounded-xl bg-muted/5 p-3">
              <div className="mb-2 flex items-center justify-between">
                <h4 className="flex items-center gap-1.5 text-xs font-semibold uppercase text-muted">
                  <Package className="size-3.5 text-accent" />
                  道具 ({data.props.length})
                </h4>
                <Button variant="ghost" size="sm" isIconOnly className="size-6" onPress={() => setShowAddPropModal(true)}>
                  <Plus className="size-3" />
                </Button>
              </div>
              <div className="aspect-video overflow-hidden rounded-lg bg-muted/10">
                {data.props.length > 0 ? (
                  <div className="relative size-full">
                    <div className="grid size-full grid-cols-3 gap-0.5 p-1">
                      {data.props.slice(0, 6).map((prop) => (
                        <div
                          key={prop.relationId}
                          className={`group relative aspect-square overflow-hidden rounded ${prop.coverUrl ? "cursor-grab active:cursor-grabbing" : ""}`}
                          draggable={!!prop.coverUrl}
                          onDragStart={(e) => handlePropDragStart(e, prop)}
                          onDragEnd={handleAssetDragEnd}
                        >
                          {prop.coverUrl ? (
                            <>
                              <NextImage src={prop.coverUrl} alt="" fill className="object-cover" sizes="120px" />
                              {/* Drag handle indicator */}
                              <div className="absolute left-0.5 top-0.5 z-10 rounded bg-black/50 p-0.5 opacity-0 transition-opacity group-hover:opacity-100">
                                <GripVertical className="size-2 text-white" />
                              </div>
                            </>
                          ) : (
                            <div className="flex size-full items-center justify-center bg-amber-500/10">
                              <Package className="size-3 text-amber-500/50" />
                            </div>
                          )}
                          <button
                            className="absolute inset-0 flex items-center justify-center bg-black/50 opacity-0 transition-opacity group-hover:opacity-100"
                            onClick={() => handleRemoveProp(prop.relationId)}
                          >
                            <Trash2 className="size-3 text-white" />
                          </button>
                        </div>
                      ))}
                    </div>
                    {data.props.length > 6 && (
                      <div className="absolute bottom-1 right-1 rounded bg-black/60 px-1.5 py-0.5 text-[10px] text-white">
                        +{data.props.length - 6}
                      </div>
                    )}
                  </div>
                ) : (
                  <button
                    className="flex size-full flex-col items-center justify-center gap-1 text-muted/40 transition-colors hover:bg-muted/20 hover:text-muted"
                    onClick={() => setShowAddPropModal(true)}
                  >
                    <Plus className="size-5" />
                    <span className="text-[10px]">添加道具</span>
                  </button>
                )}
              </div>
            </div>
          </div>

          {/* Related Assets Section */}
          <div className="rounded-xl bg-muted/5 p-4">
            {/* Hidden file input for asset upload (supports batch) */}
            <input
              ref={assetFileInputRef}
              type="file"
              accept="image/*,video/*,audio/*"
              multiple
              className="hidden"
              onChange={(e) => {
                if (e.target.files && e.target.files.length > 0) {
                  handleAssetUpload(e.target.files);
                }
              }}
            />

            {/* Header with filter and upload button */}
            <div className="mb-3 flex items-center justify-between">
              <div className="flex items-center gap-3">
                <h4 className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-muted">
                  <Image className="size-3.5 text-accent" />
                  相关素材 ({filteredAssets.length})
                </h4>
                {/* Filter buttons */}
                <ButtonGroup size="sm" variant="ghost" className="h-6">
                  {[
                    { key: "ALL" as const, label: "全部" },
                    { key: "IMAGE" as const, label: "图片" },
                    { key: "VIDEO" as const, label: "视频" },
                    { key: "AUDIO" as const, label: "音频" },
                  ].map(({ key, label }) => (
                    <Button
                      key={key}
                      variant={assetFilter === key ? "primary" : undefined}
                      className="h-6 min-w-0 px-2 text-[10px]"
                      onPress={() => setAssetFilter(key)}
                    >
                      {label}
                    </Button>
                  ))}
                </ButtonGroup>
              </div>
              <Button
                variant="ghost"
                size="sm"
                className="h-7 gap-1.5 px-2 text-xs"
                onPress={() => assetFileInputRef.current?.click()}
                isDisabled={isUploadingAsset}
              >
                {isUploadingAsset ? (
                  <Loader2 className="size-3.5 animate-spin" />
                ) : (
                  <Upload className="size-3.5" />
                )}
                上传素材
              </Button>
            </div>

            {/* Assets content */}
            {isLoadingAssets ? (
              <AssetsSectionSkeleton />
            ) : filteredAssets.length === 0 ? (
              <div className="flex h-20 flex-col items-center justify-center gap-1 text-muted/50">
                <Image className="size-6" />
                <span className="text-xs">暂无素材</span>
              </div>
            ) : (
              <div className="grid auto-rows-auto grid-cols-[repeat(auto-fill,minmax(300px,1fr))] gap-3">
                {filteredAssets.map((relation) => (
                  <AssetCard
                    key={relation.id}
                    relation={relation}
                    scriptId={scriptId}
                    workspaceId={currentWorkspaceId || undefined}
                    entityType="STORYBOARD"
                    entityId={data.id}
                    refImages={filteredAssets
                      .filter(r => r.asset && r.asset.assetType === "IMAGE" && r.asset.fileUrl)
                      .map(r => r.asset.fileUrl!)}
                    onDragStart={handleAssetDragStart}
                    onDragEnd={handleAssetDragEnd}
                    onDelete={handleDeleteAssetRelation}
                    onSave={handleSaveEditedImage}
                  />
                ))}
              </div>
            )}
          </div>

          {/* Visual Description - Collapsible */}
          <div className="rounded-xl bg-muted/5">
            <div
              className="flex cursor-pointer items-center justify-between p-4"
              onClick={() => !isEditingVisual && setIsVisualCollapsed(!isVisualCollapsed)}
            >
              <h4 className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-muted">
                <Camera className="size-3.5 text-accent" />
                视觉描述
                {isVisualCollapsed && (
                  <span className="ml-1 text-[10px] font-normal normal-case text-muted/50">
                    (点击展开)
                  </span>
                )}
              </h4>
              <div className="flex items-center gap-1">
                {!isVisualCollapsed && (
                  isEditingVisual ? (
                    <div className="flex gap-1" onClick={(e) => e.stopPropagation()}>
                      <Button variant="ghost" size="sm" className="h-6 px-2 text-xs" onPress={() => {
                        setIsEditingVisual(false);
                        onStopEditing?.();
                      }}>
                        取消
                      </Button>
                      <Button variant="tertiary" size="sm" className="h-6 px-2 text-xs" onPress={handleSaveVisual} isDisabled={isSaving}>
                        保存
                      </Button>
                    </div>
                  ) : (
                    <Button
                      variant="ghost"
                      size="sm"
                      isIconOnly
                      className="size-6"
                      isDisabled={isReadOnly}
                      onPress={(e) => {
                        setIsEditingVisual(true);
                        onStartEditing?.();
                      }}
                      onClick={(e) => e.stopPropagation()}
                    >
                      <Edit3 className="size-3" />
                    </Button>
                  )
                )}
                <Button variant="ghost" size="sm" isIconOnly className="size-6">
                  {isVisualCollapsed ? <ChevronDown className="size-3.5" /> : <ChevronUp className="size-3.5" />}
                </Button>
              </div>
            </div>
            {!isVisualCollapsed && (
              <div className="border-t border-muted/10 p-4 pt-3">
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                  {VISUAL_DESC_FIELDS.map(({ key, label, icon: Icon, placeholder }) => (
                    <div key={key} className="rounded-lg bg-background/50 p-2.5">
                      <div className="flex items-center gap-1.5 text-[10px] font-medium uppercase tracking-wide text-muted/70">
                        <Icon className="size-3" />
                        {label}
                      </div>
                      {isEditingVisual ? (
                        <Input
                          variant="secondary"
                          className="mt-1 h-7 text-xs"
                          placeholder={placeholder}
                          value={visualEdit[key as keyof typeof visualEdit]}
                          onChange={(e) => setVisualEdit((prev) => ({ ...prev, [key]: e.target.value }))}
                        />
                      ) : (
                        <p className="mt-1 text-sm text-foreground">
                          {(data.visualDesc?.[key as keyof typeof data.visualDesc] as string | null) || (
                            <span className="text-muted/40">未设置</span>
                          )}
                        </p>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>

          {/* Audio Description - Collapsible */}
          <div className="rounded-xl bg-muted/5">
            <div
              className="flex cursor-pointer items-center justify-between p-4"
              onClick={() => !isEditingAudio && setIsAudioCollapsed(!isAudioCollapsed)}
            >
              <h4 className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-muted">
                <Volume2 className="size-3.5 text-accent" />
                音频描述
                {isAudioCollapsed && (
                  <span className="ml-1 text-[10px] font-normal normal-case text-muted/50">
                    (点击展开)
                  </span>
                )}
              </h4>
              <div className="flex items-center gap-1">
                {!isAudioCollapsed && (
                  isEditingAudio ? (
                    <div className="flex gap-1" onClick={(e) => e.stopPropagation()}>
                      <Button variant="ghost" size="sm" className="h-6 px-2 text-xs" onPress={() => {
                        setIsEditingAudio(false);
                        onStopEditing?.();
                      }}>
                        取消
                      </Button>
                      <Button variant="tertiary" size="sm" className="h-6 px-2 text-xs" onPress={handleSaveAudio} isDisabled={isSaving}>
                        保存
                      </Button>
                    </div>
                  ) : (
                    <Button
                      variant="ghost"
                      size="sm"
                      isIconOnly
                      className="size-6"
                      isDisabled={isReadOnly}
                      onClick={(e) => e.stopPropagation()}
                      onPress={() => {
                        setIsEditingAudio(true);
                        onStartEditing?.();
                      }}
                    >
                      <Edit3 className="size-3" />
                    </Button>
                  )
                )}
                <Button variant="ghost" size="sm" isIconOnly className="size-6">
                  {isAudioCollapsed ? <ChevronDown className="size-3.5" /> : <ChevronUp className="size-3.5" />}
                </Button>
              </div>
            </div>
            {!isAudioCollapsed && (
              <div className="space-y-3 border-t border-muted/10 p-4 pt-3">
                {/* BGM */}
                <div className="rounded-lg bg-background/50 p-2.5">
                  <div className="flex items-center gap-1.5 text-[10px] font-medium uppercase tracking-wide text-muted/70">
                    <Music className="size-3" />
                    背景音乐 (BGM)
                  </div>
                  {isEditingAudio ? (
                    <div className="mt-2 grid grid-cols-3 gap-2">
                      {AUDIO_DESC_FIELDS.bgm.map(({ key, label, placeholder }) => (
                        <div key={key}>
                          <Label className="text-[10px] text-muted">{label}</Label>
                          <Input
                            variant="secondary"
                            className="mt-0.5 h-7 text-xs"
                            placeholder={placeholder}
                            value={audioEdit[`bgm${key.charAt(0).toUpperCase() + key.slice(1)}` as keyof typeof audioEdit]}
                            onChange={(e) =>
                              setAudioEdit((prev) => ({
                                ...prev,
                                [`bgm${key.charAt(0).toUpperCase() + key.slice(1)}`]: e.target.value,
                              }))
                            }
                          />
                        </div>
                      ))}
                    </div>
                  ) : (
                    <div className="mt-1.5 flex flex-wrap gap-2">
                      <span className="rounded-full bg-muted/10 px-2 py-0.5 text-xs">
                        情绪: {data.audioDesc?.bgm?.mood || <span className="text-muted/40">未设置</span>}
                      </span>
                      <span className="rounded-full bg-muted/10 px-2 py-0.5 text-xs">
                        风格: {data.audioDesc?.bgm?.genre || <span className="text-muted/40">未设置</span>}
                      </span>
                      <span className="rounded-full bg-muted/10 px-2 py-0.5 text-xs">
                        节奏: {data.audioDesc?.bgm?.tempo || <span className="text-muted/40">未设置</span>}
                      </span>
                    </div>
                  )}
                </div>

                {/* Narration */}
                <div className="rounded-lg bg-background/50 p-2.5">
                  <div className="flex items-center gap-1.5 text-[10px] font-medium uppercase tracking-wide text-muted/70">
                    <MessageSquare className="size-3" />
                    旁白
                  </div>
                  {isEditingAudio ? (
                    <TextArea
                      variant="secondary"
                      className="mt-1 min-h-16 resize-none text-xs"
                      placeholder="输入旁白内容"
                      value={audioEdit.narration}
                      onChange={(e) => setAudioEdit((prev) => ({ ...prev, narration: e.target.value }))}
                    />
                  ) : (
                    <p className="mt-1 text-sm">
                      {data.audioDesc?.narration || <span className="text-muted/40">未设置</span>}
                    </p>
                  )}
                </div>

                {/* Sound Effects */}
                <div className="rounded-lg bg-background/50 p-2.5">
                  <div className="flex items-center gap-1.5 text-[10px] font-medium uppercase tracking-wide text-muted/70">
                    <Volume2 className="size-3" />
                    音效
                  </div>
                  <div className="mt-1.5 flex flex-wrap gap-1.5">
                    {data.audioDesc?.soundEffects && data.audioDesc.soundEffects.length > 0 ? (
                      data.audioDesc.soundEffects.map((effect, idx) => (
                        <span key={idx} className="rounded-full bg-accent/10 px-2 py-0.5 text-xs text-accent">
                          {typeof effect === "string" ? effect : (effect as { description?: string }).description || JSON.stringify(effect)}
                        </span>
                      ))
                    ) : (
                      <span className="text-sm text-muted/40">未设置</span>
                    )}
                  </div>
                </div>
              </div>
            )}
          </div>

          {/* Dialogues Section */}
          {data.dialogues && data.dialogues.length > 0 && (
            <div className="rounded-xl bg-muted/5 p-4">
              <h4 className="mb-3 flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-muted">
                <MessageSquare className="size-3.5 text-accent" />
                对白 ({data.dialogues.length})
              </h4>
              <div className="space-y-2">
                {data.dialogues.map((dialogue, idx) => (
                  <div key={dialogue.id || idx} className="rounded-lg bg-background/50 p-3">
                    <div className="flex items-center gap-2">
                      {dialogue.characterName && <span className="font-medium text-accent">{dialogue.characterName}</span>}
                      {dialogue.emotion && (
                        <span className="rounded bg-muted/10 px-1.5 py-0.5 text-[10px] text-muted">{dialogue.emotion}</span>
                      )}
                      {dialogue.dialogueType && (
                        <span className="text-[10px] text-muted/70">[{dialogue.dialogueType}]</span>
                      )}
                    </div>
                    <p className="mt-1.5 text-sm leading-relaxed text-muted">{dialogue.content}</p>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </ScrollShadow>

      {/* Version Panel Overlay */}
      {showVersionPanel && (
        <div className="absolute inset-0 z-10 rounded-2xl bg-background/95 backdrop-blur">
          <div className="flex h-full flex-col">
            <div className="flex shrink-0 items-center justify-between border-b border-muted/10 px-4 py-3">
              <h3 className="text-sm font-semibold">版本历史</h3>
              <Button variant="ghost" size="sm" isIconOnly className="size-7" onPress={() => setShowVersionPanel(false)}>
                <X className="size-4" />
              </Button>
            </div>
            <ScrollShadow className="min-h-0 flex-1 p-4" hideScrollBar>
              {isLoadingVersions ? (
                <VersionListSkeleton />
              ) : versions.length > 0 ? (
                <div className="space-y-2">
                  {versions.map((version) => (
                    <div
                      key={version.id}
                      className={`rounded-xl p-3 ${
                        version.isCurrent ? "bg-accent/10 ring-1 ring-accent/30" : "bg-muted/5 hover:bg-muted/10"
                      }`}
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div>
                          <div className="flex items-center gap-2">
                            <span className="text-sm font-medium">v{version.versionNumber}</span>
                            {version.isCurrent && (
                              <span className="rounded-full bg-accent-soft-hover px-1.5 py-0.5 text-[10px] font-medium text-accent">
                                当前版本
                              </span>
                            )}
                          </div>
                          <p className="mt-1 text-xs text-muted">{version.changeSummary || "无变更说明"}</p>
                          <p className="mt-1 text-[11px] text-muted/70">{formatDate(version.createdAt)}</p>
                        </div>
                        {!version.isCurrent && (
                          <Button
                            variant="ghost"
                            size="sm"
                            className="shrink-0 gap-1 text-xs"
                            isPending={restoringVersion === version.versionNumber}
                            isDisabled={restoringVersion !== null}
                            onPress={() => handleRestoreVersion(version.versionNumber)}
                          >
                            {({isPending}) => (<>{isPending ? <Spinner color="current" size="sm" /> : null}恢复</>)}
                          </Button>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <div className="py-8 text-center text-sm text-muted">暂无版本历史</div>
              )}
            </ScrollShadow>
          </div>
        </div>
      )}

      {/* Add Character Modal */}
      <Modal.Backdrop isOpen={showAddCharacterModal} onOpenChange={setShowAddCharacterModal} variant="blur">
        <Modal.Container size="sm">
          <Modal.Dialog>
            <Modal.CloseTrigger />
            <Modal.Header>
              <Modal.Icon className="bg-accent/10 text-accent">
                <Users className="size-5" />
              </Modal.Icon>
              <Modal.Heading>添加角色</Modal.Heading>
            </Modal.Header>
            <Modal.Body>
              <div className="max-h-64 space-y-2 overflow-y-auto">
                {availableCharacters
                  .filter((c) => !addedCharacterIds.has(c.id))
                  .map((char) => (
                    <button
                      key={char.id}
                      className="flex w-full items-center gap-3 rounded-lg bg-muted/5 p-2.5 text-left transition-colors hover:bg-muted/10"
                      onClick={() => handleAddCharacter(char.id)}
                    >
                      {char.coverUrl ? (
                        <NextImage src={char.coverUrl} alt="" width={40} height={40} className="rounded-full object-cover" />
                      ) : (
                        <div className="flex size-10 items-center justify-center rounded-full bg-accent/10 text-sm font-medium text-accent">
                          {char.name?.charAt(0) || "?"}
                        </div>
                      )}
                      <div>
                        <p className="text-sm font-medium">{char.name}</p>
                        <p className="text-xs text-muted">{char.characterType || "未分类"}</p>
                      </div>
                    </button>
                  ))}
                {availableCharacters.filter((c) => !addedCharacterIds.has(c.id)).length === 0 && (
                  <p className="py-4 text-center text-sm text-muted">暂无可添加的角色</p>
                )}
              </div>
            </Modal.Body>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>

      {/* Add Scene Modal */}
      <Modal.Backdrop isOpen={showAddSceneModal} onOpenChange={setShowAddSceneModal} variant="blur">
        <Modal.Container size="sm">
          <Modal.Dialog>
            <Modal.CloseTrigger />
            <Modal.Header>
              <Modal.Icon className="bg-accent/10 text-accent">
                <MapPin className="size-5" />
              </Modal.Icon>
              <Modal.Heading>选择场景</Modal.Heading>
            </Modal.Header>
            <Modal.Body>
              <div className="max-h-64 space-y-2 overflow-y-auto">
                {availableScenes.map((scene) => (
                  <button
                    key={scene.id}
                    className="flex w-full items-center gap-3 rounded-lg bg-muted/5 p-2.5 text-left transition-colors hover:bg-muted/10"
                    onClick={() => handleSetScene(scene.id)}
                  >
                    {scene.coverUrl ? (
                      <NextImage src={scene.coverUrl} alt="" width={64} height={40} className="rounded object-cover" />
                    ) : (
                      <div className="flex h-10 w-16 items-center justify-center rounded bg-emerald-500/10">
                        <MapPin className="size-4 text-emerald-500/50" />
                      </div>
                    )}
                    <div>
                      <p className="text-sm font-medium">{scene.name}</p>
                      {scene.description && <p className="line-clamp-1 text-xs text-muted">{scene.description}</p>}
                    </div>
                  </button>
                ))}
                {availableScenes.length === 0 && <p className="py-4 text-center text-sm text-muted">暂无可用场景</p>}
              </div>
            </Modal.Body>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>

      {/* Add Prop Modal */}
      <Modal.Backdrop isOpen={showAddPropModal} onOpenChange={setShowAddPropModal} variant="blur">
        <Modal.Container size="sm">
          <Modal.Dialog>
            <Modal.CloseTrigger />
            <Modal.Header>
              <Modal.Icon className="bg-accent/10 text-accent">
                <Package className="size-5" />
              </Modal.Icon>
              <Modal.Heading>添加道具</Modal.Heading>
            </Modal.Header>
            <Modal.Body>
              <div className="max-h-64 space-y-2 overflow-y-auto">
                {availableProps
                  .filter((p) => !addedPropIds.has(p.id))
                  .map((prop) => (
                    <button
                      key={prop.id}
                      className="flex w-full items-center gap-3 rounded-lg bg-muted/5 p-2.5 text-left transition-colors hover:bg-muted/10"
                      onClick={() => handleAddProp(prop.id)}
                    >
                      {prop.coverUrl ? (
                        <NextImage src={prop.coverUrl} alt="" width={40} height={40} className="rounded object-cover" />
                      ) : (
                        <div className="flex size-10 items-center justify-center rounded bg-amber-500/10">
                          <Package className="size-4 text-amber-500/50" />
                        </div>
                      )}
                      <div>
                        <p className="text-sm font-medium">{prop.name}</p>
                        <p className="text-xs text-muted">{prop.propType || "未分类"}</p>
                      </div>
                    </button>
                  ))}
                {availableProps.filter((p) => !addedPropIds.has(p.id)).length === 0 && (
                  <p className="py-4 text-center text-sm text-muted">暂无可添加的道具</p>
                )}
              </div>
            </Modal.Body>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>
    </div>
  );
}

// Storyboard Detail View with sidebar
function StoryboardDetailView({
  items,
  selectedId,
  onSelect,
  onDelete,
  isSidebarCollapsed,
  onToggleSidebar,
  onRefresh,
  scriptId,
}: {
  items: EntityItem[];
  selectedId: string | null;
  onSelect: (id: string) => void;
  onDelete: (id: string) => void;
  isSidebarCollapsed: boolean;
  onToggleSidebar: () => void;
  onRefresh: () => void;
  scriptId: string;
}) {
  const locale = useLocale();
  const { currentWorkspaceId } = useWorkspace();
  const { updateEntity, activePanel } = useAIGeneration();
  const [storyboardData, setStoryboardData] = useState<StoryboardDetailDTO | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isReadOnlyMode, setIsReadOnlyMode] = useState(false);

  const selectedItem = items.find((item) => item.id === selectedId) || items[0];

  // Comment panel integration
  const openCommentPanel = useCommentPanelStore((s) => s.open);
  const setCommentTarget = useCommentPanelStore((s) => s.setTarget);
  const isCommentPanelOpen = useCommentPanelStore((s) => s.isOpen);

  useEffect(() => {
    if (!isCommentPanelOpen || !selectedItem) return;
    setCommentTarget({ type: "STORYBOARD", id: selectedItem.id, name: selectedItem.title, scriptId });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedItem?.id, isCommentPanelOpen, scriptId]);

  const handleCommentOpen = useCallback(
    (id: string) => {
      const item = items.find((i) => i.id === id);
      if (!item) return;
      openCommentPanel({ type: "STORYBOARD", id: item.id, name: item.title, scriptId });
    },
    [items, scriptId, openCommentPanel]
  );

  // Collaboration hook
  const {
    getEntityCollaborators,
    refreshEntityState,
    startEditing,
    stopEditing,
    focusEntity,
    blurEntity,
  } = useCollaboration(scriptId, "storyboards");

  // Use refs to avoid effect re-running due to function reference changes
  const focusEntityRef = useRef(focusEntity);
  const blurEntityRef = useRef(blurEntity);
  useEffect(() => {
    focusEntityRef.current = focusEntity;
    blurEntityRef.current = blurEntity;
  });

  // Get collaboration state for selected entity
  const collaborators = selectedItem?.id
    ? getEntityCollaborators("STORYBOARD", selectedItem.id)
    : { editor: null, lockedAt: null, isLockedByOther: false };

  // Focus entity when selection changes
  useEffect(() => {
    if (selectedItem?.id) {
      focusEntityRef.current("STORYBOARD", selectedItem.id);
    }
    return () => {
      if (selectedItem?.id) {
        blurEntityRef.current("STORYBOARD", selectedItem.id);
      }
    };
  }, [selectedItem?.id]);

  // Fetch storyboard detail when selection changes
  const fetchStoryboardDetail = useCallback(async () => {
    if (!currentWorkspaceId || !selectedItem?.id) return;

    try {
      setIsLoading(true);
      const data = await projectService.getStoryboard( selectedItem.id);
      setStoryboardData(data);
      setIsReadOnlyMode(false); // Reset read-only mode on new selection
    } catch (err) {
      console.error("Failed to fetch storyboard detail:", err);
      toast.danger(getErrorFromException(err, locale));
    } finally {
      setIsLoading(false);
    }
  }, [currentWorkspaceId, selectedItem?.id]);

  useEffect(() => {
    fetchStoryboardDetail();
  }, [fetchStoryboardDetail]);

  // Cache for cover assetIds to avoid redundant API calls
  const coverCacheRef = useRef<Map<string, string>>(new Map());

  const buildCoverEntityRefs = useCallback((detail: StoryboardDetailDTO): CoverEntityRef[] => {
    const entities: CoverEntityRef[] = [];

    if (detail.scene?.coverUrl) {
      entities.push({
        type: "SCENE",
        id: detail.scene.sceneId,
        name: detail.scene.sceneName,
        coverUrl: detail.scene.coverUrl,
      });
    }

    for (const char of detail.characters || []) {
      if (char.coverUrl) {
        entities.push({
          type: "CHARACTER",
          id: char.characterId,
          name: char.characterName,
          coverUrl: char.coverUrl,
        });
      }
    }

    for (const prop of detail.props || []) {
      if (prop.coverUrl) {
        entities.push({
          type: "PROP",
          id: prop.propId,
          name: prop.propName,
          coverUrl: prop.coverUrl,
        });
      }
    }

    return entities;
  }, []);

  const loadCoverAssetIds = useCallback(async (entities: CoverEntityRef[]): Promise<AssociatedCover[]> => {
    const covers: AssociatedCover[] = [];

    await Promise.all(
      entities.map(async (entity) => {
        try {
          const cached = coverCacheRef.current.get(entity.id);
          if (cached) {
            covers.push({
              entityType: entity.type,
              entityName: entity.name,
              coverAssetId: cached,
              coverUrl: entity.coverUrl,
            });
            return;
          }

          let coverAssetId: string | null = null;
          if (entity.type === "CHARACTER") {
            const detail = await projectService.getCharacter(entity.id);
            coverAssetId = detail.coverAssetId;
          } else if (entity.type === "SCENE") {
            const detail = await projectService.getScene(entity.id);
            coverAssetId = detail.coverAssetId;
          } else if (entity.type === "PROP") {
            const detail = await projectService.getProp(entity.id);
            coverAssetId = detail.coverAssetId;
          }

          if (coverAssetId) {
            coverCacheRef.current.set(entity.id, coverAssetId);
            covers.push({
              entityType: entity.type,
              entityName: entity.name,
              coverAssetId,
              coverUrl: entity.coverUrl,
            });
          }
        } catch (err) {
          console.error(`Failed to fetch cover for ${entity.type} ${entity.id}:`, err);
          toast.danger(getErrorFromException(err, locale));
        }
      }),
    );

    return covers;
  }, []);

  useEffect(() => {
    if (!storyboardData) return;
    const entitiesToFetch = buildCoverEntityRefs(storyboardData);
    if (entitiesToFetch.length === 0) return;
    void loadCoverAssetIds(entitiesToFetch);
  }, [storyboardData, buildCoverEntityRefs, loadCoverAssetIds]);

  // Auto-sync with AI generation panel when storyboard changes
  useEffect(() => {
    if (activePanel !== "ai-generation" || !storyboardData) return;

    // Build a human-readable description for AI generation
    const parts: string[] = [];

    // Add scene info
    if (storyboardData.scene?.sceneName) {
      parts.push(`场景: ${storyboardData.scene.sceneName}`);
    }

    // Add characters info
    if (storyboardData.characters && storyboardData.characters.length > 0) {
      const charNames = storyboardData.characters.map(c => c.characterName).join("、");
      parts.push(`角色: ${charNames}`);
    }

    // Add props info
    if (storyboardData.props && storyboardData.props.length > 0) {
      const propNames = storyboardData.props.map(p => p.propName).join("、");
      parts.push(`道具: ${propNames}`);
    }

    const baseEntity = {
      entityType: "STORYBOARD" as const,
      entityId: storyboardData.id,
      entityName: storyboardData.title || `分镜 ${storyboardData.sequence}`,
      entityDescription: storyboardData.synopsis || undefined,
      entityFixedDesc: parts.length > 0 ? parts.join(" | ") : undefined,
      entityCoverUrl: storyboardData.coverUrl || undefined,
      scriptId,
    };

    const entitiesToFetch = buildCoverEntityRefs(storyboardData);

    if (entitiesToFetch.length === 0) {
      updateEntity({ ...baseEntity, associatedCovers: [] });
      return;
    }

    let cancelled = false;

    const loadCovers = async () => {
      const covers = await loadCoverAssetIds(entitiesToFetch);

      if (!cancelled) {
        updateEntity({ ...baseEntity, associatedCovers: covers });
      }
    };

    // Update entity immediately with base info, then load covers
    updateEntity(baseEntity);
    loadCovers();

    return () => { cancelled = true; };
  }, [storyboardData, activePanel, updateEntity, scriptId, buildCoverEntityRefs, loadCoverAssetIds]);

  if (items.length === 0) {
    return <EmptyState icon={<Clapperboard className="size-16" />} title="暂无分镜" />;
  }

  return (
    <div className="flex h-full gap-3">
      {/* Sidebar - Collapsible */}
      <DetailSidebar
        items={items}
        selectedId={selectedId}
        onSelect={onSelect}
        onDelete={onDelete}
        onComment={handleCommentOpen}
        isCollapsed={isSidebarCollapsed}
        onToggle={onToggleSidebar}
        placeholderIcon={<Camera className="size-5 text-muted/30" />}
        entityTypeLabel="分镜"
      />

      {/* Detail Content */}
      <div className="relative min-w-0 flex-1 overflow-hidden rounded-xl">
        {isLoading ? (
          <DetailPaneSkeleton />
        ) : storyboardData ? (
          <>
            <StoryboardDetailContent
              data={storyboardData}
              scriptId={scriptId}
              onUpdate={fetchStoryboardDetail}
              onStartEditing={() => startEditing("STORYBOARD", selectedItem!.id)}
              onStopEditing={() => stopEditing("STORYBOARD", selectedItem!.id)}
              isReadOnly={isReadOnlyMode}
              getCachedCoverAssetId={(entityId) => coverCacheRef.current.get(entityId) ?? null}
            />
            {/* Editing Overlay - show when locked by others and not in read-only mode */}
            {collaborators.isLockedByOther && collaborators.editor && !isReadOnlyMode && (
              <EditingOverlay
                editor={collaborators.editor}
                lockedAt={collaborators.lockedAt}
                timeoutMinutes={5}
                onViewReadOnly={() => setIsReadOnlyMode(true)}
                onRefresh={() => refreshEntityState("STORYBOARD", selectedItem!.id)}
                onForceEdit={() => startEditing("STORYBOARD", selectedItem!.id)}
              />
            )}
          </>
        ) : (
          <div className="flex h-full items-center justify-center text-sm text-muted">
            请选择一个分镜
          </div>
        )}
      </div>
    </div>
  );
}

// Storyboard Tab Component
export function StoryboardTab({ scriptId }: StoryboardTabProps) {
  const locale = useLocale();
  const { currentWorkspaceId } = useWorkspace();
  const [viewMode, setViewMode] = useState<ViewMode>(() => getStoredViewMode("storyboards"));
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [items, setItems] = useState<EntityItem[]>([]);
  const [rawStoryboards, setRawStoryboards] = useState<StoryboardListDTO[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [isSidebarCollapsed, setIsSidebarCollapsed] = useState(() => getStoredSidebarCollapsed("storyboards"));
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [isCreating, setIsCreating] = useState(false);
  const [searchKeyword, setSearchKeyword] = useState("");
  const [sortKeys, setSortKeys] = useState<SortKey[]>(["sequence_asc"]);

  // Episodes list and selected episode
  const [episodesList, setEpisodesList] = useState<EpisodeListDTO[]>([]);
  const [selectedEpisodeId, setSelectedEpisodeId] = useState<string>("");
  const [showEpisodePicker, setShowEpisodePicker] = useState(false);
  const setStoryboardEpisode = useStoryboardEpisodeStore((s) => s.setEpisode);

  // Create form state
  const [createForm, setCreateForm] = useState({
    title: "",
    synopsis: "",
    duration: "",
  });

  const resetCreateForm = () => {
    setCreateForm({ title: "", synopsis: "", duration: "" });
  };

  // Fetch episodes on mount
  useEffect(() => {
    const fetchEpisodes = async () => {
      if (!currentWorkspaceId || !scriptId) return;
      try {
        const episodes = await projectService.getEpisodesByScript( scriptId);
        setEpisodesList(episodes);
        // Select first episode by default
        if (episodes.length > 0 && !selectedEpisodeId) {
          setSelectedEpisodeId(episodes[0].id);
        }
      } catch (err) {
        console.error("Failed to fetch episodes:", err);
        toast.danger(getErrorFromException(err, locale));
      }
    };
    fetchEpisodes();
  }, [currentWorkspaceId, scriptId, selectedEpisodeId]);

  // Sync selected episode info to the shared store so ScriptPanel can show "分镜 · 剧集title"
  const selectedEpisode = episodesList.find((ep) => ep.id === selectedEpisodeId);
  useEffect(() => {
    if (selectedEpisode) {
      setStoryboardEpisode(scriptId, {
        id: selectedEpisode.id,
        sequence: selectedEpisode.sequence,
        title: selectedEpisode.title,
      });
    } else {
      setStoryboardEpisode(scriptId, undefined);
    }
    return () => {
      setStoryboardEpisode(scriptId, undefined);
    };
  }, [scriptId, selectedEpisode?.id, selectedEpisode?.sequence, selectedEpisode?.title, setStoryboardEpisode]);

  // Fetch storyboards by episode
  const fetchStoryboards = useCallback(async () => {
    if (!currentWorkspaceId || !selectedEpisodeId) {
      setItems([]);
      setRawStoryboards([]);
      setIsLoading(false);
      return;
    }

    try {
      setIsLoading(true);
      setError(null);

      const storyboards = await projectService.getStoryboardsByEpisode( selectedEpisodeId);
      setRawStoryboards(storyboards);
      const entityItems = storyboards.map((item) => transformToEntityItem(item, "storyboards"));

      const countResults = await Promise.allSettled(
        storyboards.map((sb) => commentService.getCommentCount("STORYBOARD", sb.id))
      );
      countResults.forEach((result, idx) => {
        if (result.status === "fulfilled" && result.value > 0) {
          entityItems[idx].commentCount = result.value;
        }
      });

      setItems(entityItems);
    } catch (err) {
      console.error("Failed to fetch storyboards:", err);
      toast.danger(getErrorFromException(err, locale));
      setError("加载分镜失败");
    } finally {
      setIsLoading(false);
    }
  }, [currentWorkspaceId, selectedEpisodeId]);

  useEffect(() => {
    fetchStoryboards();
  }, [fetchStoryboards]);

  // Auto-refresh when storyboards are changed by other collaborators via WebSocket
  useDebouncedEntityChanges(
    "storyboard",
    fetchStoryboards,
    { onDeleted: (id) => { if (selectedId === id) setSelectedId(null); } },
    [fetchStoryboards, selectedId]
  );

  const handleSelect = (id: string) => {
    setSelectedId(id);
    if (viewMode !== "detail") {
      setViewMode("detail");
      saveViewMode("storyboards", "detail");
    }
  };

  const handleViewModeChange = (mode: ViewMode) => {
    setViewMode(mode);
    saveViewMode("storyboards", mode);
  };

  const handleToggleSidebar = () => {
    const newCollapsed = !isSidebarCollapsed;
    setIsSidebarCollapsed(newCollapsed);
    saveSidebarCollapsed("storyboards", newCollapsed);
  };

  // Filter items
  const filteredItems = sortItems(
    items.filter((item) => {
      if (!searchKeyword.trim()) return true;
      const keyword = searchKeyword.toLowerCase();
      return (
        item.title.toLowerCase().includes(keyword) ||
        item.description?.toLowerCase().includes(keyword)
      );
    }),
    sortKeys,
  );

  // Create storyboard
  const handleCreate = async () => {
    if (!currentWorkspaceId || !selectedEpisodeId) return;

    try {
      setIsCreating(true);
      await projectService.createStoryboard( {
        episodeId: selectedEpisodeId,
        title: createForm.title.trim() || undefined,
        synopsis: createForm.synopsis.trim() || undefined,
        duration: createForm.duration ? parseInt(createForm.duration) : undefined,
      });

      setShowCreateForm(false);
      resetCreateForm();
      fetchStoryboards();
    } catch (err) {
      console.error("Failed to create storyboard:", err);
      toast.danger(getErrorFromException(err, locale));
    } finally {
      setIsCreating(false);
    }
  };

  // Edit storyboard - switch to detail view
  const handleEdit = (id: string) => {
    setSelectedId(id);
    setViewMode("detail");
    saveViewMode("storyboards", "detail");
  };

  // Delete storyboard
  const handleDelete = async (id: string) => {
    if (!currentWorkspaceId) return;

    try {
      await projectService.deleteStoryboard( id);
      if (selectedId === id) {
        setSelectedId(items[0]?.id || "");
      }
      fetchStoryboards();
    } catch (err) {
      console.error("Failed to delete storyboard:", err);
      toast.danger(getErrorFromException(err, locale));
    }
  };

  if (isLoading && items.length === 0) {
    return <EntityTabSkeleton hasEpisodeSelector />;
  }

  if (error) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-3 text-muted">
        <p>{error}</p>
        <Button variant="ghost" size="sm" onPress={fetchStoryboards}>
          重新加载
        </Button>
      </div>
    );
  }

  // No episodes available
  if (episodesList.length === 0) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-3 text-muted">
        <Film className="size-12 text-muted/30" />
        <p className="text-sm">请先创建剧集</p>
        <p className="text-xs text-muted/70">分镜需要归属于某个剧集</p>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      {/* Toolbar */}
      <Surface variant="secondary" className="rounded-2xl mb-3 flex shrink-0 items-center justify-between gap-3 p-2">
        {/* Left Section: Episode Selector + View Mode + Search */}
        <div className="flex items-center gap-3">
          {/* Episode Selector */}
          <Tooltip delay={0}>
            <Button
              variant="ghost"
              size="sm"
              className="gap-1.5"
              onPress={() => setShowEpisodePicker(true)}
            >
              <Film className="size-4" />
              <span className="text-xs">{selectedEpisode ? `第${selectedEpisode.sequence}集` : "选择剧集"}</span>
            </Button>
            <Tooltip.Content>{selectedEpisode ? selectedEpisode.title : "选择剧集"}</Tooltip.Content>
          </Tooltip>

          <Separator variant="secondary" orientation="vertical" className="h-5 self-center" />

          {/* View Mode Toggle */}
          <ButtonGroup size="sm" variant="ghost">
            <Button
              variant={viewMode === "list" ? "primary" : undefined}
              isIconOnly
              aria-label="列表视图"
              onPress={() => handleViewModeChange("list")}
            >
              <List className="size-4" />
            </Button>
            <Button
              variant={viewMode === "grid" ? "primary" : undefined}
              isIconOnly
              aria-label="卡片视图"
              onPress={() => handleViewModeChange("grid")}
            >
              <LayoutGrid className="size-4" />
            </Button>
            <Button
              variant={viewMode === "detail" ? "primary" : undefined}
              isIconOnly
              aria-label="详情视图"
              onPress={() => handleViewModeChange("detail")}
            >
              <PanelLeft className="size-4" />
            </Button>
          </ButtonGroup>

          <Separator variant="secondary" orientation="vertical" className="h-5 self-center" />

          {/* Search */}
          <SearchField aria-label="搜索分镜" value={searchKeyword} onChange={setSearchKeyword}>
            <SearchField.Group>
              <SearchField.SearchIcon />
              <SearchField.Input className="w-36" placeholder="搜索分镜..." />
              <SearchField.ClearButton />
            </SearchField.Group>
          </SearchField>

          {searchKeyword && (
            <span className="text-xs text-muted">
              {filteredItems.length} / {items.length}
            </span>
          )}

          <Separator variant="secondary" orientation="vertical" className="h-5 self-center" />

          {/* Sort */}
          <SortButtons value={sortKeys} onChange={setSortKeys} fields={["sequence", "updatedAt", "createdAt", "name"]} />
        </div>

        {/* Right Section: Refresh + Add */}
        <div className="flex items-center gap-1">
          <Tooltip delay={0}>
            <Button variant="ghost" size="sm" className="size-8 p-0 text-muted hover:text-foreground" onPress={fetchStoryboards}>
              <RefreshCw className={`size-4 ${isLoading ? "animate-spin" : ""}`} />
            </Button>
            <Tooltip.Content>刷新</Tooltip.Content>
          </Tooltip>

          <Separator orientation="vertical" className="mx-0.5 h-5 self-center" />

          <Button
            variant="primary"
            size="sm"
            className="gap-1.5 text-xs"
            onPress={() => setShowCreateForm(true)}
            isDisabled={!selectedEpisodeId}
          >
            <Plus className="size-3.5" />
            新增分镜
          </Button>
        </div>
      </Surface>

      {/* Content */}
      <div className="min-h-0 flex-1">
        {viewMode === "list" && (
          <ScrollShadow className="h-full" hideScrollBar>
            <EntityListView
              items={filteredItems}
              onSelect={handleSelect}
              onEdit={handleEdit}
              onDelete={handleDelete}
            />
          </ScrollShadow>
        )}
        {viewMode === "grid" && (
          <ScrollShadow className="h-full" hideScrollBar>
            <EntityGridView
              items={filteredItems}
              onSelect={handleSelect}
              onEdit={handleEdit}
              onDelete={handleDelete}
            />
          </ScrollShadow>
        )}
        {viewMode === "detail" && (
          <StoryboardDetailView
            items={filteredItems}
            selectedId={selectedId}
            onSelect={setSelectedId}
            onDelete={handleDelete}
            isSidebarCollapsed={isSidebarCollapsed}
            onToggleSidebar={handleToggleSidebar}
            onRefresh={fetchStoryboards}
            scriptId={scriptId}
          />
        )}
      </div>

      {/* Create Form Modal */}
      <Modal.Backdrop
        isOpen={showCreateForm}
        onOpenChange={(open) => {
          setShowCreateForm(open);
          if (!open) resetCreateForm();
        }}
        variant="blur"
      >
        <Modal.Container size="sm">
          <Modal.Dialog>
            <Modal.CloseTrigger />
            <Modal.Header>
              <Modal.Icon className="bg-accent/10 text-accent">
                <Camera className="size-5" />
              </Modal.Icon>
              <Modal.Heading>新增分镜</Modal.Heading>
            </Modal.Header>
            <Modal.Body className="overflow-visible">
              <div className="space-y-4">
                {/* Show current episode info */}
                <div className="rounded-lg bg-muted/10 px-3 py-2">
                  <span className="text-xs text-muted">所属剧集: </span>
                  <span className="text-sm font-medium">
                    {episodesList.find(ep => ep.id === selectedEpisodeId)?.title || "未选择"}
                  </span>
                </div>

                <div className="space-y-1.5">
                  <Label className="text-xs font-medium text-muted">标题</Label>
                  <Input
                    variant="secondary"
                    value={createForm.title}
                    onChange={(e) => setCreateForm((prev) => ({ ...prev, title: e.target.value }))}
                    className="w-full"
                    placeholder="输入分镜标题（可选）"
                  />
                </div>
                <div className="space-y-1.5">
                  <Label className="text-xs font-medium text-muted">时长(秒)</Label>
                  <Input
                    variant="secondary"
                    type="number"
                    value={createForm.duration}
                    onChange={(e) => setCreateForm((prev) => ({ ...prev, duration: e.target.value }))}
                    className="w-full"
                    placeholder="输入时长（可选）"
                    min="0"
                  />
                </div>
                <div className="space-y-1.5">
                  <Label className="text-xs font-medium text-muted">简介</Label>
                  <TextArea
                    variant="secondary"
                    value={createForm.synopsis}
                    onChange={(e) => setCreateForm((prev) => ({ ...prev, synopsis: e.target.value }))}
                    className="min-h-20 w-full"
                    placeholder="输入分镜简介（可选）"
                  />
                </div>
              </div>
            </Modal.Body>
            <Modal.Footer>
              <Button variant="secondary" slot="close" isDisabled={isCreating}>
                取消
              </Button>
              <Button
                variant="primary"
                className="gap-1.5"
                onPress={handleCreate}
                isPending={isCreating}
                isDisabled={!selectedEpisodeId}
              >
                {({isPending}) => (<>{isPending ? <Spinner color="current" size="sm" /> : <Plus className="size-3.5" />}创建</>)}
              </Button>
            </Modal.Footer>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>

      {/* Episode Picker Modal */}
      <Modal.Backdrop isOpen={showEpisodePicker} onOpenChange={setShowEpisodePicker} variant="blur">
        <Modal.Container size="sm">
          <Modal.Dialog>
            <Modal.CloseTrigger />
            <Modal.Header>
              <Modal.Icon className="bg-accent/10 text-accent">
                <Film className="size-5" />
              </Modal.Icon>
              <Modal.Heading>选择剧集</Modal.Heading>
            </Modal.Header>
            <Modal.Body>
              <div className="max-h-96 space-y-1 overflow-y-auto">
                {episodesList.map((ep) => (
                  <button
                    key={ep.id}
                    type="button"
                    onClick={() => {
                      setSelectedEpisodeId(ep.id);
                      setShowEpisodePicker(false);
                    }}
                    className={`flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left transition-colors hover:bg-muted/10 ${
                      ep.id === selectedEpisodeId ? "bg-accent/10 text-accent" : ""
                    }`}
                  >
                    <span className="shrink-0 text-xs font-medium tabular-nums opacity-70">第{ep.sequence}集</span>
                    <span className="min-w-0 flex-1 truncate text-sm">{ep.title}</span>
                  </button>
                ))}
              </div>
            </Modal.Body>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>
    </div>
  );
}

export default StoryboardTab;
