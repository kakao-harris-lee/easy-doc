import { PaymentActions } from './PaymentActions'
import { openTossBilling } from '../../billing/toss'
import { useEffect, useRef, useState } from 'react'
import { ApiError } from '../../api/client'
import {
  cancelSubscription,
  checkoutSubscription,
  getSubscription,
  type SubscriptionOverview,
  type TestSubscriptionPlanId,
} from '../../api/subscriptions'
import { Button } from '../ui/Button'
import { TargetPlanCatalog } from './TargetPlanCatalog'

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

  async function act(cancel: boolean, plan: TestSubscriptionPlanId = 'start', fail = false) {
    setBusy(true)
    setError(null)
    setMessage(null)
    try {
      if (!cancel && view?.toss_enabled) {
        await openTossBilling(workspaceId, plan, fail)
        return
      }
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

  async function changeCard() {
    if (!view?.subscription) return
    setBusy(true)
    setError(null)
    try {
      if (view.subscription.status === 'active') setView(await cancelSubscription(workspaceId))
      await openTossBilling(workspaceId, 'start', false)
    } catch (cause) {
      setError(
        cause instanceof ApiError
          ? cause.message
          : '카드 변경을 완료하지 못했습니다. 새 카드로 구독 재개를 눌러 다시 시도하세요.',
      )
    } finally {
      setBusy(false)
    }
  }

  const current = view?.subscription
  const active = current?.status === 'active' || current?.status === 'canceling'
  return (
    <div className="contents">
      <section
        aria-label="월 구독 플랜"
        className="order-1 rounded-xl border border-border bg-card p-5"
      >
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
                : view.mock_enabled || view.toss_enabled
                  ? '선택한 플랜 없음'
                  : '구독 서비스 준비 중'}
            </p>
            {(view.mock_enabled || view.toss_enabled || current) && (
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
            {!(view.mock_enabled || view.toss_enabled) && !current && (
              <p className="mt-2 text-sm text-muted-foreground">
                현재는 무료 파일럿으로 운영하며, 월 구독 결제를 받지 않습니다.
              </p>
            )}
            {!admin && (view.mock_enabled || view.toss_enabled) && current?.status === 'active' && (
              <Button
                variant="ghost"
                className="mt-4"
                disabled={busy}
                onClick={() => void act(true)}
              >
                구독 갱신 중단
              </Button>
            )}
            {!admin && view.toss_enabled && !active && view.billing_state === 'authorizing' && (
              <Button
                variant="ghost"
                className="mt-4"
                disabled={busy || view.pending}
                onClick={() => void act(true)}
              >
                카드 등록 취소
              </Button>
            )}
            {!admin && view.toss_enabled && active && (
              <Button
                variant="ghost"
                className="mt-4"
                disabled={busy || view.pending}
                onClick={() => void changeCard()}
              >
                {current.status === 'canceling' ? '새 카드로 구독 재개' : '결제 카드 변경'}
              </Button>
            )}
            {view.billing_state === 'issuing' && (
              <p role="status" className="mt-3 text-sm">
                카드 등록 결과 확인 중입니다. 잠시 후 새로고침해 주세요.
              </p>
            )}
            {view.pending && (
              <p role="status" className="mt-3 text-sm">
                결제 결과 확인 중입니다. 잠시 후 새로고침해 주세요.
              </p>
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
                      {
                        {
                          paid: '성공',
                          failed: '실패',
                          partially_refunded: '부분 환불',
                          refunded: '환불 완료',
                        }[payment.status]
                      }
                      <PaymentActions
                        workspace={workspaceId}
                        payment={payment}
                        admin={admin}
                        pending={view.pending}
                        onChanged={setView}
                      />
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
      {!admin && (
        <TargetPlanCatalog
          workspaceId={workspaceId}
          checkoutEnabled={Boolean(
            (view?.mock_enabled || view?.toss_enabled) &&
            view?.plans.some((availablePlan) => availablePlan.id === 'start'),
          )}
          tossEnabled={Boolean(view?.toss_enabled)}
          pending={Boolean(view?.pending)}
          busy={busy}
          activePlanId={active ? (current?.plan_id ?? null) : null}
          className="order-3 md:col-span-2"
          onCheckout={(selectedPlan, simulateFailure) => {
            if (selectedPlan === 'start') void act(false, selectedPlan, simulateFailure)
          }}
        />
      )}
    </div>
  )
}
