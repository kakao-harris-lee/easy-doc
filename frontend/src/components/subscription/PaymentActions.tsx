import { useRef, useState } from 'react'
import { ApiError } from '../../api/client'
import {
  getTossReceipt,
  refundTossPayment,
  type SubscriptionOverview,
} from '../../api/subscriptions'
import { Button } from '../ui/Button'

type Payment = SubscriptionOverview['payments'][number]
export function PaymentActions({
  workspace,
  payment,
  admin,
  pending,
  onChanged,
}: {
  workspace: string
  payment: Payment
  admin: boolean
  pending?: boolean
  onChanged: (view: SubscriptionOverview) => void
}) {
  const [receipt, setReceipt] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [amount, setAmount] = useState(payment.amount - (payment.refunded_amount ?? 0))
  const operation = useRef<{ id: string; amount: number } | null>(null)
  async function act() {
    setBusy(true)
    setError(null)
    try {
      if (admin) {
        if (!operation.current || operation.current.amount !== amount)
          operation.current = { id: crypto.randomUUID(), amount }
        const view = await refundTossPayment(workspace, payment.id, operation.current.id, amount)
        onChanged(view)
        operation.current = null
      } else {
        const { receipt_url: url } = await getTossReceipt(workspace, payment.id)
        if (new URL(url).protocol !== 'https:') throw new Error('Invalid receipt URL')
        setReceipt(url)
      }
    } catch (cause) {
      setError(
        cause instanceof ApiError
          ? cause.message
          : '결과를 확인하지 못했습니다. 다시 시도해 주세요.',
      )
    } finally {
      setBusy(false)
    }
  }
  if (payment.provider !== 'toss_test' || payment.status === 'failed') return null
  return (
    <div className="mt-2 space-y-2">
      {admin ? (
        payment.status !== 'refunded' && (
          <details>
            <summary className="cursor-pointer">테스트 환불</summary>
            <p className="my-2 text-xs">
              환불은 구독을 해지하지 않습니다. 갱신 중단은 별도로 처리하세요.
            </p>
            <label className="block">
              환불 금액 (원)
              <input
                type="number"
                min="1"
                max={payment.amount - (payment.refunded_amount ?? 0)}
                value={amount}
                className="ml-2 w-24 rounded border p-1"
                onChange={(e) => setAmount(Number(e.target.value))}
                disabled={busy || pending}
              />
            </label>
            <Button
              className="mt-2"
              disabled={busy || pending || amount <= 0}
              onClick={() => void act()}
            >
              테스트 환불 실행
            </Button>
          </details>
        )
      ) : receipt ? (
        <a href={receipt} target="_blank" rel="noopener noreferrer" className="underline">
          토스 테스트 영수증 열기
        </a>
      ) : (
        <Button variant="ghost" disabled={busy} onClick={() => void act()}>
          테스트 영수증 확인
        </Button>
      )}
      {error && <p role="alert">{error}</p>}
    </div>
  )
}
