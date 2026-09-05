import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { fetchMe, oauthLinkCallback } from '../api/auth'
import { ApiError, listDocuments } from '../api/client'
import { AuthProvider } from '../auth/AuthProvider'
import { AppLayout } from '../components/AppLayout'
import { AppRoutes } from '../routes/AppRoutes'
import { userResponse, workspaceContext } from '../test/factories'
import { WorkspaceContext } from '../workspace/context'

const STATE_KEY = 'easydoc.oauth.google.link.state'
const REDIRECT_URI_KEY = 'easydoc.oauth.google.link.redirect_uri'
const STORED_REDIRECT_URI = 'http://localhost:5173/auth/google/link/callback'

vi.mock('../api/auth', () => ({
  login: vi.fn(),
  signup: vi.fn(),
  fetchMe: vi.fn(),
  oauthStart: vi.fn(),
  oauthCallback: vi.fn(),
  oauthLinkStart: vi.fn(),
  oauthLinkCallback: vi.fn(),
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

/** 정상적으로 연결 버튼을 거쳐 들어온 것처럼 세션 저장소와 로그인 상태를 채운다. */
function seedStartedSession(state = 'link-state-xyz') {
  window.localStorage.setItem('easydoc.access_token', 'valid-token')
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
  vi.mocked(oauthLinkCallback).mockReset()
  vi.mocked(listDocuments).mockResolvedValue({ items: [], limit: 20, offset: 0, has_more: false })
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('구글 계정 연결 콜백', () => {
  it('state가 일치하면 코드를 검증하고 성공하면 사용자를 새로고침한 뒤 홈에 안내를 보여준다', async () => {
    seedStartedSession('link-state-xyz')
    vi.mocked(fetchMe)
      .mockResolvedValueOnce(userResponse({ identities: [] }))
      .mockResolvedValueOnce(userResponse({ identities: [{ provider: 'google' }] }))
    vi.mocked(oauthLinkCallback).mockResolvedValue(undefined)

    renderAt('/auth/google/link/callback?code=auth-code&state=link-state-xyz')

    expect(await screen.findByRole('heading', { name: '문서 변환하기' })).toBeInTheDocument()
    expect(vi.mocked(oauthLinkCallback)).toHaveBeenCalledWith('google', {
      code: 'auth-code',
      state: 'link-state-xyz',
      redirectUri: STORED_REDIRECT_URI,
    })
    // refreshMe로 다시 읽은 사용자를 반영한다 — fetchMe가 두 번째로 부른 결과다.
    expect(vi.mocked(fetchMe)).toHaveBeenCalledTimes(2)
    expect(await screen.findByText('구글 계정을 연결했습니다')).toBeInTheDocument()
    expectSessionCleared()
  })

  it('저장된 state와 다르면 API를 부르지 않고 오류를 보여준다', async () => {
    seedStartedSession('link-state-xyz')
    vi.mocked(fetchMe).mockResolvedValue(userResponse())

    renderAt('/auth/google/link/callback?code=auth-code&state=different-state')

    expect(
      await screen.findByText('요청이 만료되었거나 이미 사용되었습니다. 다시 시도해 주세요.'),
    ).toBeInTheDocument()
    expect(vi.mocked(oauthLinkCallback)).not.toHaveBeenCalled()
    expectSessionCleared()
  })

  it('세션 저장소에 시작 기록이 없으면(직접 진입) API를 부르지 않고 오류를 보여준다', async () => {
    window.localStorage.setItem('easydoc.access_token', 'valid-token')
    vi.mocked(fetchMe).mockResolvedValue(userResponse())
    // seedStartedSession을 호출하지 않는다 — 시작하지 않고 이 주소로 바로 들어온 경우.

    renderAt('/auth/google/link/callback?code=auth-code&state=link-state-xyz')

    expect(
      await screen.findByText('요청이 만료되었거나 이미 사용되었습니다. 다시 시도해 주세요.'),
    ).toBeInTheDocument()
    expect(vi.mocked(oauthLinkCallback)).not.toHaveBeenCalled()
  })

  it('구글이 취소를 알리면(error=access_denied) API를 부르지 않고 취소 안내를 보여준다', async () => {
    seedStartedSession('link-state-xyz')
    vi.mocked(fetchMe).mockResolvedValue(userResponse())

    renderAt('/auth/google/link/callback?error=access_denied')

    expect(await screen.findByText('구글 계정 연결을 취소했습니다.')).toBeInTheDocument()
    expect(vi.mocked(oauthLinkCallback)).not.toHaveBeenCalled()
    expectSessionCleared()
  })

  it('이미 다른 계정에 연결된 신원이면(409) 서버가 준 사유를 보여준다', async () => {
    seedStartedSession('link-state-xyz')
    vi.mocked(fetchMe).mockResolvedValue(userResponse())
    vi.mocked(oauthLinkCallback).mockRejectedValue(
      new ApiError(409, '이 구글 계정은 이미 다른 계정에 연결되어 있습니다'),
    )

    renderAt('/auth/google/link/callback?code=auth-code&state=link-state-xyz')

    expect(
      await screen.findByText('이 구글 계정은 이미 다른 계정에 연결되어 있습니다'),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '홈으로 돌아가기' })).toBeInTheDocument()
    await waitFor(() => expectSessionCleared())
  })

  it('제공자에 닿지 못하면(502) 오류를 보여준다', async () => {
    seedStartedSession('link-state-xyz')
    vi.mocked(fetchMe).mockResolvedValue(userResponse())
    vi.mocked(oauthLinkCallback).mockRejectedValue(
      new ApiError(502, '서버에 연결하지 못했습니다. 네트워크 상태를 확인해 주세요.'),
    )

    renderAt('/auth/google/link/callback?code=auth-code&state=link-state-xyz')

    expect(
      await screen.findByText('서버에 연결하지 못했습니다. 네트워크 상태를 확인해 주세요.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '홈으로 돌아가기' })).toBeInTheDocument()
    await waitFor(() => expectSessionCleared())
  })

  it('로그인하지 않은 채로 들어오면 로그인 화면으로 보낸다', async () => {
    // seedStartedSession/토큰 저장을 하지 않는다 — RequireAuth가 걸러낸다.
    renderAt('/auth/google/link/callback?code=auth-code&state=link-state-xyz')

    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(vi.mocked(oauthLinkCallback)).not.toHaveBeenCalled()
  })
})

describe('카카오 계정 연결 콜백', () => {
  const KAKAO_STATE_KEY = 'easydoc.oauth.kakao.link.state'
  const KAKAO_REDIRECT_URI_KEY = 'easydoc.oauth.kakao.link.redirect_uri'
  const KAKAO_STORED_REDIRECT_URI = 'http://localhost:5173/auth/kakao/link/callback'

  function seedKakaoSession(state = 'link-state-xyz') {
    window.localStorage.setItem('easydoc.access_token', 'valid-token')
    window.sessionStorage.setItem(KAKAO_STATE_KEY, state)
    window.sessionStorage.setItem(KAKAO_REDIRECT_URI_KEY, KAKAO_STORED_REDIRECT_URI)
  }

  it('state가 일치하면 코드를 검증하고 성공하면 사용자를 새로고침한 뒤 홈에 안내를 보여준다', async () => {
    seedKakaoSession('link-state-xyz')
    vi.mocked(fetchMe)
      .mockResolvedValueOnce(userResponse({ identities: [] }))
      .mockResolvedValueOnce(userResponse({ identities: [{ provider: 'kakao' }] }))
    vi.mocked(oauthLinkCallback).mockResolvedValue(undefined)

    renderAt('/auth/kakao/link/callback?code=auth-code&state=link-state-xyz')

    expect(await screen.findByRole('heading', { name: '문서 변환하기' })).toBeInTheDocument()
    expect(vi.mocked(oauthLinkCallback)).toHaveBeenCalledWith('kakao', {
      code: 'auth-code',
      state: 'link-state-xyz',
      redirectUri: KAKAO_STORED_REDIRECT_URI,
    })
    expect(await screen.findByText('카카오 계정을 연결했습니다')).toBeInTheDocument()
    expect(window.sessionStorage.getItem(KAKAO_STATE_KEY)).toBeNull()
    expect(window.sessionStorage.getItem(KAKAO_REDIRECT_URI_KEY)).toBeNull()
  })

  it('이미 다른 계정에 연결된 신원이면(409) 서버가 준 사유를 보여준다', async () => {
    seedKakaoSession('link-state-xyz')
    vi.mocked(fetchMe).mockResolvedValue(userResponse())
    vi.mocked(oauthLinkCallback).mockRejectedValue(
      new ApiError(409, '이 카카오 계정은 이미 다른 계정에 연결되어 있습니다'),
    )

    renderAt('/auth/kakao/link/callback?code=auth-code&state=link-state-xyz')

    expect(
      await screen.findByText('이 카카오 계정은 이미 다른 계정에 연결되어 있습니다'),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '홈으로 돌아가기' })).toBeInTheDocument()
  })
})

describe('지원하지 않는 소셜 로그인 provider', () => {
  it('로그인한 상태에서 계약 enum 밖의 provider면 찾을 수 없는 화면을 보여준다', async () => {
    window.localStorage.setItem('easydoc.access_token', 'valid-token')
    vi.mocked(fetchMe).mockResolvedValue(userResponse())

    renderAt('/auth/naver/link/callback?code=auth-code&state=link-state-xyz')

    expect(
      await screen.findByRole('heading', { name: '찾을 수 없는 화면입니다' }),
    ).toBeInTheDocument()
    expect(vi.mocked(oauthLinkCallback)).not.toHaveBeenCalled()
  })
})
