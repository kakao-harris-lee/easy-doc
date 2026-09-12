// 목표 상품 구성 (연구안). 실제 판매 가격이 아니다.
// 출처: docs/plans/2026-08-24-v0-ui-ux-redesign-plan.md §6.2 제안 요금표
//       (docs/plans/2026-08-24-v0-ui-design-prompts.md Prompt 2에도 동일한 표가 있다.)
//
// 값을 바꾸려면 위 연구 문서를 먼저 갱신한 뒤 이 파일을 맞춰야 한다.
// 여기 있는 크레딧/가격은 SubscriptionCard가 API에서 받아오는 테스트 플랜
// (Starter 50 · Pro 200, 1,000원/3,000원)과 다른 목표 값이며 서로 섞지 않는다.

export interface TargetPlanFeature {
  label: string
  /** 아직 제공되지 않는 기능이면 상태를 함께 표시한다. */
  status?: '도입 예정' | '문의'
}

export interface TargetPlan {
  id: 'basic' | 'pro' | 'enterprise'
  name: string
  /** 타겟 고객. */
  audience: string
  /** 월 제공 크레딧. Enterprise는 계약별 합의라 고정값이 없다. */
  monthlyCredits: number | null
  /** 크레딧 대비 페이지 상당량 설명 (비교용). */
  pageEquivalent: string
  /** 월 구독 정가 (원). */
  monthlyPriceWon: number | null
  /** 월 구독 정가 안내 문구 (Enterprise처럼 하한만 있는 경우). */
  monthlyPriceLabel: string
  /** 연 결제 총액 (원). 월 정가 × 12 × 80%. */
  annualPriceWon: number | null
  /** 연 결제 월 환산액 (원). */
  annualMonthlyEquivalentWon: number | null
  /** 연 결제 안내 문구 (Enterprise는 맞춤 견적). */
  annualPriceLabel: string
  features: TargetPlanFeature[]
}

export const TARGET_PLANS: TargetPlan[] = [
  {
    id: 'basic',
    name: 'Basic',
    audience: '소규모 복지관, 학교',
    monthlyCredits: 200,
    pageEquivalent: '100페이지 상당',
    monthlyPriceWon: 99_000,
    monthlyPriceLabel: '99,000원',
    annualPriceWon: 950_400,
    annualMonthlyEquivalentWon: 79_200,
    annualPriceLabel: '950,400원 (월 환산 79,200원)',
    features: [{ label: '텍스트 변환' }, { label: '쉬운 말 사전 추천' }],
  },
  {
    id: 'pro',
    name: 'Pro',
    audience: '주민센터, 구청 실무 부서',
    monthlyCredits: 1_000,
    pageEquivalent: '500페이지 상당',
    monthlyPriceWon: 290_000,
    monthlyPriceLabel: '290,000원',
    annualPriceWon: 2_784_000,
    annualMonthlyEquivalentWon: 232_000,
    annualPriceLabel: '2,784,000원 (월 환산 232,000원)',
    features: [
      { label: '텍스트 변환' },
      { label: '쉬운 말 사전 추천' },
      { label: 'HWPX 내려받기' },
    ],
  },
  {
    id: 'enterprise',
    name: 'Enterprise',
    audience: '시·도 단위 통합 부서',
    monthlyCredits: null,
    pageEquivalent: '무제한* 또는 계약별 합의',
    monthlyPriceWon: null,
    monthlyPriceLabel: '월 1,000,000원 이상',
    annualPriceWon: null,
    annualMonthlyEquivalentWon: null,
    annualPriceLabel: '맞춤 견적',
    features: [
      { label: '텍스트 변환' },
      { label: '쉬운 말 사전 추천' },
      { label: 'HWPX 내려받기' },
      { label: '부서 계정 공유', status: '도입 예정' },
      { label: 'CSAP 대응 옵션', status: '문의' },
    ],
  },
]
