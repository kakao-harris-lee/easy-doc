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
    plans: [
      { id: 'starter', name: 'Starter', allowance: 50, monthly_price: 1000 },
      { id: 'pro', name: 'Pro', allowance: 200, monthly_price: 3000 },
    ],
    subscription: null,
    payments: [],
  })
  vi.mocked(getWorkspaceCredits).mockResolvedValue(workspaceCredits({ enforced: false }))
  vi.mocked(getWorkspaceUsage).mockResolvedValue(workspaceUsage())
})

describe('플랜과 사용량', () => {
  it('월 구독 준비 상태와 이번 달 사용량만 간단히 보여준다', async () => {
    render(page())
    expect(screen.getByRole('heading', { name: '월 구독 플랜' })).toBeInTheDocument()
    expect(await screen.findByText('구독 서비스 준비 중')).toBeInTheDocument()
    const catalog = screen.getByRole('list', { name: '테스트 플랜 구성' })
    expect(within(catalog).getByText('Starter')).toBeInTheDocument()
    expect(within(catalog).getByText('Pro')).toBeInTheDocument()
    expect(within(catalog).getByText(/월 50크레딧/)).toBeInTheDocument()
    expect(within(catalog).getByText(/월 200크레딧/)).toBeInTheDocument()
    expect(await screen.findByText('2크레딧 사용')).toBeInTheDocument()
    expect(screen.getByText('문서 1건 · 1,500자')).toBeInTheDocument()
    expect(getWorkspaceUsage).toHaveBeenCalledWith('w1', {}, expect.any(AbortSignal))
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.queryByText(/토큰|예상 비용|LLM 호출/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '세금계산서 요청' })).not.toBeInTheDocument()
    expect(listInvoiceRequests).not.toHaveBeenCalled()
    expect(createInvoiceRequest).not.toHaveBeenCalled()
  })

  it('테스트 플랜 구성과 목표 플랜 구성(연구안)을 함께 보여준다', async () => {
    render(page())
    expect(await screen.findByText('구독 서비스 준비 중')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '목표 플랜 구성' })).toBeInTheDocument()
    expect(screen.getByText('연구안 · 판매 가격이 아닙니다.')).toBeInTheDocument()
    const targetCatalog = screen.getByRole('list', { name: '목표 플랜 구성' })
    expect(within(targetCatalog).getByText('Basic')).toBeInTheDocument()
    expect(within(targetCatalog).getByText('99,000원')).toBeInTheDocument()
    expect(within(targetCatalog).getByText(/200크레딧/)).toBeInTheDocument()
    expect(within(targetCatalog).getByText('Pro')).toBeInTheDocument()
    expect(within(targetCatalog).getByText('290,000원')).toBeInTheDocument()
    expect(within(targetCatalog).getByText('Enterprise')).toBeInTheDocument()
    expect(within(targetCatalog).getByText(/1,000,000원/)).toBeInTheDocument()
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
        cycle_ends_at: '2026-10-01T00:00:00Z',
      }),
    )
    render(page())
    expect(await screen.findByText('37크레딧')).toBeInTheDocument()
    expect(screen.getByText(/제공량 50크레딧/)).toHaveTextContent('이용 기간 종료')
    expect(screen.getByText('변환 중인 3크레딧을 제외한 수량입니다.')).toBeInTheDocument()
    expect(screen.queryByText(/초기화|다음 결제일/)).not.toBeInTheDocument()
  })

  it('월 사용량을 결제주기 사용량으로 계산하지 않는다', async () => {
    vi.mocked(getWorkspaceCredits).mockResolvedValue(
      workspaceCredits({ enforced: true, allowance: 50, balance: 10, available: 10 }),
    )
    vi.mocked(getWorkspaceUsage).mockResolvedValue(workspaceUsage({ credits: 7 }))
    render(page())
    expect(await screen.findByText('7크레딧 사용')).toBeInTheDocument()
    expect(screen.getByText('10크레딧')).toBeInTheDocument()
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
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
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('남은 이용량 조회 실패가 이번 달 사용량을 감추지 않는다', async () => {
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
    const section = screen.getByRole('region', { name: '이번 달 사용량' })
    expect(within(section).getByText('이용량 제한 없음')).toBeInTheDocument()
  })
})
