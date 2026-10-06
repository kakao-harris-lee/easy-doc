import { act, render, screen, waitFor, within, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as admin from '../../api/admin'
import { getAdminBilling, getSubscription, refundTossPayment } from '../../api/subscriptions'
import { ApiError } from '../../api/client'
import type { AdminMonthlySummary } from '../../api/adminMonthlyTypes'
import {
  adminWorkspaceDetail,
  adminWorkspaceListResponse,
  adminWorkspaceSummary,
} from '../../test/factories'
import { AdminWorkspacesTab } from './AdminWorkspacesTab'
import { currentMonth, shiftMonth, validMonth } from './adminMonths'

vi.mock('../../api/admin', () => ({
  listAdminWorkspaces: vi.fn(),
  readAdminWorkspace: vi.fn(),
  adjustAdminWorkspaceCredits: vi.fn(),
  readAdminMonthlySummary: vi.fn(),
  readAdminMonthlyHistory: vi.fn(),
  listAdminCreditTransactions: vi.fn(),
  listAdminPayments: vi.fn(),
}))
vi.mock('../../api/subscriptions', () => ({
  getAdminBilling: vi.fn(),
  adminBillingAction: vi.fn(),
  getSubscription: vi.fn(),
  refundTossPayment: vi.fn(),
}))

function monthly(month = currentMonth()): AdminMonthlySummary {
  return {
    workspace_id: 'w1',
    month,
    timezone: 'Asia/Seoul',
    is_current_month: month === currentMonth(),
    current: {
      balance: 100,
      reserved: 10,
      available: 90,
      allowance: 200,
      cycle_started_at: null,
      cycle_ends_at: null,
      subscription_status: null,
      as_of: '2026-09-01T01:00:00Z',
      revision: 4,
    },
    credits: {
      opening: 100,
      granted: 20,
      consumed: 10,
      expired: 10,
      adjustment: 0,
      cycle_net: 0,
      closing: 100,
      reserved: 10,
      available: 90,
      legacy_cycle_count: 0,
    },
    usage: {
      documents: 3,
      input_tokens: 1000,
      output_tokens: 500,
      known_cost_usd: '0.12',
      unknown_cost_calls: 1,
      credits_by_reason: { illustration_suggestion: 10 },
      estimated_legacy_credits: 0,
    },
    payments: {
      paid_krw: 5000,
      refunded_krw: 1000,
      net_krw: 4000,
      test_paid_krw: 2000,
      test_refunded_krw: 0,
      unknown_date_paid_krw: 0,
      unknown_date_refund_krw: 0,
      test_unknown_date_paid_krw: 0,
      test_unknown_date_refund_krw: 0,
    },
    completeness: { ledger_matches_account: true, monthly_equation_matches: true, warnings: [] },
  }
}
const page = { items: [], page: 1, size: 20, total: 0 }
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getAdminBilling).mockResolvedValue({ orders: [], operations: [] })
  sessionStorage.clear()
  window.history.replaceState({}, '', '/admin')
  vi.mocked(admin.listAdminWorkspaces).mockResolvedValue(
    adminWorkspaceListResponse({
      items: [
        adminWorkspaceSummary({
          selected_month: {
            month: currentMonth(),
            credits: 10,
            paid_krw: 5000,
            refunded_krw: 1000,
          },
        }),
      ],
    }),
  )
  vi.mocked(admin.readAdminWorkspace).mockResolvedValue(adminWorkspaceDetail())
  vi.mocked(admin.readAdminMonthlySummary).mockImplementation((_id, month) =>
    Promise.resolve(monthly(month)),
  )
  vi.mocked(admin.readAdminMonthlyHistory).mockResolvedValue({
    workspace_id: 'w1',
    year: 2026,
    timezone: 'Asia/Seoul',
    items: [monthly()],
  })
  vi.mocked(admin.listAdminCreditTransactions).mockResolvedValue(page)
  vi.mocked(admin.listAdminPayments).mockResolvedValue(page)
  vi.mocked(getSubscription).mockResolvedValue({
    mock_enabled: false,
    plans: [],
    subscription: null,
    payments: [],
  })
})
async function open() {
  const user = userEvent.setup()
  render(<AdminWorkspacesTab />)
  await user.click(await screen.findByRole('button', { name: /의 .* 관리/ }))
  await screen.findByRole('form', { name: '크레딧 부여 및 조정' })
  return user
}
describe('월별 관리자 작업공간', () => {
  it('현재 가용과 선택 월 사용을 구분해 표시하고 월·검색·선택을 URL에 유지한다', async () => {
    const user = await open()
    expect(screen.getByRole('columnheader', { name: '가용/사용 크레딧' })).toBeInTheDocument()
    expect(screen.queryByText('가용/잔액/예약')).not.toBeInTheDocument()
    expect(screen.getByText('처리 중 확보 크레딧')).toBeInTheDocument()
    expect(new URLSearchParams(window.location.search).get('workspace')).toBe('w1')
    await user.click(screen.getByRole('button', { name: '이전 달' }))
    await waitFor(() =>
      expect(admin.listAdminWorkspaces).toHaveBeenLastCalledWith(
        expect.objectContaining({ month: shiftMonth(currentMonth(), -1) }),
        expect.anything(),
      ),
    )
    expect(new URLSearchParams(window.location.search).get('month')).toBe(
      shiftMonth(currentMonth(), -1),
    )
    await user.type(screen.getByLabelText('이름·소유자 이메일 검색'), 'owner')
    await user.click(screen.getByRole('button', { name: '검색' }))
    expect(new URLSearchParams(window.location.search).get('q')).toBe('owner')
  })
  it('기본 시작일이 있어도 종료일이 없으면 이용 주기가 없다고 표시한다', async () => {
    vi.mocked(admin.readAdminMonthlySummary).mockResolvedValue({
      ...monthly(),
      current: {
        ...monthly().current,
        cycle_started_at: '2026-01-01T00:00:00Z',
        cycle_ends_at: null,
      },
    })
    await open()
    const current = screen.getByRole('region', { name: '현재 크레딧' })
    expect(within(current).getByText('현재 이용 주기 없음')).toBeInTheDocument()
    expect(current).not.toHaveTextContent('2026. 1. 1.')
  })
  it.each(['2026-08-31T23:00:00Z', '2026-09-01T01:00:00Z'])(
    '조회 시각 이전 또는 같은 종료일 %s은 종료된 주기로 표시한다',
    async (end) => {
      vi.mocked(admin.readAdminMonthlySummary).mockResolvedValue({
        ...monthly(),
        current: {
          ...monthly().current,
          cycle_started_at: '2026-08-01T00:00:00Z',
          cycle_ends_at: end,
        },
      })
      await open()
      const current = screen.getByRole('region', { name: '현재 크레딧' })
      expect(current).toHaveTextContent('종료된 이용 주기:')
      expect(current).not.toHaveTextContent('현재 이용 주기:')
    },
  )
  it('아직 종료되지 않은 주기는 현재 이용 주기로 표시한다', async () => {
    vi.mocked(admin.readAdminMonthlySummary).mockResolvedValue({
      ...monthly(),
      current: {
        ...monthly().current,
        cycle_started_at: '2026-08-01T00:00:00Z',
        cycle_ends_at: '2026-09-01T01:00:01Z',
      },
    })
    await open()
    expect(screen.getByRole('region', { name: '현재 크레딧' })).toHaveTextContent('현재 이용 주기:')
  })
  it('연도 경계·미래 월·잘못된 월을 처리한다', () => {
    expect(shiftMonth('2026-01', -1)).toBe('2025-12')
    expect(shiftMonth('2024-12', 1)).toBe('2025-01')
    expect(shiftMonth('0001-01', -1)).toBe('0001-01')
    expect(validMonth('2026-13')).toBeNull()
    expect(validMonth(shiftMonth(currentMonth(), 1))).toBeNull()
  })
  it('URL의 월과 작업공간을 복원하고 현재 월 다음 버튼을 막는다', async () => {
    window.history.replaceState({}, '', `/admin?month=${currentMonth()}&workspace=w1&q=owner`)
    render(<AdminWorkspacesTab />)
    await screen.findByRole('form', { name: '크레딧 부여 및 조정' })
    expect(screen.getByRole('button', { name: '다음 달' })).toBeDisabled()
    expect(screen.getByLabelText('이름·소유자 이메일 검색')).toHaveValue('owner')
  })
  it('오래된 목록 응답이 새 월의 목록을 덮어쓰지 않는다', async () => {
    let resolveOld!: (value: ReturnType<typeof adminWorkspaceListResponse>) => void
    vi.mocked(admin.listAdminWorkspaces).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve
        }),
    )
    const user = userEvent.setup()
    render(<AdminWorkspacesTab />)
    await user.click(screen.getByRole('button', { name: '이전 달' }))
    await screen.findByRole('button', { name: /의 .* 관리/ })
    await act(async () =>
      resolveOld(
        adminWorkspaceListResponse({ items: [adminWorkspaceSummary({ name: '오래된 월' })] }),
      ),
    )
    expect(screen.queryByText('오래된 월')).not.toBeInTheDocument()
  })
  it('과거 월 상세의 늦은 응답이 새 월 결산을 덮어쓰지 않는다', async () => {
    let resolveOld!: (value: AdminMonthlySummary) => void
    vi.mocked(admin.readAdminMonthlySummary).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve
        }),
    )
    const user = userEvent.setup()
    render(<AdminWorkspacesTab />)
    await user.click(await screen.findByRole('button', { name: /의 .* 관리/ }))
    await screen.findByText('월별 결산을 불러오는 중입니다…')
    await user.click(screen.getByRole('button', { name: '이전 달' }))
    const expected = shiftMonth(currentMonth(), -1)
    await screen.findByRole('heading', { name: `${expected} 크레딧 결산` })
    await act(async () => resolveOld(monthly()))
    expect(screen.getByRole('heading', { name: `${expected} 크레딧 결산` })).toBeInTheDocument()
  })
  it('거래 페이지·종류 필터를 서버에 보내며 필터 변경 시 첫 페이지로 돌아간다', async () => {
    vi.mocked(admin.listAdminCreditTransactions).mockResolvedValue({ ...page, total: 55 })
    const user = await open()
    await user.click(
      within(screen.getByRole('navigation', { name: '크레딧 거래 페이지' })).getByRole('button', {
        name: '다음',
      }),
    )
    await waitFor(() =>
      expect(admin.listAdminCreditTransactions).toHaveBeenLastCalledWith(
        'w1',
        expect.objectContaining({ page: 2 }),
        expect.anything(),
      ),
    )
    await user.selectOptions(screen.getByLabelText('거래 종류'), 'consume')
    await waitFor(() =>
      expect(admin.listAdminCreditTransactions).toHaveBeenLastCalledWith(
        'w1',
        expect.objectContaining({ page: 1, kind: 'consume' }),
        expect.anything(),
      ),
    )
  })
  it('필수 메모·확보량 보호·정확한 변경 미리보기를 적용한다', async () => {
    const user = await open()
    await user.selectOptions(screen.getByLabelText('조정 종류'), 'withdraw')
    await user.type(screen.getByLabelText('크레딧 수량'), '91')
    await user.type(screen.getByLabelText('메모 (필수)'), '오지급 회수')
    await user.click(screen.getByRole('button', { name: '변경 내용 확인' }))
    expect(screen.getByRole('alert')).toHaveTextContent('현재 사용 가능 크레딧')
    expect(admin.adjustAdminWorkspaceCredits).not.toHaveBeenCalled()
    await user.clear(screen.getByLabelText('크레딧 수량'))
    await user.type(screen.getByLabelText('크레딧 수량'), '0.1')
    await user.click(screen.getByRole('button', { name: '변경 내용 확인' }))
    expect(screen.getByLabelText('조정 미리보기')).toHaveTextContent('사용 가능 90 → 89.9')
    expect(admin.adjustAdminWorkspaceCredits).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '확인 후 크레딧 반영' }))
    await waitFor(() =>
      expect(admin.adjustAdminWorkspaceCredits).toHaveBeenCalledWith('w1', {
        credits: -0.1,
        reason: 'manual',
        note: '오지급 회수',
        operation_id: expect.any(String),
        expected_balance: 100,
        expected_reserved: 10,
        expected_revision: 4,
      }),
    )
  })
  it('0.3 보유에서 0.2 확보 중일 때 0.1 회수를 허용한다', async () => {
    vi.mocked(admin.readAdminMonthlySummary).mockResolvedValue({
      ...monthly(),
      current: { ...monthly().current, balance: 0.3, reserved: 0.2, available: 0.1 },
    })
    const user = await open()
    await user.selectOptions(screen.getByLabelText('조정 종류'), 'withdraw')
    await user.type(screen.getByLabelText('크레딧 수량'), '0.1')
    await user.type(screen.getByLabelText('메모 (필수)'), '소수 회수')
    await user.click(screen.getByRole('button', { name: '변경 내용 확인' }))
    expect(screen.getByRole('button', { name: '확인 후 크레딧 반영' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
  it('네트워크 오류 후 같은 요청 식별자로 재시도한다', async () => {
    vi.mocked(admin.adjustAdminWorkspaceCredits).mockRejectedValue(new Error('offline'))
    const user = await open()
    await user.type(screen.getByLabelText('크레딧 수량'), '2')
    await user.type(screen.getByLabelText('메모 (필수)'), '지원 지급')
    await user.click(screen.getByRole('button', { name: '변경 내용 확인' }))
    await user.click(screen.getByRole('button', { name: '확인 후 크레딧 반영' }))
    await screen.findByRole('alert')
    await user.click(screen.getByRole('button', { name: '확인 후 크레딧 반영' }))
    await waitFor(() => expect(admin.adjustAdminWorkspaceCredits).toHaveBeenCalledTimes(2))
    expect(vi.mocked(admin.adjustAdminWorkspaceCredits).mock.calls[0]?.[1]).toEqual(
      vi.mocked(admin.adjustAdminWorkspaceCredits).mock.calls[1]?.[1],
    )
  })
  it('오래된 계정 상태 충돌은 다시 조회하고 확인을 다시 받는다', async () => {
    vi.mocked(admin.adjustAdminWorkspaceCredits).mockRejectedValue(
      new ApiError(409, '계정 상태가 바뀌었습니다'),
    )
    const user = await open()
    await user.type(screen.getByLabelText('크레딧 수량'), '1')
    await user.type(screen.getByLabelText('메모 (필수)'), '지원 지급')
    await user.click(screen.getByRole('button', { name: '변경 내용 확인' }))
    await user.click(screen.getByRole('button', { name: '확인 후 크레딧 반영' }))
    await waitFor(() => expect(admin.readAdminMonthlySummary).toHaveBeenCalledTimes(2))
    expect(screen.queryByLabelText('조정 미리보기')).not.toBeInTheDocument()
  })
  it('미확정 조정은 월 전환 뒤에도 같은 요청으로만 재시도한다', async () => {
    vi.mocked(admin.adjustAdminWorkspaceCredits).mockRejectedValue(new Error('offline'))
    const user = await open()
    await user.type(screen.getByLabelText('크레딧 수량'), '2')
    await user.type(screen.getByLabelText('메모 (필수)'), '지원 지급')
    await user.click(screen.getByRole('button', { name: '변경 내용 확인' }))
    await user.click(screen.getByRole('button', { name: '확인 후 크레딧 반영' }))
    await screen.findByRole('alert')
    const request = vi.mocked(admin.adjustAdminWorkspaceCredits).mock.calls[0]?.[1]
    await user.click(screen.getByRole('button', { name: '이전 달' }))
    await screen.findByRole('button', { name: '확인 후 크레딧 반영' })
    expect(screen.getByLabelText('크레딧 수량')).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '확인 후 크레딧 반영' }))
    await waitFor(() =>
      expect(admin.adjustAdminWorkspaceCredits).toHaveBeenLastCalledWith('w1', request),
    )
    expect(admin.adjustAdminWorkspaceCredits).toHaveBeenCalledTimes(2)
  })
  it('결제 내역은 테스트·실제를 구분하고 진행 중 결제에는 기존 환불 제한을 유지한다', async () => {
    vi.mocked(getSubscription).mockResolvedValue({
      mock_enabled: false,
      pending: true,
      plans: [],
      subscription: null,
      payments: [],
    })
    vi.mocked(admin.listAdminPayments).mockResolvedValue({
      ...page,
      total: 25,
      items: [
        {
          id: 'event-1',
          payment_id: 'payment-1',
          kind: 'payment',
          amount_krw: 2000,
          is_test: true,
          occurred_at: null,
          original_created_at: '2026-08-01T00:00:00Z',
          plan_id: 'start',
          status: 'paid',
          credit_transaction_id: null,
          original_amount_krw: 2000,
          refunded_amount_krw: 0,
          provider: 'toss_test',
        },
      ],
    })
    const user = await open()
    expect(screen.getByText('실제 결제 5,000원 · 환불 1,000원 · 차액 4,000원')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '현재 크레딧 확인', hidden: true })).toBeDisabled()
    await user.click(
      within(screen.getByRole('navigation', { name: '결제 내역 페이지' })).getByRole('button', {
        name: '다음',
      }),
    )
    await waitFor(() =>
      expect(admin.listAdminPayments).toHaveBeenLastCalledWith(
        'w1',
        expect.objectContaining({ page: 2 }),
        expect.anything(),
      ),
    )
  })
  it('부분 환불 후 새 잔여 금액으로 환불 입력을 초기화한다', async () => {
    const payment = {
      id: 'event-1',
      payment_id: 'payment-1',
      kind: 'payment' as const,
      amount_krw: 2000,
      is_test: true,
      occurred_at: null,
      original_created_at: '2026-08-01T00:00:00Z',
      plan_id: 'start',
      status: 'paid',
      credit_transaction_id: null,
      original_amount_krw: 2000,
      refunded_amount_krw: 0,
      provider: 'toss_test',
    }
    let detailReads = 0
    vi.mocked(admin.listAdminPayments).mockImplementation((_id, params) => {
      if (params.size === 100) return Promise.resolve(page)
      detailReads += 1
      return Promise.resolve({
        ...page,
        items: [
          detailReads === 1
            ? payment
            : { ...payment, status: 'partially_refunded', refunded_amount_krw: 500 },
        ],
        total: 1,
      })
    })
    vi.mocked(refundTossPayment).mockResolvedValue({
      operation_id: 'op',
      workspace_id: 'w1',
      payment_id: 'payment-1',
      amount: 500,
      recovery_credits: 0,
      stop_renewal: true,
      reason: '고객 요청',
      status: 'completed',
      created_at: '',
      updated_at: '',
    })
    const user = await open()
    await user.click(screen.getByText('테스트 환불', { selector: 'summary' }))
    await user.click(screen.getByRole('button', { name: '현재 크레딧 확인' }))
    await user.type(await screen.findByLabelText('처리 사유'), '고객 요청')
    await user.click(screen.getByLabelText('환불액, 회수량, 갱신 중단 여부와 사유를 확인했습니다.'))
    const amount = screen.getByLabelText('환불 금액 (원)')
    await user.clear(amount)
    await user.type(amount, '500')
    await user.click(screen.getByRole('button', { name: '테스트 환불 실행' }))
    await waitFor(() => expect(detailReads).toBeGreaterThan(1))
    expect(refundTossPayment).toHaveBeenCalledWith(
      'w1',
      'payment-1',
      expect.objectContaining({
        operation_id: expect.any(String),
        amount: 500,
        expected_revision: 4,
      }),
    )
  })
  it('기본 조회 월과 시간대를 서버 응답으로 정한다', async () => {
    vi.mocked(admin.listAdminWorkspaces).mockResolvedValue({
      ...adminWorkspaceListResponse(),
      timezone: 'UTC',
      current_month: currentMonth('UTC'),
    })
    render(<AdminWorkspacesTab />)
    await screen.findByRole('table')
    expect(vi.mocked(admin.listAdminWorkspaces).mock.calls[0]?.[0]?.month).toBeUndefined()
    expect(screen.getByLabelText('조회 월')).toHaveValue(currentMonth('UTC'))
  })
  it('목록에 실제 결제 금액과 한국 시간 결제일을 표시하고 테스트·미상 날짜는 제외한다', async () => {
    window.history.replaceState({}, '', '/admin?month=2026-09')
    const payment = {
      id: 'paid',
      payment_id: 'p1',
      kind: 'payment' as const,
      amount_krw: 5000,
      is_test: false,
      occurred_at: '2026-08-31T15:30:00Z',
      original_created_at: '2026-08-30T00:00:00Z',
      plan_id: 'start',
      status: 'paid',
      credit_transaction_id: null,
      original_amount_krw: 5000,
      refunded_amount_krw: 0,
      provider: 'toss',
    }
    vi.mocked(admin.listAdminPayments).mockResolvedValue({
      items: [
        payment,
        { ...payment, id: 'test', is_test: true, amount_krw: 9999 },
        { ...payment, id: 'unknown', occurred_at: null, amount_krw: 7777 },
      ],
      page: 1,
      size: 100,
      total: 3,
    })
    render(<AdminWorkspacesTab />)
    expect(await screen.findByText('5,000원 (2026-09-01)')).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '결제액(결제일)/환불액' })).toBeInTheDocument()
    expect(screen.queryByText(/9,999원/)).not.toBeInTheDocument()
    expect(screen.queryByText(/7,777원/)).not.toBeInTheDocument()
  })
  it('운영 시작 월보다 이전인 URL과 직접 입력을 허용하지 않는다', async () => {
    window.history.replaceState({}, '', '/admin?month=2026-08')
    render(<AdminWorkspacesTab />)
    await screen.findByRole('table')
    expect(screen.getByLabelText('조회 월')).toHaveAttribute('min', '2026-09')
    expect(new URLSearchParams(window.location.search).get('month')).not.toBe('2026-08')
    fireEvent.change(screen.getByLabelText('조회 월'), { target: { value: '2026-09' } })
    await waitFor(() => expect(screen.getByLabelText('조회 월')).toHaveValue('2026-09'))
    expect(screen.getByRole('button', { name: '이전 달' })).toBeDisabled()
    fireEvent.change(screen.getByLabelText('조회 월'), { target: { value: '2026-08' } })
    expect(screen.getByLabelText('조회 월')).toHaveValue('2026-09')
    expect(admin.listAdminWorkspaces).not.toHaveBeenCalledWith(
      expect.objectContaining({ month: '2026-08' }),
      expect.anything(),
    )
  })
  it('연간 비교에서도 운영 시작 전 월을 표시하지 않는다', async () => {
    vi.mocked(admin.readAdminMonthlyHistory).mockResolvedValue({
      workspace_id: 'w1',
      year: 2026,
      timezone: 'Asia/Seoul',
      items: [monthly('2026-08'), monthly('2026-09')],
    })
    const user = await open()
    await user.click(screen.getByText('2026년 월별 비교', { selector: 'summary' }))
    expect(await screen.findByRole('button', { name: '2026-09' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '2026-08' })).not.toBeInTheDocument()
  })
  it('미래 월 입력을 무시한다', async () => {
    render(<AdminWorkspacesTab />)
    await screen.findByRole('table')
    fireEvent.change(screen.getByLabelText('조회 월'), {
      target: { value: shiftMonth(currentMonth(), 1) },
    })
    expect(screen.getByLabelText('조회 월')).toHaveValue(currentMonth())
  })
  it('같은 이메일의 작업공간을 묶어 선택한 작업공간에만 상세를 연다', async () => {
    vi.mocked(admin.listAdminWorkspaces).mockResolvedValue(
      adminWorkspaceListResponse({
        items: [
          adminWorkspaceSummary(),
          adminWorkspaceSummary({ workspace_id: 'w2', name: '두 번째' }),
        ],
      }),
    )
    const user = userEvent.setup()
    render(<AdminWorkspacesTab />)
    await user.selectOptions(
      await screen.findByRole('combobox', { name: 'owner@example.test의 작업 공간 선택' }),
      'w2',
    )
    await user.click(screen.getByRole('button', { name: 'owner@example.test의 두 번째 관리' }))
    await waitFor(() =>
      expect(admin.readAdminWorkspace).toHaveBeenCalledWith('w2', expect.anything()),
    )
  })
})
