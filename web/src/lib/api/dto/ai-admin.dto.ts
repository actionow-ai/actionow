/**
 * AI Admin DTOs
 * Used by AI admin management pages (model providers, LLM providers, groovy templates)
 */

export type AiProviderType = "IMAGE" | "VIDEO" | "AUDIO" | "TEXT";

export type LlmVendor =
  | "GOOGLE"
  | "OPENAI"
  | "ANTHROPIC"
  | "VOLCENGINE"
  | "ZHIPU"
  | "MOONSHOT"
  | "BAIDU"
  | "ALIBABA"
  | "DASHSCOPE"
  | "DEEPSEEK";

export type GroovyTemplateType = "REQUEST_BUILDER" | "RESPONSE_MAPPER" | "CUSTOM_LOGIC";

export interface ModelProviderDTO {
  id: string;
  name: string;
  description?: string | null;
  providerType: AiProviderType;
  pluginId: string;
  pluginType?: string;
  baseUrl?: string;
  endpoint?: string;
  httpMethod?: string;
  authType?: string;
  authConfig?: Record<string, unknown> | null;
  apiKeyRef?: string | null;
  baseUrlRef?: string | null;
  llmProviderId?: string | null;
  systemPrompt?: string | null;
  requestBuilderScript?: string | null;
  responseMapperScript?: string | null;
  customLogicScript?: string | null;
  syncStatus?: string | null;
  supportedModes?: string[] | null;
  iconUrl?: string | null;
  creditCost: number;
  outputSchema?: Record<string, unknown> | unknown[] | null;
  responseSchema?: Record<string, unknown> | unknown[] | null;
  pricingRules?: Record<string, unknown> | null;
  pricingScript?: string | null;
  priority?: number;
  timeout?: number;
  maxRetries?: number;
  rateLimit?: number;
  enabled?: boolean;
  visibility?: ProviderVisibility;
  supportsStreaming?: boolean;
  supportsBlocking?: boolean;
  supportsCallback?: boolean;
  supportsPolling?: boolean;
  callbackConfig?: Record<string, unknown> | null;
  pollingConfig?: Record<string, unknown> | null;
  inputSchema?: unknown[];
  inputGroups?: unknown[];
  exclusiveGroups?: unknown[];
  createdAt?: string;
  updatedAt?: string;
}

export type ProviderVisibility = "PUBLIC" | "INTERNAL" | "WHITELIST";

export interface ProviderWhitelistEntryDTO {
  id: string;
  providerId: string;
  workspaceId: string;
  note?: string | null;
  createdAt?: string;
}

export interface SaveModelProviderRequestDTO {
  name: string;
  description?: string;
  providerType: AiProviderType;
  pluginId: string;
  pluginType?: string;
  // HTTP endpoint
  baseUrl?: string;
  endpoint?: string;
  httpMethod?: string;
  // Auth
  authType?: string;
  authConfig?: Record<string, unknown>;
  apiKeyRef?: string;
  baseUrlRef?: string;
  // LLM binding (TEXT type)
  llmProviderId?: string;
  systemPrompt?: string;
  responseSchema?: Record<string, unknown> | unknown[];
  // Scripts
  requestBuilderScript?: string;
  responseMapperScript?: string;
  customLogicScript?: string;
  // Visuals
  iconUrl?: string;
  // Billing
  creditCost: number;
  pricingRules?: Record<string, unknown>;
  pricingScript?: string;
  // Runtime
  priority?: number;
  timeout?: number;
  maxRetries?: number;
  rateLimit?: number;
  customHeaders?: Record<string, string>;
  enabled?: boolean;
  visibility?: ProviderVisibility;
  // Response modes
  supportsStreaming?: boolean;
  supportsBlocking?: boolean;
  supportsCallback?: boolean;
  supportsPolling?: boolean;
  callbackConfig?: Record<string, unknown>;
  pollingConfig?: Record<string, unknown>;
  // Schema
  outputSchema?: Record<string, unknown> | unknown[];
  inputSchema?: unknown[];
  inputGroups?: unknown[];
  exclusiveGroups?: unknown[];
}

export interface LlmProviderDTO {
  id: string;
  provider: LlmVendor | string;
  modelId: string;
  modelName: string;
  temperature?: number;
  maxOutputTokens?: number;
  topP?: number;
  topK?: number;
  apiEndpoint?: string;
  apiEndpointRef?: string;
  completionsPath?: string;
  apiKeyRef?: string;
  extraConfig?: Record<string, unknown>;
  contextWindow?: number;
  maxInputTokens?: number;
  enabled?: boolean;
  priority?: number;
  description?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface SaveLlmProviderRequestDTO {
  provider: LlmVendor | string;
  modelId: string;
  modelName: string;
  temperature?: number;
  maxOutputTokens?: number;
  topP?: number;
  topK?: number;
  apiEndpoint?: string;
  apiEndpointRef?: string;
  completionsPath?: string;
  apiKeyRef?: string;
  extraConfig?: Record<string, unknown>;
  contextWindow?: number;
  maxInputTokens?: number;
  enabled?: boolean;
  priority?: number;
  description?: string;
}

export interface GroovyTemplateDTO {
  id: string;
  name: string;
  templateType: GroovyTemplateType | string;
  generationType?: string | null;
  scriptContent: string;
  scriptVersion?: string | null;
  description?: string | null;
  documentation?: string | null;
  exampleInput?: unknown | null;
  exampleOutput?: unknown | null;
  isSystem?: boolean;
  enabled?: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface SaveGroovyTemplateRequestDTO {
  name: string;
  templateType: GroovyTemplateType | string;
  generationType?: string;
  scriptContent: string;
  scriptVersion?: string;
  description?: string;
  documentation?: string;
  exampleInput?: unknown;
  exampleOutput?: unknown;
  isSystem?: boolean;
  enabled?: boolean;
}

export interface ScriptValidationDTO {
  valid: boolean;
  errors: Array<string | { line?: number; message?: string }>;
}

export interface ScriptTestResultDTO {
  success?: boolean;
  outputs?: unknown;
  result?: unknown;
  message?: string;
  errorMessage?: string;
}

export interface AiPageResponseDTO<T> {
  records?: T[];
  list?: T[];
  items?: T[];
  total?: number;
  current?: number;
  size?: number;
  pages?: number;
  pageNum?: number;
  pageSize?: number;
  page?: number;
}

export interface NormalizedPageDTO<T> {
  records: T[];
  total: number;
  current: number;
  size: number;
  pages: number;
}
