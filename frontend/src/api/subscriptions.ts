import { requestJson } from './client'

export interface SubscriptionOverview {
  mock_enabled: boolean
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
    status: 'paid' | 'failed'
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
  plan: string,
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
