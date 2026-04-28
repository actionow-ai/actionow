/**
 * Workspace Admin Service
 * 系统租户管理员视角的 workspace 接口（用于灰度白名单 / 内部测试 workspace 管理）
 */

import { api } from "../client";

const BASE = "/api/workspaces/admin";

export interface WorkspaceAdminDTO {
  id: string;
  name: string;
  slug: string;
  ownerId: string;
  status: string;
  planType?: string;
  isInternal: boolean;
  memberCount?: number;
  createdAt?: string;
}

export interface WorkspaceAdminPageDTO {
  current: number;
  size: number;
  total: number;
  pages: number;
  records: WorkspaceAdminDTO[];
}

export const workspaceAdminService = {
  /** 分页搜索 workspace（按 name/slug 模糊匹配） */
  searchWorkspaces: (params: {
    current?: number;
    size?: number;
    q?: string;
    internalOnly?: boolean;
  }) =>
    api.get<WorkspaceAdminPageDTO>(BASE, {
      params: {
        current: params.current ?? 1,
        size: params.size ?? 20,
        q: params.q,
        internalOnly: params.internalOnly,
      },
    }),

  /** 设置 workspace 是否为内部测试 workspace */
  setInternal: (workspaceId: string, internal: boolean) =>
    api.patch<WorkspaceAdminDTO>(
      `${BASE}/${workspaceId}/internal-flag`,
      undefined,
      { params: { internal } }
    ),
};

export default workspaceAdminService;
