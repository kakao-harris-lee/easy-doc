import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'

import { TargetPlanCatalog } from './TargetPlanCatalog'

it('실제 월 플랜 가격과 제공량을 표시한다', () => {
  render(<TargetPlanCatalog workspaceId={null} />)
  expect(screen.queryByText('목표 플랜 구성')).not.toBeInTheDocument()

  const catalog = screen.getByRole('list', { name: '월 플랜 선택' })
  expect(within(catalog).getByText('Start')).toBeInTheDocument()
  expect(within(catalog).getByText('99,000원')).toBeInTheDocument()
  expect(within(catalog).getByText(/50크레딧/)).toBeInTheDocument()
  expect(within(catalog).getByText('Basic')).toBeInTheDocument()
  expect(within(catalog).getByText('190,000원')).toBeInTheDocument()
  expect(within(catalog).getByText(/200크레딧/)).toBeInTheDocument()
  expect(within(catalog).getByText('Pro')).toBeInTheDocument()
  expect(within(catalog).getByText('599,000원')).toBeInTheDocument()
  expect(within(catalog).getByText(/1,000크레딧/)).toBeInTheDocument()
  for (const plan of ['Start', 'Basic', 'Pro']) {
    const card = screen.getByRole('radio', { name: plan }).closest('li')
    expect(card).not.toBeNull()
    expect(
      within(card!)
        .getAllByRole('listitem')
        .map((item) => item.textContent),
    ).toEqual(['텍스트 변환', '쉬운 말 사전 추천', 'DOCX, HWPX, TEXT 내려받기'])
  }
  expect(screen.getByText(/재변환도 대상 원문 분량만큼 이용량에 포함/)).toBeInTheDocument()
})

it('live catalog contains only Start with the existing price and credits', () => {
  render(<TargetPlanCatalog workspaceId="w1" live checkoutEnabled tossEnabled />)
  expect(screen.getByRole('radio', { name: 'Start' })).toBeInTheDocument()
  expect(screen.queryByRole('radio', { name: 'Basic' })).not.toBeInTheDocument()
  expect(screen.queryByRole('radio', { name: 'Pro' })).not.toBeInTheDocument()
  expect(screen.queryByText(/실제 금액은 청구되지 않습니다/)).not.toBeInTheDocument()
})

// 결제대행 심사는 상품명·제공 내용·금액·제공 기간·자동갱신·해지 방법을 한 화면에서 읽을 수 있어야 한다.
const BILLING_DISCLOSURE: [string, string][] = [
  ['상품명', 'Start 월 정기결제'],
  ['제공 내용', '매월 50크레딧 (약 25페이지 상당)'],
  ['결제 금액', '월 99,000원 (VAT 포함)'],
  ['제공 기간', '1개월 (결제 승인일부터), 매월 자동결제'],
  [
    '해지 방법',
    '이 사용량 화면의 「구독 갱신 중단」 버튼으로 언제든 다음 결제를 멈출 수 있고, 이미 결제한 기간은 끝까지 이용할 수 있습니다.',
  ],
]

it('Start 정기결제의 상품·금액·제공 기간·해지 방법을 항목별로 고지한다', () => {
  render(<TargetPlanCatalog workspaceId="w1" live checkoutEnabled tossEnabled />)

  const disclosure = screen.getByLabelText('Start 월 정기결제 안내')
  for (const [term, description] of BILLING_DISCLOSURE) {
    const dt = within(disclosure).getByText(term)
    expect(dt.tagName).toBe('DT')
    expect(dt.nextElementSibling).toHaveTextContent(description)
  }

  const refund = within(disclosure).getByRole('link', { name: '이용약관 제9조(환불) 보기' })
  expect(refund).toHaveAttribute('href', '/terms')
  expect(refund.closest('dd')?.previousElementSibling).toHaveTextContent('환불')

  expect(screen.queryByText(/Start는 월 99,000원에 50크레딧을 제공합니다/)).not.toBeInTheDocument()
  expect(
    screen.queryByText(/갱신 중단 시 이미 결제한 이용 기간은 유지됩니다/),
  ).not.toBeInTheDocument()
  expect(screen.queryByText('이용약관 및 환불 안내')).not.toBeInTheDocument()
})

it('결제를 아직 열지 않은 화면에서도 같은 정기결제 고지를 보여준다', () => {
  render(<TargetPlanCatalog workspaceId={null} />)
  const disclosure = screen.getByLabelText('Start 월 정기결제 안내')
  expect(within(disclosure).getByText('결제 금액').nextElementSibling).toHaveTextContent(
    '월 99,000원 (VAT 포함)',
  )
})

it('shows the fixed paid effective date beside live consent', () => {
  render(<TargetPlanCatalog workspaceId="w1" live checkoutEnabled tossEnabled />)
  expect(screen.getByText('유료 서비스 적용일: 2026년 11월 1일')).toBeInTheDocument()
  expect(screen.getByLabelText(/금액, 제공량, 매월 자동결제/)).toBeInTheDocument()
})

it('모든 플랜을 선택할 수 있지만 Start만 구독을 시작한다', async () => {
  const user = userEvent.setup()
  const checkout = vi.fn()
  render(
    <TargetPlanCatalog
      workspaceId="w1"
      checkoutEnabled
      tossEnabled={false}
      pending={false}
      busy={false}
      activePlanId={null}
      onCheckout={checkout}
    />,
  )

  await user.click(screen.getByRole('radio', { name: 'Basic' }))
  expect(screen.getByRole('button', { name: 'Basic 결제 준비 중' })).toBeDisabled()
  await user.click(screen.getByRole('radio', { name: 'Pro' }))
  expect(screen.getByRole('button', { name: 'Pro 결제 준비 중' })).toBeDisabled()
  await user.click(screen.getByRole('radio', { name: 'Start' }))
  await user.click(screen.getByRole('button', { name: 'Start 월 99,000원 정기결제 시작' }))
  expect(checkout).toHaveBeenCalledOnce()
})
