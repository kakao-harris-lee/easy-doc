import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/client'
import { getAdminBillingRequest, refundTossPayment } from '../../api/subscriptions'
import { AdminPendingRefunds } from './AdminPendingRefunds'
import { clearToken } from '../../api/token'
vi.mock('../../api/subscriptions', () => ({
  getAdminBillingRequest: vi.fn(),
  refundTossPayment: vi.fn(),
}))
const key = 'admin-operation:anonymous:workspace:refund:payment'
const request = {
  operation_id: 'request',
  amount: 100,
  recovery_credits: 2,
  stop_renewal: true,
  reason: '고객 요청',
  expected_revision: 3,
}
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  sessionStorage.setItem(key, JSON.stringify({ ...request, payment_id: 'payment' }))
})
it('recovers a pending refund independently of month without sending a second mutation', async () => {
  vi.mocked(getAdminBillingRequest).mockResolvedValue({
    operation_id: 'request',
    kind: 'refund',
    status: 'pending',
    created_at: '',
    updated_at: '',
  })
  const user = userEvent.setup()
  render(<AdminPendingRefunds workspaceId="workspace" onChanged={vi.fn()} />)
  await user.click(screen.getByRole('button', { name: '같은 환불 요청 결과 확인' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('처리 중')
  expect(refundTossPayment).not.toHaveBeenCalled()
  expect(sessionStorage.getItem(key)).not.toBeNull()
})
it('replays exactly the original financial payload only when no request was found', async () => {
  vi.mocked(getAdminBillingRequest).mockRejectedValue(new ApiError(404, 'not found'))
  vi.mocked(refundTossPayment).mockResolvedValue({
    ...request,
    workspace_id: 'workspace',
    payment_id: 'payment',
    status: 'completed',
    created_at: '',
    updated_at: '',
  })
  const user = userEvent.setup()
  render(<AdminPendingRefunds workspaceId="workspace" onChanged={vi.fn()} />)
  await user.click(screen.getByRole('button', { name: '같은 환불 요청 결과 확인' }))
  await waitFor(() =>
    expect(refundTossPayment).toHaveBeenCalledWith('workspace', 'payment', request),
  )
  expect(sessionStorage.getItem(key)).toBeNull()
})
it('clears financial recovery data on logout but preserves unrelated browser state', () => {
  sessionStorage.setItem('unrelated', 'keep')
  clearToken()
  expect(sessionStorage.getItem(key)).toBeNull()
  expect(sessionStorage.getItem('unrelated')).toBe('keep')
})
