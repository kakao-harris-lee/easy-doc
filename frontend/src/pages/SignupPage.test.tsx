import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { fetchMe, oauthCallback, oauthStart, signup } from '../api/auth'
import { ApiError, listDocuments } from '../api/client'
import { AuthProvider } from '../auth/AuthProvider'
import { AppLayout } from '../components/AppLayout'
import { AppRoutes } from '../routes/AppRoutes'
import { workspaceContext } from '../test/factories'
import { mockLocationAssign } from '../test/location'
import { WorkspaceContext } from '../workspace/context'

vi.mock('../api/auth', () => ({
  login: vi.fn(),
  signup: vi.fn(),
  fetchMe: vi.fn(),
  oauthStart: vi.fn(),
  oauthCallback: vi.fn(),
}))

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  listDocuments: vi.fn(),
}))

function renderAt(path: string) {
  return render(
    <AuthProvider>
      <WorkspaceContext.Provider value={workspaceContext()}>
        <MemoryRouter initialEntries={[path]}>
          <AppLayout>
            <AppRoutes />
          </AppLayout>
        </MemoryRouter>
      </WorkspaceContext.Provider>
    </AuthProvider>,
  )
}

beforeEach(() => {
  window.localStorage.clear()
  window.sessionStorage.clear()
  vi.mocked(signup).mockReset()
  vi.mocked(fetchMe).mockReset()
  vi.mocked(oauthStart).mockReset()
  vi.mocked(oauthCallback).mockReset()
  vi.mocked(listDocuments).mockResolvedValue({ items: [], limit: 20, offset: 0, has_more: false })
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('구글 로그인 시작 (가입 화면)', () => {
  it('시작 요청이 성공하면 redirect_uri를 넘기고 state를 저장한 뒤 인가 URL로 이동한다', async () => {
    const user = userEvent.setup()
    vi.mocked(oauthStart).mockResolvedValue({
      authorization_url: 'https://accounts.google.com/o/oauth2/v2/auth?state=state-xyz',
      state: 'state-xyz',
    })
    const assign = mockLocationAssign()
    renderAt('/signup')

    await user.click(screen.getByRole('button', { name: 'Google로 계속하기' }))

    await waitFor(() => expect(assign).toHaveBeenCalledTimes(1))
    expect(vi.mocked(oauthStart)).toHaveBeenCalledWith(
      'google',
      `${window.location.origin}/auth/google/callback`,
    )
    expect(window.sessionStorage.getItem('easydoc.oauth.google.state')).toBe('state-xyz')
    expect(window.sessionStorage.getItem('easydoc.oauth.google.redirect_uri')).toBe(
      `${window.location.origin}/auth/google/callback`,
    )
    expect(assign).toHaveBeenCalledWith(
      'https://accounts.google.com/o/oauth2/v2/auth?state=state-xyz',
    )
  })

  it('제공자가 설정되지 않았으면(422) 버튼 아래에 안내하고 이메일 가입 폼은 그대로 쓸 수 있다', async () => {
    const user = userEvent.setup()
    vi.mocked(oauthStart).mockRejectedValue(new ApiError(422, '구글 로그인이 설정되지 않았습니다'))
    const assign = mockLocationAssign()
    renderAt('/signup')

    await user.click(screen.getByRole('button', { name: 'Google로 계속하기' }))

    expect(await screen.findByText('구글 로그인이 설정되지 않았습니다')).toBeInTheDocument()
    expect(assign).not.toHaveBeenCalled()
    expect(screen.getByLabelText('이메일')).toBeEnabled()
    expect(screen.getByRole('button', { name: '가입하기' })).toBeEnabled()
  })
})

describe('카카오 로그인 시작 (가입 화면)', () => {
  it('시작 요청이 성공하면 redirect_uri를 넘기고 state를 저장한 뒤 인가 URL로 이동한다', async () => {
    const user = userEvent.setup()
    vi.mocked(oauthStart).mockResolvedValue({
      authorization_url: 'https://kauth.kakao.com/oauth/authorize?state=state-xyz',
      state: 'state-xyz',
    })
    const assign = mockLocationAssign()
    renderAt('/signup')

    await user.click(screen.getByRole('button', { name: '카카오로 계속하기' }))

    await waitFor(() => expect(assign).toHaveBeenCalledTimes(1))
    expect(vi.mocked(oauthStart)).toHaveBeenCalledWith(
      'kakao',
      `${window.location.origin}/auth/kakao/callback`,
    )
    expect(window.sessionStorage.getItem('easydoc.oauth.kakao.state')).toBe('state-xyz')
    expect(window.sessionStorage.getItem('easydoc.oauth.kakao.redirect_uri')).toBe(
      `${window.location.origin}/auth/kakao/callback`,
    )
    expect(assign).toHaveBeenCalledWith('https://kauth.kakao.com/oauth/authorize?state=state-xyz')
  })

  it('제공자가 설정되지 않았으면(422) 버튼 아래에 안내하고 이메일 가입 폼은 그대로 쓸 수 있다', async () => {
    const user = userEvent.setup()
    vi.mocked(oauthStart).mockRejectedValue(
      new ApiError(422, '카카오 로그인이 설정되지 않았습니다'),
    )
    const assign = mockLocationAssign()
    renderAt('/signup')

    await user.click(screen.getByRole('button', { name: '카카오로 계속하기' }))

    expect(await screen.findByText('카카오 로그인이 설정되지 않았습니다')).toBeInTheDocument()
    expect(assign).not.toHaveBeenCalled()
    expect(screen.getByLabelText('이메일')).toBeEnabled()
    expect(screen.getByRole('button', { name: '가입하기' })).toBeEnabled()
  })
})
