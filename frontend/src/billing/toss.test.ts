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

it('uses the server callback URL and does not persist a legacy billing context', async () => {
  const requestBillingAuth = vi.fn().mockResolvedValue(undefined)
  vi.mocked(loadTossPayments).mockResolvedValue({
    payment: () => ({ requestBillingAuth }),
  } as never)
  vi.mocked(beginTossBilling).mockResolvedValue({
    session_id: 's',
    customer_key: 'c',
    client_key: 'test_ck_example',
    billing_environment: 'toss_test',
    callback_url: 'https://app.example.test/api/payments/toss/billing/callback?state=state-1',
  })

  await openTossBilling('w', 'start', false)

  expect(sessionStorage.getItem(BILLING_CONTEXT)).toBeNull()
  expect(requestBillingAuth).toHaveBeenCalledWith({
    method: 'CARD',
    successUrl: 'https://app.example.test/api/payments/toss/billing/callback?state=state-1',
    failUrl: 'https://app.example.test/api/payments/toss/billing/callback?state=state-1',
  })
})
it('keeps only the legacy correlation context when the old response omits callback_url', async () => {
  const requestBillingAuth = vi.fn().mockResolvedValue(undefined)
  vi.mocked(loadTossPayments).mockResolvedValue({
    payment: () => ({ requestBillingAuth }),
  } as never)
  vi.mocked(beginTossBilling).mockResolvedValue({
    session_id: 's',
    customer_key: 'c',
    client_key: 'test_ck_example',
    billing_environment: 'toss_test',
  })

  await openTossBilling('w', 'start', true, 'replace_card')

  expect(JSON.parse(sessionStorage.getItem(BILLING_CONTEXT) ?? 'null')).toEqual({
    workspace: 'w',
    session: 's',
    fail: true,
    purpose: 'replace_card',
    environment: 'toss_test',
  })
  expect(requestBillingAuth).toHaveBeenCalledWith({
    method: 'CARD',
    successUrl: `${location.origin}/billing/callback`,
    failUrl: `${location.origin}/billing/callback`,
  })
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
