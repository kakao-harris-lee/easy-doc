import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { oauthCallback, oauthStart } from './auth'
import { setUnauthorizedHandler } from './client'
import { writeToken } from './token'

/** JSON 응답을 흉내 낸다 (client.test.ts와 같은 방식). */
function jsonResponse(status: number, payload: unknown): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const fetchMock = vi.fn<typeof fetch>()
const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8000').replace(
  /\/+$/,
  '',
)

beforeEach(() => {
  window.localStorage.clear()
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  setUnauthorizedHandler(null)
  vi.unstubAllGlobals()
})

describe('oauthStart', () => {
  it('provider 경로와 redirect_uri 본문으로 인증 없이 요청한다', async () => {
    // 시작 요청은 로그인 전이라 저장된 토큰이 있어도 붙이지 않는다 — login과 같은 규칙.
    writeToken('token-abc')
    fetchMock.mockResolvedValue(
      jsonResponse(200, {
        authorization_url: 'https://accounts.google.com/o/oauth2/v2/auth?...',
        state: 'state-xyz',
      }),
    )

    const result = await oauthStart('google', 'http://localhost:5173/auth/google/callback')

    const [url, init] = fetchMock.mock.calls[0] ?? []
    expect(url).toBe(`${apiBaseUrl}/auth/oauth/google/start`)
    expect(init?.method).toBe('POST')
    expect(init?.body).toBe(
      JSON.stringify({ redirect_uri: 'http://localhost:5173/auth/google/callback' }),
    )
    expect(new Headers(init?.headers).get('Authorization')).toBeNull()
    expect(result).toEqual({
      authorization_url: 'https://accounts.google.com/o/oauth2/v2/auth?...',
      state: 'state-xyz',
    })
  })

  it('422(제공자 미설정 등)를 ApiError로 올린다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(422, { detail: '구글 로그인이 설정되지 않았습니다' }))

    await expect(
      oauthStart('google', 'http://localhost:5173/auth/google/callback'),
    ).rejects.toMatchObject({
      status: 422,
      message: '구글 로그인이 설정되지 않았습니다',
    })
  })
})

describe('oauthCallback', () => {
  it('code·state·redirect_uri 본문으로 인증 없이 요청하고 TokenResponse를 돌려준다', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(200, { access_token: 'token-abc', token_type: 'bearer', expires_in: 3600 }),
    )

    const result = await oauthCallback('google', {
      code: 'auth-code',
      state: 'state-xyz',
      redirectUri: 'http://localhost:5173/auth/google/callback',
    })

    const [url, init] = fetchMock.mock.calls[0] ?? []
    expect(url).toBe(`${apiBaseUrl}/auth/oauth/google/callback`)
    expect(init?.method).toBe('POST')
    expect(init?.body).toBe(
      JSON.stringify({
        code: 'auth-code',
        state: 'state-xyz',
        redirect_uri: 'http://localhost:5173/auth/google/callback',
      }),
    )
    expect(new Headers(init?.headers).get('Authorization')).toBeNull()
    expect(result).toEqual({ access_token: 'token-abc', token_type: 'bearer', expires_in: 3600 })
  })

  it('409(이미 연결된 이메일)를 ApiError로 올린다', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(409, {
        detail: '이미 같은 이메일로 가입된 계정이 있습니다. 이메일로 로그인한 뒤 연결해 주세요.',
      }),
    )

    await expect(
      oauthCallback('google', {
        code: 'auth-code',
        state: 'state-xyz',
        redirectUri: 'http://localhost:5173/auth/google/callback',
      }),
    ).rejects.toMatchObject({
      status: 409,
      message: '이미 같은 이메일로 가입된 계정이 있습니다. 이메일로 로그인한 뒤 연결해 주세요.',
    })
  })

  it('502(제공자 연결 불가)를 ApiError로 올린다', async () => {
    fetchMock.mockResolvedValue(new Response('<html>502</html>', { status: 502 }))

    await expect(
      oauthCallback('google', {
        code: 'auth-code',
        state: 'state-xyz',
        redirectUri: 'http://localhost:5173/auth/google/callback',
      }),
    ).rejects.toMatchObject({ status: 502 })
  })
})
