import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { fetchMe, login } from './auth'
import {
  ApiError,
  NETWORK_ERROR_STATUS,
  downloadExport,
  listDocuments,
  setUnauthorizedHandler,
} from './client'
import { readToken, writeToken } from './token'

/** JSON 응답을 흉내 낸다. */
function jsonResponse(status: number, payload: unknown): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const fetchMock = vi.fn<typeof fetch>()

beforeEach(() => {
  window.localStorage.clear()
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  setUnauthorizedHandler(null)
  vi.unstubAllGlobals()
})

describe('요청 조립', () => {
  it('저장된 토큰을 Authorization 헤더로 붙인다', async () => {
    writeToken('token-abc')
    fetchMock.mockResolvedValue(jsonResponse(200, { id: 'u1', email: 'a@example.com' }))

    await fetchMe()

    const [url, init] = fetchMock.mock.calls[0] ?? []
    expect(url).toBe('http://localhost:8000/auth/me')
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer token-abc')
  })

  it('인증 전 호출(로그인)에는 토큰을 붙이지 않는다', async () => {
    writeToken('token-abc')
    fetchMock.mockResolvedValue(
      jsonResponse(200, { access_token: 't', token_type: 'bearer', expires_in: 3600 }),
    )

    await login({ email: 'a@example.com', password: 'password123' })

    const init = fetchMock.mock.calls[0]?.[1]
    expect(new Headers(init?.headers).get('Authorization')).toBeNull()
    expect(init?.body).toBe(JSON.stringify({ email: 'a@example.com', password: 'password123' }))
  })

  it('목록 조회의 페이지 인자를 쿼리 문자열로 넘긴다', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(200, { items: [], limit: 20, offset: 0, has_more: false }),
    )

    await listDocuments({ limit: 20, offset: 40 })

    expect(fetchMock.mock.calls[0]?.[0]).toBe('http://localhost:8000/documents?limit=20&offset=40')
  })
})

describe('401 처리', () => {
  it('토큰을 들고 간 요청이 401이면 토큰을 버리고 앱에 알린다', async () => {
    writeToken('expired-token')
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    fetchMock.mockResolvedValue(jsonResponse(401, { detail: '유효하지 않은 인증 정보입니다' }))

    await expect(fetchMe()).rejects.toThrow(ApiError)

    expect(readToken()).toBeNull()
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
  })

  it('로그인 실패(401)로는 저장된 토큰을 건드리지 않는다', async () => {
    writeToken('valid-token')
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    fetchMock.mockResolvedValue(
      jsonResponse(401, { detail: '이메일 또는 비밀번호가 올바르지 않습니다' }),
    )

    await expect(login({ email: 'a@example.com', password: 'wrongpassword' })).rejects.toThrow(
      ApiError,
    )

    expect(readToken()).toBe('valid-token')
    expect(onUnauthorized).not.toHaveBeenCalled()
  })
})

describe('오류 해석', () => {
  it('detail 문자열을 그대로 메시지로 쓴다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(409, { detail: '이미 가입된 이메일입니다' }))

    await expect(login({ email: 'a@example.com', password: 'password123' })).rejects.toMatchObject({
      status: 409,
      message: '이미 가입된 이메일입니다',
    })
  })

  it('detail 배열(422 검증 오류)에서 msg만 모은다', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(422, {
        detail: [
          { loc: ['body', 'email'], msg: '이메일을 입력해 주세요', type: 'missing' },
          { loc: ['body', 'password'], msg: '비밀번호를 입력해 주세요', type: 'missing' },
        ],
      }),
    )

    await expect(login({ email: '', password: '' })).rejects.toMatchObject({
      status: 422,
      message: '이메일을 입력해 주세요\n비밀번호를 입력해 주세요',
    })
  })

  it('JSON이 아닌 오류 응답에도 안내 문구를 만든다', async () => {
    fetchMock.mockResolvedValue(new Response('<html>502</html>', { status: 502 }))

    await expect(fetchMe()).rejects.toMatchObject({
      status: 502,
      message: expect.stringContaining('요청을 처리하지 못했습니다'),
    })
  })

  it('연결 자체가 실패하면 네트워크 오류로 알린다', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))

    await expect(fetchMe()).rejects.toMatchObject({
      status: NETWORK_ERROR_STATUS,
      message: expect.stringContaining('서버에 연결하지 못했습니다'),
    })
  })
})

describe('내보내기', () => {
  it('format 쿼리를 붙이고 filename* 을 파일명으로 쓴다', async () => {
    writeToken('token-abc')
    const filename = '기초연금.txt'
    fetchMock.mockResolvedValue(
      new Response('본문', {
        status: 200,
        headers: {
          'Content-Disposition': `attachment; filename="easy-read.txt"; filename*=UTF-8''${encodeURIComponent(filename)}`,
        },
      }),
    )

    const file = await downloadExport('c1', 'txt')

    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      'http://localhost:8000/conversions/c1/export?format=txt',
    )
    expect(file.filename).toBe(filename)
    expect(await file.blob.text()).toBe('본문')
  })

  it('filename* 이 없으면 파일명을 null 로 둔다', async () => {
    writeToken('token-abc')
    fetchMock.mockResolvedValue(
      new Response('본문', {
        status: 200,
        headers: { 'Content-Disposition': 'attachment; filename="easy-read.docx"' },
      }),
    )

    const file = await downloadExport('c1', 'docx')

    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      'http://localhost:8000/conversions/c1/export?format=docx',
    )
    expect(file.filename).toBeNull()
  })
})
