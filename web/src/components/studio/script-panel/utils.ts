/**
 * Script Panel Utility Functions
 */

import type {
  TabKey,
  ViewMode,
  EntityItem,
  EpisodeListDTO,
  StoryboardListDTO,
  CharacterListDTO,
  SceneListDTO,
  PropListDTO,
  AssetListDTO,
} from "./types";
import {
  getViewMode as getStoredViewMode,
  getSidebarCollapsed as getStoredSidebarCollapsed,
  setViewMode,
  setSidebarCollapsed,
} from "@/lib/stores/preferences-store";

// Re-export preference functions with consistent naming
export {
  getStoredViewMode,
  getStoredSidebarCollapsed,
  setViewMode,
  setSidebarCollapsed,
};

// Format date display
export function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  });
}

// Helper function to transform API data to EntityItem
export function transformToEntityItem(
  item: EpisodeListDTO | StoryboardListDTO | CharacterListDTO | SceneListDTO | PropListDTO | AssetListDTO,
  type: TabKey
): EntityItem {
  switch (type) {
    case "episodes": {
      const ep = item as EpisodeListDTO;
      return {
        id: ep.id,
        title: ep.title,
        description: ep.synopsis,
        status: ep.status,
        coverUrl: ep.coverUrl,
        createdBy: ep.createdBy,
        createdByNickname: ep.createdByNickname,
        createdByUsername: ep.createdByUsername,
      };
    }
    case "storyboards": {
      const sb = item as StoryboardListDTO;
      return {
        id: sb.id,
        title: sb.title || `分镜 ${sb.sequence}`,
        description: sb.synopsis,
        status: sb.status,
        coverUrl: sb.coverUrl,
        createdBy: sb.createdBy,
      };
    }
    case "characters": {
      const chr = item as CharacterListDTO;
      return {
        id: chr.id,
        title: chr.name,
        description: chr.description,
        status: "DRAFT",
        coverUrl: chr.coverUrl,
        createdBy: chr.createdBy,
        createdByNickname: chr.createdByNickname,
        createdByUsername: chr.createdByUsername,
      };
    }
    case "scenes": {
      const scn = item as SceneListDTO;
      return {
        id: scn.id,
        title: scn.name,
        description: scn.description,
        status: "DRAFT",
        coverUrl: scn.coverUrl,
        createdBy: scn.createdBy,
        createdByNickname: scn.createdByNickname,
        createdByUsername: scn.createdByUsername,
      };
    }
    case "props": {
      const prp = item as PropListDTO;
      return {
        id: prp.id,
        title: prp.name,
        description: prp.description,
        status: "DRAFT",
        coverUrl: prp.coverUrl,
        createdBy: prp.createdBy,
        createdByNickname: prp.createdByNickname,
        createdByUsername: prp.createdByUsername,
      };
    }
    case "assets": {
      const ast = item as AssetListDTO;
      // For VIDEO: use fileUrl as cover if thumbnailUrl is null
      let coverUrl = ast.thumbnailUrl || ast.fileUrl;
      if (ast.assetType === "VIDEO" && !ast.thumbnailUrl && ast.fileUrl) {
        coverUrl = ast.fileUrl;
      }
      return {
        id: ast.id,
        title: ast.name,
        description: ast.description,
        status: ast.generationStatus,
        coverUrl: coverUrl,
        fileUrl: ast.fileUrl,
        assetType: ast.assetType as "IMAGE" | "VIDEO" | "AUDIO" | "DOCUMENT",
        source: ast.source as "UPLOAD" | "AI_GENERATED" | "EXTERNAL",
        mimeType: ast.mimeType || undefined,
        fileSize: ast.fileSize || undefined,
        generationStatus: ast.generationStatus,
        createdBy: ast.createdBy,
        createdByNickname: ast.createdByNickname,
        createdByUsername: ast.createdByUsername,
      };
    }
    default:
      return {
        id: (item as { id: string }).id,
        title: "Unknown",
        description: null,
        status: "DRAFT",
      };
  }
}
