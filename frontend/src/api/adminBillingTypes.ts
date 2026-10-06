/** Financial administration wire models shared with browser tests. */
export interface BillingOperation {
  operation_id: string
  workspace_id: string
  payment_id: string
  amount: number
  recovery_credits: number
  stop_renewal: boolean
  reason: string
  status: 'pending' | 'completed' | 'failed'
  created_at: string
  updated_at: string
}
export interface AdminBillingView {
  revision?: number
  card_state?: string | null
  deletion_pending?: boolean | null
  allowed_actions?: Array<{ action: string; allowed: boolean; reason: string | null }>
  audit?: Array<{
    operation_id: string
    actor_user_id: string | null
    action: string
    target_id: string | null
    reason: string
    status: string
    created_at: string
    updated_at: string
    before_subscription_status: string | null
    after_subscription_status: string | null
    before_card_state: string | null
    after_card_state: string | null
    before_balance?: string | null
    after_balance?: string | null
  }>
  actions?: Array<{
    operation_id: string
    action: string
    status: string
    reason: string
    created_at: string
    updated_at: string
  }>
  orders: Array<{
    id: string
    kind: string
    status: string
    amount: number
    created_at: string
    environment: string
    needs_review: boolean
    can_sync?: boolean
    sync_blocked_reason?: string | null
  }>
  operations: BillingOperation[]
}
export interface AdminBillingRequest {
  operation_id: string
  kind: string
  status: string
  created_at: string
  updated_at: string
}
