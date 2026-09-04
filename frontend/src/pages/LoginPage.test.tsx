import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { fetchMe, login, oauthCallback, oauthLinkStart, oauthStart } from '../api/auth'
import { ApiError, listDocuments } from '../api/client'
import { AuthProvider } from '../auth/AuthProvider'
import { AppLayout } from '../components/AppLayout'
import { workspaceContext } from '../test/factories'
import { mockLocationAssign } from '../test/location'
import { WorkspaceContext } from '../workspace/context'
import { AppRoutes } from '../routes/AppRoutes'

vi.mock('../api/auth', () => ({
  login: vi.fn(),
  signup: vi.fn(),
  fetchMe: vi.fn(),
  oauthStart: vi.fn(),
  oauthCallback: vi.fn(),
  oauthLinkStart: vi.fn(),
}))

// 로그인에 성공하면 홈(업로드 화면)이 뜨고, 그 화면은 「다음 할 일」 근거로 문서를
// 조회한다(§7). 모킹하지 않으면 진짜 요청이 나가 이 가짜 토큰에 401이 돌아오고, API
// 클라이언트가 그 401에 토큰을 지워 "토큰을 저장한다"는 단언이 무너진다. ApiError는
// 아래 테스트가 실제 클래스로 쓰므로 부분 모킹으로 원본을 남긴다.
vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  listDocuments: vi.fn(),
}))

function renderAt(path: string) {
  return render(
    <AuthProvider>
      {/* 머리말의 작업 공간 메뉴가 이 컨텍스트를 읽는다 — 여기서는 관심사가 아니라
          고정된 값을 꽂는다(요청은 WorkspaceProvider 테스트가 본다). */}
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
  vi.mocked(login).mockReset()
  vi.mocked(fetchMe).mockReset()
  vi.mocked(oauthStart).mockReset()
  vi.mocked(oauthCallback).mockReset()
  vi.mocked(oauthLinkStart).mockReset()
  vi.mocked(listDocuments).mockResolvedValue({ items: [], limit: 20, offset: 0, has_more: false })
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('로그인 화면', () => {
  it('입력이 비어 있으면 서버를 부르지 않고 오류를 알린다', async () => {
    const user = userEvent.setup()
    renderAt('/login')

    await user.click(screen.getByRole('button', { name: '로그인' }))

    expect(await screen.findByText('이메일을 입력해 주세요')).toBeInTheDocument()
    expect(screen.getByText('비밀번호를 입력해 주세요')).toBeInTheDocument()
    expect(vi.mocked(login)).not.toHaveBeenCalled()
  })

  it('비밀번호가 짧으면 길이 기준을 알린다', async () => {
    const user = userEvent.setup()
    renderAt('/login')

    await user.type(screen.getByLabelText('이메일'), 'user@example.com')
    await user.type(screen.getByLabelText('비밀번호'), 'short')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    expect(await screen.findByText('비밀번호는 8자 이상이어야 합니다')).toBeInTheDocument()
    expect(vi.mocked(login)).not.toHaveBeenCalled()
  })

  it('로그인에 성공하면 토큰을 저장하고 홈으로 이동한다', async () => {
    const user = userEvent.setup()
    vi.mocked(login).mockResolvedValue({
      access_token: 'token-abc',
      token_type: 'bearer',
      expires_in: 3600,
    })
    vi.mocked(fetchMe).mockResolvedValue({
      id: 'u1',
      email: 'user@example.com',
      email_verified: true,
      identities: [],
    })
    renderAt('/login')

    await user.type(screen.getByLabelText('이메일'), 'user@example.com')
    await user.type(screen.getByLabelText('비밀번호'), 'password123')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    expect(await screen.findByRole('heading', { name: '문서 변환하기' })).toBeInTheDocument()
    expect(vi.mocked(login)).toHaveBeenCalledWith({
      email: 'user@example.com',
      password: 'password123',
    })
    expect(window.localStorage.getItem('easydoc.access_token')).toBe('token-abc')
  })

  it('서버가 거절하면 그 사유를 화면에 보여준다', async () => {
    const user = userEvent.setup()
    vi.mocked(login).mockRejectedValue(
      new ApiError(401, '이메일 또는 비밀번호가 올바르지 않습니다'),
    )
    renderAt('/login')

    await user.type(screen.getByLabelText('이메일'), 'user@example.com')
    await user.type(screen.getByLabelText('비밀번호'), 'password123')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('이메일 또는 비밀번호가 올바르지 않습니다')
    // 실패했으므로 다시 시도할 수 있어야 한다.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '로그인' })).toBeEnabled()
    })
  })
})

describe('구글 로그인 시작 (로그인 화면)', () => {
  it('시작 요청이 성공하면 redirect_uri를 넘기고 state를 저장한 뒤 인가 URL로 이동한다', async () => {
    const user = userEvent.setup()
    vi.mocked(oauthStart).mockResolvedValue({
      authorization_url: 'https://accounts.google.com/o/oauth2/v2/auth?state=state-xyz',
      state: 'state-xyz',
    })
    const assign = mockLocationAssign()
    renderAt('/login')

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

  it('제공자가 설정되지 않았으면(422) 버튼 아래에 안내하고 이메일 폼은 그대로 쓸 수 있다', async () => {
    const user = userEvent.setup()
    vi.mocked(oauthStart).mockRejectedValue(new ApiError(422, '구글 로그인이 설정되지 않았습니다'))
    const assign = mockLocationAssign()
    renderAt('/login')

    await user.click(screen.getByRole('button', { name: 'Google로 계속하기' }))

    expect(await screen.findByText('구글 로그인이 설정되지 않았습니다')).toBeInTheDocument()
    expect(assign).not.toHaveBeenCalled()
    // 이메일 폼은 이 실패와 무관하게 여전히 쓸 수 있다.
    expect(screen.getByLabelText('이메일')).toBeEnabled()
    expect(screen.getByRole('button', { name: '로그인' })).toBeEnabled()
  })
})

describe('구글 계정 연결 이어가기 (?link=google)', () => {
  it('로그인에 성공하면 연결 시작 요청을 보내고 인가 URL로 이동한다 — 홈으로는 가지 않는다', async () => {
    const user = userEvent.setup()
    vi.mocked(login).mockResolvedValue({
      access_token: 'token-abc',
      token_type: 'bearer',
      expires_in: 3600,
    })
    vi.mocked(fetchMe).mockResolvedValue({
      id: 'u1',
      email: 'user@example.com',
      email_verified: true,
      identities: [],
    })
    vi.mocked(oauthLinkStart).mockResolvedValue({
      authorization_url: 'https://accounts.google.com/o/oauth2/v2/auth?state=link-state',
      state: 'link-state',
    })
    const assign = mockLocationAssign()
    renderAt('/login?link=google')

    await user.type(screen.getByLabelText('이메일'), 'user@example.com')
    await user.type(screen.getByLabelText('비밀번호'), 'password123')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    await waitFor(() => expect(assign).toHaveBeenCalledTimes(1))
    expect(vi.mocked(oauthLinkStart)).toHaveBeenCalledWith(
      'google',
      `${window.location.origin}/auth/google/link/callback`,
    )
    expect(window.sessionStorage.getItem('easydoc.oauth.google.link.state')).toBe('link-state')
    expect(window.sessionStorage.getItem('easydoc.oauth.google.link.redirect_uri')).toBe(
      `${window.location.origin}/auth/google/link/callback`,
    )
    expect(assign).toHaveBeenCalledWith(
      'https://accounts.google.com/o/oauth2/v2/auth?state=link-state',
    )
  })

  it('연결 시작이 실패해도 로그인 자체는 성공했으니 평소처럼 홈으로 보낸다', async () => {
    const user = userEvent.setup()
    vi.mocked(login).mockResolvedValue({
      access_token: 'token-abc',
      token_type: 'bearer',
      expires_in: 3600,
    })
    vi.mocked(fetchMe).mockResolvedValue({
      id: 'u1',
      email: 'user@example.com',
      email_verified: true,
      identities: [],
    })
    vi.mocked(oauthLinkStart).mockRejectedValue(
      new ApiError(422, '구글 로그인이 설정되지 않았습니다'),
    )
    renderAt('/login?link=google')

    await user.type(screen.getByLabelText('이메일'), 'user@example.com')
    await user.type(screen.getByLabelText('비밀번호'), 'password123')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    expect(await screen.findByRole('heading', { name: '문서 변환하기' })).toBeInTheDocument()
  })

  it('표시가 없으면(일반 로그인) 연결 시작을 부르지 않는다', async () => {
    const user = userEvent.setup()
    vi.mocked(login).mockResolvedValue({
      access_token: 'token-abc',
      token_type: 'bearer',
      expires_in: 3600,
    })
    vi.mocked(fetchMe).mockResolvedValue({
      id: 'u1',
      email: 'user@example.com',
      email_verified: true,
      identities: [],
    })
    renderAt('/login')

    await user.type(screen.getByLabelText('이메일'), 'user@example.com')
    await user.type(screen.getByLabelText('비밀번호'), 'password123')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    expect(await screen.findByRole('heading', { name: '문서 변환하기' })).toBeInTheDocument()
    expect(vi.mocked(oauthLinkStart)).not.toHaveBeenCalled()
  })
})
