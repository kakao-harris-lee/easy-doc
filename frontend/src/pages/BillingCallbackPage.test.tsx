import { StrictMode } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, it, vi } from 'vitest'
import { completeTossBilling } from '../api/subscriptions'
import { BILLING_CONTEXT } from '../billing/toss'
import { BillingCallbackPage } from './BillingCallbackPage'

vi.mock('../api/subscriptions', () => ({ completeTossBilling: vi.fn() }))
afterEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  history.replaceState(null, '', '/')
})
function setup() {
  sessionStorage.setItem(
    BILLING_CONTEXT,
    JSON.stringify({ workspace: 'workspace', session: 'session', fail: false }),
  )
  history.replaceState(null, '', '/billing/callback?authKey=synthetic-auth&customerKey=customer')
  render(
    <StrictMode>
      <MemoryRouter>
        <BillingCallbackPage />
      </MemoryRouter>
    </StrictMode>,
  )
}
it('handles StrictMode once, clears credentials from history and reports pending approval honestly', async () => {
  vi.mocked(completeTossBilling).mockResolvedValue({
    mock_enabled: false,
    toss_enabled: true,
    pending: true,
    plans: [],
    subscription: null,
    payments: [],
  })
  setup()
  expect(await screen.findByText(/결제 결과를 확인 중/)).toBeInTheDocument()
  expect(completeTossBilling).toHaveBeenCalledTimes(1)
  expect(completeTossBilling).toHaveBeenCalledWith(
    'workspace',
    'session',
    'customer',
    'synthetic-auth',
    false,
  )
  expect(location.search).toBe('')
  expect(sessionStorage.getItem(BILLING_CONTEXT)).toBeNull()
})
it('retries uncertain callback with the same credential and correlation identifiers', async () => {
  vi.mocked(completeTossBilling).mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce({
    mock_enabled: false,
    toss_enabled: true,
    plans: [],
    subscription: null,
    payments: [],
  })
  setup()
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: '결과 다시 확인' }))
  expect(await screen.findByText(/테스트 구독이 적용/)).toBeInTheDocument()
  expect(vi.mocked(completeTossBilling).mock.calls[1]).toEqual(
    vi.mocked(completeTossBilling).mock.calls[0],
  )
})
