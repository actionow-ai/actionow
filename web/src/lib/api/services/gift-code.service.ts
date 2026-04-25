/**
 * Gift Code Service
 * - Admin (system-tenant only) APIs for managing gift codes
 * - Member API for redeeming a code into the current workspace wallet
 */

import { api } from "../client";
import type {
  GiftCodeDTO,
  GiftCodeRequestDTO,
  GiftCodeRedemptionDTO,
  GiftCodeRedeemRequestDTO,
  GiftCodeRedeemResponseDTO,
} from "../dto/gift-code.dto";

const BASE = "/api/system/gift-codes";

export interface GiftCodePageDTO {
  records: GiftCodeDTO[];
  total: number;
  current: number;
  size: number;
  pages: number;
}

export interface GiftCodeRedemptionPageDTO {
  records: GiftCodeRedemptionDTO[];
  total: number;
  current: number;
  size: number;
  pages: number;
}

export const giftCodeService = {
  // ── Admin ────────────────────────────────────────────────
  list: (params: { current?: number; size?: number; keyword?: string; status?: string }) =>
    api.get<GiftCodePageDTO>(BASE, { params }),

  get: (id: string) => api.get<GiftCodeDTO>(`${BASE}/${id}`),

  create: (data: GiftCodeRequestDTO) => api.post<GiftCodeDTO>(BASE, data),

  update: (id: string, data: GiftCodeRequestDTO) =>
    api.put<GiftCodeDTO>(`${BASE}/${id}`, data),

  remove: (id: string) => api.delete<null>(`${BASE}/${id}`),

  listRedemptions: (id: string, params?: { current?: number; size?: number }) =>
    api.get<GiftCodeRedemptionPageDTO>(`${BASE}/${id}/redemptions`, { params }),

  // ── User ────────────────────────────────────────────────
  redeem: (data: GiftCodeRedeemRequestDTO) =>
    api.post<GiftCodeRedeemResponseDTO>(`${BASE}/redeem`, data),
};

export default giftCodeService;
