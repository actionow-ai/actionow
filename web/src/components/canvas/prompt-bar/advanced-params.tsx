"use client";

import { Button, Popover, Tooltip } from "@heroui/react";
import { SlidersHorizontal } from "lucide-react";

import {
  TextField as FormTextField,
  NumberField as FormNumberField,
  BooleanField as FormBooleanField,
  SelectField as FormSelectField,
} from "@/components/studio/ai-generation/components/form-fields";
import type { InputParamDefinition } from "@/lib/api/dto/ai.dto";

interface AdvancedParamsProps {
  params: InputParamDefinition[];
  values: Record<string, unknown>;
  onChange: (name: string, value: unknown) => void;
  disabled?: boolean;
}

/**
 * 高级参数按钮：点击展开 Popover，渲染所有非 basic / 非文件参数
 * 复用灵感模式的 form-fields
 */
export function AdvancedParams({
  params,
  values,
  onChange,
  disabled,
}: AdvancedParamsProps) {
  if (!params || params.length === 0) return null;

  return (
    <Popover>
      <Tooltip delay={300}>
        <Popover.Trigger>
          <Button
            isIconOnly
            variant="ghost"
            size="sm"
            aria-label="高级参数"
            isDisabled={disabled}
            className="!h-7 !min-h-0 !min-w-0 !w-7"
          >
            <SlidersHorizontal className="size-3.5" />
          </Button>
        </Popover.Trigger>
        <Tooltip.Content>高级参数</Tooltip.Content>
      </Tooltip>
      <Popover.Content placement="top" className="w-80 p-0">
        <Popover.Dialog className="p-0">
          <div className="flex flex-col gap-3 p-3">
            <p className="text-xs font-medium text-foreground">高级参数</p>
            {params.map((param) => {
              const pType =
                param.type === "INTEGER"
                  ? "NUMBER"
                  : param.type === "STRING"
                    ? "TEXT"
                    : param.type;
              const hasEnum = !!(param.enum || param.options);

              if (hasEnum || pType === "SELECT") {
                return (
                  <FormSelectField
                    key={param.name}
                    param={param}
                    value={String(
                      values[param.name] ?? param.defaultValue ?? param.default ?? ""
                    )}
                    onChange={(v) => onChange(param.name, v)}
                    disabled={disabled}
                  />
                );
              }

              if (pType === "BOOLEAN") {
                return (
                  <FormBooleanField
                    key={param.name}
                    param={param}
                    value={
                      (values[param.name] as boolean)
                      ?? (param.defaultValue as boolean)
                      ?? false
                    }
                    onChange={(v) => onChange(param.name, v)}
                    disabled={disabled}
                  />
                );
              }

              if (pType === "NUMBER") {
                return (
                  <FormNumberField
                    key={param.name}
                    param={param}
                    value={values[param.name] as number | undefined}
                    onChange={(v) => onChange(param.name, v)}
                    disabled={disabled}
                  />
                );
              }

              return (
                <FormTextField
                  key={param.name}
                  param={param}
                  value={
                    (values[param.name] as string)
                    ?? (param.defaultValue as string)
                    ?? ""
                  }
                  onChange={(v) => onChange(param.name, v)}
                  disabled={disabled}
                />
              );
            })}
          </div>
        </Popover.Dialog>
      </Popover.Content>
    </Popover>
  );
}
