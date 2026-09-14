import { openTossBilling } from '../../billing/toss'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import {
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
  const catalog = screen.getByRole('list', { name: '목표 플랜 구성' })
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
  expect(screen.queryByRole('heading', { name: '목표 플랜 구성' })).not.toBeInTheDocument()
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
