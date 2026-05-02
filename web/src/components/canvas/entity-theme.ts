import {
  User,
  MapPin,
  Package,
  Film,
  BookOpen,
  Clapperboard,
  Image as ImageIcon,
  Video,
  Music,
  Type,
} from "lucide-react";

export interface EntityThemeEntry {
  /** lucide 图标 */
  icon: typeof User;
  /** 中文标签 */
  label: string;
}

/**
 * 实体类型 → 图标 + 标签映射。
 * 仅保留语义信息（图标/标签），不做颜色装饰 —— 节点视觉走 HeroUI Card 默认样式。
 */
export const ENTITY_THEME: Record<string, EntityThemeEntry> = {
  CHARACTER: { icon: User, label: "角色" },
  SCENE: { icon: MapPin, label: "场景" },
  PROP: { icon: Package, label: "道具" },
  STORYBOARD: { icon: Film, label: "分镜" },
  EPISODE: { icon: Clapperboard, label: "剧集" },
  SCRIPT: { icon: BookOpen, label: "剧本" },
  ASSET: { icon: ImageIcon, label: "素材" },
};

/** ASSET 节点按 mediaType 区分子图标/标签 */
export const MEDIA_THEME: Record<string, EntityThemeEntry> = {
  IMAGE: { icon: ImageIcon, label: "图片" },
  VIDEO: { icon: Video, label: "视频" },
  AUDIO: { icon: Music, label: "音频" },
  TEXT: { icon: Type, label: "文本" },
};

export const ENTITY_FALLBACK: EntityThemeEntry = { icon: ImageIcon, label: "节点" };

export function getEntityTheme(entityType?: string): EntityThemeEntry {
  return (entityType && ENTITY_THEME[entityType]) || ENTITY_FALLBACK;
}

export function getMediaTheme(mediaType?: string): EntityThemeEntry {
  return (mediaType && MEDIA_THEME[mediaType.toUpperCase()]) || ENTITY_FALLBACK;
}
