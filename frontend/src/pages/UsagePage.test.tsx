import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '../api/client'
import { getWorkspaceCredits } from '../api/credits'
import { createInvoiceRequest, listInvoiceRequests } from '../api/invoices'
import { getWorkspaceUsage } from '../api/usage'
import {
  invoiceRequest,
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

vi.mock('../api/invoices', () => ({
  createInvoiceRequest: vi.fn(),
  listInvoiceRequests: vi.fn(),
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
  // 세금계산서 요청도 같은 이유로 기본값은 빈 목록이다 — 그 화면을 다루는 테스트만
  // 값을 명시로 덮어쓴다.
  vi.mocked(listInvoiceRequests).mockReset().mockResolvedValue({ items: [] })
  vi.mocked(createInvoiceRequest).mockReset()
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

  it('실패 호출이 0이면 열 자체가 없다 (계약 2.26.0)', async () => {
    vi.mocked(getWorkspaceUsage).mockResolvedValue(workspaceUsage({ failed_calls: 0 }))

    renderPage()

    const table = await screen.findByRole('table', {
      name: /이 기간 사용량 합계입니다/,
    })
    expect(within(table).queryByText('실패 호출')).not.toBeInTheDocument()
  })

  it('실패 호출이 있으면 열이 나타나고 값을 보여준다 (계약 2.26.0)', async () => {
    // credits 기본값(2)과 겹치지 않는 값을 쓴다 — 같은 표 안에서 텍스트가 유일해야 한다.
    vi.mocked(getWorkspaceUsage).mockResolvedValue(workspaceUsage({ failed_calls: 5 }))

    renderPage()

    const table = await screen.findByRole('table', {
      name: /이 기간 사용량 합계입니다/,
    })
    expect(within(table).getByText('실패 호출')).toBeInTheDocument()
    expect(within(table).getByText('5')).toBeInTheDocument()
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

  it('모든 행의 실패 호출이 0이면 열 자체가 없다 (계약 2.26.0)', async () => {
    vi.mocked(getWorkspaceUsage).mockResolvedValue(
      workspaceUsage({
        by_purpose: [purposeUsageItem({ purpose: 'convert', failed_calls: 0 })],
      }),
    )

    renderPage()

    const table = await screen.findByRole('table', { name: /목적.*집계입니다/ })
    expect(within(table).queryByText('실패 호출')).not.toBeInTheDocument()
  })

  it('한 행이라도 실패 호출이 있으면 모든 행에 열이 나타난다 (계약 2.26.0)', async () => {
    vi.mocked(getWorkspaceUsage).mockResolvedValue(
      workspaceUsage({
        by_purpose: [
          purposeUsageItem({ purpose: 'convert', failed_calls: 0 }),
          purposeUsageItem({ purpose: 'repair', llm_calls: 0, failed_calls: 4 }),
        ],
      }),
    )

    renderPage()

    const table = await screen.findByRole('table', { name: /목적.*집계입니다/ })
    expect(within(table).getByText('실패 호출')).toBeInTheDocument()
    expect(within(table).getByText('4')).toBeInTheDocument()
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

  it('signup_grant_skipped가 참이면 가입 크레딧 재수령 불가 안내를 보여준다', async () => {
    vi.mocked(getWorkspaceCredits).mockResolvedValue(
      workspaceCredits({ signup_grant_skipped: true }),
    )

    renderPage()

    expect(
      await screen.findByText(
        '이 이메일은 이전에 가입 크레딧을 받은 적이 있어 이번에는 제공되지 않았습니다.',
      ),
    ).toBeInTheDocument()
  })

  it('signup_grant_skipped가 거짓이면 그 안내를 보여주지 않는다', async () => {
    vi.mocked(getWorkspaceCredits).mockResolvedValue(
      workspaceCredits({ signup_grant_skipped: false }),
    )

    renderPage()

    await screen.findByRole('heading', { name: '크레딧' })
    expect(
      screen.queryByText(
        '이 이메일은 이전에 가입 크레딧을 받은 적이 있어 이번에는 제공되지 않았습니다.',
      ),
    ).not.toBeInTheDocument()
  })

  it('cycle_ends_at이 있으면 이번 주기 이용량·남은 양·초기화일을 보여준다', async () => {
    const cycleEndsAt = '2026-10-10T00:00:00Z'
    vi.mocked(getWorkspaceCredits).mockResolvedValue(
      workspaceCredits({ allowance: 50, available: 34, cycle_ends_at: cycleEndsAt }),
    )

    renderPage()

    const expectedDate = new Date(cycleEndsAt).toLocaleDateString('ko-KR')
    expect(
      await screen.findByText(`이번 주기 50 중 34 남음 · ${expectedDate} 초기화`),
    ).toBeInTheDocument()
  })

  it('cycle_ends_at이 없으면(주기 없음) 기존 문구를 유지하고 주기 문구를 보여주지 않는다', async () => {
    vi.mocked(getWorkspaceCredits).mockResolvedValue(
      workspaceCredits({ allowance: 0, cycle_ends_at: null }),
    )

    renderPage()

    await screen.findByRole('heading', { name: '크레딧' })
    expect(screen.queryByText(/이번 주기/)).not.toBeInTheDocument()
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

describe('세금계산서 요청 (2.24.0)', () => {
  beforeEach(() => {
    // 이 describe는 세금계산서 요청만 재므로, 이 화면이 함께 부르는 다른 조회는
    // 기본값으로 채워 둔다 — 그렇지 않으면 unmocked 호출이 undefined를 돌려주고
    // 화면의 .then() 호출이 던진다.
    vi.mocked(getWorkspaceUsage).mockResolvedValue(workspaceUsage())
    vi.mocked(getWorkspaceCredits).mockResolvedValue(workspaceCredits())
  })

  async function fillAndSubmit(user: ReturnType<typeof userEvent.setup>) {
    await user.click(screen.getByRole('button', { name: '세금계산서 요청' }))
    await user.type(screen.getByLabelText('사업자등록번호'), '220-81-62517')
    await user.type(screen.getByLabelText('상호'), '쉬운글 주식회사')
    await user.type(screen.getByLabelText('연락 이메일'), 'billing@example.test')
    await user.click(screen.getByRole('button', { name: '요청 보내기' }))
  }

  it('목록이 비어 있으면 안내 문구를 보여준다', async () => {
    renderPage()

    const table = await screen.findByRole('table', { name: /세금계산서 요청 목록입니다/ })
    expect(within(table).getByText('아직 요청이 없습니다.')).toBeInTheDocument()
  })

  it('폼을 열면 기간 시작일·종료일이 지난달 1일·말일로 채워져 있다', async () => {
    const user = userEvent.setup()
    renderPage()
    await screen.findByRole('table', { name: /세금계산서 요청 목록입니다/ })

    await user.click(screen.getByRole('button', { name: '세금계산서 요청' }))

    // 화면과 같은 계산(로컬 날짜, YYYY-MM-DD)을 테스트에서도 독립적으로 구해 비교한다 —
    // 화면 구현의 헬퍼를 그대로 가져오면 구현을 베끼는 대조가 된다.
    const today = new Date()
    const pad = (value: number) => String(value).padStart(2, '0')
    const format = (date: Date) =>
      `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    const expectedFrom = format(new Date(today.getFullYear(), today.getMonth() - 1, 1))
    const expectedTo = format(new Date(today.getFullYear(), today.getMonth(), 0))

    expect(screen.getByLabelText('기간 시작일')).toHaveValue(expectedFrom)
    expect(screen.getByLabelText('기간 종료일')).toHaveValue(expectedTo)
  })

  it('목록이 기간·상호·상태·요청일·운영자 메모를 한국어로 보여준다', async () => {
    vi.mocked(listInvoiceRequests).mockResolvedValue({
      items: [
        invoiceRequest({
          period_from: '2026-08-01',
          period_to: '2026-08-31',
          company_name: '쉬운글 주식회사',
          status: 'rejected',
          operator_note: '사업자번호 확인 불가',
        }),
      ],
    })

    renderPage()

    const table = await screen.findByRole('table', { name: /세금계산서 요청 목록입니다/ })
    expect(within(table).getByText('2026-08-01 ~ 2026-08-31')).toBeInTheDocument()
    expect(within(table).getByText('쉬운글 주식회사')).toBeInTheDocument()
    expect(within(table).getByText('거절됨')).toBeInTheDocument()
    expect(within(table).getByText('사업자번호 확인 불가')).toBeInTheDocument()
  })

  it('버튼을 누르면 폼이 열리고, 제출에 성공하면 상태 안내와 함께 목록을 다시 부른다', async () => {
    const user = userEvent.setup()
    vi.mocked(createInvoiceRequest).mockResolvedValue(invoiceRequest())

    renderPage()
    await screen.findByRole('table', { name: /세금계산서 요청 목록입니다/ })
    vi.mocked(listInvoiceRequests).mockClear()

    await fillAndSubmit(user)

    expect(await screen.findByRole('status')).toHaveTextContent('세금계산서 요청을 접수했습니다')
    expect(createInvoiceRequest).toHaveBeenCalledWith(
      'w1',
      expect.objectContaining({
        business_number: '220-81-62517',
        company_name: '쉬운글 주식회사',
        contact_email: 'billing@example.test',
      }),
    )
    await waitFor(() => {
      expect(listInvoiceRequests).toHaveBeenCalled()
    })
    // 폼은 성공 뒤 닫힌다.
    expect(screen.queryByRole('button', { name: '요청 보내기' })).not.toBeInTheDocument()
  })

  it('422 응답의 문구를 role=alert로 그대로 보여준다', async () => {
    const user = userEvent.setup()
    vi.mocked(createInvoiceRequest).mockRejectedValue(
      new ApiError(422, '사업자등록번호가 올바르지 않습니다'),
    )

    renderPage()
    await screen.findByRole('table', { name: /세금계산서 요청 목록입니다/ })

    await fillAndSubmit(user)

    expect(await screen.findByRole('alert')).toHaveTextContent('사업자등록번호가 올바르지 않습니다')
  })

  it('409 응답의 문구를 role=alert로 그대로 보여준다', async () => {
    const user = userEvent.setup()
    vi.mocked(createInvoiceRequest).mockRejectedValue(
      new ApiError(409, '같은 기간의 요청이 처리 대기 중입니다'),
    )

    renderPage()
    await screen.findByRole('table', { name: /세금계산서 요청 목록입니다/ })

    await fillAndSubmit(user)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '같은 기간의 요청이 처리 대기 중입니다',
    )
  })

  it('목록 조회가 실패하면 오류 문구를 보여준다', async () => {
    vi.mocked(listInvoiceRequests).mockRejectedValue(
      new ApiError(500, '세금계산서 요청 목록을 불러오지 못했습니다'),
    )

    renderPage()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '세금계산서 요청 목록을 불러오지 못했습니다',
    )
  })
})
