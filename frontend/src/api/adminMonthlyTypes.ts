/** Admin reporting wire models: contracts/easy-doc-v1.yaml. */
export interface AdminMonthlySummary {
  workspace_id: string
  month: string
  timezone: string
  is_current_month: boolean
  current: {
    balance: number
    reserved: number
    available: number
    allowance: number
    cycle_started_at: string | null
    cycle_ends_at: string | null
    subscription_status: string | null
    as_of: string
    revision: number
  }
  credits: {
    opening: number
    granted: number
    consumed: number
    expired: number
    adjustment: number
    cycle_net: number
    closing: number
    reserved: number
    available: number
    legacy_cycle_count: number
  }
  usage: {
    documents: number
    input_tokens: number
    output_tokens: number
    known_cost_usd: string | null
    unknown_cost_calls: number
    credits_by_reason: Record<string, number>
    estimated_legacy_credits: number
  }
  payments: {
    paid_krw: number
    refunded_krw: number
    net_krw: number
    test_paid_krw: number
    test_refunded_krw: number
    unknown_date_refund_krw: number
    unknown_date_paid_krw: number
    test_unknown_date_paid_krw: number
    test_unknown_date_refund_krw: number
  }
  completeness: {
    ledger_matches_account: boolean
    monthly_equation_matches: boolean
    warnings: string[]
  }
}
export interface AdminMonthlyHistory {
  workspace_id: string
  year: number
  timezone: string
  items: AdminMonthlySummary[]
}
export interface AdminReportPage<T> {
  items: T[]
  page: number
  size: number
  total: number
}
export interface AdminCreditEvent {
  granted_amount: number | null
  expired_amount: number | null
  adjustment_amount: number | null
  cycle_started_at_before: string | null
  cycle_ends_at_before: string | null
  cycle_started_at_after: string | null
  cycle_ends_at_after: string | null
  id: string
  kind: string
  reason: string
  balance_delta: number
  reserved_delta: number
  note: string | null
  actor_user_id: string | null
  payment_id: string | null
  created_at: string
}
export interface AdminPaymentEvent {
  id: string
  payment_id: string
  kind: 'payment' | 'refund' | 'attempt' | 'refund_attempt'
  amount_krw: number
  is_test: boolean
  occurred_at: string | null
  original_created_at: string
  plan_id: string
  status: string
  credit_transaction_id: string | null
  original_amount_krw: number
  refunded_amount_krw: number
  provider: string
}
