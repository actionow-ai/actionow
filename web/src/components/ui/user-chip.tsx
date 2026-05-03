"use client";

import { Avatar, Tooltip } from "@heroui/react";

export interface UserChipProps {
  /** Used to hash a stable background color. */
  userId?: string | null;
  nickname?: string | null;
  username?: string | null;
  /** Avatar size — `xs` (size-5) or `sm` (size-6). Default `xs`. */
  size?: "xs" | "sm";
  /** When `false`, only the avatar is rendered; tooltip exposes the name. Default `true`. */
  showName?: boolean;
  className?: string;
}

// Deterministic palette — keep contrast comfortable on both themes.
const PALETTE: Array<{ bg: string; fg: string }> = [
  { bg: "bg-accent/20",     fg: "text-accent" },
  { bg: "bg-success/20",    fg: "text-success" },
  { bg: "bg-warning/20",    fg: "text-warning" },
  { bg: "bg-danger/20",     fg: "text-danger" },
  { bg: "bg-sky-500/20",    fg: "text-sky-600 dark:text-sky-300" },
  { bg: "bg-violet-500/20", fg: "text-violet-600 dark:text-violet-300" },
  { bg: "bg-pink-500/20",   fg: "text-pink-600 dark:text-pink-300" },
  { bg: "bg-amber-500/20",  fg: "text-amber-600 dark:text-amber-300" },
  { bg: "bg-teal-500/20",   fg: "text-teal-600 dark:text-teal-300" },
  { bg: "bg-indigo-500/20", fg: "text-indigo-600 dark:text-indigo-300" },
];

function hash(input: string): number {
  let h = 0;
  for (let i = 0; i < input.length; i++) {
    h = (h * 31 + input.charCodeAt(i)) | 0;
  }
  return Math.abs(h);
}

function pickPalette(seed?: string | null) {
  if (!seed) return { bg: "bg-muted/20", fg: "text-muted" };
  return PALETTE[hash(seed) % PALETTE.length];
}

/** Take the first visible glyph; uppercase ASCII letters. */
function initial(name: string): string {
  const ch = Array.from(name.trim())[0];
  if (!ch) return "?";
  return /[a-z]/i.test(ch) ? ch.toUpperCase() : ch;
}

/**
 * Compact user identifier — circular initial avatar with a stable
 * hash-derived color, plus optional nickname text. Used in entity card
 * footers and other dense rows.
 */
export function UserChip({
  userId,
  nickname,
  username,
  size = "xs",
  showName = true,
  className,
}: UserChipProps) {
  const display = nickname?.trim() || username?.trim() || "";
  const tooltipText = username && username !== display
    ? `${display || "?"} · ${username}`
    : display || "?";

  const palette = pickPalette(userId ?? username ?? display);
  const sizeClass = size === "xs" ? "size-5" : "size-6";
  const fontClass = size === "xs" ? "text-[9px]" : "text-[10px]";

  const avatar = (
    <Avatar className={`${sizeClass} shrink-0`}>
      <Avatar.Fallback className={`${fontClass} font-semibold ${palette.bg} ${palette.fg}`}>
        {initial(display || "?")}
      </Avatar.Fallback>
    </Avatar>
  );

  return (
    <Tooltip delay={300}>
      <Tooltip.Trigger>
        <span
          // `leading-none` collapses the text's intrinsic line-box so
          // the avatar (a fixed-size circle) and the nickname align on
          // the same horizontal centerline. Color falls through to the
          // text via inheritance, with `text-muted` as the default —
          // callers can override by passing e.g. `text-white` in
          // `className`.
          className={`inline-flex min-w-0 items-center gap-1.5 leading-none text-muted ${className ?? ""}`}
        >
          {avatar}
          {showName && display && (
            <span className="min-w-0 truncate text-xs">{display}</span>
          )}
        </span>
      </Tooltip.Trigger>
      <Tooltip.Content>{tooltipText}</Tooltip.Content>
    </Tooltip>
  );
}
