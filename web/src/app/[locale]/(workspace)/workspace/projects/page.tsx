
"use client";

import { useState, useEffect, useCallback, useRef, memo } from "react";
import { useTranslations, useLocale } from "next-intl";
import { useRouter } from "next/navigation";
import {
  Button,
  Modal,
  TextField,
  Label,
  Input,
  TextArea,
  Tabs,
  Spinner,
  Separator,
  SearchField,
  Switch,
  toast,
} from "@heroui/react";
import {
  Plus,
  RefreshCw,
  Film,
  Pencil,
  Copy,
  Trash2,
  Archive,
  Loader2,
  Clock,
  ArrowUp,
  ArrowDown,
  ArrowDownAZ,
} from "lucide-react";
import { EntityCard, EntityCardSkeleton, type EntityCardAction } from "@/components/ui/entity-card";
import { projectService, collabService, getErrorFromException } from "@/lib/api";
import { openScript } from "@/lib/stores/script-store";
import { getUserId } from "@/lib/stores/auth-store";
import { useWorkspace } from "@/components/providers/workspace-provider";
import { useWebSocket, useWebSocketMessage } from "@/lib/websocket";
import type { CollabUser } from "@/lib/websocket";
import { ActiveUsersAvatarGroup } from "@/components/ui/active-users-avatar-group";
import { UserChip } from "@/components/ui/user-chip";
import { BackgroundRippleEffect } from "@/components/ui/background-ripple-effect";
import { SpotlightOverlay } from "@/components/onboarding/spotlight-overlay";
import { useOnboardingStore } from "@/lib/stores/onboarding-store";
import type { ScriptListDTO, ScriptStatus, UserLocationDTO, ScriptCollaborationDTO } from "@/lib/api/dto";

type StatusFilter = "ALL" | ScriptStatus;
type OrderByField = "updated_at" | "created_at" | "title";
type OrderDirection = "asc" | "desc";

const PAGE_SIZE = 12;
const STATUS_OPTIONS: StatusFilter[] = ["ALL", "DRAFT", "IN_PROGRESS", "COMPLETED", "ARCHIVED"];

interface ProjectCardProps {
  project: ScriptListDTO;
  activeUsers: CollabUser[];
  currentWorkspaceId: string | null;
  locale: string;
  duplicatingId: string | null;
  archivingId: string | null;
  onOpen: (project: ScriptListDTO) => void;
  onDuplicate: (project: ScriptListDTO) => void;
  onArchive: (project: ScriptListDTO) => void;
  onDelete: (project: ScriptListDTO) => void;
  t: (key: string, values?: Record<string, string | number>) => string;
  getStatusLabel: (status: StatusFilter) => string;
  getStatusColor: (status: ScriptStatus) => string;
  formatDate: (dateStr: string) => string;
}

const ProjectCard = memo(function ProjectCard({
  project,
  activeUsers,
  duplicatingId,
  archivingId,
  onOpen,
  onDuplicate,
  onArchive,
  onDelete,
  t,
  getStatusLabel,
  getStatusColor,
  formatDate,
}: ProjectCardProps) {
  const actions: EntityCardAction[] = [
    { id: "edit", label: t("card.edit"), icon: Pencil, onAction: () => onOpen(project) },
    { id: "duplicate", label: t("card.duplicate"), icon: Copy, onAction: () => onDuplicate(project) },
  ];
  if (project.status !== "ARCHIVED") {
    actions.push({ id: "archive", label: t("card.archive"), icon: Archive, onAction: () => onArchive(project) });
  }
  actions.push({
    id: "delete",
    label: t("card.delete"),
    icon: Trash2,
    variant: "danger",
    separatorBefore: true,
    onAction: () => onDelete(project),
  });

  const statusBadge = (
    <span className={`rounded-md px-2 py-1 text-xs font-medium shadow-sm ${getStatusColor(project.status)}`}>
      {getStatusLabel(project.status)}
    </span>
  );

  return (
    <EntityCard
      title={project.title}
      description={project.synopsis}
      descriptionFallback={t("noDescription")}
      coverUrl={project.coverUrl}
      fallbackIcon={<Film className="size-12 text-muted/20" />}
      topLeftBadge={statusBadge}
      actions={actions}
      actionsLabel={t("card.more")}
      isActionPending={duplicatingId === project.id || archivingId === project.id}
      footerLeft={
        <UserChip
          userId={project.createdBy}
          nickname={project.createdByNickname}
          username={project.createdByUsername}
        />
      }
      footerRight={
        <>
          {activeUsers.length > 0 && (
            <ActiveUsersAvatarGroup users={activeUsers} maxVisible={3} size="sm" />
          )}
          <span>{formatDate(project.createdAt)}</span>
        </>
      }
      onClick={() => onOpen(project)}
    />
  );
});

export default function ProjectsPage() {
  const t = useTranslations("workspace.projects");
  const tc = useTranslations("common");
  const locale = useLocale();
  const router = useRouter();

  const [projects, setProjects] = useState<ScriptListDTO[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("ALL");
  const [searchKeyword, setSearchKeyword] = useState("");
  const [isRefreshing, setIsRefreshing] = useState(false);

  // Sorting and filtering state
  const [orderBy, setOrderBy] = useState<OrderByField>("updated_at");
  const [orderDir, setOrderDir] = useState<OrderDirection>("desc");
  const [onlyMine, setOnlyMine] = useState(false);

  // Infinite scroll state
  const [currentPage, setCurrentPage] = useState(1);
  const [hasMore, setHasMore] = useState(true);
  const [totalRecords, setTotalRecords] = useState(0);
  const loadMoreRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [createTitle, setCreateTitle] = useState("");
  const [createSynopsis, setCreateSynopsis] = useState("");
  const [isCreating, setIsCreating] = useState(false);

  const [deleteTarget, setDeleteTarget] = useState<ScriptListDTO | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);
  const [duplicatingId, setDuplicatingId] = useState<string | null>(null);
  const [archivingId, setArchivingId] = useState<string | null>(null);

  const { currentWorkspaceId } = useWorkspace();
  const { scriptCollabs, getScriptUsers } = useWebSocket();

  const createButtonRef = useRef<HTMLDivElement>(null);
  const hasCompletedOnboarding = useOnboardingStore((s) => s.hasCompletedOnboarding);
  const dismissedTips = useOnboardingStore((s) => s.dismissedTips);
  const dismissTip = useOnboardingStore((s) => s.dismissTip);

  // Track active users per script
  const [scriptActiveUsers, setScriptActiveUsers] = useState<Map<string, CollabUser[]>>(new Map());

  // Helper to convert UserLocationDTO to CollabUser
  const userLocationToCollabUser = (user: UserLocationDTO): CollabUser => ({
    userId: user.userId,
    nickname: user.nickname,
    avatar: user.avatar,
    tab: user.tab,
    focusedEntityType: user.focusedEntityType,
    focusedEntityId: user.focusedEntityId,
    collabStatus: user.collabStatus,
  });

  // Fetch collaboration data for scripts
  const fetchCollaborationData = useCallback(async (scriptIds: string[]) => {
    if (scriptIds.length === 0) return;

    try {
      const collabData = await collabService.batchGetScriptCollaborations(scriptIds);
      setScriptActiveUsers((prev) => {
        const next = new Map(prev);
        Object.entries(collabData).forEach(([scriptId, data]) => {
          next.set(scriptId, data.users.map(userLocationToCollabUser));
        });
        return next;
      });
    } catch (error) {
      console.error("Failed to fetch collaboration data:", error);
      toast.danger(getErrorFromException(error, locale));
    }
  }, []);

  // Subscribe to collaboration messages
  useWebSocketMessage((message) => {
    // Update active users when receiving collaboration updates
    if (
      message.type === "SCRIPT_COLLABORATION" ||
      message.type === "USER_JOINED" ||
      message.type === "USER_LEFT" ||
      message.type === "USER_LOCATION_CHANGED"
    ) {
      const scriptId = message.scriptId || (message.data as { scriptId?: string })?.scriptId;
      if (scriptId) {
        const users = getScriptUsers(scriptId);
        setScriptActiveUsers((prev) => {
          const next = new Map(prev);
          next.set(scriptId, users);
          return next;
        });
      }
    }

    // Handle entity changes to refresh the list
    if (message.type === "ENTITY_CHANGED" && message.entityType === "script") {
      // Reset and reload from first page
      resetAndLoad();
    }
  }, [getScriptUsers]);

  // Sync scriptCollabs to local state
  useEffect(() => {
    const newMap = new Map<string, CollabUser[]>();
    scriptCollabs.forEach((collab, scriptId) => {
      newMap.set(scriptId, collab.users);
    });
    setScriptActiveUsers(newMap);
  }, [scriptCollabs]);

  // Listen for onboarding "create project" event
  useEffect(() => {
    const handler = () => setIsCreateModalOpen(true);
    window.addEventListener("onboarding:createProject", handler);
    return () => window.removeEventListener("onboarding:createProject", handler);
  }, []);

  // Load projects (initial or append)
  const loadProjects = useCallback(async (page: number, append: boolean = false) => {
    if (!currentWorkspaceId) return;

    try {
      if (append) {
        setIsLoadingMore(true);
      }

      const params: Record<string, string | number | boolean | undefined> = {
        pageNum: page,
        pageSize: PAGE_SIZE,
        orderBy: orderBy,
        orderDir: orderDir,
      };

      if (statusFilter !== "ALL") {
        params.status = statusFilter;
      }
      if (searchKeyword.trim()) {
        params.keyword = searchKeyword.trim();
      }
      if (onlyMine) {
        const userId = getUserId();
        if (userId) {
          params.createdBy = userId;
        }
      }

      const response = await projectService.queryScripts( params);
      const newProjects = response.records;

      if (append) {
        setProjects((prev) => {
          // Deduplicate by id to avoid duplicate key errors
          const existingIds = new Set(prev.map((p) => p.id));
          const uniqueNewProjects = newProjects.filter((p) => !existingIds.has(p.id));
          return [...prev, ...uniqueNewProjects];
        });
      } else {
        setProjects(newProjects);
      }

      setTotalRecords(response.total);
      setCurrentPage(response.current);
      setHasMore(response.current < response.pages);

      // Fetch collaboration data for new projects
      const scriptIds = newProjects.map((p) => p.id);
      fetchCollaborationData(scriptIds);
    } catch (error) {
      console.error("Failed to load projects:", error);
      toast.danger(getErrorFromException(error, locale));
      // Stop auto-loading on error to prevent infinite retry via IntersectionObserver
      setHasMore(false);
    } finally {
      setIsLoading(false);
      setIsLoadingMore(false);
      setIsRefreshing(false);
    }
  }, [currentWorkspaceId, statusFilter, searchKeyword, orderBy, orderDir, onlyMine, fetchCollaborationData]);

  // Reset and load from first page
  const resetAndLoad = useCallback(() => {
    setProjects([]);
    setCurrentPage(1);
    setHasMore(true);
    setIsLoading(true);
    loadProjects(1, false);
  }, [loadProjects]);

  // Load more (next page) — use ref so observer callback always sees latest state
  const loadMoreCallbackRef = useRef(() => {});
  loadMoreCallbackRef.current = () => {
    if (!isLoadingMore && hasMore && !isLoading) {
      loadProjects(currentPage + 1, true);
    }
  };

  // Initial load
  useEffect(() => {
    resetAndLoad();
  }, [currentWorkspaceId, statusFilter, orderBy, orderDir, onlyMine]);

  // Debounced search
  useEffect(() => {
    const timer = setTimeout(() => {
      if (!isLoading && currentWorkspaceId) {
        resetAndLoad();
      }
    }, 300);
    return () => clearTimeout(timer);
  }, [searchKeyword]);

  // Intersection Observer for infinite scroll — uses a persistent observer + callback ref
  const observerRef = useRef<IntersectionObserver | null>(null);

  useEffect(() => {
    observerRef.current = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting) {
          loadMoreCallbackRef.current();
        }
      },
      {
        root: containerRef.current,
        rootMargin: "100px",
        threshold: 0.1,
      }
    );
    return () => {
      observerRef.current?.disconnect();
      observerRef.current = null;
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Callback ref: auto-observe when the sentinel mounts, unobserve when it unmounts
  const loadMoreSentinelRef = useCallback((node: HTMLDivElement | null) => {
    // Disconnect previous target
    if (loadMoreRef.current && observerRef.current) {
      observerRef.current.unobserve(loadMoreRef.current);
    }
    loadMoreRef.current = node;
    if (node && observerRef.current) {
      observerRef.current.observe(node);
    }
  }, []);

  const handleRefresh = () => {
    setIsRefreshing(true);
    resetAndLoad();
  };

  const handleStatusChange = (status: StatusFilter) => {
    setStatusFilter(status);
  };

  const handleCreateProject = async () => {
    if (!currentWorkspaceId || !createTitle.trim()) return;
    try {
      setIsCreating(true);
      await projectService.createScript( {
        title: createTitle.trim(),
        synopsis: createSynopsis.trim() || undefined,
      });
      setIsCreateModalOpen(false);
      setCreateTitle("");
      setCreateSynopsis("");
      resetAndLoad();
    } catch (error) {
      console.error("Failed to create project:", error);
      toast.danger(getErrorFromException(error, locale));
    } finally {
      setIsCreating(false);
    }
  };

  const handleDeleteProject = async () => {
    if (!currentWorkspaceId || !deleteTarget) return;
    try {
      setIsDeleting(true);
      await projectService.deleteScript( deleteTarget.id);
      setDeleteTarget(null);
      // Remove from local state immediately
      setProjects((prev) => prev.filter((p) => p.id !== deleteTarget.id));
      setTotalRecords((prev) => prev - 1);
    } catch (error) {
      console.error("Failed to delete project:", error);
      toast.danger(getErrorFromException(error, locale));
    } finally {
      setIsDeleting(false);
    }
  };

  const handleDuplicateProject = async (project: ScriptListDTO) => {
    if (!currentWorkspaceId) return;
    try {
      setDuplicatingId(project.id);
      await projectService.createScript( {
        title: t("copyTitle", { title: project.title }),
        synopsis: project.synopsis || undefined,
        coverAssetId: project.coverAssetId || undefined,
      });
      resetAndLoad();
    } catch (error) {
      console.error("Failed to duplicate project:", error);
      toast.danger(getErrorFromException(error, locale));
    } finally {
      setDuplicatingId(null);
    }
  };

  const handleArchiveProject = async (project: ScriptListDTO) => {
    if (!currentWorkspaceId) return;
    try {
      setArchivingId(project.id);
      await projectService.archiveScript( project.id);
      // Update local state
      setProjects((prev) =>
        prev.map((p) => (p.id === project.id ? { ...p, status: "ARCHIVED" as ScriptStatus } : p))
      );
    } catch (error) {
      console.error("Failed to archive project:", error);
      toast.danger(getErrorFromException(error, locale));
    } finally {
      setArchivingId(null);
    }
  };

  const getStatusLabel = (status: StatusFilter) => {
    const labels: Record<StatusFilter, string> = {
      ALL: t("status.all"),
      DRAFT: t("status.draft"),
      IN_PROGRESS: t("status.inProgress"),
      COMPLETED: t("status.completed"),
      ARCHIVED: t("status.archived"),
    };
    return labels[status] || status;
  };

  const getStatusColor = (status: ScriptStatus) => {
    const colors: Record<ScriptStatus, string> = {
      DRAFT: "bg-orange-400/80 text-white",
      IN_PROGRESS: "bg-sky-400/80 text-white",
      COMPLETED: "bg-teal-400/80 text-white",
      ARCHIVED: "bg-slate-400/80 text-white",
    };
    return colors[status] || "bg-slate-400/80 text-white";
  };

  const formatDate = (dateStr: string) => new Date(dateStr).toLocaleDateString();

  const handleSortPress = (field: OrderByField, defaultDir: OrderDirection = "desc") => {
    if (orderBy === field) {
      setOrderDir((prev) => (prev === "desc" ? "asc" : "desc"));
    } else {
      setOrderBy(field);
      setOrderDir(defaultDir);
    }
  };

  const handleOpenProject = useCallback((project: ScriptListDTO) => {
    if (currentWorkspaceId) {
      openScript(project.id, project.title, currentWorkspaceId, project.coverUrl ?? undefined, project.status);
    }
    router.push(`/${locale}/workspace/projects/${project.id}`);
  }, [currentWorkspaceId, locale, router]);

  // ============ 渲染 ============
  const showSpotlight =
    !isLoading && projects.length === 0 && hasCompletedOnboarding && !dismissedTips.includes("projects-getting-started");

  return (
    <>
      <div className="relative flex h-full flex-col overflow-hidden">
        {/* Interactive grid background */}
        <BackgroundRippleEffect />

        {/* Content layer — pointer-events-none lets clicks pass through to ripple grid */}
        <div className="pointer-events-none relative z-10 flex h-full flex-col">
        {/* 顶部工具栏 */}
        <div className="pointer-events-auto flex shrink-0 items-center justify-between gap-3 bg-white/60 px-6 py-2.5 backdrop-blur-xl dark:bg-white/5">
          {/* 左侧：状态筛选 + 只看我的 */}
          <div className="flex items-center gap-3">
            <Tabs
              selectedKey={statusFilter}
              onSelectionChange={(key) => handleStatusChange(key as StatusFilter)}
            >
              <Tabs.ListContainer>
                <Tabs.List aria-label="Status filter">
                  {STATUS_OPTIONS.map((status) => (
                    <Tabs.Tab key={status} id={status} className="whitespace-nowrap px-3 text-xs">
                      {getStatusLabel(status)}
                      <Tabs.Indicator />
                    </Tabs.Tab>
                  ))}
                </Tabs.List>
              </Tabs.ListContainer>
            </Tabs>

            <Separator orientation="vertical" className="h-5 self-center" />

            {/* 只看我的 */}
            <Switch
              size="sm"
              isSelected={onlyMine}
              onChange={setOnlyMine}
            >
              <Switch.Control>
                <Switch.Thumb />
              </Switch.Control>
              <Label className="whitespace-nowrap text-xs">{t("filter.onlyMine")}</Label>
            </Switch>
          </div>

          {/* 右侧：排序 + 搜索 + 操作 */}
          <div className="flex items-center gap-2">
            {/* 排序控件组 */}
            <Button
              variant={orderBy === "updated_at" ? "secondary" : "ghost"}
              size="sm"
              onPress={() => handleSortPress("updated_at", "desc")}
              className="gap-1 text-xs"
            >
              <Clock className="size-3.5" />
              {t("sort.updatedAt")}
              {orderBy === "updated_at" && (
                orderDir === "desc" ? <ArrowDown className="size-3" /> : <ArrowUp className="size-3" />
              )}
            </Button>
            <Button
              variant={orderBy === "created_at" ? "secondary" : "ghost"}
              size="sm"
              onPress={() => handleSortPress("created_at", "desc")}
              className="gap-1 text-xs"
            >
              <Clock className="size-3.5" />
              {t("sort.createdAt")}
              {orderBy === "created_at" && (
                orderDir === "desc" ? <ArrowDown className="size-3" /> : <ArrowUp className="size-3" />
              )}
            </Button>
            <Button
              variant={orderBy === "title" ? "secondary" : "ghost"}
              size="sm"
              onPress={() => handleSortPress("title", "asc")}
              className="gap-1 text-xs"
            >
              <ArrowDownAZ className="size-3.5" />
              {t("sort.title")}
              {orderBy === "title" && (
                orderDir === "asc" ? <ArrowUp className="size-3" /> : <ArrowDown className="size-3" />
              )}
            </Button>

            {/* 搜索框 */}
            <SearchField
              aria-label={t("searchPlaceholder")}
              value={searchKeyword}
              onChange={setSearchKeyword}
              variant="secondary"
            >
              <SearchField.Group>
                <SearchField.SearchIcon />
                <SearchField.Input className="w-36" placeholder={t("searchPlaceholder")} />
                <SearchField.ClearButton />
              </SearchField.Group>
            </SearchField>

            {/* 刷新按钮 */}
            <Button
              variant="ghost"
              size="sm"
              isIconOnly
              isPending={isRefreshing}
              onPress={handleRefresh}
            >
              {({ isPending }) =>
                isPending ? <Spinner size="sm" color="current" /> : <RefreshCw className="size-4" />
              }
            </Button>

            {/* 创建按钮 */}
            <div
              ref={createButtonRef}
              className={showSpotlight ? "relative" : ""}
              style={showSpotlight ? { zIndex: 50 } : undefined}
            >
              <Button
                size="sm"
                onPress={() => {
                  if (showSpotlight) dismissTip("projects-getting-started");
                  setIsCreateModalOpen(true);
                }}
              >
                <Plus className="size-4" />
                {t("newProject.create")}
              </Button>
            </div>
          </div>
        </div>

        {/* 内容区 - 无限滚动容器 */}
        <div ref={containerRef} className="pointer-events-auto min-h-0 flex-1 overflow-auto">
          {isLoading && projects.length === 0 ? (
            // 初始加载骨架屏
            <div className="grid grid-cols-2 gap-5 p-6 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
              {Array.from({ length: 10 }).map((_, i) => (
                <EntityCardSkeleton key={i} />
              ))}
            </div>
          ) : projects.length === 0 ? (
            // 空状态 - 居中显示
            <div className="pointer-events-auto flex min-h-full flex-col items-center justify-center py-20 text-center">
              <div className="flex size-20 items-center justify-center rounded-full bg-muted/10">
                <Film className="size-10 text-muted/40" />
              </div>
              <p className="mt-5 text-lg font-semibold text-foreground">{t("empty")}</p>
              {!showSpotlight && (
                <p className="mt-1.5 text-sm text-muted">{t("tips.gettingStarted.description")}</p>
              )}
            </div>
          ) : (
            // 项目网格 + 加载更多
            <div className="p-6">
              <div className="grid grid-cols-2 gap-5 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
                {projects.map((project) => (
                  <ProjectCard
                    key={project.id}
                    project={project}
                    activeUsers={scriptActiveUsers.get(project.id) || []}
                    currentWorkspaceId={currentWorkspaceId}
                    locale={locale}
                    duplicatingId={duplicatingId}
                    archivingId={archivingId}
                    onOpen={handleOpenProject}
                    onDuplicate={handleDuplicateProject}
                    onArchive={handleArchiveProject}
                    onDelete={setDeleteTarget}
                    t={t}
                    getStatusLabel={getStatusLabel}
                    getStatusColor={getStatusColor}
                    formatDate={formatDate}
                  />
                ))}
              </div>

              {/* 加载更多触发器 */}
              <div ref={loadMoreSentinelRef} className="pointer-events-auto mt-6 flex items-center justify-center py-4">
                {isLoadingMore ? (
                  <div className="flex items-center gap-2 text-muted">
                    <Loader2 className="size-5 animate-spin" />
                    <span className="text-sm">{t("loadingMore")}</span>
                  </div>
                ) : hasMore ? (
                  <span className="text-sm text-muted/50">{t("scrollToLoadMore")}</span>
                ) : projects.length > 0 ? (
                  <span className="text-sm text-muted/50">{t("allLoaded", { total: totalRecords })}</span>
                ) : null}
              </div>
            </div>
          )}
        </div>
        </div>{/* end pointer-events-none wrapper */}
      </div>

      {/* Spotlight onboarding guide */}
      {showSpotlight && (
        <SpotlightOverlay
          tipId="projects-getting-started"
          targetRef={createButtonRef}
          title={t("tips.gettingStarted.title")}
          description={t("tips.gettingStarted.spotlightDescription")}
          dismissLabel={t("tips.gettingStarted.gotIt")}
        />
      )}

      {/* Create Modal */}
      <Modal.Backdrop isOpen={isCreateModalOpen} onOpenChange={setIsCreateModalOpen}>
        <Modal.Container>
          <Modal.Dialog className="overflow-visible sm:max-w-md">
            <Modal.CloseTrigger />
            <Modal.Header>
              <Modal.Heading>{t("createModal.title")}</Modal.Heading>
            </Modal.Header>
            <Modal.Body className="space-y-4 overflow-visible">
              <TextField className="w-full" name="title" isRequired value={createTitle} onChange={setCreateTitle}>
                <Label>{t("createModal.projectTitle")}</Label>
                <Input placeholder={t("createModal.projectTitlePlaceholder")} />
              </TextField>
              <TextField className="w-full" name="synopsis">
                <Label>{t("createModal.synopsis")}</Label>
                <TextArea placeholder={t("createModal.synopsisPlaceholder")} rows={3} value={createSynopsis} onChange={(e) => setCreateSynopsis(e.target.value)} />
              </TextField>
            </Modal.Body>
            <Modal.Footer>
              <Button variant="secondary" slot="close">{tc("cancel")}</Button>
              <Button onPress={handleCreateProject} isPending={isCreating} isDisabled={!createTitle.trim()}>
                {({isPending}) => (<>{isPending ? <Spinner color="current" size="sm" /> : null}{t("createModal.submit")}</>)}
              </Button>
            </Modal.Footer>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>

      {/* Delete Modal */}
      <Modal.Backdrop isOpen={!!deleteTarget} onOpenChange={() => setDeleteTarget(null)}>
        <Modal.Container>
          <Modal.Dialog className="sm:max-w-sm">
            <Modal.CloseTrigger />
            <Modal.Header>
              <Modal.Heading>{t("deleteModal.title")}</Modal.Heading>
            </Modal.Header>
            <Modal.Body>
              <p className="text-sm text-muted">{t("deleteModal.message", { title: deleteTarget?.title || "" })}</p>
            </Modal.Body>
            <Modal.Footer>
              <Button variant="secondary" slot="close">{tc("cancel")}</Button>
              <Button variant="danger" onPress={handleDeleteProject} isPending={isDeleting}>
                {({isPending}) => (<>{isPending ? <Spinner color="current" size="sm" /> : null}{t("deleteModal.confirm")}</>)}
              </Button>
            </Modal.Footer>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>
    </>
  );
}
