import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { adminBillingAction, getAdminBilling } from '../../api/subscriptions'
import { AdminBillingPanel } from './AdminBillingPanel'
vi.mock('../../api/subscriptions', () => ({
  adminBillingAction: vi.fn(),
  getAdminBilling: vi.fn(),
}))
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getAdminBilling).mockResolvedValue({
    orders: [
      {
        id: 'order',
        kind: 'renewal',
        status: 'manual_review',
        amount: 99000,
        created_at: '',
        environment: 'toss_live',
        needs_review: true,
      },
    ],
    operations: [],
  })
})
it('requires a reason and retries uncertain recovery with identical request identity', async () => {
  vi.mocked(adminBillingAction)
    .mockRejectedValueOnce(new Error('lost'))
    .mockResolvedValueOnce(undefined)
  const user = userEvent.setup()
  const changed = vi.fn()
  render(<AdminBillingPanel workspaceId="w" onChanged={changed} />)
  const sync = await screen.findByRole('button', { name: '결과 재조회' })
  expect(sync).toBeDisabled()
  expect(screen.getByText('관리자 확인 필요')).toBeInTheDocument()
  await user.type(screen.getByLabelText('관리 조치 사유'), '승인 응답 유실 확인')
  await user.click(sync)
  expect(await screen.findByRole('alert')).toHaveTextContent('결과를 확인하지 못했습니다')
  expect(screen.getByRole('button', { name: '갱신 중단' })).toBeDisabled()
  await user.click(sync)
  expect(vi.mocked(adminBillingAction).mock.calls[1]).toEqual(
    vi.mocked(adminBillingAction).mock.calls[0],
  )
  expect(changed).toHaveBeenCalledOnce()
})
