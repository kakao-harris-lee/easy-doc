import { useEffect, useRef, useState } from 'react'
import { ApiError } from '../../api/client'
import {
  cancelSubscription,
  checkoutSubscription,
  getSubscription,
  type SubscriptionOverview,
} from '../../api/subscriptions'
import { Button } from '../ui/Button'

const won = (amount: number) => `${amount.toLocaleString('ko-KR')}원`
const date = (value: string) => new Date(value).toLocaleDateString('ko-KR')

export function SubscriptionCard({
  workspaceId,
  onChanged,
  admin = false,
}: {
  workspaceId: string
  onChanged?: () => void
  admin?: boolean
}) {
  const [view, setView] = useState<SubscriptionOverview | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [selecting, setSelecting] = useState(false)
  const [plan, setPlan] = useState('starter')
  const [fail, setFail] = useState(false)
  const [busy, setBusy] = useState(false)
  const order = useRef<{ id: string; plan: string; fail: boolean } | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    getSubscription(workspaceId, controller.signal, admin)
      .then((data) => {
        if (!controller.signal.aborted) setView(data)
      })
      .catch((cause: unknown) => {
        if (!controller.signal.aborted)
          setError(cause instanceof ApiError ? cause.message : '구독 정보를 불러오지 못했습니다.')
      })
    return () => controller.abort()
  }, [workspaceId, admin])

  async function act(cancel: boolean) {
    setBusy(true)
    setError(null)
    setMessage(null)
    try {
      if (
        !cancel &&
        (order.current === null || order.current.plan !== plan || order.current.fail !== fail)
      ) {
        order.current = { id: crypto.randomUUID(), plan, fail }
      }
      const result = cancel
        ? await cancelSubscription(workspaceId)
        : await checkoutSubscription(workspaceId, plan, order.current!.id, fail)
      setView(result)
      const declined =
        !cancel &&
        result.payments.find((payment) => payment.id === order.current?.id)?.status === 'failed'
      setMessage(
        cancel
          ? '현재 이용 기간이 끝나면 구독이 종료됩니다.'
          : declined
            ? '테스트 결제가 거절되었습니다. 이용량은 그대로입니다.'
            : '테스트 구독이 적용되었습니다. 실제로 청구되지 않습니다.',
      )
      order.current = null
      if (!declined) setSelecting(false)
      onChanged?.()
    } catch (cause) {
      setError(
        cause instanceof ApiError
          ? cause.message
          : '처리 결과를 확인하지 못했습니다. 같은 요청으로 다시 시도해 주세요.',
      )
    } finally {
      setBusy(false)
    }
  }

  const current = view?.subscription
  const active = current?.status === 'active' || current?.status === 'canceling'
  return (
    <section aria-label="월 구독 플랜" className="rounded-xl border border-border bg-card p-5">
      <h2 className="text-sm font-semibold text-muted-foreground">월 구독 플랜</h2>
      {error && (
        <p role="alert" className="mt-3 text-sm text-danger">
          {error}
        </p>
      )}
      {message && (
        <p role="status" className="mt-3 text-sm">
          {message}
        </p>
      )}
      {view === null ? (
        !error && (
          <p role="status" className="mt-3 text-sm">
            구독 정보를 불러오는 중입니다…
          </p>
        )
      ) : (
        <>
          <p className="mt-3 text-2xl font-bold">
            {active
              ? (view.plans.find((item) => item.id === current.plan_id)?.name ?? current.plan_id)
              : view.mock_enabled
                ? '선택한 플랜 없음'
                : '구독 서비스 준비 중'}
          </p>
          {(view.mock_enabled || current) && (
            <p className="mt-2 text-sm font-medium text-primary">테스트 결제 · 실제 청구 없음</p>
          )}
          {active && (
            <>
              <p className="mt-2 text-sm">
                월 {won(current.monthly_price)} · {current.allowance}크레딧
              </p>
              <p className="mt-1 text-sm text-muted-foreground">
                {date(current.cycle_ends_at)}{' '}
                {current.status === 'canceling' ? '구독 종료 예정' : '다음 테스트 결제'}
              </p>
            </>
          )}
          {current?.status === 'past_due' && (
            <p className="mt-2 text-sm text-danger">갱신 결제가 실패해 구독이 중단되었습니다.</p>
          )}
          {current?.status === 'expired' && (
            <p className="mt-2 text-sm text-muted-foreground">이전 구독이 종료되었습니다.</p>
          )}
          {!view.mock_enabled && !current && (
            <p className="mt-2 text-sm text-muted-foreground">
              현재는 무료 파일럿으로 운영하며, 월 구독 결제를 받지 않습니다.
            </p>
          )}
          {view.plans.length > 0 && (
            <div className="mt-4 border-t border-border pt-4">
              <p className="text-xs font-medium text-muted-foreground">테스트 구성 · 동작 확인용</p>
              <ul aria-label="테스트 플랜 구성" className="mt-2 space-y-2">
                {view.plans.map((item) => {
                  const isCurrentPlan = active && current?.plan_id === item.id
                  const characters = item.allowance * 1000
                  const charLabel =
                    characters % 10000 === 0
                      ? `${characters / 10000}만 자`
                      : `${characters.toLocaleString('ko-KR')}자`
                  const details = (
                    <>
                      <span className="flex items-center gap-2 font-medium">
                        {item.name}
                        {isCurrentPlan && (
                          <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-semibold text-primary">
                            이용 중
                          </span>
                        )}
                      </span>
                      <span className="mt-1 block text-sm text-muted-foreground">
                        월 {item.allowance}크레딧 · {charLabel}
                      </span>
                      {view.mock_enabled && (
                        <span className="mt-1 block text-xs text-muted-foreground">
                          표시가 {won(item.monthly_price)} · 테스트용
                        </span>
                      )}
                    </>
                  )
                  return (
                    <li key={item.id} className="rounded-lg border border-border p-3">
                      {selecting ? (
                        <label className="flex cursor-pointer items-start gap-3">
                          <input
                            type="radio"
                            name="plan"
                            className="mt-1"
                            checked={plan === item.id}
                            disabled={busy}
                            aria-label={item.name}
                            onChange={() => setPlan(item.id)}
                          />
                          <span>{details}</span>
                        </label>
                      ) : (
                        details
                      )}
                    </li>
                  )
                })}
              </ul>
              <p className="mt-2 text-xs text-muted-foreground">
                Enterprise·연간 결제는 아직 제공하지 않습니다.
              </p>
            </div>
          )}
          {!admin && view.mock_enabled && !active && (
            <Button className="mt-4" onClick={() => setSelecting(!selecting)}>
              플랜 선택
            </Button>
          )}
          {!admin && view.mock_enabled && current?.status === 'active' && (
            <Button variant="ghost" className="mt-4" disabled={busy} onClick={() => void act(true)}>
              구독 갱신 중단
            </Button>
          )}
          {selecting && (
            <div className="mt-4 space-y-3 border-t border-border pt-4">
              <p className="text-xs text-muted-foreground">
                표시 가격은 테스트용입니다. 매월 제공량이 새로 설정되며 남은 수량은 이월되지
                않습니다.
              </p>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={fail}
                  disabled={busy}
                  onChange={(event) => setFail(event.target.checked)}
                />
                결제 실패 테스트
              </label>
              <Button disabled={busy} onClick={() => void act(false)}>
                {busy ? '처리 중…' : '테스트 결제'}
              </Button>
            </div>
          )}
          {view.payments.length > 0 && (
            <details className="mt-4 border-t border-border pt-3 text-sm">
              <summary className="cursor-pointer">테스트 결제 내역</summary>
              <p className="mt-2 text-xs text-muted-foreground">
                테스트 기록이며 카드 영수증이나 세무 증빙이 아닙니다.
              </p>
              <ul className="mt-2 space-y-2">
                {view.payments.map((payment) => (
                  <li key={payment.id}>
                    {date(payment.created_at)} · {won(payment.amount)} ·{' '}
                    {payment.status === 'paid' ? '성공' : '실패'}
                  </li>
                ))}
              </ul>
            </details>
          )}
          <div className="mt-5 border-t border-border pt-4 text-sm text-muted-foreground">
            <p>유료 서비스는 신용·체크카드로 결제합니다.</p>
            <p className="mt-1">결제 증빙은 카드 영수증(매출전표)을 사용합니다.</p>
          </div>
        </>
      )}
    </section>
  )
}
