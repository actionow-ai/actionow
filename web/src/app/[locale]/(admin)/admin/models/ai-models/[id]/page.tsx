"use client";

import { useEffect, useMemo, useState } from "react";
import dynamic from "next/dynamic";
import { useParams, useRouter } from "next/navigation";
import { useLocale } from "next-intl";
import {
  Button,
  Chip,
  Input,
  Label,
  ListBox,
  Modal,
  Select,
  Skeleton,
  Switch,
  TextArea,
  TextField,
  Spinner,
  toast,
} from "@heroui/react";
import {
  ArrowLeft,
  ArrowRightLeft,
  Bot,
  Braces,
  CheckCircle2,
  ChevronRight,
  Coins,
  CreditCard,
  Download,
  Eye,
  FileJson,
  FileText,
  FlaskConical,
  Globe,
  Key,
  Layers,
  Lock,
  MessageSquareText,
  Pencil,
  Save,
  Settings2,
  Tag,
  Terminal,
  Timer,
  Trash2,
  Unlock,
  Wifi,
  Zap,
} from "lucide-react";
import type { Key as ReactKey } from "react";
import { aiAdminService, adminService, getErrorFromException } from "@/lib/api";
import type {
  AiProviderType,
  GroovyTemplateDTO,
  LlmProviderDTO,
  SaveModelProviderRequestDTO,
  SystemConfigDTO,
} from "@/lib/api/dto";
import type {
  ProviderVisibility,
  ProviderWhitelistEntryDTO,
} from "@/lib/api/dto/ai-admin.dto";
import {
  workspaceAdminService,
  type WorkspaceAdminDTO,
} from "@/lib/api/services/workspace-admin.service";
import { ResizablePanels } from "@/components/ui/resizable-panels";
import { CodeEditor, type CodeLang } from "@/components/admin/ai-model/code-editor";
import {
  SchemaVisualEditor,
} from "@/components/admin/ai-model/schema-editors";

const MarkdownRenderer = dynamic(
  () =>
    import("@/components/studio/agent-chat/components/markdown-renderer").then(
      (m) => m.MarkdownRenderer
    ),
  { ssr: false }
);

// ─── Types ────────────────────────────────────────────────────────────────────

type AuthType = "NONE" | "API_KEY" | "BEARER" | "AK_SK";
type BillingMode = "static" | "structured" | "groovy";
type RightTab =
  | "requestGroup"
  | "responseGroup"
  | "custom"
  | "pricing"
  | "systemPrompt"
  | "responseSchema"
  | "whitelist";
type RequestSubTab = "script" | "schema";
type ResponseSubTab = "script" | "schema";
type CodeTabId = "request" | "response" | "custom" | "pricing";
type TemplateTarget = CodeTabId;

interface ParsedParam {
  name: string;
  type?: string;
  label?: string;
  description?: string;
}

// ─── Constants ────────────────────────────────────────────────────────────────

const TYPE_OPTIONS: { value: AiProviderType; label: string; badge: string }[] = [
  { value: "IMAGE", label: "图像 (IMAGE)", badge: "bg-purple-100 text-purple-700 dark:bg-purple-950/40 dark:text-purple-300" },
  { value: "VIDEO", label: "视频 (VIDEO)", badge: "bg-blue-100 text-blue-700 dark:bg-blue-950/40 dark:text-blue-300" },
  { value: "AUDIO", label: "音频 (AUDIO)", badge: "bg-orange-100 text-orange-700 dark:bg-orange-950/40 dark:text-orange-300" },
  { value: "TEXT", label: "文本 (TEXT)", badge: "bg-green-100 text-green-700 dark:bg-green-950/40 dark:text-green-300" },
];

const HTTP_METHODS = ["POST", "GET", "PUT", "PATCH"];

const AUTH_TYPES: {
  value: AuthType;
  label: string;
  desc: string;
  Icon: React.ComponentType<{ className?: string }>;
}[] = [
  { value: "NONE", label: "无认证", desc: "公开接口", Icon: Unlock },
  { value: "API_KEY", label: "API Key", desc: "Header 密钥", Icon: Key },
  { value: "BEARER", label: "Bearer", desc: "Token 授权", Icon: Tag },
  { value: "AK_SK", label: "AK / SK", desc: "双密钥对", Icon: Lock },
];

const BILLING_MODES: {
  mode: BillingMode;
  label: string;
  desc: string;
  Icon: React.ComponentType<{ className?: string }>;
}[] = [
  { mode: "static", label: "静态积分", desc: "固定积分", Icon: Coins },
  { mode: "structured", label: "结构化", desc: "JSON 规则", Icon: FileText },
  { mode: "groovy", label: "Groovy", desc: "动态脚本", Icon: Zap },
];

const MAIN_TABS: {
  id: RightTab;
  label: string;
  Icon: React.ComponentType<{ className?: string }>;
}[] = [
  { id: "requestGroup", label: "请求 & 输入", Icon: Terminal },
  { id: "responseGroup", label: "响应 & 输出", Icon: ArrowRightLeft },
  { id: "custom", label: "自定义", Icon: Zap },
  { id: "whitelist", label: "可见性 / 白名单", Icon: Lock },
];

const VISIBILITY_OPTIONS: { id: ProviderVisibility; label: string; desc: string }[] = [
  { id: "PUBLIC", label: "PUBLIC", desc: "所有 workspace 可见(默认)" },
  { id: "INTERNAL", label: "INTERNAL", desc: "仅 is_internal=true 的 workspace 可见" },
  { id: "WHITELIST", label: "WHITELIST", desc: "仅白名单中的 workspace 可见" },
];

const CODE_TAB_META: Record<
  CodeTabId,
  { label: string; desc: string; lang: CodeLang }
> = {
  request: {
    label: "请求构建",
    desc: "构建发送给上游 API 的请求体 / 调用 llm.chat()",
    lang: "groovy",
  },
  response: {
    label: "响应映射",
    desc: "将上游 API 响应映射为标准 StandardResponse 格式",
    lang: "groovy",
  },
  custom: {
    label: "自定义",
    desc: "可选：额外的自定义处理逻辑",
    lang: "groovy",
  },
  pricing: {
    label: "计费脚本",
    desc: "动态积分计算 · 绑定变量: inputs, baseCredits",
    lang: "groovy",
  },
};

// ─── Helpers ──────────────────────────────────────────────────────────────────

function parseObj(text: string, field: string): Record<string, unknown> | undefined {
  const t = text.trim();
  if (!t) return undefined;
  let v: unknown;
  try { v = JSON.parse(t); } catch { throw new Error(`${field} 不是合法的 JSON 对象`); }
  if (!v || typeof v !== "object" || Array.isArray(v)) throw new Error(`${field} 必须是 JSON 对象`);
  return v as Record<string, unknown>;
}

function parseArr(text: string, field: string): unknown[] | undefined {
  const t = text.trim();
  if (!t) return undefined;
  let v: unknown;
  try { v = JSON.parse(t); } catch { throw new Error(`${field} 不是合法的 JSON 数组`); }
  if (!Array.isArray(v)) throw new Error(`${field} 必须是 JSON 数组`);
  return v;
}

// ─── Sub-components ───────────────────────────────────────────────────────────

function SectionLabel({
  icon: Icon,
  title,
  iconCls,
}: {
  icon: React.ComponentType<{ className?: string }>;
  title: string;
  iconCls: string;
}) {
  return (
    <div className="mb-3 flex items-center gap-2">
      <div className={`flex size-5 shrink-0 items-center justify-center rounded ${iconCls}`}>
        <Icon className="size-3" />
      </div>
      <span className="text-[11px] font-semibold uppercase tracking-widest text-muted">{title}</span>
    </div>
  );
}

function Divider() {
  return <div className="border-t border-border/50" />;
}

type SelectableCardProps = {
  active: boolean;
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  desc: string;
} & (
  | { mode: "radio"; onPress: () => void }
  | { mode: "toggle"; toggleValue: boolean; onToggle: (v: boolean) => void }
);

function SelectableCard(props: SelectableCardProps) {
  const { active, icon: Icon, label, desc, mode } = props;
  const baseCls = `relative flex items-center gap-2 rounded-lg border p-2.5 text-left transition-all ${
    active
      ? "border-accent bg-accent/5"
      : "border-border hover:border-muted hover:bg-surface"
  }`;

  const body = (
    <>
      <Icon className="size-3.5 shrink-0 text-muted" />
      <div className="min-w-0 flex-1">
        <p className="text-xs font-medium leading-none">{label}</p>
        <p className="mt-1 truncate text-[10px] text-muted">{desc}</p>
      </div>
    </>
  );

  if (mode === "radio") {
    return (
      <button type="button" onClick={props.onPress} className={baseCls}>
        {body}
        {active && (
          <CheckCircle2 className="absolute right-1.5 top-1.5 size-3 text-accent" />
        )}
      </button>
    );
  }

  return (
    <div className={baseCls}>
      {body}
      <Switch
        isSelected={props.toggleValue}
        onChange={props.onToggle}
        size="sm"
      >
        <Switch.Control>
          <Switch.Thumb />
        </Switch.Control>
      </Switch>
    </div>
  );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function AiModelEditPage() {
  const locale = useLocale();
  const router = useRouter();
  const params = useParams<{ id: string }>();
  const id = Array.isArray(params.id) ? params.id[0] : params.id;
  const isCreate = id === "new";

  const [loading, setLoading] = useState(!isCreate);
  const [submitting, setSubmitting] = useState(false);
  const [deleteModalOpen, setDeleteModalOpen] = useState(false);
  const [rightTab, setRightTab] = useState<RightTab>("requestGroup");
  const [requestSubTab, setRequestSubTab] = useState<RequestSubTab>("script");
  const [responseSubTab, setResponseSubTab] = useState<ResponseSubTab>("script");
  const [testingConnection, setTestingConnection] = useState(false);
  const [groovyPickerOpen, setGroovyPickerOpen] = useState(false);
  const [groovyTemplates, setGroovyTemplates] = useState<GroovyTemplateDTO[]>([]);
  const [loadingTemplates, setLoadingTemplates] = useState(false);
  const [activeTemplateTarget, setActiveTemplateTarget] = useState<TemplateTarget>("request");

  // ── LLM provider dropdown (TEXT type)
  const [llmProviderOptions, setLlmProviderOptions] = useState<LlmProviderDTO[]>([]);
  const [loadingLlmProviders, setLoadingLlmProviders] = useState(false);
  const [systemPromptPreview, setSystemPromptPreview] = useState(false);

  // ── Param reference strip (Groovy editor)
  const [refStripOpen, setRefStripOpen] = useState(true);

  // ── Basic
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [providerType, setProviderType] = useState<AiProviderType>("IMAGE");
  const [pluginId, setPluginId] = useState("");
  const [pluginType, setPluginType] = useState("GROOVY");
  const [iconUrl, setIconUrl] = useState("");
  const [enabled, setEnabled] = useState(true);
  const [visibility, setVisibility] = useState<ProviderVisibility>("PUBLIC");

  // ── Whitelist (可见性=WHITELIST 时管理)
  const [whitelistEntries, setWhitelistEntries] = useState<ProviderWhitelistEntryDTO[]>([]);
  const [wlLoading, setWlLoading] = useState(false);
  const [wlSearchQuery, setWlSearchQuery] = useState("");
  const [wlSearchResults, setWlSearchResults] = useState<WorkspaceAdminDTO[]>([]);
  const [wlSearching, setWlSearching] = useState(false);

  // ── HTTP & Auth
  const [baseUrl, setBaseUrl] = useState("");
  const [endpoint, setEndpoint] = useState("");
  const [httpMethod, setHttpMethod] = useState("POST");
  const [authType, setAuthType] = useState<AuthType>("NONE");
  const [authApiKeyHeader, setAuthApiKeyHeader] = useState("X-API-Key");
  const [authApiKey, setAuthApiKey] = useState("");
  const [authBearerToken, setAuthBearerToken] = useState("");
  const [authAccessKey, setAuthAccessKey] = useState("");
  const [authSecretKey, setAuthSecretKey] = useState("");
  const [apiKeyRef, setApiKeyRef] = useState("");
  const [baseUrlRef, setBaseUrlRef] = useState("");

  // AI provider config ref options
  const [apiKeyOptions, setApiKeyOptions] = useState<SystemConfigDTO[]>([]);
  const [baseUrlOptions, setBaseUrlOptions] = useState<SystemConfigDTO[]>([]);

  // ── Runtime
  const [creditCost, setCreditCost] = useState("1");
  const [priority, setPriority] = useState("1");
  const [timeoutMs, setTimeoutMs] = useState("60000");
  const [maxRetries, setMaxRetries] = useState("2");
  const [rateLimit, setRateLimit] = useState("");

  // ── Billing
  const [billingMode, setBillingMode] = useState<BillingMode>("static");
  const [pricingRulesText, setPricingRulesText] = useState(
    '{\n  "baseCredits": 1,\n  "minCredits": 1,\n  "maxCredits": 5\n}'
  );
  const [pricingScript, setPricingScript] = useState("");

  // ── TEXT type
  const [llmProviderId, setLlmProviderId] = useState("");
  const [systemPrompt, setSystemPrompt] = useState("");
  const [responseSchemaText, setResponseSchemaText] = useState("");

  // ── Response modes
  const [supportsBlocking, setSupportsBlocking] = useState(true);
  const [supportsStreaming, setSupportsStreaming] = useState(false);
  const [supportsCallback, setSupportsCallback] = useState(false);
  const [supportsPolling, setSupportsPolling] = useState(false);
  const [callbackConfigText, setCallbackConfigText] = useState("");
  const [pollingConfigText, setPollingConfigText] = useState("");

  // ── Scripts (right panel)
  const [requestBuilderScript, setRequestBuilderScript] = useState("");
  const [responseMapperScript, setResponseMapperScript] = useState("");
  const [customLogicScript, setCustomLogicScript] = useState("");

  // ── Schema (right panel)
  const [inputSchemaText, setInputSchemaText] = useState("[]");
  const [inputGroupsText, setInputGroupsText] = useState("[]");
  const [outputSchemaText, setOutputSchemaText] = useState("[]");

  const typeOption = useMemo(
    () => TYPE_OPTIONS.find((t) => t.value === providerType),
    [providerType]
  );
  const pageTitle = useMemo(() => (isCreate ? "新建 AI 生成模型" : "编辑 AI 生成模型"), [isCreate]);

  const parsedInputParams = useMemo<ParsedParam[]>(() => {
    try {
      const arr = JSON.parse(inputSchemaText);
      if (!Array.isArray(arr)) return [];
      return arr
        .filter((p): p is Record<string, unknown> => !!p && typeof p === "object")
        .map((p) => ({
          name: String(p.name ?? ""),
          type: typeof p.type === "string" ? p.type : undefined,
          label: typeof p.label === "string" ? p.label : undefined,
          description:
            typeof p.description === "string" ? p.description : undefined,
        }))
        .filter((p) => p.name);
    } catch {
      return [];
    }
  }, [inputSchemaText]);

  const parsedOutputParams = useMemo<ParsedParam[]>(() => {
    try {
      const arr = JSON.parse(outputSchemaText);
      if (!Array.isArray(arr)) return [];
      return arr
        .filter((p): p is Record<string, unknown> => !!p && typeof p === "object")
        .map((p) => ({
          name: String(p.name ?? ""),
          type: typeof p.type === "string" ? p.type : undefined,
          label: typeof p.label === "string" ? p.label : undefined,
          description:
            typeof p.description === "string" ? p.description : undefined,
        }))
        .filter((p) => p.name);
    } catch {
      return [];
    }
  }, [outputSchemaText]);

  // ─── Load AI provider config refs ────────────────────────────────────────
  useEffect(() => {
    adminService.getAiProviderConfigs().then((configs) => {
      setApiKeyOptions(configs.filter((c) => c.configKey?.endsWith(".api_key")));
      setBaseUrlOptions(configs.filter((c) => c.configKey?.endsWith(".base_url")));
    }).catch(() => { /* non-critical */ });
  }, []);

  // ─── Lazy-load LLM providers for TEXT type ──────────────────────────────
  useEffect(() => {
    if (providerType !== "TEXT") return;
    if (llmProviderOptions.length > 0) return;
    setLoadingLlmProviders(true);
    aiAdminService
      .getLlmProviderPage({ size: 100, enabled: true })
      .then((page) => setLlmProviderOptions(page.records))
      .catch(() => { /* non-critical */ })
      .finally(() => setLoadingLlmProviders(false));
  }, [providerType, llmProviderOptions.length]);

  // ─── Fallback tab when leaving TEXT type ────────────────────────────────
  useEffect(() => {
    if (
      providerType !== "TEXT" &&
      (rightTab === "systemPrompt" || rightTab === "responseSchema")
    ) {
      setRightTab("requestGroup");
    }
  }, [providerType, rightTab]);

  // 进入"可见性 / 白名单"Tab 时自动拉一次白名单
  useEffect(() => {
    if (rightTab === "whitelist" && !isCreate && id) {
      loadWhitelist();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rightTab, id, isCreate]);

  // ─── Load ────────────────────────────────────────────────────────────────

  useEffect(() => {
    if (isCreate) return;
    const load = async () => {
      try {
        setLoading(true);
        const d = await aiAdminService.getModelProviderById(id);

        setName(d.name || "");
        setDescription(d.description || "");
        setProviderType(d.providerType || "IMAGE");
        setPluginId(d.pluginId || "");
        setPluginType(d.pluginType || "GROOVY");
        setIconUrl(d.iconUrl || "");
        setEnabled(d.enabled ?? true);
        setVisibility((d.visibility as ProviderVisibility) ?? "PUBLIC");

        setBaseUrl(d.baseUrl || "");
        setEndpoint(d.endpoint || "");
        setHttpMethod(d.httpMethod || "POST");
        setAuthType((d.authType as AuthType) || "NONE");
        // Load auth credentials from authConfig
        const ac = (d.authConfig ?? {}) as Record<string, string>;
        if (d.authType === "API_KEY") {
          setAuthApiKeyHeader(ac.headerName || "X-API-Key");
          setAuthApiKey(ac.apiKey || "");
        } else if (d.authType === "BEARER") {
          setAuthBearerToken(ac.token || "");
        } else if (d.authType === "AK_SK") {
          setAuthAccessKey(ac.accessKey || "");
          setAuthSecretKey(ac.secretKey || "");
        }
        setApiKeyRef(d.apiKeyRef || "");
        setBaseUrlRef(d.baseUrlRef || "");

        setCreditCost(String(d.creditCost ?? 1));
        setPriority(String(d.priority ?? 1));
        setTimeoutMs(String(d.timeout ?? 60000));
        setMaxRetries(String(d.maxRetries ?? 2));
        setRateLimit(d.rateLimit ? String(d.rateLimit) : "");

        if (d.pricingScript) {
          setBillingMode("groovy");
          setPricingScript(d.pricingScript);
          setRightTab("pricing");
        } else if (d.pricingRules) {
          setBillingMode("structured");
          setPricingRulesText(JSON.stringify(d.pricingRules, null, 2));
        } else {
          setBillingMode("static");
        }

        setLlmProviderId(d.llmProviderId || "");
        setSystemPrompt(d.systemPrompt || "");
        setResponseSchemaText(
          d.responseSchema ? JSON.stringify(d.responseSchema, null, 2) : ""
        );

        setSupportsBlocking(d.supportsBlocking ?? false);
        setSupportsStreaming(d.supportsStreaming ?? false);
        setSupportsCallback(d.supportsCallback ?? false);
        setSupportsPolling(d.supportsPolling ?? false);
        setCallbackConfigText(d.callbackConfig ? JSON.stringify(d.callbackConfig, null, 2) : "");
        setPollingConfigText(d.pollingConfig ? JSON.stringify(d.pollingConfig, null, 2) : "");

        setRequestBuilderScript(d.requestBuilderScript || "");
        setResponseMapperScript(d.responseMapperScript || "");
        setCustomLogicScript(d.customLogicScript || "");

        setInputSchemaText(JSON.stringify(d.inputSchema || [], null, 2));
        setInputGroupsText(JSON.stringify(d.inputGroups || [], null, 2));
        setOutputSchemaText(JSON.stringify(d.outputSchema || [], null, 2));
      } catch (err) {
        toast.danger(getErrorFromException(err, locale));
      } finally {
        setLoading(false);
      }
    };
    void load();
  }, [id, isCreate, locale]);

  // ─── Auth builder ─────────────────────────────────────────────────────────

  const buildAuthConfig = (): Record<string, unknown> | undefined => {
    switch (authType) {
      case "API_KEY": return { headerName: authApiKeyHeader, apiKey: authApiKey };
      case "BEARER": return { token: authBearerToken };
      case "AK_SK": return { accessKey: authAccessKey, secretKey: authSecretKey };
      default: return undefined;
    }
  };

  // ─── Save ─────────────────────────────────────────────────────────────────

  const handleSave = async () => {
    let parsedPricingRules: Record<string, unknown> | undefined;
    let parsedInputSchema: unknown[] | undefined;
    let parsedInputGroups: unknown[] | undefined;
    let parsedOutputSchema: unknown[] | undefined;
    let parsedResponseSchema: Record<string, unknown> | undefined;
    let parsedCallbackConfig: Record<string, unknown> | undefined;
    let parsedPollingConfig: Record<string, unknown> | undefined;

    try {
      if (billingMode === "structured")
        parsedPricingRules = parseObj(pricingRulesText, "pricingRules");
      parsedInputSchema = parseArr(inputSchemaText, "inputSchema");
      parsedInputGroups = parseArr(inputGroupsText, "inputGroups");
      parsedOutputSchema = parseArr(outputSchemaText, "outputSchema");
      if (responseSchemaText.trim())
        parsedResponseSchema = parseObj(responseSchemaText, "responseSchema");
      if (callbackConfigText.trim())
        parsedCallbackConfig = parseObj(callbackConfigText, "callbackConfig");
      if (pollingConfigText.trim())
        parsedPollingConfig = parseObj(pollingConfigText, "pollingConfig");
    } catch (err) {
      toast.danger(err instanceof Error ? err.message : "JSON 格式校验失败");
      return;
    }

    if (!name.trim()) { toast.danger("请输入模型名称"); return; }
    if (!pluginId.trim()) { toast.danger("请输入插件ID"); return; }

    const payload: SaveModelProviderRequestDTO = {
      name: name.trim(),
      description: description.trim() || undefined,
      providerType,
      pluginId: pluginId.trim(),
      pluginType: pluginType.trim() || undefined,
      iconUrl: iconUrl.trim() || undefined,
      enabled,
      visibility,
      baseUrl: baseUrl.trim() || undefined,
      endpoint: endpoint.trim() || undefined,
      httpMethod: httpMethod || undefined,
      authType: authType !== "NONE" ? authType : undefined,
      authConfig: buildAuthConfig(),
      apiKeyRef: apiKeyRef.trim() || undefined,
      baseUrlRef: baseUrlRef.trim() || undefined,
      llmProviderId: llmProviderId.trim() || undefined,
      systemPrompt: systemPrompt.trim() || undefined,
      responseSchema: parsedResponseSchema,
      creditCost: Number(creditCost || 0),
      priority: priority.trim() ? Number(priority) : undefined,
      timeout: timeoutMs.trim() ? Number(timeoutMs) : undefined,
      maxRetries: maxRetries.trim() ? Number(maxRetries) : undefined,
      rateLimit: rateLimit.trim() ? Number(rateLimit) : undefined,
      pricingRules: billingMode === "structured" ? parsedPricingRules : undefined,
      pricingScript: billingMode === "groovy" ? pricingScript.trim() || undefined : undefined,
      supportsBlocking,
      supportsStreaming,
      supportsCallback,
      supportsPolling,
      callbackConfig: supportsCallback ? parsedCallbackConfig : undefined,
      pollingConfig: supportsPolling ? parsedPollingConfig : undefined,
      requestBuilderScript: requestBuilderScript.trim() || undefined,
      responseMapperScript: responseMapperScript.trim() || undefined,
      customLogicScript: customLogicScript.trim() || undefined,
      inputSchema: parsedInputSchema,
      inputGroups: parsedInputGroups,
      outputSchema: parsedOutputSchema,
    };

    try {
      setSubmitting(true);
      if (isCreate) {
        const created = await aiAdminService.createModelProvider(payload);
        toast.success("创建成功");
        router.replace(`/${locale}/admin/models/ai-models/${created.id}`);
      } else {
        await aiAdminService.updateModelProvider(id, payload);
        toast.success("保存成功");
      }
    } catch (err) {
      toast.danger(getErrorFromException(err, locale));
    } finally {
      setSubmitting(false);
    }
  };

  const handleDeleteConfirm = async () => {
    if (isCreate) return;
    setDeleteModalOpen(false);
    try {
      setSubmitting(true);
      await aiAdminService.deleteModelProvider(id);
      toast.success("删除成功");
      router.replace(`/${locale}/admin/models/ai-models`);
    } catch (err) {
      toast.danger(getErrorFromException(err, locale));
    } finally {
      setSubmitting(false);
    }
  };

  const handleToggleEnabled = async (newEnabled: boolean) => {
    setEnabled(newEnabled);
    if (isCreate) return;
    try {
      if (newEnabled) {
        await aiAdminService.enableModelProvider(id);
      } else {
        await aiAdminService.disableModelProvider(id);
      }
      toast.success(newEnabled ? "已启用" : "已禁用");
    } catch (err) {
      setEnabled(!newEnabled);
      toast.danger(getErrorFromException(err, locale));
    }
  };

  const handleTestConnection = async () => {
    if (isCreate) { toast.danger("请先保存模型后再测试"); return; }
    setTestingConnection(true);
    try {
      const result = await aiAdminService.testModelProviderConnection(id);
      if (result.connected) {
        toast.success(`连通性测试成功${result.latencyMs != null ? `（${result.latencyMs}ms）` : ""}`);
      } else {
        toast.danger(`连通性测试失败${result.message ? `：${result.message}` : ""}`);
      }
    } catch (err) {
      toast.danger(getErrorFromException(err, locale));
    } finally {
      setTestingConnection(false);
    }
  };

  const loadGroovyTemplates = async (templateType?: string) => {
    setLoadingTemplates(true);
    try {
      const page = await aiAdminService.getGroovyTemplatePage({
        pageSize: 100,
        templateType: templateType || undefined,
      });
      setGroovyTemplates(page.records);
    } catch (err) {
      toast.danger(getErrorFromException(err, locale));
    } finally {
      setLoadingTemplates(false);
    }
  };

  const openGroovyPicker = (which: TemplateTarget) => {
    const typeMap: Record<TemplateTarget, string> = {
      request: "REQUEST_BUILDER",
      response: "RESPONSE_MAPPER",
      custom: "CUSTOM_LOGIC",
      pricing: "CUSTOM_LOGIC",
    };
    setActiveTemplateTarget(which);
    setGroovyPickerOpen(true);
    void loadGroovyTemplates(typeMap[which]);
  };

  const applyGroovyTemplate = (tpl: GroovyTemplateDTO) => {
    if (activeTemplateTarget === "request") setRequestBuilderScript(tpl.scriptContent);
    else if (activeTemplateTarget === "response") setResponseMapperScript(tpl.scriptContent);
    else if (activeTemplateTarget === "custom") setCustomLogicScript(tpl.scriptContent);
    else if (activeTemplateTarget === "pricing") setPricingScript(tpl.scriptContent);
    setGroovyPickerOpen(false);
  };

  const onProviderTypeChange = (key: ReactKey | ReactKey[] | null) => {
    if (key && !Array.isArray(key)) setProviderType(String(key) as AiProviderType);
  };
  const onHttpMethodChange = (key: ReactKey | ReactKey[] | null) => {
    if (key && !Array.isArray(key)) setHttpMethod(String(key));
  };
  const onVisibilityChange = (key: ReactKey | ReactKey[] | null) => {
    if (key && !Array.isArray(key)) setVisibility(String(key) as ProviderVisibility);
  };

  // ─── Whitelist handlers ───────────────────────────────────────────────────
  const loadWhitelist = async () => {
    if (isCreate || !id) return;
    setWlLoading(true);
    try {
      const list = await aiAdminService.listProviderWhitelist(id);
      setWhitelistEntries(list ?? []);
    } catch (e) {
      toast.danger(getErrorFromException(e, "加载白名单失败"));
    } finally {
      setWlLoading(false);
    }
  };

  const handleSearchWorkspaces = async () => {
    setWlSearching(true);
    try {
      const page = await workspaceAdminService.searchWorkspaces({
        q: wlSearchQuery.trim() || undefined,
        size: 20,
      });
      setWlSearchResults(page?.records ?? []);
    } catch (e) {
      toast.danger(getErrorFromException(e, "搜索 workspace 失败"));
    } finally {
      setWlSearching(false);
    }
  };

  const handleAddWhitelist = async (workspaceId: string, workspaceName: string) => {
    if (isCreate || !id) {
      toast.danger("请先保存 provider");
      return;
    }
    try {
      await aiAdminService.addProviderWhitelist(id, { workspaceId });
      toast.success(`已加入白名单: ${workspaceName}`);
      await loadWhitelist();
    } catch (e) {
      toast.danger(getErrorFromException(e, "添加白名单失败"));
    }
  };

  const handleRemoveWhitelist = async (workspaceId: string) => {
    if (isCreate || !id) return;
    try {
      await aiAdminService.removeProviderWhitelist(id, workspaceId);
      toast.success("已移除");
      await loadWhitelist();
    } catch (e) {
      toast.danger(getErrorFromException(e, "移除失败"));
    }
  };

  // ─── Loading skeleton ──────────────────────────────────────────────────────

  if (loading) {
    return (
      <div className="flex h-full flex-col">
        <Skeleton className="h-14 w-full shrink-0 rounded-none" />
        <div className="flex flex-1 min-h-0 gap-3 p-3">
          <Skeleton className="h-full w-[38%] rounded-xl" />
          <Skeleton className="h-full flex-1 rounded-xl" />
        </div>
      </div>
    );
  }

  // ─── Code tab config ───────────────────────────────────────────────────────

  const codeTabConfig: Record<
    CodeTabId,
    { value: string; onChange: (v: string) => void; placeholder: string }
  > = {
    request: {
      value: requestBuilderScript,
      onChange: setRequestBuilderScript,
      placeholder:
        "// requestBuilderScript\n// 绑定变量: inputs, config, authConfig\ndef body = [:]\nbody.prompt = inputs.prompt\nif (inputs.negativePrompt) {\n    body.negative_prompt = inputs.negativePrompt\n}\nreturn body",
    },
    response: {
      value: responseMapperScript,
      onChange: setResponseMapperScript,
      placeholder:
        "// responseMapperScript\nif (!response || response.error) {\n    return [outputType: \"MEDIA_SINGLE\", status: \"FAILED\",\n        error: [code: \"API_ERROR\", message: response?.error?.message]]\n}\nreturn [\n    outputType: \"MEDIA_SINGLE\",\n    status: \"SUCCEEDED\",\n    media: [mediaType: \"IMAGE\", items: [[fileUrl: response.url]]]\n]",
    },
    custom: {
      value: customLogicScript,
      onChange: setCustomLogicScript,
      placeholder: "// customLogicScript（可选）\n// 在请求/响应处理外的额外逻辑",
    },
    pricing: {
      value: pricingScript,
      onChange: setPricingScript,
      placeholder:
        "// 动态计费脚本\n// 绑定变量: inputs, baseCredits\n// 返回最终积分数值 (long)\ndef count = inputs.imageUrls?.size() ?: 1\nreturn (long)(baseCredits * count)",
    },
  };

  const renderParamRefStrip = (key: CodeTabId) => {
    type Section = {
      title: string;
      prefix: string;
      params: ParsedParam[];
      extras?: string[];
    };
    const sections: Section[] = [];

    if (key === "request") {
      sections.push({ title: "输入参数", prefix: "inputs", params: parsedInputParams });
      sections.push({
        title: "内置变量",
        prefix: "",
        params: [],
        extras: ["config", "authConfig", "db", "llm"],
      });
    } else if (key === "response") {
      sections.push({
        title: "上游响应",
        prefix: "",
        params: [],
        extras: ["response"],
      });
      sections.push({ title: "输出字段 (参考)", prefix: "", params: parsedOutputParams });
      sections.push({
        title: "内置变量",
        prefix: "",
        params: [],
        extras: ["inputs", "db", "llm"],
      });
    } else if (key === "custom") {
      sections.push({ title: "输入参数", prefix: "inputs", params: parsedInputParams });
      sections.push({
        title: "内置变量",
        prefix: "",
        params: [],
        extras: ["config", "db", "llm"],
      });
    } else if (key === "pricing") {
      sections.push({ title: "输入参数", prefix: "inputs", params: parsedInputParams });
      sections.push({
        title: "内置变量",
        prefix: "",
        params: [],
        extras: ["baseCredits"],
      });
    }

    const totalCount = sections.reduce(
      (n, s) => n + s.params.length + (s.extras?.length ?? 0),
      0
    );
    if (totalCount === 0) return null;

    const handleCopy = async (text: string) => {
      try {
        await navigator.clipboard.writeText(text);
        toast.success(`已复制: ${text}`);
      } catch {
        /* ignore */
      }
    };

    return (
      <div className="shrink-0 border-b border-[#2d2d2d] bg-[#1e1e1e]">
        <button
          type="button"
          onClick={() => setRefStripOpen((v) => !v)}
          className="flex w-full items-center gap-2 px-4 py-1 text-[11px] text-[#858585] transition-colors hover:text-[#ccc]"
        >
          <ChevronRight
            className={`size-3 transition-transform ${refStripOpen ? "rotate-90" : ""}`}
          />
          <span>可用参数</span>
          <span className="font-mono text-[10px] text-[#555]">({totalCount})</span>
          <span className="ml-auto text-[10px] text-[#555]">点击复制</span>
        </button>
        {refStripOpen && (
          <div
            className="max-h-32 space-y-1.5 overflow-y-auto px-4 pb-2"
            style={{ scrollbarWidth: "thin" }}
          >
            {sections.map((sec) => {
              const items: { full: string; type?: string; label?: string }[] = [
                ...sec.params.map((p) => ({
                  full: sec.prefix ? `${sec.prefix}.${p.name}` : p.name,
                  type: p.type,
                  label: p.label,
                })),
                ...(sec.extras ?? []).map((e) => ({
                  full: e,
                  type: undefined,
                  label: undefined,
                })),
              ];
              if (items.length === 0) return null;
              return (
                <div key={sec.title} className="flex flex-wrap items-center gap-1">
                  <span className="text-[10px] text-[#6e6e6e]">{sec.title}:</span>
                  {items.map((it) => (
                    <button
                      key={it.full}
                      type="button"
                      onClick={() => handleCopy(it.full)}
                      title={it.label ?? it.full}
                      className="flex items-center gap-1 rounded border border-[#3a3a3a] bg-[#2d2d2d] px-1.5 py-0.5 font-mono text-[10px] text-[#9cdcfe] transition-colors hover:border-[#569cd6] hover:bg-[#333]"
                    >
                      <span>{it.full}</span>
                      {it.type && (
                        <span className="text-[9px] text-[#6e6e6e]">{it.type}</span>
                      )}
                    </button>
                  ))}
                </div>
              );
            })}
          </div>
        )}
      </div>
    );
  };

  const renderCodeTab = (key: CodeTabId) => {
    const meta = CODE_TAB_META[key];
    const cfg = codeTabConfig[key];
    return (
      <>
        <div className="flex shrink-0 items-center gap-3 border-b border-[#2d2d2d] bg-[#1e1e1e] px-4 py-1">
          <span className="rounded bg-[#2d2d2d] px-1.5 py-0.5 font-mono text-[10px] text-[#9cdcfe]">
            {meta.lang === "groovy" ? "Groovy" : "JSON"}
          </span>
          <span className="text-[11px] text-[#6e6e6e]">{meta.desc}</span>
          {meta.lang === "groovy" && (
            <button
              type="button"
              onClick={() => openGroovyPicker(key)}
              className="ml-auto flex items-center gap-1 rounded px-2 py-0.5 text-[11px] text-[#858585] transition-colors hover:bg-[#2d2d2d] hover:text-[#ccc]"
            >
              <Download className="size-3" />
              从模板导入
            </button>
          )}
        </div>
        {renderParamRefStrip(key)}
        <div className="flex-1 min-h-0 overflow-hidden">
          <CodeEditor
            value={cfg.value}
            onChange={cfg.onChange}
            lang={meta.lang}
            placeholder={cfg.placeholder}
          />
        </div>
      </>
    );
  };

  const renderSystemPromptTab = () => (
    <>
      <div className="flex shrink-0 items-center gap-3 border-b border-[#2d2d2d] bg-[#1e1e1e] px-4 py-1">
        <span className="rounded bg-[#2d2d2d] px-1.5 py-0.5 font-mono text-[10px] text-[#9cdcfe]">
          Markdown
        </span>
        <span className="text-[11px] text-[#6e6e6e]">
          TEXT 类型系统提示词 · 支持 Markdown 渲染预览
        </span>
        <div className="ml-auto flex items-center gap-0.5 rounded-md border border-[#3a3a3a] bg-[#252526] p-0.5">
          <button
            type="button"
            onClick={() => setSystemPromptPreview(false)}
            className={`flex items-center gap-1 rounded px-2 py-0.5 text-[10px] transition-colors ${
              !systemPromptPreview
                ? "bg-[#1e1e1e] text-white"
                : "text-[#858585] hover:text-[#ccc]"
            }`}
          >
            <Pencil className="size-3" />
            编辑
          </button>
          <button
            type="button"
            onClick={() => setSystemPromptPreview(true)}
            className={`flex items-center gap-1 rounded px-2 py-0.5 text-[10px] transition-colors ${
              systemPromptPreview
                ? "bg-[#1e1e1e] text-emerald-400"
                : "text-[#858585] hover:text-[#ccc]"
            }`}
          >
            <Eye className="size-3" />
            预览
          </button>
        </div>
      </div>
      <div className="flex-1 min-h-0 overflow-hidden">
        {systemPromptPreview ? (
          <div
            className="h-full overflow-y-auto bg-[#1e1e1e] p-4"
            style={{ scrollbarWidth: "thin" }}
          >
            {systemPrompt.trim() ? (
              <MarkdownRenderer content={systemPrompt} />
            ) : (
              <p className="text-xs text-[#555]">无内容预览</p>
            )}
          </div>
        ) : (
          <CodeEditor
            value={systemPrompt}
            onChange={setSystemPrompt}
            lang="markdown"
            placeholder="你是一个专业的助手…"
          />
        )}
      </div>
    </>
  );

  const renderResponseSchemaTab = () => (
    <>
      <div className="flex shrink-0 items-center gap-3 border-b border-[#2d2d2d] bg-[#1e1e1e] px-4 py-1">
        <span className="rounded bg-[#2d2d2d] px-1.5 py-0.5 font-mono text-[10px] text-[#9cdcfe]">
          JSON
        </span>
        <span className="text-[11px] text-[#6e6e6e]">
          结构化输出 Schema — 用于 llm.chatStructured() 返回格式约束
        </span>
      </div>
      <div className="flex-1 min-h-0 overflow-hidden">
        <CodeEditor
          value={responseSchemaText}
          onChange={setResponseSchemaText}
          lang="json"
          placeholder={'{"type":"object","properties":{}}'}
        />
      </div>
    </>
  );

  // ─── Whitelist tab ────────────────────────────────────────────────────────

  const renderWhitelistTab = () => (
    <div className="flex h-full flex-col overflow-y-auto bg-[#1e1e1e] p-4 text-xs">
      {isCreate ? (
        <div className="rounded-lg border border-dashed border-[#3a3a3a] p-6 text-center text-[#858585]">
          请先保存 provider 后再管理白名单
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          {/* 头部说明 */}
          <div className="flex items-center gap-2 rounded-lg border border-[#3a3a3a] bg-[#252526] px-3 py-2 text-[11px] text-[#858585]">
            <Lock className="size-3.5 shrink-0 text-amber-400" />
            <span>
              当 visibility = <span className="font-mono text-amber-400">WHITELIST</span> 时,只有名单内的 workspace 能看到本 provider。
              当前 visibility:
              <span className="ml-1 font-mono font-medium text-foreground">{visibility}</span>
            </span>
          </div>

          {/* 添加区 */}
          <div className="rounded-lg border border-[#3a3a3a] bg-[#252526] p-3">
            <Label className="mb-2 block text-[11px] font-medium text-[#cccccc]">添加 workspace</Label>
            <div className="flex gap-2">
              <Input
                value={wlSearchQuery}
                onChange={(e) => setWlSearchQuery(e.target.value)}
                placeholder="按 名称 / slug / UUID 搜索 workspace"
                className="flex-1"
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    e.preventDefault();
                    handleSearchWorkspaces();
                  }
                }}
              />
              <Button onPress={handleSearchWorkspaces} size="sm" isPending={wlSearching}>
                {({ isPending }) => (isPending ? <Spinner color="current" size="sm" /> : "搜索")}
              </Button>
            </div>
            {wlSearchResults.length > 0 && (
              <div className="mt-2 max-h-48 overflow-y-auto rounded border border-[#3a3a3a]">
                {wlSearchResults.map((ws) => {
                  const already = whitelistEntries.some((e) => e.workspaceId === ws.id);
                  return (
                    <div
                      key={ws.id}
                      className="flex items-center gap-2 border-b border-[#3a3a3a] px-2 py-1.5 text-[11px] last:border-b-0"
                    >
                      <div className="min-w-0 flex-1">
                        <div className="truncate font-medium text-[#cccccc]">{ws.name}</div>
                        <div className="truncate font-mono text-[10px] text-[#858585]">
                          {ws.slug}
                          {ws.isInternal && (
                            <span className="ml-1 rounded bg-purple-500/20 px-1 py-0.5 text-purple-300">
                              internal
                            </span>
                          )}
                        </div>
                      </div>
                      {already ? (
                        <Chip size="sm" color="success">
                          已加入
                        </Chip>
                      ) : (
                        <Button
                          size="sm"
                          variant="secondary"
                          onPress={() => handleAddWhitelist(ws.id, ws.name)}
                        >
                          + 添加
                        </Button>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
            {wlSearching === false && wlSearchResults.length === 0 && wlSearchQuery && (
              <p className="mt-2 text-[10px] text-[#858585]">无匹配 workspace</p>
            )}
          </div>

          {/* 白名单列表 */}
          <div>
            <div className="mb-2 flex items-center justify-between">
              <span className="text-[11px] font-medium text-[#cccccc]">
                当前白名单 ({whitelistEntries.length})
              </span>
              <Button size="sm" variant="tertiary" onPress={loadWhitelist} isPending={wlLoading}>
                {({ isPending }) => (isPending ? <Spinner color="current" size="sm" /> : "刷新")}
              </Button>
            </div>
            {whitelistEntries.length === 0 ? (
              <div className="rounded-lg border border-dashed border-[#3a3a3a] p-6 text-center text-[#858585]">
                暂无白名单条目
              </div>
            ) : (
              <div className="overflow-x-auto rounded-lg border border-[#3a3a3a]">
                <table className="w-full text-[11px]">
                  <thead className="bg-[#252526]">
                    <tr>
                      <th className="px-3 py-2 text-left font-medium text-[#cccccc]">Workspace ID</th>
                      <th className="px-3 py-2 text-left font-medium text-[#cccccc]">备注</th>
                      <th className="px-3 py-2 text-right font-medium text-[#cccccc]">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {whitelistEntries.map((entry) => (
                      <tr key={entry.id} className="border-t border-[#3a3a3a]">
                        <td className="px-3 py-2 font-mono text-[#cccccc]">{entry.workspaceId}</td>
                        <td className="px-3 py-2 text-[#858585]">{entry.note || "-"}</td>
                        <td className="px-3 py-2 text-right">
                          <Button
                            size="sm"
                            variant="tertiary"
                            onPress={() => handleRemoveWhitelist(entry.workspaceId)}
                          >
                            <Trash2 className="size-3" />
                          </Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );

  // ─── Panel contents ────────────────────────────────────────────────────────

  const leftPanel = (
    <div className="flex h-full flex-col overflow-hidden rounded-xl border border-border bg-background">
      <div className="flex-1 overflow-y-auto p-4 space-y-4" style={{ scrollbarWidth: "thin" }}>

        {/* ── 基本信息 ── */}
        <section>
          <SectionLabel
            icon={Settings2}
            title="基本信息"
            iconCls="bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-400"
          />
          <div className="space-y-2.5">
            <TextField isRequired>
              <Label className="text-xs">名称</Label>
              <Input
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="Stable Diffusion XL"
              />
            </TextField>

            <div className="grid grid-cols-2 gap-2">
              <Select value={providerType} onChange={onProviderTypeChange}>
                <Label className="text-xs">类型</Label>
                <Select.Trigger>
                  <Select.Value>
                    {() => (
                      <span>{TYPE_OPTIONS.find((t) => t.value === providerType)?.label ?? providerType}</span>
                    )}
                  </Select.Value>
                  <Select.Indicator />
                </Select.Trigger>
                <Select.Popover>
                  <ListBox>
                    {TYPE_OPTIONS.map((opt) => (
                      <ListBox.Item key={opt.value} id={opt.value} textValue={opt.label}>
                        {opt.label}
                        <ListBox.ItemIndicator />
                      </ListBox.Item>
                    ))}
                  </ListBox>
                </Select.Popover>
              </Select>

              <TextField isRequired>
                <Label className="text-xs">插件 ID</Label>
                <Input
                  value={pluginId}
                  onChange={(e) => setPluginId(e.target.value)}
                  placeholder="stable-diffusion-xl"
                  className="font-mono text-xs"
                />
              </TextField>
            </div>

            <div className="grid grid-cols-3 gap-2">
              <TextField className="col-span-2">
                <Label className="text-xs">图标 URL</Label>
                <Input
                  value={iconUrl}
                  onChange={(e) => setIconUrl(e.target.value)}
                  placeholder="https://..."
                />
              </TextField>
              <TextField>
                <Label className="text-xs">插件类型</Label>
                <Input
                  value={pluginType}
                  onChange={(e) => setPluginType(e.target.value)}
                  placeholder="GROOVY"
                />
              </TextField>
            </div>

            <TextField>
              <Label className="text-xs">描述</Label>
              <TextArea
                rows={2}
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="模型功能说明…"
              />
            </TextField>

            <div className="flex items-center justify-between rounded-lg border border-border bg-surface/40 px-3 py-2">
              <div>
                <p className="text-xs font-medium">启用状态</p>
                <p className="text-[10px] text-muted">在生产环境提供服务</p>
              </div>
              <Switch isSelected={enabled} onChange={handleToggleEnabled} size="sm">
                <Switch.Control>
                  <Switch.Thumb />
                </Switch.Control>
              </Switch>
            </div>

            <Select selectedKey={visibility} onSelectionChange={onVisibilityChange}>
              <Label className="text-xs">可见性</Label>
              <Select.Trigger>
                <Select.Value>
                  {() => (
                    <span className="text-xs font-medium">
                      {VISIBILITY_OPTIONS.find((v) => v.id === visibility)?.label ?? visibility}
                    </span>
                  )}
                </Select.Value>
                <Select.Indicator />
              </Select.Trigger>
              <Select.Popover>
                <ListBox>
                  {VISIBILITY_OPTIONS.map((v) => (
                    <ListBox.Item key={v.id} id={v.id} textValue={v.label}>
                      <div className="flex flex-col gap-0.5">
                        <span className="text-xs font-medium">{v.label}</span>
                        <span className="text-[10px] text-muted">{v.desc}</span>
                      </div>
                      <ListBox.ItemIndicator />
                    </ListBox.Item>
                  ))}
                </ListBox>
              </Select.Popover>
            </Select>
            {visibility === "WHITELIST" && (
              <p className="text-[10px] text-muted">
                白名单为空时无人可见,请到右侧「可见性 / 白名单」Tab 添加 workspace
              </p>
            )}
          </div>
        </section>

        <Divider />

        {/* ── HTTP & 认证 ── */}
        <section>
          <SectionLabel
            icon={Globe}
            title="HTTP & 认证"
            iconCls="bg-violet-50 text-violet-600 dark:bg-violet-950/40 dark:text-violet-400"
          />
          <div className="space-y-2.5">
            <p className="text-[10px] text-muted">引用优先。设置引用后，直接值仅作为回退。</p>
            <Select value={baseUrlRef || "__none__"} onChange={(k) => !Array.isArray(k) && setBaseUrlRef(k ? String(k) : "")}>
              <Label className="text-xs">Base URL 引用</Label>
              <Select.Trigger>
                <Select.Value>{() => <span className="font-mono text-xs">{baseUrlRef || "— 不使用引用 —"}</span>}</Select.Value>
                <Select.Indicator />
              </Select.Trigger>
              <Select.Popover>
                <ListBox>
                  <ListBox.Item key="__none__" id="__none__" textValue="不使用引用">
                    — 不使用引用 —<ListBox.ItemIndicator />
                  </ListBox.Item>
                  {baseUrlOptions.map((c) => (
                    <ListBox.Item key={c.configKey} id={c.configKey} textValue={c.configKey ?? ""}>
                      <span className="font-mono text-xs">{c.configKey}</span>
                      <ListBox.ItemIndicator />
                    </ListBox.Item>
                  ))}
                </ListBox>
              </Select.Popover>
            </Select>
            <TextField>
              <Label className="text-xs">API 基础 URL（直接值，回退用）</Label>
              <Input
                value={baseUrl}
                onChange={(e) => setBaseUrl(e.target.value)}
                placeholder="https://api.example.com"
              />
            </TextField>

            <div className="grid grid-cols-3 gap-2">
              <TextField className="col-span-2">
                <Label className="text-xs">端点路径</Label>
                <Input
                  value={endpoint}
                  onChange={(e) => setEndpoint(e.target.value)}
                  placeholder="/v1/generation"
                  className="font-mono text-xs"
                />
              </TextField>
              <Select value={httpMethod} onChange={onHttpMethodChange}>
                <Label className="text-xs">方法</Label>
                <Select.Trigger>
                  <Select.Value>
                    {() => <span className="font-mono text-xs">{httpMethod}</span>}
                  </Select.Value>
                  <Select.Indicator />
                </Select.Trigger>
                <Select.Popover>
                  <ListBox>
                    {HTTP_METHODS.map((m) => (
                      <ListBox.Item key={m} id={m} textValue={m}>
                        <span className="font-mono">{m}</span>
                        <ListBox.ItemIndicator />
                      </ListBox.Item>
                    ))}
                  </ListBox>
                </Select.Popover>
              </Select>
            </div>

            {/* Auth type selector */}
            <div>
              <Label className="mb-1.5 block text-xs">认证类型</Label>
              <div className="grid grid-cols-2 gap-1.5">
                {AUTH_TYPES.map((opt) => (
                  <SelectableCard
                    key={opt.value}
                    mode="radio"
                    active={authType === opt.value}
                    onPress={() => setAuthType(opt.value)}
                    icon={opt.Icon}
                    label={opt.label}
                    desc={opt.desc}
                  />
                ))}
              </div>
            </div>

            {/* Credentials */}
            {authType !== "NONE" && (
              <div className="space-y-2 rounded-lg border border-border bg-surface/50 p-3">
                <p className="flex items-center gap-1 text-[10px] font-semibold uppercase tracking-wider text-muted">
                  <Lock className="size-3" /> 凭据
                </p>
                <p className="text-[10px] text-muted">引用优先。设置引用后，直接值仅作为回退。</p>
                <Select value={apiKeyRef || "__none__"} onChange={(k) => !Array.isArray(k) && setApiKeyRef(k ? String(k) : "")}>
                  <Label className="text-xs">API Key 引用</Label>
                  <Select.Trigger>
                    <Select.Value>{() => <span className="font-mono text-xs">{apiKeyRef || "— 不使用引用 —"}</span>}</Select.Value>
                    <Select.Indicator />
                  </Select.Trigger>
                  <Select.Popover>
                    <ListBox>
                      <ListBox.Item key="__none__" id="__none__" textValue="不使用引用">
                        — 不使用引用 —<ListBox.ItemIndicator />
                      </ListBox.Item>
                      {apiKeyOptions.map((c) => (
                        <ListBox.Item key={c.configKey} id={c.configKey} textValue={c.configKey ?? ""}>
                          <span className="font-mono text-xs">{c.configKey}</span>
                          <ListBox.ItemIndicator />
                        </ListBox.Item>
                      ))}
                    </ListBox>
                  </Select.Popover>
                </Select>
                {authType === "API_KEY" && (
                  <div className="grid grid-cols-2 gap-2">
                    <TextField>
                      <Label className="text-xs">Header 名</Label>
                      <Input
                        value={authApiKeyHeader}
                        onChange={(e) => setAuthApiKeyHeader(e.target.value)}
                        placeholder="X-API-Key"
                        className="font-mono text-xs"
                      />
                    </TextField>
                    <TextField>
                      <Label className="text-xs">API Key（直接值，回退用）</Label>
                      <Input
                        type="password"
                        value={authApiKey}
                        onChange={(e) => setAuthApiKey(e.target.value)}
                        className="font-mono text-xs"
                      />
                    </TextField>
                  </div>
                )}
                {authType === "BEARER" && (
                  <TextField>
                    <Label className="text-xs">Bearer Token</Label>
                    <Input
                      type="password"
                      value={authBearerToken}
                      onChange={(e) => setAuthBearerToken(e.target.value)}
                      className="font-mono text-xs"
                    />
                  </TextField>
                )}
                {authType === "AK_SK" && (
                  <div className="grid grid-cols-2 gap-2">
                    <TextField>
                      <Label className="text-xs">Access Key</Label>
                      <Input
                        value={authAccessKey}
                        onChange={(e) => setAuthAccessKey(e.target.value)}
                        className="font-mono text-xs"
                      />
                    </TextField>
                    <TextField>
                      <Label className="text-xs">Secret Key</Label>
                      <Input
                        type="password"
                        value={authSecretKey}
                        onChange={(e) => setAuthSecretKey(e.target.value)}
                        className="font-mono text-xs"
                      />
                    </TextField>
                  </div>
                )}
              </div>
            )}
          </div>
        </section>

        <Divider />

        {/* ── 运行参数 ── */}
        <section>
          <SectionLabel
            icon={Timer}
            title="运行参数"
            iconCls="bg-orange-50 text-orange-600 dark:bg-orange-950/40 dark:text-orange-400"
          />
          <div className="grid grid-cols-3 gap-2">
            <TextField>
              <Label className="text-xs">积分 (兜底)</Label>
              <Input
                type="number"
                value={creditCost}
                onChange={(e) => setCreditCost(e.target.value)}
                placeholder="1"
              />
            </TextField>
            <TextField>
              <Label className="text-xs">优先级</Label>
              <Input
                type="number"
                value={priority}
                onChange={(e) => setPriority(e.target.value)}
                placeholder="1"
              />
            </TextField>
            <TextField>
              <Label className="text-xs">超时 (ms)</Label>
              <Input
                type="number"
                value={timeoutMs}
                onChange={(e) => setTimeoutMs(e.target.value)}
                placeholder="60000"
              />
            </TextField>
            <TextField>
              <Label className="text-xs">最大重试</Label>
              <Input
                type="number"
                value={maxRetries}
                onChange={(e) => setMaxRetries(e.target.value)}
                placeholder="2"
              />
            </TextField>
            <TextField className="col-span-2">
              <Label className="text-xs">速率限制 (次/分)</Label>
              <Input
                type="number"
                value={rateLimit}
                onChange={(e) => setRateLimit(e.target.value)}
                placeholder="不限"
              />
            </TextField>
          </div>
        </section>

        <Divider />

        {/* ── 动态计费 ── */}
        <section>
          <SectionLabel
            icon={CreditCard}
            title="动态计费"
            iconCls="bg-amber-50 text-amber-600 dark:bg-amber-950/40 dark:text-amber-400"
          />
          <div className="space-y-2.5">
            <p className="text-[10px] text-muted">
              优先级：<span className="font-mono text-amber-600 dark:text-amber-400">Groovy脚本</span>
              {" › "}结构化规则{" › "}静态积分（兜底）
            </p>
            <div className="grid grid-cols-3 gap-1.5">
              {BILLING_MODES.map(({ mode, label, desc, Icon }) => (
                <SelectableCard
                  key={mode}
                  mode="radio"
                  active={billingMode === mode}
                  onPress={() => {
                    setBillingMode(mode);
                    if (mode === "groovy") setRightTab("pricing");
                  }}
                  icon={Icon}
                  label={label}
                  desc={desc}
                />
              ))}
            </div>

            {billingMode === "structured" && (
              <div className="space-y-1">
                <p className="text-[10px] text-muted">
                  pricingRules — <span className="font-mono">baseCredits, minCredits, maxCredits</span>
                </p>
                <div className="h-36 overflow-hidden rounded-lg border border-border">
                  <CodeEditor
                    value={pricingRulesText}
                    onChange={setPricingRulesText}
                    lang="json"
                    placeholder='{"baseCredits": 1, "minCredits": 1, "maxCredits": 5}'
                  />
                </div>
              </div>
            )}

            {billingMode === "groovy" && (
              <button
                type="button"
                onClick={() => setRightTab("pricing")}
                className="flex w-full items-center gap-2 rounded-lg border border-dashed border-amber-300 bg-amber-50 px-3 py-2 text-left text-xs text-amber-700 transition-colors hover:bg-amber-100 dark:border-amber-800/50 dark:bg-amber-950/20 dark:text-amber-400 dark:hover:bg-amber-950/30"
              >
                <Coins className="size-3.5 shrink-0" />
                <span className="flex-1">计费脚本已在右侧编辑器 — 点击查看</span>
                <ChevronRight className="size-3 shrink-0" />
              </button>
            )}
          </div>
        </section>

        {/* ── LLM 绑定 (TEXT only) ── */}
        {providerType === "TEXT" && (
          <>
            <Divider />
            <section>
              <SectionLabel
                icon={MessageSquareText}
                title="LLM 绑定"
                iconCls="bg-teal-50 text-teal-600 dark:bg-teal-950/40 dark:text-teal-400"
              />
              <div className="space-y-2.5">
                <Select
                  value={llmProviderId || "__none__"}
                  onChange={(k) => {
                    if (Array.isArray(k)) return;
                    const v = k ? String(k) : "";
                    setLlmProviderId(v === "__none__" ? "" : v);
                  }}
                >
                  <Label className="text-xs">关联 LLM Provider</Label>
                  <Select.Trigger>
                    <Select.Value>
                      {() => {
                        const sel = llmProviderOptions.find((p) => p.id === llmProviderId);
                        return sel ? (
                          <div className="flex items-center gap-2">
                            <Chip size="sm" variant="secondary">{sel.provider}</Chip>
                            <span className="font-mono text-xs">
                              {sel.modelName || sel.modelId}
                            </span>
                          </div>
                        ) : (
                          <span className="text-muted">
                            {loadingLlmProviders ? "加载中…" : "— 选择 LLM Provider —"}
                          </span>
                        );
                      }}
                    </Select.Value>
                    <Select.Indicator />
                  </Select.Trigger>
                  <Select.Popover>
                    <ListBox>
                      <ListBox.Item key="__none__" id="__none__" textValue="不设置">
                        — 不设置 —<ListBox.ItemIndicator />
                      </ListBox.Item>
                      {llmProviderOptions.map((p) => (
                        <ListBox.Item
                          key={p.id}
                          id={p.id}
                          textValue={`${p.provider} ${p.modelName || p.modelId}`}
                        >
                          <div className="flex min-w-0 flex-col">
                            <span className="text-xs font-medium">
                              {p.modelName || p.modelId}
                            </span>
                            <span className="font-mono text-[10px] text-muted">
                              {p.provider} · {p.id}
                            </span>
                          </div>
                          <ListBox.ItemIndicator />
                        </ListBox.Item>
                      ))}
                    </ListBox>
                  </Select.Popover>
                </Select>
                <div className="rounded-lg border border-blue-200 bg-blue-50 px-2.5 py-1.5 text-[10px] text-blue-700 dark:border-blue-900/40 dark:bg-blue-950/20 dark:text-blue-300">
                  TEXT 类型仅支持 BLOCKING。系统提示词与结构化输出 Schema 请在右侧面板编辑。
                </div>
              </div>
            </section>
          </>
        )}

        <Divider />

        {/* ── 响应模式 ── */}
        <section>
          <SectionLabel
            icon={Layers}
            title="响应模式"
            iconCls="bg-green-50 text-green-600 dark:bg-green-950/40 dark:text-green-400"
          />
          <div className="space-y-2.5">
            <div className="grid grid-cols-2 gap-1.5">
              {(
                [
                  { key: "blocking", label: "Blocking", desc: "同步等待结果", Icon: Zap, val: supportsBlocking, onChange: (v: boolean) => setSupportsBlocking(v) },
                  { key: "streaming", label: "Streaming", desc: "流式推送结果", Icon: ArrowRightLeft, val: supportsStreaming, onChange: (v: boolean) => setSupportsStreaming(v) },
                  { key: "callback", label: "Callback", desc: "异步回调通知", Icon: Wifi, val: supportsCallback, onChange: (v: boolean) => setSupportsCallback(v) },
                  { key: "polling", label: "Polling", desc: "轮询查询状态", Icon: Timer, val: supportsPolling, onChange: (v: boolean) => setSupportsPolling(v) },
                ] satisfies { key: string; label: string; desc: string; Icon: React.ComponentType<{ className?: string }>; val: boolean; onChange: (v: boolean) => void }[]
              ).map(({ key, label, desc, Icon, val, onChange }) => (
                <SelectableCard
                  key={key}
                  mode="toggle"
                  active={val}
                  toggleValue={val}
                  onToggle={onChange}
                  icon={Icon}
                  label={label}
                  desc={desc}
                />
              ))}
            </div>

            {supportsCallback && (
              <div className="space-y-1">
                <p className="text-[10px] text-muted">
                  回调配置 (JSON) — <span className="font-mono">callbackPath, secretHeader</span>
                </p>
                <div className="h-28 overflow-hidden rounded-lg border border-border">
                  <CodeEditor
                    value={callbackConfigText}
                    onChange={setCallbackConfigText}
                    lang="json"
                    placeholder='{"callbackPath": "/callback", "secretHeader": "X-Secret"}'
                  />
                </div>
              </div>
            )}

            {supportsPolling && (
              <div className="space-y-1">
                <p className="text-[10px] text-muted">
                  轮询配置 (JSON) — <span className="font-mono">pollingPath, statusPath, successStatus</span>
                </p>
                <div className="h-36 overflow-hidden rounded-lg border border-border">
                  <CodeEditor
                    value={pollingConfigText}
                    onChange={setPollingConfigText}
                    lang="json"
                    placeholder='{"pollingPath": "/status/{taskId}", "statusPath": "$.status", "successStatus": "succeeded"}'
                  />
                </div>
              </div>
            )}
          </div>
        </section>

        <div className="h-2" />
      </div>
    </div>
  );

  const showSubTabs = rightTab === "requestGroup" || rightTab === "responseGroup";
  const activeSubTab = rightTab === "requestGroup" ? requestSubTab : responseSubTab;
  const setActiveSubTab = (v: "script" | "schema") => {
    if (rightTab === "requestGroup") setRequestSubTab(v as RequestSubTab);
    else if (rightTab === "responseGroup") setResponseSubTab(v as ResponseSubTab);
  };

  const rightPanel = (
    <div className="flex h-full flex-col overflow-hidden rounded-xl bg-[#1e1e1e] dark:bg-[#161616]">

      {/* Main tab bar */}
      <div className="flex shrink-0 items-end gap-0 border-b border-[#3a3a3a] bg-[#252526] px-2 pt-1.5">

        {/* Main tabs */}
        <div className="flex items-end gap-0.5 pr-2">
          {MAIN_TABS.map((tab) => {
            const isActive = rightTab === tab.id;
            return (
              <button
                key={tab.id}
                type="button"
                onClick={() => setRightTab(tab.id)}
                className={`flex items-center gap-1.5 rounded-t-md border-x border-t px-3 py-1.5 text-[11px] font-medium transition-all ${
                  isActive
                    ? "border-[#3a3a3a] bg-[#1e1e1e] text-white"
                    : "border-transparent text-[#858585] hover:text-[#ccc]"
                }`}
              >
                <tab.Icon className="size-3" />
                {tab.label}
              </button>
            );
          })}
        </div>

        {/* Conditional: pricing tab */}
        {billingMode === "groovy" && (
          <>
            <div className="mb-1.5 h-3.5 w-px bg-[#3a3a3a]" />
            <div className="px-2">
              <button
                type="button"
                onClick={() => setRightTab("pricing")}
                className={`flex items-center gap-1.5 rounded-t-md border-x border-t px-3 py-1.5 text-[11px] font-medium transition-all ${
                  rightTab === "pricing"
                    ? "border-[#3a3a3a] bg-[#1e1e1e] text-amber-400"
                    : "border-transparent text-[#858585] hover:text-amber-400"
                }`}
              >
                <Coins className="size-3" />
                计费脚本
              </button>
            </div>
          </>
        )}

        {/* Conditional: TEXT tabs (system prompt + response schema) */}
        {providerType === "TEXT" && (
          <>
            <div className="mb-1.5 h-3.5 w-px bg-[#3a3a3a]" />
            <div className="flex items-end gap-0.5 px-2">
              <button
                type="button"
                onClick={() => setRightTab("systemPrompt")}
                className={`flex items-center gap-1.5 rounded-t-md border-x border-t px-3 py-1.5 text-[11px] font-medium transition-all ${
                  rightTab === "systemPrompt"
                    ? "border-[#3a3a3a] bg-[#1e1e1e] text-teal-400"
                    : "border-transparent text-[#858585] hover:text-teal-400"
                }`}
              >
                <MessageSquareText className="size-3" />
                系统提示词
              </button>
              <button
                type="button"
                onClick={() => setRightTab("responseSchema")}
                className={`flex items-center gap-1.5 rounded-t-md border-x border-t px-3 py-1.5 text-[11px] font-medium transition-all ${
                  rightTab === "responseSchema"
                    ? "border-[#3a3a3a] bg-[#1e1e1e] text-teal-400"
                    : "border-transparent text-[#858585] hover:text-teal-400"
                }`}
              >
                <Braces className="size-3" />
                输出 Schema
              </button>
            </div>
          </>
        )}
      </div>

      {/* Sub-tab bar (only for requestGroup/responseGroup) */}
      {showSubTabs && (
        <div className="flex shrink-0 items-center gap-1 border-b border-[#2d2d2d] bg-[#1e1e1e] px-3 py-1.5">
          {(
            [
              {
                id: "script" as const,
                label: rightTab === "requestGroup" ? "请求构建 Script" : "响应映射 Script",
                Icon: Zap,
              },
              {
                id: "schema" as const,
                label: rightTab === "requestGroup" ? "输入 Schema" : "输出 Schema",
                Icon: FileJson,
              },
            ]
          ).map((st) => {
            const active = activeSubTab === st.id;
            return (
              <button
                key={st.id}
                type="button"
                onClick={() => setActiveSubTab(st.id)}
                className={`flex items-center gap-1.5 rounded px-2.5 py-1 text-[11px] font-medium transition-colors ${
                  active
                    ? "bg-[#2d2d2d] text-emerald-400"
                    : "text-[#858585] hover:bg-[#2a2a2a] hover:text-[#ccc]"
                }`}
              >
                <st.Icon className="size-3" />
                {st.label}
              </button>
            );
          })}
        </div>
      )}

      {/* Content area */}
      <div className="flex flex-1 min-h-0 flex-col">
        {rightTab === "requestGroup" && requestSubTab === "script" && renderCodeTab("request")}
        {rightTab === "requestGroup" && requestSubTab === "schema" && (
          <SchemaVisualEditor
            value={inputSchemaText}
            onChange={setInputSchemaText}
            groupsValue={inputGroupsText}
            onGroupsChange={setInputGroupsText}
          />
        )}
        {rightTab === "responseGroup" && responseSubTab === "script" && renderCodeTab("response")}
        {rightTab === "responseGroup" && responseSubTab === "schema" && (
          <SchemaVisualEditor value={outputSchemaText} onChange={setOutputSchemaText} />
        )}
        {rightTab === "custom" && renderCodeTab("custom")}
        {rightTab === "pricing" && renderCodeTab("pricing")}
        {rightTab === "systemPrompt" && renderSystemPromptTab()}
        {rightTab === "responseSchema" && renderResponseSchemaTab()}
        {rightTab === "whitelist" && renderWhitelistTab()}
      </div>
    </div>
  );

  // ─── Render ───────────────────────────────────────────────────────────────

  return (
    <div className="flex h-full flex-col">

      {/* ── Top bar ── */}
      <div className="flex shrink-0 items-center gap-2">
        <Button
          variant="tertiary"
          size="sm"
          onPress={() => router.push(`/${locale}/admin/models/ai-models`)}
          className="shrink-0 gap-1"
        >
          <ArrowLeft className="size-3.5" />
          返回
        </Button>

        <div className="mx-1 h-4 w-px bg-border shrink-0" />

        {/* Icon + name */}
        <div className="flex min-w-0 flex-1 items-center gap-2">
          {iconUrl ? (
            <img
              src={iconUrl}
              alt="icon"
              className="size-7 shrink-0 rounded-md border border-border object-contain p-0.5"
              onError={(e) => { (e.target as HTMLImageElement).style.display = "none"; }}
            />
          ) : (
            <div className="flex size-7 shrink-0 items-center justify-center rounded-md bg-accent/10">
              <Bot className="size-3.5 text-accent" />
            </div>
          )}
          <h1 className="truncate text-sm font-semibold text-foreground">
            {name || pageTitle}
          </h1>
          {typeOption && (
            <span className={`shrink-0 rounded-md px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide ${typeOption.badge}`}>
              {providerType}
            </span>
          )}
        </div>

        {/* Actions */}
        <div className="flex shrink-0 items-center gap-2">
          <Switch isSelected={enabled} onChange={handleToggleEnabled} size="sm">
            <Switch.Control>
              <Switch.Thumb />
            </Switch.Control>
            <span className={`text-xs font-medium ${enabled ? "text-green-600 dark:text-green-400" : "text-muted"}`}>
              {enabled ? "启用" : "禁用"}
            </span>
          </Switch>
          <div className="h-4 w-px bg-border" />
          {!isCreate && (
            <>
              <Button
                variant="secondary"
                size="sm"
                isPending={testingConnection}
                onPress={handleTestConnection}
                className="gap-1"
              >
                {({isPending}) => (<>{isPending ? <Spinner color="current" size="sm" /> : <Wifi className="size-3.5" />}连通性测试</>)}
              </Button>
              <Button
                variant="secondary"
                size="sm"
                onPress={() => toast.success("功能测试功能即将推出")}
                className="gap-1"
              >
                <FlaskConical className="size-3.5" />
                功能测试
              </Button>
            </>
          )}
          {!isCreate && (
            <Button
              variant="danger"
              size="sm"
              isDisabled={submitting}
              onPress={() => setDeleteModalOpen(true)}
              className="gap-1"
            >
              <Trash2 className="size-3.5" />
              删除
            </Button>
          )}
          <Button
            variant="primary"
            size="sm"
            isPending={submitting}
            onPress={handleSave}
            className="gap-1"
          >
            {({isPending}) => (<>{isPending ? <Spinner color="current" size="sm" /> : <Save className="size-3.5" />}保存</>)}
          </Button>
        </div>
      </div>

      {/* ── Resizable split panels ── */}
      <div className="flex-1 min-h-0">
        <ResizablePanels
          defaultLeftWidth={38}
          minLeftWidth={25}
          maxLeftWidth={60}
          leftPanel={leftPanel}
          rightPanel={rightPanel}
        />
      </div>

      {/* ── Delete modal ── */}
      <Modal.Backdrop
        isOpen={deleteModalOpen}
        onOpenChange={(open) => !open && setDeleteModalOpen(false)}
      >
        <Modal.Container size="sm">
          <Modal.Dialog>
            <Modal.CloseTrigger />
            <Modal.Header>
              <Modal.Heading>确认删除</Modal.Heading>
            </Modal.Header>
            <Modal.Body>
              <p className="text-sm text-muted">
                确认删除「<span className="font-medium text-foreground">{name}</span>」吗？此操作不可撤销。
              </p>
            </Modal.Body>
            <Modal.Footer>
              <Button variant="secondary" slot="close">取消</Button>
              <Button variant="danger" onPress={handleDeleteConfirm} isPending={submitting}>
                {({isPending}) => (<>{isPending ? <Spinner color="current" size="sm" /> : null}删除</>)}
              </Button>
            </Modal.Footer>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>

      {/* ── Groovy Template Picker modal ── */}
      <Modal.Backdrop
        isOpen={groovyPickerOpen}
        onOpenChange={(open) => !open && setGroovyPickerOpen(false)}
      >
        <Modal.Container size="md">
          <Modal.Dialog>
            <Modal.CloseTrigger />
            <Modal.Header>
              <Modal.Heading>从 Groovy 模板导入</Modal.Heading>
            </Modal.Header>
            <Modal.Body>
              {loadingTemplates ? (
                <div className="flex items-center justify-center py-10">
                  <div className="size-6 animate-spin rounded-full border-2 border-accent border-t-transparent" />
                </div>
              ) : groovyTemplates.length === 0 ? (
                <p className="py-8 text-center text-sm text-muted">暂无可用模板</p>
              ) : (
                <div className="space-y-1.5">
                  {groovyTemplates.map((tpl) => (
                    <button
                      key={tpl.id}
                      type="button"
                      onClick={() => applyGroovyTemplate(tpl)}
                      className="flex w-full items-start gap-3 rounded-lg border border-border bg-surface/40 px-3 py-2.5 text-left transition-colors hover:border-accent/40 hover:bg-accent/5"
                    >
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-medium">{tpl.name}</p>
                        {tpl.description && (
                          <p className="mt-0.5 truncate text-xs text-muted">{tpl.description}</p>
                        )}
                        <div className="mt-1 flex items-center gap-1.5">
                          <span className="rounded bg-surface px-1.5 py-0.5 font-mono text-[10px] text-muted">
                            {tpl.templateType}
                          </span>
                          {tpl.isSystem && (
                            <span className="rounded bg-blue-50 px-1.5 py-0.5 text-[10px] text-blue-600 dark:bg-blue-950/30 dark:text-blue-400">
                              系统
                            </span>
                          )}
                        </div>
                      </div>
                      <ChevronRight className="mt-1 size-3.5 shrink-0 text-muted" />
                    </button>
                  ))}
                </div>
              )}
            </Modal.Body>
          </Modal.Dialog>
        </Modal.Container>
      </Modal.Backdrop>
    </div>
  );
}
