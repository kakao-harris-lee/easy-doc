import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import {
  checkoutSubscription,
  getSubscription,
  type SubscriptionOverview,
} from '../../api/subscriptions'
import { SubscriptionCard } from './SubscriptionCard'

vi.mock('../../api/subscriptions', () => ({
  getSubscription: vi.fn(),
  checkoutSubscription: vi.fn(),
  cancelSubscription: vi.fn(),
}))
const available: SubscriptionOverview = {
  mock_enabled: true,
  plans: [
    { id: 'starter', name: 'Starter', allowance: 50, monthly_price: 1000 },
    { id: 'pro', name: 'Pro', allowance: 200, monthly_price: 3000 },
  ],
  subscription: null,
  payments: [],
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getSubscription).mockResolvedValue(available)
})
it('shows the server-provided plan catalog without hardcoding amounts', async () => {
  render(<SubscriptionCard workspaceId="w1" />)
  const catalog = await screen.findByRole('list', { name: '테스트 플랜 구성' })
  expect(within(catalog).getByText('Starter')).toBeInTheDocument()
  expect(within(catalog).getByText('Pro')).toBeInTheDocument()
  expect(within(catalog).getByText(/월 50크레딧/)).toBeInTheDocument()
  expect(within(catalog).getByText(/월 200크레딧/)).toBeInTheDocument()
  expect(within(catalog).getByText(/5만 자/)).toBeInTheDocument()
  expect(within(catalog).getByText(/20만 자/)).toBeInTheDocument()
})
it('lets the user pick a plan from the catalog before checkout', async () => {
  const user = userEvent.setup()
  vi.mocked(checkoutSubscription).mockResolvedValue({
    ...available,
    subscription: {
      plan_id: 'pro',
      allowance: 200,
      monthly_price: 3000,
      status: 'active',
      cycle_ends_at: '2026-10-12T00:00:00Z',
    },
  })
  render(<SubscriptionCard workspaceId="w1" />)
  await user.click(await screen.findByRole('button', { name: '플랜 선택' }))
  await user.click(screen.getByRole('radio', { name: 'Pro' }))
  await user.click(screen.getByRole('button', { name: '테스트 결제' }))
  expect(checkoutSubscription).toHaveBeenCalledWith('w1', 'pro', expect.any(String), false)
})
it('uses the server plan and applies mock checkout before refreshing usage', async () => {
  const user = userEvent.setup(),
    changed = vi.fn()
  vi.mocked(checkoutSubscription).mockResolvedValue({
    ...available,
    subscription: {
      plan_id: 'starter',
      allowance: 50,
      monthly_price: 1000,
      status: 'active',
      cycle_ends_at: '2026-10-12T00:00:00Z',
    },
  })
  render(<SubscriptionCard workspaceId="w1" onChanged={changed} />)
  await user.click(await screen.findByRole('button', { name: '플랜 선택' }))
  await user.click(screen.getByRole('button', { name: '테스트 결제' }))
  expect(checkoutSubscription).toHaveBeenCalledWith('w1', 'starter', expect.any(String), false)
  expect(changed).toHaveBeenCalledOnce()
  expect(await screen.findByRole('button', { name: '구독 갱신 중단' })).toBeInTheDocument()
})
it('retries an uncertain payment with the same order id', async () => {
  const user = userEvent.setup()
  vi.mocked(checkoutSubscription).mockRejectedValue(new Error('network'))
  render(<SubscriptionCard workspaceId="w1" />)
  await user.click(await screen.findByRole('button', { name: '플랜 선택' }))
  await user.click(screen.getByRole('button', { name: '테스트 결제' }))
  await screen.findByRole('alert')
  await user.click(screen.getByRole('button', { name: '테스트 결제' }))
  expect(vi.mocked(checkoutSubscription).mock.calls[0]?.[2]).toBe(
    vi.mocked(checkoutSubscription).mock.calls[1]?.[2],
  )
})
it('admin uses admin endpoint and does not show checkout controls', async () => {
  render(<SubscriptionCard workspaceId="w2" admin />)
  await screen.findByText('선택한 플랜 없음')
  expect(getSubscription).toHaveBeenCalledWith('w2', expect.any(AbortSignal), true)
  expect(screen.queryByRole('button', { name: '플랜 선택' })).not.toBeInTheDocument()
})
