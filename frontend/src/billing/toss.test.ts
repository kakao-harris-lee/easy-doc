import { afterEach, expect, it, vi } from 'vitest'
import { loadTossPayments } from '@tosspayments/tosspayments-sdk'
import { beginTossBilling } from '../api/subscriptions'
import { openTossBilling, BILLING_CONTEXT } from './toss'
vi.mock('@tosspayments/tosspayments-sdk', () => ({ loadTossPayments: vi.fn() }))
vi.mock('../api/subscriptions', () => ({ beginTossBilling: vi.fn() }))
afterEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
})
it('rejects live and test client key mixing before opening the SDK', async () => {
  vi.mocked(beginTossBilling).mockResolvedValue({
    session_id: 's',
    customer_key: 'c',
    client_key: 'test_ck_example',
    billing_environment: 'toss_live',
  })
  await expect(
    openTossBilling('w', 'start', false, 'purchase', 'start-monthly-v1'),
  ).rejects.toThrow('결제 환경')
  expect(loadTossPayments).not.toHaveBeenCalled()
  expect(sessionStorage.getItem(BILLING_CONTEXT)).toBeNull()
})
it('rejects failure simulation for a live session', async () => {
  vi.mocked(beginTossBilling).mockResolvedValue({
    session_id: 's',
    customer_key: 'c',
    client_key: 'live_ck_example',
    billing_environment: 'toss_live',
  })
  await expect(openTossBilling('w', 'start', true)).rejects.toThrow('실결제')
  expect(loadTossPayments).not.toHaveBeenCalled()
})
