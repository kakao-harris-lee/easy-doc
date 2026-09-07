import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '../api/client'
import { getWorkspaceUsage } from '../api/usage'
import { purposeUsageItem, workspaceContext, workspaceUsage } from '../test/factories'
import { WorkspaceContext } from '../workspace/context'
import type { WorkspaceContextValue } from '../workspace/context'
import { UsagePage } from './UsagePage'

vi.mock('../api/usage', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/usage')>()),
  getWorkspaceUsage: vi.fn(),
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
