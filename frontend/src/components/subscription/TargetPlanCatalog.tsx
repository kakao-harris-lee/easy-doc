import { Badge } from '../ui/Badge'
import { TARGET_PLANS } from '../../content/plans/targetPlans'

// 목표 플랜 구성 (연구안). docs/plans/2026-08-24-v0-ui-ux-redesign-plan.md §6.2 참고.
// 실제 결제와 연결되지 않은 순수 프레젠테이션 컴포넌트다. 체크아웃/문의 버튼 없음.
export function TargetPlanCatalog() {
  return (
    <section
      aria-labelledby="target-plan-heading"
      className="rounded-xl border border-border bg-card p-5"
    >
      <div className="flex flex-wrap items-center gap-2">
        <h2 id="target-plan-heading" className="text-sm font-semibold text-muted-foreground">
          목표 플랜 구성
        </h2>
        <Badge tone="warning">연구안 · 판매 가격이 아닙니다.</Badge>
      </div>
      <p className="mt-2 text-sm text-muted-foreground">
        아직 검토 중인 상품 구성이며, 위 테스트 플랜과는 별개의 값입니다.
      </p>
      <ul aria-label="목표 플랜 구성" className="mt-4 grid gap-3 sm:grid-cols-3">
        {TARGET_PLANS.map((plan) => (
          <li key={plan.id} className="rounded-lg border border-border p-3 text-sm">
            <p className="font-medium">{plan.name}</p>
            <p className="mt-1 text-xs text-muted-foreground">{plan.audience}</p>
            <p className="mt-2 text-sm">
              {plan.monthlyCredits === null
                ? plan.pageEquivalent
                : `월 ${plan.monthlyCredits.toLocaleString('ko-KR')}크레딧 · ${plan.pageEquivalent}`}
            </p>
            <p className="mt-2 text-base font-semibold">{plan.monthlyPriceLabel}</p>
            <p className="mt-1 text-xs text-muted-foreground">연 결제 {plan.annualPriceLabel}</p>
            <ul className="mt-3 space-y-1 text-xs text-muted-foreground">
              {plan.features.map((feature) => (
                <li key={feature.label}>
                  <span>{feature.label}</span>
                  {feature.status && (
                    <span className="ml-1 font-medium text-primary">({feature.status})</span>
                  )}
                </li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
      <ul className="mt-4 space-y-1 border-t border-border pt-3 text-xs text-muted-foreground">
        <li>페이지 상당량은 비교용입니다 (1페이지 = 2,000자 = 2크레딧).</li>
        <li>연 결제 20% 할인은 연구안 계산값이며 실제 결제 정책이 아닙니다.</li>
        <li>연 결제·Enterprise 견적은 아직 제공하지 않습니다.</li>
        <li>부가세·이월·초과 사용·환불: 정책 확정 필요.</li>
      </ul>
    </section>
  )
}
