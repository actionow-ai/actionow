"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useLocale } from "next-intl";
import {
  Button,
  Chip,
  Form,
  Input,
  Label,
  ListBox,
  Modal,
  NumberField,
  ScrollShadow,
  SearchField,
  Select,
  Skeleton,
  Spinner,
  TextField,
  toast,
} from "@heroui/react";
import { Copy, Eye, Pencil, Plus, RefreshCw, Trash2 } from "lucide-react";
import { giftCodeService, getErrorFromException } from "@/lib/api";
import type {
  GiftCodeDTO,
  GiftCodeRedemptionDTO,
  GiftCodeRequestDTO,
  GiftCodeStatus,
} from "@/lib/api/dto";

const PAGE_SIZE = 20;

const STATUS_LABEL: Record<GiftCodeStatus, string> = {
  ACTIVE: "启用",
  DISABLED: "停用",
  EXHAUSTED: "已用完",
  EXPIRED: "已过期",
};

const STATUS_COLOR: Record<GiftCodeStatus, "success" | "default" | "warning" | "danger"> = {
  ACTIVE: "success",
  DISABLED: "default",
  EXHAUSTED: "warning",
  EXPIRED: "danger",
};

function formatDateTime(value?: string | null): string {
  if (!value) return "—";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleString();
}

function formatDateInput(value?: string | null): string {
  if (!value) return "";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return "";
  const off = d.getTimezoneOffset();
  return new Date(d.getTime() - off * 60000).toISOString().slice(0, 16);
}

function toIsoOrNull(value: string): string | null {
  if (!value.trim()) return null;
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return null;
  return d.toISOString();
}

type EditState = {
  id: string | null;
  code: string;
  name: string;
  description: string;
  points: number;
  validFrom: string;
  validUntil: string;
  maxRedemptions: number;
  status: GiftCodeStatus;
};

const EMPTY_FORM: EditState = {
  id: null,
  code: "",
  name: "",
  description: "",
  points: 100,
  validFrom: "",
  validUntil: "",
  maxRedemptions: 1,
  status: "ACTIVE",
};

export default function GiftCodesAdminPage() {
  const locale = useLocale();
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState<string>("");

  const [records, setRecords] = useState<GiftCodeDTO[]>([]);
  const [currentPage, setCurrentPage] = useState(0);
  const [totalRecords, setTotalRecords] = useState(0);
  const [hasMore, setHasMore] = useState(true);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);

  const sentinelRef = useRef<HTMLDivElement | null>(null);
  const requestIdRef = useRef(0);

  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState<EditState>(EMPTY_FORM);
  const [saving, setSaving] = useState(false);

  const [deleteTarget, setDeleteTarget] = useState<GiftCodeDTO | null>(null);
  const [deleting, setDeleting] = useState(false);

  const [redemptionsTarget, setRedemptionsTarget] = useState<GiftCodeDTO | null>(null);
  const [redemptions, setRedemptions] = useState<GiftCodeRedemptionDTO[]>([]);
  const [loadingRedemptions, setLoadingRedemptions] = useState(false);

  const fetchPage = useCallback(
    async (pageNum: number, mode: "reset" | "append") => {
      const requestId = ++requestIdRef.current;
      if (mode === "append") setLoadingMore(true);
      else setLoading(true);

      try {
        const page = await giftCodeService.list({
          current: pageNum,
          size: PAGE_SIZE,
          keyword: keyword.trim() || undefined,
          status: status || undefined,
        });

        if (requestId !== requestIdRef.current) return;

        setTotalRecords(page.total);
        setCurrentPage(page.current);
        setHasMore(page.current < page.pages);

        if (mode === "reset") {
          setRecords(page.records);
          return;
        }
        setRecords((prev) => {
          const seen = new Set(prev.map((r) => r.id));
          const merged = [...prev];
          for (const r of page.records) {
            if (!seen.has(r.id)) {
              merged.push(r);
              seen.add(r.id);
            }
          }
          return merged;
        });
      } catch (error) {
        if (requestId === requestIdRef.current) {
          toast.danger(getErrorFromException(error, locale));
          setHasMore(false);
        }
      } finally {
        if (requestId === requestIdRef.current) {
          setLoading(false);
          setLoadingMore(false);
        }
      }
    },
    [keyword, status, locale]
  );

  const loadFirstPage = useCallback(() => {
    setRecords([]);
    setCurrentPage(0);
    setTotalRecords(0);
    setHasMore(true);
    void fetchPage(1, "reset");
  }, [fetchPage]);

  const loadNextPage = useCallback(() => {
    if (loading || loadingMore || !hasMore) return;
    void fetchPage(currentPage + 1, "append");
  }, [currentPage, fetchPage, hasMore, loading, loadingMore]);

  useEffect(() => {
    loadFirstPage();
  }, [loadFirstPage]);

  useEffect(() => {
    const target = sentinelRef.current;
    if (!target || !hasMore) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) loadNextPage();
      },
      { root: null, rootMargin: "280px 0px", threshold: 0.1 }
    );
    observer.observe(target);
    return () => observer.disconnect();
  }, [hasMore, loadNextPage]);

  const openCreate = () => {
    setForm(EMPTY_FORM);
    setFormOpen(true);
  };

  const openEdit = (item: GiftCodeDTO) => {
    setForm({
      id: item.id,
      code: item.code,
      name: item.name ?? "",
      description: item.description ?? "",
      points: item.points,
      validFrom: formatDateInput(item.validFrom),
      validUntil: formatDateInput(item.validUntil),
      maxRedemptions: item.maxRedemptions,
      status: item.status,
    });
    setFormOpen(true);
  };

  const handleSubmit = async () => {
    if (form.points <= 0) {
      toast.danger("积分必须大于 0");
      return;
    }
    if (form.maxRedemptions <= 0) {
      toast.danger("使用次数必须大于 0");
      return;
    }
    const payload: GiftCodeRequestDTO = {
      code: form.id ? undefined : form.code.trim() || undefined,
      name: form.name.trim() || null,
      description: form.description.trim() || null,
      points: form.points,
      validFrom: toIsoOrNull(form.validFrom),
      validUntil: toIsoOrNull(form.validUntil),
      maxRedemptions: form.maxRedemptions,
      status: form.status,
    };
    try {
      setSaving(true);
      if (form.id) {
        await giftCodeService.update(form.id, payload);
        toast.success("已更新");
      } else {
        await giftCodeService.create(payload);
        toast.success("已创建");
      }
      setFormOpen(false);
      loadFirstPage();
    } catch (error) {
      toast.danger(getErrorFromException(error, locale));
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      setDeleting(true);
      await giftCodeService.remove(deleteTarget.id);
      toast.success("已删除");
      setDeleteTarget(null);
      loadFirstPage();
    } catch (error) {
      toast.danger(getErrorFromException(error, locale));
    } finally {
      setDeleting(false);
    }
  };

  const openRedemptions = async (item: GiftCodeDTO) => {
    setRedemptionsTarget(item);
    setRedemptions([]);
    setLoadingRedemptions(true);
    try {
      const page = await giftCodeService.listRedemptions(item.id, { current: 1, size: 100 });
      setRedemptions(page.records);
    } catch (error) {
      toast.danger(getErrorFromException(error, locale));
    } finally {
      setLoadingRedemptions(false);
    }
  };

  const copyCode = async (code: string) => {
    try {
      await navigator.clipboard.writeText(code);
      toast.success("已复制礼包码");
    } catch {
      toast.danger("复制失败");
    }
  };

  return (
    <div className="flex h-full flex-col">
      {/* Toolbar */}
      <div className="flex shrink-0 items-center justify-between gap-3 pb-4">
        <div className="flex items-center gap-2">
          <SearchField aria-label="搜索" value={keyword} onChange={setKeyword} variant="secondary">
            <SearchField.Group>
              <SearchField.SearchIcon />
              <SearchField.Input
                className="w-56"
                placeholder="按礼包码 / 名称搜索"
                onKeyDown={(e) => e.key === "Enter" && loadFirstPage()}
              />
              <SearchField.ClearButton />
            </SearchField.Group>
          </SearchField>
          <Select
            className="w-32"
            variant="secondary"
            aria-label="状态"
            value={status}
            onChange={(v) => setStatus(String(v))}
          >
            <Select.Trigger>
              <Select.Value />
              <Select.Indicator />
            </Select.Trigger>
            <Select.Popover>
              <ListBox>
                <ListBox.Item id="" textValue="全部状态">全部状态<ListBox.ItemIndicator /></ListBox.Item>
                <ListBox.Item id="ACTIVE" textValue="启用">启用<ListBox.ItemIndicator /></ListBox.Item>
                <ListBox.Item id="DISABLED" textValue="停用">停用<ListBox.ItemIndicator /></ListBox.Item>
                <ListBox.Item id="EXHAUSTED" textValue="已用完">已用完<ListBox.ItemIndicator /></ListBox.Item>
                <ListBox.Item id="EXPIRED" textValue="已过期">已过期<ListBox.ItemIndicator /></ListBox.Item>
              </ListBox>
            </Select.Popover>
          </Select>
          <span className="text-xs text-muted">共 {totalRecords} 个</span>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="primary" size="sm" onPress={openCreate} className="gap-1">
            <Plus className="size-3.5" />
            创建礼包码
          </Button>
          <Button variant="ghost" size="sm" isIconOnly onPress={loadFirstPage} aria-label="刷新">
            <RefreshCw className={`size-4 ${loading ? "animate-spin" : ""}`} />
          </Button>
        </div>
      </div>

      {/* List */}
      <ScrollShadow className="min-h-0 flex-1 overflow-y-auto">
        {loading ? (
          <div className="grid gap-3">
            <Skeleton className="h-12 rounded-xl" />
            <Skeleton className="h-12 rounded-xl" />
            <Skeleton className="h-12 rounded-xl" />
          </div>
        ) : records.length === 0 ? (
          <div className="rounded-xl border border-dashed border-border bg-surface p-10 text-center text-sm text-muted">
            暂无礼包码
          </div>
        ) : (
          <div className="overflow-hidden rounded-xl border border-border bg-surface">
            <div className="overflow-x-auto">
              <table className="min-w-full text-left text-sm">
                <thead className="bg-surface-secondary">
                  <tr>
                    <th className="px-3 py-2.5 text-xs font-semibold text-muted">礼包码 / 名称</th>
                    <th className="px-3 py-2.5 text-xs font-semibold text-muted">积分</th>
                    <th className="px-3 py-2.5 text-xs font-semibold text-muted">使用进度</th>
                    <th className="px-3 py-2.5 text-xs font-semibold text-muted">有效期</th>
                    <th className="px-3 py-2.5 text-xs font-semibold text-muted">状态</th>
                    <th className="px-3 py-2.5 text-xs font-semibold text-muted">创建时间</th>
                    <th className="px-3 py-2.5 text-xs font-semibold text-muted text-right">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {records.map((item) => (
                    <tr key={item.id} className="border-t border-border hover:bg-surface-secondary/40">
                      <td className="px-3 py-2.5">
                        <div className="flex items-center gap-2">
                          <code className="rounded bg-surface-secondary px-1.5 py-0.5 font-mono text-xs">
                            {item.code}
                          </code>
                          <button
                            type="button"
                            onClick={() => copyCode(item.code)}
                            className="text-muted hover:text-foreground"
                            aria-label="复制礼包码"
                          >
                            <Copy className="size-3" />
                          </button>
                        </div>
                        {item.name && <div className="mt-0.5 text-xs text-muted">{item.name}</div>}
                      </td>
                      <td className="px-3 py-2.5 font-mono text-xs">
                        {item.points.toLocaleString()}
                      </td>
                      <td className="px-3 py-2.5 text-xs">
                        <span className="font-mono">
                          {item.redeemedCount.toLocaleString()} / {item.maxRedemptions.toLocaleString()}
                        </span>
                      </td>
                      <td className="px-3 py-2.5 text-xs text-muted">
                        <div>{formatDateTime(item.validFrom)}</div>
                        <div>{formatDateTime(item.validUntil)}</div>
                      </td>
                      <td className="px-3 py-2.5">
                        <Chip size="sm" variant="soft" color={STATUS_COLOR[item.status]}>
                          {STATUS_LABEL[item.status]}
                        </Chip>
                      </td>
                      <td className="px-3 py-2.5 text-xs text-muted">
                        {formatDateTime(item.createdAt)}
                      </td>
                      <td className="px-3 py-2.5">
                        <div className="flex items-center justify-end gap-1">
                          <Button
                            variant="ghost"
                            size="sm"
                            isIconOnly
                            onPress={() => openRedemptions(item)}
                            aria-label="查看兑换记录"
                          >
                            <Eye className="size-4" />
                          </Button>
                          <Button
                            variant="ghost"
                            size="sm"
                            isIconOnly
                            onPress={() => openEdit(item)}
                            aria-label="编辑"
                          >
                            <Pencil className="size-4" />
                          </Button>
                          <Button
                            variant="ghost"
                            size="sm"
                            isIconOnly
                            onPress={() => setDeleteTarget(item)}
                            aria-label="删除"
                          >
                            <Trash2 className="size-4 text-danger" />
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}

        <div ref={sentinelRef} className="h-10" />

        {loadingMore ? (
          <div className="mt-2 grid gap-3">
            <Skeleton className="h-12 rounded-xl" />
          </div>
        ) : null}

        {!loading && !hasMore && records.length > 0 ? (
          <p className="py-4 text-center text-xs text-muted">已加载全部 {totalRecords} 条数据</p>
        ) : null}
      </ScrollShadow>

      {/* Create / Edit modal */}
      <Modal.Backdrop
        isOpen={formOpen}
        onOpenChange={(open) => {
          if (!open) {
            setFormOpen(false);
            setForm(EMPTY_FORM);
          }
        }}
      >
        <Modal.Container size="md">
          <Modal.Dialog className="overflow-visible">
            <Modal.CloseTrigger />
            <Modal.Header>
              <Modal.Heading>{form.id ? "编辑礼包码" : "创建礼包码"}</Modal.Heading>
            </Modal.Header>
            <Form
              onSubmit={(e) => {
                e.preventDefault();
                handleSubmit();
              }}
            >
              <Modal.Body className="space-y-4 overflow-visible">
                {!form.id && (
                  <TextField
                    className="w-full"
                    variant="secondary"
                    value={form.code}
                    onChange={(v) => setForm((p) => ({ ...p, code: v.toUpperCase() }))}
                  >
                    <Label>礼包码（可选，留空将自动生成）</Label>
                    <Input placeholder="如 WELCOME2026" className="font-mono" />
                  </TextField>
                )}

                <div className="grid grid-cols-2 gap-4">
                  <TextField
                    className="w-full"
                    variant="secondary"
                    value={form.name}
                    onChange={(v) => setForm((p) => ({ ...p, name: v }))}
                  >
                    <Label>名称</Label>
                    <Input placeholder="如 新用户欢迎礼包" />
                  </TextField>
                  <NumberField
                    className="w-full"
                    variant="secondary"
                    value={form.points}
                    onChange={(v) => setForm((p) => ({ ...p, points: v ?? 0 }))}
                    minValue={1}
                    isRequired
                  >
                    <Label>积分</Label>
                    <Input placeholder="100" />
                  </NumberField>
                </div>

                <TextField
                  className="w-full"
                  variant="secondary"
                  value={form.description}
                  onChange={(v) => setForm((p) => ({ ...p, description: v }))}
                >
                  <Label>描述</Label>
                  <Input placeholder="可选" />
                </TextField>

                <div className="grid grid-cols-2 gap-4">
                  <TextField
                    className="w-full"
                    variant="secondary"
                    value={form.validFrom}
                    onChange={(v) => setForm((p) => ({ ...p, validFrom: v }))}
                  >
                    <Label>生效时间（可选）</Label>
                    <Input type="datetime-local" />
                  </TextField>
                  <TextField
                    className="w-full"
                    variant="secondary"
                    value={form.validUntil}
                    onChange={(v) => setForm((p) => ({ ...p, validUntil: v }))}
                  >
                    <Label>过期时间（可选）</Label>
                    <Input type="datetime-local" />
                  </TextField>
                </div>

                <div className="grid grid-cols-2 gap-4">
                  <NumberField
                    className="w-full"
                    variant="secondary"
                    value={form.maxRedemptions}
                    onChange={(v) => setForm((p) => ({ ...p, maxRedemptions: v ?? 1 }))}
                    minValue={1}
                    isRequired
                  >
                    <Label>最大使用次数</Label>
                    <Input placeholder="1" />
                  </NumberField>
                  <Select
                    className="w-full"
                    variant="secondary"
                    value={form.status}
                    onChange={(v) => setForm((p) => ({ ...p, status: String(v) as GiftCodeStatus }))}
                  >
                    <Label>状态</Label>
                    <Select.Trigger>
                      <Select.Value />
                      <Select.Indicator />
                    </Select.Trigger>
                    <Select.Popover>
                      <ListBox>
                        <ListBox.Item id="ACTIVE" textValue="启用">启用<ListBox.ItemIndicator /></ListBox.Item>
                        <ListBox.Item id="DISABLED" textValue="停用">停用<ListBox.ItemIndicator /></ListBox.Item>
                      </ListBox>
                    </Select.Popover>
                  </Select>
                </div>
              </Modal.Body>
              <Modal.Footer>
                <Button variant="secondary" slot="close">
                  取消
                </Button>
                <Button type="submit" isPending={saving} className="gap-1">
                  {({ isPending }) => (
                    <>
                      {isPending ? <Spinner color="current" size="sm" /> : null}
                      {form.id ? "保存" : "创建"}
                    </>
                  )}
                </Button>
              </Modal.Footer>
            </Form>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>

      {/* Delete confirm */}
      <Modal.Backdrop
        isOpen={deleteTarget !== null}
        onOpenChange={(open) => !open && setDeleteTarget(null)}
      >
        <Modal.Container size="sm">
          <Modal.Dialog>
            <Modal.CloseTrigger />
            <Modal.Header>
              <Modal.Heading>确认删除</Modal.Heading>
            </Modal.Header>
            <Modal.Body>
              <p className="text-sm text-muted">
                确定要删除礼包码{" "}
                <code className="rounded bg-surface-secondary px-1 font-mono text-xs">
                  {deleteTarget?.code}
                </code>{" "}
                吗？已兑换记录将被保留。
              </p>
            </Modal.Body>
            <Modal.Footer>
              <Button variant="secondary" slot="close">
                取消
              </Button>
              <Button variant="danger" isPending={deleting} onPress={handleDelete}>
                {({ isPending }) => (
                  <>
                    {isPending ? <Spinner color="current" size="sm" /> : null}
                    确认删除
                  </>
                )}
              </Button>
            </Modal.Footer>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>

      {/* Redemptions */}
      <Modal.Backdrop
        isOpen={redemptionsTarget !== null}
        onOpenChange={(open) => {
          if (!open) {
            setRedemptionsTarget(null);
            setRedemptions([]);
          }
        }}
      >
        <Modal.Container size="lg">
          <Modal.Dialog>
            <Modal.CloseTrigger />
            <Modal.Header>
              <Modal.Heading>
                兑换记录
                {redemptionsTarget && (
                  <span className="ml-2 font-mono text-sm text-muted">
                    {redemptionsTarget.code}
                  </span>
                )}
              </Modal.Heading>
            </Modal.Header>
            <Modal.Body>
              {loadingRedemptions ? (
                <div className="grid gap-2">
                  <Skeleton className="h-10 rounded-lg" />
                  <Skeleton className="h-10 rounded-lg" />
                </div>
              ) : redemptions.length === 0 ? (
                <p className="py-6 text-center text-sm text-muted">暂无兑换记录</p>
              ) : (
                <div className="max-h-[60vh] overflow-y-auto rounded-lg border border-border">
                  <table className="min-w-full text-left text-sm">
                    <thead className="sticky top-0 bg-surface-secondary">
                      <tr>
                        <th className="px-3 py-2 text-xs font-semibold text-muted">用户ID</th>
                        <th className="px-3 py-2 text-xs font-semibold text-muted">工作空间</th>
                        <th className="px-3 py-2 text-xs font-semibold text-muted">积分</th>
                        <th className="px-3 py-2 text-xs font-semibold text-muted">兑换时间</th>
                      </tr>
                    </thead>
                    <tbody>
                      {redemptions.map((r) => (
                        <tr key={r.id} className="border-t border-border">
                          <td className="px-3 py-2 font-mono text-xs">{r.userId.slice(0, 12)}…</td>
                          <td className="px-3 py-2 font-mono text-xs">{r.workspaceId.slice(0, 12)}…</td>
                          <td className="px-3 py-2 font-mono text-xs">{r.points.toLocaleString()}</td>
                          <td className="px-3 py-2 text-xs text-muted">{formatDateTime(r.createdAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Modal.Body>
            <Modal.Footer>
              <Button variant="secondary" slot="close">
                关闭
              </Button>
            </Modal.Footer>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>
    </div>
  );
}
