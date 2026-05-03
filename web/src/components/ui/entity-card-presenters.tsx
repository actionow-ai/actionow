"use client";

import type { ReactNode } from "react";
import {
  EntityCard,
  type EntityCardAction,
  type EntityCardProps,
  type EntityCardVariant,
} from "./entity-card";

export type EntityKind =
  | "script"
  | "episode"
  | "storyboard"
  | "character"
  | "scene"
  | "prop"
  | "asset"
  | "style"
  | "inspiration_record";

/**
 * Per-call context that business pages inject into a presenter — i18n,
 * permissions, and action callbacks. Each presenter only reads the
 * fields it needs; everything is optional except `t`.
 */
export interface EntityPresenterCtx {
  t: (key: string) => string;
  isAdmin?: boolean;
  onPreview?: (item: unknown) => void;
  onEdit?: (item: unknown) => void;
  onCopy?: (item: unknown) => void;
  onDelete?: (item: unknown) => void;
  onPublish?: (item: unknown) => void;
  onUnpublish?: (item: unknown) => void;
  onDownload?: (item: unknown) => void;
  onOpenImageEditor?: (item: unknown) => void;
  onTogglePublish?: (item: unknown) => void;
  onRetry?: (item: unknown) => void;
  /** Escape hatch for one-off actions a specific page needs. */
  extraActions?: (item: unknown) => EntityCardAction[];
}

/**
 * Pure mapping from an entity DTO of type T → EntityCard props.
 * Defined per kind, colocated with its consumer when only used there;
 * shared via this module when reused across pages.
 */
export interface EntityPresenter<T> {
  variant?: EntityCardVariant;
  titleLines?: 1 | 2;
  fallbackIcon: ReactNode;
  titleFrom: (item: T) => string;
  descriptionFrom?: (item: T) => string | null | undefined;
  descriptionFallback?: (ctx: EntityPresenterCtx) => string;
  coverUrlFrom?: (item: T) => string | null | undefined;
  coverSlot?: (item: T) => ReactNode;
  aspectRatioFrom?: (item: T) => number | undefined;
  topLeftBadge?: (item: T, ctx: EntityPresenterCtx) => ReactNode;
  topRightBadge?: (item: T, ctx: EntityPresenterCtx) => ReactNode;
  statusOverlay?: (item: T, ctx: EntityPresenterCtx) => ReactNode;
  meta?: (item: T) => ReactNode;
  expandableDetails?: (item: T, ctx: EntityPresenterCtx) => ReactNode;
  footerLeft?: (item: T) => ReactNode;
  footerRight?: (item: T) => ReactNode;
  /** Drag start — defining this makes the card draggable. */
  onDragStart?: (item: T, e: React.DragEvent<HTMLDivElement>) => void;
  /** Build the hover-overlay action list. */
  actions?: (item: T, ctx: EntityPresenterCtx) => EntityCardAction[];
}

export interface EntityItemCardProps<T> {
  presenter: EntityPresenter<T>;
  item: T;
  ctx: EntityPresenterCtx;
  onClick?: () => void;
  className?: string;
  /** Override the actions label (default `ctx.t("more")` if available). */
  actionsLabel?: string;
  isActionPending?: boolean;
}

/**
 * Thin wrapper that runs a presenter against an item and forwards the
 * result to EntityCard. Business pages call this — they never poke at
 * EntityCard props directly when an entity kind is involved.
 */
export function EntityItemCard<T>({
  presenter: p,
  item,
  ctx,
  onClick,
  className,
  actionsLabel,
  isActionPending,
}: EntityItemCardProps<T>) {
  const actions = p.actions?.(item, ctx);
  const extra = ctx.extraActions?.(item);
  const allActions = [...(actions ?? []), ...(extra ?? [])];

  const cardProps: EntityCardProps = {
    title: p.titleFrom(item),
    titleLines: p.titleLines,
    description: p.descriptionFrom?.(item),
    descriptionFallback: p.descriptionFallback?.(ctx),
    coverUrl: p.coverUrlFrom?.(item),
    coverSlot: p.coverSlot?.(item),
    fallbackIcon: p.fallbackIcon,
    variant: p.variant,
    aspectRatio: p.aspectRatioFrom?.(item),
    topLeftBadge: p.topLeftBadge?.(item, ctx),
    topRightBadge: p.topRightBadge?.(item, ctx),
    statusOverlay: p.statusOverlay?.(item, ctx),
    meta: p.meta?.(item),
    expandableDetails: p.expandableDetails?.(item, ctx),
    footerLeft: p.footerLeft?.(item),
    footerRight: p.footerRight?.(item),
    actions: allActions.length > 0 ? allActions : undefined,
    actionsLabel,
    isActionPending,
    onClick,
    onDragStart: p.onDragStart ? (e) => p.onDragStart!(item, e) : undefined,
    className,
  };

  return <EntityCard {...cardProps} />;
}
