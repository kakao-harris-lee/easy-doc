import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { lookupTerm } from './dictionary'
import { writeToken } from './token'

/** JSON 응답을 흉내 낸다 (client.test.ts와 같은 방식). */
function jsonResponse(status: number, payload: unknown, headers: HeadersInit = {}): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
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
  writeToken('token-abc')
})

afterEach(() => {
  vi.unstubAllGlobals()
})

const CANDIDATE = {
  term: '구비서류',
  easy_term: '준비할 서류',
  strategy: 'substitute' as const,
  risk: 'none' as const,
  definition: '신청할 때 미리 갖춰야 하는 서류',
  caution: null,
  tags: [],
  examples: [],
  match_kind: 'exact' as const,
  applicable: true,
}

describe('lookupTerm', () => {
  it('POST /dictionary/lookup에 지목한 문자열만 담아 보낸다(위치 정보 없음)', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(200, {
        query: '구비서류',
        candidates: [CANDIDATE],
        dictionary: { name: '쉬운 말 사전', license: 'CC-BY', schema_version: '1.0.0' },
      }),
    )

    const result = await lookupTerm('구비서류')

    const [url, init] = fetchMock.mock.calls[0] ?? []
    expect(url).toBe(`${apiBaseUrl}/dictionary/lookup`)
    expect(init?.method).toBe('POST')
    expect(init?.body).toBe(JSON.stringify({ text: '구비서류' }))
    // 인증 필수 — 저장된 토큰을 붙인다.
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer token-abc')
    expect(result).toEqual({
      query: '구비서류',
      candidates: [CANDIDATE],
      dictionary: { name: '쉬운 말 사전', license: 'CC-BY', schema_version: '1.0.0' },
    })
  })

  it('후보 0건도 200으로 온다(404가 아니다)', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(200, {
        query: '게시판',
        candidates: [],
        dictionary: { name: '쉬운 말 사전', license: 'CC-BY', schema_version: '1.0.0' },
      }),
    )

    const result = await lookupTerm('게시판')

    expect(result.candidates).toEqual([])
  })

  it('422(사전 조회 꺼짐)를 ApiError로 올린다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(422, { detail: '사전 조회가 꺼져 있습니다' }))

    await expect(lookupTerm('구비서류')).rejects.toMatchObject({
      status: 422,
      message: '사전 조회가 꺼져 있습니다',
    })
  })

  it('429는 Retry-After를 ApiError.retryAfterSeconds로 옮긴다', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(429, { detail: '잠시 후 다시 시도해주세요' }, { 'Retry-After': '42' }),
    )

    await expect(lookupTerm('구비서류')).rejects.toMatchObject({
      status: 429,
      retryAfterSeconds: 42,
    })
  })
})
