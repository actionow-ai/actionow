/**
 * Gift Code DTOs
 */

export type GiftCodeStatus = "ACTIVE" | "DISABLED" | "EXHAUSTED" | "EXPIRED";

export interface GiftCodeDTO {
  id: string;
  code: string;
  name: string | null;
  description: string | null;
  points: number;
  validFrom: string | null;
  validUntil: string | null;
  maxRedemptions: number;
  redeemedCount: number;
  status: GiftCodeStatus;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface GiftCodeRequestDTO {
  code?: string;
  name?: string | null;
  description?: string | null;
  points: number;
  validFrom?: string | null;
  validUntil?: string | null;
  maxRedemptions?: number;
  status?: GiftCodeStatus;
}

export interface GiftCodeRedemptionDTO {
  id: string;
  giftCodeId: string;
  code: string | null;
  userId: string;
  workspaceId: string;
  points: number;
  createdAt: string;
}

export interface GiftCodeRedeemRequestDTO {
  code: string;
}

export interface GiftCodeRedeemResponseDTO {
  code: string;
  points: number;
  redemptionId: string;
  workspaceId: string;
}
