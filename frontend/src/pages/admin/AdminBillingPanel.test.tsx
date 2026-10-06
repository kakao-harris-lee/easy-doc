import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import {
  adminBillingAction,
  getAdminBilling,
  getAdminBillingRequest,
} from '../../api/subscriptions'
import { ApiError } from '../../api/client'
import { AdminBillingPanel } from './AdminBillingPanel'
vi.mock('../../api/subscriptions', () => ({
  adminBillingAction: vi.fn(),
  getAdminBilling: vi.fn(),
  getAdminBillingRequest: vi.fn(),
}))
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  vi.mocked(getAdminBillingRequest).mockResolvedValue({
    operation_id: 'request',
    kind: 'management',
    status: 'completed',
    created_at: '',
    updated_at: '',
  })
  vi.mocked(getAdminBilling).mockResolvedValue({
    revision: 3,
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
  vi.mocked(getAdminBillingRequest).mockRejectedValueOnce(new ApiError(404, 'not found'))
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

it('restores the same request and reason after remount', async () => {
  vi.mocked(getAdminBillingRequest).mockRejectedValueOnce(new ApiError(404, 'not found'))
  vi.mocked(adminBillingAction)
    .mockRejectedValueOnce(new Error('lost'))
    .mockResolvedValueOnce(undefined)
  const user = userEvent.setup()
  const first = render(<AdminBillingPanel workspaceId="w" onChanged={vi.fn()} />)
  await screen.findByRole('button', { name: '결과 재조회' })
  await user.type(screen.getByLabelText('관리 조치 사유'), '응답 유실 복구')
  await user.click(screen.getByRole('button', { name: '결과 재조회' }))
  await screen.findByRole('alert')
  const before = vi.mocked(adminBillingAction).mock.calls[0]
  first.unmount()
  render(<AdminBillingPanel workspaceId="w" onChanged={vi.fn()} />)
  await screen.findByRole('button', { name: '결과 재조회' })
  expect(screen.getByLabelText('관리 조치 사유')).toHaveValue('응답 유실 복구')
  await user.click(screen.getByRole('button', { name: '결과 재조회' }))
  expect(vi.mocked(adminBillingAction).mock.calls[1]).toEqual(before)
})

it('replays the same pending action after response loss and keeps it until completion is confirmed', async () => {
  vi.mocked(adminBillingAction)
    .mockRejectedValueOnce(new Error('lost'))
    .mockResolvedValue(undefined)
  const pending = {
    operation_id: 'request',
    kind: 'management',
    status: 'pending',
    created_at: '',
    updated_at: '',
  }
  vi.mocked(getAdminBillingRequest)
    .mockResolvedValueOnce(pending)
    .mockResolvedValueOnce(pending)
    .mockResolvedValueOnce(pending)
  const user = userEvent.setup()
  render(<AdminBillingPanel workspaceId="w" onChanged={vi.fn()} />)
  await screen.findByRole('button', { name: '결과 재조회' })
  await user.type(screen.getByLabelText('관리 조치 사유'), '카드 삭제 확인')
  await user.click(screen.getByRole('button', { name: '카드 삭제 재처리' }))
  await screen.findByRole('alert')
  const first = vi.mocked(adminBillingAction).mock.calls[0]
  await user.click(screen.getByRole('button', { name: '카드 삭제 재처리' }))
  await waitFor(() => expect(adminBillingAction).toHaveBeenCalledTimes(2))
  expect(vi.mocked(adminBillingAction).mock.calls[1]).toEqual(first)
  expect(sessionStorage.getItem('admin-operation:anonymous:w:billing')).not.toBeNull()
  expect(screen.getByRole('button', { name: '갱신 중단' })).toBeDisabled()
  await user.click(screen.getByRole('button', { name: '카드 삭제 재처리' }))
  await waitFor(() => expect(adminBillingAction).toHaveBeenCalledTimes(3))
  expect(vi.mocked(adminBillingAction).mock.calls[2]).toEqual(first)
  await waitFor(() => expect(screen.getByLabelText('관리 조치 사유')).toBeEnabled())
  expect(sessionStorage.getItem('admin-operation:anonymous:w:billing')).toBeNull()
})
it('polls progressing card deletion and releases the recovered request once the action completes', async () => {
  const pendingRequest = {
    action: 'retry-card-deletion',
    id: 'operation',
    reason: '카드 삭제 복구',
    revision: 3,
  }
  sessionStorage.setItem('admin-operation:anonymous:w:billing', JSON.stringify(pendingRequest))
  const pendingAction = {
    operation_id: 'operation',
    action: 'retry_card_deletion',
    status: 'pending',
    reason: pendingRequest.reason,
    created_at: '',
    updated_at: '',
  }
  vi.mocked(getAdminBilling)
    .mockResolvedValueOnce({
      orders: [],
      operations: [],
      actions: [pendingAction],
      card_state: 'revoking',
      deletion_pending: true,
    })
    .mockResolvedValue({
      orders: [],
      operations: [],
      actions: [{ ...pendingAction, status: 'completed' }],
      card_state: 'revoked',
      deletion_pending: false,
    })
  vi.useFakeTimers()
  try {
    render(<AdminBillingPanel workspaceId="w" onChanged={vi.fn()} />)
    await act(async () => {
      await Promise.resolve()
    })
    expect(screen.getByLabelText('관리 조치 사유')).toBeDisabled()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000)
    })
    expect(getAdminBilling).toHaveBeenCalledTimes(2)
    expect(screen.getByLabelText('관리 조치 사유')).toBeEnabled()
    expect(sessionStorage.getItem('admin-operation:anonymous:w:billing')).toBeNull()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000)
    })
    expect(getAdminBilling).toHaveBeenCalledTimes(2)
  } finally {
    vi.useRealTimers()
  }
})

it('does not allow a new mutation before the server revision is known', async () => {
  vi.mocked(getAdminBilling).mockResolvedValue({ orders: [], operations: [] })
  const user = userEvent.setup()
  render(<AdminBillingPanel workspaceId="w" onChanged={vi.fn()} />)
  await user.type(screen.getByLabelText('관리 조치 사유'), '확인')
  expect(screen.getByRole('button', { name: '갱신 중단' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '카드 삭제 재처리' })).toBeDisabled()
  expect(adminBillingAction).not.toHaveBeenCalled()
})

it('backs off persistent deletion polling to thirty seconds without polling manual review orders', async () => {
  vi.mocked(getAdminBilling).mockResolvedValue({
    revision: 3,
    orders: [],
    operations: [],
    card_state: 'revoking',
    deletion_pending: true,
  })
  vi.useFakeTimers()
  try {
    const panel = render(<AdminBillingPanel workspaceId="w" onChanged={vi.fn()} />)
    await act(async () => {
      await Promise.resolve()
    })
    for (const delay of [5000, 10_000, 20_000, 30_000, 30_000]) {
      const before = vi.mocked(getAdminBilling).mock.calls.length
      await act(async () => {
        await vi.advanceTimersByTimeAsync(delay)
      })
      expect(getAdminBilling).toHaveBeenCalledTimes(before + 1)
    }
    panel.unmount()
    vi.mocked(getAdminBilling).mockResolvedValue({
      revision: 3,
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
    render(<AdminBillingPanel workspaceId="w" onChanged={vi.fn()} />)
    await act(async () => {
      await Promise.resolve()
    })
    const before = vi.mocked(getAdminBilling).mock.calls.length
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000)
    })
    expect(getAdminBilling).toHaveBeenCalledTimes(before)
  } finally {
    vi.useRealTimers()
  }
})
