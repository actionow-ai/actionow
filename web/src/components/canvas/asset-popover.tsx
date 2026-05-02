"use client";

import { useMemo, useState } from "react";
import { Image as ImageIcon } from "lucide-react";

export interface RelatedAsset {
  assetId: string;
  name?: string;
  coverUrl?: string;
  status?: string;
  relationType?: string;
}

interface AssetPopoverProps {
  entityName: string;
  totalCount: number;
  assets: RelatedAsset[];
  /** 锚点（节点上的 mini-stack） */
  children: React.ReactNode;
}

export function AssetPopover({ entityName, totalCount, assets, children }: AssetPopoverProps) {
  const [open, setOpen] = useState(false);

  const grouped = useMemo(() => {
    const result = new Map<string, RelatedAsset[]>();
    for (const a of assets) {
      const key = a.relationType || a.status || "其他";
      const arr = result.get(key) ?? [];
      arr.push(a);
      result.set(key, arr);
    }
    return Array.from(result.entries());
  }, [assets]);

  return (
    <div
      className="relative inline-flex"
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
    >
      {children}
      {open && (
        <div
          className="absolute left-full top-0 z-50 ml-2 w-72 rounded-lg border border-border bg-background p-3 shadow-xl"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="mb-2 flex items-center justify-between gap-2 border-b border-border pb-2">
            <p className="truncate text-[13px] font-semibold">{entityName} · 关联素材</p>
            <span className="shrink-0 text-xs text-muted">{totalCount}</span>
          </div>

          {assets.length === 0 ? (
            <div className="flex flex-col items-center gap-1 py-6 text-muted">
              <ImageIcon className="size-6 opacity-50" />
              <p className="text-xs">暂无关联素材</p>
            </div>
          ) : (
            <div className="space-y-3 max-h-72 overflow-y-auto">
              {grouped.map(([groupName, items]) => (
                <section key={groupName}>
                  <h4 className="mb-1.5 text-[11px] font-medium uppercase tracking-wide text-muted">
                    {groupName}
                  </h4>
                  <div className="grid grid-cols-4 gap-1.5">
                    {items.map((a) => (
                      <div
                        key={a.assetId}
                        className="group flex flex-col items-center gap-0.5"
                        title={a.name}
                      >
                        <div className="size-12 overflow-hidden rounded-md border border-border bg-default-100">
                          {a.coverUrl ? (
                            // eslint-disable-next-line @next/next/no-img-element
                            <img src={a.coverUrl} alt="" className="size-full object-cover" />
                          ) : (
                            <div className="flex size-full items-center justify-center text-muted">
                              <ImageIcon className="size-4" />
                            </div>
                          )}
                        </div>
                        <span className="line-clamp-1 w-full text-center text-[10px] leading-tight text-muted">
                          {a.name || "未命名"}
                        </span>
                      </div>
                    ))}
                  </div>
                </section>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
