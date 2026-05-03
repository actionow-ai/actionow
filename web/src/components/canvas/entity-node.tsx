"use client";

import { memo } from "react";
import { Handle, NodeResizeControl, Position, type NodeProps } from "@xyflow/react";
import { Card } from "@heroui/react";
import { Image as ImageIcon, PlayCircle, AudioLines, Loader2, Type } from "lucide-react";

import type { CanvasNodeDTO } from "@/lib/api/services/canvas.service";
import { getEntityTheme, getMediaTheme } from "./entity-theme";

export type EntityNodeData = {
  label: string;
  entityType?: string;
  nodeData: CanvasNodeDTO;
  /** 是否正在 AI 生成中（PromptBar 提交后） */
  isGenerating?: boolean;
};

type Detail = {
  name?: unknown;
  description?: unknown;
  synopsis?: unknown;
  content?: unknown;
  coverUrl?: unknown;
  thumbnailUrl?: unknown;
  fileUrl?: unknown;
  mediaType?: unknown;
  /** Project 实体里 ASSET 的子类型字段（IMAGE/VIDEO/AUDIO/TEXT） */
  assetType?: unknown;
  url?: unknown;
  text?: unknown;
  detail?: unknown;
};

function readDetail(node: CanvasNodeDTO | undefined) {
  const d = (node?.entityDetail ?? {}) as Detail;
  const inner = (d.detail ?? {}) as Detail;
  const pick = (k: keyof Detail): string | undefined => {
    const v = d[k] ?? inner[k];
    return typeof v === "string" ? v : undefined;
  };
  return {
    name: pick("name"),
    description: pick("description"),
    synopsis: pick("synopsis"),
    content: pick("content"),
    coverUrl: pick("coverUrl") ?? pick("thumbnailUrl"),
    /** ASSET 节点：mediaType 优先，其次后端实体里的 assetType */
    mediaType: pick("mediaType") ?? pick("assetType"),
    url: pick("url") ?? pick("fileUrl"),
    text: pick("text") ?? pick("content"),
  };
}

const HANDLE_STYLE: React.CSSProperties = {
  background: "var(--color-default-400)",
  width: 8,
  height: 8,
};

/** Resize 角控制柄外观；NodeResizeControl 自己负责定位（top/left 等），style 仅控制外观 */
const RESIZE_HANDLE_STYLE: React.CSSProperties = {
  width: 12,
  height: 12,
  background: "var(--color-default-50)",
  border: "1.5px solid var(--color-default-500)",
  borderRadius: 3,
  zIndex: 1000,
};

const CORNER_POSITIONS = ["top-left", "top-right", "bottom-left", "bottom-right"] as const;

/**
 * TapNow 风格节点：
 * - 类型标签外置在卡片上方（小灰字 + icon）
 * - 卡片只渲染内容主体（图/视频/音频/文本），无 Card.Header
 * - 占位极简（纯背景 + 中央小 icon）
 */
function MediaCardNodeImpl({ data, selected }: NodeProps) {
  const { label, entityType, nodeData, isGenerating } = data as unknown as EntityNodeData;
  const detail = readDetail(nodeData);
  const isAsset = entityType === "ASSET";
  const mediaType = (detail.mediaType ?? "").toUpperCase();
  const theme = isAsset ? getMediaTheme(mediaType) : getEntityTheme(entityType);
  const Icon = theme.icon;
  const displayName = detail.name || label || theme.label;

  return (
    // 外层 = Card 边界：所有附属控件（4 角缩放、连接 handle、外置标签）都围绕 Card 定位
    <div className="relative h-full w-full">
      {/* 外置标签：浮在 Card 顶部上方 */}
      <div className="pointer-events-none absolute -top-5 left-0 flex items-center gap-1 text-[11px] text-muted">
        <Icon className="size-3" />
        <span className="max-w-[180px] truncate">{displayName}</span>
      </div>

      <Handle type="target" position={Position.Left} style={HANDLE_STYLE} />

      <Card
        variant="default"
        className={`relative h-full w-full overflow-hidden p-0 transition-all duration-200 animate-in fade-in zoom-in-95 ${
          entityType === "SCRIPT" ? "min-w-[320px] min-h-[140px]" : "min-w-[220px] min-h-[140px]"
        } ${selected ? "ring-2 ring-default-400" : "hover:shadow-md"}`}
      >
        <MediaContent
          entityType={entityType}
          mediaType={mediaType}
          detail={detail}
        />
        {isGenerating && (
          <div className="absolute inset-0 flex items-center justify-center bg-default-50/70 backdrop-blur-[2px]">
            <Loader2 className="size-6 animate-spin text-default-500" />
          </div>
        )}
      </Card>

      <Handle type="source" position={Position.Right} style={HANDLE_STYLE} />

      {/* 仅 4 角缩放控制柄（无虚线/无边框）；选中时显示 */}
      {selected && CORNER_POSITIONS.map((pos) => (
        <NodeResizeControl
          key={pos}
          position={pos}
          minWidth={entityType === "SCRIPT" ? 320 : 220}
          minHeight={140}
          style={RESIZE_HANDLE_STYLE}
        />
      ))}
    </div>
  );
}

interface MediaContentProps {
  entityType?: string;
  mediaType: string;
  detail: ReturnType<typeof readDetail>;
}

function MediaContent({ entityType, mediaType, detail }: MediaContentProps) {
  // SCRIPT：优先显示正文 content（剧本大纲），其次 synopsis / description
  if (entityType === "SCRIPT") {
    const body = detail.content || detail.synopsis || detail.description;
    if (!body) {
      return (
        <div className="px-3 py-3">
          <p className="text-sm text-muted">暂无内容</p>
        </div>
      );
    }
    return (
      <div className="nowheel max-h-[280px] overflow-y-auto px-3 py-3">
        <p className="text-xs leading-relaxed whitespace-pre-wrap break-words text-foreground">
          {body}
        </p>
      </div>
    );
  }

  // ASSET 各 mediaType
  if (entityType === "ASSET") {
    const url = detail.url || detail.coverUrl;
    if (mediaType === "TEXT") {
      return (
        <div className="px-3 py-3 min-h-[100px]">
          {detail.text ? (
            <p className="text-sm whitespace-pre-wrap break-words">{detail.text}</p>
          ) : (
            <p className="text-sm text-muted">双击开始编辑...</p>
          )}
        </div>
      );
    }
    if (mediaType === "IMAGE") {
      return url ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={url} alt="" className="block h-full w-full object-cover" />
      ) : (
        <ImagePlaceholder />
      );
    }
    if (mediaType === "VIDEO") {
      return url ? (
        <video src={url} controls className="block h-full w-full" />
      ) : (
        <VideoPlaceholder />
      );
    }
    if (mediaType === "AUDIO") {
      return url ? (
        <div className="px-3 py-3">
          <audio src={url} controls className="w-full" />
        </div>
      ) : (
        <AudioPlaceholder />
      );
    }
    return url ? (
      // eslint-disable-next-line @next/next/no-img-element
      <img src={url} alt="" className="block h-full w-full object-cover" />
    ) : (
      <ImagePlaceholder />
    );
  }

  // 其他业务实体（CHARACTER/SCENE/PROP）
  if (detail.coverUrl) {
    // eslint-disable-next-line @next/next/no-img-element
    return <img src={detail.coverUrl} alt="" className="block h-full w-full object-cover" />;
  }
  if (detail.description) {
    return (
      <div className="px-3 py-3">
        <p className="text-sm text-muted">{detail.description}</p>
      </div>
    );
  }
  return <div className="h-16" />;
}

/** IMAGE 占位：16:9 深底 + 中央小 icon */
function ImagePlaceholder() {
  return (
    <div className="flex h-full min-h-[120px] w-full items-center justify-center bg-default-100">
      <ImageIcon className="size-7 text-default-400" strokeWidth={1.5} />
    </div>
  );
}

/** VIDEO 占位：16:9 更深底 + 圆形播放按钮 */
function VideoPlaceholder() {
  return (
    <div className="flex h-full w-full items-center justify-center bg-default-200">
      <PlayCircle className="size-9 text-default-500" strokeWidth={1.5} />
    </div>
  );
}

/** AUDIO 占位：扁平条 + 波形 icon */
function AudioPlaceholder() {
  return (
    <div className="flex h-16 w-full items-center justify-center bg-default-100">
      <AudioLines className="size-7 text-default-400" strokeWidth={1.5} />
    </div>
  );
}

export const EntityNode = memo(MediaCardNodeImpl);
EntityNode.displayName = "EntityNode";
