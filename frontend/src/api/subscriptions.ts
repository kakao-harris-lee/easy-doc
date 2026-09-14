import { requestJson } from './client'

/** 서버가 현재 테스트 결제에 노출하는 유일한 플랜. */
export type TestSubscriptionPlanId = 'start'

export interface SubscriptionOverview {
  mock_enabled: boolean
  toss_enabled?: boolean
  pending?: boolean
  billing_state?: 'authorizing' | 'issuing' | 'active' | 'revoking' | 'revoked' | null
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
    provider?: 'stub' | 'toss_test'
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
  session_id: string
  customer_key: string
  client_key: string
}
export function beginTossBilling(workspace: string, plan: TestSubscriptionPlanId) {
  return requestJson<TossSession>(`/workspaces/${workspace}/subscription/billing`, {
    method: 'POST',
    body: { plan_id: plan },
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
export function refundTossPayment(
  workspace: string,
  id: string,
  operation: string,
  amount: number,
) {
  return requestJson<SubscriptionOverview>(`/admin/workspaces/${workspace}/payments/${id}/refund`, {
    method: 'POST',
    body: { operation_id: operation, amount },
  })
}
