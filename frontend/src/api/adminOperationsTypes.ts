/** Administrator operations wire models; see contracts/easy-doc-v1.yaml. */
export interface AdminPageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface AdminOperationItem {
  id: string
  kind: string
  /** Uncertain pending refunds are projected as manual_review (severity 2). */
  state: string
  environment: string | null
  workspace_id: string | null
  workspace_name: string | null
  created_at: string | null
  severity: number
  next_action: string
}

export interface AdminOperationsResponse extends AdminPageResult<AdminOperationItem> {
  counts: Record<string, number>
}

export interface AdminNotification {
  id: number
  workspace_id: string
  workspace_name?: string | null
  recipient_email?: string | null
  failure_code?: string | null
  event_type: string
  state: string
  environment: string | null
  revision: number
  resolution: string | null
  created_at: string
  attempted_at: string | null
  sent_at: string | null
  retry_allowed: boolean
  retry_blocked_reason: string | null
  resolve_allowed: boolean
  attempts: Array<{
    id: number
    state: string
    started_at: string
    finished_at: string | null
    failure_code?: string | null
  }>
  resolutions: Array<{
    operation_id: string
    actor_user_id: string | null
    action: string
    reason: string
    created_at: string
  }>
}

export interface AdminNotificationCommand {
  operation_id: string
  expected_revision: number
  reason: string
}

export interface AdminErrorEvent {
  conversion_id: string
  workspace_id: string
  failure_code: string | null
  created_at: string
}
