"use client";

import { Fragment } from "react";
import { Button, Dropdown } from "@heroui/react";

import type { InputParamDefinition } from "@/lib/api/dto/ai.dto";

interface InlineParamsProps {
  params: InputParamDefinition[];
  values: Record<string, unknown>;
  onChange: (name: string, value: unknown) => void;
}

/**
 * 内联参数选择条：每个 enum 参数显示当前值（点分隔），点击展开 dropdown
 */
export function InlineParams({ params, values, onChange }: InlineParamsProps) {
  if (!params || params.length === 0) return null;

  return (
    <>
      {params.map((p, idx) => {
        const current = values[p.name];
        const options =
          p.options ?? (p.enum ?? []).map((v) => ({ value: v, label: v }));
        const currentLabel =
          options.find((o) => o.value === current)?.label
          ?? (typeof current === "string" ? current : p.label);

        return (
          <Fragment key={p.name}>
            {idx > 0 && <span className="text-xs text-muted">·</span>}
            <Dropdown>
              <Button
                variant="ghost"
                size="sm"
                aria-label={p.label}
                className="!h-7 !min-h-0 px-1.5 text-xs"
              >
                {currentLabel}
              </Button>
              <Dropdown.Popover placement="bottom start" className="min-w-[160px]">
                <Dropdown.Menu
                  aria-label={p.label}
                  selectedKeys={
                    typeof current === "string" ? new Set([current]) : new Set()
                  }
                  selectionMode="single"
                  onAction={(key) => onChange(p.name, key)}
                >
                  {options.map((opt) => (
                    <Dropdown.Item
                      key={opt.value}
                      id={opt.value}
                      textValue={opt.label}
                    >
                      <span className="text-sm">{opt.label}</span>
                    </Dropdown.Item>
                  ))}
                </Dropdown.Menu>
              </Dropdown.Popover>
            </Dropdown>
          </Fragment>
        );
      })}
    </>
  );
}
