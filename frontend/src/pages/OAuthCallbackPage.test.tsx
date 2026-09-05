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
    vi.mocked(fetchMe).mockResolvedValue({
      id: 'u1',
      email: 'user@example.com',
      email_verified: true,
      identities: [],
    })

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

  it('이미 같은 이메일로 가입돼 있으면(409) 명시적 연결로 이어지는 로그인을 안내한다', async () => {
    seedStartedSession('state-xyz')
    // 서버 문구(계정 탈취 방지 갈래)가 아니라, 명시적 연결 흐름으로 안내하는 화면
    // 자체의 고정 문구를 보여준다 — 2.10.0부터 이 문이 실제로 있기 때문이다.
    vi.mocked(oauthCallback).mockRejectedValue(
      new ApiError(
        409,
        '이미 같은 이메일로 가입된 계정이 있습니다. 이메일로 로그인한 뒤 연결해 주세요.',
      ),
    )

    renderAt('/auth/google/callback?code=auth-code&state=state-xyz')

    expect(
      await screen.findByText(
        '이미 이 이메일로 가입된 계정이 있습니다. 이메일로 로그인하면 구글 계정을 연결해 드립니다.',
      ),
    ).toBeInTheDocument()
    const link = screen.getByRole('link', { name: '이메일로 로그인하기' })
    expect(link).toBeInTheDocument()
    // LoginPage가 로그인 성공 직후 연결을 이어서 시작할 수 있도록 표시를 싣는다.
    expect(link).toHaveAttribute('href', '/login?link=google')
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

describe('카카오 로그인 콜백', () => {
  const KAKAO_STATE_KEY = 'easydoc.oauth.kakao.state'
  const KAKAO_REDIRECT_URI_KEY = 'easydoc.oauth.kakao.redirect_uri'
  const KAKAO_STORED_REDIRECT_URI = 'http://localhost:5173/auth/kakao/callback'

  function seedKakaoSession(state = 'state-xyz') {
    window.sessionStorage.setItem(KAKAO_STATE_KEY, state)
    window.sessionStorage.setItem(KAKAO_REDIRECT_URI_KEY, KAKAO_STORED_REDIRECT_URI)
  }

  it('state가 일치하면 코드를 교환하고 성공 시 토큰을 저장한 뒤 홈으로 이동한다', async () => {
    seedKakaoSession('state-xyz')
    vi.mocked(oauthCallback).mockResolvedValue({
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

    renderAt('/auth/kakao/callback?code=auth-code&state=state-xyz')

    expect(await screen.findByRole('heading', { name: '문서 변환하기' })).toBeInTheDocument()
    expect(vi.mocked(oauthCallback)).toHaveBeenCalledWith('kakao', {
      code: 'auth-code',
      state: 'state-xyz',
      redirectUri: KAKAO_STORED_REDIRECT_URI,
    })
    expect(window.localStorage.getItem('easydoc.access_token')).toBe('token-abc')
    expect(window.sessionStorage.getItem(KAKAO_STATE_KEY)).toBeNull()
    expect(window.sessionStorage.getItem(KAKAO_REDIRECT_URI_KEY)).toBeNull()
  })

  it('카카오가 취소를 알리면(error=access_denied) API를 부르지 않고 취소 안내를 보여준다', async () => {
    seedKakaoSession('state-xyz')

    renderAt('/auth/kakao/callback?error=access_denied')

    expect(await screen.findByText('카카오 로그인을 취소했습니다.')).toBeInTheDocument()
    expect(vi.mocked(oauthCallback)).not.toHaveBeenCalled()
  })

  it('이미 같은 이메일로 가입돼 있으면(409) 명시적 연결로 이어지는 로그인을 안내한다', async () => {
    seedKakaoSession('state-xyz')
    vi.mocked(oauthCallback).mockRejectedValue(
      new ApiError(
        409,
        '이미 같은 이메일로 가입된 계정이 있습니다. 이메일로 로그인한 뒤 연결해 주세요.',
      ),
    )

    renderAt('/auth/kakao/callback?code=auth-code&state=state-xyz')

    expect(
      await screen.findByText(
        '이미 이 이메일로 가입된 계정이 있습니다. 이메일로 로그인하면 카카오 계정을 연결해 드립니다.',
      ),
    ).toBeInTheDocument()
    const link = screen.getByRole('link', { name: '이메일로 로그인하기' })
    expect(link).toHaveAttribute('href', '/login?link=kakao')
  })
})

describe('네이버 로그인 콜백', () => {
  const NAVER_STATE_KEY = 'easydoc.oauth.naver.state'
  const NAVER_REDIRECT_URI_KEY = 'easydoc.oauth.naver.redirect_uri'
  const NAVER_STORED_REDIRECT_URI = 'http://localhost:5173/auth/naver/callback'

  function seedNaverSession(state = 'state-xyz') {
    window.sessionStorage.setItem(NAVER_STATE_KEY, state)
    window.sessionStorage.setItem(NAVER_REDIRECT_URI_KEY, NAVER_STORED_REDIRECT_URI)
  }

  it(
    '네이버는 이메일이 있어도 미검증으로 계정을 만들 수 있다 — 성공해도 홈이 아니라 ' +
      '이메일 인증 화면으로 이동한다(readMe.email_verified=false, 2026-09-05 결정)',
    async () => {
      seedNaverSession('state-xyz')
      vi.mocked(oauthCallback).mockResolvedValue({
        access_token: 'token-abc',
        token_type: 'bearer',
        expires_in: 3600,
      })
      vi.mocked(fetchMe).mockResolvedValue({
        id: 'u1',
        email: 'user@example.com',
        email_verified: false,
        identities: [],
      })

      renderAt('/auth/naver/callback?code=auth-code&state=state-xyz')

      expect(await screen.findByRole('heading', { name: '이메일 인증' })).toBeInTheDocument()
      expect(vi.mocked(oauthCallback)).toHaveBeenCalledWith('naver', {
        code: 'auth-code',
        state: 'state-xyz',
        redirectUri: NAVER_STORED_REDIRECT_URI,
      })
      expect(window.localStorage.getItem('easydoc.access_token')).toBe('token-abc')
    },
  )
})

describe('지원하지 않는 소셜 로그인 provider', () => {
  it('계약 enum 밖의 provider면 찾을 수 없는 화면을 보여준다', async () => {
    renderAt('/auth/foo/callback?code=auth-code&state=state-xyz')

    expect(
      await screen.findByRole('heading', { name: '찾을 수 없는 화면입니다' }),
    ).toBeInTheDocument()
    expect(vi.mocked(oauthCallback)).not.toHaveBeenCalled()
  })
})
