import { useState } from 'react'

import { TARGET_PLANS, type TargetPlan } from '../../content/plans/targetPlans'
import { Badge } from '../ui/Badge'
import { Button } from '../ui/Button'
import { formatCredits } from '../../lib/credits'

interface TargetPlanCatalogProps {
  workspaceId: string | null
  checkoutEnabled?: boolean
  tossEnabled?: boolean
  pending?: boolean
  busy?: boolean
  activePlanId?: string | null
  className?: string
  onCheckout?: (planId: TargetPlan['id'], simulateFailure: boolean) => void
}

export function TargetPlanCatalog({
  workspaceId,
  checkoutEnabled = false,
  tossEnabled = false,
  pending = false,
  busy = false,
  activePlanId = null,
  className = '',
  onCheckout,
}: TargetPlanCatalogProps) {
  const [selectedPlanId, setSelectedPlanId] = useState<TargetPlan['id']>('start')
  const [simulateFailure, setSimulateFailure] = useState(false)
  const selectedPlanName =
    TARGET_PLANS.find((plan) => plan.id === selectedPlanId)?.name ?? selectedPlanId
  const startCanCheckout =
    selectedPlanId === 'start' &&
    workspaceId !== null &&
    checkoutEnabled &&
    activePlanId === null &&
    !pending
  const buttonLabel =
    selectedPlanId === 'start'
      ? tossEnabled
        ? 'Start 토스 테스트 카드 등록'
        : 'Start 테스트 결제'
      : `${selectedPlanName} 결제 준비 중`

  return (
    <section
      aria-label="플랜 선택 및 결제"
      className={`rounded-xl border border-border bg-card p-5 ${className}`.trim()}
    >
      <p className="text-sm text-muted-foreground">
        플랜을 선택해 월 제공량과 결제 금액을 확인하세요.
      </p>
      <ul aria-label="월 플랜 선택" className="mt-4 grid gap-3 sm:grid-cols-3">
        {TARGET_PLANS.map((plan) => {
          const selected = selectedPlanId === plan.id
          const active = activePlanId === plan.id
          return (
            <li
              key={plan.id}
              className={`rounded-lg border p-3 text-sm ${
                selected ? 'border-primary ring-2 ring-primary/20' : 'border-border'
              }`}
            >
              <label className="flex cursor-pointer items-start gap-3">
                <input
                  type="radio"
                  name="target-plan"
                  className="mt-1"
                  checked={selected}
                  disabled={busy || pending}
                  aria-label={plan.name}
                  onChange={() => setSelectedPlanId(plan.id)}
                />
                <span className="min-w-0 flex-1">
                  <span className="flex flex-wrap items-center gap-2 font-medium">
                    {plan.name}
                    {plan.id === 'start' && <Badge tone="success">테스트 결제 가능</Badge>}
                    {plan.id !== 'start' && <Badge tone="neutral">결제 준비 중</Badge>}
                    {active && <Badge tone="info">이용 중</Badge>}
                  </span>
                  <span className="mt-1 block text-xs text-muted-foreground">{plan.audience}</span>
                  <span className="mt-2 block">
                    월 {formatCredits(plan.monthlyCredits)}크레딧 · {plan.pageEquivalent}
                  </span>
                  <span className="mt-2 block text-base font-semibold">
                    {plan.monthlyPriceLabel}
                  </span>
                </span>
              </label>
              <ul className="mt-3 space-y-1 text-xs text-muted-foreground">
                {plan.features.map((feature) => (
                  <li key={feature.label}>{feature.label}</li>
                ))}
              </ul>
            </li>
          )
        })}
      </ul>
      <div className="mt-4 border-t border-border pt-4">
        <p className="text-xs text-muted-foreground">
          공백 포함 원문 100자마다 0.1크레딧으로 계산하며, 1자라도 남으면 올림합니다. 재변환도 대상
          원문 분량만큼 이용량에 포함됩니다. 남은 이용량은 다음 결제 주기로 이월되지 않습니다.
        </p>
        {selectedPlanId === 'start' && checkoutEnabled && activePlanId === null && (
          <label className="mt-3 flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={simulateFailure}
              disabled={busy}
              onChange={(event) => setSimulateFailure(event.target.checked)}
            />
            결제 실패 테스트
          </label>
        )}
        <Button
          className="mt-3"
          disabled={!startCanCheckout || busy}
          onClick={() => onCheckout?.(selectedPlanId, simulateFailure)}
        >
          {busy ? '처리 중…' : buttonLabel}
        </Button>
        {workspaceId === null && (
          <p className="mt-2 text-xs text-muted-foreground">
            작업 공간을 선택하면 결제할 수 있습니다.
          </p>
        )}
      </div>
    </section>
  )
}
