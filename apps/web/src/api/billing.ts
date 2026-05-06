import { apiFetch } from './client';

export interface UsageSnapshot {
  plan: string;
  monthlyPriceCents: number;
  eventsUsed: number;
  eventCap: number;
  projectCap: number;
  retentionDays: number;
  ratio: number;
  exceeded: boolean;
  /** ISO-8601 timestamp; absent unless a Stripe `invoice.payment_failed` opened a grace window. */
  paymentGraceUntil?: string;
}

export interface CheckoutResponse {
  url: string;
}

export function getUsage(orgId: number): Promise<UsageSnapshot> {
  return apiFetch<UsageSnapshot>(`/api/v1/orgs/${orgId}/usage`);
}

export function startCheckout(orgId: number): Promise<CheckoutResponse> {
  return apiFetch<CheckoutResponse>(`/api/v1/orgs/${orgId}/billing/checkout-session`, {
    method: 'POST',
  });
}

export function openPortal(orgId: number): Promise<CheckoutResponse> {
  return apiFetch<CheckoutResponse>(`/api/v1/orgs/${orgId}/billing/portal`, {
    method: 'POST',
  });
}
