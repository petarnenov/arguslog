import { apiFetch } from './client';

export type BillingInterval =
  | 'monthly'
  | 'annual'
  | 'one_month'
  | 'three_months'
  | 'six_months'
  | 'twelve_months';

export type PlanDuration = 1 | 3 | 6 | 12;

export interface BonusInfo {
  until: string;
  reason: string | null;
  grantedByEmail: string | null;
}

export interface CheckoutResponse {
  url: string;
}

export interface CryptoCheckoutResponse {
  checkoutUrl: string;
  invoiceReference: string;
}

export interface DurationOffer {
  months: PlanDuration;
  amountCents: number;
  perMonthCents: number;
  savePercent: number;
}

export interface PlanTierInfo {
  plan: string;
  monthlyPriceCents: number;
  monthlyEventCap: number;
  projectCap: number;
  memberCap: number;
  orgCap: number;
  retentionDays: number;
  unlimitedProjects: boolean;
  unlimitedMembers: boolean;
  unlimitedOrgs: boolean;
  unlimitedEvents: boolean;
  durations: DurationOffer[];
}

export interface BillingPlansResponse {
  currency: string;
  tiers: PlanTierInfo[];
}

export type PaidTier = 'starter' | 'pro' | 'business';

export function getBillingPlans(): Promise<BillingPlansResponse> {
  return apiFetch<BillingPlansResponse>('/api/v1/billing/plans');
}

// ── User-level billing (V26+) ─────────────────────────────────────────────
// /me/billing endpoints resolve the user's "primary owned org" (highest tier, earliest
// membership) under the hood. The org-scoped /api/v1/orgs/:orgId/billing/* endpoints stayed
// alive on the backend for MCP / external API callers; the dashboard no longer calls them.

export function startMeCheckout(interval: BillingInterval = 'monthly'): Promise<CheckoutResponse> {
  return apiFetch<CheckoutResponse>(`/api/v1/me/billing/checkout-session?interval=${interval}`, {
    method: 'POST',
  });
}

export function startMeCryptoCheckout(
  tier: PaidTier,
  duration: PlanDuration,
): Promise<CryptoCheckoutResponse> {
  return apiFetch<CryptoCheckoutResponse>(
    `/api/v1/me/billing/crypto-invoice?tier=${tier}&duration=${duration}`,
    { method: 'POST' },
  );
}

export function openMePortal(): Promise<CheckoutResponse> {
  return apiFetch<CheckoutResponse>('/api/v1/me/billing/portal', { method: 'POST' });
}
