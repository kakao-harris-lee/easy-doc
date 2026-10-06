import { requestJson, requestVoid } from './client'
import type { AdminBillingRequest, AdminBillingView, BillingOperation } from './adminBillingTypes'
export type { AdminBillingRequest, AdminBillingView, BillingOperation } from './adminBillingTypes'

/** 현재 판매하는 유일한 월 구독 플랜. */
export type TestSubscriptionPlanId = 'start'

export interface SubscriptionOverview {
  billing_environment?: 'stub' | 'toss_test' | 'toss_live'
  purchase_enabled?: boolean
  auto_charge_enabled?: boolean
  current_period_start?: string | null
  next_billing_at?: string | null
  card_last_four?: string | null
  manual_review?: boolean
  retry_at?: string | null
  retry_count?: number
  first_failure_at?: string | null
  mock_enabled: boolean
  toss_enabled?: boolean
  pending?: boolean
  billing_state?:
    | 'authorizing'
    | 'issuing'
    | 'active'
    | 'revoking'
    | 'revoked'
    | 'needs_card'
    | 'cancel_requested'
    | 'manual_review'
    | null
  plans: Array<{ id: string; name: string; allowance: number; monthly_price: number }>
  subscription: {
    plan_id: string
    allowance: number
    monthly_price: number
    status: 'active' | 'canceling' | 'expired' | 'past_due'
    cycle_ends_at: string
  } | null
  payments: Array<{
    id: string
    plan_id: string
    amount: number
    status: 'paid' | 'failed' | 'partially_refunded' | 'refunded'
    provider?: 'stub' | 'toss_test' | 'toss_live'
    approved_at?: string | null
    canceled_at?: string | null
    refunded_amount?: number
    created_at: string
  }>
}

export function getSubscription(workspace: string, signal?: AbortSignal, admin = false) {
  return requestJson<SubscriptionOverview>(
    `${admin ? '/admin' : ''}/workspaces/${workspace}/subscription`,
    { signal },
  )
}

export function checkoutSubscription(
  workspace: string,
  plan: TestSubscriptionPlanId,
  order: string,
  fail: boolean,
) {
  return requestJson<SubscriptionOverview>(`/workspaces/${workspace}/subscription/checkout`, {
    method: 'POST',
    body: { plan_id: plan, order_id: order, simulate_failure: fail },
  })
}

export function cancelSubscription(workspace: string) {
  return requestJson<SubscriptionOverview>(`/workspaces/${workspace}/subscription`, {
    method: 'DELETE',
  })
}

export interface TossSession {
  billing_environment?: 'toss_test' | 'toss_live'
  session_id: string
  customer_key: string
  client_key: string
}
export function beginTossBilling(
  workspace: string,
  plan: TestSubscriptionPlanId,
  purpose: 'purchase' | 'replace_card' = 'purchase',
  consentVersion?: string,
) {
  return requestJson<TossSession>(`/workspaces/${workspace}/subscription/billing`, {
    method: 'POST',
    body: { plan_id: plan, purpose, consent_version: consentVersion },
  })
}
export function completeTossBilling(
  workspace: string,
  session: string,
  customer: string,
  auth: string,
  fail: boolean,
) {
  return requestJson<SubscriptionOverview>(
    `/workspaces/${workspace}/subscription/billing/complete`,
    {
      method: 'POST',
      body: { session_id: session, customer_key: customer, auth_key: auth, simulate_failure: fail },
    },
  )
}
export function getTossReceipt(workspace: string, id: string) {
  return requestJson<{ receipt_url: string }>(`/workspaces/${workspace}/payments/${id}/receipt`)
}
export function getAdminBilling(workspace: string, signal?: AbortSignal) {
  return requestJson<AdminBillingView>(`/admin/workspaces/${workspace}/billing`, { signal })
}
export function getAdminBillingRequest(workspace: string, operation: string, signal?: AbortSignal) {
  return requestJson<AdminBillingRequest>(
    `/admin/workspaces/${workspace}/billing/requests/${operation}`,
    { signal },
  )
}
export function adminBillingAction(
  workspace: string,
  action: string,
  operation: string,
  reason: string,
  expectedRevision?: number,
) {
  return requestVoid(`/admin/workspaces/${workspace}/billing/${action}`, {
    method: 'POST',
    body: {
      operation_id: operation,
      reason,
      ...(expectedRevision === undefined ? {} : { expected_revision: expectedRevision }),
    },
  })
}
export function refundTossPayment(
  workspace: string,
  id: string,
  request: {
    operation_id: string
    amount: number
    recovery_credits: number
    stop_renewal: boolean
    reason: string
    expected_revision: number
  },
) {
  return requestJson<BillingOperation>(`/admin/workspaces/${workspace}/payments/${id}/refund`, {
    method: 'POST',
    body: request,
  })
}
