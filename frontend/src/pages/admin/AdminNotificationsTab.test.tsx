import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import {
  listAdminNotifications,
  resolveAdminNotification,
  retryAdminNotification,
} from '../../api/admin'
import { ApiError } from '../../api/client'
import type { AdminNotification } from '../../api/adminOperationsTypes'
import { AdminNotificationsTab } from './AdminNotificationsTab'
vi.mock('../../api/admin', () => ({
  listAdminNotifications: vi.fn(),
  resolveAdminNotification: vi.fn(),
  retryAdminNotification: vi.fn(),
}))
const item: AdminNotification = {
  id: 42,
  workspace_id: 'w1',
  event_type: 'payment',
  state: 'uncertain',
  environment: 'toss_test',
  revision: 2,
  resolution: null,
  created_at: '2026-10-01T00:00:00Z',
  attempted_at: null,
  sent_at: null,
  retry_allowed: false,
  retry_blocked_reason: '미전달 확인 필요',
  resolve_allowed: true,
  attempts: [],
  resolutions: [],
}
beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  window.history.replaceState({}, '', '/admin?tab=notifications&notification=42')
  vi.mocked(listAdminNotifications).mockResolvedValue({
    items: [item],
    total: 1,
    page: 1,
    size: 20,
  })
})
it('uncertain mail cannot retry until server permits after non-delivery confirmation', async () => {
  const user = userEvent.setup()
  render(<AdminNotificationsTab />)
  await screen.findByText('미전달 확인 필요')
  await user.type(screen.getByLabelText('확인 사유'), '전달 이력 확인')
  expect(screen.getByRole('button', { name: '재발송 예약' })).toBeDisabled()
  vi.mocked(resolveAdminNotification).mockResolvedValue({
    ...item,
    resolution: 'not_delivered',
    revision: 3,
    retry_allowed: true,
  })
  vi.mocked(listAdminNotifications).mockResolvedValue({
    items: [{ ...item, resolution: 'not_delivered', revision: 3, retry_allowed: true }],
    total: 1,
    page: 1,
    size: 20,
  })
  await user.click(screen.getByRole('button', { name: '미전달 확인 기록' }))
  await waitFor(() =>
    expect(resolveAdminNotification).toHaveBeenCalledWith(
      42,
      expect.objectContaining({
        expected_revision: 2,
        resolution: 'not_delivered',
        reason: '전달 이력 확인',
      }),
    ),
  )
  expect(retryAdminNotification).not.toHaveBeenCalled()
})
it('network retry retains UUID and submitted reason', async () => {
  const user = userEvent.setup()
  vi.mocked(resolveAdminNotification).mockRejectedValue(new Error('연결 끊김'))
  render(<AdminNotificationsTab />)
  await screen.findByText('미전달 확인 필요')
  await user.type(screen.getByLabelText('확인 사유'), '미전달 확인')
  await user.click(screen.getByRole('button', { name: '미전달 확인 기록' }))
  await screen.findByRole('alert')
  expect(screen.getByLabelText('확인 사유')).toBeDisabled()
  await user.click(screen.getByRole('button', { name: '미전달 확인 기록' }))
  expect(vi.mocked(resolveAdminNotification).mock.calls[0]).toEqual(
    vi.mocked(resolveAdminNotification).mock.calls[1],
  )
})

it('restores uncertain command UUID after the view is reopened', async () => {
  const user = userEvent.setup()
  vi.mocked(resolveAdminNotification).mockRejectedValue(new Error('연결 끊김'))
  const first = render(<AdminNotificationsTab />)
  await screen.findByText('미전달 확인 필요')
  await user.type(screen.getByLabelText('확인 사유'), '미전달 확인')
  await user.click(screen.getByRole('button', { name: '미전달 확인 기록' }))
  await screen.findByRole('alert')
  first.unmount()
  render(<AdminNotificationsTab />)
  await screen.findByText('미전달 확인 필요')
  expect(screen.getByLabelText('확인 사유')).toHaveValue('미전달 확인')
  await user.click(screen.getByRole('button', { name: '미전달 확인 기록' }))
  expect(vi.mocked(resolveAdminNotification).mock.calls[0]).toEqual(
    vi.mocked(resolveAdminNotification).mock.calls[1],
  )
})

it('stale 409 only unlocks after an unfiltered fresh lookup proves a changed revision and no applied request', async () => {
  const user = userEvent.setup()
  const view = render(<AdminNotificationsTab />)
  await screen.findByText('미전달 확인 필요')
  await user.type(screen.getByLabelText('확인 사유'), '첫 사유')
  let completeLookup!: (value: Awaited<ReturnType<typeof listAdminNotifications>>) => void
  vi.mocked(listAdminNotifications).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        completeLookup = resolve
      }),
  )
  vi.mocked(resolveAdminNotification).mockRejectedValue(new ApiError(409, 'revision conflict'))
  await user.click(screen.getByRole('button', { name: '미전달 확인 기록' }))
  await waitFor(() =>
    expect(listAdminNotifications).toHaveBeenLastCalledWith({ id: 42, page: 1, size: 1 }),
  )
  expect(screen.getByLabelText('확인 사유')).toBeDisabled()
  completeLookup({ items: [{ ...item, revision: 3 }], total: 1, page: 1, size: 1 })
  await screen.findByText(/이 요청은 적용되지 않았습니다/)
  expect(screen.getByLabelText('확인 사유')).toBeEnabled()
  expect(sessionStorage.length).toBe(0)
  await user.clear(screen.getByLabelText('확인 사유'))
  await user.type(screen.getByLabelText('확인 사유'), '수정 사유')
  await user.click(screen.getByRole('button', { name: '미전달 확인 기록' }))
  expect(resolveAdminNotification).toHaveBeenLastCalledWith(
    42,
    expect.objectContaining({ expected_revision: 3, reason: '수정 사유' }),
  )
  expect(vi.mocked(resolveAdminNotification).mock.calls[1]?.[1].operation_id).not.toBe(
    vi.mocked(resolveAdminNotification).mock.calls[0]?.[1].operation_id,
  )
  view.unmount()
})
it('409 with an applied UUID clears recovered work only after finding its audit result', async () => {
  const user = userEvent.setup()
  render(<AdminNotificationsTab />)
  await screen.findByText('미전달 확인 필요')
  await user.type(screen.getByLabelText('확인 사유'), '결과 확인')
  vi.mocked(resolveAdminNotification).mockImplementation(async (_id, request) => {
    vi.mocked(listAdminNotifications).mockResolvedValue({
      items: [
        {
          ...item,
          revision: 3,
          resolutions: [
            {
              operation_id: request.operation_id,
              actor_user_id: 'a1',
              action: 'not_delivered',
              reason: request.reason,
              created_at: '2026-10-07T00:00:00Z',
            },
          ],
        },
      ],
      total: 1,
      page: 1,
      size: 1,
    })
    throw new ApiError(409, 'already applied')
  })
  await user.click(screen.getByRole('button', { name: '미전달 확인 기록' }))
  await waitFor(() => expect(sessionStorage.length).toBe(0))
  expect(await screen.findByLabelText('확인 사유')).toBeEnabled()
  expect(resolveAdminNotification).toHaveBeenCalledTimes(1)
})
it('409 with unchanged revision or failed result lookup retains the UUID', async () => {
  const user = userEvent.setup()
  render(<AdminNotificationsTab />)
  await screen.findByText('미전달 확인 필요')
  await user.type(screen.getByLabelText('확인 사유'), '재확인')
  vi.mocked(resolveAdminNotification).mockRejectedValue(new ApiError(409, 'conflict'))
  vi.mocked(listAdminNotifications).mockRejectedValue(new Error('lookup offline'))
  await user.click(screen.getByRole('button', { name: '미전달 확인 기록' }))
  await screen.findByRole('alert')
  expect(screen.getByLabelText('확인 사유')).toBeDisabled()
  vi.mocked(listAdminNotifications).mockResolvedValue({ items: [item], total: 1, page: 1, size: 1 })
  await user.click(screen.getByRole('button', { name: '미전달 확인 기록' }))
  await waitFor(() => expect(resolveAdminNotification).toHaveBeenCalledTimes(2))
  expect(vi.mocked(resolveAdminNotification).mock.calls[0]).toEqual(
    vi.mocked(resolveAdminNotification).mock.calls[1],
  )
})

it('shows current recipient and safe failure codes without allowing recipient edits', async () => {
  vi.mocked(listAdminNotifications).mockResolvedValue({
    items: [
      {
        ...item,
        workspace_name: '고객 작업공간',
        recipient_email: 'operator@example.test',
        failure_code: 'SMTP_REJECTED',
        attempts: [
          {
            id: 1,
            state: 'failed',
            started_at: '2026-10-01T00:00:00Z',
            finished_at: null,
            failure_code: 'SMTP_REJECTED',
          },
        ],
      },
    ],
    total: 1,
    page: 1,
    size: 20,
  })
  render(<AdminNotificationsTab />)
  expect(await screen.findByText(/현재 수신 이메일: operator@example.test/)).toBeVisible()
  expect(screen.getByText('최근 실패 코드: SMTP_REJECTED')).toBeVisible()
  expect(screen.getAllByText(/실패 코드: SMTP_REJECTED/)).toHaveLength(2)
  expect(screen.queryByRole('textbox', { name: /수신/ })).not.toBeInTheDocument()
})

it('confirmed 422 releases the rejected request and reloads before corrected submission', async () => {
  const user = userEvent.setup()
  render(<AdminNotificationsTab />)
  await screen.findByText('미전달 확인 필요')
  await user.type(screen.getByLabelText('확인 사유'), '거절된 사유')
  vi.mocked(resolveAdminNotification).mockRejectedValueOnce(new ApiError(422, '사유 확인 필요'))
  await user.click(screen.getByRole('button', { name: '미전달 확인 기록' }))
  await screen.findByText(/요청은 적용되지 않았습니다/)
  expect(screen.getByLabelText('확인 사유')).toBeEnabled()
  expect(sessionStorage.length).toBe(0)
  expect(listAdminNotifications).toHaveBeenLastCalledWith({ id: 42, page: 1, size: 1 })
  await user.clear(screen.getByLabelText('확인 사유'))
  await user.type(screen.getByLabelText('확인 사유'), '고친 사유')
  vi.mocked(resolveAdminNotification).mockRejectedValueOnce(new Error('network lost'))
  await user.click(screen.getByRole('button', { name: '미전달 확인 기록' }))
  await screen.findByText(/network lost/)
  expect(vi.mocked(resolveAdminNotification).mock.calls[1]?.[1].operation_id).not.toBe(
    vi.mocked(resolveAdminNotification).mock.calls[0]?.[1].operation_id,
  )
})
it('confirmed 404 clears the request and disables actions for the missing target', async () => {
  const user = userEvent.setup()
  render(<AdminNotificationsTab />)
  await screen.findByText('미전달 확인 필요')
  await user.type(screen.getByLabelText('확인 사유'), '대상 확인')
  vi.mocked(resolveAdminNotification).mockRejectedValueOnce(new ApiError(404, 'missing'))
  vi.mocked(listAdminNotifications).mockResolvedValueOnce({ items: [], total: 0, page: 1, size: 1 })
  await user.click(screen.getByRole('button', { name: '미전달 확인 기록' }))
  await screen.findByText('메일 대상을 찾을 수 없습니다. 목록을 재조회해 주세요.')
  expect(sessionStorage.length).toBe(0)
  expect(screen.getByRole('button', { name: '미전달 확인 기록' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '전달 확인 기록' })).toBeDisabled()
})
