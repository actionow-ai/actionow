"use client";

import { useMemo, useState, useCallback, type ReactNode } from "react";
import { useTranslations } from "next-intl";
import { ButtonGroup, Button, Card, Chip } from "@heroui/react";
import {ChevronDown, ChevronRight, Image as ImageIcon, SettingsIcon, Video as VideoIcon} from "lucide-react";
import type {
  InputSchemaDTO,
  InputParamDefinition,
  InputGroupDefinition,
  ExclusiveGroupDefinition,
} from "@/lib/api/dto/ai.dto";
import type { EntityType, StyleListDTO } from "@/lib/api/dto/project.dto";
import {
  TextField,
  TextareaField,
  NumberField,
  BooleanField,
  SelectField,
  EntitySelectField,
  StyleSelectField,
  FileUploadField,
  FileListField,
  type FileValue,
} from "./form-fields";

type PromptEntityType = Extract<EntityType, "CHARACTER" | "SCENE" | "PROP" | "STORYBOARD">;

interface DynamicFormProps {
  schema: InputSchemaDTO;
  values: Record<string, unknown>;
  onChange: (values: Record<string, unknown>) => void;
  onFileUpload?: (file: File) => Promise<FileValue>;
  errors?: Record<string, string>;
  disabled?: boolean;
  compact?: boolean;
  /** Hide group labels and render fields directly */
  hideGroupLabels?: boolean;
  scriptId?: string;
  episodeId?: string;
  styles?: StyleListDTO[];
  hiddenFields?: string[];
}

// Field types that should span full width
const FULL_WIDTH_TYPES = new Set([
  "TEXTAREA",
  "IMAGE",
  "VIDEO",
  "AUDIO",
  "DOCUMENT",
  "IMAGE_LIST",
  "VIDEO_LIST",
  "AUDIO_LIST",
  "DOCUMENT_LIST",
  "TEXT_LIST",
  "NUMBER_LIST",
]);

export function DynamicForm({
  schema,
  values,
  onChange,
  onFileUpload,
  errors = {},
  disabled,
  compact = false,
  hideGroupLabels = false,
  scriptId,
  episodeId,
  styles = [],
  hiddenFields = [],
}: DynamicFormProps) {
  const tForm = useTranslations("workspace.aiGeneration.dynamicForm");
  const hiddenFieldSet = useMemo(() => new Set(hiddenFields), [hiddenFields]);
  // Track exclusive group selections
  const [exclusiveSelections, setExclusiveSelections] = useState<Record<string, string>>(() => {
    const initial: Record<string, string> = {};
    for (const group of schema.exclusiveGroups) {
      initial[group.name] = group.defaultOption || group.options?.[0]?.value || "";
    }
    return initial;
  });

  // Track collapsed groups (advance group collapsed by default)
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(() => {
    const collapsed = new Set<string>();
    for (const group of schema.groups) {
      // Advance group is collapsed by default
      if (group.collapsed || group.name === "advance" || group.name === "advanced") {
        collapsed.add(group.name);
      }
    }
    return collapsed;
  });

  const handleChange = useCallback(
    (name: string, value: unknown) => {
      const nextValues = { ...values, [name]: value };

      for (const param of schema.params) {
        const entityTypeField = param.componentProps?.entityTypeField;
        if (
          param.component === "EntitySelect" &&
          typeof entityTypeField === "string" &&
          entityTypeField === name
        ) {
          nextValues[param.name] = "";
        }
      }

      onChange(nextValues);
    },
    [onChange, schema.params, values]
  );

  // Handle exclusive group selection
  const handleExclusiveSelect = useCallback(
    (groupName: string, optionValue: string) => {
      setExclusiveSelections((prev) => ({
        ...prev,
        [groupName]: optionValue,
      }));
    },
    []
  );

  // Toggle group collapse
  const toggleGroup = useCallback((groupName: string) => {
    setCollapsedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(groupName)) {
        next.delete(groupName);
      } else {
        next.add(groupName);
      }
      return next;
    });
  }, []);

  // Check if a param is visible based on exclusive groups
  const isParamVisible = useCallback(
    (param: InputParamDefinition): boolean => {
      if (!param.dependsOn) return true;

      const { exclusiveGroup, selectedOption } = param.dependsOn;
      const currentSelection = exclusiveSelections[exclusiveGroup];
      return currentSelection === selectedOption;
    },
    [exclusiveSelections]
  );

  // Get params for a specific group (by param.group field)
  const getGroupParams = useCallback(
    (group: InputGroupDefinition): InputParamDefinition[] => {
      return schema.params
        .filter((p) => p.group === group.name && isParamVisible(p) && !hiddenFieldSet.has(p.name))
        .sort((a, b) => (a.order || 0) - (b.order || 0));
    },
    [hiddenFieldSet, schema.params, isParamVisible]
  );

  // Get ungrouped params (params without a group assigned)
  const ungroupedParams = useMemo(() => {
    const groupNames = new Set(schema.groups.map((g) => g.name));
    return schema.params
      .filter((p) => !p.group || !groupNames.has(p.group))
      .filter((p) => !hiddenFieldSet.has(p.name))
      .filter((p) => isParamVisible(p))
      .sort((a, b) => (a.order || 0) - (b.order || 0));
  }, [hiddenFieldSet, schema.params, schema.groups, isParamVisible]);

  // Get exclusive groups for a form group
  const getExclusiveGroupsForGroup = useCallback(
    (groupName: string): ExclusiveGroupDefinition[] => {
      return schema.exclusiveGroups.filter((eg) => eg.group === groupName);
    },
    [schema.exclusiveGroups]
  );

  // Render a single field based on type
  const renderField = (param: InputParamDefinition) => {
    const value = values[param.name];
    const error = errors[param.name];

    // Normalize type - API may return STRING/INTEGER instead of TEXT/NUMBER
    let fieldType = param.type;

    // STRING with enum/options should be SELECT
    if ((fieldType === "STRING" || fieldType === "TEXT") && (param.enum || param.options)) {
      fieldType = "SELECT";
    }
    // STRING without enum should be TEXT
    else if (fieldType === "STRING") {
      fieldType = "TEXT";
    }
    // INTEGER should be NUMBER
    else if (fieldType === "INTEGER") {
      fieldType = "NUMBER";
    }

    switch (fieldType) {
      case "TEXT":
        if (param.component === "EntitySelect") {
          const entityTypeField = param.componentProps?.entityTypeField;
          const selectedEntityType =
            typeof entityTypeField === "string" && typeof values[entityTypeField] === "string"
              ? (values[entityTypeField] as PromptEntityType)
              : null;

          return (
            <EntitySelectField
              key={param.name}
              param={param}
              value={(value as string) || ""}
              entityType={selectedEntityType}
              scriptId={scriptId}
              episodeId={episodeId}
              onChange={(v) => handleChange(param.name, v)}
              disabled={disabled}
              error={error}
            />
          );
        }

        return (
          <TextField
            key={param.name}
            param={param}
            value={(value as string) || ""}
            onChange={(v) => handleChange(param.name, v)}
            disabled={disabled}
            error={error}
          />
        );

      case "TEXTAREA":
        return (
          <TextareaField
            key={param.name}
            param={param}
            value={(value as string) || ""}
            onChange={(v) => handleChange(param.name, v)}
            disabled={disabled}
            error={error}
          />
        );

      case "NUMBER":
        return (
          <NumberField
            key={param.name}
            param={param}
            value={value as number | undefined}
            onChange={(v) => handleChange(param.name, v)}
            disabled={disabled}
            error={error}
          />
        );

      case "BOOLEAN":
        return (
          <BooleanField
            key={param.name}
            param={param}
            value={(value as boolean) ?? (param.defaultValue as boolean) ?? false}
            onChange={(v) => handleChange(param.name, v)}
            disabled={disabled}
          />
        );

      case "SELECT":
        return (
          <SelectField
            key={param.name}
            param={param}
            value={(value as string) || (param.defaultValue as string) || ""}
            onChange={(v) => handleChange(param.name, v)}
            disabled={disabled}
            error={error}
          />
        );

      case "STYLE":
        return (
          <StyleSelectField
            key={param.name}
            param={param}
            value={(value as string) || ""}
            styles={styles}
            onChange={(v) => handleChange(param.name, v)}
            disabled={disabled}
            error={error}
          />
        );

      case "IMAGE":
      case "VIDEO":
      case "AUDIO":
      case "DOCUMENT":
        return (
          <FileUploadField
            key={param.name}
            param={param}
            value={value as FileValue | null}
            onChange={(v) => handleChange(param.name, v)}
            onUpload={onFileUpload}
            disabled={disabled}
            error={error}
          />
        );

      case "IMAGE_LIST":
      case "VIDEO_LIST":
      case "AUDIO_LIST":
      case "DOCUMENT_LIST":
        return (
          <FileListField
            key={param.name}
            param={param}
            value={(value as FileValue[]) || []}
            onChange={(v) => handleChange(param.name, v)}
            onUpload={onFileUpload}
            disabled={disabled}
            error={error}
          />
        );

      case "TEXT_LIST":
      case "NUMBER_LIST":
        // For now, render as text area (comma-separated)
        return (
          <TextareaField
            key={param.name}
            param={{
              ...param,
              description: `${param.description || ""} ${tForm("onePerLine")}`.trim(),
            }}
            value={Array.isArray(value) ? (value as string[]).join("\n") : ""}
            onChange={(v) => {
              const list = v.split("\n").filter(Boolean);
              handleChange(
                param.name,
                param.type === "NUMBER_LIST" ? list.map(Number) : list
              );
            }}
            disabled={disabled}
            error={error}
          />
        );

      default:
        return null;
    }
  };

  // Render a frame field (compact mode — no description, for side-by-side layout)
  const renderFrameField = (param: InputParamDefinition) => {
    const value = values[param.name];
    const error = errors[param.name];
    return (
      <FileUploadField
        key={param.name}
        param={param}
        value={value as FileValue | null}
        onChange={(v) => handleChange(param.name, v)}
        onUpload={onFileUpload}
        disabled={disabled}
        error={error}
        compact
      />
    );
  };

  // Render exclusive group selector
  const renderExclusiveGroup = (group: ExclusiveGroupDefinition) => {
    const selectedValue = exclusiveSelections[group.name];

    return (
      <div key={group.name} className="flex flex-col gap-1.5">
        {group.label && (
          <label className="text-xs font-medium text-muted">{group.label}</label>
        )}
        <ButtonGroup className="w-full">
          {group.options.map((option) => (
            <Button
              key={option.value}
              variant={selectedValue === option.value ? "secondary" : "ghost"}
              size="sm"
              onPress={() => handleExclusiveSelect(group.name, option.value)}
              isDisabled={disabled}
              className="flex-1"
            >
              {option.label}
            </Button>
          ))}
        </ButtonGroup>
        {group.description && (
          <span className="text-xs text-muted">{group.description}</span>
        )}
      </div>
    );
  };

  // Render fields in a grid layout
  const renderFieldsGrid = (params: InputParamDefinition[]) => {
    if (params.length === 0) return null;

    // Split into full-width and grid items
    const fullWidthParams = params.filter((p) => FULL_WIDTH_TYPES.has(p.type));
    const gridParams = params.filter((p) => !FULL_WIDTH_TYPES.has(p.type));

    return (
      <div className="flex flex-col gap-3">
        {/* Grid layout for compact fields */}
        {gridParams.length > 0 && (
          <div className={compact ? "grid grid-cols-2 gap-3" : "flex flex-col gap-3"}>
            {gridParams.map(renderField)}
          </div>
        )}
        {/* Full width fields */}
        {fullWidthParams.map(renderField)}
      </div>
    );
  };

  // Shared collapsible card header
  const renderGroupHeader = (
    group: InputGroupDefinition,
    params: InputParamDefinition[],
    icon?: ReactNode,
  ) => {
    const isCollapsed = collapsedGroups.has(group.name);
    return (
      <div
        role="button"
        tabIndex={0}
        className="flex w-full cursor-pointer items-center justify-between text-left"
        onClick={() => toggleGroup(group.name)}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            toggleGroup(group.name);
          }
        }}
      >
        <div className="flex items-center gap-2">
          {icon}
          <span className="text-sm font-medium">{group.label}</span>
          <Chip size="sm" variant="soft">{tForm("itemCount", { count: params.length })}</Chip>
        </div>
        <div className="flex size-7 items-center justify-center rounded-md hover:bg-muted/10">
          {isCollapsed ? (
            <ChevronRight className="size-4 text-muted" />
          ) : (
            <ChevronDown className="size-4 text-muted" />
          )}
        </div>
      </div>
    );
  };

  // Render basic group (collapsible, expanded by default)
  const renderBasicGroup = (group: InputGroupDefinition, params: InputParamDefinition[], exclusiveGroups: ExclusiveGroupDefinition[]) => {
    if (params.length === 0 && exclusiveGroups.length === 0) return null;
    const isCollapsed = collapsedGroups.has(group.name);
    return (
      <Card key={group.name} variant="tertiary" className="overflow-hidden p-4">
        {renderGroupHeader(group, params)}
        {!isCollapsed && (
          <div className="mt-3 border-t border-border/50 pt-3">
            <div className="flex flex-col gap-3">
              {exclusiveGroups.map(renderExclusiveGroup)}
              {renderFieldsGrid(params)}
            </div>
          </div>
        )}
      </Card>
    );
  };

  // Render image group - collapsible, with frame pair detection, expanded by default
  const renderImageGroup = (group: InputGroupDefinition, params: InputParamDefinition[], exclusiveGroups: ExclusiveGroupDefinition[]) => {
    if (params.length === 0 && exclusiveGroups.length === 0) return null;

    const firstFrameParam = params.find(p => p.name.toLowerCase().includes("first_frame") || p.name.toLowerCase().includes("start_frame"));
    const lastFrameParam = params.find(p => p.name.toLowerCase().includes("last_frame") || p.name.toLowerCase().includes("end_frame"));
    const otherParams = params.filter(p => p !== firstFrameParam && p !== lastFrameParam);
    const hasFramePair = firstFrameParam && lastFrameParam;
    const isCollapsed = collapsedGroups.has(group.name);

    return (
      <Card key={group.name} variant="tertiary" className="overflow-hidden p-4">
        {renderGroupHeader(group, params, <ImageIcon className="size-4 text-accent" />)}
        {!isCollapsed && (
          <div className="border-t border-border/50">
            <div className="flex flex-col gap-3">
              {exclusiveGroups.map(renderExclusiveGroup)}
              {hasFramePair ? (
                <div className="grid grid-cols-2 gap-3">
                  {renderFrameField(firstFrameParam)}
                  {renderFrameField(lastFrameParam)}
                </div>
              ) : (
                <>
                  {firstFrameParam && renderField(firstFrameParam)}
                  {lastFrameParam && renderField(lastFrameParam)}
                </>
              )}
              {otherParams.map(renderField)}
            </div>
          </div>
        )}
      </Card>
    );
  };

  // Render video group - collapsible, expanded by default
  const renderVideoGroup = (group: InputGroupDefinition, params: InputParamDefinition[], exclusiveGroups: ExclusiveGroupDefinition[]) => {
    if (params.length === 0 && exclusiveGroups.length === 0) return null;
    const isCollapsed = collapsedGroups.has(group.name);
    return (
      <Card key={group.name} variant="tertiary" className="overflow-hidden p-4">
        {renderGroupHeader(group, params, <VideoIcon className="size-4 text-accent" />)}
        {!isCollapsed && (
          <div className="border-t border-border/50">
            <div className="flex flex-col gap-3">
              {exclusiveGroups.map(renderExclusiveGroup)}
              {renderFieldsGrid(params)}
            </div>
          </div>
        )}
      </Card>
    );
  };

  // Render advance group (collapsible, collapsed by default)
  const renderAdvanceGroup = (group: InputGroupDefinition, params: InputParamDefinition[], exclusiveGroups: ExclusiveGroupDefinition[]) => {
    if (params.length === 0 && exclusiveGroups.length === 0) return null;
    const isCollapsed = collapsedGroups.has(group.name);
    return (
      <Card key={group.name} variant="tertiary" className="overflow-hidden p-4">
        {renderGroupHeader(group, params, <SettingsIcon className="size-4 text-accent" />)}
        {!isCollapsed && (
          <div className="border-t border-border/50">
            <div className="flex flex-col gap-3">
              {exclusiveGroups.map(renderExclusiveGroup)}
              {renderFieldsGrid(params)}
            </div>
          </div>
        )}
      </Card>
    );
  };

  // Render a group based on its type
  const renderGroup = (group: InputGroupDefinition) => {
    const params = getGroupParams(group);
    const exclusiveGroups = getExclusiveGroupsForGroup(group.name);
    const groupName = group.name.toLowerCase();

    if (groupName === "basic" || groupName === "基础" || groupName === "基本") {
      return renderBasicGroup(group, params, exclusiveGroups);
    }
    if (groupName === "image" || groupName === "图片" || groupName === "图像") {
      return renderImageGroup(group, params, exclusiveGroups);
    }
    if (groupName === "video" || groupName === "视频") {
      return renderVideoGroup(group, params, exclusiveGroups);
    }
    if (groupName === "advance" || groupName === "advanced" || groupName === "高级") {
      return renderAdvanceGroup(group, params, exclusiveGroups);
    }

    // Default: same collapsible card style
    if (params.length === 0 && exclusiveGroups.length === 0) return null;
    const isCollapsed = collapsedGroups.has(group.name);
    return (
      <Card key={group.name} variant="tertiary" className="overflow-hidden p-4">
        {renderGroupHeader(group, params)}
        {!isCollapsed && (
          <div className="border-t border-border/50">
            <div className="flex flex-col gap-3">
              {exclusiveGroups.map(renderExclusiveGroup)}
              {renderFieldsGrid(params)}
            </div>
          </div>
        )}
      </Card>
    );
  };

  // Sort groups by backend-defined order field (basic=1, image=2, video=3, advanced=last)
  const sortedGroups = useMemo(() => {
    return [...schema.groups].sort((a, b) => (a.order ?? 999) - (b.order ?? 999));
  }, [schema.groups]);

  return (
    <div className="flex flex-col gap-4">
      {/* When hideGroupLabels is true, render all params directly but with frame pair detection */}
      {hideGroupLabels ? (
        <div className="flex flex-col gap-3">
          {(() => {
            const visibleParams = schema.params.filter(isParamVisible);
            const visibleFieldParams = visibleParams.filter((param) => !hiddenFieldSet.has(param.name));
            const firstFrameParam = visibleFieldParams.find(p => p.name.toLowerCase().includes("first_frame") || p.name.toLowerCase().includes("start_frame"));
            const lastFrameParam = visibleFieldParams.find(p => p.name.toLowerCase().includes("last_frame") || p.name.toLowerCase().includes("end_frame"));
            const hasFramePair = firstFrameParam && lastFrameParam;
            const frameNames = new Set(hasFramePair ? [firstFrameParam.name, lastFrameParam.name] : []);

            return (
              <>
                {hasFramePair && (
                  <div className="grid grid-cols-2 gap-3">
                    {renderFrameField(firstFrameParam)}
                    {renderFrameField(lastFrameParam)}
                  </div>
                )}
                {visibleFieldParams
                  .filter(p => !frameNames.has(p.name))
                  .map(renderField)}
              </>
            );
          })()}
        </div>
      ) : (
        <>
          {/* Ungrouped params first */}
          {ungroupedParams.length > 0 && (
            <Card variant="tertiary" className="p-4">
              {renderFieldsGrid(ungroupedParams)}
            </Card>
          )}

          {/* Grouped params */}
          {sortedGroups.map(renderGroup)}
        </>
      )}
    </div>
  );
}
