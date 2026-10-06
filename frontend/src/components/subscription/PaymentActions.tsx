import { useEffect, useRef, useState } from 'react'
import { ApiError } from '../../api/client'
import {
  getTossReceipt,
  getAdminBillingRequest,
  refundTossPayment,
  type SubscriptionOverview,
} from '../../api/subscriptions'
import { readAdminMonthlySummary } from '../../api/admin'
import type { AdminMonthlySummary } from '../../api/adminMonthlyTypes'
import { Button } from '../ui/Button'

import {
  useAdminOperationKey,
  readOperation,
  saveOperation,
} from '../../pages/admin/adminOperationStorage'

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
  onChanged: (view?: SubscriptionOverview) => void
}) {
  const storageKey = useAdminOperationKey(workspace, `refund:${payment.id}`)
  const [restored] = useState(() =>
    admin ? readOperation<Parameters<typeof refundTossPayment>[2]>(storageKey) : null,
  )
  const [receipt, setReceipt] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [account, setAccount] = useState<AdminMonthlySummary['current'] | null>(null)
  const [amount, setAmount] = useState(
    restored?.amount ?? payment.amount - (payment.refunded_amount ?? 0),
  )
  const [credits, setCredits] = useState(restored?.recovery_credits ?? 0)
  const [stop, setStop] = useState(restored?.stop_renewal ?? true)
  const [reason, setReason] = useState(restored?.reason ?? '')
  const [confirmed, setConfirmed] = useState(Boolean(restored))
  const [submitted, setSubmitted] = useState(Boolean(restored))
  const operation = useRef<Parameters<typeof refundTossPayment>[2] | null>(
    restored
      ? {
          operation_id: restored.operation_id,
          amount: restored.amount,
          recovery_credits: restored.recovery_credits,
          stop_renewal: restored.stop_renewal,
          reason: restored.reason,
          expected_revision: restored.expected_revision,
        }
      : null,
  )
  useEffect(() => {
    if (!restored || message) return
    const controller = new AbortController()
    void getAdminBillingRequest(workspace, restored.operation_id, controller.signal)
      .then((result) => {
        if (controller.signal.aborted) return
        if (['completed', 'failed'].includes(result.status)) {
          saveOperation(storageKey, null)
          setMessage(
            result.status === 'completed'
              ? '환불 관리 작업이 완료되었습니다.'
              : '환불이 거절되었습니다. 관리 작업 내역을 확인하세요.',
          )
          onChanged()
        }
      })
      .catch(() => {
        if (!controller.signal.aborted)
          setError('이전 환불 요청의 결과를 확인하지 못했습니다. 같은 요청으로 다시 확인하세요.')
      })
    return () => controller.abort()
  }, [restored, workspace, storageKey, onChanged, message])
  const live = payment.provider === 'toss_live'
  async function review() {
    setBusy(true)
    setError(null)
    try {
      const month = new Intl.DateTimeFormat('sv-SE', {
        timeZone: 'Asia/Seoul',
        year: 'numeric',
        month: '2-digit',
      }).format(new Date())
      setAccount((await readAdminMonthlySummary(workspace, month)).current)
    } catch {
      setError('현재 크레딧과 계정 버전을 불러오지 못했습니다.')
    } finally {
      setBusy(false)
    }
  }
  async function act() {
    setBusy(true)
    setError(null)
    try {
      if (admin && (account || operation.current)) {
        operation.current ??= {
          operation_id: crypto.randomUUID(),
          amount,
          recovery_credits: credits,
          stop_renewal: stop,
          reason: reason.trim(),
          expected_revision: account!.revision,
        }
        setSubmitted(true)
        saveOperation(storageKey, { ...operation.current, payment_id: payment.id })
        const result = await refundTossPayment(workspace, payment.id, operation.current)
        if (['completed', 'failed'].includes(result.status)) saveOperation(storageKey, null)
        setMessage(
          result.status === 'completed'
            ? '환불 관리 작업이 완료되었습니다.'
            : result.status === 'failed'
              ? '환불이 거절되었습니다. 관리 작업 내역을 확인하세요.'
              : null,
        )
        onChanged()
      } else if (!admin) {
        const { receipt_url: url } = await getTossReceipt(workspace, payment.id)
        if (new URL(url).protocol !== 'https:') throw new Error('Invalid receipt URL')
        setReceipt(url)
      }
    } catch (cause) {
      let confirmedAbsent = false
      if (admin && cause instanceof ApiError && cause.status === 409 && operation.current) {
        try {
          const result = await getAdminBillingRequest(workspace, operation.current.operation_id)
          if (['completed', 'failed'].includes(result.status)) {
            saveOperation(storageKey, null)
            setMessage(
              result.status === 'completed'
                ? '환불 관리 작업이 완료되었습니다.'
                : '환불이 거절되었습니다. 관리 작업 내역을 확인하세요.',
            )
            onChanged()
            return
          }
        } catch (lookupError) {
          confirmedAbsent = lookupError instanceof ApiError && lookupError.status === 404
        }
        onChanged()
      }
      if (admin && cause instanceof ApiError && (cause.status === 422 || confirmedAbsent)) {
        saveOperation(storageKey, null)
        operation.current = null
        setSubmitted(false)
        setAccount(null)
        onChanged()
      }
      setError(
        cause instanceof ApiError
          ? cause.message
          : '결과를 확인하지 못했습니다. 같은 요청으로 다시 확인하세요.',
      )
    } finally {
      setBusy(false)
    }
  }
  if (!['toss_test', 'toss_live'].includes(payment.provider ?? '') || payment.status === 'failed')
    return null
  const locked = busy || submitted
  return (
    <div className="mt-2 space-y-2">
      {admin ? (
        payment.status !== 'refunded' && (
          <details>
            <summary className="cursor-pointer">
              {live ? '실결제 환불 관리' : '테스트 환불'}
            </summary>
            <p>
              원 결제 {payment.amount.toLocaleString('ko-KR')}원 · 환불 가능{' '}
              {(payment.amount - (payment.refunded_amount ?? 0)).toLocaleString('ko-KR')}원
            </p>
            {submitted && !message && <p role="status">이전 환불 요청의 결과 확인이 필요합니다.</p>}
            {submitted && !account && !message && (
              <Button disabled={busy} onClick={() => void act()}>
                같은 환불 요청 결과 확인
              </Button>
            )}
            {!account ? (
              <Button disabled={busy || pending} onClick={() => void review()}>
                현재 크레딧 확인
              </Button>
            ) : (
              <>
                <p>
                  보유 {account.balance} · 확보 {account.reserved} · 가용 {account.available}크레딧
                </p>
                <label className="block">
                  환불 금액 (원)
                  <input
                    type="number"
                    min="1"
                    max={payment.amount - (payment.refunded_amount ?? 0)}
                    value={amount}
                    onChange={(e) => setAmount(Number(e.target.value))}
                    disabled={locked}
                  />
                </label>
                <label className="block">
                  회수 크레딧
                  <input
                    type="number"
                    min="0"
                    max={account.available}
                    step="0.1"
                    value={credits}
                    onChange={(e) => setCredits(Number(e.target.value))}
                    disabled={locked}
                  />
                </label>
                <label className="block">
                  <input
                    type="checkbox"
                    checked={stop}
                    onChange={(e) => setStop(e.target.checked)}
                    disabled={locked}
                  />
                  다음 갱신 중단
                </label>
                <label className="block">
                  처리 사유
                  <input
                    maxLength={200}
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                    disabled={locked}
                  />
                </label>
                <label className="block">
                  <input
                    type="checkbox"
                    checked={confirmed}
                    onChange={(e) => setConfirmed(e.target.checked)}
                    disabled={locked}
                  />
                  환불액, 회수량, 갱신 중단 여부와 사유를 확인했습니다.
                </label>
                <Button
                  disabled={
                    busy ||
                    (pending && !submitted) ||
                    !!message ||
                    !confirmed ||
                    !reason.trim() ||
                    !Number.isInteger(amount) ||
                    amount <= 0 ||
                    amount > payment.amount - (payment.refunded_amount ?? 0) ||
                    !Number.isFinite(credits) ||
                    credits < 0 ||
                    credits > account.available
                  }
                  onClick={() => void act()}
                >
                  {submitted
                    ? '같은 환불 요청 결과 확인'
                    : live
                      ? '실결제 환불 실행'
                      : '테스트 환불 실행'}
                </Button>
              </>
            )}
          </details>
        )
      ) : receipt ? (
        <a href={receipt} target="_blank" rel="noopener noreferrer" className="underline">
          {live ? '카드 영수증 열기' : '토스 테스트 영수증 열기'}
        </a>
      ) : (
        <Button variant="ghost" disabled={busy} onClick={() => void act()}>
          {live ? '카드 영수증 확인' : '테스트 영수증 확인'}
        </Button>
      )}
      {message && <p role="status">{message}</p>}
      {error && <p role="alert">{error}</p>}
    </div>
  )
}
