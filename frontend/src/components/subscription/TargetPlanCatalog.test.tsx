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

it('모든 플랜을 선택할 수 있지만 Start만 테스트 결제를 연다', async () => {
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
  await user.click(screen.getByRole('button', { name: 'Start 테스트 결제' }))
  expect(checkout).toHaveBeenCalledOnce()
})
