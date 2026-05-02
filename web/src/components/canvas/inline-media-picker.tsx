"use client";

import { useEffect, useRef } from "react";
import { Card } from "@heroui/react";
import { Image as ImageIcon, Video, Music, Type } from "lucide-react";

export type MediaPickerType = "IMAGE" | "VIDEO" | "AUDIO" | "TEXT";

interface InlineMediaPickerProps {
  /** 屏幕坐标（基于 viewport） */
  screenX: number;
  screenY: number;
  /** 标题 — "添加节点"（双击空白）/ "引用该节点生成"（拖线触发）等 */
  title?: string;
  onSelect: (type: MediaPickerType) => void;
  onDismiss: () => void;
}

interface PickerItem {
  type: MediaPickerType;
  icon: typeof ImageIcon;
  label: string;
  description: string;
}

const ITEMS: PickerItem[] = [
  { type: "TEXT", icon: Type, label: "文本生成", description: "脚本、广告词、品牌文案" },
  { type: "IMAGE", icon: ImageIcon, label: "图片生成", description: "封面、参考图、设计稿" },
  { type: "VIDEO", icon: Video, label: "视频生成", description: "镜头片段、动画演示" },
  { type: "AUDIO", icon: Music, label: "音频生成", description: "配音、音效、配乐" },
];

/**
 * 紧贴光标的小浮层（不是 Modal）。外部点击 / ESC 自动 dismiss。
 * 视觉参考 TapNow：HeroUI Card + 列表项（icon + 主标题 + 副标题）。
 */
export function InlineMediaPicker({
  screenX,
  screenY,
  title,
  onSelect,
  onDismiss,
}: InlineMediaPickerProps) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleDocClick = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        onDismiss();
      }
    };
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onDismiss();
    };
    const t = setTimeout(() => {
      document.addEventListener("mousedown", handleDocClick);
      document.addEventListener("keydown", handleKey);
    }, 0);
    return () => {
      clearTimeout(t);
      document.removeEventListener("mousedown", handleDocClick);
      document.removeEventListener("keydown", handleKey);
    };
  }, [onDismiss]);

  return (
    <div
      ref={ref}
      role="menu"
      className="fixed z-50 origin-top-left animate-in fade-in zoom-in-95 duration-150"
      style={{ left: screenX, top: screenY }}
    >
      <Card variant="default" className="w-[260px] gap-0 p-1">
        {title && (
          <p className="px-3 pb-1 pt-2 text-xs text-muted">{title}</p>
        )}
        <div className="flex flex-col">
          {ITEMS.map((item, idx) => (
            <button
              key={item.type}
              type="button"
              role="menuitem"
              onClick={() => onSelect(item.type)}
              className="group flex items-start gap-3 rounded-md px-3 py-2 text-left transition-all duration-150 hover:translate-x-0.5 hover:bg-default-100 active:scale-[0.98]"
              style={{ animationDelay: `${idx * 30}ms` }}
            >
              <item.icon className="mt-0.5 size-4 shrink-0 text-muted transition-colors group-hover:text-foreground" />
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium leading-tight">{item.label}</p>
                <p className="mt-0.5 text-xs text-muted">{item.description}</p>
              </div>
            </button>
          ))}
        </div>
      </Card>
    </div>
  );
}
