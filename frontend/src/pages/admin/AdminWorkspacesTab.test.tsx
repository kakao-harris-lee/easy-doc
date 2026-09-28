import { getSubscription } from '../../api/subscriptions'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  adjustAdminWorkspaceCredits,
  listAdminWorkspaces,
  readAdminWorkspace,
} from '../../api/admin'
import { ApiError } from '../../api/client'
import {
  adminWorkspaceDetail,
  adminWorkspaceListResponse,
  adminWorkspaceSummary,
} from '../../test/factories'
import { AdminWorkspacesTab } from './AdminWorkspacesTab'

vi.mock('../../api/admin', () => ({
  listAdminWorkspaces: vi.fn(),
  readAdminWorkspace: vi.fn(),
  adjustAdminWorkspaceCredits: vi.fn(),
}))

vi.mock('../../api/subscriptions', () => ({ getSubscription: vi.fn() }))

beforeEach(() => {
  vi.mocked(getSubscription).mockResolvedValue({
    mock_enabled: false,
    plans: [],
    subscription: null,
    payments: [],
  })
  vi.mocked(listAdminWorkspaces).mockReset()
  vi.mocked(readAdminWorkspace).mockReset()
  vi.mocked(adjustAdminWorkspaceCredits).mockReset()
})

afterEach(() => {
  vi.restoreAllMocks()
})

/**
 * 같은 계정(`owner@example.test`)이 가진 두 번째 작업 공간.
 *
 * 값이 기본 factory(w1)와 하나도 겹치지 않아야 「드롭다운을 바꾸면 그 줄의 숫자도 같이
 * 바뀐다」를 실제로 잴 수 있다.
 */
const SECOND_WORKSPACE = adminWorkspaceSummary({
  workspace_id: 'w2',
  name: '민원안내팀',
  created_at: '2026-09-15T00:00:00Z',
  credit_balance: 88,
  credit_reserved: 8,
  credit_available: 80,
  month_documents: 11,
  month_credits: 5,
  month_cost_usd: '0.002000',
})

/** 그 계정의 두 작업 공간을 id에 맞는 상세로 돌려주는 mock. */
function mockDetailByWorkspaceId(): void {
  vi.mocked(readAdminWorkspace).mockImplementation((workspaceId: string) =>
    Promise.resolve(
      adminWorkspaceDetail({
        summary:
          workspaceId === 'w2'
            ? SECOND_WORKSPACE
            : adminWorkspaceSummary({ workspace_id: 'w1', name: '복지정책팀' }),
      }),
    ),
  )
}

describe('AdminWorkspacesTab — 워크스페이스 (어드민 최소, 계약 2.25.0)', () => {
  it('목록을 표로 그린다', async () => {
    vi.mocked(listAdminWorkspaces).mockResolvedValue(
      adminWorkspaceListResponse({
        items: [adminWorkspaceSummary({ workspace_id: 'w1', name: '복지정책팀' })],
      }),
    )

    render(<AdminWorkspacesTab />)

    expect(await screen.findByText('복지정책팀')).toBeInTheDocument()
    expect(vi.mocked(listAdminWorkspaces)).toHaveBeenCalledWith(
      { q: undefined, page: 1, size: 20 },
      expect.anything(),
    )
  })

  it('검색어를 넣고 검색을 누르면 q·page=1로 다시 부른다', async () => {
    const user = userEvent.setup()
    vi.mocked(listAdminWorkspaces).mockResolvedValue(adminWorkspaceListResponse())

    render(<AdminWorkspacesTab />)
    await screen.findByRole('table')

    await user.type(screen.getByLabelText('이름·소유자 이메일 검색'), '복지')
    await user.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() =>
      expect(vi.mocked(listAdminWorkspaces)).toHaveBeenLastCalledWith(
        { q: '복지', page: 1, size: 20 },
        expect.anything(),
      ),
    )
  })

  it('다음을 누르면 page=2로 다시 부른다', async () => {
    const user = userEvent.setup()
    vi.mocked(listAdminWorkspaces).mockResolvedValue(
      adminWorkspaceListResponse({ items: [adminWorkspaceSummary()], total: 25 }),
    )

    render(<AdminWorkspacesTab />)
    await screen.findByRole('table')

    await user.click(screen.getByRole('button', { name: '다음' }))

    await waitFor(() =>
      expect(vi.mocked(listAdminWorkspaces)).toHaveBeenLastCalledWith(
        { q: undefined, page: 2, size: 20 },
        expect.anything(),
      ),
    )
  })

  it('관리를 누르면 상세와 크레딧 부여 폼을 연다', async () => {
    const user = userEvent.setup()
    vi.mocked(listAdminWorkspaces).mockResolvedValue(
      adminWorkspaceListResponse({
        items: [adminWorkspaceSummary({ workspace_id: 'w1', name: '복지정책팀' })],
      }),
    )
    vi.mocked(readAdminWorkspace).mockResolvedValue(adminWorkspaceDetail())

    render(<AdminWorkspacesTab />)
    await user.click(await screen.findByRole('button', { name: /관리/ }))

    expect(await screen.findByRole('form', { name: '크레딧 부여 및 조정' })).toBeInTheDocument()
    expect(vi.mocked(readAdminWorkspace)).toHaveBeenCalledWith('w1', expect.anything())
  })

  it('한 계정의 작업 공간이 여럿이면 한 행에 묶고 드롭다운으로 고른다', async () => {
    const user = userEvent.setup()
    vi.mocked(listAdminWorkspaces).mockResolvedValue(
      adminWorkspaceListResponse({
        items: [
          adminWorkspaceSummary({ workspace_id: 'w1', name: '복지정책팀' }),
          SECOND_WORKSPACE,
        ],
      }),
    )
    mockDetailByWorkspaceId()

    render(<AdminWorkspacesTab />)

    const select = await screen.findByRole('combobox', {
      name: 'owner@example.test의 작업 공간 선택',
    })
    // 계정이 하나뿐이므로 본문 행도 하나다 — 머리글 행까지 합쳐 두 줄.
    expect(screen.getAllByRole('row')).toHaveLength(2)
    expect(within(select).getByRole('option', { name: '복지정책팀' })).toBeInTheDocument()
    expect(within(select).getByRole('option', { name: '민원안내팀' })).toBeInTheDocument()

    // 처음에는 그 계정의 첫 작업 공간(w1) 값을 그린다.
    expect(screen.getByText('7/10/3')).toBeInTheDocument()
    expect(screen.getByText('2/4/$0.001000')).toBeInTheDocument()

    await user.selectOptions(select, 'w2')

    // 줄의 생성일·크레딧·이번 달 숫자가 고른 작업 공간(w2)을 따라가야 한다.
    expect(
      screen.getByText(new Date('2026-09-15T00:00:00Z').toLocaleDateString('ko-KR')),
    ).toBeInTheDocument()
    expect(screen.getByText('80/88/8')).toBeInTheDocument()
    expect(screen.getByText('11/5/$0.002000')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'owner@example.test의 민원안내팀 관리' }))

    await waitFor(() =>
      expect(vi.mocked(readAdminWorkspace)).toHaveBeenCalledWith('w2', expect.anything()),
    )
  })

  it('상세가 열린 줄에서 드롭다운을 바꾸면 상세도 그 작업 공간으로 바뀐다', async () => {
    const user = userEvent.setup()
    vi.mocked(listAdminWorkspaces).mockResolvedValue(
      adminWorkspaceListResponse({
        items: [
          adminWorkspaceSummary({ workspace_id: 'w1', name: '복지정책팀' }),
          SECOND_WORKSPACE,
        ],
      }),
    )
    mockDetailByWorkspaceId()

    render(<AdminWorkspacesTab />)

    const select = await screen.findByRole('combobox', {
      name: 'owner@example.test의 작업 공간 선택',
    })
    await user.click(screen.getByRole('button', { name: 'owner@example.test의 복지정책팀 관리' }))
    expect(
      await screen.findByRole('heading', { name: 'owner@example.test · 복지정책팀 상세' }),
    ).toBeInTheDocument()

    await user.selectOptions(select, 'w2')

    expect(
      await screen.findByRole('heading', { name: 'owner@example.test · 민원안내팀 상세' }),
    ).toBeInTheDocument()
    await waitFor(() =>
      expect(vi.mocked(readAdminWorkspace)).toHaveBeenCalledWith('w2', expect.anything()),
    )
  })

  it('상세를 열지 않은 줄에서 드롭다운만 바꾸면 상세를 열지 않는다', async () => {
    const user = userEvent.setup()
    vi.mocked(listAdminWorkspaces).mockResolvedValue(
      adminWorkspaceListResponse({
        items: [
          adminWorkspaceSummary({ workspace_id: 'w1', name: '복지정책팀' }),
          SECOND_WORKSPACE,
        ],
      }),
    )
    mockDetailByWorkspaceId()

    render(<AdminWorkspacesTab />)

    await user.selectOptions(
      await screen.findByRole('combobox', { name: 'owner@example.test의 작업 공간 선택' }),
      'w2',
    )

    expect(vi.mocked(readAdminWorkspace)).not.toHaveBeenCalled()
  })

  it('소유자 이메일이 다르면 계정마다 줄을 따로 그린다', async () => {
    vi.mocked(listAdminWorkspaces).mockResolvedValue(
      adminWorkspaceListResponse({
        items: [
          adminWorkspaceSummary({ workspace_id: 'w1', name: '복지정책팀' }),
          adminWorkspaceSummary({
            workspace_id: 'w2',
            name: '민원안내팀',
            owner_email: 'other@example.test',
          }),
        ],
      }),
    )

    render(<AdminWorkspacesTab />)

    await screen.findByRole('table')
    // 머리글 행 + 계정 두 줄.
    expect(screen.getAllByRole('row')).toHaveLength(3)
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })

  it('작업 공간 드롭다운과 관리 버튼은 44px 터치 대상을 지킨다', async () => {
    vi.mocked(listAdminWorkspaces).mockResolvedValue(
      adminWorkspaceListResponse({
        items: [
          adminWorkspaceSummary({ workspace_id: 'w1', name: '복지정책팀' }),
          SECOND_WORKSPACE,
        ],
      }),
    )

    render(<AdminWorkspacesTab />)

    // jsdom은 레이아웃을 계산하지 않으므로 실제 픽셀 높이 대신, 저장소가 44px 터치
    // 대상에 쓰는 고정 클래스(`min-h-11`)를 갖는지로 잰다(DESIGN.md §11).
    const select = await screen.findByRole('combobox', {
      name: 'owner@example.test의 작업 공간 선택',
    })
    expect(select.className).toContain('min-h-11')
    expect(
      screen.getByRole('button', { name: 'owner@example.test의 복지정책팀 관리' }).className,
    ).toContain('min-h-11')
  })

  it('작업 공간이 하나뿐인 계정은 드롭다운 없이 이름만 보여준다', async () => {
    vi.mocked(listAdminWorkspaces).mockResolvedValue(
      adminWorkspaceListResponse({
        items: [adminWorkspaceSummary({ workspace_id: 'w1', name: '복지정책팀' })],
      }),
    )

    render(<AdminWorkspacesTab />)

    expect(await screen.findByText('복지정책팀')).toBeInTheDocument()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })

  it('크레딧을 부여하면 성공 문구를 보여주고 상세를 다시 읽는다', async () => {
    const user = userEvent.setup()
    vi.mocked(listAdminWorkspaces).mockResolvedValue(
      adminWorkspaceListResponse({
        items: [adminWorkspaceSummary({ workspace_id: 'w1', name: '복지정책팀' })],
      }),
    )
    vi.mocked(readAdminWorkspace).mockResolvedValue(adminWorkspaceDetail())
    vi.mocked(adjustAdminWorkspaceCredits).mockResolvedValue({
      workspace_id: 'w1',
      balance: 60,
      reserved: 3,
      available: 57,
      enforced: true,
      transactions: [],
      signup_grant_skipped: false,
      allowance: 0,
      cycle_started_at: null,
      cycle_ends_at: null,
    })

    render(<AdminWorkspacesTab />)
    await user.click(await screen.findByRole('button', { name: /관리/ }))
    await screen.findByRole('form', { name: '크레딧 부여 및 조정' })

    await user.type(screen.getByLabelText('부여할 크레딧 (회수는 음수)'), '0.1')
    await user.selectOptions(screen.getByLabelText('사유'), 'plan_monthly')
    await user.click(screen.getByRole('button', { name: '크레딧 반영하기' }))

    expect(await screen.findByText('크레딧을 반영했습니다.')).toBeInTheDocument()
    expect(vi.mocked(adjustAdminWorkspaceCredits)).toHaveBeenCalledWith('w1', {
      credits: 0.1,
      reason: 'plan_monthly',
      note: null,
    })
    // 상세를 다시 읽어야 한다 — 조정 전 1회 + 조정 후 1회.
    await waitFor(() => expect(vi.mocked(readAdminWorkspace)).toHaveBeenCalledTimes(2))
    // 목록 요약도 낡으므로 다시 읽어야 한다 — 최초 1회 + 조정 후 1회.
    await waitFor(() => expect(vi.mocked(listAdminWorkspaces)).toHaveBeenCalledTimes(2))
  })

  it('메모를 채워 조정하면 그 메모를 그대로 보낸다', async () => {
    const user = userEvent.setup()
    vi.mocked(listAdminWorkspaces).mockResolvedValue(
      adminWorkspaceListResponse({
        items: [adminWorkspaceSummary({ workspace_id: 'w1', name: '복지정책팀' })],
      }),
    )
    vi.mocked(readAdminWorkspace).mockResolvedValue(adminWorkspaceDetail())
    vi.mocked(adjustAdminWorkspaceCredits).mockResolvedValue({
      workspace_id: 'w1',
      balance: 7,
      reserved: 3,
      available: 4,
      enforced: true,
      transactions: [],
      signup_grant_skipped: false,
      allowance: 0,
      cycle_started_at: null,
      cycle_ends_at: null,
    })

    render(<AdminWorkspacesTab />)
    await user.click(await screen.findByRole('button', { name: /관리/ }))
    await screen.findByRole('form', { name: '크레딧 부여 및 조정' })

    await user.type(screen.getByLabelText('부여할 크레딧 (회수는 음수)'), '-3')
    await user.selectOptions(screen.getByLabelText('사유'), 'refund')
    await user.type(screen.getByLabelText('메모 (선택)'), '결제 취소 환급')
    await user.click(screen.getByRole('button', { name: '크레딧 반영하기' }))

    expect(await screen.findByText('크레딧을 반영했습니다.')).toBeInTheDocument()
    expect(vi.mocked(adjustAdminWorkspaceCredits)).toHaveBeenCalledWith('w1', {
      credits: -3,
      reason: 'refund',
      note: '결제 취소 환급',
    })
  })

  it('크레딧이 0 또는 0.1 단위가 아니면 서버를 부르지 않고 화면에서 막는다', async () => {
    const user = userEvent.setup()
    vi.mocked(listAdminWorkspaces).mockResolvedValue(
      adminWorkspaceListResponse({
        items: [adminWorkspaceSummary({ workspace_id: 'w1', name: '복지정책팀' })],
      }),
    )
    vi.mocked(readAdminWorkspace).mockResolvedValue(adminWorkspaceDetail())

    render(<AdminWorkspacesTab />)
    await user.click(await screen.findByRole('button', { name: /관리/ }))
    await screen.findByRole('form', { name: '크레딧 부여 및 조정' })

    await user.type(screen.getByLabelText('부여할 크레딧 (회수는 음수)'), '0.01')
    await user.click(screen.getByRole('button', { name: '크레딧 반영하기' }))

    expect(
      await screen.findByText('크레딧은 0이 아닌 0.1 단위 숫자여야 합니다.'),
    ).toBeInTheDocument()
    expect(vi.mocked(adjustAdminWorkspaceCredits)).not.toHaveBeenCalled()
  })

  it('조정이 서버 오류로 실패하면 서버 문구를 보여준다', async () => {
    const user = userEvent.setup()
    vi.mocked(listAdminWorkspaces).mockResolvedValue(
      adminWorkspaceListResponse({
        items: [adminWorkspaceSummary({ workspace_id: 'w1', name: '복지정책팀' })],
      }),
    )
    vi.mocked(readAdminWorkspace).mockResolvedValue(adminWorkspaceDetail())
    vi.mocked(adjustAdminWorkspaceCredits).mockRejectedValue(
      new ApiError(422, 'credits 는 0이 될 수 없습니다'),
    )

    render(<AdminWorkspacesTab />)
    await user.click(await screen.findByRole('button', { name: /관리/ }))
    await screen.findByRole('form', { name: '크레딧 부여 및 조정' })

    await user.type(screen.getByLabelText('부여할 크레딧 (회수는 음수)'), '10')
    await user.click(screen.getByRole('button', { name: '크레딧 반영하기' }))

    expect(await screen.findByText('credits 는 0이 될 수 없습니다')).toBeInTheDocument()
  })

  it('목록 조회가 실패하면 서버 문구를 보여준다', async () => {
    vi.mocked(listAdminWorkspaces).mockRejectedValue(new ApiError(403, '관리자 권한이 필요합니다'))

    render(<AdminWorkspacesTab />)

    expect(await screen.findByText('관리자 권한이 필요합니다')).toBeInTheDocument()
  })
})
