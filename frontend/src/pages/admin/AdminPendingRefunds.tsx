import { useState } from 'react'
import { ApiError } from '../../api/client'
import { getAdminBillingRequest, refundTossPayment } from '../../api/subscriptions'
import { Button } from '../../components/ui/Button'
import { readOperation, saveOperation, useAdminOperationKey } from './adminOperationStorage'

type Pending = Parameters<typeof refundTossPayment>[2] & { payment_id: string }
export function AdminPendingRefunds({
  workspaceId,
  onChanged,
}: {
  workspaceId: string
  onChanged: () => void
}) {
  const prefix = useAdminOperationKey(workspaceId, 'refund:')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  let entries: Array<[string, Pending]> = []
  try {
    entries = Object.keys(sessionStorage)
      .filter((key) => key.startsWith(prefix))
      .flatMap((key) => {
        const value = readOperation<Pending>(key)
        return value?.operation_id && value.payment_id ? [[key, value] as [string, Pending]] : []
      })
  } catch {
    /* Individual forms retain their request in memory. */
  }
  async function recover(key: string, request: Pending) {
    setBusy(true)
    setError(null)
    try {
      let result
      try {
        result = await getAdminBillingRequest(workspaceId, request.operation_id)
      } catch (cause) {
        if (!(cause instanceof ApiError) || cause.status !== 404) throw cause
        const { payment_id, ...payload } = request
        result = await refundTossPayment(workspaceId, payment_id, payload)
      }
      if (['completed', 'failed'].includes(result.status)) saveOperation(key, null)
      else setError('요청이 처리 중입니다. 잠시 후 결과를 다시 확인하세요.')
      onChanged()
    } catch (cause) {
      setError(
        cause instanceof ApiError
          ? cause.message
          : '환불 결과를 확인하지 못했습니다. 요청은 보존됩니다.',
      )
    } finally {
      setBusy(false)
    }
  }
  if (!entries.length) return null
  return (
    <section aria-label="미확정 환불 복구">
      <h4>이전 환불 요청 확인</h4>
      <p>조회 월과 관계없이 아직 확인되지 않은 요청입니다.</p>
      {entries.map(([key, value]) => (
        <div key={key}>
          <p>
            결제 {value.payment_id} · 요청 {value.operation_id} ·{' '}
            {value.amount.toLocaleString('ko-KR')}원 · {value.reason}
          </p>
          <Button disabled={busy} onClick={() => void recover(key, value)}>
            같은 환불 요청 결과 확인
          </Button>
        </div>
      ))}
      {error && <p role="alert">{error}</p>}
    </section>
  )
}
