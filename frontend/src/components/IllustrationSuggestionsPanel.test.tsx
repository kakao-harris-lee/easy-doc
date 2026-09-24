import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  ApiError,
  createIllustrationSuggestionJob,
  getIllustrationSuggestionJob,
  getIllustrationSuggestions,
  listIllustrationSuggestionJobs,
} from '../api/client'
import type {
  IllustrationSuggestion,
  IllustrationSuggestionJob,
  IllustrationSuggestionJobCollection,
  IllustrationSuggestionsResource,
} from '../api/types'
import {
  IllustrationSuggestionsPanel,
  type IllustrationSuggestionsPanelProps,
} from './IllustrationSuggestionsPanel'

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  createIllustrationSuggestionJob: vi.fn(),
  getIllustrationSuggestionJob: vi.fn(),
  getIllustrationSuggestions: vi.fn(),
  listIllustrationSuggestionJobs: vi.fn(),
}))

const SAVED_BODY = [
  '신청서를 씁니다.',
  '주민센터에 냅니다.',
  '결과를 기다립니다.',
  '안내를 받습니다.',
].join('\n')

const suggestion: IllustrationSuggestion = {
  suggestion_id: 'suggestion-1',
  purpose: 'procedure',
  reason: '신청 순서를 그림으로 보면 이해하기 쉬워집니다',
  body_range: { start: 0, end: 2 },
  source_anchors: [{ source_unit_indexes: [0], quote: '신청서를 제출합니다' }],
  scenes: ['신청서를 쓰는 장면', '주민센터에 내는 장면'],
  preserved_facts: ['직접 방문해야 합니다'],
  alt_text_draft: '신청 순서를 차례대로 보여 주는 그림',
}

const notAnalyzed: IllustrationSuggestionsResource = {
  status: 'not_analyzed',
  content_revision: 1,
  based_on_content_revision: null,
  required_credits: 1.5,
  suggestions: [],
  dropped_count: 0,
}

const ready: IllustrationSuggestionsResource = {
  ...notAnalyzed,
  status: 'ready',
  based_on_content_revision: 1,
  suggestions: [suggestion],
}

const job: IllustrationSuggestionJob = {
  job_id: 'job-1',
  request_id: 'request-1',
  status: 'queued',
  based_on_content_revision: 1,
  reserved_credits: 1.5,
  failure_code: null,
  created_at: '2026-09-24T00:00:00Z',
  updated_at: '2026-09-24T00:00:01Z',
}

const jobs: IllustrationSuggestionJobCollection = {
  active_job: null,
  latest_job: null,
  required_credits: 1.5,
  available_credits: 6.4,
}

const defaults: IllustrationSuggestionsPanelProps = {
  conversionId: 'conversion-1',
  contentRevision: 1,
  bodyDirty: false,
  bodyBusy: false,
  bodyConflict: false,
  savedBody: SAVED_BODY,
}

beforeEach(() => {
  vi.mocked(getIllustrationSuggestions).mockReset().mockResolvedValue(notAnalyzed)
  vi.mocked(listIllustrationSuggestionJobs).mockReset().mockResolvedValue(jobs)
  vi.mocked(getIllustrationSuggestionJob).mockReset().mockResolvedValue(job)
  vi.mocked(createIllustrationSuggestionJob).mockReset()
  vi.stubGlobal('crypto', { randomUUID: () => 'generated-uuid' })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

function show(overrides: Partial<IllustrationSuggestionsPanelProps> = {}) {
  return render(<IllustrationSuggestionsPanel {...defaults} {...overrides} />)
}

describe('ER-17 그림 제안 패널 — 요청 전', () => {
  it('무엇을 하는지·필요 이용량·이미지가 생기지 않는다는 점을 알리고 자동 요청하지 않는다', async () => {
    show()

    expect(await screen.findByRole('heading', { name: '그림 제안' })).toBeInTheDocument()
    expect(
      screen.getByText(/이 단계에서는 그림을 만들지 않습니다/, { selector: 'p' }),
    ).toBeInTheDocument()
    expect(screen.getByText('필요 이용량 1.5크레딧 / 남은 이용량 6.4크레딧')).toBeInTheDocument()
    expect(
      screen.getByText(/DOCX·HWPX·TXT 파일에는 새 그림이 포함되지 않습니다/),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '그림 제안 확인' })).toBeEnabled()
    expect(createIllustrationSuggestionJob).not.toHaveBeenCalled()
  })

  it('이용량 단가가 설정되지 않았으면 요청 버튼 대신 안내를 보여 준다', async () => {
    vi.mocked(listIllustrationSuggestionJobs).mockResolvedValue({ ...jobs, required_credits: null })
    vi.mocked(getIllustrationSuggestions).mockResolvedValue({
      ...notAnalyzed,
      required_credits: null,
    })

    show()

    expect(await screen.findByText(/이용량이 설정되지 않았습니다/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '그림 제안 확인' })).not.toBeInTheDocument()
  })

  it('단가가 0이면 추가 차감이 없다고 알린다', async () => {
    vi.mocked(listIllustrationSuggestionJobs).mockResolvedValue({ ...jobs, required_credits: 0 })

    show()

    expect(await screen.findByText('추가 차감 없음')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '그림 제안 확인' })).toBeEnabled()
  })

  it('이용량이 부족하면 요청을 막고 이용량 화면으로 보낸다', async () => {
    vi.mocked(listIllustrationSuggestionJobs).mockResolvedValue({ ...jobs, available_credits: 0.5 })

    show()

    expect(await screen.findByRole('button', { name: '그림 제안 확인' })).toBeDisabled()
    expect(screen.getByRole('link', { name: '이용량 화면으로 이동' })).toHaveAttribute(
      'href',
      '/usage',
    )
  })

  it('저장하지 않은 본문이 있으면 먼저 저장하라고 안내하고 요청을 막는다', async () => {
    show({ bodyDirty: true })

    expect(await screen.findByRole('button', { name: '그림 제안 확인' })).toBeDisabled()
    expect(screen.getByText(/저장하지 않은 본문 수정이 있습니다/)).toBeInTheDocument()
  })

  it('본문 충돌 중에도 요청을 막는다', async () => {
    show({ bodyConflict: true })

    expect(await screen.findByRole('button', { name: '그림 제안 확인' })).toBeDisabled()
  })
})

describe('ER-17 그림 제안 패널 — 접수와 진행', () => {
  it('요청은 멱등 키와 현재 본문 revision을 보내고 진행 상태를 알린다', async () => {
    const user = userEvent.setup()
    vi.mocked(createIllustrationSuggestionJob).mockResolvedValue({ job, creditBalance: 4.9 })

    show({ contentRevision: 3 })
    await user.click(await screen.findByRole('button', { name: '그림 제안 확인' }))

    expect(createIllustrationSuggestionJob).toHaveBeenCalledWith('conversion-1', {
      request_id: 'generated-uuid',
      expected_content_revision: 3,
    })
    expect(
      await screen.findByText('그림 제안을 분석하고 있어요. 다른 화면으로 이동해도 계속됩니다.'),
    ).toBeInTheDocument()
  })

  it('접수가 실패하면 같은 request_id로 다시 보낸다', async () => {
    const user = userEvent.setup()
    vi.mocked(createIllustrationSuggestionJob)
      .mockRejectedValueOnce(new ApiError(0, '네트워크'))
      .mockResolvedValueOnce({ job, creditBalance: 4.9 })

    show()
    await user.click(await screen.findByRole('button', { name: '그림 제안 확인' }))
    await user.click(await screen.findByRole('button', { name: '같은 요청 다시 보내기' }))

    await waitFor(() => expect(createIllustrationSuggestionJob).toHaveBeenCalledTimes(2))
    const first = vi.mocked(createIllustrationSuggestionJob).mock.calls.at(0)?.[1]
    expect(first?.request_id).toBe('generated-uuid')
    expect(vi.mocked(createIllustrationSuggestionJob).mock.calls.at(1)?.[1]).toEqual(first)
  })

  it('재방문하면 활성 작업을 목록에서 찾아 폴링을 이어간다', async () => {
    vi.useFakeTimers()
    try {
      vi.mocked(listIllustrationSuggestionJobs).mockResolvedValue({ ...jobs, active_job: job })
      vi.mocked(getIllustrationSuggestionJob).mockResolvedValue({ ...job, status: 'running' })

      show()
      await act(async () => {
        await Promise.resolve()
      })
      expect(
        screen.getByText('그림 제안을 분석하고 있어요. 다른 화면으로 이동해도 계속됩니다.'),
      ).toBeInTheDocument()
      expect(createIllustrationSuggestionJob).not.toHaveBeenCalled()

      vi.mocked(getIllustrationSuggestionJob).mockResolvedValue({ ...job, status: 'succeeded' })
      vi.mocked(getIllustrationSuggestions).mockResolvedValue(ready)
      vi.mocked(listIllustrationSuggestionJobs).mockResolvedValue({
        ...jobs,
        latest_job: { ...job, status: 'succeeded' },
      })
      await act(async () => {
        await vi.advanceTimersByTimeAsync(3000)
      })

      expect(screen.getByText('신청 순서를 그림으로 보면 이해하기 쉬워집니다')).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('실패한 작업은 원인과 이용량 반환을 함께 알리고 자동으로 다시 분석하지 않는다', async () => {
    vi.mocked(listIllustrationSuggestionJobs).mockResolvedValue({
      ...jobs,
      latest_job: { ...job, status: 'failed', failure_code: 'generation_failed' },
    })

    show()

    expect(await screen.findByText(/그림 제안을 만들지 못했습니다/)).toBeInTheDocument()
    expect(screen.getByText(/예약한 이용량은 반환됐습니다/)).toBeInTheDocument()
    expect(createIllustrationSuggestionJob).not.toHaveBeenCalled()
  })

  it('결과 형식 실패와 결과 불명확 실패를 구분해 적는다', async () => {
    vi.mocked(listIllustrationSuggestionJobs).mockResolvedValue({
      ...jobs,
      latest_job: { ...job, status: 'failed', failure_code: 'result_invalid' },
    })
    const { rerender } = show()
    expect(await screen.findByText(/원문 근거와 맞지 않아/)).toBeInTheDocument()

    vi.mocked(listIllustrationSuggestionJobs).mockResolvedValue({
      ...jobs,
      latest_job: { ...job, status: 'failed', failure_code: 'outcome_unknown' },
    })
    rerender(<IllustrationSuggestionsPanel {...defaults} conversionId="conversion-2" />)
    expect(await screen.findByText(/분석 결과를 확인하지 못했습니다/)).toBeInTheDocument()
  })

  it('작업 중 본문이 바뀌어 취소된 작업을 알린다', async () => {
    vi.mocked(listIllustrationSuggestionJobs).mockResolvedValue({
      ...jobs,
      latest_job: { ...job, status: 'superseded' },
    })

    show()

    expect(await screen.findByText(/분석 중 본문이 바뀌어/)).toBeInTheDocument()
  })
})

describe('ER-17 그림 제안 패널 — 결과', () => {
  it('제안 카드에 목적·이유·본문 줄·원문 인용·장면·보존 사실·대체텍스트를 모두 보여 준다', async () => {
    vi.mocked(getIllustrationSuggestions).mockResolvedValue(ready)

    show()

    expect(await screen.findByRole('heading', { name: '제안 1 · 절차' })).toBeInTheDocument()
    expect(screen.getByText('신청 순서를 그림으로 보면 이해하기 쉬워집니다')).toBeInTheDocument()
    expect(screen.getByText('본문 1–3줄')).toBeInTheDocument()
    expect(screen.getByText(/신청서를 씁니다\./)).toBeInTheDocument()
    expect(screen.getByText('원문 1줄: 신청서를 제출합니다')).toBeInTheDocument()
    expect(screen.getByText('신청서를 쓰는 장면')).toBeInTheDocument()
    expect(screen.getByText('직접 방문해야 합니다')).toBeInTheDocument()
    expect(screen.getByText('신청 순서를 차례대로 보여 주는 그림')).toBeInTheDocument()
  })

  it('그림 만들기 버튼은 다음 단계 안내와 함께 비활성이다', async () => {
    vi.mocked(getIllustrationSuggestions).mockResolvedValue(ready)

    show()

    const create = await screen.findByRole('button', { name: '이 내용으로 그림 만들기' })
    expect(create).toBeDisabled()
    expect(create).toHaveAccessibleDescription(/다음 단계에서 제공합니다/)
  })

  it('한 줄만 가리키는 제안은 단일 줄로 적는다', async () => {
    vi.mocked(getIllustrationSuggestions).mockResolvedValue({
      ...ready,
      suggestions: [{ ...suggestion, body_range: { start: 1, end: 1 } }],
    })

    show()

    expect(await screen.findByText('본문 2줄')).toBeInTheDocument()
  })

  it('제안 없음은 실패가 아니라 정상 결과로 적는다', async () => {
    vi.mocked(getIllustrationSuggestions).mockResolvedValue({
      ...ready,
      status: 'no_suggestions',
      suggestions: [],
    })

    show()

    expect(
      await screen.findByText('이 문서에서 추가 그림이 도움이 될 부분을 찾지 못했습니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('stale 결과는 이전 버전으로 표시하고 그림 만들기를 막은 채 재분석을 권한다', async () => {
    vi.mocked(getIllustrationSuggestions).mockResolvedValue({
      ...ready,
      status: 'stale',
      content_revision: 2,
    })

    show({ contentRevision: 2 })

    expect(await screen.findByText(/이전 버전의 본문으로 만든 제안입니다/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '이 내용으로 그림 만들기' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '그림 제안 다시 확인' })).toBeEnabled()
  })

  it('본문을 저장해 revision이 오르면 스스로 다시 읽어 이전 버전으로 바꾼다', async () => {
    vi.mocked(getIllustrationSuggestions)
      .mockResolvedValueOnce(ready)
      .mockResolvedValue({
        ...ready,
        status: 'stale',
        content_revision: 2,
      })

    const { rerender } = show()
    expect(await screen.findByRole('heading', { name: '제안 1 · 절차' })).toBeInTheDocument()

    rerender(<IllustrationSuggestionsPanel {...defaults} contentRevision={2} />)

    expect(await screen.findByText(/이전 버전의 본문으로 만든 제안입니다/)).toBeInTheDocument()
    expect(createIllustrationSuggestionJob).not.toHaveBeenCalled()
  })

  it('버려진 제안 수는 중립적인 안내로 적는다', async () => {
    vi.mocked(getIllustrationSuggestions).mockResolvedValue({ ...ready, dropped_count: 2 })

    show()

    expect(
      await screen.findByText('원문에서 근거를 확인하지 못한 제안 2건은 제외했습니다.'),
    ).toBeInTheDocument()
  })
})

describe('ER-17 그림 제안 패널 — 오류', () => {
  const cases: readonly { status: number; pattern: RegExp }[] = [
    { status: 402, pattern: /이용량이 부족합니다/ },
    { status: 409, pattern: /다른 화면에서 본문이 바뀌었거나/ },
    { status: 429, pattern: /그림 제안 분석 횟수를 모두 사용했습니다/ },
    { status: 503, pattern: /잠시 후 다시 시도해 주세요/ },
  ]

  for (const { status, pattern } of cases) {
    it(`${status} 응답을 이 기능의 문구로 옮긴다`, async () => {
      const user = userEvent.setup()
      vi.mocked(createIllustrationSuggestionJob).mockRejectedValue(new ApiError(status, '실패'))

      show()
      await user.click(await screen.findByRole('button', { name: '그림 제안 확인' }))

      expect(await screen.findByRole('alert')).toHaveTextContent(pattern)
    })
  }

  it('402 뒤에는 이용량 화면 이동을 함께 제공한다', async () => {
    const user = userEvent.setup()
    vi.mocked(createIllustrationSuggestionJob).mockRejectedValue(new ApiError(402, '부족'))

    show()
    await user.click(await screen.findByRole('button', { name: '그림 제안 확인' }))

    expect(await screen.findByRole('link', { name: '이용량 화면으로 이동' })).toBeInTheDocument()
  })

  it('조회에 실패하면 오류를 알리고 요청 버튼을 만들지 않는다', async () => {
    vi.mocked(getIllustrationSuggestions).mockRejectedValue(new ApiError(503, '조회 실패'))

    show()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      /그림 제안 상태를 불러오지 못했습니다/,
    )
    expect(screen.queryByRole('button', { name: '그림 제안 확인' })).not.toBeInTheDocument()
  })
})
