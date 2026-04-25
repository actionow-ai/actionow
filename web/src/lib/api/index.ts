/**
 * API Module Exports
 *
 * Usage:
 *
 * ```ts
 * import { api, authService, ApiError, ERROR_CODES, getErrorMessage } from "@/lib/api";
 * import type { UserDTO, AuthResponseDTO } from "@/lib/api";
 *
 * // Using services
 * const user = await authService.getCurrentUser();
 *
 * // Using generic API client
 * const data = await api.get<MyType>("/endpoint");
 *
 * // Error handling
 * try {
 *   await authService.login({ account, password });
 * } catch (error) {
 *   if (error instanceof ApiError) {
 *     const message = getErrorMessage(error.code, locale);
 *   }
 * }
 * ```
 */

// API Client
export {
  api,
  createDebouncedFetch,
  resetApiClientSessionState,
  beginWorkspaceSwitchTransition,
  endWorkspaceSwitchTransition,
} from "./client";

// Types
export { ApiError, ERROR_CODES } from "./types";
export type { ApiResponse, RequestOptions, ErrorCode } from "./types";

// DTOs
export * from "./dto";

// Services
export {
  authService,
  userService,
  inviteService,
  oauthService,
  workspaceService,
  walletService,
  projectService,
  collabService,
  agentService,
  aiService,
  aiAdminService,
  adminService,
} from "./services";

// Mission service (separate export to avoid name collision)
export { missionService } from "./services";

// Skill service
export { skillService } from "./services";

// Batch job service
export { batchJobService } from "./services";

// Script permission service
export { scriptPermissionService } from "./services";

// Billing service
export { billingService } from "./services";

// Gift code service
export { giftCodeService } from "./services";

// Error utilities
export { getErrorMessage, getErrorFromException } from "./errors";

// Re-export stores for convenience
export {
  getAuthToken,
  getRefreshToken,
  getTokenBundle,
  getTokenWorkspaceId,
  setAuthBundle,
  clearAuthTokens,
  isAuthenticated,
} from "@/lib/stores/auth-store";
