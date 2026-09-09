import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { deleteAccount } from '../api/auth'
import { ApiError } from '../api/client'
import { getWorkspaceCredits } from '../api/credits'
import { listInvoiceRequests } from '../api/invoices'
import { AuthContext } from '../auth/context'
import type { AuthContextValue } from '../auth/context'
import {
  authContextValue,
  userResponse,
  workspaceContext,
  workspaceCredits,
} from '../test/factories'
import { WorkspaceContext } from '../workspace/context'
import type { WorkspaceContextValue } from '../workspace/context'
import { AccountSettingsPage } from './AccountSettingsPage'

vi.mock('../api/auth', () => ({
  deleteAccount: vi.fn(),
}))

vi.mock('../api/credits', () => ({
  getWorkspaceCredits: vi.fn(),
}))

vi.mock('../api/invoices', () => ({
  listInvoiceRequests: vi.fn(),
}))

function page(
  auth: Partial<AuthContextValue> = {},
  workspace: Partial<WorkspaceContextValue> = {},
) {
  return (
    <AuthContext.Provider value={authContextValue(auth)}>
      <WorkspaceContext.Provider value={workspaceContext(workspace)}>
        <MemoryRouter>
          <AccountSettingsPage />
        </MemoryRouter>
      </WorkspaceContext.Provider>
    </AuthContext.Provider>
  )
}

function renderPage(
  auth: Partial<AuthContextValue> = {},
  workspace: Partial<WorkspaceContextValue> = {},
) {
  return render(page(auth, workspace))
}

beforeEach(() => {
  vi.mocked(deleteAccount).mockReset()
  vi.mocked(getWorkspaceCredits)
    .mockReset()
    .mockResolvedValue(workspaceCredits({ available: 12 }))
  vi.mocked(listInvoiceRequests).mockReset().mockResolvedValue({ items: [] })
})

describe('계정 설정 화면', () => {
  it('처음에는 확인 폼을 보여주지 않는다 — 「회원 탈퇴」 버튼만 있다', () => {
    renderPage()

    expect(screen.getByRole('button', { name: '회원 탈퇴' })).toBeInTheDocument()
    expect(screen.queryByLabelText('확인 문구')).not.toBeInTheDocument()
  })

  it('「회원 탈퇴」를 누르면 남은 이용량과 처리 중인 요청 수를 보여준다', async () => {
    vi.mocked(getWorkspaceCredits).mockResolvedValue(workspaceCredits({ available: 42 }))
    vi.mocked(listInvoiceRequests).mockResolvedValue({
      items: [{ id: 'r1', status: 'requested' } as never, { id: 'r2', status: 'issued' } as never],
    })
    const user = userEvent.setup()

    renderPage()
    await user.click(screen.getByRole('button', { name: '회원 탈퇴' }))

    expect(await screen.findByText(/42 크레딧/)).toBeInTheDocument()
    expect(screen.getByText(/1건/)).toBeInTheDocument()
  })

  it('비밀번호 계정은 비밀번호 입력란을 보여준다', async () => {
    const user = userEvent.setup()
    renderPage({ user: userResponse({ has_password: true }) })

    await user.click(screen.getByRole('button', { name: '회원 탈퇴' }))

    expect(screen.getByLabelText('비밀번호')).toBeInTheDocument()
  })

  it('소셜 전용 계정은 비밀번호 입력란이 없다', async () => {
    const user = userEvent.setup()
    renderPage({ user: userResponse({ has_password: false }) })

    await user.click(screen.getByRole('button', { name: '회원 탈퇴' }))

    expect(screen.queryByLabelText('비밀번호')).not.toBeInTheDocument()
  })

  it('제출 성공은 signOut을 부른다', async () => {
    vi.mocked(deleteAccount).mockResolvedValue(undefined)
    const signOut = vi.fn()
    const user = userEvent.setup()

    renderPage({ user: userResponse({ has_password: true }), signOut })
    await user.click(screen.getByRole('button', { name: '회원 탈퇴' }))
    await user.type(screen.getByLabelText('비밀번호'), 'current-password')
    await user.type(screen.getByLabelText('확인 문구'), '탈퇴합니다')
    await user.click(screen.getByRole('button', { name: '계정을 영구히 삭제합니다' }))

    await waitFor(() => expect(signOut).toHaveBeenCalledTimes(1))
    expect(vi.mocked(deleteAccount)).toHaveBeenCalledWith({
      password: 'current-password',
      confirmation: '탈퇴합니다',
    })
  })

  it('소셜 전용 계정의 제출은 password를 보내지 않는다', async () => {
    vi.mocked(deleteAccount).mockResolvedValue(undefined)
    const user = userEvent.setup()

    renderPage({ user: userResponse({ has_password: false }) })
    await user.click(screen.getByRole('button', { name: '회원 탈퇴' }))
    await user.type(screen.getByLabelText('확인 문구'), '탈퇴합니다')
    await user.click(screen.getByRole('button', { name: '계정을 영구히 삭제합니다' }))

    await waitFor(() =>
      expect(vi.mocked(deleteAccount)).toHaveBeenCalledWith({
        password: undefined,
        confirmation: '탈퇴합니다',
      }),
    )
  })

  it('서버 오류(422)는 문구를 그대로 보여주고 signOut을 부르지 않는다', async () => {
    vi.mocked(deleteAccount).mockRejectedValue(new ApiError(422, '확인 문구가 올바르지 않습니다.'))
    const signOut = vi.fn()
    const user = userEvent.setup()

    renderPage({ user: userResponse({ has_password: false }), signOut })
    await user.click(screen.getByRole('button', { name: '회원 탈퇴' }))
    await user.type(screen.getByLabelText('확인 문구'), '틀림')
    await user.click(screen.getByRole('button', { name: '계정을 영구히 삭제합니다' }))

    expect(await screen.findByText('확인 문구가 올바르지 않습니다.')).toBeInTheDocument()
    expect(signOut).not.toHaveBeenCalled()
  })

  it('취소를 누르면 입력값을 비우고 확인 폼을 닫는다', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: '회원 탈퇴' }))
    await user.type(screen.getByLabelText('확인 문구'), '탈퇴합니다')
    await user.click(screen.getByRole('button', { name: '취소' }))

    expect(screen.queryByLabelText('확인 문구')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '회원 탈퇴' })).toBeInTheDocument()
  })
})
