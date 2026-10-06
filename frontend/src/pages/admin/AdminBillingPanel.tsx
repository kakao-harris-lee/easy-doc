import { useEffect, useRef, useState } from 'react'
import { adminBillingAction, getAdminBilling, type AdminBillingView } from '../../api/subscriptions'
import { ApiError } from '../../api/client'
import { Button } from '../../components/ui/Button'

export function AdminBillingPanel({
  workspaceId,
  onChanged,
}: {
  workspaceId: string
  onChanged: () => void
}) {
  const [view, setView] = useState<AdminBillingView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [reload, setReload] = useState(0)
  const [submittedAction, setSubmittedAction] = useState<string | null>(null)
  const request = useRef<{ action: string; id: string; reason: string } | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    void getAdminBilling(workspaceId, controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) setView(data)
      })
      .catch(() => {
        if (!controller.signal.aborted) setError('결제 운영 상태를 불러오지 못했습니다.')
      })
    return () => controller.abort()
  }, [workspaceId, reload])
  async function act(action: string) {
    setBusy(true)
    setError(null)
    try {
      request.current ??= { action, id: crypto.randomUUID(), reason: reason.trim() }
      setSubmittedAction(request.current.action)
      await adminBillingAction(
        workspaceId,
        request.current.action,
        request.current.id,
        request.current.reason,
      )
      request.current = null
      setSubmittedAction(null)
      setReload((n) => n + 1)
      onChanged()
    } catch (cause) {
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
    return busy || !reason.trim() || (submittedAction !== null && submittedAction !== action)
  }
  return (
    <section aria-label="결제 운영 관리" className="space-y-3 rounded-xl border border-border p-4">
      <h4 className="font-semibold">결제 운영 관리</h4>
      {error && <p role="alert">{error}</p>}
      <label className="block">
        관리 조치 사유
        <input
          maxLength={200}
          value={reason}
          disabled={busy || submittedAction !== null}
          onChange={(e) => setReason(e.target.value)}
        />
      </label>
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
                <Button
                  disabled={disabled(`orders/${order.id}/sync`)}
                  onClick={() => void act(`orders/${order.id}/sync`)}
                >
                  결과 재조회
                </Button>
              </li>
            ))}
          </ul>
          {view.orders.length === 0 && <p>조회할 결제 주문이 없습니다.</p>}
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
