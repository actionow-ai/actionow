"use client";

import { useState, type ComponentType, type ReactNode, type DragEvent } from "react";
import Image from "next/image";
import { Skeleton, Tooltip } from "@heroui/react";

/**
 * An action item shown inside the card's hover overlay.
 *
 * Set `separatorBefore` to render a visual separator before the item
 * (useful for destructive actions like delete).
 */
export interface EntityCardAction {
  id: string;
  label: string;
  icon?: ComponentType<{ className?: string }>;
  variant?: "default" | "danger";
  separatorBefore?: boolean;
  onAction: () => void;
}

export type EntityCardVariant = "grid" | "masonry";

export interface EntityCardProps {
  /** Title shown under the cover. */
  title: string;
  /** Number of lines for the title (default 1). Use 2 for prompt-style titles. */
  titleLines?: 1 | 2;
  /** Optional body text. Two-line clamp. */
  description?: string | null;
  /** Fallback rendered inline when description is empty. */
  descriptionFallback?: string;
  /** Cover image URL. Ignored when `coverSlot` is provided. */
  coverUrl?: string | null;
  /** Custom cover node (e.g. a <video>). Overrides the default <img>. */
  coverSlot?: ReactNode;
  /** Node rendered inside the cover when neither coverUrl nor coverSlot is present. */
  fallbackIcon?: ReactNode;
  /** Layout variant. `grid` (default) uses aspect-video; `masonry` uses natural ratio. */
  variant?: EntityCardVariant;
  /**
   * Aspect ratio (w / h) for masonry mode — used to reserve space and
   * prevent layout shift while the image loads. Ignored in grid mode.
   */
  aspectRatio?: number;
  /** Badge rendered at the top-left corner of the cover. */
  topLeftBadge?: ReactNode;
  /** Badge rendered at the top-right corner. Fades out on hover when actions are present. */
  topRightBadge?: ReactNode;
  /** Status overlay rendered on top of the cover (e.g. generating / failed). */
  statusOverlay?: ReactNode;
  /** Actions shown in the hover overlay (top-right corner). */
  actions?: EntityCardAction[];
  /** Accessible label for the actions trigger button. */
  actionsLabel?: string;
  /** Marks the actions button as pending (shows spinner). */
  isActionPending?: boolean;
  /** Single-line meta row rendered above the footer (e.g. provider · time). */
  meta?: ReactNode;
  /** Hover-expanded details (default-collapsed) — e.g. param tags, ref assets. */
  expandableDetails?: ReactNode;
  /** Footer left slot (e.g., author chip). */
  footerLeft?: ReactNode;
  /** Footer right slot (e.g., date, file size). */
  footerRight?: ReactNode;
  /** Click handler for the whole card. */
  onClick?: () => void;
  /** Drag start handler. Card becomes draggable when provided. */
  onDragStart?: (e: DragEvent<HTMLDivElement>) => void;
  /** Drag end handler — pair with `onDragStart` to clean up drag state. */
  onDragEnd?: (e: DragEvent<HTMLDivElement>) => void;
  /**
   * Cover-only mode — skip title, description, meta, expandableDetails
   * and footer. Useful for pure thumbnail tiles (image / video /
   * placeholder). Title is still used as the cover alt text.
   */
  mediaOnly?: boolean;
  /** Additional classes merged into the card root. */
  className?: string;
}

/**
 * Generic entity / media card used across the workspace to render
 * scripts, episodes, shots, characters, scenes, props, assets, styles
 * and inspiration records with a consistent look (rounded-3xl,
 * frosted glass, hover scale + actions).
 */
export function EntityCard({
  title,
  titleLines = 1,
  description,
  descriptionFallback = "",
  coverUrl,
  coverSlot,
  fallbackIcon,
  variant = "grid",
  aspectRatio,
  topLeftBadge,
  topRightBadge,
  statusOverlay,
  actions,
  actionsLabel = "More",
  isActionPending,
  meta,
  expandableDetails,
  footerLeft,
  footerRight,
  onClick,
  onDragStart,
  onDragEnd,
  mediaOnly = false,
  className,
}: EntityCardProps) {
  const hasActions = !!actions && actions.length > 0;
  const isMasonry = variant === "masonry";
  const isDraggable = !!onDragStart;

  return (
    <div
      // Root owns the rounded outer boundary AND the clip. Because the
      // root is already a compositing layer (`backdrop-blur-xl`,
      // `isolate`), `overflow-hidden + border-radius` here reliably
      // clips any nested layers — including the cover image's hover
      // scale transform. Inner cover doesn't need its own clip.
      className={`pointer-events-auto group relative isolate flex cursor-pointer flex-col overflow-hidden rounded-3xl border border-white/10 bg-white/60 shadow-sm backdrop-blur-xl transition-shadow duration-200 hover:shadow-md dark:bg-white/5${className ? ` ${className}` : ""}`}
      onClick={onClick}
      draggable={isDraggable}
      onDragStart={onDragStart}
      onDragEnd={onDragEnd}
    >
      {/* Cover — relies on the root's overflow clip; only needs its
          own overflow-hidden so the scaled image can't bleed down
          into the content section. */}
      <div
        className={`relative w-full overflow-hidden bg-black/5 dark:bg-white/5 ${
          isMasonry ? "" : "aspect-video"
        }`}
        style={
          isMasonry
            ? aspectRatio
              ? { aspectRatio }
              : { minHeight: 120 }
            : undefined
        }
      >
        {coverSlot ?? (
          <CardCover
            src={coverUrl}
            alt={title}
            fallbackIcon={fallbackIcon}
            isMasonry={isMasonry}
          />
        )}

        {/* Top-left badge */}
        {topLeftBadge && (
          <div className="absolute left-2.5 top-2.5">{topLeftBadge}</div>
        )}

        {/* Top-right badge — fades away on hover if a dropdown is present */}
        {topRightBadge && (
          <div
            className={`absolute right-2.5 top-2.5${hasActions ? " transition-opacity group-hover:opacity-0" : ""}`}
          >
            {topRightBadge}
          </div>
        )}

        {/* Status overlay (e.g., generating / failed) */}
        {statusOverlay && (
          <div className="pointer-events-none absolute inset-0">{statusOverlay}</div>
        )}

        {/* Hover actions toolbar — small icon buttons docked at the
            top-right corner. Only the buttons themselves are
            interactive; the rest of the cover stays clickable for the
            card's onClick, so the mouse can't accidentally land on an
            action just by entering the card. */}
        {hasActions && (
          <div
            className="pointer-events-none absolute right-2 top-2 z-30 flex items-center gap-1 opacity-0 transition-opacity duration-150 group-hover:pointer-events-auto group-hover:opacity-100"
            onClick={(e) => e.stopPropagation()}
            aria-label={actionsLabel}
          >
            {actions!.map((action) => {
              const Icon = action.icon;
              const isDanger = action.variant === "danger";
              return (
                <Tooltip key={action.id} delay={0}>
                  <Tooltip.Trigger>
                    <button
                      type="button"
                      disabled={isActionPending}
                      onClick={(e) => {
                        e.stopPropagation();
                        action.onAction();
                      }}
                      className={`flex size-7 cursor-pointer items-center justify-center rounded-full bg-black/55 text-white shadow-sm backdrop-blur-sm transition-colors hover:bg-black/75 disabled:cursor-not-allowed disabled:opacity-50 ${
                        isDanger ? "hover:text-danger" : "hover:text-accent"
                      }${action.separatorBefore ? " ml-1.5 before:absolute before:-left-[5px] before:top-1/2 before:h-3.5 before:w-px before:-translate-y-1/2 before:bg-white/30 relative" : ""}`}
                    >
                      {Icon && <Icon className="size-3.5" />}
                    </button>
                  </Tooltip.Trigger>
                  <Tooltip.Content>{action.label}</Tooltip.Content>
                </Tooltip>
              );
            })}
          </div>
        )}
      </div>

      {/* Content — skipped entirely in mediaOnly mode.
          Description / prompt body text is intentionally not rendered;
          the title alone keeps the card compact. `description` and
          `descriptionFallback` props are retained for API compatibility
          but no longer drawn. */}
      {!mediaOnly && (
      <div className="flex flex-1 flex-col px-3 py-2">
        <h3
          className={`${titleLines === 2 ? "line-clamp-2" : "line-clamp-1"} text-sm font-medium text-foreground`}
        >
          {title}
        </h3>

        {meta && (
          <div className="mt-1.5 flex items-center gap-1.5 text-[11px] text-muted/80">
            {meta}
          </div>
        )}

        {expandableDetails && (
          <div className="max-h-0 overflow-hidden transition-all duration-200 group-hover:max-h-32">
            <div className="pt-1.5">{expandableDetails}</div>
          </div>
        )}

        {(footerLeft || footerRight) && (
          <div className="mt-2 flex items-center justify-between gap-2 text-[11px] text-muted/70">
            <div className="min-w-0 flex-1 truncate">{footerLeft}</div>
            {footerRight && <div className="flex shrink-0 items-center gap-2">{footerRight}</div>}
          </div>
        )}
      </div>
      )}
    </div>
  );
}

/**
 * Internal cover renderer that handles the loading skeleton, fade-in,
 * and onError fallback. Splits between grid (filled) and masonry
 * (natural-height) layouts.
 */
function CardCover({
  src,
  alt,
  fallbackIcon,
  isMasonry,
}: {
  src?: string | null;
  alt: string;
  fallbackIcon?: ReactNode;
  isMasonry: boolean;
}) {
  const [loaded, setLoaded] = useState(false);
  const [errored, setErrored] = useState(false);

  if (!src || errored) {
    return (
      <div className="flex size-full items-center justify-center bg-white/10 dark:bg-white/5">
        {fallbackIcon}
      </div>
    );
  }

  // Masonry mode: use a plain <img> so natural height drives layout.
  if (isMasonry) {
    return (
      <>
        {!loaded && (
          <div className="absolute inset-0 animate-pulse bg-surface-2" />
        )}
        <img
          src={src}
          alt={alt}
          loading="lazy"
          decoding="async"
          onLoad={() => setLoaded(true)}
          onError={() => setErrored(true)}
          className={`block w-full transition-[transform,opacity] duration-300 group-hover:scale-105 ${
            loaded ? "opacity-100" : "opacity-0"
          }`}
        />
      </>
    );
  }

  // Grid mode: next/image fills the aspect-video container.
  return (
    <>
      {!loaded && (
        <div className="absolute inset-0 animate-pulse bg-surface-2" />
      )}
      <Image
        src={src}
        alt={alt}
        fill
        sizes="(min-width: 1280px) 20vw, (min-width: 1024px) 25vw, (min-width: 768px) 33vw, 50vw"
        onLoad={() => setLoaded(true)}
        onError={() => setErrored(true)}
        className={`object-cover transition-[transform,opacity] duration-300 group-hover:scale-105 ${
          loaded ? "opacity-100" : "opacity-0"
        }`}
      />
    </>
  );
}

/**
 * Skeleton placeholder matching the EntityCard layout — used while
 * lists are loading.
 */
export function EntityCardSkeleton({
  variant = "grid",
}: {
  variant?: EntityCardVariant;
} = {}) {
  return (
    <div className="pointer-events-auto flex flex-col overflow-hidden rounded-3xl border border-white/10 bg-white/60 backdrop-blur-xl dark:bg-white/5">
      <Skeleton
        className={`w-full rounded-none ${variant === "masonry" ? "h-40" : "aspect-video"}`}
      />
      <div className="flex flex-col gap-1.5 px-3 py-2">
        <Skeleton className="h-4 w-3/4 rounded" />
        <div className="mt-1 flex items-center justify-between">
          <Skeleton className="h-3 w-20 rounded" />
          <Skeleton className="h-3 w-16 rounded" />
        </div>
      </div>
    </div>
  );
}
