import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  confirmPhoneVerification,
  deleteAccount,
  oauthUnlink,
  requestPhoneVerification,
} from '../api/auth'
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
  oauthUnlink: vi.fn(),
  setPassword: vi.fn(),
  requestPhoneVerification: vi.fn(),
  confirmPhoneVerification: vi.fn(),
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
  vi.mocked(oauthUnlink).mockReset()
  vi.mocked(requestPhoneVerification).mockReset()
  vi.mocked(confirmPhoneVerification).mockReset()
  vi.mocked(getWorkspaceCredits)
    .mockReset()
    .mockResolvedValue(workspaceCredits({ available: 12 }))
  vi.mocked(listInvoiceRequests).mockReset().mockResolvedValue({ items: [] })
})

describe('계정 설정 화면', () => {
  it('내용을 가운데 정렬한 동일 너비 영역에 배치한다', () => {
    const { container } = renderPage()

    expect(container.firstElementChild).toHaveClass('mx-auto', 'w-full', 'max-w-5xl')
  })

  it('휴대폰 인증 절이 렌더된다', () => {
    renderPage({ user: userResponse({ phone_verified: false }) })

    expect(screen.getByLabelText('휴대폰 번호')).toBeInTheDocument()
    // 세부 흐름(재발송 쿨다운·확인 결과 문구 등)은 PhoneVerificationSection.test.tsx가 다룬다.
  })

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

    expect(screen.getByLabelText('비밀번호', { selector: 'input' })).toBeInTheDocument()
  })

  it('소셜 전용 계정은 비밀번호 입력란이 없다', async () => {
    const user = userEvent.setup()
    renderPage({ user: userResponse({ has_password: false }) })

    await user.click(screen.getByRole('button', { name: '회원 탈퇴' }))

    expect(screen.queryByLabelText('비밀번호', { selector: 'input' })).not.toBeInTheDocument()
  })

  it('제출 성공은 signOut을 부른다', async () => {
    vi.mocked(deleteAccount).mockResolvedValue(undefined)
    const signOut = vi.fn()
    const user = userEvent.setup()

    renderPage({ user: userResponse({ has_password: true }), signOut })
    await user.click(screen.getByRole('button', { name: '회원 탈퇴' }))
    await user.type(screen.getByLabelText('비밀번호', { selector: 'input' }), 'current-password')
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

  it('로그인 연결 절에 연결한 소셜 계정을 보여준다', () => {
    renderPage({ user: userResponse({ identities: [{ provider: 'google' }] }) })

    const section = screen.getByRole('region', { name: '로그인 연결' })
    expect(within(section).getByText('연결한 계정으로도 로그인할 수 있어요.')).toBeInTheDocument()
    expect(within(section).getByRole('button', { name: '연결 해제' })).toBeInTheDocument()
  })

  it('연결을 해제하면 refreshMe를 부른다', async () => {
    vi.mocked(oauthUnlink).mockResolvedValue(undefined)
    const refreshMe = vi.fn()
    const user = userEvent.setup()

    renderPage({
      user: userResponse({
        has_password: true,
        identities: [{ provider: 'google' }, { provider: 'kakao' }],
      }),
      refreshMe,
    })
    await user.click(screen.getAllByRole('button', { name: '연결 해제' })[0]!)
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: '연결 해제' }))

    await waitFor(() => expect(refreshMe).toHaveBeenCalledTimes(1))
    expect(vi.mocked(oauthUnlink)).toHaveBeenCalledWith('google')
  })

  it('비밀번호가 있으면 비밀번호 절을 그리지 않는다', () => {
    renderPage({ user: userResponse({ has_password: true }) })

    expect(screen.queryByRole('region', { name: '비밀번호' })).not.toBeInTheDocument()
  })

  it('비밀번호가 없고 이메일이 미인증이면 인증 링크만 보여준다', () => {
    renderPage({ user: userResponse({ has_password: false, email_verified: false }) })

    const section = screen.getByRole('region', { name: '비밀번호' })
    expect(
      within(section).getByText('비밀번호를 만들면 이메일로도 로그인할 수 있어요.'),
    ).toBeInTheDocument()
    expect(within(section).getByRole('link', { name: '이메일 인증' })).toHaveAttribute(
      'href',
      '/verify-email',
    )
    expect(within(section).queryByRole('button', { name: '비밀번호 만들기' })).toBeNull()
  })

  it('비밀번호가 없고 이메일이 인증됐으면 비밀번호 만들기 폼을 보여준다', () => {
    renderPage({ user: userResponse({ has_password: false, email_verified: true }) })

    const section = screen.getByRole('region', { name: '비밀번호' })
    expect(within(section).getByRole('button', { name: '비밀번호 만들기' })).toBeInTheDocument()
  })

  it('안내 절은 로그인 계정 뒤, 휴대폰 인증과 회원 탈퇴 앞에 온다', () => {
    renderPage({ user: userResponse({ has_password: false, email_verified: true }) })

    const headings = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)
    expect(headings.slice(0, 3)).toEqual(['로그인 계정', '로그인 연결', '비밀번호'])
    expect(headings.at(-1)).toBe('회원 탈퇴')
  })
})
