import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { fetchMe, oauthCallback } from '../api/auth'
import { ApiError, listDocuments } from '../api/client'
import { AuthProvider } from '../auth/AuthProvider'
import { AppLayout } from '../components/AppLayout'
import { AppRoutes } from '../routes/AppRoutes'
import { workspaceContext } from '../test/factories'
import { WorkspaceContext } from '../workspace/context'

const STATE_KEY = 'easydoc.oauth.google.state'
const REDIRECT_URI_KEY = 'easydoc.oauth.google.redirect_uri'
const STORED_REDIRECT_URI = 'http://localhost:5173/auth/google/callback'

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

/** 정상적으로 시작을 거쳐 들어온 것처럼 세션 저장소를 채운다. */
function seedStartedSession(state = 'state-xyz') {
  window.sessionStorage.setItem(STATE_KEY, state)
  window.sessionStorage.setItem(REDIRECT_URI_KEY, STORED_REDIRECT_URI)
}

function expectSessionCleared() {
  expect(window.sessionStorage.getItem(STATE_KEY)).toBeNull()
  expect(window.sessionStorage.getItem(REDIRECT_URI_KEY)).toBeNull()
}

beforeEach(() => {
  window.localStorage.clear()
  window.sessionStorage.clear()
  vi.mocked(fetchMe).mockReset()
  vi.mocked(oauthCallback).mockReset()
  vi.mocked(listDocuments).mockResolvedValue({ items: [], limit: 20, offset: 0, has_more: false })
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('구글 로그인 콜백', () => {
  it('state가 일치하면 코드를 교환하고 성공 시 토큰을 저장한 뒤 홈으로 이동한다', async () => {
    seedStartedSession('state-xyz')
    vi.mocked(oauthCallback).mockResolvedValue({
      access_token: 'token-abc',
      token_type: 'bearer',
      expires_in: 3600,
    })
    vi.mocked(fetchMe).mockResolvedValue({ id: 'u1', email: 'user@example.com' })

    renderAt('/auth/google/callback?code=auth-code&state=state-xyz')

    expect(await screen.findByRole('heading', { name: '문서 변환하기' })).toBeInTheDocument()
    expect(vi.mocked(oauthCallback)).toHaveBeenCalledWith('google', {
      code: 'auth-code',
      state: 'state-xyz',
      redirectUri: STORED_REDIRECT_URI,
    })
    expect(window.localStorage.getItem('easydoc.access_token')).toBe('token-abc')
    expectSessionCleared()
  })

  it('저장된 state와 다르면 API를 부르지 않고 오류를 보여준다', async () => {
    seedStartedSession('state-xyz')

    renderAt('/auth/google/callback?code=auth-code&state=different-state')

    expect(
      await screen.findByText('요청이 만료되었거나 이미 사용되었습니다. 다시 시도해 주세요.'),
    ).toBeInTheDocument()
    expect(vi.mocked(oauthCallback)).not.toHaveBeenCalled()
    expectSessionCleared()
  })

  it('세션 저장소에 시작 기록이 없으면(직접 진입) API를 부르지 않고 오류를 보여준다', async () => {
    // seedStartedSession을 호출하지 않는다 — 시작하지 않고 이 주소로 바로 들어온 경우.
    renderAt('/auth/google/callback?code=auth-code&state=state-xyz')

    expect(
      await screen.findByText('요청이 만료되었거나 이미 사용되었습니다. 다시 시도해 주세요.'),
    ).toBeInTheDocument()
    expect(vi.mocked(oauthCallback)).not.toHaveBeenCalled()
  })

  it('구글이 취소를 알리면(error=access_denied) API를 부르지 않고 취소 안내를 보여준다', async () => {
    seedStartedSession('state-xyz')

    renderAt('/auth/google/callback?error=access_denied')

    expect(await screen.findByText('구글 로그인을 취소했습니다.')).toBeInTheDocument()
    expect(vi.mocked(oauthCallback)).not.toHaveBeenCalled()
    expectSessionCleared()
  })

  it('이미 같은 이메일로 가입돼 있으면(409) 로그인으로 안내한다', async () => {
    seedStartedSession('state-xyz')
    vi.mocked(oauthCallback).mockRejectedValue(
      new ApiError(
        409,
        '이미 같은 이메일로 가입된 계정이 있습니다. 이메일로 로그인한 뒤 연결해 주세요.',
      ),
    )

    renderAt('/auth/google/callback?code=auth-code&state=state-xyz')

    expect(
      await screen.findByText(
        '이미 같은 이메일로 가입된 계정이 있습니다. 이메일로 로그인한 뒤 연결해 주세요.',
      ),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '이메일로 로그인하기' })).toBeInTheDocument()
    expectSessionCleared()
  })

  it('제공자에 닿지 못하면(502) 오류를 보여준다', async () => {
    seedStartedSession('state-xyz')
    vi.mocked(oauthCallback).mockRejectedValue(
      new ApiError(502, '서버에 연결하지 못했습니다. 네트워크 상태를 확인해 주세요.'),
    )

    renderAt('/auth/google/callback?code=auth-code&state=state-xyz')

    expect(
      await screen.findByText('서버에 연결하지 못했습니다. 네트워크 상태를 확인해 주세요.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '로그인 화면으로 돌아가기' })).toBeInTheDocument()
    await waitFor(() => expectSessionCleared())
  })
})
