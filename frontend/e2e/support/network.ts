/**
 * 네트워크 관측 — 판정의 절반.
 *
 * 계획 §1-2: *"「통과」의 판정 기준은 화면 상태 + 네트워크 관측 둘 다다. 화면만 보면
 * 서버가 계약을 어겨도 화면이 우연히 맞는 경우를 놓치고, 네트워크만 보면 `client.ts`·
 * Provider 가 응답을 잘못 소비하는 경우를 놓친다."*
 *
 * **원시 헤더를 읽는다.** 페이지 스크립트의 `response.headers.get()` 으로는 교차 출처에서
 * `X-Content-Type-Options` 가 보이지 않는다 — CORS 노출 목록에도 Fetch 표준의
 * safelist 에도 없기 때문이다. 그 구분을 놓치면 계약을 지키는 서버에 대해 테스트가
 * 빨개지고, 그 빨강을 고치겠다고 `expose_headers` 를 넓히는 **계약 개악**으로 이어진다
 * (계획 §3-2 주의).
 *
 * ## 관측 통로가 둘인 이유 — 프리플라이트는 페이지 이벤트에 안 나온다 (실측)
 *
 * [NetworkLog] 는 Playwright 의 `page.on('response')` 를 쓴다. 간단하고 순서가 보존되지만
 * **CORS 프리플라이트(OPTIONS)를 한 건도 보고하지 않는다** — 2026-08-19 실측: PATCH 흐름을
 * 돌려 `preflights()` 가 0건. 프리플라이트는 페이지가 아니라 네트워크 스택이 보내므로
 * 페이지 수준 이벤트에 실리지 않는다.
 *
 * 그래서 [CdpNetworkLog] 를 따로 둔다. Chrome DevTools Protocol 의 `Network.*` 는
 * 프리플라이트를 `type: "Preflight"` 로 **분리해서** 보고하고
 * (`Network.responseReceivedExtraInfo` 가 원시 헤더까지 준다), 그것이 E10·E11 이
 * 프리플라이트 응답까지 보게 하는 유일한 통로다. 계약 `x-global-response-headers.applies_to`
 * 가 프리플라이트를 **명시적으로 범위에 넣었으므로**, 그 자리를 못 보는 관측은 선언한
 * 범위보다 좁다.
 */

import type { CDPSession, Page } from '@playwright/test'

/** 관측한 응답 한 건. `headers` 는 중복까지 보존한 원시 목록이다. */
export interface ObservedResponse {
  readonly method: string
  readonly url: string
  /** API 출처를 걷어낸 경로(+쿼리). */
  readonly path: string
  readonly status: number
  readonly headers: readonly { name: string; value: string }[]
}

/** `METHOD /path 상태` 한 줄 — 순서 단언의 비교 단위다. */
export function signature(entry: ObservedResponse): string {
  return `${entry.method} ${entry.path} ${entry.status}`
}

/** 헤더 이름은 대소문자를 가리지 않는다. 같은 이름이 몇 번 실렸는지까지 센다. */
export function headerValues(entry: ObservedResponse, name: string): string[] {
  const wanted = name.toLowerCase()
  return entry.headers.filter((header) => header.name.toLowerCase() === wanted).map((h) => h.value)
}

/**
 * 한 페이지에서 오간 응답을 모은다.
 *
 * `headersArray()` 가 비동기라 즉시 배열로 못 담는다 — 약속을 쌓아 두고 단언 시점에
 * 한꺼번에 기다린다. 그래야 이벤트 순서(= 응답 도착 순서)가 보존된다.
 */
export class NetworkLog {
  private readonly pending: Promise<ObservedResponse>[] = []
  private readonly issued: { method: string; url: string }[] = []
  // 생성자 파라미터 프로퍼티를 쓰지 않는다 — `erasableSyntaxOnly` 가 금지한다
  // (타입만 지워서는 실행 의미가 보존되지 않는 문법이다).
  private readonly apiBaseUrl: string

  constructor(page: Page, apiBaseUrl: string) {
    this.apiBaseUrl = apiBaseUrl
    // 응답이 아니라 **요청**을 세는 자리가 따로 필요하다 — E12 는 "호출이 아예 나가지
    // 않는다"를 재는데, 나갔다가 CORS·연결 실패로 응답이 없으면 응답 목록만 보는 판정은
    // 그것을 통과시킨다.
    page.on('request', (request) => {
      this.issued.push({ method: request.method(), url: request.url() })
    })
    page.on('response', (response) => {
      const url = response.url()
      this.pending.push(
        response
          .headersArray()
          .then((headers) => ({
            method: response.request().method(),
            url,
            path: url.startsWith(apiBaseUrl) ? url.slice(apiBaseUrl.length) : url,
            status: response.status(),
            headers,
          }))
          // 페이지가 닫히는 중이면 헤더를 못 읽을 수 있다. 관측 실패를 통과로 바꾸지
          // 않으려고 상태만이라도 남긴다(헤더 0개는 E10 에서 그대로 빨강이 된다).
          .catch(() => ({
            method: response.request().method(),
            url,
            path: url.startsWith(apiBaseUrl) ? url.slice(apiBaseUrl.length) : url,
            status: response.status(),
            headers: [],
          })),
      )
    })
  }

  /** 지금까지 관측한 전부(정적 자원 포함). */
  async all(): Promise<ObservedResponse[]> {
    return Promise.all(this.pending)
  }

  /** API 출처로 나간 것만. 프리플라이트(OPTIONS)도 포함한다. */
  async api(): Promise<ObservedResponse[]> {
    return (await this.all()).filter((entry) => entry.url.startsWith(this.apiBaseUrl))
  }

  /** 프리플라이트를 걷어낸 본 요청만 — 순서 단언은 이쪽으로 한다. */
  async apiCalls(): Promise<ObservedResponse[]> {
    return (await this.api()).filter((entry) => entry.method !== 'OPTIONS')
  }

  /** 프리플라이트만. 브라우저가 실제로 보냈는지 자체가 관측 대상이다(E11). */
  async preflights(): Promise<ObservedResponse[]> {
    return (await this.api()).filter((entry) => entry.method === 'OPTIONS')
  }

  /** API 출처로 **나간** 요청(응답 도착 여부와 무관). */
  apiRequests(): { method: string; url: string }[] {
    return this.issued.filter((entry) => entry.url.startsWith(this.apiBaseUrl))
  }
}

/**
 * CDP 로 관측한다 — **프리플라이트를 포함한** 모든 응답과 그 원시 헤더.
 *
 * [NetworkLog] 가 못 보는 자리를 이쪽이 본다(위 모듈 주석). 두 통로를 하나로 합치지
 * 않는 이유는, 흐름 순서 단언(E1·E5·E8)이 이미 페이지 이벤트로 정확하게 돌고 있어
 * 바꿀 근거가 없기 때문이다. 이쪽은 **헤더와 프리플라이트 전용**이다.
 */
export class CdpNetworkLog {
  private readonly apiBaseUrl: string
  private readonly requests = new Map<string, { url: string; method: string }>()
  private readonly rawHeaders = new Map<string, Record<string, string>>()
  private readonly entries: ObservedResponse[] = []

  private constructor(apiBaseUrl: string) {
    this.apiBaseUrl = apiBaseUrl
  }

  /** CDP 세션을 열고 기록을 시작한다. `Network.enable` 이 비동기라 팩터리가 필요하다. */
  static async attach(page: Page, apiBaseUrl: string): Promise<CdpNetworkLog> {
    const log = new CdpNetworkLog(apiBaseUrl)
    const session: CDPSession = await page.context().newCDPSession(page)
    await session.send('Network.enable')

    session.on('Network.requestWillBeSent', (event) => {
      log.requests.set(event.requestId, {
        url: event.request.url,
        method: event.request.method,
      })
    })
    // 원시 헤더는 이쪽에만 온다 — 브라우저가 페이지에 가리는 헤더까지 그대로다.
    session.on('Network.responseReceivedExtraInfo', (event) => {
      log.rawHeaders.set(event.requestId, event.headers)
    })
    session.on('Network.responseReceived', (event) => {
      const request = log.requests.get(event.requestId)
      const url = event.response.url
      const headers = log.rawHeaders.get(event.requestId) ?? event.response.headers
      log.entries.push({
        method: request?.method ?? '',
        url,
        path: url.startsWith(apiBaseUrl) ? url.slice(apiBaseUrl.length) : url,
        status: event.response.status,
        headers: flattenCdpHeaders(headers),
      })
    })
    return log
  }

  /** API 출처로 나간 응답 전부(프리플라이트 포함). */
  api(): ObservedResponse[] {
    return this.entries.filter((entry) => entry.url.startsWith(this.apiBaseUrl))
  }

  /** 프리플라이트만. */
  preflights(): ObservedResponse[] {
    return this.api().filter((entry) => entry.method === 'OPTIONS')
  }

  /** 프리플라이트를 걷어낸 본 요청만. */
  calls(): ObservedResponse[] {
    return this.api().filter((entry) => entry.method !== 'OPTIONS')
  }
}

/**
 * CDP 의 헤더 표현(이름 → 값 매핑)을 이름·값 쌍 목록으로 편다.
 *
 * **같은 이름이 두 번 실리면 값이 개행으로 이어져 온다** — 그것이 중복 부착을 세는
 * 근거다. 매핑을 그대로 쓰면 `no-store, no-store` 같은 이중 부착이 한 건으로 보인다.
 */
function flattenCdpHeaders(headers: Record<string, string>): { name: string; value: string }[] {
  return Object.entries(headers).flatMap(([name, value]) =>
    value.split('\n').map((single) => ({ name, value: single })),
  )
}
