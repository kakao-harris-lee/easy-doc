// 실제 월 구독 가격표. 결제 API는 현재 Start 테스트 결제만 허용한다.
// 공백 포함 원문 100자마다 0.1크레딧으로 계산하며, 최초 변환과 재변환 모두 이용량에 포함한다.

export interface TargetPlanFeature {
  label: string
}

export interface TargetPlan {
  id: 'start' | 'basic' | 'pro'
  name: string
  audience: string
  monthlyCredits: number
  pageEquivalent: string
  monthlyPriceWon: number
  monthlyPriceLabel: string
  features: TargetPlanFeature[]
}

const COMMON_FEATURES: TargetPlanFeature[] = [
  { label: '텍스트 변환' },
  { label: '쉬운 말 사전 추천' },
  { label: 'DOCX, HWPX, TEXT 내려받기' },
]

export const TARGET_PLANS: TargetPlan[] = [
  {
    id: 'start',
    name: 'Start',
    audience: '소규모 팀의 월간 문서 변환',
    monthlyCredits: 50,
    pageEquivalent: '약 25페이지 상당',
    monthlyPriceWon: 99_000,
    monthlyPriceLabel: '99,000원',
    features: COMMON_FEATURES,
  },
  {
    id: 'basic',
    name: 'Basic',
    audience: '복지관·학교·실무 부서',
    monthlyCredits: 200,
    pageEquivalent: '약 100페이지 상당',
    monthlyPriceWon: 190_000,
    monthlyPriceLabel: '190,000원',
    features: COMMON_FEATURES,
  },
  {
    id: 'pro',
    name: 'Pro',
    audience: '기관 단위의 상시 문서 변환',
    monthlyCredits: 1_000,
    pageEquivalent: '약 500페이지 상당',
    monthlyPriceWon: 599_000,
    monthlyPriceLabel: '599,000원',
    features: COMMON_FEATURES,
  },
]
