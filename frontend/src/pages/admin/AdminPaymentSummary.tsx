import { useEffect, useState } from 'react'
import { listAdminPayments } from '../../api/admin'
import type { AdminPaymentEvent } from '../../api/adminMonthlyTypes'

const won = (amount: number) => `${amount.toLocaleString('ko-KR')}원`

/** 목록 합계는 선택 월 기준이며, 날짜는 실제 결제 이벤트에서만 가져온다. */
export function AdminPaymentSummary({
  workspaceId,
  month,
  timezone,
  paid,
  refunded,
}: {
  workspaceId: string
  month: string
  timezone: string
  paid: number
  refunded: number
}) {
  const [payments, setPayments] = useState<AdminPaymentEvent[] | null>(null)
  const [error, setError] = useState(false)
  useEffect(() => {
    if (paid === 0) return
    const controller = new AbortController()
    async function load() {
      const found: AdminPaymentEvent[] = []
      // 원장은 환불·테스트·발생일 미상도 포함한다. 실제 선택 월 결제만 최대 3건 미리 본다.
      for (let page = 1; ; page += 1) {
        const result = await listAdminPayments(
          workspaceId,
          { month, page, size: 100 },
          controller.signal,
        )
        if (controller.signal.aborted) return
        found.push(
          ...result.items.filter(
            (item) =>
              item.kind === 'payment' &&
              !item.is_test &&
              item.occurred_at !== null &&
              new Intl.DateTimeFormat('sv-SE', {
                timeZone: timezone,
                year: 'numeric',
                month: '2-digit',
              }).format(new Date(item.occurred_at)) === month,
          ),
        )
        if (found.length >= 3 || page * result.size >= result.total || result.items.length === 0)
          break
      }
      if (!controller.signal.aborted) setPayments(found.slice(0, 3))
    }
    void load().catch(() => {
      if (!controller.signal.aborted) setError(true)
    })
    return () => controller.abort()
  }, [workspaceId, month, timezone, paid])

  return (
    <>
      <span>
        {won(paid)} / {won(refunded)}
      </span>
      {paid > 0 && (
        <div className="text-xs text-muted-foreground">
          {error ? (
            '결제일 조회 실패 · 관리에서 재확인'
          ) : payments === null ? (
            '결제일 확인 중…'
          ) : payments.length === 0 ? (
            '결제일 확인 필요'
          ) : (
            <ul>
              {payments.map((payment) => (
                <li key={payment.id}>
                  {won(payment.amount_krw)} (
                  {new Intl.DateTimeFormat('sv-SE', {
                    timeZone: timezone,
                    year: 'numeric',
                    month: '2-digit',
                    day: '2-digit',
                  }).format(new Date(payment.occurred_at!))}
                  )
                </li>
              ))}
            </ul>
          )}
          {payments && payments.length >= 3 && <span>최근 3건 · 전체 내역은 관리에서 확인</span>}
        </div>
      )}
      <span className="block text-xs text-muted-foreground">
        {month} 실제 결제 / 환불 · 테스트 제외
      </span>
    </>
  )
}
