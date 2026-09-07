import { render, screen, waitFor } from '@testing-library/react'
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

beforeEach(() => {
  vi.mocked(listAdminWorkspaces).mockReset()
  vi.mocked(readAdminWorkspace).mockReset()
  vi.mocked(adjustAdminWorkspaceCredits).mockReset()
})

afterEach(() => {
  vi.restoreAllMocks()
})

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

  it('행을 누르면 상세와 크레딧 조정 폼을 연다', async () => {
    const user = userEvent.setup()
    vi.mocked(listAdminWorkspaces).mockResolvedValue(
      adminWorkspaceListResponse({
        items: [adminWorkspaceSummary({ workspace_id: 'w1', name: '복지정책팀' })],
      }),
    )
    vi.mocked(readAdminWorkspace).mockResolvedValue(adminWorkspaceDetail())

    render(<AdminWorkspacesTab />)
    await user.click(await screen.findByRole('button', { name: '복지정책팀' }))

    expect(await screen.findByRole('form', { name: '크레딧 조정' })).toBeInTheDocument()
    expect(vi.mocked(readAdminWorkspace)).toHaveBeenCalledWith('w1', expect.anything())
  })

  it('크레딧을 조정하면 성공 문구를 보여주고 상세를 다시 읽는다', async () => {
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
    })

    render(<AdminWorkspacesTab />)
    await user.click(await screen.findByRole('button', { name: '복지정책팀' }))
    await screen.findByRole('form', { name: '크레딧 조정' })

    await user.type(screen.getByLabelText('크레딧 (0이 될 수 없음)'), '50')
    await user.selectOptions(screen.getByLabelText('사유'), 'plan_monthly')
    await user.click(screen.getByRole('button', { name: '조정하기' }))

    expect(await screen.findByText('크레딧을 조정했습니다.')).toBeInTheDocument()
    expect(vi.mocked(adjustAdminWorkspaceCredits)).toHaveBeenCalledWith('w1', {
      credits: 50,
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
    })

    render(<AdminWorkspacesTab />)
    await user.click(await screen.findByRole('button', { name: '복지정책팀' }))
    await screen.findByRole('form', { name: '크레딧 조정' })

    await user.type(screen.getByLabelText('크레딧 (0이 될 수 없음)'), '-3')
    await user.selectOptions(screen.getByLabelText('사유'), 'refund')
    await user.type(screen.getByLabelText('메모 (선택)'), '결제 취소 환급')
    await user.click(screen.getByRole('button', { name: '조정하기' }))

    expect(await screen.findByText('크레딧을 조정했습니다.')).toBeInTheDocument()
    expect(vi.mocked(adjustAdminWorkspaceCredits)).toHaveBeenCalledWith('w1', {
      credits: -3,
      reason: 'refund',
      note: '결제 취소 환급',
    })
  })

  it('크레딧이 0이면 서버를 부르지 않고 화면에서 막는다', async () => {
    const user = userEvent.setup()
    vi.mocked(listAdminWorkspaces).mockResolvedValue(
      adminWorkspaceListResponse({
        items: [adminWorkspaceSummary({ workspace_id: 'w1', name: '복지정책팀' })],
      }),
    )
    vi.mocked(readAdminWorkspace).mockResolvedValue(adminWorkspaceDetail())

    render(<AdminWorkspacesTab />)
    await user.click(await screen.findByRole('button', { name: '복지정책팀' }))
    await screen.findByRole('form', { name: '크레딧 조정' })

    await user.type(screen.getByLabelText('크레딧 (0이 될 수 없음)'), '0')
    await user.click(screen.getByRole('button', { name: '조정하기' }))

    expect(await screen.findByText('크레딧은 0이 아닌 정수여야 합니다.')).toBeInTheDocument()
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
    await user.click(await screen.findByRole('button', { name: '복지정책팀' }))
    await screen.findByRole('form', { name: '크레딧 조정' })

    await user.type(screen.getByLabelText('크레딧 (0이 될 수 없음)'), '10')
    await user.click(screen.getByRole('button', { name: '조정하기' }))

    expect(await screen.findByText('credits 는 0이 될 수 없습니다')).toBeInTheDocument()
  })

  it('목록 조회가 실패하면 서버 문구를 보여준다', async () => {
    vi.mocked(listAdminWorkspaces).mockRejectedValue(new ApiError(403, '관리자 권한이 필요합니다'))

    render(<AdminWorkspacesTab />)

    expect(await screen.findByText('관리자 권한이 필요합니다')).toBeInTheDocument()
  })
})
