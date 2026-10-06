import { useEffect, useRef, useState } from 'react'
import {
  adminBillingAction,
  getAdminBilling,
  getAdminBillingRequest,
  type AdminBillingView,
} from '../../api/subscriptions'
import { ApiError } from '../../api/client'
import { useAdminOperationKey, readOperation, saveOperation } from './adminOperationStorage'
import { AdminPendingRefunds } from './AdminPendingRefunds'
import { Button } from '../../components/ui/Button'

export function AdminBillingPanel({
  workspaceId,
  onChanged,
  refreshToken = 0,
}: {
  workspaceId: string
  refreshToken?: string | number
  onChanged: () => void
}) {
  const [view, setView] = useState<AdminBillingView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const storageKey = useAdminOperationKey(workspaceId, 'billing')
  const [restored] = useState(() =>
    readOperation<{ action: string; id: string; reason: string; revision?: number }>(storageKey),
  )
  const [reason, setReason] = useState(restored?.reason ?? '')
  const [lastChecked, setLastChecked] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [reload, setReload] = useState(0)
  const observationKey = `${workspaceId}:${reload}:${refreshToken}`
  const [viewKey, setViewKey] = useState('')
  const [submittedAction, setSubmittedAction] = useState<string | null>(restored?.action ?? null)
  const request = useRef<{ action: string; id: string; reason: string; revision?: number } | null>(
    restored,
  )
  const polling = useRef({ signature: '', attempt: 0 })
  useEffect(() => {
    const controller = new AbortController()
    void getAdminBilling(workspaceId, controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) {
          const pendingRequest = request.current
          const status = data.actions?.find(
            (item) => item.operation_id === pendingRequest?.id,
          )?.status
          if (pendingRequest && status && ['completed', 'failed'].includes(status)) {
            saveOperation(storageKey, null)
            request.current = null
            setSubmittedAction(null)
          }
          setView(data)
          setViewKey(observationKey)
          setError(null)
          setLastChecked(new Date().toLocaleString('ko-KR'))
        }
      })
      .catch(() => {
        if (!controller.signal.aborted) setError('결제 운영 상태를 불러오지 못했습니다.')
      })
    return () => controller.abort()
  }, [workspaceId, reload, refreshToken, observationKey, storageKey])
  useEffect(() => {
    if (
      !view ||
      viewKey !== observationKey ||
      !(
        view.deletion_pending ||
        [...view.orders, ...view.operations, ...(view.actions ?? [])].some((item) =>
          ['pending', 'processing'].includes(item.status),
        )
      )
    )
      return
    const signature = JSON.stringify([
      view.card_state,
      view.deletion_pending,
      view.orders.map((item) => [item.id, item.status]),
      view.operations.map((item) => [item.operation_id, item.status]),
      view.actions?.map((item) => [item.operation_id, item.status]),
    ])
    if (polling.current.signature !== signature) polling.current = { signature, attempt: 0 }
    const delay = Math.min(30_000, 5000 * 2 ** Math.min(polling.current.attempt, 3))
    const timer = window.setTimeout(() => {
      polling.current.attempt += 1
      setReload((n) => n + 1)
      onChanged()
    }, delay)
    return () => window.clearTimeout(timer)
  }, [view, onChanged, viewKey, observationKey])
  async function act(action: string) {
    if (
      !request.current &&
      (typeof view?.revision !== 'number' || !Number.isSafeInteger(view.revision))
    ) {
      setError('현재 계정 버전을 확인한 뒤 조치할 수 있습니다. 상태를 새로고침해 주세요.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      request.current ??= {
        action,
        id: crypto.randomUUID(),
        reason: reason.trim(),
        revision: view?.revision,
      }
      const submittedRequest = request.current
      setSubmittedAction(submittedRequest.action)
      saveOperation(storageKey, submittedRequest)
      let resolved = false
      if (submittedAction) {
        try {
          const prior = await getAdminBillingRequest(workspaceId, submittedRequest.id)
          resolved = ['completed', 'failed'].includes(prior.status)
        } catch (lookupError) {
          if (!(lookupError instanceof ApiError) || lookupError.status !== 404) throw lookupError
        }
      }
      if (!resolved) {
        await adminBillingAction(
          workspaceId,
          submittedRequest.action,
          submittedRequest.id,
          submittedRequest.reason,
          submittedRequest.revision,
        )
        const latest = await getAdminBillingRequest(workspaceId, submittedRequest.id)
        resolved = ['completed', 'failed'].includes(latest.status)
      }
      if (resolved) {
        saveOperation(storageKey, null)
        request.current = null
        setSubmittedAction(null)
      }
      setReload((n) => n + 1)
      onChanged()
    } catch (cause) {
      let confirmedAbsent = false
      if (cause instanceof ApiError && cause.status === 409 && request.current) {
        try {
          await getAdminBillingRequest(workspaceId, request.current.id)
        } catch (lookupError) {
          confirmedAbsent = lookupError instanceof ApiError && lookupError.status === 404
        }
      }
      if (cause instanceof ApiError && (cause.status === 422 || confirmedAbsent)) {
        saveOperation(storageKey, null)
        request.current = null
        setSubmittedAction(null)
        onChanged()
        setReload((n) => n + 1)
      }
      setError(
        cause instanceof ApiError
          ? cause.message
          : '결과를 확인하지 못했습니다. 같은 조치를 다시 확인하세요.',
      )
    } finally {
      setBusy(false)
    }
  }
  function disabled(action: string) {
    const permission = view?.allowed_actions?.find(
      (item) => item.action === action.replaceAll('-', '_'),
    )
    return (
      busy ||
      !view ||
      (submittedAction === null &&
        (viewKey !== observationKey ||
          typeof view.revision !== 'number' ||
          !Number.isSafeInteger(view.revision))) ||
      !reason.trim() ||
      (submittedAction !== null && submittedAction !== action) ||
      (submittedAction !== action && permission?.allowed === false)
    )
  }
  return (
    <section aria-label="결제 운영 관리" className="space-y-3 rounded-xl border border-border p-4">
      <h4 className="font-semibold">결제 운영 관리</h4>
      <AdminPendingRefunds workspaceId={workspaceId} onChanged={onChanged} />
      {error && <p role="alert">{error}</p>}
      <p>마지막 확인: {lastChecked ?? '아직 확인하지 못함'}</p>
      {submittedAction && (
        <p role="status">결과 미확정 요청이 있습니다. 같은 조치로 결과를 확인하세요.</p>
      )}
      <label className="block">
        관리 조치 사유
        <input
          maxLength={200}
          value={reason}
          disabled={busy || submittedAction !== null}
          onChange={(e) => setReason(e.target.value)}
        />
      </label>
      {view?.allowed_actions
        ?.filter((item) => !item.allowed)
        .map((item) => (
          <p key={item.action}>{item.reason}</p>
        ))}
      <div className="flex gap-2">
        <Button disabled={disabled('stop-renewal')} onClick={() => void act('stop-renewal')}>
          갱신 중단
        </Button>
        <Button
          disabled={disabled('retry-card-deletion')}
          onClick={() => void act('retry-card-deletion')}
        >
          카드 삭제 재처리
        </Button>
        <Button disabled={busy} onClick={() => setReload((n) => n + 1)}>
          상태 새로고침
        </Button>
      </div>
      {!view ? (
        <p role="status">운영 상태 확인 중…</p>
      ) : (
        <>
          <ul className="space-y-2">
            {view.orders.map((order) => (
              <li key={order.id}>
                {order.environment === 'toss_live' ? '실결제' : '테스트'} · {order.id} ·{' '}
                {order.amount.toLocaleString('ko-KR')}원 · {order.status}
                {order.needs_review && (
                  <strong className="ml-2 text-danger">관리자 확인 필요</strong>
                )}
                {order.sync_blocked_reason && <p>{order.sync_blocked_reason}</p>}
                <Button
                  disabled={
                    disabled(`orders/${order.id}/sync`) ||
                    (submittedAction !== `orders/${order.id}/sync` && order.can_sync === false)
                  }
                  onClick={() => void act(`orders/${order.id}/sync`)}
                >
                  결과 재조회
                </Button>
              </li>
            ))}
          </ul>
          {view.orders.length === 0 && <p>조회할 결제 주문이 없습니다.</p>}
          <details>
            <summary>감사 이력 {view.audit?.length ?? 0}건</summary>
            <ul>
              {view.audit?.map((entry) => (
                <li key={entry.operation_id}>
                  관리자 {entry.actor_user_id} · 대상 {entry.target_id ?? workspaceId} ·{' '}
                  {entry.action} · {entry.status}
                  <p>
                    사유: {entry.reason} · 요청 {entry.operation_id}
                  </p>
                  <p>
                    요청 시각 {entry.created_at} · 최종 처리 {entry.updated_at}
                  </p>
                  <p>
                    보유 {entry.before_balance ?? '기록 없음'} →{' '}
                    {entry.after_balance ?? '기록 없음'}
                  </p>
                  <p>
                    구독 {entry.before_subscription_status ?? '없음'} →{' '}
                    {entry.after_subscription_status ?? '없음'} · 카드{' '}
                    {entry.before_card_state ?? '없음'} → {entry.after_card_state ?? '없음'}
                  </p>
                </li>
              ))}
            </ul>
          </details>
          <details>
            <summary>관리 조치 이력 {view.actions?.length ?? 0}건</summary>
            <ul>
              {view.actions?.map((action) => (
                <li key={action.operation_id}>
                  {action.operation_id} · {action.action} · {action.status} · {action.reason} ·{' '}
                  {new Date(action.updated_at).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}
                </li>
              ))}
            </ul>
          </details>
          <details>
            <summary>환불 관리 작업 {view.operations.length}건</summary>
            <ul>
              {view.operations.map((operation) => (
                <li key={operation.operation_id}>
                  {operation.operation_id} · {operation.amount.toLocaleString('ko-KR')}원 · 회수{' '}
                  {operation.recovery_credits}크레딧 ·{' '}
                  {operation.stop_renewal ? '갱신 중단' : '갱신 유지'} · {operation.status} ·{' '}
                  {operation.reason}
                </li>
              ))}
            </ul>
          </details>
        </>
      )}
    </section>
  )
}
