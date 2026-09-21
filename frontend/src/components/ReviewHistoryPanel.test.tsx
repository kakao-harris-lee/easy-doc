import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, downloadReviewHistory, getReviewHistory } from '../api/client'
import type { ReviewHistoryEvent } from '../api/types'
import { ReviewHistoryPanel } from './ReviewHistoryPanel'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  downloadReviewHistory: vi.fn(),
  getReviewHistory: vi.fn(),
}))

function historyEvent(overrides: Partial<ReviewHistoryEvent> = {}): ReviewHistoryEvent {
  return {
    event_id: 'event-1',
    event_type: 'item_confirmed',
    created_at: '2026-09-21T03:04:05Z',
    actor_user_id: 'user-1',
    content_revision: 2,
    artifact_revision: 1,
    item_id: 'item-1',
    assessment_id: 'assessment-1',
    guide_id: null,
    snapshot: {
      status: 'available',
      kind: 'review_assessment',
      content_text: '확인한 당시 본문',
      artifact_json: null,
    },
    ...overrides,
  }
}

beforeEach(() => {
  vi.mocked(getReviewHistory).mockReset()
  vi.mocked(downloadReviewHistory).mockReset()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('ReviewHistoryPanel', () => {
  it('서버 revision과 snapshot 상태를 기록 카드에 표시한다', async () => {
    vi.mocked(getReviewHistory).mockResolvedValue({
      conversion_id: 'c1',
      current_content_revision: 2,
      events: [
        historyEvent({ content_revision: 1 }),
        historyEvent({
          event_id: 'event-2',
          event_type: 'guide_reviewed',
          snapshot: {
            status: 'missing',
            kind: null,
            content_text: null,
            artifact_json: null,
          },
        }),
      ],
      next_cursor: null,
    })

    render(<ReviewHistoryPanel conversionId="c1" contentRevision={2} />)

    await waitFor(() => expect(screen.getByText('검수 항목 확인')).toBeInTheDocument())
    expect(screen.getByText('현재 본문과 다름')).toBeInTheDocument()
    expect(screen.getByText('본문 스냅샷 없음')).toBeInTheDocument()
    expect(screen.getAllByText('user-1')).toHaveLength(2)
    expect(screen.getByText('확인한 당시 본문')).toBeInTheDocument()
    expect(getReviewHistory).toHaveBeenCalledWith('c1', { limit: 20 }, expect.any(AbortSignal))
  })

  it('더 보기 응답은 event_id로 중복을 제거하고 cursor를 이어 간다', async () => {
    const first = historyEvent()
    vi.mocked(getReviewHistory)
      .mockResolvedValueOnce({
        conversion_id: 'c1',
        current_content_revision: 2,
        events: [first],
        next_cursor: 'cursor-1',
      })
      .mockResolvedValueOnce({
        conversion_id: 'c1',
        current_content_revision: 2,
        events: [first, historyEvent({ event_id: 'event-2', event_type: 'item_reopened' })],
        next_cursor: null,
      })

    const user = userEvent.setup()
    render(<ReviewHistoryPanel conversionId="c1" contentRevision={2} />)
    await waitFor(() => expect(screen.getByRole('button', { name: '더 보기' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: '더 보기' }))

    await waitFor(() => expect(screen.getByText('검수 항목 확인 취소')).toBeInTheDocument())
    expect(screen.getAllByText('확인한 당시 본문')).toHaveLength(2)
    expect(getReviewHistory).toHaveBeenLastCalledWith(
      'c1',
      { cursor: 'cursor-1', limit: 20 },
      expect.any(AbortSignal),
    )
    expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument()
  })

  it('새로 고침이 진행 중인 더 보기를 취소해 버튼이 영구히 잠기지 않게 한다', async () => {
    const morePromise = new Promise<Awaited<ReturnType<typeof getReviewHistory>>>(() => undefined)
    const initial = {
      conversion_id: 'c1',
      current_content_revision: 2,
      events: [historyEvent()],
      next_cursor: 'cursor-1',
    }
    const refreshed = {
      ...initial,
      events: [historyEvent({ event_id: 'event-refreshed' })],
      next_cursor: 'cursor-2',
    }
    vi.mocked(getReviewHistory)
      .mockResolvedValueOnce(initial)
      .mockImplementationOnce(() => morePromise)
      .mockResolvedValueOnce(refreshed)

    const user = userEvent.setup()
    render(<ReviewHistoryPanel conversionId="c1" contentRevision={2} />)
    await waitFor(() => expect(screen.getByRole('button', { name: '더 보기' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: '더 보기' }))
    expect(screen.getByRole('button', { name: '더 보기' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: '새로 고침' }))
    await waitFor(() => expect(getReviewHistory).toHaveBeenCalledTimes(3))
    await waitFor(() => expect(screen.getByRole('button', { name: '더 보기' })).not.toBeDisabled())
    expect(screen.getByText('확인한 당시 본문')).toBeInTheDocument()
  })

  it('TXT 내려받기 실패는 ApiError 메시지를 사용자에게 보여준다', async () => {
    vi.mocked(getReviewHistory).mockResolvedValue({
      conversion_id: 'c1',
      current_content_revision: 2,
      events: [],
      next_cursor: null,
    })
    vi.mocked(downloadReviewHistory).mockRejectedValue(
      new ApiError(409, '검수 기록이 아직 준비되지 않았습니다.'),
    )

    const user = userEvent.setup()
    render(<ReviewHistoryPanel conversionId="c1" contentRevision={2} />)
    await user.click(await screen.findByRole('button', { name: 'TXT로 내려받기' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '검수 기록이 아직 준비되지 않았습니다.',
    )
  })
})
