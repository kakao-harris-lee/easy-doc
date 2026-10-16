import { StrictMode } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { completeTossBilling, resumeTossBilling } from '../api/subscriptions'
import { BILLING_CONTEXT } from '../billing/toss'
import { BillingCallbackPage } from './BillingCallbackPage'

vi.mock('../api/subscriptions', () => ({
  completeTossBilling: vi.fn(),
  getSubscription: vi.fn(),
  resumeTossBilling: vi.fn(),
}))
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
  expect(await screen.findByText(/구독이 적용/)).toBeInTheDocument()
  expect(vi.mocked(completeTossBilling).mock.calls[1]).toEqual(
    vi.mocked(completeTossBilling).mock.calls[0],
  )
})

function setupResume(resumeId = 'resume-1') {
  history.replaceState(null, '', `/billing/callback?resume=${resumeId}`)
  render(
    <StrictMode>
      <MemoryRouter>
        <BillingCallbackPage />
      </MemoryRouter>
    </StrictMode>,
  )
}

const overview = (overrides: Record<string, unknown> = {}) => ({
  mock_enabled: false,
  toss_enabled: true,
  pending: false,
  plans: [],
  subscription: null,
  payments: [],
  ...overrides,
})

it('resumes a server-owned callback without session storage or credentials', async () => {
  vi.mocked(resumeTossBilling).mockResolvedValue({
    workspace_id: 'workspace',
    session_id: 'session',
    purpose: 'purchase',
    overview: overview(),
  })
  sessionStorage.setItem('easydoc.billing.context', 'must-not-be-used')
  setupResume()

  expect(await screen.findByText(/구독이 적용/)).toBeInTheDocument()
  expect(resumeTossBilling).toHaveBeenCalledTimes(1)
  expect(resumeTossBilling).toHaveBeenCalledWith('resume-1')
  expect(sessionStorage.getItem('easydoc.billing.context')).toBe('must-not-be-used')
  expect(location.search).toBe('')
})

it('keeps a pending resume id in history so refresh and re-login can retry it', async () => {
  vi.mocked(resumeTossBilling).mockResolvedValue({
    workspace_id: 'workspace',
    session_id: 'session',
    purpose: 'purchase',
    overview: overview({ pending: true }),
  })
  setupResume('pending-resume')

  expect(await screen.findByText(/결제 결과를 확인 중/)).toBeInTheDocument()
  expect(resumeTossBilling).toHaveBeenCalledWith('pending-resume')
  expect(location.search).toBe('?resume=pending-resume')
  expect(sessionStorage.getItem('easydoc.billing.context')).toBeNull()
})

it('retries an uncertain server resume with the same id', async () => {
  vi.mocked(resumeTossBilling).mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce({
    workspace_id: 'workspace',
    session_id: 'session',
    purpose: 'replace_card',
    overview: overview(),
  })
  setupResume('retry-resume')

  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: '결과 다시 확인' }))
  expect(await screen.findByText(/카드가 변경되었습니다/)).toBeInTheDocument()
  expect(resumeTossBilling).toHaveBeenNthCalledWith(1, 'retry-resume')
  expect(resumeTossBilling).toHaveBeenNthCalledWith(2, 'retry-resume')
})

it('removes expired resume ids from history and shows the server reason', async () => {
  vi.mocked(resumeTossBilling).mockRejectedValue(new ApiError(404, '복구 자료가 만료되었습니다.'))
  setupResume('expired-resume')

  expect(await screen.findByText('복구 자료가 만료되었습니다.')).toBeInTheDocument()
  expect(location.search).toBe('')
  expect(screen.queryByRole('button', { name: '결과 다시 확인' })).not.toBeInTheDocument()
})
