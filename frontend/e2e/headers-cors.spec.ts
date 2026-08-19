/**
 * E10·E11 — 전역 사적 응답 헤더의 **브라우저 도달**과 CORS 프리플라이트.
 *
 * 이 둘은 계획 §3-6 축 1 이 「계약 파일에서 읽어도 되는 값」으로 지목한 자리다 —
 * 전역 응답 헤더 두 값(`x-global-response-headers.headers`)과 CORS 허용 origin·메서드
 * (`x-cors`). 계약 파일에서 그 값을 바꾸면 이 두 케이스가 빨개져야 한다.
 *
 * 관측은 [CdpNetworkLog] 로 한다 — `page.on('response')` 는 프리플라이트를 한 건도
 * 보고하지 않아(실측) 계약이 범위에 넣은 자리를 통째로 못 본다(`support/network.ts` 주석).
 */

import { expect, test } from '@playwright/test'

import { ROUTES, corsPolicy, globalResponseHeaders } from './contract'
import {
  API_BASE_URL,
  FRONTEND_ORIGIN,
  answerPrompt,
  newAccount,
  signUpAndLand,
  uniqueWorkspaceName,
  workspaceSelect,
} from './support/app'
import { CdpNetworkLog, headerValues } from './support/network'

/**
 * Fetch 표준의 CORS-safelisted response-header 이름들.
 *
 * 계약 값이 아니라 **브라우저 상수**다 — 노출 목록(`x-cors.expose_headers`)과 무관하게
 * 교차 출처에서 읽힌다. 여기 있는 헤더는 "페이지에서 못 읽는다"의 근거가 될 수 없다.
 */
const SAFELISTED_RESPONSE_HEADERS = new Set([
  'cache-control',
  'content-language',
  'content-length',
  'content-type',
  'expires',
  'last-modified',
  'pragma',
])

/** 쉼표로 이어진 헤더 값을 항목 목록으로 편다. */
function commaList(values: string[]): string[] {
  return values
    .join(',')
    .split(',')
    .map((item) => item.trim())
    .filter((item) => item !== '')
}

test.describe('전역 응답 헤더와 CORS', () => {
  test('E10 사적 응답 헤더가 프리플라이트까지 포함해 브라우저에 도달한다 (각각 1개씩)', async ({
    page,
  }) => {
    const log = await CdpNetworkLog.attach(page, API_BASE_URL)
    const required = globalResponseHeaders()

    // 가입·로그인·조회·생성·재조회를 한 번에 훑는다 — 성공 응답 여러 종을 한 실행에서 본다.
    await signUpAndLand(page, newAccount())
    await answerPrompt(page, '새로 만들기', uniqueWorkspaceName())
    await expect(workspaceSelect(page).locator('option')).toHaveCount(2)

    const observed = log.api()
    expect(observed.length).toBeGreaterThan(0)
    // 계약 `x-global-response-headers.applies_to` 가 CORS 프리플라이트를 명시적으로
    // 범위에 넣었다. 프리플라이트를 한 건도 못 본 관측은 그 요구보다 좁다.
    expect(log.preflights().length, '프리플라이트를 한 건도 관측하지 못했다').toBeGreaterThan(0)

    for (const entry of observed) {
      for (const [name, value] of required) {
        const values = headerValues(entry, name)
        // **개수까지 센다.** 컨트롤러의 `ResponseEntity` 와 전역 필터·밸브가 같은 헤더를
        // 쓰므로, 한쪽이 `add` 로 붙으면 `no-store, no-store` 가 나가 계약이 깨진다.
        // 기능은 멀쩡하고 계약만 깨지는 종류라 눈으로는 안 잡힌다.
        expect(values, `${entry.method} ${entry.path} ${entry.status} 의 \`${name}\``).toHaveLength(
          1,
        )
        expect(values[0]).toBe(value)
      }
    }

    // 위 단언을 **페이지 스크립트가 아니라 원시 응답 관측**으로 하는 이유를 같은 실행에서
    // 증명한다.
    //
    // 계획 §3-2 주의는 `Cache-Control` 을 교차 출처에서 못 읽는 예로 들었는데, 그것은
    // **사실이 아니다** — Fetch 표준의 CORS-safelisted response-header 목록에
    // `Cache-Control` 이 들어 있어 노출 목록 없이도 읽힌다. 못 읽는 것은
    // `X-Content-Type-Options` 쪽이다. 결론(원시 관측으로 재라)은 그대로 옳지만,
    // 근거가 반만 맞으면 다음 사람이 "그럼 페이지에서 읽으면 되겠네"로 간다.
    const readable = await page.evaluate(
      async ([base, names]) => {
        const response = await fetch(`${base as string}/health`)
        return (names as string[]).map((name) => [name, response.headers.get(name)] as const)
      },
      [API_BASE_URL, [...required.keys()]] as const,
    )
    const unreadable = readable.filter(
      ([name, value]) => value === null && !SAFELISTED_RESPONSE_HEADERS.has(name.toLowerCase()),
    )
    expect(
      unreadable.length,
      '요구 헤더가 전부 페이지 스크립트로 읽히면 이 케이스의 관측 방식 근거가 사라진다',
    ).toBeGreaterThan(0)
  })

  test('E11 PATCH 가 프리플라이트를 유발하고, 응답이 계약 CORS 정책과 같다', async ({ page }) => {
    const log = await CdpNetworkLog.attach(page, API_BASE_URL)
    const policy = corsPolicy()

    // 브라우저가 보는 출처가 계약의 허용 목록에 있어야 이 경로가 성립한다.
    // 계약에서 이 값을 빼면 여기서 먼저 빨개진다.
    expect(policy.allowOrigins).toContain(FRONTEND_ORIGIN)

    await signUpAndLand(page, newAccount())
    const renamed = uniqueWorkspaceName('R')
    await answerPrompt(page, '이름 바꾸기', renamed)
    // 이름이 실제로 바뀔 때까지 기다린다. 항목 **개수**로 기다리면 이름 변경은 개수를
    // 바꾸지 않아 단언이 즉시 참이 되고, PATCH 가 끝나기도 전에 관측을 읽게 된다.
    await expect(workspaceSelect(page).locator('option')).toHaveText([renamed])

    const preflights = log.preflights()
    expect(preflights.length, '브라우저가 프리플라이트를 보내지 않았다').toBeGreaterThan(0)

    // PATCH 는 단순 요청이 아니라 반드시 프리플라이트를 유발한다.
    const patchPreflight = preflights.find((entry) => entry.path.startsWith('/workspaces/'))
    expect(patchPreflight, 'PATCH 가 유발한 프리플라이트가 없다').toBeDefined()
    if (patchPreflight === undefined) {
      return
    }

    expect(patchPreflight.status).toBe(200)
    expect(headerValues(patchPreflight, 'Access-Control-Allow-Origin')).toEqual([FRONTEND_ORIGIN])

    const allowedMethods = commaList(headerValues(patchPreflight, 'Access-Control-Allow-Methods'))
    expect(new Set(allowedMethods)).toEqual(new Set(policy.allowMethods))

    // 허용 요청 헤더는 브라우저가 물어본 것만 되돌아온다(Spring 의 `DefaultCorsProcessor`).
    // 그래서 같은지가 아니라 **계약의 부분집합인지**를 본다.
    const allowedHeaders = commaList(
      headerValues(patchPreflight, 'Access-Control-Allow-Headers'),
    ).map((header) => header.toLowerCase())
    const contractHeaders = new Set(policy.allowHeaders.map((header) => header.toLowerCase()))
    expect(allowedHeaders.length).toBeGreaterThan(0)
    for (const header of allowedHeaders) {
      expect(contractHeaders, `프리플라이트가 계약에 없는 헤더를 허용했다: ${header}`).toContain(
        header,
      )
    }

    // 프리플라이트가 막혔다면 본 요청이 나가지 못한다 — 실제로 통과했는지 확인한다.
    const patch = log.calls().filter((entry) => entry.method === ROUTES.workspaceRename.method)
    expect(patch).toHaveLength(1)
    expect(patch[0]?.status).toBe(ROUTES.workspaceRename.ok)
  })
})
