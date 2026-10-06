import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { listAdminOperations } from '../../api/admin'
import { AdminOperationsTab } from './AdminOperationsTab'
vi.mock('../../api/admin', () => ({ listAdminOperations: vi.fn() }))
beforeEach(() => {
  window.history.replaceState({}, '', '/admin')
  vi.mocked(listAdminOperations).mockReset()
})
it('counts tasks and routes invoice and notification work to their specific details', async () => {
  vi.mocked(listAdminOperations).mockResolvedValue({
    items: [
      {
        id: '42',
        kind: 'notification',
        state: 'uncertain',
        environment: 'toss_test',
        workspace_id: 'w1',
        workspace_name: '고객',
        created_at: '2026-10-01T00:00:00Z',
        severity: 2,
        next_action: '전달 여부 확인',
      },
    ],
    total: 1,
    page: 1,
    size: 20,
    counts: { notification: 1 },
  })
  render(<AdminOperationsTab />)
  expect(await screen.findByText('총 1 작업 건')).toBeVisible()
  expect(screen.getByRole('link', { name: '상세 확인' })).toHaveAttribute(
    'href',
    '/admin?tab=notifications&notification=42',
  )
  await userEvent.setup().selectOptions(screen.getByLabelText('결제 환경'), 'toss_test')
  await waitFor(() =>
    expect(listAdminOperations).toHaveBeenLastCalledWith(
      expect.objectContaining({ environment: 'toss_test', page: 1 }),
      expect.anything(),
    ),
  )
  expect(window.location.search).toContain('operations_environment=toss_test')
})

it('does not invent elapsed time for legacy card deletion without a recorded transition', async () => {
  vi.mocked(listAdminOperations).mockResolvedValue({
    items: [
      {
        id: 'card-legacy',
        kind: 'card_deletion',
        state: 'delete_pending',
        environment: 'toss_test',
        workspace_id: 'w1',
        workspace_name: '고객',
        created_at: null,
        severity: 2,
        next_action: '카드 삭제 재처리',
      },
    ],
    total: 1,
    page: 1,
    size: 20,
    counts: { card_deletion: 1 },
  })
  render(<AdminOperationsTab />)
  expect(await screen.findByText('시각 기록 없음 · 경과 시간 확인 불가')).toBeVisible()
  expect(screen.queryByText(/1970|NaN|Invalid Date/)).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: '상세 확인' })).toHaveAttribute(
    'href',
    '/admin?tab=workspaces&workspace=w1',
  )
})
