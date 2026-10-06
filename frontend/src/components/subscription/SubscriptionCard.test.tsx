import { openTossBilling } from '../../billing/toss'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import {
  cancelSubscription,
  checkoutSubscription,
  getSubscription,
  type SubscriptionOverview,
} from '../../api/subscriptions'
import { SubscriptionCard } from './SubscriptionCard'

vi.mock('../../billing/toss', () => ({ openTossBilling: vi.fn() }))

vi.mock('../../api/subscriptions', () => ({
  getSubscription: vi.fn(),
  checkoutSubscription: vi.fn(),
  cancelSubscription: vi.fn(),
}))
const available: SubscriptionOverview = {
  mock_enabled: true,
  plans: [{ id: 'start', name: 'Start', allowance: 50, monthly_price: 99_000 }],
  subscription: null,
  payments: [],
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getSubscription).mockResolvedValue(available)
})
it('removes the old test catalog and shows the selectable target plans', async () => {
  render(<SubscriptionCard workspaceId="w1" />)
  await screen.findByText('선택한 플랜 없음')
  expect(screen.queryByText('테스트 구성 · 동작 확인용')).not.toBeInTheDocument()
  expect(screen.queryByRole('list', { name: '테스트 플랜 구성' })).not.toBeInTheDocument()
  expect(screen.queryByText('목표 플랜 구성')).not.toBeInTheDocument()
  const catalog = screen.getByRole('list', { name: '월 플랜 선택' })
  expect(within(catalog).getByText('Start')).toBeInTheDocument()
  expect(within(catalog).getByText('Basic')).toBeInTheDocument()
  expect(within(catalog).getByText('Pro')).toBeInTheDocument()
  expect(within(catalog).getByText(/월 50크레딧/)).toBeInTheDocument()
  expect(within(catalog).getByText(/월 200크레딧/)).toBeInTheDocument()
})
it('lets the user select Basic and Pro but does not open their checkout', async () => {
  const user = userEvent.setup()
  render(<SubscriptionCard workspaceId="w1" />)
  await user.click(await screen.findByRole('radio', { name: 'Basic' }))
  expect(screen.getByRole('button', { name: 'Basic 결제 준비 중' })).toBeDisabled()
  await user.click(screen.getByRole('radio', { name: 'Pro' }))
  expect(screen.getByRole('button', { name: 'Pro 결제 준비 중' })).toBeDisabled()
  expect(checkoutSubscription).not.toHaveBeenCalled()
})
it('uses the server plan and applies mock checkout before refreshing usage', async () => {
  const user = userEvent.setup(),
    changed = vi.fn()
  vi.mocked(checkoutSubscription).mockResolvedValue({
    ...available,
    subscription: {
      plan_id: 'start',
      allowance: 50,
      monthly_price: 99_000,
      status: 'active',
      cycle_ends_at: '2026-10-12T00:00:00Z',
    },
  })
  render(<SubscriptionCard workspaceId="w1" onChanged={changed} />)
  await user.click(await screen.findByRole('button', { name: 'Start 테스트 결제' }))
  expect(checkoutSubscription).toHaveBeenCalledWith('w1', 'start', expect.any(String), false)
  expect(changed).toHaveBeenCalledOnce()
  expect(await screen.findByRole('button', { name: '구독 갱신 중단' })).toBeInTheDocument()
})
it('retries an uncertain payment with the same order id', async () => {
  const user = userEvent.setup()
  vi.mocked(checkoutSubscription).mockRejectedValue(new Error('network'))
  render(<SubscriptionCard workspaceId="w1" />)
  await user.click(await screen.findByRole('button', { name: 'Start 테스트 결제' }))
  await screen.findByRole('alert')
  await user.click(screen.getByRole('button', { name: 'Start 테스트 결제' }))
  expect(vi.mocked(checkoutSubscription).mock.calls[0]?.[2]).toBe(
    vi.mocked(checkoutSubscription).mock.calls[1]?.[2],
  )
})
it('admin uses admin endpoint and does not show checkout controls', async () => {
  render(<SubscriptionCard workspaceId="w2" admin />)
  await screen.findByText('선택한 플랜 없음')
  expect(getSubscription).toHaveBeenCalledWith('w2', expect.any(AbortSignal), true)
  expect(screen.queryByRole('list', { name: '월 플랜 선택' })).not.toBeInTheDocument()
})

it('opens Toss billing for the selected plan when the server enables Toss test mode', async () => {
  vi.mocked(getSubscription).mockResolvedValue({
    ...available,
    mock_enabled: false,
    toss_enabled: true,
  })
  vi.mocked(openTossBilling).mockResolvedValue()
  const user = userEvent.setup()
  render(<SubscriptionCard workspaceId="w1" />)
  await user.click(await screen.findByRole('button', { name: 'Start 토스 테스트 카드 등록' }))
  expect(openTossBilling).toHaveBeenCalledWith('w1', 'start', false)
  expect(checkoutSubscription).not.toHaveBeenCalled()
})

it('requires explicit live consent, hides other plans and failure simulation', async () => {
  vi.mocked(getSubscription).mockResolvedValue({
    ...available,
    mock_enabled: false,
    toss_enabled: true,
    billing_environment: 'toss_live',
    purchase_enabled: true,
  })
  const user = userEvent.setup()
  render(<SubscriptionCard workspaceId="w1" />)
  const button = await screen.findByRole('button', { name: 'Start 월 99,000원 정기결제 시작' })
  expect(button).toBeDisabled()
  expect(screen.queryByRole('radio', { name: 'Basic' })).not.toBeInTheDocument()
  expect(screen.queryByLabelText('결제 실패 테스트')).not.toBeInTheDocument()
  await user.click(
    screen.getByLabelText(
      '금액, 제공량, 매월 자동결제, 갱신 중단 및 환불 조건을 확인하고 동의합니다.',
    ),
  )
  await user.click(button)
  expect(openTossBilling).toHaveBeenCalledWith('w1', 'start', false, 'purchase', 'start-monthly-v1')
})
it('replaces a card without canceling the active subscription first', async () => {
  vi.mocked(getSubscription).mockResolvedValue({
    ...available,
    mock_enabled: false,
    toss_enabled: true,
    billing_environment: 'toss_live',
    subscription: {
      plan_id: 'start',
      allowance: 50,
      monthly_price: 99000,
      status: 'active',
      cycle_ends_at: '2026-11-01T00:00:00Z',
    },
  })
  const user = userEvent.setup()
  render(<SubscriptionCard workspaceId="w1" />)
  await user.click(await screen.findByRole('button', { name: '결제 카드 변경' }))
  expect(cancelSubscription).not.toHaveBeenCalled()
  expect(openTossBilling).toHaveBeenCalledWith('w1', 'start', false, 'replace_card')
})
it('blocks new live purchases when manual review is required', async () => {
  vi.mocked(getSubscription).mockResolvedValue({
    ...available,
    mock_enabled: false,
    toss_enabled: true,
    billing_environment: 'toss_live',
    manual_review: true,
  })
  render(<SubscriptionCard workspaceId="w1" />)
  expect(
    await screen.findByRole('button', { name: 'Start 월 99,000원 정기결제 시작' }),
  ).toBeDisabled()
  expect(screen.getByText(/결제 결과를 관리자가 확인/)).toBeInTheDocument()
})

it('allows repair of a known declined renewal while purchases are closed', async () => {
  vi.mocked(getSubscription).mockResolvedValue({
    ...available,
    mock_enabled: false,
    toss_enabled: true,
    billing_environment: 'toss_live',
    purchase_enabled: false,
    pending: true,
    retry_at: '2026-11-01T01:00:00Z',
    subscription: {
      plan_id: 'start',
      allowance: 50,
      monthly_price: 99000,
      status: 'past_due',
      cycle_ends_at: '2026-11-01T00:00:00Z',
    },
  })
  const user = userEvent.setup()
  render(<SubscriptionCard workspaceId="w1" />)
  await user.click(await screen.findByRole('button', { name: '결제 카드 변경' }))
  expect(openTossBilling).toHaveBeenCalledWith('w1', 'start', false, 'replace_card')
  expect(cancelSubscription).not.toHaveBeenCalled()
})

it('immediately explains a needs_card state and offers safe card registration', async () => {
  vi.mocked(getSubscription).mockResolvedValue({
    ...available,
    mock_enabled: false,
    toss_enabled: true,
    billing_environment: 'toss_live',
    billing_state: 'needs_card',
    purchase_enabled: false,
    subscription: {
      plan_id: 'start',
      allowance: 50,
      monthly_price: 99000,
      status: 'past_due',
      cycle_ends_at: '2026-11-01T00:00:00Z',
    },
  })
  const user = userEvent.setup()
  render(<SubscriptionCard workspaceId="w1" />)
  expect(await screen.findByRole('alert')).toHaveTextContent('카드를 다시 등록해 주세요')
  await user.click(screen.getByRole('button', { name: '결제 카드 변경' }))
  expect(openTossBilling).toHaveBeenCalledWith('w1', 'start', false, 'replace_card')
  expect(cancelSubscription).not.toHaveBeenCalled()
})
