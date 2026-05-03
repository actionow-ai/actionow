"use client";

import { Tooltip } from "@heroui/react";
import { FileText, Image as ImageIcon, Music, Video } from "lucide-react";

import type { CanvasNodeDTO } from "@/lib/api/services/canvas.service";

interface ParentSlotsProps {
  parents: CanvasNodeDTO[];
}

type Detail = {
  name?: unknown;
  coverUrl?: unknown;
  thumbnailUrl?: unknown;
  fileUrl?: unknown;
  mediaType?: unknown;
  assetType?: unknown;
};

function readParent(node: CanvasNodeDTO) {
  const d = (node.entityDetail ?? {}) as Detail;
  const pick = (k: keyof Detail) => {
    const v = d[k];
    return typeof v === "string" ? v : undefined;
  };
  const mediaTypeRaw = pick("mediaType") ?? pick("assetType");
  const mediaType = mediaTypeRaw ? mediaTypeRaw.toUpperCase() : undefined;
  return {
    name: pick("name") ?? node.entityType ?? "节点",
    thumb: pick("coverUrl") ?? pick("thumbnailUrl") ?? pick("fileUrl"),
    mediaType,
    entityType: node.entityType,
  };
}

function ParentIcon({
  mediaType,
  entityType,
}: {
  mediaType?: string;
  entityType?: string;
}) {
  if (entityType !== "ASSET") {
    // 非 ASSET：使用文档图标
    return <FileText className="size-3.5 text-muted" />;
  }
  if (mediaType === "VIDEO") return <Video className="size-3.5 text-muted" />;
  if (mediaType === "AUDIO") return <Music className="size-3.5 text-muted" />;
  if (mediaType === "TEXT") return <FileText className="size-3.5 text-muted" />;
  return <ImageIcon className="size-3.5 text-muted" />;
}

/**
 * 顶部行左侧：显示父节点的略缩图/icon，hover 显示名称
 */
export function ParentSlots({ parents }: ParentSlotsProps) {
  if (!parents || parents.length === 0) return null;

  return (
    <div className="flex items-center gap-1">
      {parents.map((p) => {
        const info = readParent(p);
        return (
          <Tooltip key={p.id} delay={300}>
            <Tooltip.Trigger>
              <div
                className="flex size-7 items-center justify-center overflow-hidden rounded-md border border-border bg-default-100"
                aria-label={info.name}
              >
                {info.thumb ? (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img
                    src={info.thumb}
                    alt={info.name}
                    className="size-full object-cover"
                  />
                ) : (
                  <ParentIcon
                    mediaType={info.mediaType}
                    entityType={info.entityType}
                  />
                )}
              </div>
            </Tooltip.Trigger>
            <Tooltip.Content>{info.name}</Tooltip.Content>
          </Tooltip>
        );
      })}
    </div>
  );
}
