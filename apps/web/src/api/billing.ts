import { apiFetch } from './client';

export type BillingInterval =
  | 'monthly'
  | 'annual'
  | 'one_month'
  | 'three_months'
  | 'six_months'
  | 'twelve_months';

export type PlanDuration = 1 | 3 | 6 | 12;

export interface UsageSnapshot {
  plan: string;
  monthlyPriceCents: number;
  eventsUsed: number;
  eventCap: number;
  projectCap: number;
  retentionDays: number;
  ratio: number;
  exceeded: boolean;
  /** ISO-8601 timestamp; absent unless a payment failure or expiry opened a grace window. */
  paymentGraceUntil?: string;
  /** Billing cadence the org is on. Defaults to monthly via DB migration. */
  billingInterval: BillingInterval;
  /** Next renewal/expiry; absent for free-tier orgs. */
  renewsAt?: string;
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
  monthlyEventCap: number;
  projectCap: number;
  retentionDays: number;
  durations: DurationOffer[];
}

export interface BillingPlansResponse {
  currency: string;
  free: PlanTierInfo;
  pro: PlanTierInfo;
  enterprise: PlanTierInfo;
}

export function getUsage(orgId: number): Promise<UsageSnapshot> {
  return apiFetch<UsageSnapshot>(`/api/v1/orgs/${orgId}/usage`);
}

export function getBillingPlans(): Promise<BillingPlansResponse> {
  return apiFetch<BillingPlansResponse>('/api/v1/billing/plans');
}

export function startCheckout(
  orgId: number,
  interval: BillingInterval = 'monthly',
): Promise<CheckoutResponse> {
  return apiFetch<CheckoutResponse>(
    `/api/v1/orgs/${orgId}/billing/checkout-session?interval=${interval}`,
    { method: 'POST' },
  );
}

export function startCryptoCheckout(
  orgId: number,
  duration: PlanDuration,
): Promise<CryptoCheckoutResponse> {
  return apiFetch<CryptoCheckoutResponse>(
    `/api/v1/orgs/${orgId}/billing/crypto-invoice?duration=${duration}`,
    { method: 'POST' },
  );
}

export function openPortal(orgId: number): Promise<CheckoutResponse> {
  return apiFetch<CheckoutResponse>(`/api/v1/orgs/${orgId}/billing/portal`, {
    method: 'POST',
  });
}
