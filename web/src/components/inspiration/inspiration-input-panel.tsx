"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { MentionPromptEditor, type FileMentionItem } from "@/components/ui/mention-prompt-editor";
import {
  Button,
  Card,
  Select,
  ListBox,
  Label,
  Description,
  Tooltip,
  Spinner,
  Popover,
  ScrollShadow,
  toast,
} from "@heroui/react";
import NextImage from "@/components/ui/content-image";
import {
  Image,
  Video,
  Music,
  Bot,
  Wand2,
  Send,
  Zap,
  Palette,
  SlidersHorizontal,
  ArrowLeftRight,
  Loader2,
  X,
  Plus,
} from "lucide-react";
import type {
  ProviderType,
  AvailableProviderDTO,
  InputSchemaDTO,
  InputParamDefinition,
  InputParamType,
} from "@/lib/api/dto/ai.dto";
import type { StyleListDTO } from "@/lib/api/dto/project.dto";
import {
  TextField as FormTextField,
  NumberField as FormNumberField,
  BooleanField as FormBooleanField,
  SelectField as FormSelectField,
  type FileValue,
} from "@/components/studio/ai-generation/components/form-fields";
import { projectService } from "@/lib/api/services/project.service";
import { DRAG_MIME } from "./inspiration-record-card";

type MediaType = Exclude<ProviderType, "TEXT">;

const TYPE_OPTIONS: { type: MediaType; labelKey: "image" | "video" | "audio"; icon: typeof Image }[] = [
  { type: "IMAGE", labelKey: "image", icon: Image },
  { type: "VIDEO", labelKey: "video", icon: Video },
  { type: "AUDIO", labelKey: "audio", icon: Music },
];

const FILE_PARAM_TYPES = new Set<InputParamType>([
  "IMAGE", "VIDEO", "AUDIO",
  "IMAGE_LIST", "VIDEO_LIST", "AUDIO_LIST",
]);

type RefMediaKind = "IMAGE" | "VIDEO" | "AUDIO";

interface ReferenceSlot {
  kind: RefMediaKind;
  param: InputParamDefinition;
  isList: boolean;
  maxCount: number;
  accept: string;
}

// ── Frame params (first/last frame for video models) ──
interface FrameParam {
  param: InputParamDefinition;
  label: string;
}

const FRAME_NAME_PATTERNS = {
  first: ["first_frame", "start_frame"],
  last: ["last_frame", "end_frame"],
} as const;

function isFrameParam(name: string): "first" | "last" | null {
  const lower = name.toLowerCase();
  if (FRAME_NAME_PATTERNS.first.some((p) => lower.includes(p))) return "first";
  if (FRAME_NAME_PATTERNS.last.some((p) => lower.includes(p))) return "last";
  return null;
}

function getRefMediaKind(type: InputParamType): RefMediaKind | null {
  if (type === "IMAGE" || type === "IMAGE_LIST") return "IMAGE";
  if (type === "VIDEO" || type === "VIDEO_LIST") return "VIDEO";
  if (type === "AUDIO" || type === "AUDIO_LIST") return "AUDIO";
  return null;
}

function getAccept(kind: RefMediaKind): string {
  switch (kind) {
    case "IMAGE": return "image/*";
    case "VIDEO": return "video/*";
    case "AUDIO": return "audio/*";
  }
}

const KIND_ICON: Record<RefMediaKind, typeof Image> = {
  IMAGE: Image,
  VIDEO: Video,
  AUDIO: Music,
};

const KIND_LABEL: Record<RefMediaKind, string> = {
  IMAGE: "Image",
  VIDEO: "Video",
  AUDIO: "Audio",
};

const ALL_REF_KINDS: RefMediaKind[] = ["IMAGE", "VIDEO", "AUDIO"];

// ── @-Mention types ──
const KIND_CN_PREFIX: Record<RefMediaKind, string> = {
  IMAGE: "图片",
  VIDEO: "视频",
  AUDIO: "音频",
};

const STACK_ROTATIONS = [-6, 3, -2, 5, -4];

function FileThumb({ file, kind, isUploading }: { file: FileValue; kind: RefMediaKind; isUploading?: boolean }) {
  const content = kind === "IMAGE" ? (
    <NextImage src={file.url} alt="" fill className="object-cover" sizes="80px" />
  ) : kind === "VIDEO" ? (
    <video src={file.url} className="size-full object-cover" muted preload="metadata" />
  ) : (
    <div className="flex size-full items-center justify-center bg-surface-2">
      <Music className="size-5 text-muted" />
    </div>
  );
  if (!isUploading) return content;
  return (
    <div className="relative size-full">
      {content}
      <div className="absolute inset-0 flex items-center justify-center bg-black/40">
        <Loader2 className="size-4 animate-spin text-white" />
      </div>
    </div>
  );
}

// ── RefSlot: stacked cards with popover ──
interface RefSlotProps {
  kind: RefMediaKind;
  slot: ReferenceSlot | null;
  formValues: Record<string, unknown>;
  onFormValuesChange: (values: Record<string, unknown>) => void;
  fileInputRefs: React.MutableRefObject<Record<string, HTMLInputElement | null>>;
  handleFileSelect: (
    paramName: string,
    isList: boolean,
    maxCount: number,
  ) => (e: React.ChangeEvent<HTMLInputElement>) => void;
  removeFile: (paramName: string, isList: boolean, index?: number) => void;
  isGenerating: boolean;
  uploadingIds: Set<string>;
  t: (key: string) => string;
  frames?: { first: FrameParam | null; last: FrameParam | null };
}

function RefSlot({
  kind,
  slot,
  formValues,
  onFormValuesChange,
  fileInputRefs,
  handleFileSelect,
  removeFile,
  isGenerating,
  uploadingIds,
  t,
  frames,
}: RefSlotProps) {
  const enabled = !!slot;
  const hasFrames = !!(frames?.first || frames?.last);
  const Icon = KIND_ICON[kind];
  const label = KIND_LABEL[kind];
  const [isDragOver, setIsDragOver] = useState(false);

  const handleDragOver = useCallback(
    (e: React.DragEvent) => {
      if ((!enabled && !hasFrames) || isGenerating) return;
      if (!e.dataTransfer.types.includes(DRAG_MIME)) return;
      e.preventDefault();
      e.dataTransfer.dropEffect = "copy";
      setIsDragOver(true);
    },
    [enabled, hasFrames, isGenerating]
  );

  const handleDragLeave = useCallback(() => setIsDragOver(false), []);

  const handleDrop = useCallback(
    (e: React.DragEvent) => {
      setIsDragOver(false);
      if ((!enabled && !hasFrames) || isGenerating) return;
      const raw = e.dataTransfer.getData(DRAG_MIME);
      if (!raw) return;
      e.preventDefault();

      try {
        const data = JSON.parse(raw) as {
          assetId: string;
          url: string;
          name: string;
          mimeType: string;
          fileSize: number;
          assetType: string;
        };
        // Check asset type matches this slot's kind
        const assetKind = getRefMediaKind(data.assetType as InputParamType);
        if (assetKind !== kind) return;

        const fileValue: FileValue = {
          assetId: data.assetId,
          url: data.url,
          name: data.name,
          mimeType: data.mimeType,
          fileSize: data.fileSize,
        };

        if (slot) {
          if (slot.isList) {
            const existing = (formValues[slot.param.name] as FileValue[] | undefined) ?? [];
            if (existing.some((f) => f.assetId === fileValue.assetId)) return;
            if (existing.length >= slot.maxCount) return;
            onFormValuesChange({ ...formValues, [slot.param.name]: [...existing, fileValue] });
          } else {
            onFormValuesChange({ ...formValues, [slot.param.name]: fileValue });
          }
        } else if (hasFrames) {
          // No generic slot — drop into first empty frame slot
          const firstFrame = frames?.first;
          const lastFrame = frames?.last;
          if (firstFrame && !formValues[firstFrame.param.name]) {
            onFormValuesChange({ ...formValues, [firstFrame.param.name]: fileValue });
          } else if (lastFrame && !formValues[lastFrame.param.name]) {
            onFormValuesChange({ ...formValues, [lastFrame.param.name]: fileValue });
          }
        }
      } catch {
        // Invalid data — ignore
      }
    },
    [enabled, hasFrames, frames, slot, isGenerating, kind, formValues, onFormValuesChange]
  );

  if (!enabled && !hasFrames) {
    return (
      <Tooltip delay={300}>
        <Tooltip.Trigger>
          <div
            className="flex aspect-square w-full cursor-not-allowed flex-col items-center justify-center gap-0.5 rounded-lg border border-dashed border-border/40 bg-surface-2/30 opacity-40"
            onDragOver={(e) => { e.preventDefault(); e.dataTransfer.dropEffect = "none"; }}
          >
            <Icon className="size-4 text-muted" />
            <span className="text-[10px] text-muted">{label}</span>
          </div>
        </Tooltip.Trigger>
        <Tooltip.Content>{t("input.refNotSupported")}</Tooltip.Content>
      </Tooltip>
    );
  }

  const value = slot ? formValues[slot.param.name] : undefined;
  const files: FileValue[] = slot
    ? (slot.isList ? ((value as FileValue[] | undefined) ?? []) : value ? [value as FileValue] : [])
    : [];
  const hasFiles = files.length > 0;
  const canAddMore = slot ? (slot.isList ? files.length < slot.maxCount : files.length === 0) : false;

  // Check if any frame has a value (for showing indicator)
  const hasAnyFrameValue = hasFrames && (
    !!(frames!.first && formValues[frames!.first.param.name]) ||
    !!(frames!.last && formValues[frames!.last.param.name])
  );

  if (!hasFiles && !hasAnyFrameValue) {
    // If has frames, show popover on click; otherwise just open file picker
    if (hasFrames) {
      return (
        <div
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onDrop={handleDrop}
        >
          <Popover>
            <Popover.Trigger>
              <button
                type="button"
                className={`flex aspect-square w-full flex-col items-center justify-center gap-0.5 rounded-lg border border-dashed text-muted transition-colors ${
                  isDragOver
                    ? "border-accent bg-accent/10 text-accent"
                    : "border-border bg-surface-2/50 hover:border-accent/50 hover:text-accent"
                }`}
                disabled={isGenerating}
              >
                <Plus className="size-3.5" />
                <span className="text-[10px]">{label}</span>
              </button>
            </Popover.Trigger>
            <Popover.Content placement="right" className="w-56 p-0">
              <Popover.Dialog className="p-0">
                <div className="flex flex-col gap-3 p-2">
                  {/* Frame uploads */}
                  {frames!.first && frames!.last ? (
                    <FramePairSection
                      first={frames!.first}
                      last={frames!.last}
                      formValues={formValues}
                      onFormValuesChange={onFormValuesChange}
                      fileInputRefs={fileInputRefs}
                      handleFileSelect={handleFileSelect}
                      removeFile={removeFile}
                      isGenerating={isGenerating}
                      uploadingIds={uploadingIds}
                    />
                  ) : (frames!.first || frames!.last) ? (
                    <div>
                      <p className="mb-1.5 px-1 text-[11px] font-medium text-muted">帧控制</p>
                      <FrameUploadSlot
                        frame={(frames!.first || frames!.last)!}
                        formValues={formValues}
                        onFormValuesChange={onFormValuesChange}
                        fileInputRefs={fileInputRefs}
                        handleFileSelect={handleFileSelect}
                        removeFile={removeFile}
                        isGenerating={isGenerating}
                        uploadingIds={uploadingIds}
                      />
                    </div>
                  ) : null}
                  {/* Reference image upload */}
                  {slot && (
                    <div>
                      <p className="mb-1.5 px-1 text-[11px] font-medium text-muted">参考图</p>
                      <button
                        type="button"
                        className="flex w-full items-center justify-center gap-1 rounded-md border border-dashed border-border py-2 text-xs text-muted transition-colors hover:border-accent/50 hover:text-accent"
                        onClick={() => fileInputRefs.current[slot.param.name]?.click()}
                        disabled={isGenerating}
                      >
                        <Plus className="size-3" />
                        <span>添加图片</span>
                      </button>
                    </div>
                  )}
                </div>
              </Popover.Dialog>
            </Popover.Content>
          </Popover>
          {slot && (
            <input
              ref={(el) => { fileInputRefs.current[slot.param.name] = el; }}
              type="file"
              accept={slot.accept}
              multiple={slot.isList}
              className="hidden"
              onChange={handleFileSelect(slot.param.name, slot.isList, slot.maxCount)}
            />
          )}
        </div>
      );
    }

    return (
      <div
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
      >
        <button
          type="button"
          className={`flex aspect-square w-full flex-col items-center justify-center gap-0.5 rounded-lg border border-dashed text-muted transition-colors ${
            isDragOver
              ? "border-accent bg-accent/10 text-accent"
              : "border-border bg-surface-2/50 hover:border-accent/50 hover:text-accent"
          }`}
          onClick={() => slot && fileInputRefs.current[slot.param.name]?.click()}
          disabled={isGenerating}
        >
          <Plus className="size-3.5" />
          <span className="text-[10px]">{label}</span>
        </button>
        {slot && (
          <input
            ref={(el) => { fileInputRefs.current[slot.param.name] = el; }}
            type="file"
            accept={slot.accept}
            multiple={slot.isList}
            className="hidden"
            onChange={handleFileSelect(slot.param.name, slot.isList, slot.maxCount)}
          />
        )}
      </div>
    );
  }

  return (
    <div
      className="relative"
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
    >
      {isDragOver && (
        <div className="absolute inset-0 z-20 flex items-center justify-center rounded-lg border-2 border-dashed border-accent bg-accent/10">
          <Plus className="size-4 text-accent" />
        </div>
      )}
      <Popover>
        <Popover.Trigger>
          <button type="button" className="relative aspect-square w-full cursor-pointer">
            {files.length > 0 ? (
              <>
                {files.map((file, idx) => {
                  const rot = STACK_ROTATIONS[idx % STACK_ROTATIONS.length];
                  const isTop = idx === files.length - 1;
                  return (
                    <div
                      key={file.assetId}
                      className="absolute inset-0 overflow-hidden rounded-lg border border-border bg-surface-2 shadow-sm transition-transform"
                      style={{
                        transform: `rotate(${rot}deg)`,
                        zIndex: idx,
                        opacity: isTop ? 1 : 0.85,
                      }}
                    >
                      <FileThumb file={file} kind={kind} isUploading={uploadingIds.has(file.assetId)} />
                    </div>
                  );
                })}
                {files.length > 1 && (
                  <div className="absolute -right-1 -top-1 z-10 flex size-4 items-center justify-center rounded-full bg-accent text-[10px] font-bold text-white shadow">
                    {files.length}
                  </div>
                )}
              </>
            ) : (
              /* Only frame values — show frame thumbnails stacked */
              <>
                {[frames?.first, frames?.last]
                  .filter((f): f is FrameParam => !!(f && formValues[f.param.name]))
                  .map((f, idx, arr) => {
                    const fv = formValues[f.param.name] as FileValue;
                    const rot = STACK_ROTATIONS[idx % STACK_ROTATIONS.length];
                    const isTop = idx === arr.length - 1;
                    return (
                      <div
                        key={f.param.name}
                        className="absolute inset-0 overflow-hidden rounded-lg border border-border bg-surface-2 shadow-sm transition-transform"
                        style={{
                          transform: `rotate(${rot}deg)`,
                          zIndex: idx,
                          opacity: isTop ? 1 : 0.85,
                        }}
                      >
                        <NextImage src={fv.url} alt="" fill className="object-cover" sizes="80px" />
                      </div>
                    );
                  })}
              </>
            )}
          </button>
        </Popover.Trigger>
        <Popover.Content placement="right" className="w-56 p-0">
          <Popover.Dialog className="p-0">
            <div className="flex flex-col gap-3 p-2">
              {/* Frame uploads */}
              {hasFrames && frames!.first && frames!.last ? (
                <FramePairSection
                  first={frames!.first}
                  last={frames!.last}
                  formValues={formValues}
                  onFormValuesChange={onFormValuesChange}
                  fileInputRefs={fileInputRefs}
                  handleFileSelect={handleFileSelect}
                  removeFile={removeFile}
                  isGenerating={isGenerating}
                  uploadingIds={uploadingIds}
                />
              ) : hasFrames && (frames!.first || frames!.last) ? (
                <div>
                  <p className="mb-1.5 px-1 text-[11px] font-medium text-muted">帧控制</p>
                  <FrameUploadSlot
                    frame={(frames!.first || frames!.last)!}
                    formValues={formValues}
                    onFormValuesChange={onFormValuesChange}
                    fileInputRefs={fileInputRefs}
                    handleFileSelect={handleFileSelect}
                    removeFile={removeFile}
                    isGenerating={isGenerating}
                    uploadingIds={uploadingIds}
                  />
                </div>
              ) : null}

              {/* Reference images */}
              {slot && (
                <div>
                  {hasFrames && <p className="mb-1.5 px-1 text-[11px] font-medium text-muted">参考图</p>}
                  {!hasFrames && (
                    <p className="mb-2 px-1 text-xs font-medium text-foreground">
                      {label}
                      {slot.isList && (
                        <span className="ml-1 text-muted">({files.length}/{slot.maxCount})</span>
                      )}
                    </p>
                  )}
                  <div className="grid grid-cols-3 gap-1.5">
                    {files.map((file, idx) => (
                      <div
                        key={file.assetId}
                        className="group/ref relative aspect-square overflow-hidden rounded-md border border-border bg-surface-2"
                      >
                        <FileThumb file={file} kind={kind} isUploading={uploadingIds.has(file.assetId)} />
                        <button
                          type="button"
                          className="absolute right-0.5 top-0.5 flex size-4 items-center justify-center rounded-full bg-foreground/80 text-background opacity-0 transition-opacity group-hover/ref:opacity-100"
                          onClick={() => removeFile(slot.param.name, slot.isList, slot.isList ? idx : undefined)}
                        >
                          <X className="size-2.5" />
                        </button>
                      </div>
                    ))}
                    {canAddMore && (
                      <button
                        type="button"
                        className="flex aspect-square items-center justify-center rounded-md border border-dashed border-border text-muted transition-colors hover:border-accent/50 hover:text-accent"
                        onClick={() => fileInputRefs.current[slot.param.name]?.click()}
                        disabled={isGenerating}
                      >
                        <Plus className="size-4" />
                      </button>
                    )}
                  </div>
                </div>
              )}
            </div>
          </Popover.Dialog>
        </Popover.Content>
      </Popover>

      {canAddMore && slot && (
        <button
          type="button"
          className="absolute -bottom-1 -right-1 z-10 flex size-5 items-center justify-center rounded-full border border-border bg-background text-muted shadow-sm transition-colors hover:border-accent hover:text-accent"
          onClick={() => fileInputRefs.current[slot.param.name]?.click()}
          disabled={isGenerating}
        >
          <Plus className="size-3" />
        </button>
      )}

      {slot && (
        <input
          ref={(el) => { fileInputRefs.current[slot.param.name] = el; }}
          type="file"
          accept={slot.accept}
          multiple={slot.isList}
          className="hidden"
          onChange={handleFileSelect(slot.param.name, slot.isList, slot.maxCount)}
        />
      )}
    </div>
  );
}

// ── FrameUploadSlot: compact frame upload (first/last frame) ──
function FrameUploadSlot({
  frame,
  formValues,
  onFormValuesChange,
  fileInputRefs,
  handleFileSelect,
  removeFile,
  isGenerating,
  uploadingIds,
}: {
  frame: FrameParam;
  formValues: Record<string, unknown>;
  onFormValuesChange: (values: Record<string, unknown>) => void;
  fileInputRefs: React.MutableRefObject<Record<string, HTMLInputElement | null>>;
  handleFileSelect: (paramName: string, isList: boolean, maxCount: number) => (e: React.ChangeEvent<HTMLInputElement>) => void;
  removeFile: (paramName: string, isList: boolean, index?: number) => void;
  isGenerating: boolean;
  uploadingIds: Set<string>;
}) {
  const value = formValues[frame.param.name] as FileValue | undefined;
  const isFileUploading = value ? uploadingIds.has(value.assetId) : false;

  return (
    <div className="flex flex-col items-center gap-0.5">
      {value ? (
        <div className="group/frame relative aspect-square w-full overflow-hidden rounded-md border border-border bg-surface-2">
          <NextImage src={value.url} alt="" fill className="object-cover" sizes="80px" />
          {isFileUploading && (
            <div className="absolute inset-0 flex items-center justify-center bg-black/40">
              <Loader2 className="size-3 animate-spin text-white" />
            </div>
          )}
          <button
            type="button"
            className="absolute right-0 top-0 flex size-4 items-center justify-center rounded-bl-md bg-foreground/80 text-background opacity-0 transition-opacity group-hover/frame:opacity-100"
            onClick={() => removeFile(frame.param.name, false)}
          >
            <X className="size-2.5" />
          </button>
        </div>
      ) : (
        <button
          type="button"
          className="flex aspect-square w-full flex-col items-center justify-center gap-0.5 rounded-md border border-dashed border-border bg-surface-2/50 text-muted transition-colors hover:border-accent/50 hover:text-accent"
          onClick={() => fileInputRefs.current[frame.param.name]?.click()}
          disabled={isGenerating}
        >
          <Plus className="size-3" />
        </button>
      )}
      <span className="text-[9px] leading-tight text-muted">{frame.label}</span>
      <input
        ref={(el) => { fileInputRefs.current[frame.param.name] = el; }}
        type="file"
        accept="image/*"
        className="hidden"
        onChange={handleFileSelect(frame.param.name, false, 1)}
      />
    </div>
  );
}

// ── Frame pair with swap button ──
function FramePairSection({
  first,
  last,
  formValues,
  onFormValuesChange,
  fileInputRefs,
  handleFileSelect,
  removeFile,
  isGenerating,
  uploadingIds,
}: {
  first: FrameParam;
  last: FrameParam;
  formValues: Record<string, unknown>;
  onFormValuesChange: (values: Record<string, unknown>) => void;
  fileInputRefs: React.MutableRefObject<Record<string, HTMLInputElement | null>>;
  handleFileSelect: (paramName: string, isList: boolean, maxCount: number) => (e: React.ChangeEvent<HTMLInputElement>) => void;
  removeFile: (paramName: string, isList: boolean, index?: number) => void;
  isGenerating: boolean;
  uploadingIds: Set<string>;
}) {
  const firstVal = formValues[first.param.name] as FileValue | undefined;
  const lastVal = formValues[last.param.name] as FileValue | undefined;
  const canSwap = !!(firstVal || lastVal);

  const handleSwap = useCallback(() => {
    onFormValuesChange({
      ...formValues,
      [first.param.name]: lastVal ?? null,
      [last.param.name]: firstVal ?? null,
    });
  }, [formValues, onFormValuesChange, first.param.name, last.param.name, firstVal, lastVal]);

  return (
    <div>
      <p className="mb-1.5 px-1 text-[11px] font-medium text-muted">帧控制</p>
      <div className="flex items-start gap-1">
        <div className="flex-1">
          <FrameUploadSlot
            frame={first}
            formValues={formValues}
            onFormValuesChange={onFormValuesChange}
            fileInputRefs={fileInputRefs}
            handleFileSelect={handleFileSelect}
            removeFile={removeFile}
            isGenerating={isGenerating}
            uploadingIds={uploadingIds}
          />
        </div>
        <Tooltip delay={300}>
          <Tooltip.Trigger>
            <button
              type="button"
              className="mt-3 flex size-5 shrink-0 items-center justify-center rounded text-muted transition-colors hover:bg-muted/15 hover:text-foreground disabled:opacity-30"
              onClick={handleSwap}
              disabled={!canSwap || isGenerating}
            >
              <ArrowLeftRight className="size-3" />
            </button>
          </Tooltip.Trigger>
          <Tooltip.Content>交换首尾帧</Tooltip.Content>
        </Tooltip>
        <div className="flex-1">
          <FrameUploadSlot
            frame={last}
            formValues={formValues}
            onFormValuesChange={onFormValuesChange}
            fileInputRefs={fileInputRefs}
            handleFileSelect={handleFileSelect}
            removeFile={removeFile}
            isGenerating={isGenerating}
            uploadingIds={uploadingIds}
          />
        </div>
      </div>
    </div>
  );
}

// ── Inline param renderer (shared by basic + advanced) ──
function InlineParam({
  param,
  value,
  onChange,
  disabled,
}: {
  param: InputParamDefinition;
  value: unknown;
  onChange: (name: string, val: unknown) => void;
  disabled: boolean;
}) {
  const rawType = param.type === "INTEGER" ? "NUMBER" : param.type === "STRING" ? "TEXT" : param.type;

  // Enum select
  if (param.enum && param.enum.length > 0) {
    return (
      <Tooltip delay={300}>
        <Select
          aria-label={param.label}
          variant="secondary"
          value={String(value ?? param.defaultValue ?? param.default ?? "")}
          onChange={(v) => { if (v !== null) onChange(param.name, v); }}
          isDisabled={disabled}
        >
          <Select.Trigger className="h-8 shrink-0 gap-1.5 border-0 bg-transparent px-2 hover:bg-muted/20">
            <Select.Value>
              {({ isPlaceholder }) => (
                <span className="block min-w-0 truncate text-xs">
                  {isPlaceholder ? param.label : String(value ?? param.defaultValue ?? param.default ?? param.label)}
                </span>
              )}
            </Select.Value>
          </Select.Trigger>
          <Select.Popover>
            <ListBox>
              {param.enum.map((opt) => (
                <ListBox.Item key={opt} id={opt} textValue={opt} className="data-[selected=true]:bg-accent/10">
                  <Label>{opt}</Label>
                </ListBox.Item>
              ))}
            </ListBox>
          </Select.Popover>
        </Select>
        <Tooltip.Content>
          <div>
            <p className="font-medium">{param.label}</p>
            {param.description && <p className="mt-0.5 text-xs text-muted">{param.description}</p>}
          </div>
        </Tooltip.Content>
      </Tooltip>
    );
  }

  // Number
  if (rawType === "NUMBER") {
    return (
      <Tooltip delay={300}>
        <Tooltip.Trigger>
          <div className="flex h-8 shrink-0 items-center rounded-md border-0 bg-transparent px-2 hover:bg-muted/20">
            <input
              type="number"
              className="w-14 bg-transparent text-xs text-foreground outline-none"
              value={value as number ?? (param.defaultValue as number) ?? ""}
              onChange={(e) => onChange(param.name, e.target.valueAsNumber)}
              min={param.min}
              max={param.max}
              disabled={disabled}
            />
          </div>
        </Tooltip.Trigger>
        <Tooltip.Content>
          <div>
            <p className="font-medium">{param.label}</p>
            {param.description && <p className="mt-0.5 text-xs text-muted">{param.description}</p>}
          </div>
        </Tooltip.Content>
      </Tooltip>
    );
  }

  // Boolean
  if (rawType === "BOOLEAN") {
    return (
      <Tooltip delay={300}>
        <Tooltip.Trigger>
          <button
            type="button"
            className={`flex h-8 shrink-0 items-center rounded-md px-2 text-xs transition-colors ${
              value ? "bg-accent/10 text-accent" : "bg-transparent text-foreground-2 hover:bg-muted/20"
            }`}
            onClick={() => onChange(param.name, !value)}
            disabled={disabled}
          >
            {param.label}
          </button>
        </Tooltip.Trigger>
        <Tooltip.Content>
          <div>
            <p className="font-medium">{param.label}</p>
            {param.description && <p className="mt-0.5 text-xs text-muted">{param.description}</p>}
          </div>
        </Tooltip.Content>
      </Tooltip>
    );
  }

  // Text
  if (rawType === "TEXT" || rawType === "TEXTAREA") {
    return (
      <Tooltip delay={300}>
        <Tooltip.Trigger>
          <div className="flex h-8 shrink-0 items-center rounded-md border-0 bg-transparent px-2 hover:bg-muted/20">
            <input
              type="text"
              className="w-24 bg-transparent text-xs text-foreground outline-none"
              value={(value as string) ?? (param.defaultValue as string) ?? ""}
              onChange={(e) => onChange(param.name, e.target.value)}
              placeholder={param.label}
              disabled={disabled}
            />
          </div>
        </Tooltip.Trigger>
        <Tooltip.Content>
          <div>
            <p className="font-medium">{param.label}</p>
            {param.description && <p className="mt-0.5 text-xs text-muted">{param.description}</p>}
          </div>
        </Tooltip.Content>
      </Tooltip>
    );
  }

  return null;
}

// ══════════════════════════════════════════════════════════════
// Main component
// ══════════════════════════════════════════════════════════════

interface InspirationInputPanelProps {
  generationType: MediaType;
  onGenerationTypeChange: (type: MediaType) => void;
  providers: AvailableProviderDTO[];
  selectedProviderId: string | null;
  selectedProvider: AvailableProviderDTO | null;
  onProviderChange: (id: string) => void;
  isLoadingProviders: boolean;
  prompt: string;
  onPromptChange: (value: string) => void;
  formValues: Record<string, unknown>;
  onFormValuesChange: (values: Record<string, unknown>) => void;
  inputSchema: InputSchemaDTO | null;
  basicParams: InputParamDefinition[];
  basicNonEnumParams: InputParamDefinition[];
  advancedParams: InputParamDefinition[];
  styles: StyleListDTO[];
  isLoadingStyles: boolean;
  onGenerate: () => void;
  onOptimize: () => void;
  canGenerate: boolean;
  isGenerating: boolean;
  isOptimizing: boolean;
  hasGeneratedPrompt: boolean;
  canOptimize: boolean;
  estimatedCost: number | null;
}

export function InspirationInputPanel({
  generationType,
  onGenerationTypeChange,
  providers,
  selectedProviderId,
  selectedProvider,
  onProviderChange,
  isLoadingProviders,
  prompt,
  onPromptChange,
  formValues,
  onFormValuesChange,
  inputSchema,
  basicParams,
  basicNonEnumParams,
  advancedParams,
  styles,
  isLoadingStyles,
  onGenerate,
  onOptimize,
  canGenerate,
  isGenerating,
  isOptimizing,
  hasGeneratedPrompt,
  canOptimize,
  estimatedCost,
}: InspirationInputPanelProps) {
  const t = useTranslations("workspace.inspiration");
  const tType = useTranslations("workspace.inspiration.type");
  const fileInputRefs = useRef<Record<string, HTMLInputElement | null>>({});
  const [uploadingIds, setUploadingIds] = useState<Set<string>>(new Set());
  const formValuesRef = useRef(formValues);
  formValuesRef.current = formValues;

  const CurrentTypeIcon = TYPE_OPTIONS.find((o) => o.type === generationType)?.icon ?? Image;
  const currentTypeLabel = tType(TYPE_OPTIONS.find((o) => o.type === generationType)?.labelKey ?? "image");

  // ── Detect frame params (first/last frame for video models) ──
  const frameParams = useMemo(() => {
    if (!inputSchema) return { first: null as FrameParam | null, last: null as FrameParam | null };
    let first: FrameParam | null = null;
    let last: FrameParam | null = null;
    for (const param of inputSchema.params) {
      if (param.type !== "IMAGE") continue;
      const role = isFrameParam(param.name);
      if (role === "first" && !first) first = { param, label: param.label || "首帧" };
      if (role === "last" && !last) last = { param, label: param.label || "尾帧" };
    }
    return { first, last };
  }, [inputSchema]);

  const frameParamNames = useMemo(() => {
    const names = new Set<string>();
    if (frameParams.first) names.add(frameParams.first.param.name);
    if (frameParams.last) names.add(frameParams.last.param.name);
    return names;
  }, [frameParams]);

  // ── Compute reference slots from inputSchema (excluding frame params) ──
  const referenceSlotMap = useMemo(() => {
    const map = new Map<RefMediaKind, ReferenceSlot>();
    if (!inputSchema) return map;
    for (const param of inputSchema.params) {
      if (!FILE_PARAM_TYPES.has(param.type)) continue;
      if (frameParamNames.has(param.name)) continue;
      const kind = getRefMediaKind(param.type);
      if (!kind || map.has(kind)) continue;
      const isList = param.type.endsWith("_LIST");
      map.set(kind, {
        kind,
        param,
        isList,
        maxCount: isList ? (param.fileConfig?.maxCount ?? 5) : 1,
        accept: param.fileConfig?.accept ?? getAccept(kind),
      });
    }
    return map;
  }, [inputSchema, frameParamNames]);

  // ── Compute mentionable assets from ref slots ──
  const mentionItems = useMemo(() => {
    const items: FileMentionItem[] = [];
    for (const kind of ALL_REF_KINDS) {
      const slot = referenceSlotMap.get(kind);
      if (!slot) continue;
      const value = formValues[slot.param.name];
      const files: FileValue[] = slot.isList
        ? ((value as FileValue[] | undefined) ?? [])
        : value ? [value as FileValue] : [];
      files.forEach((file, idx) => {
        items.push({
          kind: "file",
          name: `${KIND_CN_PREFIX[kind]}${idx + 1}`,
          mediaKind: kind,
          thumbnailUrl: file.url,
          thumbnailType: kind === "IMAGE" ? "image" : kind === "VIDEO" ? "video" : "icon",
          fileUrl: file.url,
          mimeType: file.mimeType,
          iconFallback: kind === "AUDIO" ? "\u266B" : undefined,
        });
      });
    }
    return items;
  }, [referenceSlotMap, formValues]);

  const mentionItemMap = useMemo(
    () => new Map(mentionItems.map((a) => [a.name, a])),
    [mentionItems]
  );

  // ── Style value ──
  const selectedStyleId = String(formValues.styleId ?? "");
  const selectedStyleName = styles.find((s) => s.id === selectedStyleId)?.name;

  // ── File handling (upload to workspace then use) ──
  const uploadFileToWorkspace = useCallback(
    async (file: File): Promise<FileValue> => {
      const initResponse = await projectService.initAssetUpload({
        name: file.name,
        fileName: file.name,
        mimeType: file.type,
        fileSize: file.size,
        scope: "WORKSPACE",
      });

      await fetch(initResponse.uploadUrl, {
        method: initResponse.method,
        headers: initResponse.headers,
        body: file,
      });

      await projectService.confirmAssetUpload(initResponse.assetId, {
        fileKey: initResponse.fileKey,
        actualFileSize: file.size,
      });

      return {
        assetId: initResponse.assetId,
        url: initResponse.uploadUrl.split("?")[0],
        name: file.name,
        mimeType: file.type,
        fileSize: file.size,
      };
    },
    [],
  );

  const handleFileSelect = useCallback(
    (paramName: string, isList: boolean, maxCount: number) =>
      async (e: React.ChangeEvent<HTMLInputElement>) => {
        const files = e.target.files;
        if (!files || files.length === 0) return;

        // Capture files before resetting input — FileList is a live reference
        // that gets cleared when input.value is set to ""
        const localFiles = Array.from(files);
        e.target.value = "";
        const placeholders: FileValue[] = localFiles.map((file) => {
          const placeholderId = `uploading_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`;
          return {
            assetId: placeholderId,
            url: URL.createObjectURL(file),
            name: file.name,
            mimeType: file.type,
            fileSize: file.size,
          };
        });

        // Set placeholders immediately for preview
        if (isList) {
          const existing = (formValues[paramName] as FileValue[] | undefined) ?? [];
          const merged = [...existing, ...placeholders].slice(0, maxCount);
          onFormValuesChange({ ...formValues, [paramName]: merged });
        } else {
          onFormValuesChange({ ...formValues, [paramName]: placeholders[0] });
        }

        // Track uploading state
        const pIds = new Set(placeholders.map((p) => p.assetId));
        setUploadingIds((prev) => new Set([...prev, ...pIds]));

        // Upload all files concurrently
        await Promise.all(
          localFiles.map(async (file, idx) => {
            const placeholder = placeholders[idx];
            try {
              const uploaded = await uploadFileToWorkspace(file);
              // Replace placeholder with real value (read latest formValues from ref)
              const latest = formValuesRef.current;
              const current = latest[paramName];
              if (isList && Array.isArray(current)) {
                onFormValuesChange({
                  ...latest,
                  [paramName]: current.map((v: FileValue) =>
                    v.assetId === placeholder.assetId ? uploaded : v,
                  ),
                });
              } else if (!isList && current && (current as FileValue).assetId === placeholder.assetId) {
                onFormValuesChange({ ...latest, [paramName]: uploaded });
              }
              URL.revokeObjectURL(placeholder.url);
            } catch (err) {
              console.error("File upload failed:", err);
              toast.danger(t("input.uploadFailed"));
              // Remove failed placeholder
              const latest = formValuesRef.current;
              const current = latest[paramName];
              if (isList && Array.isArray(current)) {
                onFormValuesChange({
                  ...latest,
                  [paramName]: current.filter((v: FileValue) => v.assetId !== placeholder.assetId),
                });
              } else if (!isList && current && (current as FileValue).assetId === placeholder.assetId) {
                onFormValuesChange({ ...latest, [paramName]: null });
              }
              URL.revokeObjectURL(placeholder.url);
            } finally {
              setUploadingIds((prev) => {
                const next = new Set(prev);
                next.delete(placeholder.assetId);
                return next;
              });
            }
          }),
        );
      },
    [formValues, onFormValuesChange, uploadFileToWorkspace, t],
  );

  const removeFile = useCallback(
    (paramName: string, isList: boolean, index?: number) => {
      if (isList && index !== undefined) {
        const existing = (formValues[paramName] as FileValue[] | undefined) ?? [];
        onFormValuesChange({
          ...formValues,
          [paramName]: existing.filter((_, i) => i !== index),
        });
      } else {
        onFormValuesChange({ ...formValues, [paramName]: isList ? [] : null });
      }
    },
    [formValues, onFormValuesChange]
  );

  const handleParamChange = useCallback(
    (name: string, val: unknown) => {
      onFormValuesChange({ ...formValues, [name]: val });
    },
    [formValues, onFormValuesChange]
  );

  return (
    <div className="pointer-events-auto mx-auto w-full max-w-5xl">
      {/* Options bar: Style + Basic params + Advanced params (all flattened) */}
      <div className="pb-2">
        <ScrollShadow orientation="horizontal" hideScrollBar>
          <div className="flex gap-2">
            {/* Style Selector — IMAGE / VIDEO only */}
            {(generationType === "IMAGE" || generationType === "VIDEO") && (
              <Tooltip delay={300}>
                <Select
                  aria-label={t("input.style")}
                  variant="secondary"
                  value={selectedStyleId}
                  onChange={(value) => onFormValuesChange({ ...formValues, styleId: String(value ?? "") })}
                  isDisabled={isLoadingStyles || isGenerating}
                >
                  <Select.Trigger className="h-8 shrink-0 gap-1.5 border-0 bg-transparent px-2 hover:bg-muted/20">
                    <Select.Value>
                      {({ isPlaceholder }) => (
                        <span className="flex min-w-0 items-center gap-1.5 text-xs">
                          <Palette className="size-3.5 shrink-0 text-accent" />
                          <span className="truncate">
                            {isLoadingStyles
                              ? "..."
                              : isPlaceholder || !selectedStyleId
                                ? t("input.style")
                                : selectedStyleName}
                          </span>
                        </span>
                      )}
                    </Select.Value>
                  </Select.Trigger>
                  <Select.Popover className="w-56">
                    <ListBox>
                      <ListBox.Item key="no-style" id="" textValue={t("input.noStyle")} className="data-[selected=true]:bg-accent/10">
                        <span className="text-muted">{t("input.noStyle")}</span>
                      </ListBox.Item>
                      {styles.map((style) => (
                        <ListBox.Item key={style.id} id={style.id} textValue={style.name} className="data-[selected=true]:bg-accent/10">
                          {style.coverUrl && <NextImage src={style.coverUrl} alt="" width={24} height={24} className="size-6 rounded object-cover" />}
                          <div className="flex flex-col">
                            <Label>{style.name}</Label>
                            {style.description && <Description className="text-xs line-clamp-1">{style.description}</Description>}
                          </div>
                        </ListBox.Item>
                      ))}
                    </ListBox>
                  </Select.Popover>
                </Select>
                <Tooltip.Content>{t("input.styleTooltip")}</Tooltip.Content>
              </Tooltip>
            )}

            {/* Inline basic params */}
            {basicParams.map((param) => (
              <InlineParam
                key={param.name}
                param={param}
                value={formValues[param.name]}
                onChange={handleParamChange}
                disabled={isGenerating}
              />
            ))}
            {basicNonEnumParams.map((param) => (
              <InlineParam
                key={param.name}
                param={param}
                value={formValues[param.name]}
                onChange={handleParamChange}
                disabled={isGenerating}
              />
            ))}

            {/* Advanced params — Popover button */}
            {advancedParams.length > 0 && (
              <Popover>
                <Tooltip delay={300}>
                  <Popover.Trigger>
                    <Button
                      size="sm"
                      isIconOnly
                      variant="ghost"
                      className="h-8 w-8 shrink-0"
                      isDisabled={isGenerating}
                    >
                      <SlidersHorizontal className="size-3.5" />
                    </Button>
                  </Popover.Trigger>
                  <Tooltip.Content>{t("input.advancedParams")}</Tooltip.Content>
                </Tooltip>
                <Popover.Content placement="top" className="w-80 p-0">
                  <Popover.Dialog className="p-0">
                    <div className="flex flex-col gap-3 p-3">
                      <p className="text-xs font-medium text-foreground">{t("input.advancedParams")}</p>
                      {advancedParams.map((param) => {
                        const pType = param.type === "INTEGER" ? "NUMBER" : param.type === "STRING" ? "TEXT" : param.type;
                        const hasEnum = !!(param.enum || param.options);

                        // STRING/TEXT with enum → SELECT
                        if (hasEnum || pType === "SELECT") {
                          return (
                            <FormSelectField
                              key={param.name}
                              param={param}
                              value={String(formValues[param.name] ?? param.defaultValue ?? param.default ?? "")}
                              onChange={(v) => handleParamChange(param.name, v)}
                              disabled={isGenerating}
                            />
                          );
                        }

                        if (pType === "BOOLEAN") {
                          return (
                            <FormBooleanField
                              key={param.name}
                              param={param}
                              value={(formValues[param.name] as boolean) ?? (param.defaultValue as boolean) ?? false}
                              onChange={(v) => handleParamChange(param.name, v)}
                              disabled={isGenerating}
                            />
                          );
                        }

                        if (pType === "NUMBER") {
                          return (
                            <FormNumberField
                              key={param.name}
                              param={param}
                              value={formValues[param.name] as number | undefined}
                              onChange={(v) => handleParamChange(param.name, v)}
                              disabled={isGenerating}
                            />
                          );
                        }

                        return (
                          <FormTextField
                            key={param.name}
                            param={param}
                            value={(formValues[param.name] as string) ?? (param.defaultValue as string) ?? ""}
                            onChange={(v) => handleParamChange(param.name, v)}
                            disabled={isGenerating}
                          />
                        );
                      })}
                    </div>
                  </Popover.Dialog>
                </Popover.Content>
              </Popover>
            )}
          </div>
        </ScrollShadow>
      </div>

      {/* Bottom row: Left ref card + Right prompt card (aligned heights) */}
      <div className="flex items-stretch gap-2">
        {/* Left: Reference Asset Card */}
        <Card variant="tertiary" className="flex w-20 shrink-0 flex-col gap-3 overflow-visible border-white/10 bg-white/60 p-1.5 backdrop-blur-xl dark:bg-white/5">
          {ALL_REF_KINDS.map((kind) => (
            <RefSlot
              key={kind}
              kind={kind}
              slot={referenceSlotMap.get(kind) ?? null}
              formValues={formValues}
              onFormValuesChange={onFormValuesChange}
              fileInputRefs={fileInputRefs}
              handleFileSelect={handleFileSelect}
              removeFile={removeFile}
              isGenerating={isGenerating}
              uploadingIds={uploadingIds}
              t={t}
              frames={kind === "IMAGE" ? frameParams : undefined}
            />
          ))}
          <div className="flex-1" />
        </Card>

        {/* Right: Prompt Card */}
        <Card variant="tertiary" className="relative min-w-0 flex-1 gap-0 border-white/10 bg-white/60 p-0 backdrop-blur-xl dark:bg-white/5">
          {/* Prompt editor with @-mention support */}
          <div className="relative flex-1 overflow-hidden">
            <MentionPromptEditor
              value={prompt}
              onChange={onPromptChange}
              onSubmit={onGenerate}
              canSubmit={canGenerate && uploadingIds.size === 0}
              placeholder={t("input.placeholder")}
              disabled={isGenerating || isOptimizing}
              mentionItems={mentionItems}
              mentionItemMap={mentionItemMap}
            />

            {isOptimizing && (
              <div className="absolute inset-0 z-10 flex flex-col items-center justify-center gap-2 rounded-t-xl bg-background/80">
                <Spinner size="md" color="accent" />
                <span className="text-sm font-medium text-foreground">{t("input.optimizing")}</span>
              </div>
            )}
          </div>

          {/* Bottom Action Bar */}
          <div className="flex items-center justify-between bg-muted/5 px-3 py-2">
            {/* Left: Type + Model + Credits */}
            <div className="flex min-w-0 items-center gap-1">
              <Select
                aria-label="Generation type"
                className="w-auto shrink-0"
                variant="secondary"
                value={generationType}
                onChange={(value) => value && onGenerationTypeChange(value as MediaType)}
              >
                <Select.Trigger className="h-7 gap-1 border-0 bg-transparent px-1.5 hover:bg-muted/20">
                  <Select.Value>
                    {() => (
                      <span className="flex items-center gap-1 text-xs">
                        <CurrentTypeIcon className="size-3.5 shrink-0 text-accent" />
                        <span>{currentTypeLabel}</span>
                      </span>
                    )}
                  </Select.Value>
                </Select.Trigger>
                <Select.Popover>
                  <ListBox>
                    {TYPE_OPTIONS.map((opt) => {
                      const Icon = opt.icon;
                      return (
                        <ListBox.Item
                          key={opt.type}
                          id={opt.type}
                          textValue={tType(opt.labelKey)}
                          className="data-[selected=true]:bg-accent/10"
                        >
                          <Icon className="size-4 text-muted" />
                          <Label>{tType(opt.labelKey)}</Label>
                        </ListBox.Item>
                      );
                    })}
                  </ListBox>
                </Select.Popover>
              </Select>

              <Select
                aria-label="Model"
                className="shrink"
                variant="secondary"
                value={selectedProviderId}
                onChange={(value) => value && onProviderChange(value as string)}
                isDisabled={isLoadingProviders}
              >
                <Select.Trigger className="h-7 gap-1 border-0 bg-transparent px-1.5 hover:bg-muted/20">
                  <Select.Value>
                    {({ isPlaceholder }) => (
                      <span className="flex min-w-0 items-center gap-1 text-xs">
                        {isLoadingProviders ? (
                          <Spinner size="sm" />
                        ) : selectedProvider?.iconUrl ? (
                          <img src={selectedProvider.iconUrl} alt="" className="size-3.5 shrink-0 rounded" />
                        ) : (
                          <Bot className="size-3.5 shrink-0" />
                        )}
                        <span className="truncate">
                          {isLoadingProviders ? "..." : isPlaceholder ? "Select model" : selectedProvider?.name}
                        </span>
                      </span>
                    )}
                  </Select.Value>
                </Select.Trigger>
                <Select.Popover>
                  {providers.length === 0 ? (
                    <div className="px-3 py-4 text-center text-sm text-muted">No models</div>
                  ) : (
                    <ListBox>
                      {providers.map((p) => (
                        <ListBox.Item
                          key={p.id}
                          id={p.id}
                          textValue={p.name}
                          className="data-[selected=true]:bg-accent/10"
                        >
                          {p.iconUrl ? (
                            <img src={p.iconUrl} alt="" className="size-4 rounded" />
                          ) : (
                            <Bot className="size-4 text-muted" />
                          )}
                          <div className="flex flex-1 flex-col">
                            <Label>{p.name}</Label>
                            {p.creditCost > 0 && <Description>{p.creditCost} credits</Description>}
                          </div>
                        </ListBox.Item>
                      ))}
                    </ListBox>
                  )}
                </Select.Popover>
              </Select>

              {selectedProvider && (estimatedCost ?? selectedProvider.creditCost) > 0 && (
                <Tooltip delay={0}>
                  <Tooltip.Trigger>
                    <div className="flex shrink-0 items-center gap-1 rounded-full bg-warning/10 px-1.5 py-0.5 text-xs font-medium text-warning">
                      <Zap className="size-3" />
                      <span>{estimatedCost ?? selectedProvider.creditCost}</span>
                    </div>
                  </Tooltip.Trigger>
                  <Tooltip.Content>Estimated cost per generation</Tooltip.Content>
                </Tooltip>
              )}
            </div>

            {/* Right: Keyboard hint + Optimize + Generate */}
            <div className="flex shrink-0 items-center gap-2">
              {!isGenerating && (
                <span className="text-xs text-muted">Enter {t("input.generate")}</span>
              )}

              {canOptimize && (
                <Tooltip delay={300}>
                  <Button
                    size="sm"
                    isIconOnly
                    variant="secondary"
                    onPress={onOptimize}
                    isDisabled={!prompt.trim() || isOptimizing || isGenerating}
                  >
                    {isOptimizing ? <Spinner size="sm" /> : <Wand2 className="size-4" />}
                  </Button>
                  <Tooltip.Content>
                    <span>{t("input.optimize")}</span>
                  </Tooltip.Content>
                </Tooltip>
              )}

              <Button
                size="sm"
                isIconOnly
                aria-label={t("input.generate")}
                isDisabled={!canGenerate || uploadingIds.size > 0 || isGenerating}
                isPending={isGenerating}
                onPress={onGenerate}
              >
                {({isPending}) => isPending ? <Spinner color="current" size="sm" /> : <Send className="size-4" />}
              </Button>
            </div>
          </div>
        </Card>
      </div>
    </div>
  );
}
