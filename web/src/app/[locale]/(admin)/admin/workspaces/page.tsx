"use client";

import { useCallback, useEffect, useState } from "react";
import {
  Button,
  Chip,
  SearchField,
  Spinner,
  Switch,
  Table,
  toast,
} from "@heroui/react";
import { Building2, FlaskConical, RefreshCw } from "lucide-react";
import { getErrorFromException } from "@/lib/api";
import {
  workspaceAdminService,
  type WorkspaceAdminDTO,
} from "@/lib/api/services/workspace-admin.service";

const PAGE_SIZE = 20;

export default function AdminWorkspacesPage() {
  const [query, setQuery] = useState("");
  const [internalOnly, setInternalOnly] = useState(false);
  const [records, setRecords] = useState<WorkspaceAdminDTO[]>([]);
  const [total, setTotal] = useState(0);
  const [pages, setPages] = useState(0);
  const [current, setCurrent] = useState(1);
  const [loading, setLoading] = useState(true);
  const [togglingId, setTogglingId] = useState<string | null>(null);

  const load = useCallback(
    async (page: number, q: string, only: boolean) => {
      setLoading(true);
      try {
        const r = await workspaceAdminService.searchWorkspaces({
          current: page,
          size: PAGE_SIZE,
          q: q.trim() || undefined,
          internalOnly: only || undefined,
        });
        setRecords(r?.records ?? []);
        setTotal(r?.total ?? 0);
        setPages(r?.pages ?? 0);
        setCurrent(r?.current ?? page);
      } catch (e) {
        toast.danger(getErrorFromException(e, "加载 workspace 失败"));
      } finally {
        setLoading(false);
      }
    },
    []
  );

  useEffect(() => {
    load(1, query, internalOnly);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [internalOnly]);

  const handleToggleInternal = async (ws: WorkspaceAdminDTO, next: boolean) => {
    setTogglingId(ws.id);
    try {
      await workspaceAdminService.setInternal(ws.id, next);
      setRecords((prev) =>
        prev.map((w) => (w.id === ws.id ? { ...w, isInternal: next } : w))
      );
      toast.success(`已${next ? "标记" : "取消"}内部测试 workspace: ${ws.name}`);
    } catch (e) {
      toast.danger(getErrorFromException(e, "更新失败"));
    } finally {
      setTogglingId(null);
    }
  };

  const handleSearch = () => load(1, query, internalOnly);

  return (
    <div className="flex h-full flex-col gap-4 p-6">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <div className="flex size-9 items-center justify-center rounded-lg bg-accent/10">
            <Building2 className="size-5 text-accent" />
          </div>
          <div>
            <h1 className="text-lg font-semibold">Workspace 管理</h1>
            <p className="text-xs text-muted">
              系统租户管理员视角:管理内部测试 workspace 标记,用于灰度可见 provider
            </p>
          </div>
        </div>
        <Button variant="secondary" size="sm" onPress={handleSearch} isPending={loading}>
          {({ isPending }) => (
            <>
              {isPending ? (
                <Spinner color="current" size="sm" />
              ) : (
                <RefreshCw className="size-3.5" />
              )}
              刷新
            </>
          )}
        </Button>
      </div>

      {/* Filters */}
      <div className="flex flex-wrap items-center gap-3 rounded-lg border border-border bg-surface/40 p-3">
        <SearchField
          value={query}
          onChange={setQuery}
          onSubmit={handleSearch}
          aria-label="搜索 workspace"
          className="min-w-[280px] flex-1"
        >
          <SearchField.Group>
            <SearchField.Input placeholder="按 名称 / slug / UUID 搜索" />
            <SearchField.ClearButton />
          </SearchField.Group>
        </SearchField>
        <div className="flex items-center gap-2 rounded-lg border border-border bg-background px-3 py-1.5 text-xs">
          <FlaskConical className="size-3.5 text-purple-500" />
          <span>仅看内部测试</span>
          <Switch isSelected={internalOnly} onChange={setInternalOnly} size="sm">
            <Switch.Control>
              <Switch.Thumb />
            </Switch.Control>
          </Switch>
        </div>
        <span className="text-xs text-muted">
          共 <span className="font-mono font-semibold text-foreground">{total}</span> 个
        </span>
      </div>

      {/* Table */}
      <div className="min-h-0 flex-1 overflow-hidden">
        <Table aria-label="Workspace 列表">
          <Table.ScrollContainer className="h-full overflow-y-auto">
            <Table.Content className="min-w-[800px]">
              <Table.Header>
                <Table.Column isRowHeader>名称 / Slug</Table.Column>
                <Table.Column className="w-40">Owner ID</Table.Column>
                <Table.Column className="w-24">Plan</Table.Column>
                <Table.Column className="w-32 text-center">内部测试</Table.Column>
                <Table.Column className="w-20 text-right">成员数</Table.Column>
                <Table.Column className="w-32 text-right">创建时间</Table.Column>
              </Table.Header>
              <Table.Body
                renderEmptyState={() =>
                  loading ? (
                    <div className="flex items-center justify-center py-20">
                      <Spinner size="md" />
                    </div>
                  ) : (
                    <div className="flex flex-col items-center justify-center py-20 text-center">
                      <Building2 className="size-10 text-muted/30" />
                      <p className="mt-3 text-sm text-muted">暂无匹配 workspace</p>
                    </div>
                  )
                }
              >
                <Table.Collection items={records}>
                  {(ws) => (
                    <Table.Row id={ws.id}>
                      <Table.Cell>
                        <div className="flex flex-col gap-0.5">
                          <span className="text-sm font-medium">{ws.name}</span>
                          <span className="font-mono text-[11px] text-muted">{ws.slug}</span>
                          <span className="font-mono text-[10px] text-muted/70">{ws.id}</span>
                        </div>
                      </Table.Cell>
                      <Table.Cell>
                        <span className="font-mono text-[11px] text-muted">{ws.ownerId}</span>
                      </Table.Cell>
                      <Table.Cell>
                        <Chip size="sm" color="default">
                          {ws.planType ?? "Free"}
                        </Chip>
                      </Table.Cell>
                      <Table.Cell>
                        <div className="flex items-center justify-center gap-2">
                          {togglingId === ws.id ? (
                            <Spinner size="sm" />
                          ) : (
                            <Switch
                              isSelected={ws.isInternal}
                              onChange={(next) => handleToggleInternal(ws, next)}
                              size="sm"
                            >
                              <Switch.Control>
                                <Switch.Thumb />
                              </Switch.Control>
                            </Switch>
                          )}
                        </div>
                      </Table.Cell>
                      <Table.Cell className="text-right">
                        <span className="font-mono text-xs">{ws.memberCount ?? 0}</span>
                      </Table.Cell>
                      <Table.Cell className="text-right">
                        <span className="text-xs text-muted">
                          {ws.createdAt
                            ? new Date(ws.createdAt).toLocaleDateString("zh-CN")
                            : "-"}
                        </span>
                      </Table.Cell>
                    </Table.Row>
                  )}
                </Table.Collection>
              </Table.Body>
            </Table.Content>
          </Table.ScrollContainer>
        </Table>
      </div>

      {/* Pagination */}
      {pages > 1 && (
        <div className="flex items-center justify-end gap-2 text-xs">
          <Button
            size="sm"
            variant="tertiary"
            isDisabled={current <= 1 || loading}
            onPress={() => load(current - 1, query, internalOnly)}
          >
            上一页
          </Button>
          <span className="text-muted">
            第 <span className="font-mono">{current}</span> / {pages} 页
          </span>
          <Button
            size="sm"
            variant="tertiary"
            isDisabled={current >= pages || loading}
            onPress={() => load(current + 1, query, internalOnly)}
          >
            下一页
          </Button>
        </div>
      )}
    </div>
  );
}
