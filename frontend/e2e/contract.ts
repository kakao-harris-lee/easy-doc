/**
 * 계약 파일에서 **E2E 가 읽어도 되는 값만** 꺼낸다.
 *
 * 정본: `contracts/easy-doc-v1.yaml`. 계획 §3-6 축 1 이 읽을 값을 셋으로 못박았다 —
 * 전역 응답 헤더 두 값, CORS 허용 origin·메서드, 422 이름 규칙의 `detail` 문구.
 * **그 밖의 값은 읽지 않는다.** 계약을 통째로 파싱하면 이 스위트가 Kotlin 계약 테스트의
 * 약한 복제가 되고, E2E 고유의 값어치(브라우저·클라이언트를 통과해야만 드러나는 것)를
 * 잃는다.
 *
 * 읽지 않는 값(경로·상태 코드)은 [ROUTES] 에 **계약 키 경로 주석과 함께** 한 곳에 모은다.
 * 두 벌이 되는 것은 같지만, 어디를 보고 고쳐야 하는지가 파일에 적혀 있다.
 */

import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { parse } from 'yaml'

const CONTRACT_URL = new URL('../../contracts/easy-doc-v1.yaml', import.meta.url)

/** 계약 파일 경로 — 실패 메시지에 적어 어느 파일을 봐야 하는지 남긴다. */
export const CONTRACT_PATH = fileURLToPath(CONTRACT_URL)

let cached: unknown

function document(): unknown {
  cached ??= parse(readFileSync(CONTRACT_URL, 'utf8'))
  return cached
}

/**
 * 키 경로를 따라 값을 꺼낸다. **없으면 던진다** — 기본값으로 대체하면 계약이 구조를
 * 바꿨을 때 테스트가 조용히 옛 값으로 통과한다.
 */
function at(...path: readonly string[]): unknown {
  let node: unknown = document()
  const walked: string[] = []
  for (const key of path) {
    walked.push(key)
    if (typeof node !== 'object' || node === null || !(key in node)) {
      throw new Error(
        `계약에 \`${walked.join('.')}\` 가 없다 (${CONTRACT_PATH}). ` +
          '조항이 옮겨졌다면 이 접근자를 함께 고친다 — 기본값으로 대체하지 않는다.',
      )
    }
    node = (node as Record<string, unknown>)[key]
  }
  return node
}

function asString(value: unknown, label: string): string {
  if (typeof value !== 'string') {
    throw new Error(`계약 \`${label}\` 이 문자열이 아니다: ${JSON.stringify(value)}`)
  }
  return value
}

function asStringArray(value: unknown, label: string): string[] {
  if (!Array.isArray(value) || value.some((item) => typeof item !== 'string')) {
    throw new Error(`계약 \`${label}\` 이 문자열 배열이 아니다: ${JSON.stringify(value)}`)
  }
  return value as string[]
}

// --- 축 1 ① 전역 사적 응답 헤더 ------------------------------------------------
// `x-global-response-headers.headers` 가 **요구의 정본**이다(계약 529-533행 부근).
// 뒤따르는 `x-private-response-headers` 의 10곳 목록은 하한선이지 범위가 아니다.

/** 계약이 요구하는 전역 응답 헤더 이름 → 값. E10 이 브라우저 도달을 잰다. */
export function globalResponseHeaders(): ReadonlyMap<string, string> {
  const raw = at('x-global-response-headers', 'headers')
  if (typeof raw !== 'object' || raw === null) {
    throw new Error('계약 `x-global-response-headers.headers` 가 매핑이 아니다.')
  }
  const entries = Object.entries(raw as Record<string, unknown>)
  if (entries.length === 0) {
    throw new Error('계약 `x-global-response-headers.headers` 가 비었다 — 잴 것이 없다.')
  }
  return new Map(
    entries.map(([name, value]) => [
      name,
      asString(value, `x-global-response-headers.headers.${name}`),
    ]),
  )
}

// --- 축 1 ② CORS ---------------------------------------------------------------
// `x-cors` (계약 196-202행 부근). OpenAPI 문법으로 표현할 수 없어 확장 필드에 있다.

export interface CorsPolicy {
  readonly allowOrigins: readonly string[]
  readonly allowMethods: readonly string[]
  readonly allowHeaders: readonly string[]
}

/** 계약이 정한 CORS 정책. E11 이 프리플라이트 응답과 대조한다. */
export function corsPolicy(): CorsPolicy {
  return {
    allowOrigins: asStringArray(at('x-cors', 'allow_origins'), 'x-cors.allow_origins'),
    allowMethods: asStringArray(at('x-cors', 'allow_methods'), 'x-cors.allow_methods'),
    allowHeaders: asStringArray(at('x-cors', 'allow_headers'), 'x-cors.allow_headers'),
  }
}

// --- 축 1 ③ 422 이름 규칙 문구 --------------------------------------------------
// `POST /workspaces` 의 422 는 **예시가 있으므로** 문구를 계약에서 읽어 단언한다.
// 409(같은 이름)는 예시가 없다 — RD-6. 그 자리는 문자열을 단언하지 않는다.

/** 빈 이름으로 작업 공간을 만들 때 계약이 게시한 `detail` 문구. E7 이 화면 문구와 대조한다. */
export function emptyWorkspaceNameDetail(): string {
  return asString(
    at(
      'paths',
      '/workspaces',
      'post',
      'responses',
      '422',
      'content',
      'application/json',
      'examples',
      'empty',
      'value',
      'detail',
    ),
    'paths./workspaces.post.responses.422…examples.empty.value.detail',
  )
}

// --- 읽지 않는 값 — 계약 키 경로만 지목한다 -------------------------------------

/**
 * 이 스위트가 두드리는 경로와 기대 상태 코드.
 *
 * **계약에서 파싱하지 않는다**(계획 §3-6 축 1). 대신 계약의 어느 키가 정본인지 각 줄에
 * 적는다 — 갈리면 Kotlin 계약 테스트가 먼저 빨개지고, 여기는 그 뒤에 고친다.
 */
export const ROUTES = {
  /** 계약 `paths./auth/signup.post` — 201, `security: []`. */
  signup: { path: '/auth/signup', method: 'POST', created: 201 },
  /** 계약 `paths./auth/login.post` — 200, `security: []`. */
  login: { path: '/auth/login', method: 'POST', ok: 200 },
  /** 계약 `paths./auth/me.get` — 200, Bearer 필요. */
  me: { path: '/auth/me', method: 'GET', ok: 200 },
  /** 계약 `paths./workspaces.get` — 200. 정렬(만든 순)이 계약이다. */
  workspaceList: { path: '/workspaces', method: 'GET', ok: 200 },
  /** 계약 `paths./workspaces.post` — 201 · 409(같은 이름) · 422(빈 이름). */
  workspaceCreate: { path: '/workspaces', method: 'POST', created: 201, conflict: 409 },
  /** 계약 `paths./workspaces/{workspace_id}.patch` — 200. **PUT 이 아니다.** */
  workspaceRename: { method: 'PATCH', ok: 200 },
  /** 계약 `components/responses/Unauthorized` — 401. */
  unauthorized: 401,
  /** 계약 `components/responses/ValidationFailed` — 422. */
  unprocessable: 422,
  /** 계약 `paths./documents.post` — 202 (변환은 아직 시작 전). */
  documentCreate: { path: '/documents', method: 'POST', accepted: 202 },
  /** 계약 `paths./conversions/{conversion_id}.get` — 200. */
  conversionRead: { method: 'GET', ok: 200 },
  /** 계약 `paths./conversions/{conversion_id}.put` — 200. */
  conversionReview: { method: 'PUT', ok: 200 },
  /** 계약 `paths./conversions/{conversion_id}/feedback.put` — 200 (멱등 upsert). */
  conversionFeedback: { method: 'PUT', ok: 200 },
  /** 계약 `paths./conversions/{conversion_id}/export.get` — 200 (파일). */
  conversionExport: { method: 'GET', ok: 200 },
} as const
