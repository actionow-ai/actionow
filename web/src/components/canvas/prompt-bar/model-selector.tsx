"use client";

import { Button, Dropdown } from "@heroui/react";
import { Sparkles } from "lucide-react";

import type { AvailableProviderDTO } from "@/lib/api/dto/ai.dto";

interface ModelSelectorProps {
  providers: AvailableProviderDTO[];
  selectedProviderId: string | null;
  onSelect: (id: string) => void;
  isLoading: boolean;
}

/**
 * 模型选择器：触发器是文字+sparkle 图标（无边框），点击下拉显示完整模型列表
 */
export function ModelSelector({
  providers,
  selectedProviderId,
  onSelect,
  isLoading,
}: ModelSelectorProps) {
  const selected = providers.find((p) => p.id === selectedProviderId);
  const triggerLabel = selected?.name ?? (isLoading ? "加载中..." : "选择模型");

  return (
    <Dropdown>
      <Button
        variant="ghost"
        size="sm"
        aria-label="选择模型"
        isDisabled={isLoading || providers.length === 0}
        className="!h-7 !min-h-0 gap-1.5 px-1.5 text-xs"
      >
        <Sparkles className="size-3.5 text-muted" />
        <span className="max-w-[140px] truncate">{triggerLabel}</span>
      </Button>
      <Dropdown.Popover placement="bottom start" className="min-w-[280px]">
        <Dropdown.Menu
          aria-label="模型选择"
          selectedKeys={selectedProviderId ? new Set([selectedProviderId]) : new Set()}
          selectionMode="single"
          onAction={(key) => onSelect(key as string)}
        >
          {providers.map((p) => (
            <Dropdown.Item key={p.id} id={p.id} textValue={p.name}>
              <Sparkles className="size-3.5 text-muted" />
              <span className="min-w-0 flex-1 truncate text-sm text-foreground">{p.name}</span>
              <span className="ml-auto shrink-0 text-[11px] text-muted">
                {p.creditCost}c
              </span>
            </Dropdown.Item>
          ))}
        </Dropdown.Menu>
      </Dropdown.Popover>
    </Dropdown>
  );
}
