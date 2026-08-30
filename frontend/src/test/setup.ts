import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach, vi } from 'vitest'

// globals: false 설정이라 Testing Library의 자동 정리가 걸리지 않는다 — 직접 건다.
afterEach(() => {
  cleanup()
})

/**
 * 단위 테스트에서 나간 진짜 네트워크 요청. 있으면 그 테스트를 실패시킨다.
 *
 * 실제로 겪은 사고를 막는다: 어떤 화면이 새 API 호출을 갖게 됐는데 그 호출을 모킹하지
 * 않은 테스트가 있으면, jsdom의 fetch가 `VITE_API_BASE_URL`로 **진짜** 요청을 보낸다.
 * 개발 기계에서 그 주소에 서버가 떠 있으면(E2E compose 스택) 테스트가 쓰는 가짜 토큰에
 * 401이 돌아오고, API 클라이언트는 그것을 세션 만료로 처리해 토큰을 지운다 — 화면이
 * 단언 도중에 로그인으로 넘어간다. 서버가 떠 있는지와 응답이 단언보다 먼저 오는지에
 * 따라 결과가 갈리므로 flaky로 보이고, 원인은 테스트 파일 어디에도 적혀 있지 않다.
 *
 * 그래서 요청을 거절만 하지 않고 **기록해서 실패시킨다**. 거절만 하면 API 클라이언트가
 * 그것을 연결 실패(ApiError)로 바꿔 삼키고, 화면이 조용히 넘어가 아무도 모른다.
 *
 * fetch를 진짜로 쓸 테스트(api/client.test.ts)는 자기 beforeEach에서 다시 stub하므로
 * — 파일의 훅이 이 setup 훅보다 나중에 돈다 — 여기에 걸리지 않는다.
 */
const unmockedRequests: string[] = []

beforeEach(() => {
  unmockedRequests.length = 0
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = input instanceof Request ? input.url : String(input)
      unmockedRequests.push(`${init?.method ?? 'GET'} ${url}`)
      return Promise.reject(new Error(`모킹되지 않은 요청: ${url}`))
    }),
  )
})

afterEach(() => {
  const requests = [...unmockedRequests]
  unmockedRequests.length = 0
  if (requests.length > 0) {
    throw new Error(
      `테스트가 모킹되지 않은 네트워크 요청을 보냈다:\n  ${requests.join('\n  ')}\n` +
        '해당 API 함수를 이 테스트 파일에서 모킹하라 (vi.mock).',
    )
  }
})
