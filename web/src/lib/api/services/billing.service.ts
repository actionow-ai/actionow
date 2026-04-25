/**
 * Billing Service
 * Handles payment orders, checkout sessions, and order status queries
 */

import { api } from "../client";
import type {
  CreateTopupOrderRequest,
  CreateTopupOrderResponse,
  CreateCheckoutSessionRequest,
  CreateCheckoutSessionResponse,
  OrderDetailResponse,
  PlanPriceResponse,
  TopupRateConfig,
} from "../dto";

const BILLING_BASE = "/api/billing";

export const billingService = {
  /** Create a topup order */
  createTopupOrder: (data: CreateTopupOrderRequest) =>
    api.post<CreateTopupOrderResponse>(`${BILLING_BASE}/topups/orders`, data),

  /** Create checkout session (Stripe → redirect URL, WeChat → QR code_url) */
  createCheckoutSession: (orderNo: string, data?: CreateCheckoutSessionRequest) =>
    api.post<CreateCheckoutSessionResponse>(
      `${BILLING_BASE}/topups/orders/${orderNo}/checkout-session`,
      data
    ),

  /** Query order detail (used for polling WeChat payment status) */
  getOrder: (orderNo: string) =>
    api.get<OrderDetailResponse>(`${BILLING_BASE}/orders/${orderNo}`, {
      skipDedup: true,
    }),

  /** Verify payment status (called after Stripe redirect back) */
  verifyPayment: (orderNo: string) =>
    api.post<OrderDetailResponse>(
      `${BILLING_BASE}/orders/${orderNo}/verify-payment`
    ),

  /** Get topup exchange rate for a currency */
  getTopupRate: (currency: string) =>
    api.get<TopupRateConfig>(`${BILLING_BASE}/topups/rate`, {
      params: { currency },
    }),

  /** Get list of enabled payment providers */
  getEnabledPaymentProviders: () =>
    api.get<string[]>(`${BILLING_BASE}/payment-providers`),

  /** Admin: list plan prices */
  listPlans: (provider?: string) =>
    api.get<PlanPriceResponse[]>(`${BILLING_BASE}/admin/plans`, {
      params: provider ? { provider } : {},
    }),
};

export default billingService;