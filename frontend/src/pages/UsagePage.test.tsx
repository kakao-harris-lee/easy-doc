import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { getSubscription } from '../api/subscriptions'
import { ApiError } from '../api/client'
import { getWorkspaceCredits } from '../api/credits'
import { createInvoiceRequest, listInvoiceRequests } from '../api/invoices'
import { getWorkspaceUsage } from '../api/usage'
import { workspaceContext, workspaceCredits, workspaceUsage } from '../test/factories'
import { WorkspaceContext, type WorkspaceContextValue } from '../workspace/context'
import { UsagePage } from './UsagePage'

vi.mock('../api/subscriptions', () => ({ getSubscription: vi.fn() }))
vi.mock('../api/credits', () => ({ getWorkspaceCredits: vi.fn() }))
vi.mock('../api/usage', () => ({ getWorkspaceUsage: vi.fn() }))
vi.mock('../api/invoices', () => ({ createInvoiceRequest: vi.fn(), listInvoiceRequests: vi.fn() }))

function page(workspace: Partial<WorkspaceContextValue> = {}) {
  return (
    <WorkspaceContext.Provider value={workspaceContext(workspace)}>
      <MemoryRouter>
        <UsagePage />
      </MemoryRouter>
    </WorkspaceContext.Provider>
  )
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getSubscription).mockResolvedValue({
    mock_enabled: false,
    plans: [{ id: 'start', name: 'Start', allowance: 50, monthly_price: 99_000 }],
    subscription: null,
    payments: [],
  })
  vi.mocked(getWorkspaceCredits).mockResolvedValue(
    workspaceCredits({
      enforced: false,
      allowance: 50,
      cycle_started_at: '2026-09-03T06:24:30Z',
      cycle_ends_at: '2099-10-03T06:24:30Z',
    }),
  )
  vi.mocked(getWorkspaceUsage).mockResolvedValue(workspaceUsage())
})

describe('플랜과 사용량', () => {
  it('월 구독 준비 상태와 현재 이용 기간 사용량만 간단히 보여준다', async () => {
    render(page())
    expect(screen.getByRole('heading', { name: '월 구독 플랜' })).toBeInTheDocument()
    expect(await screen.findByText('구독 서비스 준비 중')).toBeInTheDocument()
    expect(screen.queryByText('테스트 구성 · 동작 확인용')).not.toBeInTheDocument()
    expect(screen.queryByText('목표 플랜 구성')).not.toBeInTheDocument()
    const catalog = screen.getByRole('list', { name: '월 플랜 선택' })
    expect(within(catalog).getByText('Start')).toBeInTheDocument()
    expect(within(catalog).getByText('Basic')).toBeInTheDocument()
    expect(within(catalog).getByText('Pro')).toBeInTheDocument()
    expect(within(catalog).getByText(/월 50크레딧/)).toBeInTheDocument()
    expect(within(catalog).getByText(/월 200크레딧/)).toBeInTheDocument()
    expect(await screen.findByText('2크레딧 사용')).toBeInTheDocument()
    expect(screen.getByText('문서 1건 · 1,500자')).toBeInTheDocument()
    expect(screen.getByText(/2026\. 9\. 3\. 이용 시작일부터 오늘까지/)).toBeInTheDocument()
    expect(getWorkspaceUsage).toHaveBeenCalledWith('w1', {}, expect.any(AbortSignal))
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.queryByText(/토큰|예상 비용|LLM 호출/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '세금계산서 요청' })).not.toBeInTheDocument()
    expect(listInvoiceRequests).not.toHaveBeenCalled()
    expect(createInvoiceRequest).not.toHaveBeenCalled()
  })

  it('실제 목표 플랜 가격과 Start 테스트 결제 상태를 보여준다', async () => {
    render(page())
    expect(await screen.findByText('구독 서비스 준비 중')).toBeInTheDocument()
    expect(screen.queryByText('목표 플랜 구성')).not.toBeInTheDocument()
    const targetCatalog = screen.getByRole('list', { name: '월 플랜 선택' })
    expect(within(targetCatalog).getByText('Start')).toBeInTheDocument()
    expect(within(targetCatalog).getByText('99,000원')).toBeInTheDocument()
    expect(within(targetCatalog).getByText('Basic')).toBeInTheDocument()
    expect(within(targetCatalog).getByText('190,000원')).toBeInTheDocument()
    expect(within(targetCatalog).getByText(/200크레딧/)).toBeInTheDocument()
    expect(within(targetCatalog).getByText('Pro')).toBeInTheDocument()
    expect(within(targetCatalog).getByText('599,000원')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Start 테스트 결제' })).toBeDisabled()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.queryByText(/토큰|예상 비용|LLM 호출/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '세금계산서 요청' })).not.toBeInTheDocument()
    expect(listInvoiceRequests).not.toHaveBeenCalled()
    expect(createInvoiceRequest).not.toHaveBeenCalled()
  })

  it('이용량 제한이 꺼지면 음수 잔액을 보여주지 않는다', async () => {
    vi.mocked(getWorkspaceCredits).mockResolvedValue(
      workspaceCredits({ enforced: false, balance: -11, available: -11 }),
    )
    render(page())
    expect(await screen.findByText('이용량 제한 없음')).toBeInTheDocument()
    expect(screen.queryByText('-11')).not.toBeInTheDocument()
  })

  it('제한이 켜지면 남은 수량과 변환 중인 수량, 종료일을 표시한다', async () => {
    vi.mocked(getWorkspaceCredits).mockResolvedValue(
      workspaceCredits({
        enforced: true,
        allowance: 50,
        balance: 40,
        available: 37,
        reserved: 3,
        cycle_started_at: '2026-09-01T00:00:00Z',
        cycle_ends_at: '2026-10-01T00:00:00Z',
      }),
    )
    render(page())
    expect(await screen.findByText('37크레딧')).toBeInTheDocument()
    expect(screen.getByText(/제공량 50크레딧/)).toHaveTextContent('이용 기간 종료')
    expect(screen.getByText('변환 중인 3크레딧을 제외한 수량입니다.')).toBeInTheDocument()
    expect(screen.queryByText(/초기화|다음 결제일/)).not.toBeInTheDocument()
  })

  it('현재 이용 주기의 사용량과 남은 수량을 함께 보여준다', async () => {
    vi.mocked(getWorkspaceCredits).mockResolvedValue(
      workspaceCredits({
        enforced: true,
        allowance: 50,
        balance: 10,
        available: 10,
        cycle_started_at: '2026-09-03T06:24:30Z',
        cycle_ends_at: '2099-10-03T06:24:30Z',
      }),
    )
    vi.mocked(getWorkspaceUsage).mockResolvedValue(workspaceUsage({ credits: 7 }))
    render(page())
    expect(await screen.findByText('7크레딧 사용')).toBeInTheDocument()
    expect(screen.getByText('10크레딧')).toBeInTheDocument()
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
  })

  it('소수 첫째 자리 이용량과 잔액을 표시한다', async () => {
    vi.mocked(getWorkspaceCredits).mockResolvedValue(
      workspaceCredits({
        enforced: true,
        allowance: 1.1,
        balance: 0.4,
        available: 0.3,
        reserved: 0.1,
        cycle_started_at: '2026-09-03T06:24:30Z',
        cycle_ends_at: '2099-10-03T06:24:30Z',
      }),
    )
    vi.mocked(getWorkspaceUsage).mockResolvedValue(workspaceUsage({ credits: 0.8 }))

    render(page())

    expect(await screen.findByText('0.8크레딧 사용')).toBeInTheDocument()
    expect(screen.getByText('0.3크레딧')).toBeInTheDocument()
    expect(screen.getByText(/제공량 1.1크레딧/)).toBeInTheDocument()
    expect(screen.getByText('변환 중인 0.1크레딧을 제외한 수량입니다.')).toBeInTheDocument()
  })

  it('미결제·결제 실패·만료로 진행 중인 주기가 없으면 과거 사용량을 표시하지 않는다', async () => {
    vi.mocked(getWorkspaceCredits).mockResolvedValue(
      workspaceCredits({ enforced: true, allowance: 0, balance: 0, available: 0 }),
    )
    vi.mocked(getWorkspaceUsage).mockResolvedValue(workspaceUsage({ credits: 0, documents: 0 }))

    render(page())

    expect(await screen.findByText('사용 중인 이용 기간이 없습니다.')).toBeInTheDocument()
    expect(screen.getByText(/미결제·결제 실패·만료 상태의 과거 사용량/)).toBeInTheDocument()
    expect(screen.getByText(/플랜 결제가 완료되면 이용량이 제공됩니다/)).toBeInTheDocument()
    expect(screen.queryByText(/크레딧 사용/)).not.toBeInTheDocument()
  })

  it('제공량을 모두 소진해도 시작일부터 누적 사용량과 소진 상태를 표시한다', async () => {
    vi.mocked(getWorkspaceCredits).mockResolvedValue(
      workspaceCredits({
        enforced: true,
        allowance: 50,
        balance: 0,
        available: 0,
        reserved: 0,
        cycle_started_at: '2026-09-03T06:24:30Z',
        cycle_ends_at: '2099-10-03T06:24:30Z',
      }),
    )
    vi.mocked(getWorkspaceUsage).mockResolvedValue(workspaceUsage({ credits: 50 }))

    render(page())

    expect(await screen.findByText('50크레딧 사용')).toBeInTheDocument()
    expect(screen.getByText('0크레딧')).toBeInTheDocument()
    expect(screen.getByText('이번 이용 기간의 제공량을 모두 사용했습니다.')).toBeInTheDocument()
  })

  it('무료 혜택 중복 수령 안내를 유지한다', async () => {
    vi.mocked(getWorkspaceCredits).mockResolvedValue(
      workspaceCredits({ signup_grant_skipped: true }),
    )
    render(page())
    expect(await screen.findByText(/이 이메일은 이전에 가입 크레딧을 받은 적/)).toBeInTheDocument()
  })

  it('작업 공간이 없으면 조회하지 않고 선택을 안내한다', () => {
    render(page({ currentId: null, workspaces: [] }))
    expect(screen.getByText('작업 공간을 선택하면 사용량을 볼 수 있습니다.')).toBeInTheDocument()
    expect(getWorkspaceUsage).not.toHaveBeenCalled()
    expect(getWorkspaceCredits).not.toHaveBeenCalled()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('사용량 실패가 남은 이용량을 감추지 않으며 새로고침으로 복구한다', async () => {
    const user = userEvent.setup()
    vi.mocked(getWorkspaceUsage).mockRejectedValueOnce(new ApiError(500, '사용량 조회 실패'))
    render(page())
    expect(await screen.findByRole('alert')).toHaveTextContent('사용량 조회 실패')
    expect(screen.getByText('이용량 제한 없음')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '새로고침' }))
    expect(await screen.findByText('2크레딧 사용')).toBeInTheDocument()
    expect(getSubscription).toHaveBeenCalledTimes(2)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('남은 이용량 조회 실패가 현재 이용 기간 사용량을 감추지 않는다', async () => {
    vi.mocked(getWorkspaceCredits).mockRejectedValue(new Error('network'))
    render(page())
    expect(await screen.findByRole('alert')).toHaveTextContent('남은 이용량을 불러오지 못했습니다.')
    expect(screen.getByText('2크레딧 사용')).toBeInTheDocument()
  })

  it('작업 공간 전환 후 이전 응답이 늦게 와도 새 공간 수치를 유지한다', async () => {
    let resolveOldUsage!: (value: ReturnType<typeof workspaceUsage>) => void
    let resolveOldCredits!: (value: ReturnType<typeof workspaceCredits>) => void
    vi.mocked(getWorkspaceUsage).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOldUsage = resolve
        }),
    )
    vi.mocked(getWorkspaceCredits).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOldCredits = resolve
        }),
    )
    const { rerender } = render(page())
    const oldSignal = vi.mocked(getWorkspaceUsage).mock.calls[0]?.[2]
    rerender(page({ currentId: 'w2' }))
    expect(await screen.findByText('2크레딧 사용')).toBeInTheDocument()
    await act(async () => {
      resolveOldUsage(workspaceUsage({ credits: 999 }))
      resolveOldCredits(workspaceCredits({ enforced: true, available: 999 }))
    })
    expect(oldSignal?.aborted).toBe(true)
    expect(screen.queryByText('999크레딧 사용')).not.toBeInTheDocument()
    const section = screen.getByRole('region', { name: '현재 이용 기간 사용량' })
    expect(within(section).getByText('이용량 제한 없음')).toBeInTheDocument()
  })
})
