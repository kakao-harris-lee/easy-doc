import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '../api/client'
import { getWorkspaceCredits } from '../api/credits'
import { getWorkspaceUsage } from '../api/usage'
import {
  purposeUsageItem,
  workspaceContext,
  workspaceCredits,
  workspaceUsage,
} from '../test/factories'
import { WorkspaceContext } from '../workspace/context'
import type { WorkspaceContextValue } from '../workspace/context'
import { UsagePage } from './UsagePage'

vi.mock('../api/usage', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/usage')>()),
  getWorkspaceUsage: vi.fn(),
}))

vi.mock('../api/credits', () => ({
  getWorkspaceCredits: vi.fn(),
}))

function page(workspace: Partial<WorkspaceContextValue> = {}) {
  return (
    <WorkspaceContext.Provider value={workspaceContext(workspace)}>
      <MemoryRouter>
        <UsagePage />
      </MemoryRouter>
    </WorkspaceContext.Provider>
  )
}

function renderPage(workspace: Partial<WorkspaceContextValue> = {}) {
  return render(page(workspace))
}

beforeEach(() => {
  vi.mocked(getWorkspaceUsage).mockReset()
  // 기본값은 크레딧 카드를 다루지 않는 기존 시나리오가 흔들리지 않도록 항상 성공한다.
  // 크레딧 자체를 재는 테스트만 값을 명시로 덮어쓴다.
  vi.mocked(getWorkspaceCredits).mockReset().mockResolvedValue(workspaceCredits())
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('합계 표', () => {
  it('문서·문자·크레딧·호출·토큰·예상 비용을 보여준다', async () => {
    vi.mocked(getWorkspaceUsage).mockResolvedValue(workspaceUsage())

    renderPage()

    const table = await screen.findByRole('table', {
      name: /이 기간 사용량 합계입니다/,
    })
    expect(within(table).getByText('1건')).toBeInTheDocument()
    expect(within(table).getByText('1,500자')).toBeInTheDocument()
    expect(within(table).getByText('$0.001000')).toBeInTheDocument()
  })

  it('알려진 비용이 없으면 「모름」이라고 적는다 — "0"이 아니다', async () => {
    vi.mocked(getWorkspaceUsage).mockResolvedValue(
      workspaceUsage({ estimated_cost_usd: null, cost_unknown_calls: 1 }),
    )

    renderPage()

    const table = await screen.findByRole('table', {
      name: /이 기간 사용량 합계입니다/,
    })
    expect(within(table).getByText('모름')).toBeInTheDocument()
  })

  it('비용 미상 건수는 0이면 열 자체가 없다', async () => {
    vi.mocked(getWorkspaceUsage).mockResolvedValue(workspaceUsage({ cost_unknown_calls: 0 }))

    renderPage()

    const table = await screen.findByRole('table', {
      name: /이 기간 사용량 합계입니다/,
    })
    expect(within(table).queryByText('비용 미상 건수')).not.toBeInTheDocument()
  })

  it('비용 미상 건수가 있으면 열이 나타나고 값을 보여준다', async () => {
    vi.mocked(getWorkspaceUsage).mockResolvedValue(
      workspaceUsage({ estimated_cost_usd: null, cost_unknown_calls: 3 }),
    )

    renderPage()

    const table = await screen.findByRole('table', {
      name: /이 기간 사용량 합계입니다/,
    })
    expect(within(table).getByText('비용 미상 건수')).toBeInTheDocument()
    expect(within(table).getByText('3')).toBeInTheDocument()
  })
})

describe('목적별 표', () => {
  it('purpose별 소계를 한국어 이름으로 보여준다', async () => {
    vi.mocked(getWorkspaceUsage).mockResolvedValue(
      workspaceUsage({
        by_purpose: [
          purposeUsageItem({ purpose: 'convert', llm_calls: 3 }),
          purposeUsageItem({ purpose: 'repair', llm_calls: 2, estimated_cost_usd: null }),
        ],
      }),
    )

    renderPage()

    const table = await screen.findByRole('table', { name: /목적.*집계입니다/ })
    expect(within(table).getByText('변환')).toBeInTheDocument()
    expect(within(table).getByText('보정')).toBeInTheDocument()
  })

  it('그 기간에 호출이 없으면 목적별 표가 빈 상태 문구를 보여준다', async () => {
    vi.mocked(getWorkspaceUsage).mockResolvedValue(workspaceUsage({ by_purpose: [] }))

    renderPage()

    const table = await screen.findByRole('table', { name: /목적.*집계입니다/ })
    expect(within(table).getByText('이 기간에 호출이 없습니다.')).toBeInTheDocument()
  })
})

describe('오류', () => {
  it('422 응답의 문구를 그대로 보여준다', async () => {
    vi.mocked(getWorkspaceUsage).mockRejectedValue(
      new ApiError(422, 'to는 from보다 앞일 수 없습니다'),
    )

    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('to는 from보다 앞일 수 없습니다')
  })
})

describe('기간 변경', () => {
  it('지난달을 고르면 그 기간으로 다시 조회한다', async () => {
    const user = userEvent.setup()
    vi.mocked(getWorkspaceUsage).mockResolvedValue(workspaceUsage())

    renderPage()
    await screen.findByRole('table', { name: /이 기간 사용량 합계입니다/ })
    vi.mocked(getWorkspaceUsage).mockClear()
    vi.mocked(getWorkspaceUsage).mockResolvedValue(workspaceUsage({ documents: 9 }))

    await user.click(screen.getByRole('radio', { name: '지난달' }))

    await waitFor(() => {
      expect(getWorkspaceUsage).toHaveBeenCalledTimes(1)
    })
    const [, params] = vi.mocked(getWorkspaceUsage).mock.calls[0] ?? []
    expect(params).toEqual(
      expect.objectContaining({
        from: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/),
        to: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/),
      }),
    )
    const table = await screen.findByRole('table', { name: /이 기간 사용량 합계입니다/ })
    expect(within(table).getByText('9건')).toBeInTheDocument()
  })

  it('직접 입력은 두 날짜가 모두 채워져야 조회한다', async () => {
    const user = userEvent.setup()
    vi.mocked(getWorkspaceUsage).mockResolvedValue(workspaceUsage())

    renderPage()
    await screen.findByRole('table', { name: /이 기간 사용량 합계입니다/ })
    vi.mocked(getWorkspaceUsage).mockClear()

    await user.click(screen.getByRole('radio', { name: '직접 입력' }))
    // jsdom의 date input은 세그먼트 입력을 흉내 내지 못해 change로 직접 값을 넣는다.
    fireEvent.change(screen.getByLabelText('시작일'), { target: { value: '2026-01-01' } })

    // from만 채운 상태에서는 아직 조회하지 않는다.
    expect(getWorkspaceUsage).not.toHaveBeenCalled()

    fireEvent.change(screen.getByLabelText('종료일'), { target: { value: '2026-01-31' } })

    await waitFor(() => {
      expect(getWorkspaceUsage).toHaveBeenCalledWith(
        'w1',
        { from: '2026-01-01', to: '2026-01-31' },
        expect.anything(),
      )
    })
  })

  it('직접 입력으로 바꾸면 아직 채우지 않은 동안 이전 기간의 표가 남지 않는다', async () => {
    const user = userEvent.setup()
    vi.mocked(getWorkspaceUsage).mockResolvedValue(workspaceUsage({ documents: 9 }))

    renderPage()
    await screen.findByRole('table', { name: /이 기간 사용량 합계입니다/ })

    await user.click(screen.getByRole('radio', { name: '직접 입력' }))

    // 새 기간이 아직 없으므로 지난 기간(이번 달)의 숫자가 그대로 남아 있으면 안 된다.
    expect(
      screen.queryByRole('table', { name: /이 기간 사용량 합계입니다/ }),
    ).not.toBeInTheDocument()
    expect(screen.queryByText('9건')).not.toBeInTheDocument()
  })
})

describe('크레딧 카드 (C1/C2)', () => {
  beforeEach(() => {
    // 이 describe는 크레딧만 재므로, 별도 사용량 화면이 필요로 하는 조회는 기본값으로
    // 채워 둔다 — 그렇지 않으면 unmocked getWorkspaceUsage가 undefined를 돌려주고
    // 화면의 .then() 호출이 던진다.
    vi.mocked(getWorkspaceUsage).mockResolvedValue(workspaceUsage())
  })

  it('가용·잔액·예약 중을 보여준다', async () => {
    vi.mocked(getWorkspaceCredits).mockResolvedValue(
      workspaceCredits({ balance: 10, reserved: 3, available: 7 }),
    )

    renderPage()

    expect(await screen.findByRole('heading', { name: '크레딧' })).toBeInTheDocument()
    expect(screen.getByText('가용')).toBeInTheDocument()
    expect(screen.getByText('7')).toBeInTheDocument()
    expect(screen.getByText('잔액')).toBeInTheDocument()
    expect(screen.getByText('10')).toBeInTheDocument()
    expect(screen.getByText('예약 중')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
  })

  it('집행이 꺼져 있으면 그 사실을 덧붙인다', async () => {
    vi.mocked(getWorkspaceCredits).mockResolvedValue(workspaceCredits({ enforced: false }))

    renderPage()

    expect(await screen.findByText('(지금은 집행되지 않습니다)')).toBeInTheDocument()
  })

  it('집행 중이면 그 안내를 보여주지 않는다', async () => {
    vi.mocked(getWorkspaceCredits).mockResolvedValue(workspaceCredits({ enforced: true }))

    renderPage()

    await screen.findByRole('heading', { name: '크레딧' })
    expect(screen.queryByText('(지금은 집행되지 않습니다)')).not.toBeInTheDocument()
  })

  it('거래 표가 종류·크레딧(부호)·사유·메모·일시를 한국어로 보여준다', async () => {
    vi.mocked(getWorkspaceCredits).mockResolvedValue(
      workspaceCredits({
        transactions: [
          {
            id: 't1',
            kind: 'reserve',
            credits: -3,
            reason: 'conversion',
            note: null,
            document_id: 'd1',
            created_at: '2026-09-01T00:00:00Z',
          },
          {
            id: 't2',
            kind: 'grant',
            credits: 50,
            reason: 'manual',
            note: '파일럿 충전',
            document_id: null,
            created_at: '2026-09-02T00:00:00Z',
          },
        ],
      }),
    )

    renderPage()

    const table = await screen.findByRole('table', { name: /최근 크레딧 거래 내역입니다/ })
    expect(within(table).getByText('예약')).toBeInTheDocument()
    expect(within(table).getByText('-3')).toBeInTheDocument()
    expect(within(table).getByText('문서 변환')).toBeInTheDocument()
    expect(within(table).getByText('부여')).toBeInTheDocument()
    expect(within(table).getByText('+50')).toBeInTheDocument()
    expect(within(table).getByText('수동')).toBeInTheDocument()
    expect(within(table).getByText('파일럿 충전')).toBeInTheDocument()
  })

  it('document_id가 있으면 문서로 가는 링크를 보여준다', async () => {
    vi.mocked(getWorkspaceCredits).mockResolvedValue(
      workspaceCredits({
        transactions: [
          {
            id: 't1',
            kind: 'reserve',
            credits: -3,
            reason: 'conversion',
            note: null,
            document_id: 'd1',
            created_at: '2026-09-01T00:00:00Z',
          },
        ],
      }),
    )

    renderPage()

    const table = await screen.findByRole('table', { name: /최근 크레딧 거래 내역입니다/ })
    expect(within(table).getByRole('link', { name: '문서 d1 보기' })).toHaveAttribute(
      'href',
      '/history',
    )
  })

  it('document_id가 없으면 링크 대신 대시를 보여준다', async () => {
    vi.mocked(getWorkspaceCredits).mockResolvedValue(
      workspaceCredits({
        transactions: [
          {
            id: 't1',
            kind: 'grant',
            credits: 50,
            reason: 'manual',
            note: null,
            document_id: null,
            created_at: '2026-09-01T00:00:00Z',
          },
        ],
      }),
    )

    renderPage()

    const table = await screen.findByRole('table', { name: /최근 크레딧 거래 내역입니다/ })
    expect(within(table).queryByRole('link')).not.toBeInTheDocument()
  })

  it('조회가 실패하면 오류 문구를 보여준다', async () => {
    vi.mocked(getWorkspaceCredits).mockRejectedValue(
      new ApiError(500, '크레딧 계정을 불러오지 못했습니다'),
    )

    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent('크레딧 계정을 불러오지 못했습니다')
  })
})
