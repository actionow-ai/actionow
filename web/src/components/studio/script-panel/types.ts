/**
 * Script Panel Types
 */

import type {
  ScriptDetailDTO,
  ScriptStatus,
  EpisodeListDTO,
  EpisodeDetailDTO,
  StoryboardListDTO,
  StoryboardDetailDTO,
  CharacterListDTO,
  CharacterDetailDTO,
  SceneListDTO,
  SceneDetailDTO,
  PropListDTO,
  PropDetailDTO,
  AssetListDTO,
  AssetDetailDTO,
  TrashAssetDTO,
  VersionInfoDTO,
  ScriptVersionDetailDTO,
  VersionDiffDTO,
  EntityAssetRelationDTO,
  RelationType,
  GenerationStatus,
} from "@/lib/api/dto";

export type TabKey = "details" | "episodes" | "storyboards" | "characters" | "scenes" | "props" | "assets";
export type ViewMode = "list" | "grid" | "detail";

export interface ScriptPanelProps {
  scriptId: string;
}

export interface EntityItem {
  id: string;
  title: string;
  description: string | null;
  status: string;
  coverUrl?: string | null;
  fileUrl?: string | null;
  assetType?: "IMAGE" | "VIDEO" | "AUDIO" | "DOCUMENT";
  source?: "UPLOAD" | "AI_GENERATED" | "EXTERNAL";
  mimeType?: string;
  fileSize?: number;
  generationStatus?: GenerationStatus;
  createdBy?: string | null;
  createdByNickname?: string | null;
  createdByUsername?: string | null;
  commentCount?: number;
}

export interface TabConfig {
  key: TabKey;
  label: string;
  icon: React.ReactNode;
}

export interface StyleConfig {
  key: string;
  label: string;
  icon: React.ReactNode;
}

export interface StatusOption {
  key: ScriptStatus;
  label: string;
}

// Re-export DTO types for convenience
export type {
  ScriptDetailDTO,
  ScriptStatus,
  EpisodeListDTO,
  EpisodeDetailDTO,
  StoryboardListDTO,
  StoryboardDetailDTO,
  CharacterListDTO,
  CharacterDetailDTO,
  SceneListDTO,
  SceneDetailDTO,
  PropListDTO,
  PropDetailDTO,
  AssetListDTO,
  AssetDetailDTO,
  TrashAssetDTO,
  VersionInfoDTO,
  ScriptVersionDetailDTO,
  VersionDiffDTO,
  EntityAssetRelationDTO,
  RelationType,
  GenerationStatus,
};
