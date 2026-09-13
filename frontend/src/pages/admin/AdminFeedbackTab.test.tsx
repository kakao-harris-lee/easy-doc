import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { listAdminFeedback } from '../../api/admin'
import { AdminFeedbackTab } from './AdminFeedbackTab'

vi.mock('../../api/admin', () => ({ listAdminFeedback: vi.fn() }))
beforeEach(() => vi.mocked(listAdminFeedback).mockReset())

it('의견 내용과 척도를 보여주고 다음 페이지를 조회한다', async () => {
  const user = userEvent.setup()
  vi.mocked(listAdminFeedback)
    .mockResolvedValueOnce({
      items: [
        {
          conversion_id: 'c1',
          user_id: 'u1',
          owner_email: 'reader@example.test',
          publish_intent: 'with_edits',
          quality_score: 4,
          minutes_spent: 12,
          comment: '문장을 조금 더 짧게 해주세요.',
          comment_unreadable: false,
          submitted_at: '2026-09-12T01:00:00Z',
        },
      ],
      total: 21,
      page: 1,
      size: 20,
    })
    .mockResolvedValueOnce({ items: [], total: 21, page: 2, size: 20 })
  render(<AdminFeedbackTab />)
  expect(await screen.findByText('문장을 조금 더 짧게 해주세요.')).toBeInTheDocument()
  expect(screen.getByText('reader@example.test')).toBeInTheDocument()
  expect(screen.getByText(/만족도 4\/5 · 조금 고쳐서 쓰겠다 · 소요 시간 12분/)).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '다음' }))
  await waitFor(() =>
    expect(listAdminFeedback).toHaveBeenLastCalledWith(
      { page: 2, size: 20 },
      expect.any(AbortSignal),
    ),
  )
  expect(screen.queryByText('문장을 조금 더 짧게 해주세요.')).not.toBeInTheDocument()
})

it('실패 안내에서 새로고침으로 다시 조회한다', async () => {
  vi.mocked(listAdminFeedback)
    .mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValueOnce({ items: [], total: 0, page: 1, size: 20 })
  render(<AdminFeedbackTab />)
  expect(await screen.findByRole('alert')).toHaveTextContent('사용자 의견을 불러오지 못했습니다.')
  await userEvent.click(screen.getByRole('button', { name: '새로고침' }))
  expect(await screen.findByText('이 페이지에 제출된 의견이 없습니다.')).toBeInTheDocument()
})

it('의견이 파기된 경우 빈 값의 이유를 표시한다', async () => {
  vi.mocked(listAdminFeedback).mockResolvedValue({
    items: [
      {
        conversion_id: 'c1',
        user_id: null,
        owner_email: null,
        publish_intent: 'as_is',
        quality_score: 5,
        minutes_spent: 0,
        comment: null,
        comment_unreadable: false,
        submitted_at: '2026-09-12T01:00:00Z',
      },
    ],
    total: 1,
    page: 1,
    size: 20,
  })
  render(<AdminFeedbackTab />)
  expect(await screen.findByText('자유 의견이 없거나 보존 기간이 끝났습니다.')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '다음' })).toBeDisabled()
})

it('복호화 실패를 미작성 의견과 구분한다', async () => {
  vi.mocked(listAdminFeedback).mockResolvedValue({
    items: [
      {
        conversion_id: 'c1',
        user_id: 'u1',
        owner_email: 'reader@example.test',
        publish_intent: 'as_is',
        quality_score: 5,
        minutes_spent: 0,
        comment: null,
        comment_unreadable: true,
        submitted_at: '2026-09-12T01:00:00Z',
      },
    ],
    total: 1,
    page: 1,
    size: 20,
  })
  render(<AdminFeedbackTab />)
  expect(await screen.findByText('저장된 의견을 읽을 수 없습니다.')).toBeInTheDocument()
  expect(screen.queryByText('자유 의견이 없거나 보존 기간이 끝났습니다.')).not.toBeInTheDocument()
})
